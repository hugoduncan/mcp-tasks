(ns mcp-tasks.shared-context-integration-test
  "Integration tests for shared context workflow across multiple task executions.

  Tests complete story execution workflow with shared context communication
  between child tasks, verifying automatic prefixing, context persistence,
  and backward compatibility."
  (:require
    [babashka.fs :as fs]
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-clj.mcp-client.core :as mcp-client]
    [mcp-tasks.integration-test-fixtures :as fixtures]
    [mcp-tasks.tasks-file :as tasks-file]))

(use-fixtures :each fixtures/with-test-project)

(deftest ^:integ shared-context-multi-task-workflow-test
  ;; Test complete workflow: Task 1 appends context, Task 2 reads and appends,
  ;; verifying automatic prefixing and chronological order
  (testing "shared context workflow across multiple tasks"
    (fixtures/write-config-file "{:use-git? false}")

    (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
      (try
        ;; Setup: Create story with two child tasks
        (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
              story {:id 100
                     :title "Story with shared context"
                     :description "Test story"
                     :design ""
                     :category "large"
                     :status :open
                     :type :story
                     :meta {}
                     :relations []}
              task1 {:id 101
                     :parent-id 100
                     :title "First task"
                     :description "First child task"
                     :design ""
                     :category "simple"
                     :status :open
                     :type :task
                     :meta {}
                     :relations []}
              task2 {:id 102
                     :parent-id 100
                     :title "Second task"
                     :description "Second child task"
                     :design ""
                     :category "simple"
                     :status :open
                     :type :task
                     :meta {}
                     :relations []}]
          (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story task1 task2]))

        ;; Simulate Task 1 execution
        (testing "Task 1 appends to parent context with automatic prefix"
          ;; Write execution state for task 101
          (let [state {:story-id 100
                       :task-id 101
                       :task-start-time "2025-01-15T10:00:00Z"}
                state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
            (spit state-file (pr-str state)))

          ;; Task 1 appends to parent story's shared context
          (let [result @(mcp-client/call-tool
                          client
                          "update-task"
                          {:task-id 100
                           :shared-context ["API endpoint chosen: https://api.example.com/v1"]})]
            (is (not (:isError result)))

            ;; Verify context was added with automatic prefix
            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                  story (first (filter #(= 100 (:id %)) tasks))]
              (is (= ["Task 101: API endpoint chosen: https://api.example.com/v1"]
                     (:shared-context story))))))

        ;; Simulate Task 2 execution
        (testing "Task 2 reads parent context and appends with different task ID prefix"
          ;; Update execution state for task 102
          (let [state {:story-id 100
                       :task-id 102
                       :task-start-time "2025-01-15T11:00:00Z"}
                state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
            (spit state-file (pr-str state)))

          ;; Task 2 reads parent context via select-tasks
          (let [result @(mcp-client/call-tool
                          client
                          "select-tasks"
                          {:task-id 102
                           :unique true})
                response (json/parse-string (get-in result [:content 0 :text]) keyword)
                task (first (:tasks response))]
            (is (not (:isError result)))
            (is (= 102 (:id task)))
            (is (= ["Task 101: API endpoint chosen: https://api.example.com/v1"]
                   (:parent-shared-context task))))

          ;; Task 2 appends additional context
          (let [result @(mcp-client/call-tool
                          client
                          "update-task"
                          {:task-id 100
                           :shared-context ["Implemented JWT authentication, tokens expire in 1 hour"]})]
            (is (not (:isError result)))

            ;; Verify both entries exist with correct prefixes in chronological order
            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                  story (first (filter #(= 100 (:id %)) tasks))]
              (is (= ["Task 101: API endpoint chosen: https://api.example.com/v1"
                      "Task 102: Implemented JWT authentication, tokens expire in 1 hour"]
                     (:shared-context story))))))

        (finally
          (mcp-client/close! client)
          ((:stop server)))))))

(deftest ^:integ shared-context-multiple-appends-same-task-test
  ;; Test that a single task can append to shared context multiple times
  ;; and all entries are preserved with correct prefixes
  (testing "single task appends to shared context multiple times"
    (fixtures/write-config-file "{:use-git? false}")

    (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
      (try
        ;; Setup: Create story with one child task
        (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
              story {:id 200
                     :title "Story for multiple appends"
                     :description "Test story"
                     :design ""
                     :category "large"
                     :status :open
                     :type :story
                     :meta {}
                     :relations []}
              task {:id 201
                    :parent-id 200
                    :title "Task with multiple context updates"
                    :description "Task description"
                    :design ""
                    :category "simple"
                    :status :open
                    :type :task
                    :meta {}
                    :relations []}]
          (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story task]))

        ;; Set execution state
        (let [state {:story-id 200
                     :task-id 201
                     :task-start-time "2025-01-15T12:00:00Z"}
              state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
          (spit state-file (pr-str state)))

        ;; First append
        (testing "first append creates initial context"
          (let [result @(mcp-client/call-tool
                          client
                          "update-task"
                          {:task-id 200
                           :shared-context ["Started implementation"]})]
            (is (not (:isError result)))

            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                  story (first (filter #(= 200 (:id %)) tasks))]
              (is (= ["Task 201: Started implementation"]
                     (:shared-context story))))))

        ;; Second append
        (testing "second append preserves first entry"
          (let [result @(mcp-client/call-tool
                          client
                          "update-task"
                          {:task-id 200
                           :shared-context ["Found edge case - empty input"]})]
            (is (not (:isError result)))

            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                  story (first (filter #(= 200 (:id %)) tasks))]
              (is (= ["Task 201: Started implementation"
                      "Task 201: Found edge case - empty input"]
                     (:shared-context story))))))

        ;; Third append
        (testing "third append preserves all previous entries"
          (let [result @(mcp-client/call-tool
                          client
                          "update-task"
                          {:task-id 200
                           :shared-context ["Completed implementation with validation"]})]
            (is (not (:isError result)))

            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                  story (first (filter #(= 200 (:id %)) tasks))]
              (is (= ["Task 201: Started implementation"
                      "Task 201: Found edge case - empty input"
                      "Task 201: Completed implementation with validation"]
                     (:shared-context story))))))

        (finally
          (mcp-client/close! client)
          ((:stop server)))))))

(deftest ^:integ shared-context-without-execution-state-test
  ;; Test that manual updates (without execution state) add entries without prefix
  (testing "shared context updates without execution state"
    (fixtures/write-config-file "{:use-git? false}")

    (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
      (try
        ;; Setup: Create story
        (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
              story {:id 300
                     :title "Story for manual updates"
                     :description "Test story"
                     :design ""
                     :category "large"
                     :status :open
                     :type :story
                     :meta {}
                     :relations []}]
          (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story]))

        ;; No execution state file exists
        (let [state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
          (is (not (fs/exists? state-file))))

        ;; Manual update without execution state
        (testing "entries are added without task prefix"
          (let [result @(mcp-client/call-tool
                          client
                          "update-task"
                          {:task-id 300
                           :shared-context ["Manual context entry"]})]
            (is (not (:isError result)))

            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                  story (first tasks)]
              (is (= ["Manual context entry"]
                     (:shared-context story))))))

        ;; Another manual update
        (testing "subsequent manual entries also lack prefix"
          (let [result @(mcp-client/call-tool
                          client
                          "update-task"
                          {:task-id 300
                           :shared-context ["Another manual entry"]})]
            (is (not (:isError result)))

            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                  story (first tasks)]
              (is (= ["Manual context entry"
                      "Another manual entry"]
                     (:shared-context story))))))

        (finally
          (mcp-client/close! client)
          ((:stop server)))))))

