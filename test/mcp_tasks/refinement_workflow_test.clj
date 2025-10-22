(ns mcp-tasks.refinement-workflow-test
  "Integration tests for task refinement workflow.
  
  Tests refinement status tracking via meta field, including setting, detecting,
  and preserving refinement status through task lifecycle."
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-clj.mcp-client.core :as mcp-client]
    [mcp-tasks.integration-test-fixtures :as fixtures]
    [mcp-tasks.tasks-file :as tasks-file]))

(use-fixtures :each fixtures/with-test-project)

(deftest ^:integ refinement-status-meta-setting-test
  ;; Test that update-task correctly sets and merges refinement status in meta field.
  ;; This supports the refine-task prompt workflow which marks tasks as refined.
  (testing "refinement status meta setting"
    (testing "sets refined meta on task without existing meta"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                initial-task {:id 1
                              :title "Unrefined task"
                              :description "Task description"
                              :design ""
                              :category "simple"
                              :status :open
                              :type :task
                              :meta {}
                              :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [initial-task]))

          (let [result @(mcp-client/call-tool client
                                              "update-task"
                                              {:task-id 1
                                               :meta {"refined" "true"}})]
            (is (not (:isError result)))

            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                  updated-task (first tasks)]
              (is (= {"refined" "true"} (:meta updated-task)))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "merges refined meta with existing meta values"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                initial-task {:id 1
                              :title "Task with meta"
                              :description "Task description"
                              :design ""
                              :category "medium"
                              :status :open
                              :type :task
                              :meta {"priority" "high"
                                     "assigned-to" "alice"}
                              :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [initial-task]))

          (let [result @(mcp-client/call-tool
                          client
                          "update-task"
                          {:task-id 1
                           :meta {"priority" "high"
                                  "assigned-to" "alice"
                                  "refined" "true"}})]
            (is (not (:isError result)))

            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                  updated-task (first tasks)]
              (is (= {"priority" "high"
                      "assigned-to" "alice"
                      "refined" "true"}
                     (:meta updated-task)))
              (is (contains? (:meta updated-task) "refined"))
              (is (= "true" (get (:meta updated-task) "refined")))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "updates existing refined flag"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                initial-task {:id 1
                              :title "Already refined"
                              :description "Task description"
                              :design ""
                              :category "large"
                              :status :open
                              :type :task
                              :meta {"refined" "false"}
                              :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [initial-task]))

          (let [result @(mcp-client/call-tool client
                                              "update-task"
                                              {:task-id 1
                                               :meta {"refined" "true"}})]
            (is (not (:isError result)))

            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                  updated-task (first tasks)]
              (is (= {"refined" "true"} (:meta updated-task)))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "sets refined meta on story task"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                story-task {:id 1
                            :title "Unrefined story"
                            :description "Story description"
                            :design ""
                            :category "large"
                            :status :open
                            :type :story
                            :meta {}
                            :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story-task]))

          (let [result @(mcp-client/call-tool client
                                              "update-task"
                                              {:task-id 1
                                               :meta {"refined" "true"}})]
            (is (not (:isError result)))

            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                  updated-task (first tasks)]
              (is (= :story (:type updated-task)))
              (is (= {"refined" "true"} (:meta updated-task)))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ refinement-status-detection-test
  ;; Test that refinement status can be correctly detected from task meta field.
  ;; This supports category prompts and create-story-tasks prompt which check refinement.
  (testing "refinement status detection"
    (testing "detects refined task via file read"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                refined-task {:id 1
                              :title "Refined task"
                              :description "Task description"
                              :design ""
                              :category "simple"
                              :status :open
                              :type :task
                              :meta {"refined" "true"}
                              :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [refined-task]))

          ;; Verify via file read
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                task (first tasks)]
            (is (= "true" (get-in task [:meta "refined"])))
            (is (contains? (:meta task) "refined")))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "detects unrefined task via file read"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                unrefined-task {:id 1
                                :title "Unrefined task"
                                :description "Task description"
                                :design ""
                                :category "medium"
                                :status :open
                                :type :task
                                :meta {}
                                :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [unrefined-task]))

          ;; Verify via file read
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                task (first tasks)]
            (is (= {} (:meta task)))
            (is (not (contains? (:meta task) "refined"))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "detects refined story via file read"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                refined-story {:id 1
                               :title "Refined story"
                               :description "Story description"
                               :design ""
                               :category "large"
                               :status :open
                               :type :story
                               :meta {"refined" "true"}
                               :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [refined-story]))

          ;; Verify via file read
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                story (first tasks)]
            (is (= :story (:type story)))
            (is (= "true" (get-in story [:meta "refined"]))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "distinguishes refined from skip-refinement-check flag"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                refined-task {:id 1
                              :title "Refined task"
                              :description ""
                              :design ""
                              :category "simple"
                              :status :open
                              :type :task
                              :meta {"refined" "true"}
                              :relations []}
                bypass-task {:id 2
                             :title "Bypass task"
                             :description ""
                             :design ""
                             :category "simple"
                             :status :open
                             :type :task
                             :meta {"skip-refinement-check" "true"}
                             :relations []}
                both-task {:id 3
                           :title "Both flags"
                           :description ""
                           :design ""
                           :category "simple"
                           :status :open
                           :type :task
                           :meta {"refined" "true"
                                  "skip-refinement-check" "true"}
                           :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file)
                                    [refined-task bypass-task both-task]))

          ;; Verify via file read
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                refined-task (first tasks)
                bypass-task (second tasks)
                both-task (nth tasks 2)]
            (is (= "true" (get-in refined-task [:meta "refined"])))
            (is (not (contains? (:meta refined-task) "skip-refinement-check")))

            (is (= "true" (get-in bypass-task [:meta "skip-refinement-check"])))
            (is (not (contains? (:meta bypass-task) "refined")))

            (is (= "true" (get-in both-task [:meta "refined"])))
            (is (= "true" (get-in both-task [:meta "skip-refinement-check"]))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ refinement-workflow-integration-test
  ;; Test complete refinement workflow from task creation through refinement to execution.
  ;; Verifies the end-to-end integration of refinement status tracking.
  (testing "refinement workflow integration"
    (testing "complete workflow: create → refine → verify"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Ensure tasks file is empty before starting
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) []))

          ;; Step 1: Create an unrefined task
          (let [add-result @(mcp-client/call-tool client
                                                  "add-task"
                                                  {:category "medium"
                                                   :title "New feature task"})]
            (is (not (:isError add-result))))

          ;; Step 2: Verify task is unrefined initially
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                task (first tasks)]
            (is (not (contains? (:meta task) "refined")))
            (is (= {} (:meta task))))

          ;; Step 3: Simulate refine-task by updating with refined meta
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                task-id (:id (first tasks))
                update-result @(mcp-client/call-tool client
                                                     "update-task"
                                                     {:task-id task-id
                                                      :meta {"refined" "true"}})]
            (is (not (:isError update-result))))

          ;; Step 4: Verify task is now refined
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                task (first tasks)]
            (is (contains? (:meta task) "refined"))
            (is (= "true" (get-in task [:meta "refined"]))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "story workflow: create story → refine → create tasks"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Step 1: Create story task
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                story-task {:id 1
                            :title "User authentication"
                            :description "Implement user auth"
                            :design ""
                            :category "large"
                            :status :open
                            :type :story
                            :meta {}
                            :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story-task]))

          ;; Step 2: Verify story is unrefined
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                story (first tasks)]
            (is (= :story (:type story)))
            (is (not (contains? (:meta story) "refined"))))

          ;; Step 3: Refine the story
          (let [update-result @(mcp-client/call-tool client
                                                     "update-task"
                                                     {:task-id 1
                                                      :meta {"refined" "true"}})]
            (is (not (:isError update-result))))

          ;; Step 4: Verify story is refined
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                story (first tasks)]
            (is (= "true" (get-in story [:meta "refined"]))))

          ;; Step 5: Add child tasks to refined story
          (let [add-result @(mcp-client/call-tool client
                                                  "add-task"
                                                  {:category "simple"
                                                   :title "Login endpoint"
                                                   :parent-id 1})]
            (is (not (:isError add-result))))

          ;; Step 6: Verify child task was created
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                child-tasks (filter #(= 1 (:parent-id %)) tasks)]
            (is (= 1 (count child-tasks)))
            (let [child-task (first child-tasks)]
              (is (= "Login endpoint" (:title child-task)))
              (is (= 1 (:parent-id child-task)))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "preserves meta when updating other fields"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Create a refined task with additional meta
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                initial-task {:id 1
                              :title "Original title"
                              :description "Original desc"
                              :design ""
                              :category "medium"
                              :status :open
                              :type :task
                              :meta {"refined" "true"
                                     "priority" "high"}
                              :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [initial-task]))

          ;; Update only title and description
          (let [update-result @(mcp-client/call-tool client
                                                     "update-task"
                                                     {:task-id 1
                                                      :title "Updated title"
                                                      :description "Updated desc"})]
            (is (not (:isError update-result))))

          ;; Verify meta is preserved (since we didn't update it)
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                updated-task (first tasks)]
            (is (= "Updated title" (:title updated-task)))
            (is (= "Updated desc" (:description updated-task)))
            ;; Meta should remain unchanged
            (is (= {"refined" "true" "priority" "high"} (:meta updated-task))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))
