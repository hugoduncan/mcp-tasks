(ns mcp-tasks.tool.delete-task-test
  (:require
    [babashka.fs :as fs]
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.java.shell :as sh]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-tasks.tasks :as tasks]
    [mcp-tasks.tasks-file :as tasks-file]
    [mcp-tasks.tool.delete-task :as sut]))

(def ^:dynamic *test-dir* nil)

(defn- setup-test-dir
  [test-dir]
  (fs/create-dirs (io/file test-dir ".mcp-tasks")))

(defn- write-ednl-test-file
  "Write tasks as EDNL format to test file."
  [path tasks]
  (let [file-path (str *test-dir* "/.mcp-tasks/" path)]
    (tasks-file/write-tasks file-path tasks)))

(defn- read-ednl-test-file
  "Read tasks from EDNL test file."
  [path]
  (let [file-path (str *test-dir* "/.mcp-tasks/" path)]
    (tasks-file/read-ednl file-path)))

(defn- reset-tasks-state!
  "Reset the tasks namespace global state for testing."
  []
  (reset! tasks/task-ids [])
  (reset! tasks/tasks {})
  (reset! tasks/parent-children {})
  (reset! tasks/child-parent {})
  (vreset! tasks/next-id 1))

(defn- test-config
  "Config that points to test fixtures directory."
  []
  {:base-dir *test-dir* :use-git? false})

(defn- test-fixture
  "Fixture that sets up and cleans up test directory for each test."
  [f]
  (let [dir (fs/create-temp-dir {:prefix "mcp-tasks-delete-task-test-"})]
    (try
      (binding [*test-dir* (str dir)]
        (setup-test-dir *test-dir*)
        (reset-tasks-state!)
        (f))
      (finally
        (fs/delete-tree dir)))))

(use-fixtures :each test-fixture)

;; Git helper functions

(defn- git-test-config
  "Config with git enabled for testing."
  []
  {:base-dir *test-dir* :use-git? true})

(defn- init-git-repo
  "Initialize a git repository in the test .mcp-tasks directory."
  [test-dir]
  (let [git-dir (str test-dir "/.mcp-tasks")]
    (sh/sh "git" "init" :dir git-dir)
    (sh/sh "git" "config" "user.email" "test@test.com" :dir git-dir)
    (sh/sh "git" "config" "user.name" "Test User" :dir git-dir)))

(defn- git-log-last-commit
  "Get the last commit message from the git repo."
  [test-dir]
  (let [git-dir (str test-dir "/.mcp-tasks")
        result (sh/sh "git" "log" "-1" "--pretty=%B" :dir git-dir)]
    (str/trim (:out result))))

(defn- git-commit-exists?
  "Check if there are any commits in the git repo."
  [test-dir]
  (let [git-dir (str test-dir "/.mcp-tasks")
        result (sh/sh "git" "rev-parse" "HEAD" :dir git-dir)]
    (zero? (:exit result))))

;; delete-task tests

(deftest delete-task-by-id
  ;; Tests that delete-task-impl can find and delete a task by exact ID
  (testing "delete-task"
    (testing "deletes task by exact task-id"
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "first task" :description "detail" :design "" :category "test" :type :task :status :open :meta {} :relations []}
                             {:id 2 :parent-id nil :title "second task" :description "" :design "" :category "other" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/delete-task-impl
                    (test-config)
                    nil
                    {:task-id 2})]
        (is (false? (:isError result)))
        ;; Verify response structure (2 items without git)
        (is (= 2 (count (:content result))))
        (is (= "Task 2 deleted successfully" (get-in result [:content 0 :text])))
        ;; Second content item: deleted task data
        (let [deleted-data (json/read-str (get-in result [:content 1 :text]) :key-fn keyword)]
          (is (contains? deleted-data :deleted))
          (is (= 2 (:id (:deleted deleted-data))))
          (is (= "deleted" (:status (:deleted deleted-data))))
          (is (contains? deleted-data :metadata))
          (is (= 1 (get-in deleted-data [:metadata :count])))
          (is (= "deleted" (get-in deleted-data [:metadata :status]))))
        ;; Verify task 2 is in complete.ednl with :status :deleted
        (let [complete-tasks (read-ednl-test-file "complete.ednl")]
          (is (= 1 (count complete-tasks)))
          (is (= "second task" (:title (first complete-tasks))))
          (is (= :deleted (:status (first complete-tasks)))))
        ;; Verify task 1 remains in tasks.ednl
        (let [tasks (read-ednl-test-file "tasks.ednl")]
          (is (= 1 (count tasks)))
          (is (= "first task" (:title (first tasks)))))))))

