(ns mcp-tasks.native-binary-integration-test
  "Integration tests for native binary CLI executable.

  Tests that the GraalVM native-image binary behaves identically to the
  JVM CLI implementation. Includes smoke tests suitable for CI and
  comprehensive workflow tests for Linux."
  (:require
    [babashka.fs :as fs]
    [babashka.process :as process]
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:dynamic *test-dir* nil)
(def ^:dynamic *binary-path* nil)

(defn- setup-test-dir
  [test-dir]
  (fs/create-dirs (io/file test-dir ".mcp-tasks"))
  (fs/create-dirs (io/file test-dir ".mcp-tasks/prompts"))
  ;; Create a simple category prompt for testing
  (spit (io/file test-dir ".mcp-tasks/prompts/simple.md")
        "---\ndescription: Simple tasks\n---\nSimple task execution"))

(defn- binary-test-fixture
  "Test fixture that sets up temporary directory and locates binary.
  Skips tests gracefully if binary is not found (e.g., during unit test runs)."
  [f]
  ;; Find binary - check legacy fallback name then platform-specific names
  ;; mcp-tasks-cli is legacy, current builds use mcp-tasks-<platform>-<arch>
  (let [binary-locations [(io/file "target/mcp-tasks-cli")
                          (io/file "target/mcp-tasks-linux-amd64")
                          (io/file "target/mcp-tasks-macos-amd64")
                          (io/file "target/mcp-tasks-macos-arm64")
                          (io/file "target/mcp-tasks-windows-amd64.exe")]
        binary (some #(when (.exists %) %) binary-locations)]
    (if-not binary
      ;; Binary not found - skip test silently (happens during unit/integration test runs)
      (println "Skipping native binary test - binary not found. Build with: clj -T:build native-cli")
      ;; Binary found - run test with proper setup
      (let [test-dir (str (fs/create-temp-dir {:prefix "mcp-tasks-native-binary-"}))]
        (try
          (setup-test-dir test-dir)
          (binding [*test-dir* test-dir
                    *binary-path* (.getAbsolutePath binary)]
            (f))
          (finally
            (fs/delete-tree test-dir)))))))

(use-fixtures :each binary-test-fixture)

(defn- call-binary
  "Call native binary executable capturing stdout, stderr, and exit code.
  Changes working directory to *test-dir* for config discovery.
  Returns {:exit exit-code :out output-string :err error-string}"
  [& args]
  (let [cmd (into [*binary-path*] args)
        result (apply process/shell {:out :string
                                     :err :string
                                     :dir *test-dir*
                                     :continue true}
                      cmd)]
    {:exit (:exit result)
     :out (:out result)
     :err (:err result)}))

;; Smoke Tests
;; These tests are suitable for all platforms (Linux, macOS, Windows)

(deftest ^:native-binary smoke-test-help
  ;; Verify binary runs and shows help
  (testing "smoke-test-help"
    (testing "binary shows help with --help"
      (let [result (call-binary "--help")]
        (is (= 0 (:exit result))
            "Binary should exit successfully with --help")
        (is (str/includes? (:out result) "mcp-tasks")
            "Help should mention mcp-tasks")
        (is (str/includes? (:out result) "list")
            "Help should list available commands")))))

(deftest ^:native-binary smoke-test-basic-workflow
  ;; Test basic add → list → complete workflow
  (testing "smoke-test-basic-workflow"
    (testing "can add a task"
      (let [result (call-binary "--format" "edn"
                                "add"
                                "--category" "simple"
                                "--title" "Smoke test task")]
        (is (= 0 (:exit result))
            "Add command should succeed")
        (is (str/includes? (:out result) ":id 1")
            "Should create task with ID 1")))

    (testing "can list tasks"
      (let [result (call-binary "--format" "edn" "list")]
        (is (= 0 (:exit result))
            "List command should succeed")
        (is (str/includes? (:out result) "Smoke test task")
            "Should show the created task")))

    (testing "can complete task"
      (let [result (call-binary "--format" "edn"
                                "complete"
                                "--task-id" "1")]
        (is (= 0 (:exit result))
            "Complete command should succeed")
        (is (str/includes? (:out result) ":status \"closed\"")
            "Task should be marked as closed")))))

;; Comprehensive Tests
;; These tests are more extensive and intended primarily for Linux CI

(deftest ^:native-binary ^:comprehensive comprehensive-cli-commands
  ;; Test all major CLI commands work correctly
  (testing "comprehensive-cli-commands"

    (testing "add command with all options"
      (let [result (call-binary "--format" "edn"
                                "add"
                                "--category" "simple"
                                "--title" "Comprehensive test"
                                "--description" "Test description"
                                "--type" "feature")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= "Comprehensive test" (-> parsed :task :title)))
          (is (= "feature" (-> parsed :task :type))))))

    (testing "show command"
      (let [result (call-binary "--format" "edn"
                                "show"
                                "--task-id" "1")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= 1 (-> parsed :task :id))))))

    (testing "update command"
      (let [result (call-binary "--format" "edn"
                                "update"
                                "--task-id" "1"
                                "--status" "in-progress")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= "in-progress" (-> parsed :task :status))))))

    (testing "list with filters"
      (let [result (call-binary "--format" "edn"
                                "list"
                                "--status" "in-progress")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= 1 (-> parsed :metadata :open-task-count))))))

    (testing "complete command"
      (let [result (call-binary "--format" "edn"
                                "complete"
                                "--task-id" "1")]
        (is (= 0 (:exit result)))))))

