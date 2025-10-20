(ns mcp-tasks.cli.commands-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-tasks.cli.commands :as sut]
    [mcp-tasks.tasks-file :as tasks-file]
    [mcp-tasks.test-helpers :as h]))

(use-fixtures :each h/test-fixture)

;; list-command tests

(deftest list-command-returns-all-tasks
  ;; Test list-command returns all tasks when no filters provided
  (testing "list-command"
    (testing "returns all tasks with no filters"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :status :open :title "Task One" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}
                               {:id 2 :status :open :title "Task Two" :description "" :design ""
                                :category "medium" :type :task :meta {} :relations []}
                               {:id 3 :status :open :title "Task Three" :description "" :design ""
                                :category "large" :type :task :meta {} :relations []}])

      (let [result (sut/list-command (h/test-config) {})]
        (is (= 3 (count (:tasks result))))
        (is (= 3 (get-in result [:metadata :count])))
        (is (= 3 (get-in result [:metadata :total-matches])))
        (is (false? (get-in result [:metadata :limited?])))
        (is (= ["Task One" "Task Two" "Task Three"]
               (map :title (:tasks result))))))))

(deftest list-command-filters-by-status
  ;; Test list-command filters tasks by status
  (testing "list-command"
    (testing "filters by status"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :status :open :title "Open Task" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}
                               {:id 2 :status :closed :title "Closed Task" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}
                               {:id 3 :status :in-progress :title "Active Task" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}
                               {:id 4 :status :blocked :title "Blocked Task" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}])

      (testing "status :open"
        (let [result (sut/list-command (h/test-config) {:status :open})]
          (is (= 1 (count (:tasks result))))
          (is (= "Open Task" (:title (first (:tasks result)))))))

      (testing "status :closed"
        (let [result (sut/list-command (h/test-config) {:status :closed})]
          (is (= 1 (count (:tasks result))))
          (is (= "Closed Task" (:title (first (:tasks result)))))))

      (testing "status :in-progress"
        (let [result (sut/list-command (h/test-config) {:status :in-progress})]
          (is (= 1 (count (:tasks result))))
          (is (= "Active Task" (:title (first (:tasks result)))))))

      (testing "status :blocked"
        (let [result (sut/list-command (h/test-config) {:status :blocked})]
          (is (= 1 (count (:tasks result))))
          (is (= "Blocked Task" (:title (first (:tasks result))))))))))

(deftest list-command-filters-by-category
  ;; Test list-command filters tasks by category
  (testing "list-command"
    (testing "filters by category"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :status :open :title "Simple Task" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}
                               {:id 2 :status :open :title "Medium Task" :description "" :design ""
                                :category "medium" :type :task :meta {} :relations []}
                               {:id 3 :status :open :title "Large Task" :description "" :design ""
                                :category "large" :type :task :meta {} :relations []}])

      (let [result (sut/list-command (h/test-config) {:category "medium"})]
        (is (= 1 (count (:tasks result))))
        (is (= "Medium Task" (:title (first (:tasks result)))))
        (is (= "medium" (:category (first (:tasks result)))))))))

(deftest list-command-filters-by-type
  ;; Test list-command filters tasks by type
  (testing "list-command"
    (testing "filters by type"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :status :open :title "Regular Task" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}
                               {:id 2 :status :open :title "Bug Fix" :description "" :design ""
                                :category "simple" :type :bug :meta {} :relations []}
                               {:id 3 :status :open :title "New Feature" :description "" :design ""
                                :category "simple" :type :feature :meta {} :relations []}
                               {:id 4 :status :open :title "User Story" :description "" :design ""
                                :category "simple" :type :story :meta {} :relations []}
                               {:id 5 :status :open :title "Chore Work" :description "" :design ""
                                :category "simple" :type :chore :meta {} :relations []}])

      (testing "type :bug"
        (let [result (sut/list-command (h/test-config) {:type :bug})]
          (is (= 1 (count (:tasks result))))
          (is (= "Bug Fix" (:title (first (:tasks result)))))))

      (testing "type :feature"
        (let [result (sut/list-command (h/test-config) {:type :feature})]
          (is (= 1 (count (:tasks result))))
          (is (= "New Feature" (:title (first (:tasks result)))))))

      (testing "type :story"
        (let [result (sut/list-command (h/test-config) {:type :story})]
          (is (= 1 (count (:tasks result))))
          (is (= "User Story" (:title (first (:tasks result))))))))))

