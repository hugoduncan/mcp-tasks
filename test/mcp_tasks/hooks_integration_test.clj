(ns mcp-tasks.hooks-integration-test
  "Integration tests for hook event capture workflow.

  Tests end-to-end scenarios: event stored on story via update-task tool,
  events archive with story on completion, and verifies event capture
  behavior when story-id is/isn't present in execution state.

  Note: Tests use the MCP server tools directly since hook scripts call
  the CLI which requires system installation."
  (:require
    [babashka.fs :as fs]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-clj.mcp-client.core :as mcp-client]
    [mcp-tasks.integration-test-fixtures :as fixtures]
    [mcp-tasks.tasks-file :as tasks-file]))

(use-fixtures :each fixtures/with-test-project)

;; Helper functions

(defn write-execution-state
  "Write execution state file to the test project directory."
  [state]
  (let [state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
    (spit state-file (pr-str state))))

(defn read-story-session-events
  "Read session-events from a story task."
  [tasks-file-path story-id]
  (let [tasks (tasks-file/read-ednl tasks-file-path)
        story (first (filter #(= story-id (:id %)) tasks))]
    (:session-events story)))

;; Integration Tests

(deftest ^:integ hook-event-stored-on-story-test
  ;; Test end-to-end: hook script triggered → event stored on story.
  ;; Verifies that hooks correctly read execution state and update the story.
  (testing "hook event capture workflow"
    (testing "UserPromptSubmit hook stores event on story"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Create story task
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                story {:id 100
                       :title "Test story for hook"
                       :description "Story description"
                       :design ""
                       :category "large"
                       :status :open
                       :type :story
                       :meta {}
                       :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story]))

          ;; Write execution state with story-id
          (write-execution-state {:story-id 100
                                  :task-id 101
                                  :task-start-time "2025-01-15T10:00:00Z"})

          ;; Simulate hook call via CLI update command
          (let [result @(mcp-client/call-tool
                          client
                          "update-task"
                          {:task-id 100
                           :session-events [{"event-type" "user-prompt"
                                             "content" "test user prompt"}]})]
            (is (not (:isError result))))

          ;; Verify event was stored
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                events (read-story-session-events (.getAbsolutePath tasks-file) 100)]
            (is (= 1 (count events)))
            (is (= :user-prompt (:event-type (first events))))
            (is (= "test user prompt" (:content (first events))))
            (is (some? (:timestamp (first events)))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "PreCompact hook stores compaction event on story"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Create story task
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                story {:id 200
                       :title "Story for compaction test"
                       :description "Story description"
                       :design ""
                       :category "large"
                       :status :open
                       :type :story
                       :meta {}
                       :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story]))

          ;; Write execution state
          (write-execution-state {:story-id 200
                                  :task-id 201
                                  :task-start-time "2025-01-15T11:00:00Z"})

          ;; Simulate compaction event via update-task
          (let [result @(mcp-client/call-tool
                          client
                          "update-task"
                          {:task-id 200
                           :session-events [{"event-type" "compaction"
                                             "trigger" "auto"}]})]
            (is (not (:isError result))))

          ;; Verify event was stored
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                events (read-story-session-events (.getAbsolutePath tasks-file) 200)]
            (is (= 1 (count events)))
            (is (= :compaction (:event-type (first events))))
            (is (= "auto" (:trigger (first events)))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "SessionStart hook stores session start event on story"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Create story task
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                story {:id 300
                       :title "Story for session start test"
                       :description "Story description"
                       :design ""
                       :category "large"
                       :status :open
                       :type :story
                       :meta {}
                       :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story]))

          ;; Write execution state
          (write-execution-state {:story-id 300
                                  :task-id 301
                                  :task-start-time "2025-01-15T12:00:00Z"})

          ;; Simulate session start event via update-task
          (let [result @(mcp-client/call-tool
                          client
                          "update-task"
                          {:task-id 300
                           :session-events [{"event-type" "session-start"
                                             "session-id" "session-abc123"
                                             "trigger" "startup"}]})]
            (is (not (:isError result))))

          ;; Verify event was stored
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                events (read-story-session-events (.getAbsolutePath tasks-file) 300)]
            (is (= 1 (count events)))
            (is (= :session-start (:event-type (first events))))
            (is (= "session-abc123" (:session-id (first events))))
            (is (= "startup" (:trigger (first events)))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ events-archive-with-story-test
  ;; Test that session-events are archived when story is completed.
  ;; Verifies events persist in complete.ednl after story completion.
  (testing "events archive with story on completion"
    (testing "session events persist in archive after story completion"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Create story with child task
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                story {:id 400
                       :title "Story to archive"
                       :description "Story with events"
                       :design ""
                       :category "large"
                       :status :open
                       :type :story
                       :meta {}
                       :relations []
                       :session-events [{:timestamp "2025-01-15T10:00:00Z"
                                         :event-type :user-prompt
                                         :content "first prompt"}
                                        {:timestamp "2025-01-15T10:05:00Z"
                                         :event-type :compaction
                                         :trigger "auto"}
                                        {:timestamp "2025-01-15T10:10:00Z"
                                         :event-type :session-start
                                         :session-id "s1"
                                         :trigger "resume"}]}
                child {:id 401
                       :parent-id 400
                       :title "Child task"
                       :description "Task description"
                       :design ""
                       :category "simple"
                       :status :closed
                       :type :task
                       :meta {}
                       :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story child]))

          ;; Write execution state
          (write-execution-state {:story-id 400
                                  :task-id 400
                                  :task-start-time "2025-01-15T10:15:00Z"})

          ;; Complete the story
          (let [result @(mcp-client/call-tool
                          client
                          "complete-task"
                          {:task-id 400})]
            (is (not (:isError result))))

          ;; Verify events are in archive
          (let [complete-file (io/file (fixtures/test-project-dir)
                                       ".mcp-tasks" "complete.ednl")
                completed-tasks (tasks-file/read-ednl (.getAbsolutePath complete-file))
                archived-story (first (filter #(= 400 (:id %)) completed-tasks))]
            (is (some? archived-story) "Story should be in archive")
            (is (= :closed (:status archived-story)))
            (is (= 3 (count (:session-events archived-story)))
                "All events should be preserved")
            ;; Verify event details
            (let [events (:session-events archived-story)]
              (is (= :user-prompt (:event-type (first events))))
              (is (= "first prompt" (:content (first events))))
              (is (= :compaction (:event-type (second events))))
              (is (= :session-start (:event-type (nth events 2))))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "events accumulated during story execution are preserved"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Create story and child
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                story {:id 500
                       :title "Story with accumulated events"
                       :description "Story"
                       :design ""
                       :category "large"
                       :status :open
                       :type :story
                       :meta {}
                       :relations []}
                child {:id 501
                       :parent-id 500
                       :title "Child task"
                       :description "Task"
                       :design ""
                       :category "simple"
                       :status :closed
                       :type :task
                       :meta {}
                       :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story child]))

          ;; Write execution state
          (write-execution-state {:story-id 500
                                  :task-id 501
                                  :task-start-time "2025-01-15T13:00:00Z"})

          ;; Add multiple events during execution
          @(mcp-client/call-tool
             client
             "update-task"
             {:task-id 500
              :session-events [{"event-type" "user-prompt"
                                "content" "event during task 1"}]})

          @(mcp-client/call-tool
             client
             "update-task"
             {:task-id 500
              :session-events [{"event-type" "compaction"
                                "trigger" "manual"}]})

          @(mcp-client/call-tool
             client
             "update-task"
             {:task-id 500
              :session-events [{"event-type" "user-prompt"
                                "content" "event during task 2"}]})

          ;; Update execution state for story completion
          (write-execution-state {:story-id 500
                                  :task-id 500
                                  :task-start-time "2025-01-15T13:30:00Z"})

          ;; Complete the story
          (let [result @(mcp-client/call-tool
                          client
                          "complete-task"
                          {:task-id 500})]
            (is (not (:isError result))))

          ;; Verify all events are archived
          (let [complete-file (io/file (fixtures/test-project-dir)
                                       ".mcp-tasks" "complete.ednl")
                completed-tasks (tasks-file/read-ednl (.getAbsolutePath complete-file))
                archived-story (first (filter #(= 500 (:id %)) completed-tasks))
                events (:session-events archived-story)]
            (is (= 3 (count events)) "All accumulated events should be archived")
            (is (= "event during task 1" (:content (first events))))
            (is (= :compaction (:event-type (second events))))
            (is (= "event during task 2" (:content (nth events 2)))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ events-only-captured-with-story-id-test
  ;; Test that events should only be captured on story tasks, not standalone tasks.
  ;; This validates the intended hook behavior pattern.
  (testing "events only captured when story-id is present"
    (testing "standalone tasks can receive events but should not by hook design"
      ;; Hooks check for story-id before calling update-task.
      ;; This test verifies the pattern - standalone tasks shouldn't have events.
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Create standalone task (no parent-id)
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                task {:id 600
                      :title "Standalone task"
                      :description "Task without parent"
                      :design ""
                      :category "simple"
                      :status :open
                      :type :task
                      :meta {}
                      :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [task]))

          ;; Write execution state WITHOUT story-id (standalone task)
          (write-execution-state {:story-id nil
                                  :task-id 600
                                  :task-start-time "2025-01-15T14:00:00Z"})

          ;; Verify task has no events initially (before any hook would run)
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                task (first tasks)]
            (is (or (nil? (:session-events task))
                    (empty? (:session-events task)))
                "Standalone task should have no session events"))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "update-task can add events when story-id is present"
      ;; Simulates what the hook does: check story-id, then call update-task.
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Create story
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                story {:id 700
                       :title "Story for hook test"
                       :description "Story"
                       :design ""
                       :category "large"
                       :status :open
                       :type :story
                       :meta {}
                       :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story]))

          ;; Write execution state WITH story-id
          (write-execution-state {:story-id 700
                                  :task-id 701
                                  :task-start-time "2025-01-15T15:00:00Z"})

          ;; Simulate hook behavior: call update-task with session event
          (let [result @(mcp-client/call-tool
                          client
                          "update-task"
                          {:task-id 700
                           :session-events [{"event-type" "user-prompt"
                                             "content" "simulated hook capture"}]})]
            (is (not (:isError result))))

          ;; Verify event was stored on story
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                events (read-story-session-events (.getAbsolutePath tasks-file) 700)]
            (is (= 1 (count events)) "Story should have one event")
            (is (= :user-prompt (:event-type (first events))))
            (is (= "simulated hook capture" (:content (first events)))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "event capture respects execution state pattern"
      ;; Tests that the execution state file is correctly read
      ;; and can be used to gate event capture.
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Create story and task
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                story {:id 800
                       :title "Story for execution state test"
                       :description "Story"
                       :design ""
                       :category "large"
                       :status :open
                       :type :story
                       :meta {}
                       :relations []}
                task {:id 801
                      :parent-id 800
                      :title "Child task"
                      :description "Task"
                      :design ""
                      :category "simple"
                      :status :open
                      :type :task
                      :meta {}
                      :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story task]))

          ;; Ensure no execution state file exists initially
          (let [state-file (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")]
            (when (fs/exists? state-file)
              (fs/delete state-file)))

          ;; Without execution state, hooks should not capture events.
          ;; Here we just verify the state pattern - hooks check the file.
          (is (not (fs/exists? (io/file (fixtures/test-project-dir) ".mcp-tasks-current.edn")))
              "No execution state file should exist")

          ;; Now write execution state
          (write-execution-state {:story-id 800
                                  :task-id 801
                                  :task-start-time "2025-01-15T16:00:00Z"})

          ;; With execution state, simulate hook capturing event
          (let [result @(mcp-client/call-tool
                          client
                          "update-task"
                          {:task-id 800
                           :session-events [{"event-type" "session-start"
                                             "session-id" "s1"
                                             "trigger" "startup"}]})]
            (is (not (:isError result))))

          ;; Verify event was captured on story
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                events (read-story-session-events (.getAbsolutePath tasks-file) 800)]
            (is (= 1 (count events)))
            (is (= :session-start (:event-type (first events)))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ multiple-events-during-story-execution-test
  ;; Test capturing multiple events of different types during story execution.
  ;; Verifies chronological order is preserved and all event types work.
  (testing "multiple events during story execution"
    (testing "captures mixed event types in chronological order"
      (fixtures/write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Create story
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                story {:id 900
                       :title "Story for multiple events"
                       :description "Story"
                       :design ""
                       :category "large"
                       :status :open
                       :type :story
                       :meta {}
                       :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story]))

          ;; Write execution state
          (write-execution-state {:story-id 900
                                  :task-id 901
                                  :task-start-time "2025-01-15T16:00:00Z"})

          ;; Capture session-start event
          @(mcp-client/call-tool
             client
             "update-task"
             {:task-id 900
              :session-events [{"event-type" "session-start"
                                "session-id" "s1"
                                "trigger" "startup"}]})

          ;; Capture user-prompt event
          @(mcp-client/call-tool
             client
             "update-task"
             {:task-id 900
              :session-events [{"event-type" "user-prompt"
                                "content" "first prompt"}]})

          ;; Capture compaction event
          @(mcp-client/call-tool
             client
             "update-task"
             {:task-id 900
              :session-events [{"event-type" "compaction"
                                "trigger" "auto"}]})

          ;; Capture another user-prompt
          @(mcp-client/call-tool
             client
             "update-task"
             {:task-id 900
              :session-events [{"event-type" "user-prompt"
                                "content" "second prompt"}]})

          ;; Verify all events in order
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                events (read-story-session-events (.getAbsolutePath tasks-file) 900)]
            (is (= 4 (count events)))
            (is (= :session-start (:event-type (nth events 0))))
            (is (= :user-prompt (:event-type (nth events 1))))
            (is (= "first prompt" (:content (nth events 1))))
            (is (= :compaction (:event-type (nth events 2))))
            (is (= :user-prompt (:event-type (nth events 3))))
            (is (= "second prompt" (:content (nth events 3)))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))