(deftest ^:native-binary ^:comprehensive comprehensive-formats
  ;; Test all output formats work
  (testing "comprehensive-formats"

    (testing "edn format"
      (call-binary "add" "--category" "simple" "--title" "EDN test")
      (let [result (call-binary "--format" "edn" "list")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (map? parsed))
          (is (contains? parsed :tasks))
          (is (contains? parsed :metadata)))))

    (testing "json format"
      (let [result (call-binary "--format" "json" "list")]
        (is (= 0 (:exit result)))
        (let [parsed (json/parse-string (:out result) keyword)]
          (is (map? parsed))
          (is (contains? parsed :tasks))
          (is (contains? parsed :metadata)))))

    (testing "human format"
      (let [result (call-binary "--format" "human" "list")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "ID"))
        (is (str/includes? (:out result) "Status"))))))

(deftest ^:native-binary ^:comprehensive comprehensive-error-handling
  ;; Test error handling works correctly
  (testing "comprehensive-error-handling"

    (testing "invalid command returns error"
      (let [result (call-binary "nonexistent")]
        (is (not= 0 (:exit result)))
        (is (not (str/blank? (:err result))))))

    (testing "missing required args returns error"
      (let [result (call-binary "add" "--category" "simple")]
        (is (not= 0 (:exit result)))
        (is (str/includes? (:err result) "title"))))

    (testing "invalid task id returns error"
      (call-binary "add" "--category" "simple" "--title" "Test")
      (let [result (call-binary "show" "--task-id" "999")]
        (is (not= 0 (:exit result)))
        (is (or (str/includes? (:err result) "not found")
                (str/includes? (:err result) "No task")))))))

(deftest ^:native-binary ^:comprehensive comprehensive-malli-warnings
  ;; Test that Malli warnings are present but don't affect functionality
  (testing "comprehensive-malli-warnings"
    (testing "binary works despite Malli warnings"
      (let [result (call-binary "--format" "edn"
                                "add"
                                "--category" "simple"
                                "--title" "Malli test")]
        ;; May have warnings about missing malli/core
        (is (= 0 (:exit result))
            "Binary should succeed despite Malli warnings")
        ;; Verify task was actually created
        (let [parsed (read-string (:out result))]
          (is (= 1 (-> parsed :task :id)))
          (is (= "Malli test" (-> parsed :task :title))))))))

(deftest ^:native-binary ^:comprehensive comprehensive-startup-performance
  ;; Measure startup time to ensure it's reasonable
  (testing "comprehensive-startup-performance"
    (testing "help command executes quickly"
      (let [start (System/nanoTime)
            result (call-binary "--help")
            elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)]
        (is (= 0 (:exit result)))
        (is (< elapsed-ms 500)
            (str "Help command should complete in under 500ms (took " elapsed-ms "ms)"))))))