(deftest list-command-filters-by-parent-id
  ;; Test list-command filters story children by parent-id
  (testing "list-command"
    (testing "filters by parent-id"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 10 :status :open :title "Parent Story" :description "" :design ""
                                :category "large" :type :story :meta {} :relations []}
                               {:id 11 :status :open :title "Child Task 1" :description "" :design ""
                                :category "simple" :type :task :parent-id 10 :meta {} :relations []}
                               {:id 12 :status :open :title "Child Task 2" :description "" :design ""
                                :category "simple" :type :task :parent-id 10 :meta {} :relations []}
                               {:id 13 :status :open :title "Other Task" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}])

      (let [result (sut/list-command (h/test-config) {:parent-id 10})]
        (is (= 2 (count (:tasks result))))
        (is (= ["Child Task 1" "Child Task 2"]
               (map :title (:tasks result))))
        (is (every? #(= 10 (:parent-id %)) (:tasks result)))))))

(deftest list-command-filters-by-task-id
  ;; Test list-command filters by specific task ID
  (testing "list-command"
    (testing "filters by task-id"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :status :open :title "Task One" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}
                               {:id 2 :status :open :title "Task Two" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}
                               {:id 3 :status :open :title "Task Three" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}])

      (let [result (sut/list-command (h/test-config) {:task-id 2})]
        (is (= 1 (count (:tasks result))))
        (is (= "Task Two" (:title (first (:tasks result)))))
        (is (= 2 (:id (first (:tasks result)))))))))

(deftest list-command-filters-by-title-pattern
  ;; Test list-command filters tasks by title pattern
  (testing "list-command"
    (testing "filters by title-pattern"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :status :open :title "Fix parser bug" :description "" :design ""
                                :category "simple" :type :bug :meta {} :relations []}
                               {:id 2 :status :open :title "Fix render bug" :description "" :design ""
                                :category "simple" :type :bug :meta {} :relations []}
                               {:id 3 :status :open :title "Add new feature" :description "" :design ""
                                :category "medium" :type :feature :meta {} :relations []}])

      (testing "substring match"
        (let [result (sut/list-command (h/test-config) {:title-pattern "bug"})]
          (is (= 2 (count (:tasks result))))
          (is (= ["Fix parser bug" "Fix render bug"]
                 (map :title (:tasks result))))))

      (testing "regex pattern"
        (let [result (sut/list-command (h/test-config) {:title-pattern "Fix.*parser"})]
          (is (= 1 (count (:tasks result))))
          (is (= "Fix parser bug" (:title (first (:tasks result))))))))))

(deftest list-command-respects-limit
  ;; Test list-command respects limit parameter
  (testing "list-command"
    (testing "respects limit parameter"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :status :open :title "Task 1" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}
                               {:id 2 :status :open :title "Task 2" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}
                               {:id 3 :status :open :title "Task 3" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}
                               {:id 4 :status :open :title "Task 4" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}
                               {:id 5 :status :open :title "Task 5" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}])

      (let [result (sut/list-command (h/test-config) {:limit 3})]
        (is (= 3 (count (:tasks result))))
        (is (= 3 (get-in result [:metadata :count])))
        (is (= 5 (get-in result [:metadata :total-matches])))
        (is (true? (get-in result [:metadata :limited?])))))))

(deftest list-command-enforces-unique-constraint
  ;; Test list-command enforces unique constraint when requested
  (testing "list-command"
    (testing "enforces unique constraint"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :status :open :title "Task One" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}
                               {:id 2 :status :open :title "Task Two" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}])

      (testing "succeeds with 0 matches"
        (let [result (sut/list-command (h/test-config) {:category "nonexistent" :unique true})]
          (is (= 0 (count (:tasks result))))))

      (testing "succeeds with 1 match"
        (let [result (sut/list-command (h/test-config) {:task-id 1 :unique true})]
          (is (= 1 (count (:tasks result))))
          (is (= "Task One" (:title (first (:tasks result)))))))

      (testing "errors with >1 matches"
        (let [result (sut/list-command (h/test-config) {:category "simple" :unique true})]
          (is (some? (:error result)))
          (is (re-find #"Multiple tasks matched" (:error result)))
          (is (= 2 (get-in result [:metadata :total-matches]))))))))

