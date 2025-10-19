(ns mcp-tasks.tool.complete-task-test
  (:require
    [clojure.data.json :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-tasks.tasks :as tasks]
    [mcp-tasks.test-helpers :as h]
    [mcp-tasks.tool.complete-task :as sut]))

(use-fixtures :each h/test-fixture)

;; Git helper functions

;; complete-task tests

(deftest moves-first-task-from-tasks-to-complete
  ;; Tests that the complete-task-impl function correctly moves the first
  ;; task from tasks.ednl to complete.ednl
  (testing "complete-task"
    (testing "moves first task from tasks to complete"
      ;; Create EDNL file with two tasks
      (h/write-ednl-test-file
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
                    (h/test-config)
                    nil
                    {:category "test"
                     :title "first task"})]
        (is (false? (:isError result)))
        ;; Verify complete file has the completed task
        (let [complete-tasks (h/read-ednl-test-file "complete.ednl")]
          (is (= 1 (count complete-tasks)))
          (is (= "first task" (:title (first complete-tasks))))
          (is (= :closed (:status (first complete-tasks)))))
        ;; Verify tasks file has only the second task
        (let [tasks (h/read-ednl-test-file "tasks.ednl")]
          (is (= 1 (count tasks)))
          (is (= "second task" (:title (first tasks)))))))))

(deftest adds-completion-comment-when-provided
  ;; Tests that completion comments are appended to completed tasks
  (testing "complete-task"
    (testing "adds completion comment when provided"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :parent-id nil :title "task with comment" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/complete-task-impl
                    (h/test-config)
                    nil
                    {:category "test"
                     :title "task with comment"
                     :completion-comment "Added feature X"})]
        (is (false? (:isError result)))
        (let [complete-tasks (h/read-ednl-test-file "complete.ednl")]
          (is (= 1 (count complete-tasks)))
          (is (str/includes? (:description (first complete-tasks)) "Added feature X")))))))

(deftest completes-task-by-id
  ;; Tests that complete-task-impl can find and complete a task by exact ID
  (testing "complete-task"
    (testing "completes task by exact task-id"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :parent-id nil :title "first task" :description "detail" :design "" :category "test" :type :task :status :open :meta {} :relations []}
                               {:id 2 :parent-id nil :title "second task" :description "" :design "" :category "other" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/complete-task-impl
                    (h/test-config)
                    nil
                    {:task-id 2})]
        (is (false? (:isError result)))
        ;; Verify task 2 is complete
        (let [complete-tasks (h/read-ednl-test-file "complete.ednl")]
          (is (= 1 (count complete-tasks)))
          (is (= "second task" (:title (first complete-tasks))))
          (is (= :closed (:status (first complete-tasks)))))
        ;; Verify task 1 remains in tasks
        (let [tasks (h/read-ednl-test-file "tasks.ednl")]
          (is (= 1 (count tasks)))
          (is (= "first task" (:title (first tasks)))))))))

(deftest completes-task-by-exact-title
  ;; Tests that complete-task-impl finds tasks by exact title match
  (testing "complete-task"
    (testing "completes task by exact title match"
      (h/write-ednl-test-file
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
                    (h/test-config)
                    nil
                    {:title "second task"})]
        (is (false? (:isError result)))
        ;; Verify second task is complete
        (let [complete-tasks (h/read-ednl-test-file "complete.ednl")]
          (is (= 1 (count complete-tasks)))
          (is (= "second task" (:title (first complete-tasks)))))))))

(deftest rejects-ambiguous-title
  ;; Tests that complete-task-impl rejects when multiple tasks have the same title
  (testing "complete-task"
    (testing "rejects multiple tasks with same title"
      (h/write-ednl-test-file
        "tasks.ednl"
        [{:id 1 :parent-id nil :title "duplicate" :description "first" :design "" :category "test" :type :task :status :open :meta {} :relations []}
         {:id 2 :parent-id nil :title "duplicate" :description "second" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/complete-task-impl
                    (h/test-config)
                    nil
                    {:title "duplicate"})]
        (is (true? (:isError result)))
        (is (str/includes?
              (get-in result [:content 0 :text])
              "Multiple tasks found"))))))

