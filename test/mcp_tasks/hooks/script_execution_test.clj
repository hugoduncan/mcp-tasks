(ns mcp-tasks.hooks.script-execution-test
  "Integration tests for hook script execution.

  Tests the end-to-end flow: babashka script receives stdin JSON,
  reads execution state, calls mcp-tasks update CLI, and session
  events are stored on the story task.

  These tests execute the actual hook scripts with a real mcp-tasks
  CLI wrapper to verify the complete integration path."
  (:require
    [babashka.fs :as fs]
    [babashka.process :as p]
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.tasks-file :as tasks-file])
  (:import
    (java.nio.file
      Files)
    (java.nio.file.attribute
      FileAttribute
      PosixFilePermissions)))

;; Test Infrastructure

(def ^:private project-root
  "Root directory of the mcp-tasks project."
  (-> (io/file ".")
      .getCanonicalFile
      .getAbsolutePath))

(def ^:private hook-scripts
  "Paths to hook scripts relative to project root."
  {:user-prompt-submit "resources/hooks/user-prompt-submit.bb"
   :pre-compact "resources/hooks/pre-compact.bb"
   :session-start "resources/hooks/session-start.bb"})

(defn- create-temp-dir
  "Create a temporary directory for testing."
  []
  (str (Files/createTempDirectory "hook-exec-test"
                                  (into-array FileAttribute []))))

(defn- delete-dir
  "Recursively delete a directory."
  [dir]
  (fs/delete-tree dir))