(deftest list-command-combines-filters
  ;; Test list-command combines multiple filters correctly
  (testing "list-command"
    (testing "combines multiple filters"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :status :open :title "Simple Bug" :description "" :design ""
                                :category "simple" :type :bug :meta {} :relations []}
                               {:id 2 :status :closed :title "Simple Task" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}
                               {:id 3 :status :open :title "Medium Bug" :description "" :design ""
                                :category "medium" :type :bug :meta {} :relations []}
                               {:id 4 :status :open :title "Simple Feature" :description "" :design ""
                                :category "simple" :type :feature :meta {} :relations []}])

      (let [result (sut/list-command (h/test-config) {:category "simple"
                                                      :status :open
                                                      :type :bug})]
        (is (= 1 (count (:tasks result))))
        (is (= "Simple Bug" (:title (first (:tasks result)))))
        (is (= "open" (:status (first (:tasks result)))))
        (is (= "simple" (:category (first (:tasks result)))))
        (is (= "bug" (:type (first (:tasks result)))))))))

(deftest list-command-returns-empty-results
  ;; Test list-command returns empty results when no matches
  (testing "list-command"
    (testing "returns empty results"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :status :open :title "Task One" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}])

      (let [result (sut/list-command (h/test-config) {:category "nonexistent"})]
        (is (= 0 (count (:tasks result))))
        (is (= 0 (get-in result [:metadata :count])))
        (is (= 0 (get-in result [:metadata :total-matches])))
        (is (false? (get-in result [:metadata :limited?])))))))

(deftest list-command-strips-format-option
  ;; Test list-command removes :format from tool args
  (testing "list-command"
    (testing "strips format option"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :status :open :title "Task One" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}])

      ;; Should not error even with :format in parsed-args
      (let [result (sut/list-command (h/test-config) {:format :human})]
        (is (= 1 (count (:tasks result))))
        (is (= "Task One" (:title (first (:tasks result)))))))))

;; show-command tests

(deftest show-command-returns-single-task
  ;; Test show-command returns a single task by ID
  (testing "show-command"
    (testing "returns single task by task-id"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :status :open :title "Task One" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}
                               {:id 2 :status :open :title "Task Two" :description "" :design ""
                                :category "medium" :type :bug :meta {} :relations []}
                               {:id 3 :status :closed :title "Task Three" :description "" :design ""
                                :category "large" :type :feature :meta {} :relations []}])

      (let [result (sut/show-command (h/test-config) {:task-id 2})
            task (:task result)]
        (is (some? task))
        (is (= "Task Two" (:title task)))
        (is (= 2 (:id task)))
        (is (= "medium" (:category task)))
        (is (= "bug" (:type task)))
        ;; Should not have :tasks key
        (is (nil? (:tasks result)))))))

(deftest show-command-sets-unique-automatically
  ;; Test show-command sets unique: true automatically
  (testing "show-command"
    (testing "sets unique: true"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :status :open :title "Task One" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}])

      ;; Even if unique is not in parsed-args, it should be set
      (let [result (sut/show-command (h/test-config) {:task-id 1})
            task (:task result)]
        (is (some? task))
        (is (= "Task One" (:title task)))
        ;; Should not have :tasks key
        (is (nil? (:tasks result)))))))

(deftest show-command-error-task-not-found
  ;; Test show-command errors when task not found
  (testing "show-command"
    (testing "errors when task not found"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :status :open :title "Task One" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}])

      (let [result (sut/show-command (h/test-config) {:task-id 999})]
        (is (contains? result :error))
        (is (= "No task found with the specified task-id" (:error result)))
        (is (= 999 (get-in result [:metadata :task-id])))))))

(deftest show-command-strips-format-option
  ;; Test show-command removes :format from tool args
  (testing "show-command"
    (testing "strips format option"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :status :open :title "Task One" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}])

      ;; Should not error even with :format in parsed-args
      (let [result (sut/show-command (h/test-config) {:task-id 1 :format :json})
            task (:task result)]
        (is (some? task))
        (is (= "Task One" (:title task)))
        ;; Should not have :tasks key
        (is (nil? (:tasks result)))))))

;; add-command tests

(deftest add-command-creates-task-with-required-fields
  (testing "add-command"
    (testing "creates task with required fields only"
      (h/write-ednl-test-file "tasks.ednl" [])

      (let [result (sut/add-command
                     (h/test-config)
                     {:category "simple"
                      :title "New task"})]
        (is (= "New task" (:title (:task result))))
        (is (= "simple" (:category (:task result))))
        (is (= "task" (:type (:task result))))
        (is (= "open" (:status (:task result))))))))

