(ns mcp-tasks.tool.update-task-test
  (:require
    [clojure.data.json :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-tasks.tasks :as tasks]
    [mcp-tasks.test-helpers :as h]
    [mcp-tasks.tool.update-task :as sut]))

(use-fixtures :each h/test-fixture)

(deftest update-task-updates-title-field
  ;; Tests updating the title field of an existing task
  (testing "update-task"
    (testing "updates title field"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :parent-id nil :title "orig title" :description "desc" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (h/test-config)
                    nil
                    {:task-id 1 :title "new title"})]
        (is (false? (:isError result)))
        (is (str/includes? (get-in result [:content 0 :text]) "Task 1 updated"))
        ;; Verify task file has updated title
        (let [tasks (h/read-ednl-test-file "tasks.ednl")
              task (first tasks)]
          (is (= "new title" (:title task)))
          (is (= "desc" (:description task))))))))

(deftest update-task-updates-description-field
  ;; Tests updating the description field of an existing task
  (testing "update-task"
    (testing "updates description field"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :parent-id nil :title "task" :description "old desc" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (h/test-config)
                    nil
                    {:task-id 1 :description "new desc"})]
        (is (false? (:isError result)))
        (let [tasks (h/read-ednl-test-file "tasks.ednl")
              task (first tasks)]
          (is (= "new desc" (:description task)))
          (is (= "task" (:title task))))))))

(deftest update-task-updates-design-field
  ;; Tests updating the design field of an existing task
  (testing "update-task"
    (testing "updates design field"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :parent-id nil :title "task" :description "" :design "old design" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (h/test-config)
                    nil
                    {:task-id 1 :design "new design"})]
        (is (false? (:isError result)))
        (let [tasks (h/read-ednl-test-file "tasks.ednl")
              task (first tasks)]
          (is (= "new design" (:design task))))))))

(deftest update-task-updates-status-field
  ;; Tests updating the status field of an existing task
  (testing "update-task"
    (testing "updates status field"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (h/test-config)
                    nil
                    {:task-id 1 :status "in-progress"})]
        (is (false? (:isError result)))
        (let [tasks (h/read-ednl-test-file "tasks.ednl")
              task (first tasks)]
          (is (= :in-progress (:status task))))))))

(deftest update-task-updates-category-field
  ;; Tests updating the category field of an existing task
  (testing "update-task"
    (testing "updates category field"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "old-cat" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (h/test-config)
                    nil
                    {:task-id 1 :category "new-cat"})]
        (is (false? (:isError result)))
        (let [tasks (h/read-ednl-test-file "tasks.ednl")
              task (first tasks)]
          (is (= "new-cat" (:category task))))))))

(deftest update-task-updates-type-field
  ;; Tests updating the type field of an existing task
  (testing "update-task"
    (testing "updates type field"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (h/test-config)
                    nil
                    {:task-id 1 :type "bug"})]
        (is (false? (:isError result)))
        (let [tasks (h/read-ednl-test-file "tasks.ednl")
              task (first tasks)]
          (is (= :bug (:type task))))))))

(deftest update-task-updates-parent-id-field
  ;; Tests updating the parent-id field to link a task to a parent
  (testing "update-task"
    (testing "updates parent-id field"
      ;; Create parent and child task
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :parent-id nil :title "parent" :description "" :design "" :category "test" :type :story :status :open :meta {} :relations []}
                               {:id 2 :parent-id nil :title "child" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (h/test-config)
                    nil
                    {:task-id 2 :parent-id 1})]
        (is (false? (:isError result)))
        (let [tasks (h/read-ednl-test-file "tasks.ednl")
              child-task (second tasks)]
          (is (= 1 (:parent-id child-task))))))))

(deftest update-task-updates-meta-field
  ;; Tests updating the meta field with a new map
  (testing "update-task"
    (testing "updates meta field"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {"old-key" "old-val"} :relations []}])
      (let [result (#'sut/update-task-impl
                    (h/test-config)
                    nil
                    {:task-id 1 :meta {"new-key" "new-val"}})]
        (is (false? (:isError result)))
        (let [tasks (h/read-ednl-test-file "tasks.ednl")
              task (first tasks)]
          (is (= {"new-key" "new-val"} (:meta task))))))))

(deftest update-task-updates-relations-field
  ;; Tests updating the relations field with a new vector
  (testing "update-task"
    (testing "updates relations field"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :parent-id nil :title "task1" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}
                               {:id 2 :parent-id nil :title "task2" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (h/test-config)
                    nil
                    {:task-id 1 :relations [{"id" 1 "relates-to" 2 "as-type" "blocked-by"}]})]
        (is (false? (:isError result)))
        (let [tasks (h/read-ednl-test-file "tasks.ednl")
              task (first tasks)]
          (is (= [{:id 1 :relates-to 2 :as-type :blocked-by}] (:relations task))))))))

(deftest update-task-updates-multiple-fields
  ;; Tests updating multiple fields in a single call
  (testing "update-task"
    (testing "updates multiple fields in single call"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :parent-id nil :title "old" :description "old desc" :design "" :category "old-cat" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (h/test-config)
                    nil
                    {:task-id 1
                     :title "new"
                     :description "new desc"
                     :status "in-progress"})]
        (is (false? (:isError result)))
        (let [tasks (h/read-ednl-test-file "tasks.ednl")
              task (first tasks)]
          (is (= "new" (:title task)))
          (is (= "new desc" (:description task)))
          (is (= :in-progress (:status task)))
          (is (= "old-cat" (:category task))))))))

