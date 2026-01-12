(ns mcp-tasks.tool.update-task-test
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.test-helpers :as h]
    [mcp-tasks.tool.update-task :as sut]))

(deftest update-task-updates-title-field
  (h/with-test-setup [test-dir]
    ;; Tests updating the title field of an existing task
    (testing "update-task"
      (testing "updates title field"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "orig title" :description "desc" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :title "new title"})]
          (is (false? (:isError result)))
          (is (str/includes? (get-in result [:content 0 :text]) "Task 1 updated"))
          ;; Verify task file has updated title
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task (first tasks)]
            (is (= "new title" (:title task)))
            (is (= "desc" (:description task)))))))))

(deftest update-task-updates-description-field
  (h/with-test-setup [test-dir]
    ;; Tests updating the description field of an existing task
    (testing "update-task"
      (testing "updates description field"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "task" :description "old desc" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :description "new desc"})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task (first tasks)]
            (is (= "new desc" (:description task)))
            (is (= "task" (:title task)))))))))

(deftest update-task-updates-design-field
  (h/with-test-setup [test-dir]
    ;; Tests updating the design field of an existing task
    (testing "update-task"
      (testing "updates design field"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "task" :description "" :design "old design" :category "test" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :design "new design"})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task (first tasks)]
            (is (= "new design" (:design task)))))))))

(deftest update-task-updates-status-field
  (h/with-test-setup [test-dir]
    ;; Tests updating the status field of an existing task
    (testing "update-task"
      (testing "updates status field"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :status "in-progress"})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task (first tasks)]
            (is (= :in-progress (:status task)))))))))

(deftest update-task-updates-category-field
  (h/with-test-setup [test-dir]
    ;; Tests updating the category field of an existing task
    (testing "update-task"
      (testing "updates category field"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "old-cat" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :category "new-cat"})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task (first tasks)]
            (is (= "new-cat" (:category task)))))))))

(deftest update-task-updates-type-field
  (h/with-test-setup [test-dir]
    ;; Tests updating the type field of an existing task
    (testing "update-task"
      (testing "updates type field"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :type "bug"})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task (first tasks)]
            (is (= :bug (:type task)))))))))

(deftest update-task-updates-parent-id-field
  (h/with-test-setup [test-dir]
    ;; Tests updating the parent-id field to link a task to a parent
    (testing "update-task"
      (testing "updates parent-id field"
        ;; Create parent and child task
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "parent" :description "" :design "" :category "test" :type :story :status :open :meta {} :relations []}
           {:id 2 :parent-id nil :title "child" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 2 :parent-id 1})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                child-task (second tasks)]
            (is (= 1 (:parent-id child-task)))))))))

(deftest update-task-updates-meta-field
  (h/with-test-setup [test-dir]
    ;; Tests updating the meta field with a new map
    (testing "update-task"
      (testing "updates meta field"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {"old-key" "old-val"} :relations []}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :meta {"new-key" "new-val"}})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task (first tasks)]
            (is (= {"new-key" "new-val"} (:meta task)))))))))

(deftest update-task-updates-relations-field
  (h/with-test-setup [test-dir]
    ;; Tests updating the relations field with a new vector
    (testing "update-task"
      (testing "updates relations field"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "task1" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}
           {:id 2 :parent-id nil :title "task2" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :relations [{"id" 1 "relates-to" 2 "as-type" "blocked-by"}]})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task (first tasks)]
            (is (= [{:id 1 :relates-to 2 :as-type :blocked-by}] (:relations task)))))))))

(deftest update-task-updates-multiple-fields
  (h/with-test-setup [test-dir]
    ;; Tests updating multiple fields in a single call
    (testing "update-task"
      (testing "updates multiple fields in single call"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "old" :description "old desc" :design "" :category "old-cat" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1
                       :title "new"
                       :description "new desc"
                       :status "in-progress"})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task (first tasks)]
            (is (= "new" (:title task)))
            (is (= "new desc" (:description task)))
            (is (= :in-progress (:status task)))
            (is (= "old-cat" (:category task)))))))))