(deftest delete-task-by-title-pattern
  ;; Tests that delete-task-impl can find and delete a task by exact title match
  (testing "delete-task"
    (testing "deletes task by exact title-pattern match"
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "unique task" :description "detail" :design "" :category "test" :type :task :status :open :meta {} :relations []}
                             {:id 2 :parent-id nil :title "other task" :description "" :design "" :category "other" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/delete-task-impl
                    (test-config)
                    nil
                    {:title-pattern "unique task"})]
        (is (false? (:isError result)))
        ;; Verify task with title "unique task" is deleted
        (let [complete-tasks (read-ednl-test-file "complete.ednl")]
          (is (= 1 (count complete-tasks)))
          (is (= "unique task" (:title (first complete-tasks))))
          (is (= :deleted (:status (first complete-tasks)))))
        ;; Verify other task remains
        (let [tasks (read-ednl-test-file "tasks.ednl")]
          (is (= 1 (count tasks)))
          (is (= "other task" (:title (first tasks)))))))))

(deftest delete-task-rejects-ambiguous-title
  ;; Tests that delete-task-impl rejects when multiple tasks have the same title
  (testing "delete-task"
    (testing "rejects multiple tasks with same title"
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 1 :parent-id nil :title "duplicate" :description "first" :design "" :category "test" :type :task :status :open :meta {} :relations []}
         {:id 2 :parent-id nil :title "duplicate" :description "second" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/delete-task-impl
                    (test-config)
                    nil
                    {:title-pattern "duplicate"})]
        (is (true? (:isError result)))
        (is (str/includes?
              (get-in result [:content 0 :text])
              "Multiple tasks found"))
        ;; Verify no tasks were deleted
        (is (empty? (read-ednl-test-file "complete.ednl")))
        (is (= 2 (count (read-ednl-test-file "tasks.ednl"))))))))

(deftest delete-task-requires-identifier
  ;; Tests that delete-task-impl requires at least one identifier
  (testing "delete-task"
    (testing "requires at least one of task-id or title-pattern"
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/delete-task-impl
                    (test-config)
                    nil
                    {})]
        (is (true? (:isError result)))
        (is (str/includes?
              (get-in result [:content 0 :text])
              "Must provide either task-id or title"))))))

(deftest delete-task-verifies-id-and-title-match
  ;; Tests that delete-task-impl verifies both identifiers refer to the same task
  (testing "delete-task"
    (testing "verifies task-id and title-pattern match same task"
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 1 :parent-id nil :title "first" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}
         {:id 2 :parent-id nil :title "second" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/delete-task-impl
                    (test-config)
                    nil
                    {:task-id 1 :title-pattern "second"})]
        (is (true? (:isError result)))
        (is (str/includes?
              (get-in result [:content 0 :text])
              "do not refer to the same task"))))))

(deftest delete-task-rejects-nonexistent-task
  ;; Tests that delete-task-impl returns error for non-existent task
  (testing "delete-task"
    (testing "returns error for non-existent task ID"
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/delete-task-impl
                    (test-config)
                    nil
                    {:task-id 999})]
        (is (true? (:isError result)))
        (is (str/includes?
              (get-in result [:content 0 :text])
              "Task ID not found"))))
    (testing "returns error for non-existent title-pattern"
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/delete-task-impl
                    (test-config)
                    nil
                    {:title-pattern "nonexistent"})]
        (is (true? (:isError result)))
        (is (str/includes?
              (get-in result [:content 0 :text])
              "No task found with exact title match"))))))

(deftest delete-task-prevents-deletion-with-non-closed-children
  ;; Tests that delete-task-impl prevents deletion of parent with non-closed children
  (testing "delete-task"
    (testing "prevents deletion of parent with non-closed children"
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 1 :parent-id nil :title "parent" :description "" :design "" :category "test" :type :story :status :open :meta {} :relations []}
         {:id 2 :parent-id 1 :title "child 1" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}
         {:id 3 :parent-id 1 :title "child 2" :description "" :design "" :category "test" :type :task :status :closed :meta {} :relations []}])
      (let [result (#'sut/delete-task-impl
                    (test-config)
                    nil
                    {:task-id 1})]
        (is (true? (:isError result)))
        (is (str/includes?
              (get-in result [:content 0 :text])
              "Cannot delete task with children"))
        ;; Verify error metadata includes child info
        (let [data (json/read-str (get-in result [:content 1 :text]) :key-fn keyword)]
          (is (= 1 (get-in data [:metadata :child-count])))
          (is (= 1 (count (get-in data [:metadata :non-closed-children])))))
        ;; Verify no deletion occurred
        (is (empty? (read-ednl-test-file "complete.ednl")))
        (is (= 3 (count (read-ednl-test-file "tasks.ednl"))))))))

