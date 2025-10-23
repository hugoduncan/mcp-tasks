(ns mcp-tasks.select-tasks-integration-test
  "Integration tests for select-tasks tool through MCP server.
  
  Tests end-to-end behavior of select-tasks features including metadata
  enhancements like completed-task-count for story progress tracking."
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-clj.mcp-client.core :as mcp-client]
    [mcp-tasks.integration-test-fixtures :as fixtures]
    [mcp-tasks.tasks-file :as tasks-file]))

(use-fixtures :each fixtures/with-test-project)

(deftest ^:integ select-tasks-completed-count-integration-test
  ;; Test end-to-end behavior of :completed-task-count metadata feature.
  ;; Validates that select-tasks returns accurate completed child task counts
  ;; for story progress tracking across tasks.ednl and complete.ednl files.
  (testing "select-tasks completed-task-count metadata"
    (testing "story with some completed child tasks"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Create story with child tasks
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                complete-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "complete.ednl")
                story {:id 100
                       :title "Test Story"
                       :description "Story for testing completed count"
                       :design ""
                       :category "large"
                       :status :open
                       :type :story
                       :meta {}
                       :relations []}
                child1 {:id 101
                        :parent-id 100
                        :title "Child Task 1"
                        :description "First child"
                        :design ""
                        :category "simple"
                        :status :open
                        :type :task
                        :meta {}
                        :relations []}
                child2 {:id 102
                        :parent-id 100
                        :title "Child Task 2"
                        :description "Second child"
                        :design ""
                        :category "simple"
                        :status :closed
                        :type :task
                        :meta {}
                        :relations []}
                child3 {:id 103
                        :parent-id 100
                        :title "Child Task 3"
                        :description "Third child"
                        :design ""
                        :category "simple"
                        :status :closed
                        :type :task
                        :meta {}
                        :relations []}]

            ;; Write story and open child to tasks.ednl
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story child1])

            ;; Write completed children to complete.ednl
            (tasks-file/write-tasks (.getAbsolutePath complete-file) [child2 child3]))

          ;; Call select-tasks with parent-id filter
          (let [result @(mcp-client/call-tool
                          client
                          "select-tasks"
                          {:parent-id 100
                           :limit 10})]
            (is (not (:isError result)) "select-tasks should succeed")

            (let [response (get-in result [:content 0 :text])
                  data (clojure.data.json/read-str response :key-fn keyword)
                  tasks (:tasks data)
                  metadata (:metadata data)]

              ;; Verify open child task returned
              (is (= 1 (count tasks)) "Should return 1 open child task")
              (is (= 101 (:id (first tasks))) "Should return child1")

              ;; Verify metadata includes completed-task-count
              (is (contains? metadata :completed-task-count)
                  "Metadata should include :completed-task-count")
              (is (= 2 (:completed-task-count metadata))
                  "Should count 2 completed child tasks")

              ;; Verify other metadata fields
              (is (= 1 (:open-task-count metadata)) "Open task count should be 1")
              (is (= 1 (:total-matches metadata)) "Total matches should be 1")))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "story with no completed tasks"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Create story with only open child tasks
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                story {:id 200
                       :title "Story No Completed"
                       :description "Story with no completed tasks"
                       :design ""
                       :category "large"
                       :status :open
                       :type :story
                       :meta {}
                       :relations []}
                child1 {:id 201
                        :parent-id 200
                        :title "Open Child 1"
                        :description "First open child"
                        :design ""
                        :category "simple"
                        :status :open
                        :type :task
                        :meta {}
                        :relations []}
                child2 {:id 202
                        :parent-id 200
                        :title "Open Child 2"
                        :description "Second open child"
                        :design ""
                        :category "simple"
                        :status :open
                        :type :task
                        :meta {}
                        :relations []}]

            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story child1 child2]))

          ;; Call select-tasks with parent-id filter
          (let [result @(mcp-client/call-tool
                          client
                          "select-tasks"
                          {:parent-id 200})]
            (is (not (:isError result)))

            (let [response (get-in result [:content 0 :text])
                  data (clojure.data.json/read-str response :key-fn keyword)
                  metadata (:metadata data)]

              ;; Verify completed-task-count is 0
              (is (= 0 (:completed-task-count metadata))
                  "Should have 0 completed tasks")))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "story with all tasks completed"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Create story with all completed child tasks
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                complete-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "complete.ednl")
                story {:id 300
                       :title "Story All Completed"
                       :description "Story with all tasks completed"
                       :design ""
                       :category "large"
                       :status :open
                       :type :story
                       :meta {}
                       :relations []}
                child1 {:id 301
                        :parent-id 300
                        :title "Completed Child 1"
                        :description "First completed"
                        :design ""
                        :category "simple"
                        :status :closed
                        :type :task
                        :meta {}
                        :relations []}
                child2 {:id 302
                        :parent-id 300
                        :title "Completed Child 2"
                        :description "Second completed"
                        :design ""
                        :category "simple"
                        :status :closed
                        :type :task
                        :meta {}
                        :relations []}]

            ;; Write story to tasks.ednl
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story])

            ;; Write all children to complete.ednl
            (tasks-file/write-tasks (.getAbsolutePath complete-file) [child1 child2]))

          ;; Call select-tasks with parent-id filter
          (let [result @(mcp-client/call-tool
                          client
                          "select-tasks"
                          {:parent-id 300})]
            (is (not (:isError result)))

            (let [response (get-in result [:content 0 :text])
                  data (clojure.data.json/read-str response :key-fn keyword)
                  tasks (:tasks data)
                  metadata (:metadata data)]

              ;; Verify no open tasks returned
              (is (= 0 (count tasks)) "Should return 0 open tasks")

              ;; Verify completed-task-count equals total children
              (is (= 2 (:completed-task-count metadata))
                  "Should count 2 completed tasks")))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "story task itself not included in completed count"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Create closed story (as if being archived) with closed child
          (let [complete-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "complete.ednl")
                story {:id 400
                       :title "Closed Story"
                       :description "Story that is closed"
                       :design ""
                       :category "large"
                       :status :closed
                       :type :story
                       :meta {}
                       :relations []}
                child {:id 401
                       :parent-id 400
                       :title "Closed Child"
                       :description "Child of closed story"
                       :design ""
                       :category "simple"
                       :status :closed
                       :type :task
                       :meta {}
                       :relations []}]

            ;; Write both to complete.ednl
            (tasks-file/write-tasks (.getAbsolutePath complete-file) [story child]))

          ;; Call select-tasks with parent-id filter
          (let [result @(mcp-client/call-tool
                          client
                          "select-tasks"
                          {:parent-id 400})]
            (is (not (:isError result)))

            (let [response (get-in result [:content 0 :text])
                  data (clojure.data.json/read-str response :key-fn keyword)
                  metadata (:metadata data)]

              ;; Verify only child is counted, not story itself
              (is (= 1 (:completed-task-count metadata))
                  "Should count only the child, not the story itself")))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "query without parent-id does not include completed-task-count"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Create some regular tasks
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                task1 {:id 500
                       :title "Regular Task 1"
                       :description "No parent"
                       :design ""
                       :category "simple"
                       :status :open
                       :type :task
                       :meta {}
                       :relations []}
                task2 {:id 501
                       :title "Regular Task 2"
                       :description "No parent"
                       :design ""
                       :category "simple"
                       :status :open
                       :type :task
                       :meta {}
                       :relations []}]

            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [task1 task2]))

          ;; Call select-tasks WITHOUT parent-id filter
          (let [result @(mcp-client/call-tool
                          client
                          "select-tasks"
                          {:category "simple"})]
            (is (not (:isError result)))

            (let [response (get-in result [:content 0 :text])
                  data (clojure.data.json/read-str response :key-fn keyword)
                  metadata (:metadata data)]

              ;; Verify completed-task-count is NOT in metadata
              (is (not (contains? metadata :completed-task-count))
                  "Metadata should not include :completed-task-count without parent-id")))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))