(deftest update-task-clears-parent-id-with-nil
  (h/with-test-setup [test-dir]
    ;; Tests clearing parent-id by passing nil
    (testing "update-task"
      (testing "clears parent-id when nil is provided"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "parent" :description "" :design "" :category "test" :type :story :status :open :meta {} :relations []}
           {:id 2 :parent-id 1 :title "child" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 2 :parent-id nil})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                child-task (second tasks)]
            (is (nil? (:parent-id child-task)))))))))

(deftest update-task-clears-meta-with-nil
  (h/with-test-setup [test-dir]
    ;; Tests clearing meta map by passing nil
    (testing "update-task"
      (testing "clears meta map when nil is provided"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {"key" "val"} :relations []}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :meta nil})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task (first tasks)]
            (is (= {} (:meta task)))))))))

(deftest update-task-clears-relations-with-nil
  (h/with-test-setup [test-dir]
    ;; Tests clearing relations vector by passing nil
    (testing "update-task"
      (testing "clears relations vector when nil is provided"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "task1" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations [{:id 1 :relates-to 2 :as-type :related}]}
           {:id 2 :parent-id nil :title "task2" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :relations nil})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task (first tasks)]
            (is (= [] (:relations task)))))))))

(deftest update-task-validates-invalid-status
  (h/with-test-setup [test-dir]
    ;; Tests that invalid status values return a validation error
    (testing "update-task"
      (testing "returns error for invalid status value"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :status "invalid-status"})]
          (is (true? (:isError result)))
          (is (str/includes? (get-in result [:content 0 :text]) "Invalid task field values")))))))

(deftest update-task-validates-invalid-type
  (h/with-test-setup [test-dir]
    ;; Tests that invalid type values return a validation error
    (testing "update-task"
      (testing "returns error for invalid type value"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :type "invalid-type"})]
          (is (true? (:isError result)))
          (is (str/includes? (get-in result [:content 0 :text]) "Invalid task field values")))))))

(deftest update-task-validates-parent-id-exists
  (h/with-test-setup [test-dir]
    ;; Tests that referencing a non-existent parent-id returns an error
    (testing "update-task"
      (testing "returns error when parent-id does not exist"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :parent-id 999})]
          (is (true? (:isError result)))
          (is (= "Parent task not found" (get-in result [:content 0 :text])))
          ;; Verify structured error data
          (let [data-content (second (:content result))
                data (json/parse-string (:text data-content) keyword)]
            (is (= "Parent task not found" (:error data)))
            (is (= 999 (get-in data [:metadata :parent-id])))))))))

(deftest update-task-validates-task-exists
  (h/with-test-setup [test-dir]
    ;; Tests that updating a non-existent task returns an error
    (testing "update-task"
      (testing "returns error when task does not exist"
        (h/write-ednl-test-file test-dir "tasks.ednl" [])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 999 :title "new title"})]
          (is (true? (:isError result)))
          (is (str/includes? (get-in result [:content 0 :text]) "Task not found")))))))

(deftest update-task-meta-replaces-not-merges
  (h/with-test-setup [test-dir]
    ;; Tests that meta field is replaced entirely, not merged
    (testing "update-task"
      (testing "meta field replacement behavior"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {"old-key" "old-val" "keep-key" "keep-val"} :relations []}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :meta {"new-key" "new-val"}})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task (first tasks)]
            ;; Meta should be completely replaced, not merged
            (is (= {"new-key" "new-val"} (:meta task)))
            (is (not (contains? (:meta task) "old-key")))
            (is (not (contains? (:meta task) "keep-key")))))))))

(deftest update-task-relations-replaces-not-appends
  (h/with-test-setup [test-dir]
    ;; Tests that relations field is replaced entirely, not appended
    (testing "update-task"
      (testing "relations field replacement behavior"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "task1" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations [{:id 1 :relates-to 2 :as-type :related}]}
           {:id 2 :parent-id nil :title "task2" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}
           {:id 3 :parent-id nil :title "task3" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :relations [{"id" 2 "relates-to" 3 "as-type" "blocked-by"}]})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task (first tasks)]
            ;; Relations should be completely replaced, not appended
            (is (= 1 (count (:relations task))))
            (is (= [{:id 2 :relates-to 3 :as-type :blocked-by}] (:relations task)))))))))