(defn- compute-classpath
  "Compute the classpath for running the CLI.

  Converts relative paths to absolute paths so the CLI can run from
  any directory."
  []
  (let [result (p/shell {:out :string
                         :err :string
                         :dir project-root}
                        "clojure" "-Spath" "-M:cli")
        raw-cp (str/trim (:out result))
        ;; Convert relative paths to absolute
        entries (str/split raw-cp #":")
        abs-entries (map (fn [entry]
                           (if (str/starts-with? entry "/")
                             entry
                             (str project-root "/" entry)))
                         entries)]
    (str/join ":" abs-entries)))

(defn- create-cli-wrapper
  "Create a wrapper script that invokes mcp-tasks via java with classpath.

  The wrapper uses a precomputed classpath from the project root but runs
  java from the current directory, allowing config discovery to find the
  test project's config.

  Returns the path to the wrapper script directory (to add to PATH)."
  [test-dir classpath]
  (let [bin-dir (io/file test-dir "bin")
        cp-file (io/file bin-dir "classpath.txt")
        wrapper (io/file bin-dir "mcp-tasks")
        ;; Script that reads classpath from file to avoid heredoc issues
        script "#!/bin/bash
DIR=\"$(dirname \"$0\")\"
CP=$(cat \"$DIR/classpath.txt\")
exec java -cp \"$CP\" clojure.main -m mcp-tasks.cli \"$@\"
"]
    (fs/create-dirs bin-dir)
    ;; Write classpath to separate file
    (spit cp-file classpath)
    (spit wrapper script)
    ;; Make executable
    (let [perms (PosixFilePermissions/fromString "rwxr-xr-x")]
      (Files/setPosixFilePermissions (.toPath wrapper) perms))
    (str bin-dir)))

(def ^:private cached-classpath
  "Cached classpath to avoid recomputing for each test."
  (delay (compute-classpath)))

(defn- setup-test-project
  "Set up a test project directory with config and tasks.

  Returns a map with:
  - :test-dir - the test directory path
  - :bin-dir - directory containing mcp-tasks wrapper (for PATH)
  - :tasks-file - path to tasks.ednl"
  []
  (let [test-dir (create-temp-dir)
        mcp-tasks-dir (io/file test-dir ".mcp-tasks")
        tasks-file (io/file mcp-tasks-dir "tasks.ednl")
        bin-dir (create-cli-wrapper test-dir @cached-classpath)]
    ;; Create directory structure
    (fs/create-dirs mcp-tasks-dir)
    ;; Create config file
    (spit (io/file test-dir ".mcp-tasks.edn") "{:use-git? false}")
    ;; Create empty tasks file
    (spit tasks-file "")
    {:test-dir test-dir
     :bin-dir bin-dir
     :tasks-file (str tasks-file)}))

(defn- write-story
  "Write a story task to the tasks file."
  [tasks-file story]
  (tasks-file/write-tasks tasks-file [story]))

(defn- write-execution-state
  "Write execution state to .mcp-tasks-current.edn."
  [test-dir state]
  (spit (io/file test-dir ".mcp-tasks-current.edn") (pr-str state)))

(defn- read-story-events
  "Read session-events from a story in the tasks file."
  [tasks-file story-id]
  (let [tasks (tasks-file/read-ednl tasks-file)
        story (first (filter #(= story-id (:id %)) tasks))]
    (:session-events story)))

(defn- run-hook-script
  "Run a hook script with the given stdin input.

  Parameters:
  - script-key: keyword identifying the script (:user-prompt-submit, etc.)
  - input-json: JSON string to pass as stdin
  - test-dir: test project directory
  - bin-dir: directory containing mcp-tasks wrapper

  Returns {:exit exit-code :out stdout :err stderr}"
  [script-key input-json test-dir bin-dir]
  (let [script-path (get hook-scripts script-key)
        result (p/shell {:in input-json
                         :out :string
                         :err :string
                         :dir test-dir
                         ;; Prepend bin-dir to PATH so hooks find mcp-tasks
                         :extra-env {"PATH" (str bin-dir ":" (System/getenv "PATH"))}
                         :continue true}
                        "bb" (str project-root "/" script-path))]
    {:exit (:exit result)
     :out (:out result)
     :err (:err result)}))

;; Integration Tests

(deftest ^:integration user-prompt-submit-script-execution-test
  ;; Tests that the UserPromptSubmit hook script executes end-to-end:
  ;; receives stdin JSON, reads execution state, calls mcp-tasks update,
  ;; and session events are stored on the story.
  (testing "UserPromptSubmit script execution"
    (testing "when story is executing"
      (testing "stores user-prompt event on story"
        (let [{:keys [test-dir bin-dir tasks-file]} (setup-test-project)]
          (try
            ;; Create a story
            (write-story tasks-file
                         {:id 1
                          :title "Test story"
                          :description "Story for hook test"
                          :design ""
                          :category "large"
                          :status :open
                          :type :story
                          :meta {}
                          :relations []})

            ;; Write execution state indicating story is active
            (write-execution-state test-dir
                                   {:story-id 1
                                    :task-id 2
                                    :task-start-time "2025-01-15T10:00:00Z"})

            ;; Run the hook script with simulated Claude Code input
            (let [input (json/generate-string
                          {:session_id "test-session"
                           :prompt "Help me implement the feature"
                           :cwd test-dir})
                  result (run-hook-script :user-prompt-submit input test-dir bin-dir)]
              ;; Hook should exit 0
              (is (= 0 (:exit result))
                  (str "Hook should exit 0. stderr: " (:err result)))

              ;; Verify session event was stored
              (let [events (read-story-events tasks-file 1)]
                (is (= 1 (count events))
                    "Should have one session event")
                (is (= :user-prompt (:event-type (first events)))
                    "Event type should be :user-prompt")
                (is (= "Help me implement the feature" (:content (first events)))
                    "Content should match prompt")
                (is (some? (:timestamp (first events)))
                    "Event should have timestamp")))

            (finally
              (delete-dir test-dir))))))

    (testing "when no story is executing"
      (testing "does not store events"
        (let [{:keys [test-dir bin-dir tasks-file]} (setup-test-project)]
          (try
            ;; Create a standalone task (not a story)
            (write-story tasks-file
                         {:id 1
                          :title "Standalone task"
                          :description "Task without parent"
                          :design ""
                          :category "simple"
                          :status :open
                          :type :task
                          :meta {}
                          :relations []})

            ;; Write execution state WITHOUT story-id
            (write-execution-state test-dir
                                   {:task-id 1
                                    :task-start-time "2025-01-15T10:00:00Z"})

            ;; Run the hook
            (let [input (json/generate-string
                          {:session_id "test-session"
                           :prompt "Some prompt"
                           :cwd test-dir})
                  result (run-hook-script :user-prompt-submit input test-dir bin-dir)]
              ;; Should still exit 0 (non-blocking)
              (is (= 0 (:exit result)))

              ;; Task should have no events (hook skips when no story-id)
              (let [events (read-story-events tasks-file 1)]
                (is (or (nil? events) (empty? events))
                    "Standalone task should have no session events")))

            (finally
              (delete-dir test-dir))))))

    (testing "truncates long prompts"
      (let [{:keys [test-dir bin-dir tasks-file]} (setup-test-project)]
        (try
          (write-story tasks-file
                       {:id 1
                        :title "Test story"
                        :description "Story"
                        :design ""
                        :category "large"
                        :status :open
                        :type :story
                        :meta {}
                        :relations []})

          (write-execution-state test-dir
                                 {:story-id 1
                                  :task-id 2
                                  :task-start-time "2025-01-15T10:00:00Z"})

          ;; Create a very long prompt (>1000 chars)
          (let [long-prompt (apply str (repeat 1500 "x"))
                input (json/generate-string
                        {:session_id "test-session"
                         :prompt long-prompt
                         :cwd test-dir})
                result (run-hook-script :user-prompt-submit input test-dir bin-dir)]
            (is (= 0 (:exit result)))

            (let [events (read-story-events tasks-file 1)
                  content (:content (first events))]
              (is (<= (count content) 1000)
                  "Content should be truncated to 1000 chars")
              (is (str/ends-with? content "...")
                  "Truncated content should end with ...")))

          (finally
            (delete-dir test-dir)))))))

(deftest ^:integration pre-compact-script-execution-test
  ;; Tests that the PreCompact hook script executes end-to-end.
  (testing "PreCompact script execution"
    (testing "when story is executing"
      (testing "stores compaction event on story"
        (let [{:keys [test-dir bin-dir tasks-file]} (setup-test-project)]
          (try
            (write-story tasks-file
                         {:id 1
                          :title "Test story"
                          :description "Story"
                          :design ""
                          :category "large"
                          :status :open
                          :type :story
                          :meta {}
                          :relations []})

            (write-execution-state test-dir
                                   {:story-id 1
                                    :task-id 2
                                    :task-start-time "2025-01-15T10:00:00Z"})

            (let [input (json/generate-string
                          {:session_id "test-session"
                           :trigger "auto"
                           :cwd test-dir})
                  result (run-hook-script :pre-compact input test-dir bin-dir)]
              (is (= 0 (:exit result))
                  (str "Hook should exit 0. stderr: " (:err result)))

              (let [events (read-story-events tasks-file 1)]
                (is (= 1 (count events)))
                (is (= :compaction (:event-type (first events))))
                (is (= "auto" (:trigger (first events))))
                (is (some? (:timestamp (first events))))))

            (finally
              (delete-dir test-dir))))))

    (testing "captures manual trigger"
      (let [{:keys [test-dir bin-dir tasks-file]} (setup-test-project)]
        (try
          (write-story tasks-file
                       {:id 1
                        :title "Test story"
                        :description "Story"
                        :design ""
                        :category "large"
                        :status :open
                        :type :story
                        :meta {}
                        :relations []})

          (write-execution-state test-dir
                                 {:story-id 1
                                  :task-id 2
                                  :task-start-time "2025-01-15T10:00:00Z"})

          (let [input (json/generate-string
                        {:session_id "test-session"
                         :trigger "manual"
                         :cwd test-dir})
                result (run-hook-script :pre-compact input test-dir bin-dir)]
            (is (= 0 (:exit result)))

            (let [events (read-story-events tasks-file 1)]
              (is (= "manual" (:trigger (first events))))))

          (finally
            (delete-dir test-dir)))))))

(deftest ^:integration session-start-script-execution-test
  ;; Tests that the SessionStart hook script executes end-to-end.
  (testing "SessionStart script execution"
    (testing "when story is executing"
      (testing "stores session-start event on story"
        (let [{:keys [test-dir bin-dir tasks-file]} (setup-test-project)]
          (try
            (write-story tasks-file
                         {:id 1
                          :title "Test story"
                          :description "Story"
                          :design ""
                          :category "large"
                          :status :open
                          :type :story
                          :meta {}
                          :relations []})

            (write-execution-state test-dir
                                   {:story-id 1
                                    :task-id 2
                                    :task-start-time "2025-01-15T10:00:00Z"})

            (let [input (json/generate-string
                          {:session_id "sess-abc123"
                           :source "startup"
                           :cwd test-dir})
                  result (run-hook-script :session-start input test-dir bin-dir)]
              (is (= 0 (:exit result))
                  (str "Hook should exit 0. stderr: " (:err result)))

              (let [events (read-story-events tasks-file 1)]
                (is (= 1 (count events)))
                (is (= :session-start (:event-type (first events))))
                (is (= "sess-abc123" (:session-id (first events))))
                (is (= "startup" (:trigger (first events))))
                (is (some? (:timestamp (first events))))))

            (finally
              (delete-dir test-dir))))))

    (testing "captures resume source"
      (let [{:keys [test-dir bin-dir tasks-file]} (setup-test-project)]
        (try
          (write-story tasks-file
                       {:id 1
                        :title "Test story"
                        :description "Story"
                        :design ""
                        :category "large"
                        :status :open
                        :type :story
                        :meta {}
                        :relations []})

          (write-execution-state test-dir
                                 {:story-id 1
                                  :task-id 2
                                  :task-start-time "2025-01-15T10:00:00Z"})

          (let [input (json/generate-string
                        {:session_id "sess-xyz789"
                         :source "resume"
                         :cwd test-dir})
                result (run-hook-script :session-start input test-dir bin-dir)]
            (is (= 0 (:exit result)))

            (let [events (read-story-events tasks-file 1)]
              (is (= "resume" (:trigger (first events))))))

          (finally
            (delete-dir test-dir)))))))

(deftest ^:integration multiple-events-accumulation-test
  ;; Tests that multiple hook invocations accumulate events correctly.
  (testing "multiple events accumulation"
    (testing "events from different hooks accumulate in order"
      (let [{:keys [test-dir bin-dir tasks-file]} (setup-test-project)]
        (try
          (write-story tasks-file
                       {:id 1
                        :title "Test story"
                        :description "Story"
                        :design ""
                        :category "large"
                        :status :open
                        :type :story
                        :meta {}
                        :relations []})

          (write-execution-state test-dir
                                 {:story-id 1
                                  :task-id 2
                                  :task-start-time "2025-01-15T10:00:00Z"})

          ;; Fire multiple hooks in sequence
          (run-hook-script :session-start
                           (json/generate-string
                             {:session_id "s1" :source "startup" :cwd test-dir})
                           test-dir bin-dir)

          (run-hook-script :user-prompt-submit
                           (json/generate-string
                             {:session_id "s1" :prompt "First prompt" :cwd test-dir})
                           test-dir bin-dir)

          (run-hook-script :pre-compact
                           (json/generate-string
                             {:session_id "s1" :trigger "auto" :cwd test-dir})
                           test-dir bin-dir)

          (run-hook-script :user-prompt-submit
                           (json/generate-string
                             {:session_id "s1" :prompt "Second prompt" :cwd test-dir})
                           test-dir bin-dir)

          ;; Verify all events accumulated
          (let [events (read-story-events tasks-file 1)]
            (is (= 4 (count events))
                "Should have 4 events")
            (is (= :session-start (:event-type (nth events 0))))
            (is (= :user-prompt (:event-type (nth events 1))))
            (is (= "First prompt" (:content (nth events 1))))
            (is (= :compaction (:event-type (nth events 2))))
            (is (= :user-prompt (:event-type (nth events 3))))
            (is (= "Second prompt" (:content (nth events 3)))))

          (finally
            (delete-dir test-dir)))))))

(deftest ^:integration hook-error-handling-test
  ;; Tests that hooks handle errors gracefully and always exit 0.
  (testing "hook error handling"
    (testing "exits 0 when task not found"
      (let [{:keys [test-dir bin-dir tasks-file]} (setup-test-project)]
        (try
          ;; Create a story but reference wrong ID in execution state
          (write-story tasks-file
                       {:id 1
                        :title "Test story"
                        :description "Story"
                        :design ""
                        :category "large"
                        :status :open
                        :type :story
                        :meta {}
                        :relations []})

          ;; Reference non-existent story-id 999
          (write-execution-state test-dir
                                 {:story-id 999
                                  :task-id 1000
                                  :task-start-time "2025-01-15T10:00:00Z"})

          (let [input (json/generate-string
                        {:session_id "test"
                         :prompt "test"
                         :cwd test-dir})
                result (run-hook-script :user-prompt-submit input test-dir bin-dir)]
            ;; Should still exit 0 (non-blocking)
            (is (= 0 (:exit result))
                "Hook should exit 0 even when task not found"))

          (finally
            (delete-dir test-dir)))))

    (testing "exits 0 when execution state file missing"
      (let [{:keys [test-dir bin-dir tasks-file]} (setup-test-project)]
        (try
          (write-story tasks-file
                       {:id 1
                        :title "Test story"
                        :description "Story"
                        :design ""
                        :category "large"
                        :status :open
                        :type :story
                        :meta {}
                        :relations []})

          ;; Don't write execution state file
          (let [input (json/generate-string
                        {:session_id "test"
                         :prompt "test"
                         :cwd test-dir})
                result (run-hook-script :user-prompt-submit input test-dir bin-dir)]
            (is (= 0 (:exit result))
                "Hook should exit 0 when no execution state"))

          (finally
            (delete-dir test-dir)))))))
