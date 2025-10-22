(ns mcp-tasks.tool.delete-task-test
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.test-helpers :as h]
    [mcp-tasks.tool.delete-task :as sut]))

;; delete-task tests

(deftest delete-task-by-id
  (h/with-test-setup [test-dir]
    ;; Tests that delete-task-impl can find and delete a task by exact ID
    (testing "delete-task"
      (testing "deletes task by exact task-id"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "first task" :description "detail" :design "" :category "test" :type :task :status :open :meta {} :relations []}
           {:id 2 :parent-id nil :title "second task" :description "" :design "" :category "other" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/delete-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 2})]
          (is (false? (:isError result)))
          ;; Verify response structure (2 items without git)
          (is (= 2 (count (:content result))))
          (is (= "Task 2 deleted successfully" (get-in result [:content 0 :text])))
          ;; Second content item: deleted task data
          (let [deleted-data (json/parse-string (get-in result [:content 1 :text]) keyword)]
            (is (contains? deleted-data :deleted))
            (is (= 2 (:id (:deleted deleted-data))))
            (is (= "deleted" (:status (:deleted deleted-data))))
            (is (contains? deleted-data :metadata))
            (is (= 1 (get-in deleted-data [:metadata :count])))
            (is (= "deleted" (get-in deleted-data [:metadata :status]))))
          ;; Verify task 2 is in complete.ednl with :status :deleted
          (let [complete-tasks (h/read-ednl-test-file test-dir "complete.ednl")]
            (is (= 1 (count complete-tasks)))
            (is (= "second task" (:title (first complete-tasks))))
            (is (= :deleted (:status (first complete-tasks)))))
          ;; Verify task 1 remains in tasks.ednl
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")]
            (is (= 1 (count tasks)))
            (is (= "first task" (:title (first tasks))))))))))

(deftest delete-task-by-title-pattern
  (h/with-test-setup [test-dir]
    ;; Tests that delete-task-impl can find and delete a task by exact title match
    (testing "delete-task"
      (testing "deletes task by exact title-pattern match"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "unique task" :description "detail" :design "" :category "test" :type :task :status :open :meta {} :relations []}
           {:id 2 :parent-id nil :title "other task" :description "" :design "" :category "other" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/delete-task-impl
                      (h/test-config test-dir)
                      nil
                      {:title-pattern "unique task"})]
          (is (false? (:isError result)))
          ;; Verify task with title "unique task" is deleted
          (let [complete-tasks (h/read-ednl-test-file test-dir "complete.ednl")]
            (is (= 1 (count complete-tasks)))
            (is (= "unique task" (:title (first complete-tasks))))
            (is (= :deleted (:status (first complete-tasks)))))
          ;; Verify other task remains
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")]
            (is (= 1 (count tasks)))
            (is (= "other task" (:title (first tasks))))))))))

(deftest delete-task-rejects-ambiguous-title
  (h/with-test-setup [test-dir]
    ;; Tests that delete-task-impl rejects when multiple tasks have the same title
    (testing "delete-task"
      (testing "rejects multiple tasks with same title"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "duplicate" :description "first" :design "" :category "test" :type :task :status :open :meta {} :relations []}
           {:id 2 :parent-id nil :title "duplicate" :description "second" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/delete-task-impl
                      (h/test-config test-dir)
                      nil
                      {:title-pattern "duplicate"})]
          (is (true? (:isError result)))
          (is (str/includes?
                (get-in result [:content 0 :text])
                "Multiple tasks found"))
          ;; Verify no tasks were deleted
          (is (empty? (h/read-ednl-test-file test-dir "complete.ednl")))
          (is (= 2 (count (h/read-ednl-test-file test-dir "tasks.ednl")))))))))

(deftest delete-task-requires-identifier
  (h/with-test-setup [test-dir]
    ;; Tests that delete-task-impl requires at least one identifier
    (testing "delete-task"
      (testing "requires at least one of task-id or title-pattern"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/delete-task-impl
                      (h/test-config test-dir)
                      nil
                      {})]
          (is (true? (:isError result)))
          (is (str/includes?
                (get-in result [:content 0 :text])
                "Must provide either task-id or title")))))))