(deftest update-task-validates-meta-string-values
  (h/with-test-setup [test-dir]
    ;; Tests that meta field values are coerced to strings to match schema
    (testing "update-task"
      (testing "validates meta values are strings"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :meta {"refined" "true" "priority" "high"}})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task (first tasks)]
            ;; Meta values should be strings, not keywords
            (is (= {"refined" "true" "priority" "high"} (:meta task)))
            ;; Verify schema compliance - all keys and values must be strings
            (is (every? string? (keys (:meta task))))
            (is (every? string? (vals (:meta task))))))))))

(deftest update-task-prevents-simple-self-cycle
  (h/with-test-setup [test-dir]
    ;; Tests preventing A → A circular dependency
    ;; Contracts: Cannot create self-referencing blocked-by relation
    (testing "update-task"
      (testing "prevents simple A → A self-cycle"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "task1" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :relations [{"id" 1 "relates-to" 1 "as-type" "blocked-by"}]})]
          (is (true? (:isError result)))
          (is (str/includes? (get-in result [:content 0 :text]) "Circular dependency detected"))
          ;; Verify structured error includes cycle path
          (let [data-content (second (:content result))
                data (json/parse-string (:text data-content) keyword)]
            (is (some? (get-in data [:metadata :cycle])))
            ;; Cycle should start and end with same task ID
            (let [cycle (get-in data [:metadata :cycle])]
              (is (= (first cycle) (last cycle)))
              (is (= 1 (first cycle))))))))))

(deftest update-task-prevents-two-task-cycle
  (h/with-test-setup [test-dir]
    ;; Tests preventing A → B → A circular dependency
    ;; Contracts: Detects cycle when task 1 blocked-by task 2, and task 2 blocked-by task 1
    (testing "update-task"
      (testing "prevents A → B → A cycle"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "task1" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations [{:id 1 :relates-to 2 :as-type :blocked-by}]}
           {:id 2 :parent-id nil :title "task2" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        ;; Try to add blocked-by from task 2 to task 1 (creates cycle)
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 2 :relations [{"id" 1 "relates-to" 1 "as-type" "blocked-by"}]})]
          (is (true? (:isError result)))
          (is (str/includes? (get-in result [:content 0 :text]) "Circular dependency detected"))
          ;; Verify cycle path is provided
          (let [data-content (second (:content result))
                data (json/parse-string (:text data-content) keyword)
                cycle (get-in data [:metadata :cycle])]
            (is (some? cycle))
            (is (= (first cycle) (last cycle)))))))))

(deftest update-task-prevents-three-task-cycle
  (h/with-test-setup [test-dir]
    ;; Tests preventing A → B → C → A circular dependency
    ;; Contracts: Detects longer cycles in blocked-by chains
    (testing "update-task"
      (testing "prevents A → B → C → A cycle"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "task1" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations [{:id 1 :relates-to 2 :as-type :blocked-by}]}
           {:id 2 :parent-id nil :title "task2" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations [{:id 1 :relates-to 3 :as-type :blocked-by}]}
           {:id 3 :parent-id nil :title "task3" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        ;; Try to add blocked-by from task 3 to task 1 (creates cycle)
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 3 :relations [{"id" 1 "relates-to" 1 "as-type" "blocked-by"}]})]
          (is (true? (:isError result)))
          (is (str/includes? (get-in result [:content 0 :text]) "Circular dependency detected"))
          ;; Verify cycle is returned
          (let [data-content (second (:content result))
                data (json/parse-string (:text data-content) keyword)
                cycle (get-in data [:metadata :cycle])]
            (is (some? cycle))
            (is (= (first cycle) (last cycle)))))))))