(deftest delete-task-allows-deletion-with-all-closed-children
  ;; Tests that delete-task-impl allows deletion of parent when all children are closed
  (testing "delete-task"
    (testing "allows deletion of parent with all closed children"
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 1 :parent-id nil :title "parent" :description "" :design "" :category "test" :type :story :status :open :meta {} :relations []}
         {:id 2 :parent-id 1 :title "child 1" :description "" :design "" :category "test" :type :task :status :closed :meta {} :relations []}
         {:id 3 :parent-id 1 :title "child 2" :description "" :design "" :category "test" :type :task :status :closed :meta {} :relations []}])
      (let [result (#'sut/delete-task-impl
                    (test-config)
                    nil
                    {:task-id 1})]
        (is (false? (:isError result)))
        ;; Verify parent was deleted
        (let [complete-tasks (read-ednl-test-file "complete.ednl")]
          (is (= 1 (count complete-tasks)))
          (is (= 1 (:id (first complete-tasks))))
          (is (= :deleted (:status (first complete-tasks)))))
        ;; Verify children remain in tasks.ednl
        (let [tasks (read-ednl-test-file "tasks.ednl")]
          (is (= 2 (count tasks)))
          (is (every? #(= 1 (:parent-id %)) tasks)))))))

(deftest delete-task-allows-deletion-with-no-children
  ;; Tests that delete-task-impl allows deletion of task with no children
  (testing "delete-task"
    (testing "allows deletion of task with no children"
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 1 :parent-id nil :title "parent" :description "" :design "" :category "test" :type :story :status :open :meta {} :relations []}])
      (let [result (#'sut/delete-task-impl
                    (test-config)
                    nil
                    {:task-id 1})]
        (is (false? (:isError result)))
        ;; Verify task was deleted
        (let [complete-tasks (read-ednl-test-file "complete.ednl")]
          (is (= 1 (count complete-tasks)))
          (is (= :deleted (:status (first complete-tasks)))))
        (is (empty? (read-ednl-test-file "tasks.ednl")))))))

(deftest delete-task-allows-deletion-of-child-task
  ;; Tests that delete-task-impl allows deletion of child tasks
  (testing "delete-task"
    (testing "allows deletion of child task"
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 1 :parent-id nil :title "parent" :description "" :design "" :category "test" :type :story :status :open :meta {} :relations []}
         {:id 2 :parent-id 1 :title "child" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/delete-task-impl
                    (test-config)
                    nil
                    {:task-id 2})]
        (is (false? (:isError result)))
        ;; Verify child was deleted
        (let [complete-tasks (read-ednl-test-file "complete.ednl")]
          (is (= 1 (count complete-tasks)))
          (is (= 2 (:id (first complete-tasks))))
          (is (= :deleted (:status (first complete-tasks)))))
        ;; Verify parent remains
        (let [tasks (read-ednl-test-file "tasks.ednl")]
          (is (= 1 (count tasks)))
          (is (= 1 (:id (first tasks)))))))))

(deftest delete-task-rejects-already-deleted
  ;; Tests that delete-task-impl rejects tasks that are already deleted
  (testing "delete-task"
    (testing "rejects task that is already deleted"
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :deleted :meta {} :relations []}])
      (let [result (#'sut/delete-task-impl
                    (test-config)
                    nil
                    {:task-id 1})]
        (is (true? (:isError result)))
        (is (str/includes?
              (get-in result [:content 0 :text])
              "already deleted"))
        ;; Verify no change in complete.ednl
        (is (empty? (read-ednl-test-file "complete.ednl")))))))

(deftest delete-task-returns-three-content-items-with-git
  ;; Tests that delete-task returns 3 content items when git is enabled
  (testing "delete-task with git enabled"
    (testing "returns three content items"
      (init-git-repo *test-dir*)
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "test task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/delete-task-impl
                    (git-test-config)
                    nil
                    {:task-id 1})]
        (is (false? (:isError result)))
        (is (= 3 (count (:content result))))

        ;; First content item: deletion message
        (let [text-content (first (:content result))]
          (is (= "text" (:type text-content)))
          (is (str/includes? (:text text-content) "Task 1 deleted")))

        ;; Second content item: deleted task data
        (let [deleted-content (second (:content result))
              deleted-data (json/read-str (:text deleted-content) :key-fn keyword)]
          (is (= "text" (:type deleted-content)))
          (is (contains? deleted-data :deleted))
          (is (= 1 (:id (:deleted deleted-data))))
          (is (= "deleted" (:status (:deleted deleted-data))))
          (is (contains? deleted-data :metadata))
          (is (= 1 (get-in deleted-data [:metadata :count])))
          (is (= "deleted" (get-in deleted-data [:metadata :status]))))

        ;; Third content item: git status
        (let [git-content (nth (:content result) 2)
              git-data (json/read-str (:text git-content) :key-fn keyword)]
          (is (= "text" (:type git-content)))
          (is (contains? git-data :git-status))
          (is (contains? git-data :git-commit)))))))