(deftest delete-task-verifies-id-and-title-match
  (h/with-test-setup [test-dir]
    ;; Tests that delete-task-impl verifies both identifiers refer to the same task
    (testing "delete-task"
      (testing "verifies task-id and title-pattern match same task"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "first" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}
           {:id 2 :parent-id nil :title "second" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/delete-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :title-pattern "second"})]
          (is (true? (:isError result)))
          (is (str/includes?
                (get-in result [:content 0 :text])
                "do not refer to the same task")))))))

(deftest delete-task-rejects-nonexistent-task
  (h/with-test-setup [test-dir]
    ;; Tests that delete-task-impl returns error for non-existent task
    (testing "delete-task"
      (testing "returns error for non-existent task ID"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/delete-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 999})]
          (is (true? (:isError result)))
          (is (str/includes?
                (get-in result [:content 0 :text])
                "Task ID not found"))))
      (testing "returns error for non-existent title-pattern"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/delete-task-impl
                      (h/test-config test-dir)
                      nil
                      {:title-pattern "nonexistent"})]
          (is (true? (:isError result)))
          (is (str/includes?
                (get-in result [:content 0 :text])
                "No task found with exact title match")))))))

(deftest delete-task-prevents-deletion-with-non-closed-children
  (h/with-test-setup [test-dir]
    ;; Tests that delete-task-impl prevents deletion of parent with non-closed children
    (testing "delete-task"
      (testing "prevents deletion of parent with non-closed children"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "parent" :description "" :design "" :category "test" :type :story :status :open :meta {} :relations []}
           {:id 2 :parent-id 1 :title "child 1" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}
           {:id 3 :parent-id 1 :title "child 2" :description "" :design "" :category "test" :type :task :status :closed :meta {} :relations []}])
        (let [result (#'sut/delete-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1})]
          (is (true? (:isError result)))
          (is (str/includes?
                (get-in result [:content 0 :text])
                "Cannot delete task with children"))
          ;; Verify error metadata includes child info
          (let [data (json/parse-string (get-in result [:content 1 :text]) keyword)]
            (is (= 1 (get-in data [:metadata :child-count])))
            (is (= 1 (count (get-in data [:metadata :non-closed-children])))))
          ;; Verify no deletion occurred
          (is (empty? (h/read-ednl-test-file test-dir "complete.ednl")))
          (is (= 3 (count (h/read-ednl-test-file test-dir "tasks.ednl")))))))))