(deftest update-task-allows-valid-blocked-by-relations
  (h/with-test-setup [test-dir]
    ;; Tests that valid (non-circular) blocked-by relations are allowed
    ;; Contracts: Linear dependency chains are allowed
    (testing "update-task"
      (testing "allows valid non-circular blocked-by relations"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "task1" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}
           {:id 2 :parent-id nil :title "task2" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}
           {:id 3 :parent-id nil :title "task3" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        ;; Create linear chain: 3 blocked-by 2 blocked-by 1 (no cycle)
        (let [result1 (#'sut/update-task-impl
                       (h/test-config test-dir)
                       nil
                       {:task-id 2 :relations [{"id" 1 "relates-to" 1 "as-type" "blocked-by"}]})
              result2 (#'sut/update-task-impl
                       (h/test-config test-dir)
                       nil
                       {:task-id 3 :relations [{"id" 1 "relates-to" 2 "as-type" "blocked-by"}]})]
          (is (false? (:isError result1)))
          (is (false? (:isError result2)))
          ;; Verify relations were saved correctly
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task2 (second tasks)
                task3 (nth tasks 2)]
            (is (= [{:id 1 :relates-to 1 :as-type :blocked-by}] (:relations task2)))
            (is (= [{:id 1 :relates-to 2 :as-type :blocked-by}] (:relations task3)))))))))

(deftest update-task-detects-cycle-in-multiple-relations
  (h/with-test-setup [test-dir]
    ;; Tests detecting cycle when one of multiple blocked-by relations creates a cycle
    ;; Contracts: All blocked-by relations are checked for cycles
    (testing "update-task"
      (testing "detects cycle when one of multiple blocked-by relations creates cycle"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "task1" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations [{:id 1 :relates-to 2 :as-type :blocked-by}]}
           {:id 2 :parent-id nil :title "task2" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}
           {:id 3 :parent-id nil :title "task3" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        ;; Try to add multiple blocked-by relations where one creates a cycle
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 2 :relations [{"id" 1 "relates-to" 3 "as-type" "blocked-by"}
                                              {"id" 2 "relates-to" 1 "as-type" "blocked-by"}]})]
          (is (true? (:isError result)))
          (is (str/includes? (get-in result [:content 0 :text]) "Circular dependency detected")))))))

(deftest update-task-allows-non-blocked-by-relations
  (h/with-test-setup [test-dir]
    ;; Tests that non-blocked-by relations are not validated for cycles
    ;; Contracts: Only :blocked-by relations are checked for circular dependencies
    (testing "update-task"
      (testing "allows circular :related relations without error"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "task1" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations [{:id 1 :relates-to 2 :as-type :related}]}
           {:id 2 :parent-id nil :title "task2" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        ;; Create "circular" related relation (should be allowed)
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 2 :relations [{"id" 1 "relates-to" 1 "as-type" "related"}]})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task2 (second tasks)]
            (is (= [{:id 1 :relates-to 1 :as-type :related}] (:relations task2)))))))))

(deftest update-task-appends-shared-context-with-automatic-prefixing
  ;; Tests that shared-context entries are appended with automatic task ID prefix
  (h/with-test-setup [test-dir]
    (testing "update-task"
      (testing "appends to shared-context with automatic task ID prefix"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "story" :description "desc" :design "" :category "test" :type :story :status :open :meta {} :relations [] :shared-context ["Task 10: First entry"]}
           {:id 2 :parent-id 1 :title "task" :description "desc" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
        ;; Write execution state with task-id 2
        (spit (str test-dir "/.mcp-tasks-current.edn")
              (pr-str {:task-id 2 :story-id 1 :task-start-time "2025-01-01T00:00:00Z"}))
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :shared-context ["Second entry"]})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                story (first tasks)]
            (is (= ["Task 10: First entry" "Task 2: Second entry"] (:shared-context story)))))))))

