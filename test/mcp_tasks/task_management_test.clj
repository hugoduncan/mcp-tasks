(ns mcp-tasks.task-management-test
  "Integration tests for task management operations.
  
  Tests add-task and update-task operations including story task relationships,
  field updates, validation, and nil handling in EDN storage."
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-clj.mcp-client.core :as mcp-client]
    [mcp-tasks.integration-test-fixtures :as fixtures]
    [mcp-tasks.tasks-file :as tasks-file]))

(use-fixtures :each fixtures/with-test-project)

(deftest ^:integ add-task-with-story-name-test
  ;; Tests that add-task with story-name creates child tasks with parent-id in EDN storage.
  (testing "add-task tool with story-name"
    (testing "adds child task with parent-id to tasks.ednl"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Create a story task in tasks.ednl
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                story-task {:id 1
                            :title "new-story"
                            :description "Story description"
                            :design ""
                            :category "large"
                            :status :open
                            :type :story
                            :meta {}
                            :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story-task]))

          (let [result @(mcp-client/call-tool
                          client
                          "add-task"
                          {:category "simple"
                           :title "First task for story"
                           :parent-id 1})]
            (is (not (:isError result)))
            (is (re-find #"Task added" (-> result :content first :text)))

            ;; Verify task was added to tasks.ednl with parent-id
            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                  child-tasks (filter #(= (:parent-id %) 1) tasks)]
              (is (= 2 (count tasks)) "Should have story + child task")
              (is (= 1 (count child-tasks)) "Should have one child task")
              (let [child-task (first child-tasks)]
                (is (= "First task for story" (:title child-task)))
                (is (= "simple" (:category child-task)))
                (is (= :task (:type child-task)))
                (is (= :open (:status child-task)))
                (is (= 1 (:parent-id child-task))))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ add-task-with-story-name-append-test
  ;; Tests that add-task with story-name appends child tasks in correct order.
  (testing "add-task tool with story-name"
    (testing "appends child tasks to tasks.ednl"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Create a story task with one existing child in tasks.ednl
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                story-task {:id 1
                            :title "existing-story"
                            :description ""
                            :design ""
                            :category "large"
                            :status :open
                            :type :story
                            :meta {}
                            :relations []}
                first-child {:id 2
                             :parent-id 1
                             :title "First task"
                             :description ""
                             :design ""
                             :category "simple"
                             :status :open
                             :type :task
                             :meta {}
                             :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story-task first-child]))

          (let [result @(mcp-client/call-tool
                          client
                          "add-task"
                          {:category "medium"
                           :title "Second task"
                           :parent-id 1})]
            (is (not (:isError result)))

            ;; Verify second task was appended
            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                  child-tasks (filter #(= (:parent-id %) 1) tasks)]
              (is (= 3 (count tasks)) "Should have story + 2 child tasks")
              (is (= 2 (count child-tasks)) "Should have two child tasks")
              (let [first-task (first child-tasks)
                    second-task (second child-tasks)]
                (is (= "First task" (:title first-task)))
                (is (= "simple" (:category first-task)))
                (is (= "Second task" (:title second-task)))
                (is (= "medium" (:category second-task))))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ add-task-with-story-name-prepend-test
  ;; Tests that add-task with story-name and prepend flag prepends child tasks.
  (testing "add-task tool with story-name"
    (testing "prepends child tasks to tasks.ednl when prepend is true"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Create a story task with one existing child in tasks.ednl
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                story-task {:id 1
                            :title "prepend-story"
                            :description ""
                            :design ""
                            :category "large"
                            :status :open
                            :type :story
                            :meta {}
                            :relations []}
                existing-child {:id 2
                                :parent-id 1
                                :title "Existing task"
                                :description ""
                                :design ""
                                :category "simple"
                                :status :open
                                :type :task
                                :meta {}
                                :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story-task existing-child]))

          (let [result @(mcp-client/call-tool client
                                              "add-task"
                                              {:category "large"
                                               :title "New first task"
                                               :story-name "prepend-story"
                                               :prepend true})]
            (is (not (:isError result)))

            ;; Verify new task was prepended (appears first in file)
            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))]
              (is (= 3 (count tasks)) "Should have story + 2 child tasks")
              ;; The prepended task should appear before the existing task in the file
              (let [task-titles (mapv :title tasks)]
                (is (= ["New first task" "prepend-story" "Existing task"] task-titles)))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ add-task-preserves-category-tasks-workflow-test
  ;; Tests that adding story tasks and category tasks both work in unified EDN storage.
  (testing "add-task tool"
    (testing "handles both story and category tasks in unified storage"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Create a story task in tasks.ednl
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                story-task {:id 1
                            :title "test-story"
                            :description ""
                            :design ""
                            :category "large"
                            :status :open
                            :type :story
                            :meta {}
                            :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story-task]))

          (let [story-result @(mcp-client/call-tool
                                client
                                "add-task"
                                {:category "simple"
                                 :title "Story task"
                                 :parent-id 1})
                category-result @(mcp-client/call-tool
                                   client
                                   "add-task"
                                   {:category "simple"
                                    :title "Category task"})]
            (is (not (:isError story-result)))
            (is (not (:isError category-result)))

            ;; Verify both tasks are in tasks.ednl
            (let [tasks-file (io/file
                               (fixtures/test-project-dir)
                               ".mcp-tasks"
                               "tasks.ednl")
                  tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                  story-children (filter #(= (:parent-id %) 1) tasks)
                  category-tasks (filter #(and (nil? (:parent-id %))
                                               (= (:type %) :task)) tasks)]
              (is (= 3 (count tasks)) "Should have story + story child + category task")
              (is (= 1 (count story-children)) "Should have one story child")
              (is (= 1 (count category-tasks)) "Should have one category task")
              (let [story-child (first story-children)
                    category-task (first category-tasks)]
                (is (= "Story task" (:title story-child)))
                (is (= "simple" (:category story-child)))
                (is (= 1 (:parent-id story-child)))
                (is (= "Category task" (:title category-task)))
                (is (= "simple" (:category category-task)))
                (is (nil? (:parent-id category-task))))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ add-task-story-validation-test
  ;; Tests that add-task returns error when story task doesn't exist in tasks.ednl.
  (testing "add-task tool with story-name"
    (testing "returns error when story task doesn't exist"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          (let [result @(mcp-client/call-tool client
                                              "add-task"
                                              {:category "simple"
                                               :title "Task for nonexistent"
                                               :parent-id 99999})]
            (is (:isError result))
            (is (re-find #"Parent story not found"
                         (-> result :content first :text))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ add-task-parent-id-validation-with-file-io-test
  ;; Tests parent-id validation with actual file I/O and state management.
  ;; Verifies that attempting to add a task with non-existent parent-id fails
  ;; properly without modifying the tasks file.
  (testing "add-task tool with parent-id validation"
    (testing "verifies file state is unchanged after validation error"
      (fixtures/write-config-file "{:use-git? false}")

      ;; Create a tasks.ednl file with a real task
      (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
            initial-task-content "{:id 1 :title \"Existing task\" :description \"Test\" :design \"\" :category \"simple\" :status :open :type :task :meta {} :relations []}\n"]
        (spit tasks-file initial-task-content)

        (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
          (try
            ;; Verify initial file content
            (is (= initial-task-content (slurp tasks-file)))

            ;; Attempt to add task with non-existent parent-id
            (let [result @(mcp-client/call-tool client
                                                "add-task"
                                                {:category "simple"
                                                 :title "Child task"
                                                 :parent-id 99999})]
              ;; Verify error response
              (is (:isError result))
              (is (re-find #"Parent story not found"
                           (-> result :content first :text)))

              ;; Verify file was not modified
              (is (= initial-task-content (slurp tasks-file))))

            (finally
              (mcp-client/close! client)
              ((:stop server)))))))))

(deftest ^:integ update-task-tool-test
  ;; Tests update-task tool functionality including field updates, validation,
  ;; nil handling, and replacement semantics.
  (testing "update-task tool"
    (testing "field updates"
      (testing "updates multiple fields and persists to tasks.ednl"
        (fixtures/write-config-file "{:use-git? false}")

        (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
          (try
            ;; Create an initial task in tasks.ednl
            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  initial-task {:id 1
                                :title "Original title"
                                :description "Original desc"
                                :design "Original design"
                                :category "simple"
                                :status :open
                                :type :task
                                :meta {}
                                :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [initial-task]))

            ;; Update the task using the tool
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 1
                                                 :title "Updated title"
                                                 :description "Updated desc"
                                                 :design "Updated design"})]
              (is (not (:isError result)))
              (is (re-find #"Task 1 updated"
                           (-> result :content first :text)))

              ;; Verify task was updated in tasks.ednl
              (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                    tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                    updated-task (first tasks)]
                (is (= 1 (count tasks)))
                (is (= "Updated title" (:title updated-task)))
                (is (= "Updated desc" (:description updated-task)))
                (is (= "Updated design" (:design updated-task)))
                ;; Other fields should remain unchanged
                (is (= :open (:status updated-task)))
                (is (= "simple" (:category updated-task)))
                (is (= :task (:type updated-task)))))

            (finally
              (mcp-client/close! client)
              ((:stop server))))))

      (testing "updates only specified fields"
        (fixtures/write-config-file "{:use-git? false}")

        (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
          (try
            ;; Create an initial task
            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  initial-task {:id 2
                                :title "Keep title"
                                :description "Change desc"
                                :design "Keep design"
                                :category "medium"
                                :status :open
                                :type :task
                                :meta {}
                                :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [initial-task]))

            ;; Update only description field
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 2
                                                 :description "New desc"})]
              (is (not (:isError result)))

              ;; Verify only description changed
              (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                    tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                    updated-task (first tasks)]
                (is (= "Keep title" (:title updated-task)))
                (is (= "New desc" (:description updated-task)))
                (is (= "Keep design" (:design updated-task)))))

            (finally
              (mcp-client/close! client)
              ((:stop server))))))

      (testing "returns error for non-existent task ID"
        (fixtures/write-config-file "{:use-git? false}")

        (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
          (try
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 999
                                                 :title "New title"})]
              (is (:isError result))
              (is (re-find #"Task not found"
                           (-> result :content first :text))))

            (finally
              (mcp-client/close! client)
              ((:stop server)))))))

    (testing "validation"
      (testing "validates status field"
        (fixtures/write-config-file "{:use-git? false}")

        (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
          (try
            ;; Create initial task
            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  initial-task {:id 1
                                :title "Test task"
                                :description "Desc"
                                :design ""
                                :category "simple"
                                :status :open
                                :type :task
                                :meta {}
                                :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [initial-task]))

            ;; Test valid status values
            (doseq [valid-status ["open" "closed" "in-progress" "blocked"]]
              (let [result @(mcp-client/call-tool client
                                                  "update-task"
                                                  {:task-id 1
                                                   :status valid-status})]
                (is (not (:isError result))
                    (str "Should accept valid status: " valid-status))))

            ;; Test invalid status value
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 1
                                                 :status "invalid-status"})]
              (is (:isError result))
              (is (re-find #"Invalid task field values"
                           (-> result :content first :text))))

            (finally
              (mcp-client/close! client)
              ((:stop server))))))

      (testing "validates type field"
        (fixtures/write-config-file "{:use-git? false}")

        (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
          (try
            ;; Create initial task
            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  initial-task {:id 1
                                :title "Test task"
                                :description "Desc"
                                :design ""
                                :category "simple"
                                :status :open
                                :type :task
                                :meta {}
                                :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [initial-task]))

            ;; Test valid type values
            (doseq [valid-type ["task" "bug" "feature" "story" "chore"]]
              (let [result @(mcp-client/call-tool client
                                                  "update-task"
                                                  {:task-id 1
                                                   :type valid-type})]
                (is (not (:isError result))
                    (str "Should accept valid type: " valid-type))))

            ;; Test invalid type value
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 1
                                                 :type "invalid-type"})]
              (is (:isError result))
              (is (re-find #"Invalid task field values"
                           (-> result :content first :text))))

            (finally
              (mcp-client/close! client)
              ((:stop server))))))

      (testing "validates parent-id field"
        (fixtures/write-config-file "{:use-git? false}")

        (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
          (try
            ;; Create parent and child tasks
            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  parent-task {:id 1
                               :title "Parent"
                               :description ""
                               :design ""
                               :category "large"
                               :status :open
                               :type :story
                               :meta {}
                               :relations []}
                  child-task {:id 2
                              :title "Child"
                              :description ""
                              :design ""
                              :category "simple"
                              :status :open
                              :type :task
                              :meta {}
                              :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [parent-task child-task]))

            ;; Test valid parent-id
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 2
                                                 :parent-id 1})]
              (is (not (:isError result))))

            ;; Test non-existent parent-id
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 2
                                                 :parent-id 9999})]
              (is (:isError result))
              (is (re-find #"Parent task not found"
                           (-> result :content first :text))))

            (finally
              (mcp-client/close! client)
              ((:stop server))))))

      (testing "validates meta field"
        (fixtures/write-config-file "{:use-git? false}")

        (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
          (try
            ;; Create initial task
            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  initial-task {:id 1
                                :title "Test task"
                                :description "Desc"
                                :design ""
                                :category "simple"
                                :status :open
                                :type :task
                                :meta {}
                                :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [initial-task]))

            ;; Test valid meta with string keys and values
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 1
                                                 :meta {"priority" "high"
                                                        "assigned-to" "alice"}})]
              (is (not (:isError result))))

            ;; Test meta with non-string value gets coerced to string
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 1
                                                 :meta {"priority" 123}})]
              (is (not (:isError result)))
              ;; Verify the number was coerced to a string
              (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                    tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                    updated-task (first tasks)]
                (is (= {"priority" "123"} (:meta updated-task)))))

            (finally
              (mcp-client/close! client)
              ((:stop server))))))

      (testing "validates relations field"
        (fixtures/write-config-file "{:use-git? false}")

        (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
          (try
            ;; Create initial tasks
            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  task-1 {:id 1
                          :title "Task 1"
                          :description ""
                          :design ""
                          :category "simple"
                          :status :open
                          :type :task
                          :meta {}
                          :relations []}
                  task-2 {:id 2
                          :title "Task 2"
                          :description ""
                          :design ""
                          :category "simple"
                          :status :open
                          :type :task
                          :meta {}
                          :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [task-1 task-2]))

            ;; Test valid relations with proper structure
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 1
                                                 :relations [{"id" 1
                                                              "relates-to" 2
                                                              "as-type" "blocked-by"}]})]
              (is (not (:isError result))))

            ;; Test multiple valid relation types
            (doseq [relation-type ["blocked-by" "related" "discovered-during"]]
              (let [result @(mcp-client/call-tool client
                                                  "update-task"
                                                  {:task-id 1
                                                   :relations [{"id" 1
                                                                "relates-to" 2
                                                                "as-type" relation-type}]})]
                (is (not (:isError result))
                    (str "Should accept valid relation type: " relation-type))))

            ;; Test invalid relation - wrong as-type enum
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 1
                                                 :relations [{"id" 1
                                                              "relates-to" 2
                                                              "as-type" "invalid-type"}]})]
              (is (:isError result))
              (is (re-find #"Invalid task field values"
                           (-> result :content first :text))))

            ;; Test invalid relation - missing required field
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 1
                                                 :relations [{"id" 1
                                                              "relates-to" 2}]})]
              (is (:isError result))
              (is (re-find #"Invalid task field values"
                           (-> result :content first :text))))

            (finally
              (mcp-client/close! client)
              ((:stop server)))))))

    (testing "nil handling"
      (testing "clears parent-id with nil"
        (fixtures/write-config-file "{:use-git? false}")

        (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
          (try
            ;; Create parent and child tasks
            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  parent-task {:id 1
                               :title "Parent"
                               :description ""
                               :design ""
                               :category "large"
                               :status :open
                               :type :story
                               :meta {}
                               :relations []}
                  child-task {:id 2
                              :title "Child"
                              :description ""
                              :design ""
                              :category "simple"
                              :status :open
                              :type :task
                              :parent-id 1
                              :meta {}
                              :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [parent-task child-task]))

            ;; Clear parent-id with nil
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 2
                                                 :parent-id nil})]
              (is (not (:isError result)))

              ;; Verify parent-id was cleared
              (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                    tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                    updated-task (first (filter #(= (:id %) 2) tasks))]
                (is (nil? (:parent-id updated-task)))))

            (finally
              (mcp-client/close! client)
              ((:stop server))))))

      (testing "clears meta with nil"
        (fixtures/write-config-file "{:use-git? false}")

        (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
          (try
            ;; Create task with meta
            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  initial-task {:id 1
                                :title "Test task"
                                :description "Desc"
                                :design ""
                                :category "simple"
                                :status :open
                                :type :task
                                :meta {"priority" "high"}
                                :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [initial-task]))

            ;; Clear meta with nil
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 1
                                                 :meta nil})]
              (is (not (:isError result)))

              ;; Verify meta was cleared to empty map
              (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                    tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                    updated-task (first tasks)]
                (is (= {} (:meta updated-task)))))

            (finally
              (mcp-client/close! client)
              ((:stop server))))))

      (testing "clears relations with nil"
        (fixtures/write-config-file "{:use-git? false}")

        (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
          (try
            ;; Create task with relations
            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  task-1 {:id 1
                          :title "Task 1"
                          :description ""
                          :design ""
                          :category "simple"
                          :status :open
                          :type :task
                          :meta {}
                          :relations [{:id 1 :relates-to 2 :as-type :blocked-by}]}
                  task-2 {:id 2
                          :title "Task 2"
                          :description ""
                          :design ""
                          :category "simple"
                          :status :open
                          :type :task
                          :meta {}
                          :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [task-1 task-2]))

            ;; Clear relations with nil
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 1
                                                 :relations nil})]
              (is (not (:isError result)))

              ;; Verify relations were cleared to empty vector
              (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                    tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                    updated-task (first (filter #(= (:id %) 1) tasks))]
                (is (= [] (:relations updated-task)))))

            (finally
              (mcp-client/close! client)
              ((:stop server)))))))

    (testing "replacement semantics"
      (testing "replaces entire meta map"
        (fixtures/write-config-file "{:use-git? false}")

        (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
          (try
            ;; Create task with initial meta
            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  initial-task {:id 1
                                :title "Test task"
                                :description "Desc"
                                :design ""
                                :category "simple"
                                :status :open
                                :type :task
                                :meta {"old-key" "old-value"
                                       "keep-key" "keep-value"}
                                :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [initial-task]))

            ;; Replace meta entirely (not merge)
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 1
                                                 :meta {"new-key" "new-value"}})]
              (is (not (:isError result)))

              ;; Verify old keys are gone and only new keys exist
              (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                    tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                    updated-task (first tasks)]
                (is (= {"new-key" "new-value"} (:meta updated-task)))
                (is (not (contains? (:meta updated-task) "old-key")))
                (is (not (contains? (:meta updated-task) "keep-key")))))

            (finally
              (mcp-client/close! client)
              ((:stop server))))))

      (testing "replaces entire relations vector"
        (fixtures/write-config-file "{:use-git? false}")

        (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
          (try
            ;; Create tasks with initial relations
            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  task-1 {:id 1
                          :title "Task 1"
                          :description ""
                          :design ""
                          :category "simple"
                          :status :open
                          :type :task
                          :meta {}
                          :relations [{:id 1 :relates-to 2 :as-type :blocked-by}
                                      {:id 2 :relates-to 3 :as-type :related}]}
                  task-2 {:id 2
                          :title "Task 2"
                          :description ""
                          :design ""
                          :category "simple"
                          :status :open
                          :type :task
                          :meta {}
                          :relations []}
                  task-3 {:id 3
                          :title "Task 3"
                          :description ""
                          :design ""
                          :category "simple"
                          :status :open
                          :type :task
                          :meta {}
                          :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [task-1 task-2 task-3]))

            ;; Replace relations entirely (not append)
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 1
                                                 :relations [{"id" 3
                                                              "relates-to" 3
                                                              "as-type" "discovered-during"}]})]
              (is (not (:isError result)))

              ;; Verify old relations are gone and only new relation exists
              (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                    tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                    updated-task (first (filter #(= (:id %) 1) tasks))]
                (is (= 1 (count (:relations updated-task))))
                (let [relation (first (:relations updated-task))]
                  (is (= 3 (:id relation)))
                  (is (= 3 (:relates-to relation)))
                  (is (= :discovered-during (:as-type relation))))))

            (finally
              (mcp-client/close! client)
              ((:stop server)))))))))