(deftest delete-task-allows-deletion-with-all-closed-children
  (h/with-test-setup [test-dir]
    ;; Tests that delete-task-impl allows deletion of parent when all children are closed
    (testing "delete-task"
      (testing "allows deletion of parent with all closed children"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "parent" :description "" :design "" :category "test" :type :story :status :open :meta {} :relations []}
           {:id 2 :parent-id 1 :title "child 1" :description "" :design "" :category "test" :type :task :status :closed :meta {} :relations []}
           {:id 3 :parent-id 1 :title "child 2" :description "" :design "" :category "test" :type :task :status :closed :meta {} :relations []}])
        (let [result (#'sut/delete-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1})]
          (is (false? (:isError result)))
          ;; Verify parent was deleted
          (let [complete-tasks (h/read-ednl-test-file test-dir "complete.ednl")]
            (is (= 1 (count complete-tasks)))
            (is (= 1 (:id (first complete-tasks))))
            (is (= :deleted (:status (first complete-tasks)))))
          ;; Verify children remain in tasks.ednl
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")]
            (is (= 2 (count tasks)))
            (is (every? #(= 1 (:parent-id %)) tasks))))))))

(deftest delete-task-allows-deletion-with-no-children
  (h/with-test-setup [test-dir]
    ;; Tests that delete-task-impl allows deletion of task with no children
    (testing "delete-task"
      (testing "allows deletion of task with no children"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "parent" :description "" :design "" :category "test" :type :story :status :open :meta {} :relations []}])
        (let [result (#'sut/delete-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1})]
          (is (false? (:isError result)))
          ;; Verify task was deleted
          (let [complete-tasks (h/read-ednl-test-file test-dir "complete.ednl")]
            (is (= 1 (count complete-tasks)))
            (is (= :deleted (:status (first complete-tasks)))))
          (is (empty? (h/read-ednl-test-file test-dir "tasks.ednl"))))))))

(deftest delete-task-allows-deletion-of-child-task
  (h/with-test-setup [test-dir]
    ;; Tests that delete-task-impl allows deletion of child tasks
    (testing "delete-task"
      (testing "allows deletion of child task"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "parent" :description "" :design "" :category "test" :type :story :status :open :meta {} :relations []}
           {:id 2 :parent-id 1 :title "child" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/delete-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 2})]
          (is (false? (:isError result)))
          ;; Verify child was deleted
          (let [complete-tasks (h/read-ednl-test-file test-dir "complete.ednl")]
            (is (= 1 (count complete-tasks)))
            (is (= 2 (:id (first complete-tasks))))
            (is (= :deleted (:status (first complete-tasks)))))
          ;; Verify parent remains
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")]
            (is (= 1 (count tasks)))
            (is (= 1 (:id (first tasks))))))))))

(deftest delete-task-rejects-already-deleted
  (h/with-test-setup [test-dir]
    ;; Tests that delete-task-impl rejects tasks that are already deleted
    (testing "delete-task"
      (testing "rejects task that is already deleted"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :deleted :meta {} :relations []}])
        (let [result (#'sut/delete-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1})]
          (is (true? (:isError result)))
          (is (str/includes?
                (get-in result [:content 0 :text])
                "already deleted"))
          ;; Verify no change in complete.ednl
          (is (empty? (h/read-ednl-test-file test-dir "complete.ednl"))))))))

(deftest delete-task-returns-three-content-items-with-git
  (h/with-test-setup [test-dir]
    ;; Tests that delete-task returns 3 content items when git is enabled
    (testing "delete-task with git enabled"
      (testing "returns three content items"
        (h/init-git-repo test-dir)
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "test task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/delete-task-impl
                      (h/git-test-config test-dir)
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
                deleted-data (json/parse-string (:text deleted-content) keyword)]
            (is (= "text" (:type deleted-content)))
            (is (contains? deleted-data :deleted))
            (is (= 1 (:id (:deleted deleted-data))))
            (is (= "deleted" (:status (:deleted deleted-data))))
            (is (contains? deleted-data :metadata))
            (is (= 1 (get-in deleted-data [:metadata :count])))
            (is (= "deleted" (get-in deleted-data [:metadata :status]))))

          ;; Third content item: git status
          (let [git-content (nth (:content result) 2)
                git-data (json/parse-string (:text git-content) keyword)]
            (is (= "text" (:type git-content)))
            (is (contains? git-data :git-status))
            (is (contains? git-data :git-commit))))))))

