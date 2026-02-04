(ns mcp-tasks.native-binary-integration-test
  "Integration tests for native binary CLI executable.

  Tests that the GraalVM native-image binary behaves identically to the
  JVM CLI implementation. Includes smoke tests suitable for CI and
  comprehensive workflow tests for Linux."
  (:require
    [babashka.fs :as fs]
    [babashka.process :as process]
    [build :as build]
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:dynamic *test-dir* nil)
(def ^:dynamic *binary-path* nil)

(defn- setup-test-dir
  [test-dir]
  (fs/create-dirs (io/file test-dir ".mcp-tasks"))
  (fs/create-dirs (io/file test-dir ".mcp-tasks/category-prompts"))
  ;; Create config file
  (spit (io/file test-dir ".mcp-tasks.edn") "{}")
  ;; Create a simple category prompt for testing
  (spit (io/file test-dir ".mcp-tasks/category-prompts/simple.md")
        "---\ndescription: Simple tasks\n---\nSimple task execution"))

(defn- binary-test-fixture
  "Test fixture that sets up temporary directory and locates binary.
  Skips tests gracefully if binary is not found (e.g., during unit test runs).

  Uses BINARY_TARGET_OS and BINARY_TARGET_ARCH environment variables if available
  (set by CI to test cross-compiled binaries), otherwise detects current platform."
  [f]
  (let [;; Check for env vars first (for CI cross-platform testing)
        target-os (System/getenv "BINARY_TARGET_OS")
        target-arch (System/getenv "BINARY_TARGET_ARCH")
        binary (if (and target-os target-arch)
                 ;; Use env vars to construct binary name
                 (let [platform {:os (keyword target-os)
                                 :arch (keyword target-arch)}
                       binary-name (build/platform-binary-name "mcp-tasks" platform)]
                   (io/file "target" binary-name))
                 ;; Fall back to legacy detection for local testing
                 (let [binary-locations [(io/file "target/mcp-tasks-cli")
                                         (io/file "target/mcp-tasks-linux-amd64")
                                         (io/file "target/mcp-tasks-macos-amd64")
                                         (io/file "target/mcp-tasks-macos-arm64")
                                         (io/file "target/mcp-tasks-macos-universal")
                                         (io/file "target/mcp-tasks-windows-amd64.exe")]]
                   (some #(when (.exists %) %) binary-locations)))]
    (if-not (and binary (.exists binary))
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
        (when (not= 0 (:exit result))
          (println "STDERR:" (:err result))
          (println "STDOUT:" (:out result)))
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
        (when (not= 0 (:exit result))
          (println "STDERR:" (:err result))
          (println "STDOUT:" (:out result)))
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

(deftest ^:native-binary ^:comprehensive comprehensive-prompts-discovery
  ;; Test that workflow and category prompt discovery works in native binary
  ;; This verifies the manifest-based approach for workflow prompts
  (testing "comprehensive-prompts-discovery"

    (testing "prompts list shows workflow prompts from manifest"
      (let [result (call-binary "prompts" "list")]
        (is (= 0 (:exit result))
            (format "prompts list command should succeed. Exit: %s, Error: %s"
                    (:exit result) (:err result)))
        (let [output (:out result)]
          ;; Verify workflow prompts are listed
          (is (str/includes? output "execute-task")
              (format "Should list execute-task workflow. Output: %s" output))
          (is (str/includes? output "refine-task")
              (format "Should list refine-task workflow. Output: %s" output))
          (is (str/includes? output "complete-story")
              (format "Should list complete-story workflow. Output: %s" output))
          (is (str/includes? output "create-story-tasks")
              (format "Should list create-story-tasks workflow. Output: %s" output))
          (is (str/includes? output "execute-story-child")
              (format "Should list execute-story-child workflow. Output: %s" output))
          (is (str/includes? output "review-story-implementation")
              (format "Should list review-story-implementation workflow. Output: %s" output))
          (is (str/includes? output "review-task-implementation")
              (format "Should list review-task-implementation workflow. Output: %s" output))
          (is (str/includes? output "create-story-pr")
              (format "Should list create-story-pr workflow. Output: %s" output))

          ;; Verify category prompts are listed
          (is (str/includes? output "simple")
              (format "Should list simple category. Output: %s" output))
          (is (str/includes? output "medium")
              (format "Should list medium category. Output: %s" output))
          (is (str/includes? output "large")
              (format "Should list large category. Output: %s" output))
          (is (str/includes? output "clarify-task")
              (format "Should list clarify-task category. Output: %s" output)))))

    (testing "prompts show displays workflow prompt content"
      (let [result (call-binary "prompts" "show" "execute-task")]
        (is (= 0 (:exit result))
            (format "prompts show should succeed for workflow prompts. Exit: %s, Error: %s"
                    (:exit result) (:err result)))
        (is (seq (:out result))
            (format "Should return prompt content. Output: %s" (:out result)))))

    (testing "prompts show displays category prompt content"
      (let [result (call-binary "prompts" "show" "simple")]
        (is (= 0 (:exit result))
            (format "prompts show should succeed for category prompts. Exit: %s, Error: %s"
                    (:exit result) (:err result)))
        (is (seq (:out result))
            (format "Should return prompt content. Output: %s" (:out result)))))))

(deftest ^:native-binary ^:comprehensive comprehensive-prompts-install
  ;; Test that prompts install command generates slash commands correctly
  ;; This verifies the manifest-based workflow discovery works end-to-end
  (testing "comprehensive-prompts-install"

    (testing "prompts install generates all slash command files"
      (let [result (call-binary "prompts" "install")]
        (is (= 0 (:exit result))
            (format "prompts install should succeed. Exit: %s, Error: %s"
                    (:exit result) (:err result)))

        ;; Verify .claude/commands directory exists
        (let [commands-dir (io/file *test-dir* ".claude/commands")]
          (is (.exists commands-dir)
              (format ".claude/commands directory should be created. Path: %s, Exists: %s"
                      commands-dir (.exists commands-dir)))
          (is (.isDirectory commands-dir)
              (format ".claude/commands should be a directory. Path: %s, Is directory: %s"
                      commands-dir (.isDirectory commands-dir)))

          ;; Verify workflow slash commands were generated
          (let [workflow-names ["execute-task" "refine-task" "complete-story"
                                "create-story-tasks" "execute-story-child"
                                "review-story-implementation" "review-task-implementation"
                                "create-story-pr"]]
            (doseq [workflow-name workflow-names]
              (let [file-name (str "mcp-tasks-" workflow-name ".md")
                    slash-file (io/file commands-dir file-name)]
                (is (.exists slash-file)
                    (format "Should generate %s. File path: %s, Exists: %s"
                            file-name slash-file (.exists slash-file)))
                (when (.exists slash-file)
                  (let [content (slurp slash-file)]
                    ;; Verify frontmatter exists
                    (is (str/starts-with? content "---")
                        (format "%s should start with frontmatter. First 50 chars: %s"
                                file-name (subs content 0 (min 50 (count content)))))
                    ;; Verify no MCP references
                    (is (not (or (str/includes? content "mcp-tasks show")
                                 (str/includes? content "mcp-tasks list")
                                 (str/includes? content "mcp-tasks complete")
                                 (str/includes? content "mcp-tasks add")
                                 (str/includes? content "mcp-tasks update")))
                        (format "%s should not contain mcp-tasks CLI commands. Content length: %s"
                                file-name (count content)))
                    ;; Verify has content beyond frontmatter
                    (is (> (count content) 50)
                        (format "%s should have substantial content. Actual length: %s"
                                file-name (count content))))))))

          ;; Verify category slash commands were generated
          (let [category-names ["simple" "medium" "large" "clarify-task"]]
            (doseq [category-name category-names]
              (let [file-name (str "mcp-tasks-next-" category-name ".md")
                    slash-file (io/file commands-dir file-name)]
                (is (.exists slash-file)
                    (format "Should generate %s. File path: %s, Exists: %s"
                            file-name slash-file (.exists slash-file)))
                (when (.exists slash-file)
                  (let [content (slurp slash-file)]
                    ;; Verify frontmatter exists
                    (is (str/starts-with? content "---")
                        (format "%s should start with frontmatter. First 50 chars: %s"
                                file-name (subs content 0 (min 50 (count content)))))
                    ;; Verify no MCP references
                    (is (not (or (str/includes? content "mcp-tasks show")
                                 (str/includes? content "mcp-tasks list")
                                 (str/includes? content "mcp-tasks complete")
                                 (str/includes? content "mcp-tasks add")
                                 (str/includes? content "mcp-tasks update")))
                        (format "%s should not contain mcp-tasks CLI commands. Content length: %s"
                                file-name (count content)))
                    ;; Verify has content beyond frontmatter
                    (is (> (count content) 50)
                        (format "%s should have substantial content. Actual length: %s"
                                file-name (count content)))))))))))

    (testing "prompts install reports correct count"
      (let [result (call-binary "prompts" "install")]
        (is (= 0 (:exit result))
            (format "Exit code should be 0. Actual: %s" (:exit result)))
        ;; Should report 13 files generated (8 workflows + 5 categories)
        (is (str/includes? (:out result) "13")
            (format "Should report generating 13 slash command files. Output: %s" (:out result)))))))

(deftest ^:native-binary ^:comprehensive comprehensive-prompts-customize
  ;; Test that prompts customize command copies prompts correctly
  ;; This verifies both category and workflow prompt customization
  (testing "comprehensive-prompts-customize"

    (testing "prompts customize copies category prompt"
      (let [result (call-binary "prompts" "customize" "simple")]
        (is (= 0 (:exit result))
            (format "prompts customize should succeed for category. Exit: %s, Error: %s"
                    (:exit result) (:err result)))

        ;; Verify file was copied to correct location
        (let [custom-file (io/file *test-dir* ".mcp-tasks/category-prompts/simple.md")]
          (is (.exists custom-file)
              (format "Should copy simple.md to category-prompts/. Path: %s, Exists: %s"
                      custom-file (.exists custom-file)))
          (when (.exists custom-file)
            (let [content (slurp custom-file)]
              ;; Verify frontmatter exists
              (is (str/starts-with? content "---")
                  (format "Customized category should have frontmatter. First 50 chars: %s"
                          (subs content 0 (min 50 (count content)))))
              ;; Verify has content
              (is (> (count content) 20)
                  (format "Customized category should have content. Actual length: %s"
                          (count content))))))))

    (testing "prompts customize copies workflow prompt"
      (let [result (call-binary "prompts" "customize" "execute-task")]
        (is (= 0 (:exit result))
            (format "prompts customize should succeed for workflow. Exit: %s, Error: %s"
                    (:exit result) (:err result)))

        ;; Verify file was copied to correct location
        (let [custom-file (io/file *test-dir* ".mcp-tasks/prompt-overrides/execute-task.md")]
          (is (.exists custom-file)
              (format "Should copy execute-task.md to prompt-overrides/. Path: %s, Exists: %s"
                      custom-file (.exists custom-file)))
          (when (.exists custom-file)
            (let [content (slurp custom-file)]
              ;; Verify has content
              (is (> (count content) 50)
                  (format "Customized workflow should have content. Actual length: %s"
                          (count content))))))))

    (testing "prompts customize handles multiple prompts"
      (let [result (call-binary "prompts" "customize" "medium" "refine-task")]
        (is (= 0 (:exit result))
            (format "prompts customize should succeed for multiple prompts. Exit: %s, Error: %s"
                    (:exit result) (:err result)))

        ;; Verify both files were copied
        (let [medium-file (io/file *test-dir* ".mcp-tasks/category-prompts/medium.md")]
          (is (.exists medium-file)
              (format "Should copy medium.md. Path: %s, Exists: %s"
                      medium-file (.exists medium-file))))
        (let [refine-file (io/file *test-dir* ".mcp-tasks/prompt-overrides/refine-task.md")]
          (is (.exists refine-file)
              (format "Should copy refine-task.md. Path: %s, Exists: %s"
                      refine-file (.exists refine-file))))))))
