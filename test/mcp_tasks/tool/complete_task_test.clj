(ns mcp-tasks.tool.complete-task-test
  (:require
    [babashka.fs :as fs]
    [cheshire.core :as json]
    [clojure.java.shell]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.test-helpers :as h]
    [mcp-tasks.tool.complete-task :as sut]
    [mcp-tasks.tool.work-on]
    [mcp-tasks.tools.git]
    [mcp-tasks.tools.helpers]))

;; Git helper functions

;; complete-task tests

(deftest moves-first-task-from-tasks-to-complete
  (h/with-test-setup [test-dir]
    ;; Tests that the complete-task-impl function correctly moves the first
    ;; task from tasks.ednl to complete.ednl
    (testing "complete-task"
      (testing "moves first task from tasks to complete"
        ;; Create EDNL file with two tasks
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1
            :parent-id nil
            :title "first task"
            :description "detail line"
            :design ""
            :category "test"
            :type :task
            :status :open
            :meta {}
            :relations []}
           {:id 2
            :parent-id nil
            :title "second task"
            :description ""
            :design ""
            :category "test"
            :type :task
            :status :open
            :meta {}
            :relations []}])
        (let [result (#'sut/complete-task-impl
                      (h/test-config test-dir)
                      nil
                      {:category "test"
                       :title "first task"})]
          (is (false? (:isError result)))
          ;; Verify complete file has the completed task
          (let [complete-tasks (h/read-ednl-test-file test-dir "complete.ednl")]
            (is (= 1 (count complete-tasks)))
            (is (= "first task" (:title (first complete-tasks))))
            (is (= :closed (:status (first complete-tasks)))))
          ;; Verify tasks file has only the second task
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")]
            (is (= 1 (count tasks)))
            (is (= "second task" (:title (first tasks))))))))))

(deftest adds-completion-comment-when-provided
  (h/with-test-setup [test-dir]
    ;; Tests that completion comments are appended to completed tasks
    (testing "complete-task"
      (testing "adds completion comment when provided"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "task with comment" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/complete-task-impl
                      (h/test-config test-dir)
                      nil
                      {:category "test"
                       :title "task with comment"
                       :completion-comment "Added feature X"})]
          (is (false? (:isError result)))
          (let [complete-tasks (h/read-ednl-test-file test-dir "complete.ednl")]
            (is (= 1 (count complete-tasks)))
            (is (str/includes? (:description (first complete-tasks)) "Added feature X"))))))))

(deftest completes-task-by-id
  (h/with-test-setup [test-dir]
    ;; Tests that complete-task-impl can find and complete a task by exact ID
    (testing "complete-task"
      (testing "completes task by exact task-id"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "first task" :description "detail" :design "" :category "test" :type :task :status :open :meta {} :relations []}
           {:id 2 :parent-id nil :title "second task" :description "" :design "" :category "other" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/complete-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 2})]
          (is (false? (:isError result)))
          ;; Verify task 2 is complete
          (let [complete-tasks (h/read-ednl-test-file test-dir "complete.ednl")]
            (is (= 1 (count complete-tasks)))
            (is (= "second task" (:title (first complete-tasks))))
            (is (= :closed (:status (first complete-tasks)))))
          ;; Verify task 1 remains in tasks
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")]
            (is (= 1 (count tasks)))
            (is (= "first task" (:title (first tasks))))))))))

(deftest completes-task-by-exact-title
  (h/with-test-setup [test-dir]
    ;; Tests that complete-task-impl finds tasks by exact title match
    (testing "complete-task"
      (testing "completes task by exact title match"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1
            :parent-id nil
            :title "first task"
            :description "detail"
            :design ""
            :category "test"
            :type :task
            :status :open
            :meta {}
            :relations []}
           {:id 2
            :parent-id nil
            :title "second task"
            :description ""
            :design ""
            :category "test"
            :type :task
            :status :open
            :meta {}
            :relations []}])
        (let [result (#'sut/complete-task-impl
                      (h/test-config test-dir)
                      nil
                      {:title "second task"})]
          (is (false? (:isError result)))
          ;; Verify second task is complete
          (let [complete-tasks (h/read-ednl-test-file test-dir "complete.ednl")]
            (is (= 1 (count complete-tasks)))
            (is (= "second task" (:title (first complete-tasks))))))))))

(deftest rejects-ambiguous-title
  (h/with-test-setup [test-dir]
    ;; Tests that complete-task-impl rejects when multiple tasks have the same title
    (testing "complete-task"
      (testing "rejects multiple tasks with same title"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "duplicate" :description "first" :design "" :category "test" :type :task :status :open :meta {} :relations []}
           {:id 2 :parent-id nil :title "duplicate" :description "second" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/complete-task-impl
                      (h/test-config test-dir)
                      nil
                      {:title "duplicate"})]
          (is (true? (:isError result)))
          (is (str/includes?
                (get-in result [:content 0 :text])
                "Multiple tasks found")))))))