(deftest ^:integ shared-context-backward-compatibility-test
  ;; Test that stories and tasks without :shared-context field work correctly
  (testing "backward compatibility with tasks lacking shared-context"
    (fixtures/write-config-file "{:use-git? false}")

    (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
      (try
        ;; Setup: Create story and task WITHOUT :shared-context field
        (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
              story {:id 400
                     :title "Legacy story"
                     :description "Story without shared-context field"
                     :design ""
                     :category "large"
                     :status :open
                     :type :story
                     :meta {}
                     :relations []}
              task {:id 401
                    :parent-id 400
                    :title "Legacy task"
                    :description "Task description"
                    :design ""
                    :category "simple"
                    :status :open
                    :type :task
                    :meta {}
                    :relations []}]
          (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story task]))

        ;; Child task reading parent context should get empty vector
        (testing "child task gets empty parent-shared-context"
          (let [result @(mcp-client/call-tool
                          client
                          "select-tasks"
                          {:parent-id 400})
                response (json/parse-string (get-in result [:content 0 :text]) keyword)
                task (first (:tasks response))]
            (is (not (:isError result)))
            (is (= 401 (:id task)))
            (is (= [] (:parent-shared-context task)))))

        ;; Adding context to legacy story should work
        (testing "can add shared-context to legacy story"
          (let [state {:story-id 400
                       :task-id 401
                       :task-start-time "2025-01-15T13:00:00Z"}
                state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
            (spit state-file (pr-str state)))

          (let [result @(mcp-client/call-tool
                          client
                          "update-task"
                          {:task-id 400
                           :shared-context ["First context for legacy story"]})]
            (is (not (:isError result)))

            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                  story (first (filter #(= 400 (:id %)) tasks))]
              (is (= ["Task 401: First context for legacy story"]
                     (:shared-context story))))))

        (finally
          (mcp-client/close! client)
          ((:stop server)))))))