(deftest update-task-clears-parent-id-with-nil
  ;; Tests clearing parent-id by passing nil
  (testing "update-task"
    (testing "clears parent-id when nil is provided"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :parent-id nil :title "parent" :description "" :design "" :category "test" :type :story :status :open :meta {} :relations []}
                               {:id 2 :parent-id 1 :title "child" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (h/test-config)
                    nil
                    {:task-id 2 :parent-id nil})]
        (is (false? (:isError result)))
        (let [tasks (h/read-ednl-test-file "tasks.ednl")
              child-task (second tasks)]
          (is (nil? (:parent-id child-task))))))))

(deftest update-task-clears-meta-with-nil
  ;; Tests clearing meta map by passing nil
  (testing "update-task"
    (testing "clears meta map when nil is provided"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {"key" "val"} :relations []}])
      (let [result (#'sut/update-task-impl
                    (h/test-config)
                    nil
                    {:task-id 1 :meta nil})]
        (is (false? (:isError result)))
        (let [tasks (h/read-ednl-test-file "tasks.ednl")
              task (first tasks)]
          (is (= {} (:meta task))))))))

(deftest update-task-clears-relations-with-nil
  ;; Tests clearing relations vector by passing nil
  (testing "update-task"
    (testing "clears relations vector when nil is provided"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :parent-id nil :title "task1" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations [{:id 1 :relates-to 2 :as-type :related}]}
                               {:id 2 :parent-id nil :title "task2" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (h/test-config)
                    nil
                    {:task-id 1 :relations nil})]
        (is (false? (:isError result)))
        (let [tasks (h/read-ednl-test-file "tasks.ednl")
              task (first tasks)]
          (is (= [] (:relations task))))))))

(deftest update-task-validates-invalid-status
  ;; Tests that invalid status values return a validation error
  (testing "update-task"
    (testing "returns error for invalid status value"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (h/test-config)
                    nil
                    {:task-id 1 :status "invalid-status"})]
        (is (true? (:isError result)))
        (is (str/includes? (get-in result [:content 0 :text]) "Invalid task field values"))))))

(deftest update-task-validates-invalid-type
  ;; Tests that invalid type values return a validation error
  (testing "update-task"
    (testing "returns error for invalid type value"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (h/test-config)
                    nil
                    {:task-id 1 :type "invalid-type"})]
        (is (true? (:isError result)))
        (is (str/includes? (get-in result [:content 0 :text]) "Invalid task field values"))))))

(deftest update-task-validates-parent-id-exists
  ;; Tests that referencing a non-existent parent-id returns an error
  (testing "update-task"
    (testing "returns error when parent-id does not exist"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (h/test-config)
                    nil
                    {:task-id 1 :parent-id 999})]
        (is (true? (:isError result)))
        (is (= "Parent task not found" (get-in result [:content 0 :text])))
        ;; Verify structured error data
        (let [data-content (second (:content result))
              data (json/read-str (:text data-content) :key-fn keyword)]
          (is (= "Parent task not found" (:error data)))
          (is (= 999 (get-in data [:metadata :parent-id]))))))))

(deftest update-task-validates-task-exists
  ;; Tests that updating a non-existent task returns an error
  (testing "update-task"
    (testing "returns error when task does not exist"
      (h/write-ednl-test-file "tasks.ednl" [])
      (let [result (#'sut/update-task-impl
                    (h/test-config)
                    nil
                    {:task-id 999 :title "new title"})]
        (is (true? (:isError result)))
        (is (str/includes? (get-in result [:content 0 :text]) "Task not found"))))))

(deftest update-task-meta-replaces-not-merges
  ;; Tests that meta field is replaced entirely, not merged
  (testing "update-task"
    (testing "meta field replacement behavior"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {"old-key" "old-val" "keep-key" "keep-val"} :relations []}])
      (let [result (#'sut/update-task-impl
                    (h/test-config)
                    nil
                    {:task-id 1 :meta {"new-key" "new-val"}})]
        (is (false? (:isError result)))
        (let [tasks (h/read-ednl-test-file "tasks.ednl")
              task (first tasks)]
          ;; Meta should be completely replaced, not merged
          (is (= {"new-key" "new-val"} (:meta task)))
          (is (not (contains? (:meta task) "old-key")))
          (is (not (contains? (:meta task) "keep-key"))))))))

(deftest update-task-relations-replaces-not-appends
  ;; Tests that relations field is replaced entirely, not appended
  (testing "update-task"
    (testing "relations field replacement behavior"
      (h/write-ednl-test-file "tasks.ednl"
                              [{:id 1 :parent-id nil :title "task1" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations [{:id 1 :relates-to 2 :as-type :related}]}
                               {:id 2 :parent-id nil :title "task2" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}
                               {:id 3 :parent-id nil :title "task3" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (h/test-config)
                    nil
                    {:task-id 1 :relations [{"id" 2 "relates-to" 3 "as-type" "blocked-by"}]})]
        (is (false? (:isError result)))
        (let [tasks (h/read-ednl-test-file "tasks.ednl")
              task (first tasks)]
          ;; Relations should be completely replaced, not appended
          (is (= 1 (count (:relations task))))
          (is (= [{:id 2 :relates-to 3 :as-type :blocked-by}] (:relations task))))))))