(deftest verifies-id-and-text-match
  (h/with-test-setup [test-dir]
    ;; Tests that when both task-id and title are provided, they must refer to the same task
    (testing "complete-task"
      (testing "verifies task-id and title refer to same task"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "first task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}
           {:id 2 :parent-id nil :title "second task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        ;; Mismatched ID and text
        (let [result (#'sut/complete-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :title "second task"})]
          (is (true? (:isError result)))
          (is (str/includes? (get-in result [:content 0 :text]) "do not refer to the same task")))
        ;; Matching ID and text
        (let [result (#'sut/complete-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 2 :title "second task"})]
          (is (false? (:isError result))))))))

(deftest requires-at-least-one-identifier
  (h/with-test-setup [test-dir]
    ;; Tests that either task-id or title must be provided
    (testing "complete-task"
      (testing "requires either task-id or title"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/complete-task-impl
                      (h/test-config test-dir)
                      nil
                      {})]
          (is (true? (:isError result)))
          (is (str/includes? (get-in result [:content 0 :text]) "Must provide either")))))))

(deftest completes-story-child-without-archiving
  (h/with-test-setup [test-dir]
    ;; Tests that completing a story child task keeps it in tasks.ednl with :status :closed
    ;; and does NOT move it to complete.ednl
    (testing "complete-task"
      (testing "story child remains in tasks.ednl, closed, not archived"
        ;; Prepare two tasks: one story, one child
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 10 :parent-id nil :title "Parent story" :description "" :design "" :category "story" :type :story :status :open :meta {} :relations []}
           {:id 11 :parent-id 10 :title "Child task" :description "" :design "" :category "simple" :type :task :status :open :meta {} :relations []}])
        ;; Invoke completion on the child
        (let [result (#'sut/complete-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 11})]
          (is (false? (:isError result)))
          ;; Verify message doesn't say "moved to"
          (is (str/includes? (get-in result [:content 0 :text]) "Task 11 completed"))
          (is (not (str/includes? (get-in result [:content 0 :text]) "moved to")))
          ;; tasks.ednl: child turns :closed but stays in file
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")]
            (is (= 2 (count tasks)))
            (is (= :open (:status (first tasks))))
            (is (= :closed (:status (second tasks))))
            (is (= 11 (:id (second tasks)))))
          ;; complete.ednl remains empty
          (is (empty? (h/read-ednl-test-file test-dir "complete.ednl"))))))))

(deftest complete-task-returns-three-content-items-with-git
  (h/with-test-setup [test-dir]
    ;; Tests that complete-task returns 3 content items when git is enabled
    (testing "complete-task with git enabled"
      (testing "returns three content items"
        (h/init-git-repo test-dir)
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "test task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/complete-task-impl
                      (h/git-test-config test-dir)
                      nil
                      {:task-id 1})]
          (is (false? (:isError result)))
          (is (= 3 (count (:content result))))

          ;; First content item: completion message
          (let [text-content (first (:content result))]
            (is (= "text" (:type text-content)))
            (is (str/includes? (:text text-content) "Task 1 completed")))

          ;; Second content item: modified files
          (let [files-content (second (:content result))
                files-data (json/parse-string (:text files-content) keyword)]
            (is (= "text" (:type files-content)))
            (is (contains? files-data :modified-files))
            (is (= 2 (count (:modified-files files-data)))))

          ;; Third content item: git status
          (let [git-content (nth (:content result) 2)
                git-data (json/parse-string (:text git-content) keyword)]
            (is (= "text" (:type git-content)))
            (is (contains? git-data :git-status))
            (is (contains? git-data :git-commit))))))))

(deftest complete-task-returns-one-content-item-without-git
  (h/with-test-setup [test-dir]
    ;; Tests that complete-task returns 2 content items when git is disabled
    (testing "complete-task with git disabled"
      (testing "returns two content items (message and task data)"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "test task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/complete-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1})]
          (is (false? (:isError result)))
          (is (= 2 (count (:content result))))

          ;; First content item: completion message
          (let [text-content (first (:content result))]
            (is (= "text" (:type text-content)))
            (is (str/includes? (:text text-content) "Task 1 completed")))

          ;; Second content item: task data as JSON
          (let [json-content (second (:content result))
                data (json/parse-string (:text json-content) keyword)]
            (is (= "text" (:type json-content)))
            (is (map? (:task data)))
            (is (= 1 (get-in data [:task :id])))
            (is (= "closed" (get-in data [:task :status])))))))))

(deftest ^:integration complete-task-creates-git-commit
  ;; Integration test verifying git commit is actually created
  (testing "complete-task with git enabled"
    (h/with-test-setup [test-dir]
      (testing "creates git commit with correct message"
        (h/init-git-repo test-dir)
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 42 :parent-id nil :title "implement feature X" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])

        ;; Complete the task
        (let [result (#'sut/complete-task-impl
                      (h/git-test-config test-dir)
                      nil
                      {:task-id 42})]
          (is (false? (:isError result)))

          ;; Verify git commit was created
          (is (h/git-commit-exists? test-dir))

          ;; Verify commit message format
          (let [commit-msg (h/git-log-last-commit test-dir)]
            (is (= "Complete task #42: implement feature X" commit-msg)))

          ;; Verify git status in response
          (let [git-content (nth (:content result) 2)
                git-data (json/parse-string (:text git-content) keyword)]
            (is (= "success" (:git-status git-data)))
            (is (string? (:git-commit git-data)))
            (is (= 40 (count (:git-commit git-data)))) ; SHA is 40 chars
            (is (nil? (:git-error git-data)))))))))

(deftest ^:integration complete-task-succeeds-despite-git-failure
  ;; Tests that task completion succeeds even when git operations fail
  (testing "complete-task with git enabled but no git repo"
    (h/with-test-setup [test-dir]
      (testing "task completes successfully despite git error"
        ;; Do not initialize git repo - this will cause git operations to fail
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "test task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])

        (let [result (#'sut/complete-task-impl
                      (h/git-test-config test-dir)
                      nil
                      {:task-id 1})]
          ;; Task completion should succeed
          (is (false? (:isError result)))

          ;; Verify task was actually completed
          (let [complete-tasks (h/read-ednl-test-file test-dir "complete.ednl")]
            (is (= 1 (count complete-tasks)))
            (is (= "test task" (:title (first complete-tasks)))))

          ;; Verify git error is reported in response
          (let [git-content (nth (:content result) 2)
                git-data (json/parse-string (:text git-content) keyword)]
            (is (= "error" (:git-status git-data)))
            (is (nil? (:git-commit git-data)))
            (is (string? (:git-error git-data)))
            (is (not (str/blank? (:git-error git-data))))))))))