(deftest ^:integ shared-context-persistence-across-completion-test
  ;; Test that shared context persists when tasks are completed
  (testing "shared context persists across task completions"
    (fixtures/write-config-file "{:use-git? false}")

    (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
      (try
        ;; Setup: Create story with three child tasks
        (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
              story {:id 500
                     :title "Story with persistence test"
                     :description "Test story"
                     :design ""
                     :category "large"
                     :status :open
                     :type :story
                     :meta {}
                     :relations []}
              task1 {:id 501
                     :parent-id 500
                     :title "Task 1"
                     :description "First task"
                     :design ""
                     :category "simple"
                     :status :open
                     :type :task
                     :meta {}
                     :relations []}
              task2 {:id 502
                     :parent-id 500
                     :title "Task 2"
                     :description "Second task"
                     :design ""
                     :category "simple"
                     :status :open
                     :type :task
                     :meta {}
                     :relations []}
              task3 {:id 503
                     :parent-id 500
                     :title "Task 3"
                     :description "Third task"
                     :design ""
                     :category "simple"
                     :status :open
                     :type :task
                     :meta {}
                     :relations []}]
          (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story task1 task2 task3]))

        ;; Task 1: Add context and complete
        (testing "Task 1 adds context and completes"
          (let [state {:story-id 500
                       :task-id 501
                       :task-start-time "2025-01-15T14:00:00Z"}
                state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
            (spit state-file (pr-str state)))

          @(mcp-client/call-tool
             client
             "update-task"
             {:task-id 500
              :shared-context ["Task 1 completed setup"]})

          (let [result @(mcp-client/call-tool
                          client
                          "complete-task"
                          {:task-id 501})]
            (is (not (:isError result)))))

        ;; Task 2: Read context (should still see Task 1's entry), add more, and complete
        (testing "Task 2 reads Task 1's context and adds more"
          (let [state {:story-id 500
                       :task-id 502
                       :task-start-time "2025-01-15T14:30:00Z"}
                state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
            (spit state-file (pr-str state)))

          ;; Read context
          (let [result @(mcp-client/call-tool
                          client
                          "select-tasks"
                          {:task-id 502
                           :unique true})
                response (json/parse-string (get-in result [:content 0 :text]) keyword)
                task (first (:tasks response))]
            (is (= 502 (:id task)))
            (is (= ["Task 501: Task 1 completed setup"]
                   (:parent-shared-context task))))

          ;; Add context
          @(mcp-client/call-tool
             client
             "update-task"
             {:task-id 500
              :shared-context ["Task 2 implemented feature X"]})

          @(mcp-client/call-tool
             client
             "complete-task"
             {:task-id 502}))

        ;; Task 3: Read context (should see both Task 1 and Task 2 entries)
        (testing "Task 3 reads both previous tasks' context"
          (let [state {:story-id 500
                       :task-id 503
                       :task-start-time "2025-01-15T15:00:00Z"}
                state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
            (spit state-file (pr-str state)))

          (let [result @(mcp-client/call-tool
                          client
                          "select-tasks"
                          {:task-id 503
                           :unique true})
                response (json/parse-string (get-in result [:content 0 :text]) keyword)
                task (first (:tasks response))]
            (is (= 503 (:id task)))
            (is (= ["Task 501: Task 1 completed setup"
                    "Task 502: Task 2 implemented feature X"]
                   (:parent-shared-context task)))))

        ;; Verify shared context persists in story record
        (testing "shared context persists in story record"
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                story (first (filter #(= 500 (:id %)) tasks))]
            (is (= ["Task 501: Task 1 completed setup"
                    "Task 502: Task 2 implemented feature X"]
                   (:shared-context story)))))

        (finally
          (mcp-client/close! client)
          ((:stop server)))))))

(deftest ^:integ shared-context-size-limit-enforcement-test
  ;; Test that the 50KB size limit is enforced
  (testing "shared context size limit enforcement"
    (fixtures/write-config-file "{:use-git? false}")

    (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
      (try
        ;; Setup: Create story
        (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
              story {:id 600
                     :title "Story for size limit test"
                     :description "Test story"
                     :design ""
                     :category "large"
                     :status :open
                     :type :story
                     :meta {}
                     :relations []}]
          (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story]))

        ;; Try to add context that exceeds 50KB
        (testing "rejects context exceeding 50KB limit"
          (let [large-entry (apply str (repeat 52000 "x"))
                result @(mcp-client/call-tool
                          client
                          "update-task"
                          {:task-id 600
                           :shared-context [large-entry]})]
            (is (:isError result))
            (let [error-text (get-in result [:content 0 :text])]
              (is (str/includes? error-text "Shared context size limit (50KB) exceeded")))))

        ;; Verify story has no context after failed attempt
        (testing "story remains unchanged after failed update"
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                story (first tasks)]
            (is (or (nil? (:shared-context story))
                    (empty? (:shared-context story))))))

        (finally
          (mcp-client/close! client)
          ((:stop server)))))))