(deftest ^:integration delete-task-creates-git-commit
  ;; Integration test verifying git commit is actually created
  (testing "delete-task with git enabled"
    (testing "creates git commit with correct message"
      (init-git-repo *test-dir*)
      (write-ednl-test-file "tasks.ednl"
                            [{:id 42 :parent-id nil :title "implement feature X" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])

      ;; Delete the task
      (let [result (#'sut/delete-task-impl
                    (git-test-config)
                    nil
                    {:task-id 42})]
        (is (false? (:isError result)))

        ;; Verify git commit was created
        (is (git-commit-exists? *test-dir*))

        ;; Verify commit message format
        (let [commit-msg (git-log-last-commit *test-dir*)]
          (is (= "Delete task #42: implement feature X" commit-msg)))

        ;; Verify deleted task data in second content item
        (let [deleted-content (second (:content result))
              deleted-data (json/read-str (:text deleted-content) :key-fn keyword)]
          (is (= "text" (:type deleted-content)))
          (is (contains? deleted-data :deleted))
          (is (= 42 (:id (:deleted deleted-data))))
          (is (= "deleted" (:status (:deleted deleted-data))))
          (is (contains? deleted-data :metadata))
          (is (= 1 (get-in deleted-data [:metadata :count])))
          (is (= "deleted" (get-in deleted-data [:metadata :status]))))

        ;; Verify git status in response
        (let [git-content (nth (:content result) 2)
              git-data (json/read-str (:text git-content) :key-fn keyword)]
          (is (= "success" (:git-status git-data)))
          (is (string? (:git-commit git-data)))
          (is (= 40 (count (:git-commit git-data)))) ; SHA is 40 chars
          (is (nil? (:git-error git-data))))))))

(deftest ^:integration delete-task-succeeds-despite-git-failure
  ;; Tests that task deletion succeeds even when git operations fail
  (testing "delete-task with git enabled but no git repo"
    (testing "task deletes successfully despite git error"
      ;; Do not initialize git repo - this will cause git operations to fail
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "test task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])

      (let [result (#'sut/delete-task-impl
                    (git-test-config)
                    nil
                    {:task-id 1})]
        ;; Task deletion should succeed
        (is (false? (:isError result)))

        ;; Verify task was actually deleted
        (let [complete-tasks (read-ednl-test-file "complete.ednl")]
          (is (= 1 (count complete-tasks)))
          (is (= "test task" (:title (first complete-tasks))))
          (is (= :deleted (:status (first complete-tasks)))))

        ;; Verify deleted task data in second content item
        (let [deleted-content (second (:content result))
              deleted-data (json/read-str (:text deleted-content) :key-fn keyword)]
          (is (= "text" (:type deleted-content)))
          (is (contains? deleted-data :deleted))
          (is (= 1 (:id (:deleted deleted-data))))
          (is (= "deleted" (:status (:deleted deleted-data))))
          (is (contains? deleted-data :metadata))
          (is (= 1 (get-in deleted-data [:metadata :count])))
          (is (= "deleted" (get-in deleted-data [:metadata :status]))))

        ;; Verify git error is reported in response
        (let [git-content (nth (:content result) 2)
              git-data (json/read-str (:text git-content) :key-fn keyword)]
          (is (= "error" (:git-status git-data)))
          (is (nil? (:git-commit git-data)))
          (is (string? (:git-error git-data)))
          (is (not (str/blank? (:git-error git-data)))))))))

(deftest ^:integration delete-task-git-commit-sha-format
  ;; Tests that git commit SHA is returned in correct format
  (testing "delete-task with git enabled"
    (testing "returns valid git commit SHA"
      (init-git-repo *test-dir*)
      (write-ednl-test-file "tasks.ednl"
                            [{:id 99 :parent-id nil :title "task title" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])

      (let [result (#'sut/delete-task-impl
                    (git-test-config)
                    nil
                    {:task-id 99})]

        ;; Verify deleted task data in second content item
        (let [deleted-content (second (:content result))
              deleted-data (json/read-str (:text deleted-content) :key-fn keyword)]
          (is (= "text" (:type deleted-content)))
          (is (contains? deleted-data :deleted))
          (is (= 99 (:id (:deleted deleted-data))))
          (is (= "deleted" (:status (:deleted deleted-data))))
          (is (contains? deleted-data :metadata))
          (is (= 1 (get-in deleted-data [:metadata :count])))
          (is (= "deleted" (get-in deleted-data [:metadata :status]))))

        ;; Verify SHA format
        (let [git-content (nth (:content result) 2)
              git-data (json/read-str (:text git-content) :key-fn keyword)
              sha (:git-commit git-data)]
          (is (string? sha))
          (is (= 40 (count sha)))
          (is (re-matches #"[0-9a-f]{40}" sha)))))))