(deftest verifies-id-and-text-match
  ;; Tests that when both task-id and title are provided, they must refer to the same task
  (testing "complete-task"
    (testing "verifies task-id and title refer to same task"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :parent-id nil :title "first task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}
                               {:id 2 :parent-id nil :title "second task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      ;; Mismatched ID and text
      (let [result (#'sut/complete-task-impl
                    (h/test-config)
                    nil
                    {:task-id 1 :title "second task"})]
        (is (true? (:isError result)))
        (is (str/includes? (get-in result [:content 0 :text]) "do not refer to the same task")))
      ;; Matching ID and text
      (let [result (#'sut/complete-task-impl
                    (h/test-config)
                    nil
                    {:task-id 2 :title "second task"})]
        (is (false? (:isError result)))))))

(deftest requires-at-least-one-identifier
  ;; Tests that either task-id or title must be provided
  (testing "complete-task"
    (testing "requires either task-id or title"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/complete-task-impl
                    (h/test-config)
                    nil
                    {})]
        (is (true? (:isError result)))
        (is (str/includes? (get-in result [:content 0 :text]) "Must provide either"))))))

(deftest completes-story-child-without-archiving
  ;; Tests that completing a story child task keeps it in tasks.ednl with :status :closed
  ;; and does NOT move it to complete.ednl
  (testing "complete-task"
    (testing "story child remains in tasks.ednl, closed, not archived"
      ;; Prepare two tasks: one story, one child
      (h/write-ednl-test-file
        "tasks.ednl"
        [{:id 10 :parent-id nil :title "Parent story" :description "" :design "" :category "story" :type :story :status :open :meta {} :relations []}
         {:id 11 :parent-id 10 :title "Child task" :description "" :design "" :category "simple" :type :task :status :open :meta {} :relations []}])
      ;; Invoke completion on the child
      (let [result (#'sut/complete-task-impl
                    (h/test-config)
                    nil
                    {:task-id 11})]
        (is (false? (:isError result)))
        ;; Verify message doesn't say "moved to"
        (is (str/includes? (get-in result [:content 0 :text]) "Task 11 completed"))
        (is (not (str/includes? (get-in result [:content 0 :text]) "moved to")))
        ;; tasks.ednl: child turns :closed but stays in file
        (let [tasks (h/read-ednl-test-file "tasks.ednl")]
          (is (= 2 (count tasks)))
          (is (= :open (:status (first tasks))))
          (is (= :closed (:status (second tasks))))
          (is (= 11 (:id (second tasks)))))
        ;; complete.ednl remains empty
        (is (empty? (h/read-ednl-test-file "complete.ednl")))))))