(deftest update-task-appends-shared-context-without-prefix-when-no-execution-state
  ;; Tests that shared-context entries are added without prefix when execution state is missing
  (h/with-test-setup [test-dir]
    (testing "update-task"
      (testing "appends to shared-context without prefix when no execution state"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "story" :description "desc" :design "" :category "test" :type :story :status :open :meta {} :relations [] :shared-context ["Task 10: First entry"]}])
        ;; No execution state file
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :shared-context ["Manual entry"]})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                story (first tasks)]
            (is (= ["Task 10: First entry" "Manual entry"] (:shared-context story)))))))))

(deftest update-task-shared-context-appends-to-empty-context
  ;; Tests that shared-context can be added to tasks with no existing context
  (h/with-test-setup [test-dir]
    (testing "update-task"
      (testing "initializes and appends to empty shared-context"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "story" :description "desc" :design "" :category "test" :type :story :status :open :meta {} :relations []}])
        ;; Write execution state
        (spit (str test-dir "/.mcp-tasks-current.edn")
              (pr-str {:task-id 2 :story-id 1 :task-start-time "2025-01-01T00:00:00Z"}))
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :shared-context ["First entry"]})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                story (first tasks)]
            (is (= ["Task 2: First entry"] (:shared-context story)))))))))

(deftest update-task-shared-context-multiple-appends
  ;; Tests that multiple shared-context updates preserve all entries in order
  (h/with-test-setup [test-dir]
    (testing "update-task"
      (testing "preserves all entries across multiple appends"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "story" :description "desc" :design "" :category "test" :type :story :status :open :meta {} :relations []}])
        ;; First append with task 2
        (spit (str test-dir "/.mcp-tasks-current.edn")
              (pr-str {:task-id 2 :story-id 1 :task-start-time "2025-01-01T00:00:00Z"}))
        (#'sut/update-task-impl
         (h/test-config test-dir)
         nil
         {:task-id 1 :shared-context ["Entry from task 2"]})
        ;; Second append with task 3
        (spit (str test-dir "/.mcp-tasks-current.edn")
              (pr-str {:task-id 3 :story-id 1 :task-start-time "2025-01-01T00:00:00Z"}))
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :shared-context ["Entry from task 3"]})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                story (first tasks)]
            (is (= ["Task 2: Entry from task 2" "Task 3: Entry from task 3"] (:shared-context story)))))))))

(deftest update-task-shared-context-enforces-size-limit
  ;; Tests that shared-context size is limited to 50KB
  (h/with-test-setup [test-dir]
    (testing "update-task"
      (testing "enforces 50KB size limit on shared-context"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "story" :description "desc" :design "" :category "test" :type :story :status :open :meta {} :relations []}])
        ;; Create a large entry that will exceed 50KB
        (let [large-entry (apply str (repeat 52000 "x"))
              result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :shared-context [large-entry]})]
          (is (true? (:isError result)))
          (is (str/includes? (get-in result [:content 0 :text]) "Shared context size limit (50KB) exceeded")))))))

(deftest update-task-shared-context-works-with-other-fields
  ;; Tests that shared-context can be updated alongside other fields
  (h/with-test-setup [test-dir]
    (testing "update-task"
      (testing "updates shared-context alongside other fields"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "old title" :description "old desc" :design "" :category "test" :type :story :status :open :meta {} :relations []}])
        (spit (str test-dir "/.mcp-tasks-current.edn")
              (pr-str {:task-id 2 :story-id 1 :task-start-time "2025-01-01T00:00:00Z"}))
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :title "new title" :description "new desc" :shared-context ["Context entry"]})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                story (first tasks)]
            (is (= "new title" (:title story)))
            (is (= "new desc" (:description story)))
            (is (= ["Task 2: Context entry"] (:shared-context story)))))))))

(deftest update-task-shared-context-accepts-string-input
  ;; Tests that shared-context accepts a single string and wraps it in a vector
  (h/with-test-setup [test-dir]
    (testing "update-task"
      (testing "accepts string input for shared-context"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "story" :description "desc" :design "" :category "test" :type :story :status :open :meta {} :relations []}])
        (spit (str test-dir "/.mcp-tasks-current.edn")
              (pr-str {:task-id 2 :story-id 1 :task-start-time "2025-01-01T00:00:00Z"}))
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :shared-context "Single string entry"})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                story (first tasks)]
            (is (= ["Task 2: Single string entry"] (:shared-context story)))))))))