(deftest ^:integration complete-task-git-commit-sha-format
  ;; Tests that git commit SHA is returned in correct format
  (testing "complete-task with git enabled"
    (h/with-test-setup [test-dir]
      (testing "returns valid git commit SHA"
        (h/init-git-repo test-dir)
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 99 :parent-id nil :title "task title" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])

        (let [result (#'sut/complete-task-impl
                      (h/git-test-config test-dir)
                      nil
                      {:task-id 99})
              git-content (nth (:content result) 2)
              git-data (json/parse-string (:text git-content) keyword)
              sha (:git-commit git-data)]

          ;; Verify SHA format
          (is (string? sha))
          (is (= 40 (count sha)))
          (is (re-matches #"[0-9a-f]{40}" sha)))))))

(deftest ^:integration completes-story-child-with-git
  ;; Tests that completing a story child with git only modifies tasks.ednl
  (testing "complete-task with git"
    (h/with-test-setup [test-dir]
      (testing "only tasks.ednl is modified for story child"
        (h/init-git-repo test-dir)
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 20 :parent-id nil :title "Story" :description "" :design "" :category "story" :type :story :status :open :meta {} :relations []}
           {:id 21 :parent-id 20 :title "Child" :description "" :design "" :category "simple" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/complete-task-impl
                      (h/git-test-config test-dir)
                      nil
                      {:task-id 21})]
          (is (false? (:isError result)))
          ;; Expect 3 content items
          (is (= 3 (count (:content result))))

          ;; First item: message doesn't say "moved to"
          (is (str/includes? (get-in result [:content 0 :text]) "Task 21 completed"))
          (is (not (str/includes? (get-in result [:content 0 :text]) "moved to")))

          ;; Second item: modified-files contains only tasks.ednl
          (let [files-data (json/parse-string (get-in result [:content 1 :text]) keyword)]
            (is (= ["tasks.ednl"] (:modified-files files-data))))

          ;; Third item: has git-status and commit-sha
          (let [git-data (json/parse-string (get-in result [:content 2 :text]) keyword)]
            (is (= "success" (:git-status git-data)))
            (is (string? (:git-commit git-data))))

          ;; Verify git commit was created
          (is (h/git-commit-exists? test-dir))

          ;; Verify task stayed in tasks.ednl with :status :closed
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")]
            (is (= 2 (count tasks)))
            (is (= :closed (:status (second tasks)))))

          ;; Verify complete.ednl is still empty
          (is (empty? (h/read-ednl-test-file test-dir "complete.ednl"))))))))

(deftest completes-story-with-unclosed-children-returns-error
  (h/with-test-setup [test-dir]
    ;; Tests that attempting to complete a story with unclosed children returns an error
    (testing "complete-task"
      (testing "returns error when story has unclosed children"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 30 :parent-id nil :title "My Story" :description "" :design "" :category "story" :type :story :status :open :meta {} :relations []}
           {:id 31 :parent-id 30 :title "Child 1" :description "" :design "" :category "simple" :type :task :status :open :meta {} :relations []}
           {:id 32 :parent-id 30 :title "Child 2" :description "" :design "" :category "simple" :type :task :status :closed :meta {} :relations []}
           {:id 33 :parent-id 30 :title "Child 3" :description "" :design "" :category "simple" :type :task :status :in-progress :meta {} :relations []}])

        (let [result (#'sut/complete-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 30})]
          ;; Verify error response
          (is (true? (:isError result)))
          (is (= 2 (count (:content result))))

          ;; First content: error message
          (let [msg (get-in result [:content 0 :text])]
            (is (str/includes? msg "Cannot complete story"))
            (is (str/includes? msg "2 child tasks"))
            (is (str/includes? msg "not closed")))

          ;; Second content: error metadata with unclosed children
          (let [error-data (json/parse-string (get-in result [:content 1 :text]) keyword)]
            (is (= "Cannot complete story: 2 child tasks still are not closed" (:error error-data)))
            (is (= 30 (get-in error-data [:metadata :task-id])))
            (is (= 2 (count (get-in error-data [:metadata :blocking-children]))))
            ;; Verify unclosed children details
            (let [unclosed (get-in error-data [:metadata :blocking-children])]
              (is (some #(= 31 (:id %)) unclosed))
              (is (some #(= 33 (:id %)) unclosed))
              (is (every? #(not= :closed (:status %)) unclosed))))

          ;; Verify nothing was moved to complete.ednl
          (is (empty? (h/read-ednl-test-file test-dir "complete.ednl")))

          ;; Verify all tasks remain in tasks.ednl unchanged
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")]
            (is (= 4 (count tasks)))
            (is (= :open (:status (first tasks))))))))))

(deftest completes-story-with-all-children-closed
  (h/with-test-setup [test-dir]
    ;; Tests that completing a story with all children closed archives everything atomically
    (testing "complete-task"
      (testing "archives story and all children atomically when all closed"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 40 :parent-id nil :title "Complete Story" :description "Story desc" :design "" :category "story" :type :story :status :open :meta {} :relations []}
           {:id 41 :parent-id 40 :title "Child 1" :description "" :design "" :category "simple" :type :task :status :closed :meta {} :relations []}
           {:id 42 :parent-id 40 :title "Child 2" :description "" :design "" :category "simple" :type :task :status :closed :meta {} :relations []}])

        (let [result (#'sut/complete-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 40})]
          ;; Verify success response
          (is (false? (:isError result)))
          (is (= 2 (count (:content result))))

          ;; First content item: completion message
          (let [msg (get-in result [:content 0 :text])]
            (is (str/includes? msg "Story 40 completed and archived"))
            (is (str/includes? msg "with 2 child tasks")))

          ;; Second content item: task data as JSON
          (let [json-content (second (:content result))
                data (json/parse-string (:text json-content) keyword)]
            (is (= "text" (:type json-content)))
            (is (map? (:task data)))
            (is (= 40 (get-in data [:task :id])))
            (is (= "closed" (get-in data [:task :status]))))

          ;; Verify all tasks moved to complete.ednl
          (let [completed-tasks (h/read-ednl-test-file test-dir "complete.ednl")]
            (is (= 3 (count completed-tasks)))
            ;; Verify story is first
            (is (= 40 (:id (first completed-tasks))))
            (is (= :story (:type (first completed-tasks))))
            (is (= :closed (:status (first completed-tasks))))
            ;; Verify children follow
            (is (= 41 (:id (second completed-tasks))))
            (is (= 42 (:id (nth completed-tasks 2)))))

          ;; Verify tasks.ednl is now empty
          (is (empty? (h/read-ednl-test-file test-dir "tasks.ednl"))))))))

(deftest completes-story-with-deleted-children
  (h/with-test-setup [test-dir]
    ;; Tests that completing a story with deleted children succeeds
    ;; Deleted children should not block story completion
    (testing "complete-task"
      (testing "archives story when children include deleted status"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 50 :parent-id nil :title "Story with Deleted Children" :description "Story desc" :design "" :category "story" :type :story :status :open :meta {} :relations []}
           {:id 51 :parent-id 50 :title "Closed Child" :description "" :design "" :category "simple" :type :task :status :closed :meta {} :relations []}
           {:id 52 :parent-id 50 :title "Deleted Child" :description "" :design "" :category "simple" :type :task :status :deleted :meta {} :relations []}])

        (let [result (#'sut/complete-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 50})]
          ;; Verify success response
          (is (false? (:isError result)))
          (is (= 2 (count (:content result))))

          ;; First content item: completion message
          (let [msg (get-in result [:content 0 :text])]
            (is (str/includes? msg "Story 50 completed and archived"))
            (is (str/includes? msg "with 2 child tasks")))

          ;; Second content item: task data as JSON
          (let [json-content (second (:content result))
                data (json/parse-string (:text json-content) keyword)]
            (is (= "text" (:type json-content)))
            (is (map? (:task data)))
            (is (= 50 (get-in data [:task :id])))
            (is (= "closed" (get-in data [:task :status]))))

          ;; Verify all tasks moved to complete.ednl
          (let [completed-tasks (h/read-ednl-test-file test-dir "complete.ednl")]
            (is (= 3 (count completed-tasks)))
            ;; Verify story is first
            (is (= 50 (:id (first completed-tasks))))
            (is (= :story (:type (first completed-tasks))))
            (is (= :closed (:status (first completed-tasks))))
            ;; Verify children follow (both closed and deleted)
            (is (= 51 (:id (second completed-tasks))))
            (is (= :closed (:status (second completed-tasks))))
            (is (= 52 (:id (nth completed-tasks 2))))
            (is (= :deleted (:status (nth completed-tasks 2)))))

          ;; Verify tasks.ednl is now empty
          (is (empty? (h/read-ednl-test-file test-dir "tasks.ednl"))))))))

(deftest completes-story-with-no-children
  (h/with-test-setup [test-dir]
    ;; Tests that completing a story with no children archives it immediately
    (testing "complete-task"
      (testing "archives story immediately when it has no children"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 50 :parent-id nil :title "Empty Story" :description "" :design "" :category "story" :type :story :status :open :meta {} :relations []}])

        (let [result (#'sut/complete-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 50})]
          ;; Verify success response
          (is (false? (:isError result)))

          ;; Verify completion message doesn't mention children
          (let [msg (get-in result [:content 0 :text])]
            (is (str/includes? msg "Story 50 completed and archived"))
            (is (not (str/includes? msg "child"))))

          ;; Verify story moved to complete.ednl
          (let [completed-tasks (h/read-ednl-test-file test-dir "complete.ednl")]
            (is (= 1 (count completed-tasks)))
            (is (= 50 (:id (first completed-tasks))))
            (is (= :closed (:status (first completed-tasks)))))

          ;; Verify tasks.ednl is now empty
          (is (empty? (h/read-ednl-test-file test-dir "tasks.ednl"))))))))

(deftest ^:integration completes-story-with-git-creates-commit
  ;; Tests that completing a story with git creates a commit with custom message
  (testing "complete-task with git"
    (h/with-test-setup [test-dir]
      (testing "creates commit with story-specific message and child count"
        (h/init-git-repo test-dir)
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 60 :parent-id nil :title "Git Story" :description "" :design "" :category "story" :type :story :status :open :meta {} :relations []}
           {:id 61 :parent-id 60 :title "Child A" :description "" :design "" :category "simple" :type :task :status :closed :meta {} :relations []}
           {:id 62 :parent-id 60 :title "Child B" :description "" :design "" :category "simple" :type :task :status :closed :meta {} :relations []}
           {:id 63 :parent-id 60 :title "Child C" :description "" :design "" :category "simple" :type :task :status :closed :meta {} :relations []}])

        (let [result (#'sut/complete-task-impl
                      (h/git-test-config test-dir)
                      nil
                      {:task-id 60})]
          (is (false? (:isError result)))
          (is (= 3 (count (:content result))))

          ;; Verify completion message
          (let [msg (get-in result [:content 0 :text])]
            (is (str/includes? msg "Story 60 completed and archived"))
            (is (str/includes? msg "with 3 child tasks")))

          ;; Verify modified files includes both tasks.ednl and complete.ednl
          (let [files-data (json/parse-string (get-in result [:content 1 :text]) keyword)]
            (is (= ["tasks.ednl" "complete.ednl"] (:modified-files files-data))))

          ;; Verify git status is success
          (let [git-data (json/parse-string (get-in result [:content 2 :text]) keyword)]
            (is (= "success" (:git-status git-data)))
            (is (string? (:git-commit git-data)))
            (is (= 40 (count (:git-commit git-data)))))

          ;; Verify git commit was created with correct message
          (is (h/git-commit-exists? test-dir))
          (let [commit-msg (h/git-log-last-commit test-dir)]
            (is (= "Complete story #60: Git Story (with 3 tasks)" commit-msg)))

          ;; Verify all tasks archived
          (let [completed-tasks (h/read-ednl-test-file test-dir "complete.ednl")]
            (is (= 4 (count completed-tasks))))
          (is (empty? (h/read-ednl-test-file test-dir "tasks.ednl"))))))))

;; Execution State Clearing Tests

(deftest complete-regular-task-clears-execution-state
  (h/with-test-setup [test-dir]
    ;; Tests that completing a regular task automatically clears the execution state.
    (testing "complete-task"
      (testing "clears execution state when completing regular task"
        ;; Setup task
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1
            :parent-id nil
            :title "regular task"
            :description "test task"
            :design ""
            :category "test"
            :type :task
            :status :open
            :meta {}
            :relations []}])

        ;; Write execution state file to simulate task execution
        (let [state {:story-id nil
                     :task-id 1
                     :started-at "2025-10-20T14:30:00Z"}
              state-file (fs/file test-dir ".mcp-tasks-current.edn")]
          (spit state-file (pr-str state))
          (is (fs/exists? state-file)))

        ;; Complete the task
        (let [result (#'sut/complete-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1})]
          (is (false? (:isError result)))

          ;; Verify execution state file was cleared
          (let [state-file (fs/file test-dir ".mcp-tasks-current.edn")]
            (is (not (fs/exists? state-file)))))))))

