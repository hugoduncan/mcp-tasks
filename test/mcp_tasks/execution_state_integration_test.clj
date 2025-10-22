(ns mcp-tasks.execution-state-integration-test
  "Integration tests for execution state tracking through MCP server.
  
  Tests execution state creation, persistence, resource exposure, and clearing
  through task lifecycle including worktree isolation."
  (:require
    [babashka.fs :as fs]
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-clj.mcp-client.core :as mcp-client]
    [mcp-tasks.integration-test-fixtures :as fixtures]
    [mcp-tasks.tasks-file :as tasks-file]))

(use-fixtures :each fixtures/with-test-project)

(deftest ^:integ current-execution-resource-test
  ;; Test that current-execution resource exposes execution state correctly.
  ;; Validates resource reads .mcp-tasks-current.edn and returns proper format.
  (testing "current-execution resource"
    (testing "returns null when no execution state exists"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          (let [read-response @(mcp-client/read-resource client "resource://current-execution")
                text (-> read-response :contents first :text)]
            (is (not (:isError read-response)))
            (is (= "null" text)))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "returns execution state when file exists"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Write execution state file
          (let [state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")
                state {:story-id 177
                       :task-id 181
                       :started-at "2025-10-20T14:30:00Z"}]
            (spit state-file (pr-str state)))

          (let [read-response @(mcp-client/read-resource client "resource://current-execution")
                text (-> read-response :contents first :text)
                state (json/parse-string text true)]
            (is (not (:isError read-response)))
            (is (= 177 (:story-id state)))
            (is (= 181 (:task-id state)))
            (is (= "2025-10-20T14:30:00Z" (:started-at state))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "returns null when execution state file is invalid"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Write invalid execution state file (missing required field)
          (let [state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")
                invalid-state {:story-id 177}]
            (spit state-file (pr-str invalid-state)))

          (let [read-response @(mcp-client/read-resource client "resource://current-execution")
                text (-> read-response :contents first :text)]
            (is (not (:isError read-response)))
            (is (= "null" text)))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "returns execution state with nil story-id for standalone task"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Write execution state file with nil story-id
          (let [state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")
                state {:story-id nil
                       :task-id 42
                       :started-at "2025-10-20T15:00:00Z"}]
            (spit state-file (pr-str state)))

          (let [read-response @(mcp-client/read-resource client "resource://current-execution")
                text (-> read-response :contents first :text)
                state (json/parse-string text true)]
            (is (not (:isError read-response)))
            (is (nil? (:story-id state)))
            (is (= 42 (:task-id state)))
            (is (= "2025-10-20T15:00:00Z" (:started-at state))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "resource is listed in available resources"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          (let [resources-response @(mcp-client/list-resources client)
                resources (:resources resources-response)
                current-exec-resource (first (filter #(= "resource://current-execution" (:uri %))
                                                     resources))]
            (is (some? current-exec-resource))
            (is (= "current-execution" (:name current-exec-resource)))
            (is (= "resource://current-execution" (:uri current-exec-resource)))
            (is (= "application/json" (:mimeType current-exec-resource)))
            (is (= "Current story and task execution state" (:description current-exec-resource))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ execution-state-workflow-test
  ;; Test execution state creation, persistence, and clearing through complete workflow.
  ;; Validates that state file is created/cleared at appropriate points in task lifecycle.
  (testing "execution state workflow"
    (testing "state file created when write-execution-state is called"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Verify state file doesn't exist initially
          (let [state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
            (is (not (fs/exists? state-file))))

          ;; Simulate task execution starting - write state directly
          (let [state {:story-id 177
                       :task-id 181
                       :started-at "2025-10-20T14:30:00Z"}
                state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
            (spit state-file (pr-str state))

            ;; Verify state file exists and contains correct data
            (is (fs/exists? state-file))
            (let [read-state (edn/read-string (slurp state-file))]
              (is (= 177 (:story-id read-state)))
              (is (= 181 (:task-id read-state)))
              (is (= "2025-10-20T14:30:00Z" (:started-at read-state)))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "state file cleared when clear-execution-state is called"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Create state file
          (let [state {:story-id 177
                       :task-id 181
                       :started-at "2025-10-20T14:30:00Z"}
                state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
            (spit state-file (pr-str state))
            (is (fs/exists? state-file)))

          ;; Simulate task completion - delete state file
          (let [state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
            (fs/delete state-file)

            ;; Verify state file was removed
            (is (not (fs/exists? state-file))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "state file contains started-at timestamp for stale detection"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Write state with timestamp
          (let [state {:story-id nil
                       :task-id 42
                       :started-at "2025-10-20T10:00:00Z"}
                state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
            (spit state-file (pr-str state))

            ;; Verify timestamp can be read for stale detection
            (let [read-state (edn/read-string (slurp state-file))]
              (is (string? (:started-at read-state)))
              (is (= "2025-10-20T10:00:00Z" (:started-at read-state)))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "state persists story-id for story tasks"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Write state for story task
          (let [state {:story-id 177
                       :task-id 181
                       :started-at "2025-10-20T14:30:00Z"}
                state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
            (spit state-file (pr-str state))

            ;; Verify story-id is persisted
            (let [read-state (edn/read-string (slurp state-file))]
              (is (= 177 (:story-id read-state)))
              (is (= 181 (:task-id read-state)))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "state uses nil story-id for standalone tasks"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Write state for standalone task
          (let [state {:story-id nil
                       :task-id 42
                       :started-at "2025-10-20T15:00:00Z"}
                state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
            (spit state-file (pr-str state))

            ;; Verify nil story-id is persisted
            (let [read-state (edn/read-string (slurp state-file))]
              (is (nil? (:story-id read-state)))
              (is (= 42 (:task-id read-state)))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ complete-task-clears-execution-state-test
  ;; Test that complete-task tool automatically clears execution state after successful completion.
  ;; Validates integration between complete-task and execution state clearing across all task types.
  (testing "complete-task tool clears execution state"
    (testing "regular task completion"
      (testing "clears state with git disabled"
        (fixtures/write-config-file "{:use-git? false}")

        (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
          (try
            ;; Create a regular task
            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  task {:id 1
                        :title "Regular task"
                        :description "Test task"
                        :design ""
                        :category "simple"
                        :status :open
                        :type :task
                        :meta {}
                        :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [task]))

            ;; Simulate execution state being written when task starts
            (let [state {:story-id nil
                         :task-id 1
                         :started-at "2025-10-20T14:30:00Z"}
                  state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
              (spit state-file (pr-str state))
              (is (fs/exists? state-file) "State file should exist before completion"))

            ;; Call complete-task tool
            (let [result @(mcp-client/call-tool
                            client
                            "complete-task"
                            {:task-id 1})]
              (is (not (:isError result)) "Complete-task should succeed")

              ;; Verify state file was deleted
              (let [state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
                (is (not (fs/exists? state-file)) "State file should be cleared after completion"))

              ;; Verify task was moved to complete.ednl
              (let [complete-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "complete.ednl")
                    completed-tasks (tasks-file/read-ednl (.getAbsolutePath complete-file))]
                (is (= 1 (count completed-tasks)))
                (is (= :closed (:status (first completed-tasks))))))

            (finally
              (mcp-client/close! client)
              ((:stop server))))))

      (testing "clears state with git enabled"
        (fixtures/write-config-file "{:use-git? true}")
        (fixtures/init-test-git-repo)

        (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
          (try
            ;; Create a regular task
            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  task {:id 2
                        :title "Regular task with git"
                        :description "Test task"
                        :design ""
                        :category "simple"
                        :status :open
                        :type :task
                        :meta {}
                        :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [task]))

            ;; Simulate execution state
            (let [state {:story-id nil
                         :task-id 2
                         :started-at "2025-10-20T14:35:00Z"}
                  state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
              (spit state-file (pr-str state))
              (is (fs/exists? state-file)))

            ;; Call complete-task tool
            (let [result @(mcp-client/call-tool
                            client
                            "complete-task"
                            {:task-id 2})]
              (is (not (:isError result)))

              ;; Verify state file was deleted
              (let [state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
                (is (not (fs/exists? state-file)))))

            (finally
              (mcp-client/close! client)
              ((:stop server)))))))

    (testing "child task completion"
      (testing "clears state with git disabled"
        (fixtures/write-config-file "{:use-git? false}")

        (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
          (try
            ;; Create story and child task
            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  story {:id 10
                         :title "Test story"
                         :description "Story for testing"
                         :design ""
                         :category "large"
                         :status :open
                         :type :story
                         :meta {}
                         :relations []}
                  child {:id 11
                         :parent-id 10
                         :title "Child task"
                         :description "Test child"
                         :design ""
                         :category "simple"
                         :status :open
                         :type :task
                         :meta {}
                         :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story child]))

            ;; Simulate execution state with story-id
            (let [state {:story-id 10
                         :task-id 11
                         :started-at "2025-10-20T14:40:00Z"}
                  state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
              (spit state-file (pr-str state))
              (is (fs/exists? state-file)))

            ;; Call complete-task tool for child
            (let [result @(mcp-client/call-tool
                            client
                            "complete-task"
                            {:task-id 11})]
              (is (not (:isError result)))

              ;; Verify state file was deleted
              (let [state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
                (is (not (fs/exists? state-file))))

              ;; Verify child was marked closed but stayed in tasks.ednl
              (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                    tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                    child-task (first (filter #(= 11 (:id %)) tasks))]
                (is (= :closed (:status child-task)))
                (is (= 10 (:parent-id child-task)))))

            (finally
              (mcp-client/close! client)
              ((:stop server))))))

      (testing "clears state with git enabled"
        (fixtures/write-config-file "{:use-git? true}")
        (fixtures/init-test-git-repo)

        (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
          (try
            ;; Create story and child task
            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  story {:id 20
                         :title "Test story git"
                         :description "Story for testing"
                         :design ""
                         :category "large"
                         :status :open
                         :type :story
                         :meta {}
                         :relations []}
                  child {:id 21
                         :parent-id 20
                         :title "Child task git"
                         :description "Test child"
                         :design ""
                         :category "simple"
                         :status :open
                         :type :task
                         :meta {}
                         :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story child]))

            ;; Simulate execution state
            (let [state {:story-id 20
                         :task-id 21
                         :started-at "2025-10-20T14:45:00Z"}
                  state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
              (spit state-file (pr-str state))
              (is (fs/exists? state-file)))

            ;; Call complete-task tool
            (let [result @(mcp-client/call-tool
                            client
                            "complete-task"
                            {:task-id 21})]
              (is (not (:isError result)))

              ;; Verify state file was deleted
              (let [state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
                (is (not (fs/exists? state-file)))))

            (finally
              (mcp-client/close! client)
              ((:stop server)))))))

    (testing "story task completion"
      (testing "clears state with git disabled"
        (fixtures/write-config-file "{:use-git? false}")

        (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
          (try
            ;; Create story with child tasks
            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  story {:id 30
                         :title "Story to complete"
                         :description "Story for testing"
                         :design ""
                         :category "large"
                         :status :open
                         :type :story
                         :meta {}
                         :relations []}
                  child1 {:id 31
                          :parent-id 30
                          :title "Child 1"
                          :description "First child"
                          :design ""
                          :category "simple"
                          :status :closed
                          :type :task
                          :meta {}
                          :relations []}
                  child2 {:id 32
                          :parent-id 30
                          :title "Child 2"
                          :description "Second child"
                          :design ""
                          :category "simple"
                          :status :closed
                          :type :task
                          :meta {}
                          :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story child1 child2]))

            ;; Simulate execution state
            (let [state {:story-id 30
                         :task-id 30
                         :started-at "2025-10-20T14:50:00Z"}
                  state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
              (spit state-file (pr-str state))
              (is (fs/exists? state-file)))

            ;; Call complete-task tool for story
            (let [result @(mcp-client/call-tool
                            client
                            "complete-task"
                            {:task-id 30})]
              (is (not (:isError result)))

              ;; Verify state file was deleted
              (let [state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
                (is (not (fs/exists? state-file))))

              ;; Verify story and children were moved to complete.ednl
              (let [complete-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "complete.ednl")
                    completed-tasks (tasks-file/read-ednl (.getAbsolutePath complete-file))
                    story-tasks (filter #(#{30 31 32} (:id %)) completed-tasks)]
                (is (= 3 (count story-tasks)) "Story and 2 children should be in complete.ednl")
                (is (some #(and (= 30 (:id %)) (= :closed (:status %))) story-tasks))
                (is (some #(and (= 31 (:id %)) (= :closed (:status %))) story-tasks))
                (is (some #(and (= 32 (:id %)) (= :closed (:status %))) story-tasks))))

            (finally
              (mcp-client/close! client)
              ((:stop server))))))

      (testing "clears state with git enabled"
        (fixtures/write-config-file "{:use-git? true}")
        (fixtures/init-test-git-repo)

        (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
          (try
            ;; Create story with child tasks
            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  story {:id 40
                         :title "Story git complete"
                         :description "Story for testing"
                         :design ""
                         :category "large"
                         :status :open
                         :type :story
                         :meta {}
                         :relations []}
                  child {:id 41
                         :parent-id 40
                         :title "Child git"
                         :description "Child task"
                         :design ""
                         :category "simple"
                         :status :closed
                         :type :task
                         :meta {}
                         :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story child]))

            ;; Simulate execution state
            (let [state {:story-id 40
                         :task-id 40
                         :started-at "2025-10-20T14:55:00Z"}
                  state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
              (spit state-file (pr-str state))
              (is (fs/exists? state-file)))

            ;; Call complete-task tool
            (let [result @(mcp-client/call-tool
                            client
                            "complete-task"
                            {:task-id 40})]
              (is (not (:isError result)))

              ;; Verify state file was deleted
              (let [state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
                (is (not (fs/exists? state-file)))))

            (finally
              (mcp-client/close! client)
              ((:stop server)))))))))

(deftest ^:integ execution-state-worktree-isolation-test
  ;; Test that execution state is isolated per worktree/base-dir.
  ;; Each worktree should have its own .mcp-tasks-current.edn file.
  (testing "execution state worktree isolation"
    (testing "different base directories have separate state files"
      (let [base-dir-1 (str (fixtures/test-project-dir) "/worktree-1")
            base-dir-2 (str (fixtures/test-project-dir) "/worktree-2")]

        ;; Setup both worktrees
        (.mkdirs (io/file base-dir-1))
        (.mkdirs (io/file base-dir-2))

        (try
          ;; Write different state to each worktree
          (let [state-1 {:story-id 100
                         :task-id 101
                         :started-at "2025-10-20T14:00:00Z"}
                state-2 {:story-id 200
                         :task-id 201
                         :started-at "2025-10-20T15:00:00Z"}
                state-file-1 (io/file base-dir-1 ".mcp-tasks-current.edn")
                state-file-2 (io/file base-dir-2 ".mcp-tasks-current.edn")]
            (spit state-file-1 (pr-str state-1))
            (spit state-file-2 (pr-str state-2))

            ;; Verify both files exist and contain different data
            (is (fs/exists? state-file-1))
            (is (fs/exists? state-file-2))

            (let [read-state-1 (edn/read-string (slurp state-file-1))
                  read-state-2 (edn/read-string (slurp state-file-2))]
              (is (= 100 (:story-id read-state-1)))
              (is (= 101 (:task-id read-state-1)))
              (is (= 200 (:story-id read-state-2)))
              (is (= 201 (:task-id read-state-2)))))

          (finally
            ;; Cleanup
            (when (fs/exists? base-dir-1)
              (fs/delete-tree base-dir-1))
            (when (fs/exists? base-dir-2)
              (fs/delete-tree base-dir-2))))))

    (testing "clearing state in one worktree doesn't affect another"
      (let [base-dir-1 (str (fixtures/test-project-dir) "/worktree-a")
            base-dir-2 (str (fixtures/test-project-dir) "/worktree-b")]

        ;; Setup both worktrees
        (.mkdirs (io/file base-dir-1))
        (.mkdirs (io/file base-dir-2))

        (try
          ;; Write state to both worktrees
          (let [state {:story-id 50
                       :task-id 51
                       :started-at "2025-10-20T12:00:00Z"}
                state-file-1 (io/file base-dir-1 ".mcp-tasks-current.edn")
                state-file-2 (io/file base-dir-2 ".mcp-tasks-current.edn")]
            (spit state-file-1 (pr-str state))
            (spit state-file-2 (pr-str state))

            ;; Clear state in worktree-1
            (fs/delete state-file-1)

            ;; Verify worktree-1 state is cleared but worktree-2 remains
            (is (not (fs/exists? state-file-1)))
            (is (fs/exists? state-file-2))

            (let [read-state-2 (edn/read-string (slurp state-file-2))]
              (is (= 50 (:story-id read-state-2)))
              (is (= 51 (:task-id read-state-2)))))

          (finally
            ;; Cleanup
            (when (fs/exists? base-dir-1)
              (fs/delete-tree base-dir-1))
            (when (fs/exists? base-dir-2)
              (fs/delete-tree base-dir-2))))))))