(deftest add-command-creates-child-task
  (testing "add-command"
    (testing "creates child task with parent-id"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 50 :status :open :title "Parent Task" :description "" :design ""
                                :category "large" :type :story :meta {} :relations []}])

      (let [result (sut/add-command
                     (h/test-config)
                     {:category "simple"
                      :title "Child task"
                      :parent-id 50})]
        (is (= "Child task" (:title (:task result))))
        (is (= 50 (:parent-id (:task result))))))))

(deftest add-command-strips-format-option
  (testing "add-command"
    (testing "strips format option"
      (h/write-ednl-test-file "tasks.ednl" [])

      (let [result (sut/add-command
                     (h/test-config)
                     {:category "simple"
                      :title "New task"
                      :format :json})]
        (is (= "New task" (:title (:task result))))))))

(deftest add-command-persists-to-file
  (testing "add-command"
    (testing "persists task to tasks.ednl"
      (h/write-ednl-test-file "tasks.ednl" [])

      (sut/add-command
        (h/test-config)
        {:category "simple"
         :title "Persisted Task"
         :description "Should be in file"})

      (let [tasks-file-path (str h/*test-dir* "/.mcp-tasks/tasks.ednl")
            tasks (tasks-file/read-ednl tasks-file-path)]
        (is (= 1 (count tasks)))
        (is (= "Persisted Task" (:title (first tasks))))))))

;; update-command tests

(deftest update-command-updates-title
  (testing "update-command"
    (testing "updates task title"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :status :open :title "Old Title" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}])

      (let [result (sut/update-command
                     (h/test-config)
                     {:task-id 1
                      :title "New Title"})]
        (is (= "New Title" (:title (:task result))))
        (is (= 1 (:id (:task result))))))))

(deftest update-command-updates-multiple-fields
  (testing "update-command"
    (testing "updates multiple fields at once"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :status :open :title "Old Title" :description "Old desc" :design ""
                                :category "simple" :type :task :meta {} :relations []}])

      (let [result (sut/update-command
                     (h/test-config)
                     {:task-id 1
                      :title "New Title"
                      :status :in-progress
                      :category "medium"
                      :type :bug})
            task (:task result)]
        (is (= "New Title" (:title task)))
        (is (= "in-progress" (:status task)))
        (is (= "medium" (:category task)))
        (is (= "bug" (:type task)))))))

(deftest update-command-strips-format-option
  (testing "update-command"
    (testing "strips format option"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :status :open :title "Task" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}])

      (let [result (sut/update-command
                     (h/test-config)
                     {:task-id 1
                      :title "Updated"
                      :format :json})]
        (is (= "Updated" (:title (:task result))))))))

(deftest update-command-persists-to-file
  (testing "update-command"
    (testing "persists changes to tasks.ednl"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :status :open :title "Original" :description "Original desc" :design ""
                                :category "simple" :type :task :meta {} :relations []}])

      (sut/update-command
        (h/test-config)
        {:task-id 1
         :title "Updated Title"
         :description "Updated description"
         :status :in-progress})

      (let [tasks-file-path (str h/*test-dir* "/.mcp-tasks/tasks.ednl")
            tasks (tasks-file/read-ednl tasks-file-path)]
        (is (= 1 (count tasks)))
        (let [task (first tasks)]
          (is (= "Updated Title" (:title task)))
          (is (= "Updated description" (:description task)))
          (is (= :in-progress (:status task))))))))

;; complete-command tests

(deftest complete-command-completes-task-with-task-id
  (testing "complete-command"
    (testing "completes task using task-id"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :status :open :title "Task to complete" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}
                               {:id 2 :status :open :title "Other task" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}])

      (let [result (sut/complete-command
                     (h/test-config)
                     {:task-id 1})]
        (is (= "Task to complete" (:title (:task result))))
        (is (= "closed" (:status (:task result))))
        (is (= 1 (:id (:task result))))))))

(deftest complete-command-completes-task-with-title
  (testing "complete-command"
    (testing "completes task using title"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :status :open :title "Unique task title" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}])

      (let [result (sut/complete-command
                     (h/test-config)
                     {:title "Unique task title"})]
        (is (= "Unique task title" (:title (:task result))))
        (is (= "closed" (:status (:task result))))))))

(deftest complete-command-adds-completion-comment
  (testing "complete-command"
    (testing "appends completion comment to description"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :status :open :title "Task" :description "Original description" :design ""
                                :category "simple" :type :task :meta {} :relations []}])

      (let [result (sut/complete-command
                     (h/test-config)
                     {:task-id 1
                      :completion-comment "Fixed via PR #42"})]
        (is (= "closed" (:status (:task result))))
        (is (re-find #"Fixed via PR #42" (:description (:task result))))))))

(deftest complete-command-strips-format-option
  (testing "complete-command"
    (testing "strips format option"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :status :open :title "Task" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}])

      (let [result (sut/complete-command
                     (h/test-config)
                     {:task-id 1
                      :format :json})]
        (is (= "closed" (:status (:task result))))))))

(deftest complete-command-persists-to-file
  (testing "complete-command"
    (testing "moves completed task to complete.ednl"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :status :open :title "Task to complete" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}
                               {:id 2 :status :open :title "Other task" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}])

      (sut/complete-command
        (h/test-config)
        {:task-id 1})

      (let [tasks-file-path (str h/*test-dir* "/.mcp-tasks/tasks.ednl")
            complete-file-path (str h/*test-dir* "/.mcp-tasks/complete.ednl")
            tasks (tasks-file/read-ednl tasks-file-path)
            complete (tasks-file/read-ednl complete-file-path)]
        ;; Task removed from tasks.ednl
        (is (= 1 (count tasks)))
        (is (= "Other task" (:title (first tasks))))
        ;; Task added to complete.ednl with :closed status
        (is (= 1 (count complete)))
        (is (= "Task to complete" (:title (first complete))))
        (is (= :closed (:status (first complete))))))))

;; delete-command tests

(deftest delete-command-deletes-task-with-task-id
  (testing "delete-command"
    (testing "deletes task using task-id"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :status :open :title "Task to delete" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}
                               {:id 2 :status :open :title "Other task" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}])

      (let [result (sut/delete-command
                     (h/test-config)
                     {:task-id 1})]
        (is (= "Task to delete" (:title (:deleted result))))
        (is (= "deleted" (:status (:deleted result))))
        (is (= 1 (:id (:deleted result))))))))

(deftest delete-command-deletes-task-with-title-pattern
  (testing "delete-command"
    (testing "deletes task using title-pattern"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :status :open :title "Unique task to delete" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}])

      (let [result (sut/delete-command
                     (h/test-config)
                     {:title-pattern "Unique task to delete"})]
        (is (= "Unique task to delete" (:title (:deleted result))))
        (is (= "deleted" (:status (:deleted result))))))))

(deftest delete-command-errors-with-non-closed-children
  (testing "delete-command"
    (testing "errors when task has non-closed children"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 10 :status :open :title "Parent task" :description "" :design ""
                                :category "large" :type :story :meta {} :relations []}
                               {:id 11 :status :open :title "Child task" :description "" :design ""
                                :category "simple" :type :task :parent-id 10 :meta {} :relations []}])

      (let [result (sut/delete-command
                     (h/test-config)
                     {:task-id 10})]
        (is (some? (:error result)))
        (is (re-find #"Cannot delete task with children" (:error result)))
        (is (= 1 (get-in result [:metadata :child-count])))))))

(deftest delete-command-strips-format-option
  (testing "delete-command"
    (testing "strips format option"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :status :open :title "Task" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}])

      (let [result (sut/delete-command
                     (h/test-config)
                     {:task-id 1
                      :format :json})]
        (is (= "deleted" (:status (:deleted result))))))))

(deftest delete-command-persists-to-file
  (testing "delete-command"
    (testing "moves deleted task to complete.ednl with :deleted status"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :status :open :title "Task to delete" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}
                               {:id 2 :status :open :title "Other task" :description "" :design ""
                                :category "simple" :type :task :meta {} :relations []}])

      (sut/delete-command
        (h/test-config)
        {:task-id 1})

      (let [tasks-file-path (str h/*test-dir* "/.mcp-tasks/tasks.ednl")
            complete-file-path (str h/*test-dir* "/.mcp-tasks/complete.ednl")
            tasks (tasks-file/read-ednl tasks-file-path)
            complete (tasks-file/read-ednl complete-file-path)]
        ;; Task removed from tasks.ednl
        (is (= 1 (count tasks)))
        (is (= "Other task" (:title (first tasks))))
        ;; Task added to complete.ednl with :deleted status
        (is (= 1 (count complete)))
        (is (= "Task to delete" (:title (first complete))))
        (is (= :deleted (:status (first complete))))))))