(deftest complete-task-returns-three-content-items-with-git
  ;; Tests that complete-task returns 3 content items when git is enabled
  (testing "complete-task with git enabled"
    (testing "returns three content items"
      (h/init-git-repo h/*test-dir*)
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :parent-id nil :title "test task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/complete-task-impl
                    (h/git-test-config)
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
              files-data (json/read-str (:text files-content) :key-fn keyword)]
          (is (= "text" (:type files-content)))
          (is (contains? files-data :modified-files))
          (is (= 2 (count (:modified-files files-data)))))

        ;; Third content item: git status
        (let [git-content (nth (:content result) 2)
              git-data (json/read-str (:text git-content) :key-fn keyword)]
          (is (= "text" (:type git-content)))
          (is (contains? git-data :git-status))
          (is (contains? git-data :git-commit)))))))

(deftest complete-task-returns-one-content-item-without-git
  ;; Tests that complete-task returns 2 content items when git is disabled
  (testing "complete-task with git disabled"
    (testing "returns two content items (message and task data)"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :parent-id nil :title "test task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/complete-task-impl
                    (h/test-config)
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
              data (json/read-str (:text json-content) :key-fn keyword)]
          (is (= "text" (:type json-content)))
          (is (map? (:task data)))
          (is (= 1 (get-in data [:task :id])))
          (is (= "closed" (get-in data [:task :status]))))))))

(deftest ^:integration complete-task-creates-git-commit
  ;; Integration test verifying git commit is actually created
  (testing "complete-task with git enabled"
    (testing "creates git commit with correct message"
      (h/init-git-repo h/*test-dir*)
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 42 :parent-id nil :title "implement feature X" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])

      ;; Complete the task
      (let [result (#'sut/complete-task-impl
                    (h/git-test-config)
                    nil
                    {:task-id 42})]
        (is (false? (:isError result)))

        ;; Verify git commit was created
        (is (h/git-commit-exists? h/*test-dir*))

        ;; Verify commit message format
        (let [commit-msg (h/git-log-last-commit h/*test-dir*)]
          (is (= "Complete task #42: implement feature X" commit-msg)))

        ;; Verify git status in response
        (let [git-content (nth (:content result) 2)
              git-data (json/read-str (:text git-content) :key-fn keyword)]
          (is (= "success" (:git-status git-data)))
          (is (string? (:git-commit git-data)))
          (is (= 40 (count (:git-commit git-data)))) ; SHA is 40 chars
          (is (nil? (:git-error git-data))))))))

(deftest ^:integration complete-task-succeeds-despite-git-failure
  ;; Tests that task completion succeeds even when git operations fail
  (testing "complete-task with git enabled but no git repo"
    (testing "task completes successfully despite git error"
      ;; Do not initialize git repo - this will cause git operations to fail
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :parent-id nil :title "test task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])

      (let [result (#'sut/complete-task-impl
                    (h/git-test-config)
                    nil
                    {:task-id 1})]
        ;; Task completion should succeed
        (is (false? (:isError result)))

        ;; Verify task was actually completed
        (let [complete-tasks (h/read-ednl-test-file "complete.ednl")]
          (is (= 1 (count complete-tasks)))
          (is (= "test task" (:title (first complete-tasks)))))

        ;; Verify git error is reported in response
        (let [git-content (nth (:content result) 2)
              git-data (json/read-str (:text git-content) :key-fn keyword)]
          (is (= "error" (:git-status git-data)))
          (is (nil? (:git-commit git-data)))
          (is (string? (:git-error git-data)))
          (is (not (str/blank? (:git-error git-data)))))))))

(deftest ^:integration complete-task-git-commit-sha-format
  ;; Tests that git commit SHA is returned in correct format
  (testing "complete-task with git enabled"
    (testing "returns valid git commit SHA"
      (h/init-git-repo h/*test-dir*)
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 99 :parent-id nil :title "task title" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])

      (let [result (#'sut/complete-task-impl
                    (h/git-test-config)
                    nil
                    {:task-id 99})
            git-content (nth (:content result) 2)
            git-data (json/read-str (:text git-content) :key-fn keyword)
            sha (:git-commit git-data)]

        ;; Verify SHA format
        (is (string? sha))
        (is (= 40 (count sha)))
        (is (re-matches #"[0-9a-f]{40}" sha))))))

(deftest ^:integration completes-story-child-with-git
  ;; Tests that completing a story child with git only modifies tasks.ednl
  (testing "complete-task with git"
    (testing "only tasks.ednl is modified for story child"
      (h/init-git-repo h/*test-dir*)
      (h/write-ednl-test-file
        "tasks.ednl"
        [{:id 20 :parent-id nil :title "Story" :description "" :design "" :category "story" :type :story :status :open :meta {} :relations []}
         {:id 21 :parent-id 20 :title "Child" :description "" :design "" :category "simple" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/complete-task-impl
                    (h/git-test-config)
                    nil
                    {:task-id 21})]
        (is (false? (:isError result)))
        ;; Expect 3 content items
        (is (= 3 (count (:content result))))

        ;; First item: message doesn't say "moved to"
        (is (str/includes? (get-in result [:content 0 :text]) "Task 21 completed"))
        (is (not (str/includes? (get-in result [:content 0 :text]) "moved to")))

        ;; Second item: modified-files contains only tasks.ednl
        (let [files-data (json/read-str (get-in result [:content 1 :text]) :key-fn keyword)]
          (is (= ["tasks.ednl"] (:modified-files files-data))))

        ;; Third item: has git-status and commit-sha
        (let [git-data (json/read-str (get-in result [:content 2 :text]) :key-fn keyword)]
          (is (= "success" (:git-status git-data)))
          (is (string? (:git-commit git-data))))

        ;; Verify git commit was created
        (is (h/git-commit-exists? h/*test-dir*))

        ;; Verify task stayed in tasks.ednl with :status :closed
        (let [tasks (h/read-ednl-test-file "tasks.ednl")]
          (is (= 2 (count tasks)))
          (is (= :closed (:status (second tasks)))))

        ;; Verify complete.ednl is still empty
        (is (empty? (h/read-ednl-test-file "complete.ednl")))))))

(deftest completes-story-with-unclosed-children-returns-error
  ;; Tests that attempting to complete a story with unclosed children returns an error
  (testing "complete-task"
    (testing "returns error when story has unclosed children"
      (h/write-ednl-test-file
        "tasks.ednl"
        [{:id 30 :parent-id nil :title "My Story" :description "" :design "" :category "story" :type :story :status :open :meta {} :relations []}
         {:id 31 :parent-id 30 :title "Child 1" :description "" :design "" :category "simple" :type :task :status :open :meta {} :relations []}
         {:id 32 :parent-id 30 :title "Child 2" :description "" :design "" :category "simple" :type :task :status :closed :meta {} :relations []}
         {:id 33 :parent-id 30 :title "Child 3" :description "" :design "" :category "simple" :type :task :status :in-progress :meta {} :relations []}])

      (let [result (#'sut/complete-task-impl
                    (h/test-config)
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
        (let [error-data (json/read-str (get-in result [:content 1 :text]) :key-fn keyword)]
          (is (= "Cannot complete story: 2 child tasks still are not closed" (:error error-data)))
          (is (= 30 (get-in error-data [:metadata :task-id])))
          (is (= 2 (count (get-in error-data [:metadata :unclosed-children]))))
          ;; Verify unclosed children details
          (let [unclosed (get-in error-data [:metadata :unclosed-children])]
            (is (some #(= 31 (:id %)) unclosed))
            (is (some #(= 33 (:id %)) unclosed))
            (is (every? #(not= :closed (:status %)) unclosed))))

        ;; Verify nothing was moved to complete.ednl
        (is (empty? (h/read-ednl-test-file "complete.ednl")))

        ;; Verify all tasks remain in tasks.ednl unchanged
        (let [tasks (h/read-ednl-test-file "tasks.ednl")]
          (is (= 4 (count tasks)))
          (is (= :open (:status (first tasks)))))))))

(deftest completes-story-with-all-children-closed
  ;; Tests that completing a story with all children closed archives everything atomically
  (testing "complete-task"
    (testing "archives story and all children atomically when all closed"
      (h/write-ednl-test-file
        "tasks.ednl"
        [{:id 40 :parent-id nil :title "Complete Story" :description "Story desc" :design "" :category "story" :type :story :status :open :meta {} :relations []}
         {:id 41 :parent-id 40 :title "Child 1" :description "" :design "" :category "simple" :type :task :status :closed :meta {} :relations []}
         {:id 42 :parent-id 40 :title "Child 2" :description "" :design "" :category "simple" :type :task :status :closed :meta {} :relations []}])

      (let [result (#'sut/complete-task-impl
                    (h/test-config)
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
              data (json/read-str (:text json-content) :key-fn keyword)]
          (is (= "text" (:type json-content)))
          (is (map? (:task data)))
          (is (= 40 (get-in data [:task :id])))
          (is (= "closed" (get-in data [:task :status]))))

        ;; Verify all tasks moved to complete.ednl
        (let [completed-tasks (h/read-ednl-test-file "complete.ednl")]
          (is (= 3 (count completed-tasks)))
          ;; Verify story is first
          (is (= 40 (:id (first completed-tasks))))
          (is (= :story (:type (first completed-tasks))))
          (is (= :closed (:status (first completed-tasks))))
          ;; Verify children follow
          (is (= 41 (:id (second completed-tasks))))
          (is (= 42 (:id (nth completed-tasks 2)))))

        ;; Verify tasks.ednl is now empty
        (is (empty? (h/read-ednl-test-file "tasks.ednl")))))))

(deftest completes-story-with-no-children
  ;; Tests that completing a story with no children archives it immediately
  (testing "complete-task"
    (testing "archives story immediately when it has no children"
      (h/write-ednl-test-file
        "tasks.ednl"
        [{:id 50 :parent-id nil :title "Empty Story" :description "" :design "" :category "story" :type :story :status :open :meta {} :relations []}])

      (let [result (#'sut/complete-task-impl
                    (h/test-config)
                    nil
                    {:task-id 50})]
        ;; Verify success response
        (is (false? (:isError result)))

        ;; Verify completion message doesn't mention children
        (let [msg (get-in result [:content 0 :text])]
          (is (str/includes? msg "Story 50 completed and archived"))
          (is (not (str/includes? msg "child"))))

        ;; Verify story moved to complete.ednl
        (let [completed-tasks (h/read-ednl-test-file "complete.ednl")]
          (is (= 1 (count completed-tasks)))
          (is (= 50 (:id (first completed-tasks))))
          (is (= :closed (:status (first completed-tasks)))))

        ;; Verify tasks.ednl is now empty
        (is (empty? (h/read-ednl-test-file "tasks.ednl")))))))

(deftest ^:integration completes-story-with-git-creates-commit
  ;; Tests that completing a story with git creates a commit with custom message
  (testing "complete-task with git"
    (testing "creates commit with story-specific message and child count"
      (h/init-git-repo h/*test-dir*)
      (h/write-ednl-test-file
        "tasks.ednl"
        [{:id 60 :parent-id nil :title "Git Story" :description "" :design "" :category "story" :type :story :status :open :meta {} :relations []}
         {:id 61 :parent-id 60 :title "Child A" :description "" :design "" :category "simple" :type :task :status :closed :meta {} :relations []}
         {:id 62 :parent-id 60 :title "Child B" :description "" :design "" :category "simple" :type :task :status :closed :meta {} :relations []}
         {:id 63 :parent-id 60 :title "Child C" :description "" :design "" :category "simple" :type :task :status :closed :meta {} :relations []}])

      (let [result (#'sut/complete-task-impl
                    (h/git-test-config)
                    nil
                    {:task-id 60})]
        (is (false? (:isError result)))
        (is (= 3 (count (:content result))))

        ;; Verify completion message
        (let [msg (get-in result [:content 0 :text])]
          (is (str/includes? msg "Story 60 completed and archived"))
          (is (str/includes? msg "with 3 child tasks")))

        ;; Verify modified files includes both tasks.ednl and complete.ednl
        (let [files-data (json/read-str (get-in result [:content 1 :text]) :key-fn keyword)]
          (is (= ["tasks.ednl" "complete.ednl"] (:modified-files files-data))))

        ;; Verify git status is success
        (let [git-data (json/read-str (get-in result [:content 2 :text]) :key-fn keyword)]
          (is (= "success" (:git-status git-data)))
          (is (string? (:git-commit git-data)))
          (is (= 40 (count (:git-commit git-data)))))

        ;; Verify git commit was created with correct message
        (is (h/git-commit-exists? h/*test-dir*))
        (let [commit-msg (h/git-log-last-commit h/*test-dir*)]
          (is (= "Complete story #60: Git Story (with 3 tasks)" commit-msg)))

        ;; Verify all tasks archived
        (let [completed-tasks (h/read-ednl-test-file "complete.ednl")]
          (is (= 4 (count completed-tasks))))
        (is (empty? (h/read-ednl-test-file "tasks.ednl")))))))