(deftest ^:integration delete-task-creates-git-commit
  ;; Integration test verifying git commit is actually created
  (h/with-test-setup [test-dir]
    (testing "delete-task with git enabled"
      (testing "creates git commit with correct message"
        (h/init-git-repo test-dir)
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 42 :parent-id nil :title "implement feature X" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])

        ;; Delete the task
        (let [result (#'sut/delete-task-impl
                      (h/git-test-config test-dir)
                      nil
                      {:task-id 42})]
          (is (false? (:isError result)))

          ;; Verify git commit was created
          (is (h/git-commit-exists? test-dir))

          ;; Verify commit message format
          (let [commit-msg (h/git-log-last-commit test-dir)]
            (is (= "Delete task #42: implement feature X" commit-msg)))

          ;; Verify deleted task data in second content item
          (let [deleted-content (second (:content result))
                deleted-data (json/parse-string (:text deleted-content) keyword)]
            (is (= "text" (:type deleted-content)))
            (is (contains? deleted-data :deleted))
            (is (= 42 (:id (:deleted deleted-data))))
            (is (= "deleted" (:status (:deleted deleted-data))))
            (is (contains? deleted-data :metadata))
            (is (= 1 (get-in deleted-data [:metadata :count])))
            (is (= "deleted" (get-in deleted-data [:metadata :status]))))

          ;; Verify git status in response
          (let [git-content (nth (:content result) 2)
                git-data (json/parse-string (:text git-content) keyword)]
            (is (= "success" (:git-status git-data)))
            (is (string? (:git-commit git-data)))
            (is (= 40 (count (:git-commit git-data)))) ; SHA is 40 chars
            (is (nil? (:git-error git-data)))))))))

(deftest ^:integration delete-task-succeeds-despite-git-failure
  ;; Tests that task deletion succeeds even when git operations fail
  (h/with-test-setup [test-dir]
    (testing "delete-task with git enabled but no git repo"
      (testing "task deletes successfully despite git error"
        ;; Do not initialize git repo - this will cause git operations to fail
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "test task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])

        (let [result (#'sut/delete-task-impl
                      (h/git-test-config test-dir)
                      nil
                      {:task-id 1})]
          ;; Task deletion should succeed
          (is (false? (:isError result)))

          ;; Verify task was actually deleted
          (let [complete-tasks (h/read-ednl-test-file test-dir "complete.ednl")]
            (is (= 1 (count complete-tasks)))
            (is (= "test task" (:title (first complete-tasks))))
            (is (= :deleted (:status (first complete-tasks)))))

          ;; Verify deleted task data in second content item
          (let [deleted-content (second (:content result))
                deleted-data (json/parse-string (:text deleted-content) keyword)]
            (is (= "text" (:type deleted-content)))
            (is (contains? deleted-data :deleted))
            (is (= 1 (:id (:deleted deleted-data))))
            (is (= "deleted" (:status (:deleted deleted-data))))
            (is (contains? deleted-data :metadata))
            (is (= 1 (get-in deleted-data [:metadata :count])))
            (is (= "deleted" (get-in deleted-data [:metadata :status]))))

          ;; Verify git error is reported in response
          (let [git-content (nth (:content result) 2)
                git-data (json/parse-string (:text git-content) keyword)]
            (is (= "error" (:git-status git-data)))
            (is (nil? (:git-commit git-data)))
            (is (string? (:git-error git-data)))
            (is (not (str/blank? (:git-error git-data))))))))))

(deftest ^:integration delete-task-git-commit-sha-format
  ;; Tests that git commit SHA is returned in correct format
  (h/with-test-setup [test-dir]
    (testing "delete-task with git enabled"
      (testing "returns valid git commit SHA"
        (h/init-git-repo test-dir)
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 99 :parent-id nil :title "task title" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])

        (let [result (#'sut/delete-task-impl
                      (h/git-test-config test-dir)
                      nil
                      {:task-id 99})]

          ;; Verify deleted task data in second content item
          (let [deleted-content (second (:content result))
                deleted-data (json/parse-string (:text deleted-content) keyword)]
            (is (= "text" (:type deleted-content)))
            (is (contains? deleted-data :deleted))
            (is (= 99 (:id (:deleted deleted-data))))
            (is (= "deleted" (:status (:deleted deleted-data))))
            (is (contains? deleted-data :metadata))
            (is (= 1 (get-in deleted-data [:metadata :count])))
            (is (= "deleted" (get-in deleted-data [:metadata :status]))))

          ;; Verify SHA format
          (let [git-content (nth (:content result) 2)
                git-data (json/parse-string (:text git-content) keyword)
                sha (:git-commit git-data)]
            (is (string? sha))
            (is (= 40 (count sha)))
            (is (re-matches #"[0-9a-f]{40}" sha))))))))