(deftest update-task-shared-context-accepts-string-without-execution-state
  ;; Tests that string input works without execution state
  (h/with-test-setup [test-dir]
    (testing "update-task"
      (testing "accepts string input without execution state"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "story" :description "desc" :design "" :category "test" :type :story :status :open :meta {} :relations []}])
        ;; No execution state file
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :shared-context "Manual string entry"})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                story (first tasks)]
            (is (= ["Manual string entry"] (:shared-context story)))))))))

(deftest update-task-shared-context-string-appends-to-existing
  ;; Tests that string input appends to existing context
  (h/with-test-setup [test-dir]
    (testing "update-task"
      (testing "string input appends to existing context"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "story" :description "desc" :design "" :category "test" :type :story :status :open :meta {} :relations [] :shared-context ["Task 5: First entry"]}])
        (spit (str test-dir "/.mcp-tasks-current.edn")
              (pr-str {:task-id 10 :story-id 1 :task-start-time "2025-01-01T00:00:00Z"}))
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :shared-context "Second entry"})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                story (first tasks)]
            (is (= ["Task 5: First entry" "Task 10: Second entry"] (:shared-context story)))))))))

(deftest update-task-shared-context-empty-string-is-no-op
  ;; Tests that empty string for shared-context is treated as a no-op
  (h/with-test-setup [test-dir]
    (testing "update-task"
      (testing "empty string for shared-context is a no-op"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "story" :description "desc" :design "" :category "test" :type :story :status :open :meta {} :relations [] :shared-context ["Task 5: Existing entry"]}])
        ;; Pass empty string for shared-context
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :title "new title" :shared-context ""})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                story (first tasks)]
            ;; Title should be updated, but shared-context unchanged
            (is (= "new title" (:title story)))
            (is (= ["Task 5: Existing entry"] (:shared-context story)))))))))

(deftest update-task-shared-context-empty-string-alone-returns-error
  ;; Tests that empty string shared-context alone returns "No fields to update"
  (h/with-test-setup [test-dir]
    (testing "update-task"
      (testing "empty string shared-context alone returns no-fields-to-update error"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "story" :description "desc" :design "" :category "test" :type :story :status :open :meta {} :relations []}])
        ;; Pass only empty string for shared-context
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :shared-context ""})]
          (is (true? (:isError result)))
          (is (str/includes? (get-in result [:content 0 :text]) "No fields to update")))))))

;; Session Events Tests

(deftest update-task-appends-session-event
  ;; Tests that session-events can be appended to a task
  (h/with-test-setup [test-dir]
    (testing "update-task"
      (testing "appends session event to task"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "story" :description "desc" :design "" :category "test" :type :story :status :open :meta {} :relations []}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :session-events [{"event-type" "user-prompt" "content" "test prompt"}]})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task (first tasks)
                events (:session-events task)]
            (is (= 1 (count events)))
            (is (= :user-prompt (:event-type (first events))))
            (is (= "test prompt" (:content (first events))))
            ;; Timestamp should be auto-generated
            (is (some? (:timestamp (first events))))))))))

(deftest update-task-session-event-auto-generates-timestamp
  ;; Tests that timestamp is auto-generated when not provided
  (h/with-test-setup [test-dir]
    (testing "update-task"
      (testing "auto-generates timestamp for session events"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "story" :description "desc" :design "" :category "test" :type :story :status :open :meta {} :relations []}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :session-events [{"event-type" "compaction" "trigger" "auto"}]})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task (first tasks)
                event (first (:session-events task))]
            (is (string? (:timestamp event)))
            ;; Should be ISO-8601 format
            (is (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}.*" (:timestamp event)))))))))

(deftest update-task-session-event-preserves-provided-timestamp
  ;; Tests that provided timestamp is preserved
  (h/with-test-setup [test-dir]
    (testing "update-task"
      (testing "preserves provided timestamp"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "story" :description "desc" :design "" :category "test" :type :story :status :open :meta {} :relations []}])
        (let [provided-ts "2025-01-15T10:30:00Z"
              result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :session-events [{"event-type" "session-start" "session-id" "abc123" "timestamp" provided-ts}]})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task (first tasks)
                event (first (:session-events task))]
            (is (= provided-ts (:timestamp event)))
            (is (= :session-start (:event-type event)))
            (is (= "abc123" (:session-id event)))))))))