(deftest complete-child-task-clears-execution-state
  (h/with-test-setup [test-dir]
    ;; Tests that completing a story child task automatically clears the execution state.
    (testing "complete-task"
      (testing "clears execution state when completing child task"
        ;; Setup story and child task
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 50
            :parent-id nil
            :title "story task"
            :description "parent story"
            :design ""
            :category "simple"
            :type :story
            :status :open
            :meta {}
            :relations []}
           {:id 51
            :parent-id 50
            :title "child task"
            :description "child of story"
            :design ""
            :category "test"
            :type :task
            :status :open
            :meta {}
            :relations []}])

        ;; Write execution state file to simulate task execution
        (let [state {:story-id 50
                     :task-id 51
                     :started-at "2025-10-20T14:30:00Z"}
              state-file (fs/file test-dir ".mcp-tasks-current.edn")]
          (spit state-file (pr-str state))
          (is (fs/exists? state-file)))

        ;; Complete the child task
        (let [result (#'sut/complete-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 51})]
          (is (false? (:isError result)))

          ;; Verify execution state file was cleared
          (let [state-file (fs/file test-dir ".mcp-tasks-current.edn")]
            (is (not (fs/exists? state-file)))))))))

(deftest complete-story-task-clears-execution-state
  (h/with-test-setup [test-dir]
    ;; Tests that completing a story task automatically clears the execution state.
    (testing "complete-task"
      (testing "clears execution state when completing story task"
        ;; Setup story with closed children
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 60
            :parent-id nil
            :title "story task"
            :description "parent story"
            :design ""
            :category "simple"
            :type :story
            :status :open
            :meta {}
            :relations []}
           {:id 61
            :parent-id 60
            :title "child task 1"
            :description "first child"
            :design ""
            :category "test"
            :type :task
            :status :closed
            :meta {}
            :relations []}
           {:id 62
            :parent-id 60
            :title "child task 2"
            :description "second child"
            :design ""
            :category "test"
            :type :task
            :status :closed
            :meta {}
            :relations []}])

        ;; Write execution state file to simulate task execution
        (let [state {:story-id nil
                     :task-id 60
                     :started-at "2025-10-20T14:30:00Z"}
              state-file (fs/file test-dir ".mcp-tasks-current.edn")]
          (spit state-file (pr-str state))
          (is (fs/exists? state-file)))

        ;; Complete the story task
        (let [result (#'sut/complete-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 60})]
          (is (false? (:isError result)))

          ;; Verify execution state file was cleared
          (let [state-file (fs/file test-dir ".mcp-tasks-current.edn")]
            (is (not (fs/exists? state-file)))))))))