(deftest ^:integ shared-context-with-git-enabled-test
  ;; Test that shared context workflow works with git enabled
  (testing "shared context workflow with git enabled"
    (fixtures/write-config-file "{:use-git? true}")
    (fixtures/init-test-git-repo)

    (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
      (try
        ;; Setup: Create story with child tasks
        (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
              story {:id 700
                     :title "Story with git"
                     :description "Test story"
                     :design ""
                     :category "large"
                     :status :open
                     :type :story
                     :meta {}
                     :relations []}
              task1 {:id 701
                     :parent-id 700
                     :title "Task 1 with git"
                     :description "First task"
                     :design ""
                     :category "simple"
                     :status :open
                     :type :task
                     :meta {}
                     :relations []}
              task2 {:id 702
                     :parent-id 700
                     :title "Task 2 with git"
                     :description "Second task"
                     :design ""
                     :category "simple"
                     :status :open
                     :type :task
                     :meta {}
                     :relations []}]
          (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story task1 task2]))

        ;; Task 1: Add context
        (testing "Task 1 adds context with git enabled"
          (let [state {:story-id 700
                       :task-id 701
                       :task-start-time "2025-01-15T16:00:00Z"}
                state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
            (spit state-file (pr-str state)))

          (let [result @(mcp-client/call-tool
                          client
                          "update-task"
                          {:task-id 700
                           :shared-context ["Task 1 context with git"]})]
            (is (not (:isError result)))))

        ;; Task 2: Read and append context
        (testing "Task 2 reads and appends context with git enabled"
          (let [state {:story-id 700
                       :task-id 702
                       :task-start-time "2025-01-15T16:30:00Z"}
                state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
            (spit state-file (pr-str state)))

          ;; Read context
          (let [result @(mcp-client/call-tool
                          client
                          "select-tasks"
                          {:task-id 702
                           :unique true})
                response (json/parse-string (get-in result [:content 0 :text]) keyword)
                task (first (:tasks response))]
            (is (= ["Task 701: Task 1 context with git"]
                   (:parent-shared-context task))))

          ;; Append context
          (let [result @(mcp-client/call-tool
                          client
                          "update-task"
                          {:task-id 700
                           :shared-context ["Task 2 context with git"]})]
            (is (not (:isError result)))))

        ;; Verify final state
        (testing "shared context persists correctly with git"
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                story (first (filter #(= 700 (:id %)) tasks))]
            (is (= ["Task 701: Task 1 context with git"
                    "Task 702: Task 2 context with git"]
                   (:shared-context story)))))

        (finally
          (mcp-client/close! client)
          ((:stop server)))))))