(deftest update-task-session-events-appends-to-existing
  ;; Tests that new session events append to existing events
  (h/with-test-setup [test-dir]
    (testing "update-task"
      (testing "appends to existing session events"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "story" :description "desc" :design "" :category "test" :type :story :status :open :meta {} :relations []
            :session-events [{:timestamp "2025-01-01T00:00:00Z" :event-type :user-prompt :content "first"}]}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :session-events [{"event-type" "user-prompt" "content" "second"}]})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task (first tasks)
                events (:session-events task)]
            (is (= 2 (count events)))
            (is (= "first" (:content (first events))))
            (is (= "second" (:content (second events))))))))))

(deftest update-task-session-events-validates-event-type
  ;; Tests that invalid event-type is rejected
  (h/with-test-setup [test-dir]
    (testing "update-task"
      (testing "rejects invalid event-type"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "story" :description "desc" :design "" :category "test" :type :story :status :open :meta {} :relations []}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :session-events [{"event-type" "invalid-type"}]})]
          (is (true? (:isError result)))
          (is (str/includes? (get-in result [:content 0 :text]) "Invalid session event")))))))

(deftest update-task-session-events-enforces-size-limit
  ;; Tests that session-events size is limited to 50KB
  (h/with-test-setup [test-dir]
    (testing "update-task"
      (testing "enforces 50KB size limit on session-events"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "story" :description "desc" :design "" :category "test" :type :story :status :open :meta {} :relations []}])
        ;; Create a large content that will exceed 50KB
        (let [large-content (apply str (repeat 52000 "x"))
              result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :session-events [{"event-type" "user-prompt" "content" large-content}]})]
          (is (true? (:isError result)))
          (is (str/includes? (get-in result [:content 0 :text]) "Session events size limit (50KB) exceeded")))))))

(deftest update-task-session-events-accepts-single-event
  ;; Tests that a single event map (not array) is accepted
  (h/with-test-setup [test-dir]
    (testing "update-task"
      (testing "accepts single event map"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "story" :description "desc" :design "" :category "test" :type :story :status :open :meta {} :relations []}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :session-events {"event-type" "user-prompt" "content" "single event"}})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task (first tasks)
                events (:session-events task)]
            (is (= 1 (count events)))
            (is (= "single event" (:content (first events))))))))))

(deftest update-task-session-events-with-other-fields
  ;; Tests that session-events can be updated alongside other fields
  (h/with-test-setup [test-dir]
    (testing "update-task"
      (testing "updates session-events alongside other fields"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "old title" :description "old desc" :design "" :category "test" :type :story :status :open :meta {} :relations []}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :title "new title" :session-events [{"event-type" "user-prompt" "content" "test"}]})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task (first tasks)]
            (is (= "new title" (:title task)))
            (is (= 1 (count (:session-events task))))))))))

(deftest update-task-session-events-multiple-events
  ;; Tests that multiple events can be appended in one call
  (h/with-test-setup [test-dir]
    (testing "update-task"
      (testing "appends multiple events in one call"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "story" :description "desc" :design "" :category "test" :type :story :status :open :meta {} :relations []}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :session-events [{"event-type" "session-start" "session-id" "s1"}
                                                   {"event-type" "user-prompt" "content" "prompt 1"}
                                                   {"event-type" "compaction" "trigger" "auto"}]})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task (first tasks)
                events (:session-events task)]
            (is (= 3 (count events)))
            (is (= :session-start (:event-type (first events))))
            (is (= :user-prompt (:event-type (second events))))
            (is (= :compaction (:event-type (nth events 2))))))))))