(deftest ^:integration complete-task-syncs-with-git-pull
  ;; Integration test verifying complete-task calls sync-and-prepare-task-file
  ;; to pull latest changes before modification
  (testing "complete-task with git sync"
    (h/with-test-setup [test-dir]
      (testing "syncs with remote before completing task"
        (h/init-git-repo test-dir)
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 70 :parent-id nil :title "sync test task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])

        ;; Track whether sync-and-prepare-task-file was called
        (let [sync-called (atom false)
              original-prepare @#'mcp-tasks.tools.helpers/prepare-task-file]
          (with-redefs [mcp-tasks.tools.helpers/sync-and-prepare-task-file
                        (fn [config]
                          (reset! sync-called true)
                          ;; Simulate successful sync by calling prepare-task-file
                          (original-prepare config))]
            (let [result (#'sut/complete-task-impl
                          (h/git-test-config test-dir)
                          nil
                          {:task-id 70})]
              (is (false? (:isError result)))
              (is @sync-called "sync-and-prepare-task-file should be called")

              ;; Verify task was completed
              (let [complete-tasks (h/read-ednl-test-file test-dir "complete.ednl")]
                (is (= 1 (count complete-tasks)))
                (is (= 70 (:id (first complete-tasks))))
                (is (= :closed (:status (first complete-tasks))))))))

        (testing "returns error on pull conflicts"
          (with-redefs [mcp-tasks.tools.helpers/sync-and-prepare-task-file
                        (fn [_config]
                          {:success false
                           :error "CONFLICT (content): Merge conflict in tasks.ednl"
                           :error-type :conflict})]
            (h/write-ednl-test-file
              test-dir
              "tasks.ednl"
              [{:id 71 :parent-id nil :title "conflict test" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])

            (let [result (#'sut/complete-task-impl
                          (h/git-test-config test-dir)
                          nil
                          {:task-id 71})]
              (is (:isError result))
              (let [error-text (-> result :content first :text)]
                (is (str/includes? error-text "Pull failed with conflicts")))

              ;; Verify task was NOT completed due to sync error
              (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")]
                (is (= 1 (count tasks)))
                (is (= 71 (:id (first tasks))))
                (is (= :open (:status (first tasks))))))))

        (testing "returns error on network failure"
          (with-redefs [mcp-tasks.tools.helpers/sync-and-prepare-task-file
                        (fn [_config]
                          {:success false
                           :error "fatal: Could not resolve host: github.com"
                           :error-type :network})]
            (h/write-ednl-test-file
              test-dir
              "tasks.ednl"
              [{:id 72 :parent-id nil :title "network test" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])

            (let [result (#'sut/complete-task-impl
                          (h/git-test-config test-dir)
                          nil
                          {:task-id 72})]
              (is (:isError result))
              (let [error-text (-> result :content first :text)]
                (is (str/includes? error-text "Pull failed")))

              ;; Verify task was NOT completed due to sync error
              (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")]
                (is (= 1 (count tasks)))
                (is (= 72 (:id (first tasks))))
                (is (= :open (:status (first tasks))))))))

        (testing "continues normally when no remote configured"
          (with-redefs [mcp-tasks.tools.helpers/sync-and-prepare-task-file
                        (fn [config]
                          ;; Simulate no-remote scenario - still returns path
                          (@#'mcp-tasks.tools.helpers/prepare-task-file config))]
            (h/write-ednl-test-file
              test-dir
              "tasks.ednl"
              [{:id 73 :parent-id nil :title "no-remote test" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])

            (let [result (#'sut/complete-task-impl
                          (h/git-test-config test-dir)
                          nil
                          {:task-id 73})]
              (is (false? (:isError result)))

              ;; Verify task was completed normally
              (let [complete-tasks (h/read-ednl-test-file test-dir "complete.ednl")]
                (is (some #(= 73 (:id %)) complete-tasks))
                (is (some #(= :closed (:status %)) complete-tasks))))))))))

(deftest completes-task-and-cleans-up-worktree
  (h/with-test-setup [test-dir]
    ;; Tests that completing a task in a worktree triggers cleanup when worktree-management is enabled
    (testing "complete-task with worktree cleanup"
      (testing "removes worktree when in worktree with worktree-management enabled"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 80 :parent-id nil :title "worktree task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])

        (let [main-repo-dir "/main/repo"
              worktree-path test-dir
              cleanup-called (atom false)
              cleanup-args (atom nil)]
          (with-redefs [mcp-tasks.tools.git/in-worktree?
                        (fn [_] true)
                        mcp-tasks.tools.git/get-main-repo-dir
                        (fn [_] main-repo-dir)
                        mcp-tasks.tool.work-on/cleanup-worktree-after-completion
                        (fn [main-repo worktree config]
                          (reset! cleanup-called true)
                          (reset! cleanup-args {:main-repo main-repo
                                                :worktree worktree
                                                :config config})
                          {:success true
                           :message (str "Worktree removed at " worktree)
                           :error nil})]
            (let [result (#'sut/complete-task-impl
                          (assoc (h/test-config test-dir) :worktree-management? true)
                          nil
                          {:task-id 80})]
              (is (false? (:isError result)))
              (is @cleanup-called "cleanup-worktree-after-completion should be called")
              (is (= main-repo-dir (:main-repo @cleanup-args)))
              (is (= worktree-path (:worktree @cleanup-args)))
              (is (:worktree-management? (:config @cleanup-args)))

              ;; Verify message includes cleanup success
              (let [msg (get-in result [:content 0 :text])]
                (is (str/includes? msg "Task 80 completed"))
                (is (str/includes? msg "Worktree removed at"))
                (is (str/includes? msg "switch directories to continue"))))))))))

(deftest completes-task-without-cleanup-in-main-repo
  (h/with-test-setup [test-dir]
    ;; Tests that completing a task in main repo does NOT trigger cleanup
    (testing "complete-task without worktree cleanup"
      (testing "does not attempt cleanup when not in worktree"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 81 :parent-id nil :title "main repo task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])

        (let [cleanup-called (atom false)]
          (with-redefs [mcp-tasks.tools.git/in-worktree?
                        (fn [_] false)
                        mcp-tasks.tool.work-on/cleanup-worktree-after-completion
                        (fn [_ _ _]
                          (reset! cleanup-called true)
                          {:success true :message "Should not be called" :error nil})]
            (let [result (#'sut/complete-task-impl
                          (assoc (h/test-config test-dir) :worktree-management? true)
                          nil
                          {:task-id 81})]
              (is (false? (:isError result)))
              (is (not @cleanup-called) "cleanup should not be called in main repo")

              ;; Verify message does not include cleanup text
              (let [msg (get-in result [:content 0 :text])]
                (is (str/includes? msg "Task 81 completed"))
                (is (not (str/includes? msg "Worktree removed")))))))))))

(deftest completes-task-without-cleanup-when-disabled
  (h/with-test-setup [test-dir]
    ;; Tests that completing a task does NOT trigger cleanup when worktree-management is disabled
    (testing "complete-task without worktree cleanup"
      (testing "does not attempt cleanup when worktree-management disabled"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 82 :parent-id nil :title "worktree task no mgmt" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])

        (let [cleanup-called (atom false)]
          (with-redefs [mcp-tasks.tools.git/in-worktree?
                        (fn [_] true)
                        mcp-tasks.tools.git/get-main-repo-dir
                        (fn [_] "/main/repo")
                        mcp-tasks.tool.work-on/cleanup-worktree-after-completion
                        (fn [_ _ _]
                          (reset! cleanup-called true)
                          {:success true :message "Should not be called" :error nil})]
            (let [result (#'sut/complete-task-impl
                          (assoc (h/test-config test-dir) :worktree-management? false)
                          nil
                          {:task-id 82})]
              (is (false? (:isError result)))
              (is (not @cleanup-called) "cleanup should not be called when disabled")

              ;; Verify message does not include cleanup text
              (let [msg (get-in result [:content 0 :text])]
                (is (str/includes? msg "Task 82 completed"))
                (is (not (str/includes? msg "Worktree removed")))))))))))

(deftest completes-task-despite-worktree-cleanup-failure
  (h/with-test-setup [test-dir]
    ;; Tests that task completion succeeds even when worktree cleanup fails
    (testing "complete-task with worktree cleanup failure"
      (testing "completes task successfully but warns about cleanup failure"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 83 :parent-id nil :title "cleanup fail task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])

        (with-redefs [mcp-tasks.tools.git/in-worktree?
                      (fn [_] true)
                      mcp-tasks.tools.git/get-main-repo-dir
                      (fn [_] "/main/repo")
                      mcp-tasks.tool.work-on/cleanup-worktree-after-completion
                      (fn [_ _ _]
                        {:success false
                         :message nil
                         :error "Uncommitted changes exist in worktree"})]
          (let [result (#'sut/complete-task-impl
                        (assoc (h/test-config test-dir) :worktree-management? true)
                        nil
                        {:task-id 83})]
            (is (false? (:isError result)) "Task completion should succeed")

            ;; Verify task was actually completed
            (let [complete-tasks (h/read-ednl-test-file test-dir "complete.ednl")]
              (is (= 1 (count complete-tasks)))
              (is (= 83 (:id (first complete-tasks))))
              (is (= :closed (:status (first complete-tasks)))))

            ;; Verify message includes cleanup failure warning
            (let [msg (get-in result [:content 0 :text])]
              (is (str/includes? msg "Task 83 completed"))
              (is (str/includes? msg "Warning: Could not remove worktree"))
              (is (str/includes? msg "Uncommitted changes exist in worktree")))))))))

(deftest completes-story-child-with-worktree-cleanup
  (h/with-test-setup [test-dir]
    ;; Tests that completing a story child task does NOT trigger worktree cleanup
    (testing "complete-task child task with worktree cleanup"
      (testing "keeps worktree after completing child task"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 90 :parent-id nil :title "Story" :description "" :design "" :category "story" :type :story :status :open :meta {} :relations []}
           {:id 91 :parent-id 90 :title "Child" :description "" :design "" :category "simple" :type :task :status :open :meta {} :relations []}])

        (let [cleanup-called (atom false)]
          (with-redefs [mcp-tasks.tools.git/in-worktree?
                        (fn [_] true)
                        mcp-tasks.tools.git/get-main-repo-dir
                        (fn [_] "/main/repo")
                        mcp-tasks.tool.work-on/cleanup-worktree-after-completion
                        (fn [_ _ _]
                          (reset! cleanup-called true)
                          {:success true
                           :message "Worktree removed"
                           :error nil})]
            (let [result (#'sut/complete-task-impl
                          (assoc (h/test-config test-dir) :worktree-management? true)
                          nil
                          {:task-id 91})]
              (is (false? (:isError result)))
              (is (not @cleanup-called) "cleanup should NOT be called for child tasks")

              ;; Verify message indicates staying in worktree
              (let [msg (get-in result [:content 0 :text])]
                (is (str/includes? msg "Task 91 completed"))
                (is (str/includes? msg "staying in worktree for remaining story tasks"))))))))))

(deftest completes-story-with-worktree-cleanup
  (h/with-test-setup [test-dir]
    ;; Tests that completing a story triggers worktree cleanup
    (testing "complete-task story with worktree cleanup"
      (testing "removes worktree after completing story with all children closed"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 95 :parent-id nil :title "Complete Story" :description "" :design "" :category "story" :type :story :status :open :meta {} :relations []}
           {:id 96 :parent-id 95 :title "Child 1" :description "" :design "" :category "simple" :type :task :status :closed :meta {} :relations []}
           {:id 97 :parent-id 95 :title "Child 2" :description "" :design "" :category "simple" :type :task :status :closed :meta {} :relations []}])

        (let [cleanup-called (atom false)]
          (with-redefs [mcp-tasks.tools.git/in-worktree?
                        (fn [_] true)
                        mcp-tasks.tools.git/get-main-repo-dir
                        (fn [_] "/main/repo")
                        mcp-tasks.tool.work-on/cleanup-worktree-after-completion
                        (fn [_ _ _]
                          (reset! cleanup-called true)
                          {:success true
                           :message "Worktree removed"
                           :error nil})]
            (let [result (#'sut/complete-task-impl
                          (assoc (h/test-config test-dir) :worktree-management? true)
                          nil
                          {:task-id 95})]
              (is (false? (:isError result)))
              (is @cleanup-called "cleanup should be called for story tasks")

              ;; Verify message includes cleanup success
              (let [msg (get-in result [:content 0 :text])]
                (is (str/includes? msg "Story 95 completed and archived"))
                (is (str/includes? msg "Worktree removed"))))))))))

(deftest completes-task-from-within-worktree-being-removed
  ;; Integration test for self-removal scenario where complete-task is called
  ;; from within the worktree being cleaned up
  (testing "complete-task from within worktree being removed"
    (testing "successfully removes worktree when called from within it"
      (h/with-test-setup [test-dir]
        ;; Create main repo with a remote
        (let [remote-dir (fs/create-temp-dir {:prefix "mcp-tasks-remote-"})
              ;; Initialize remote repo
              _ (clojure.java.shell/sh "git" "init" "--bare" :dir (str remote-dir))

              ;; Initialize local repo
              _ (clojure.java.shell/sh "git" "init" :dir (str test-dir))
              _ (clojure.java.shell/sh "git" "config" "user.email" "test@test.com" :dir (str test-dir))
              _ (clojure.java.shell/sh "git" "config" "user.name" "Test User" :dir (str test-dir))
              _ (clojure.java.shell/sh "git" "-C" (str test-dir) "remote" "add" "origin" (str remote-dir))

              ;; Create initial commit in local
              initial-file (str test-dir "/initial.txt")
              _ (spit initial-file "initial")
              _ (clojure.java.shell/sh "git" "-C" (str test-dir) "add" "initial.txt")
              _ (clojure.java.shell/sh "git" "-C" (str test-dir) "commit" "-m" "Initial commit")

              ;; Push to remote
              _ (clojure.java.shell/sh "git" "-C" (str test-dir) "push" "-u" "origin" "master")

              ;; Create a worktree with a new branch based on master (which is pushed)
              worktree-path (str test-dir "-worktree")
              _ (clojure.java.shell/sh "git" "-C" (str test-dir) "worktree" "add" "-b" "test-branch" worktree-path "master")

              ;; Add a task in the worktree
              _ (h/write-ednl-test-file
                  worktree-path
                  "tasks.ednl"
                  [{:id 100 :parent-id nil :title "worktree task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])

              ;; Commit the task in the worktree
              _ (clojure.java.shell/sh "git" "-C" worktree-path "add" ".mcp-tasks/tasks.ednl")
              _ (clojure.java.shell/sh "git" "-C" worktree-path "commit" "-m" "Add test task")

              ;; Push the branch from the worktree
              _ (clojure.java.shell/sh "git" "-C" worktree-path "push" "-u" "origin" "test-branch")

              ;; Track whether task was completed (capture before worktree removal)
              task-completed (atom nil)

              ;; Call complete-task with get-current-directory redef'd to simulate running from within worktree
              ;; Wrap cleanup to push commits and capture task state before removal
              config (assoc (h/git-test-config worktree-path) :worktree-management? true)
              original-cleanup @#'mcp-tasks.tool.work-on/cleanup-worktree-after-completion
              result (with-redefs [mcp-tasks.tool.complete-task/get-current-directory
                                   (fn [_] worktree-path)
                                   mcp-tasks.tool.work-on/cleanup-worktree-after-completion
                                   (fn [main-repo worktree cfg]
                                     ;; Capture completed task state before worktree is removed
                                     (reset! task-completed
                                             (try
                                               (let [tasks (h/read-ednl-test-file worktree "complete.ednl")]
                                                 (first tasks))
                                               (catch Exception _ nil)))
                                     ;; Push any commits before cleanup (simulates real workflow)
                                     (clojure.java.shell/sh "git" "-C" worktree "push")
                                     ;; Call the original cleanup function
                                     (original-cleanup main-repo worktree cfg))]
                       (#'sut/complete-task-impl
                        config
                        nil
                        {:task-id 100}))]

          ;; Verify task completion succeeded
          (is (false? (:isError result)) "Task completion should succeed")

          ;; Verify task was actually completed (captured before removal)
          (is (some? @task-completed) "Task should have been captured before removal")
          (when @task-completed
            (is (= 100 (:id @task-completed)))
            (is (= :closed (:status @task-completed))))

          ;; Verify worktree was actually removed
          (is (not (fs/exists? worktree-path))
              "Worktree should be removed from filesystem")

          ;; Verify message includes self-removal warning
          (let [msg (get-in result [:content 0 :text])]
            (is (str/includes? msg "Task 100 completed"))
            (is (str/includes? msg "Worktree removed at"))
            (is (str/includes? msg worktree-path))
            (is (str/includes? msg "switch directories to continue")
                "Message should warn about needing to switch directories"))

          ;; Cleanup
          (fs/delete-tree remote-dir))))))