;; Code-reviewed and pr-num field tests

(deftest update-task-sets-code-reviewed-field
  ;; Tests setting the code-reviewed field with an ISO-8601 timestamp
  (h/with-test-setup [test-dir]
    (testing "update-task"
      (testing "sets code-reviewed timestamp"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "story" :description "desc" :design "" :category "test" :type :story :status :open :meta {} :relations []}])
        (let [timestamp "2025-01-15T10:30:00Z"
              result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :code-reviewed timestamp})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task (first tasks)]
            (is (= timestamp (:code-reviewed task)))))))))

(deftest update-task-sets-pr-num-field
  ;; Tests setting the pr-num field with a PR number
  (h/with-test-setup [test-dir]
    (testing "update-task"
      (testing "sets pr-num field"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "story" :description "desc" :design "" :category "test" :type :story :status :open :meta {} :relations []}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :pr-num 123})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task (first tasks)]
            (is (= 123 (:pr-num task)))))))))

(deftest update-task-clears-code-reviewed-with-nil
  ;; Tests clearing code-reviewed field by passing nil
  (h/with-test-setup [test-dir]
    (testing "update-task"
      (testing "clears code-reviewed when nil is provided"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "story" :description "desc" :design "" :category "test" :type :story :status :open :meta {} :relations [] :code-reviewed "2025-01-15T10:30:00Z"}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :code-reviewed nil})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task (first tasks)]
            (is (nil? (:code-reviewed task)))))))))

(deftest update-task-clears-pr-num-with-nil
  ;; Tests clearing pr-num field by passing nil
  (h/with-test-setup [test-dir]
    (testing "update-task"
      (testing "clears pr-num when nil is provided"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "story" :description "desc" :design "" :category "test" :type :story :status :open :meta {} :relations [] :pr-num 42}])
        (let [result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :pr-num nil})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task (first tasks)]
            (is (nil? (:pr-num task)))))))))

(deftest update-task-sets-both-code-reviewed-and-pr-num
  ;; Tests setting both fields in a single update
  (h/with-test-setup [test-dir]
    (testing "update-task"
      (testing "sets both code-reviewed and pr-num together"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "story" :description "desc" :design "" :category "test" :type :story :status :open :meta {} :relations []}])
        (let [timestamp "2025-01-15T14:00:00Z"
              result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :code-reviewed timestamp :pr-num 456})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task (first tasks)]
            (is (= timestamp (:code-reviewed task)))
            (is (= 456 (:pr-num task)))))))))

(deftest update-task-overwrites-code-reviewed-timestamp
  ;; Tests that updating code-reviewed overwrites the previous value
  (h/with-test-setup [test-dir]
    (testing "update-task"
      (testing "overwrites existing code-reviewed timestamp"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "story" :description "desc" :design "" :category "test" :type :story :status :open :meta {} :relations [] :code-reviewed "2025-01-10T08:00:00Z"}])
        (let [new-timestamp "2025-01-15T16:30:00Z"
              result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :code-reviewed new-timestamp})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task (first tasks)]
            (is (= new-timestamp (:code-reviewed task)))))))))

(deftest update-task-code-reviewed-and-pr-num-with-other-fields
  ;; Tests updating code-reviewed and pr-num alongside other fields
  (h/with-test-setup [test-dir]
    (testing "update-task"
      (testing "updates code-reviewed and pr-num alongside other fields"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "old title" :description "desc" :design "" :category "test" :type :story :status :open :meta {} :relations []}])
        (let [timestamp "2025-01-15T18:00:00Z"
              result (#'sut/update-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :title "new title" :status "closed" :code-reviewed timestamp :pr-num 789})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task (first tasks)]
            (is (= "new title" (:title task)))
            (is (= :closed (:status task)))
            (is (= timestamp (:code-reviewed task)))
            (is (= 789 (:pr-num task)))))))))
