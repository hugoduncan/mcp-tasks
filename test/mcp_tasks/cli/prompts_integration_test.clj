(ns mcp-tasks.cli.prompts-integration-test
  "Integration tests for CLI prompts commands.

  Tests complete workflows executing prompts commands through the CLI
  and verifying correct output in all formats."
  (:require
    [babashka.fs :as fs]
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-tasks.cli :as cli]
    [mcp-tasks.test-helpers :as h]))

(def ^:dynamic *test-dir* nil)

(defn- setup-test-dir
  [test-dir]
  (fs/create-dirs (io/file test-dir ".mcp-tasks"))
  (fs/create-dirs (io/file test-dir ".mcp-tasks/category-prompts"))
  (fs/create-dirs (io/file test-dir ".mcp-tasks/prompt-overrides"))
  ;; Create config file for config discovery
  (spit (io/file test-dir ".mcp-tasks.edn") "{}"))

(defn- cli-test-fixture
  "CLI-specific test fixture for prompts commands."
  [f]
  (let [test-dir (str (fs/create-temp-dir {:prefix "mcp-tasks-prompts-cli-"}))]
    (try
      (setup-test-dir test-dir)
      (binding [*test-dir* test-dir]
        (h/reset-tasks-state!)
        (f))
      (finally
        (fs/delete-tree test-dir)))))

(use-fixtures :each cli-test-fixture)

(defn- call-cli
  "Call CLI main function capturing stdout and exit code.
  Changes working directory for config discovery.
  
  Parameters:
  - dir (optional): Directory to use as working directory. Defaults to *test-dir*.
  - args: CLI arguments
  
  Returns {:exit exit-code :out output-string :err error-string}"
  [& args]
  (let [[dir cli-args] (if (and (seq args) (instance? java.io.File (first args)))
                         [(str (first args)) (rest args)]
                         [*test-dir* args])
        out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        exit-code (atom nil)
        original-dir (System/getProperty "user.dir")]
    (try
      (System/setProperty "user.dir" dir)
      (binding [*out* out
                *err* err]
        (with-redefs [cli/exit (fn [code] (reset! exit-code code))]
          (apply cli/-main cli-args)))
      (finally
        (System/setProperty "user.dir" original-dir)))
    {:exit @exit-code
     :out (str out)
     :err (str err)}))

(deftest prompts-list-human-format-test
  ;; Test prompts list command with human-readable output
  (testing "prompts-list-human-format"
    (testing "can list prompts with default format"
      (let [result (call-cli "prompts" "list")
            output (:out result)]
        (is (= 0 (:exit result))
            (format "Exit code should be 0. Actual: %s, Error: %s" (:exit result) (:err result)))
        (is (str/includes? output "Available Prompts")
            (format "Should include 'Available Prompts'. Output: %s" output))
        (is (str/includes? output "Category Prompts")
            (format "Should include 'Category Prompts'. Output: %s" output))
        (is (str/includes? output "Workflow Prompts")
            (format "Should include 'Workflow Prompts'. Output: %s" output))
        ;; Check for known category prompts
        (is (str/includes? output "simple")
            (format "Should include 'simple'. Output: %s" output))
        (is (str/includes? output "medium")
            (format "Should include 'medium'. Output: %s" output))
        (is (str/includes? output "large")
            (format "Should include 'large'. Output: %s" output))
        ;; Check for known workflow prompts
        (is (str/includes? output "execute-task")
            (format "Should include 'execute-task'. Output: %s" output))
        (is (str/includes? output "refine-task")
            (format "Should include 'refine-task'. Output: %s" output))))

    (testing "human format explicitly specified"
      (let [result (call-cli "--format" "human" "prompts" "list")]
        (is (= 0 (:exit result))
            (format "Exit code should be 0. Actual: %s, Error: %s" (:exit result) (:err result)))
        (is (str/includes? (:out result) "Available Prompts")
            (format "Should include 'Available Prompts'. Output: %s" (:out result)))))))

(deftest prompts-list-edn-format-test
  ;; Test prompts list with EDN output format
  (testing "prompts-list-edn-format"
    (testing "can list prompts in EDN format"
      (let [result (call-cli "--format" "edn" "prompts" "list")]
        (is (= 0 (:exit result))
            (format "Exit code should be 0. Actual: %s, Error: %s" (:exit result) (:err result)))
        (let [parsed (edn/read-string (:out result))]
          (is (map? parsed)
              (format "Parsed result should be a map. Actual type: %s, Value: %s" (type parsed) parsed))
          (is (contains? parsed :prompts)
              (format "Should contain :prompts key. Actual keys: %s" (keys parsed)))
          (is (contains? parsed :metadata)
              (format "Should contain :metadata key. Actual keys: %s" (keys parsed)))
          (is (vector? (:prompts parsed))
              (format ":prompts should be a vector. Actual type: %s" (type (:prompts parsed))))
          (is (pos? (count (:prompts parsed)))
              (format ":prompts should have positive count. Actual count: %s" (count (:prompts parsed))))

          (testing "metadata contains counts"
            (let [metadata (:metadata parsed)]
              (is (contains? metadata :total-count))
              (is (contains? metadata :category-count))
              (is (contains? metadata :workflow-count))
              (is (= (:total-count metadata)
                     (+ (:category-count metadata)
                        (:workflow-count metadata))))))

          (testing "each prompt has required fields"
            (doseq [prompt (:prompts parsed)]
              (is (contains? prompt :name))
              (is (contains? prompt :type))
              (is (contains? prompt :description))
              (is (string? (:name prompt)))
              (is (keyword? (:type prompt)))
              (is (#{:category :workflow} (:type prompt)))
              (is (string? (:description prompt)))))

          (testing "prompts are grouped by type"
            (let [prompts (:prompts parsed)
                  categories (filter #(= :category (:type %)) prompts)
                  workflows (filter #(= :workflow (:type %)) prompts)]
              (is (pos? (count categories)))
              (is (pos? (count workflows)))
              ;; Verify no duplicates by name
              (is (= (count prompts)
                     (count (distinct (map :name prompts))))))))))

    (testing "EDN format with -f alias in subcommand"
      (let [result (call-cli "prompts" "list" "-f" "edn")]
        (is (= 0 (:exit result)))
        (let [parsed (edn/read-string (:out result))]
          (is (contains? parsed :prompts)))))))

(deftest prompts-list-json-format-test
  ;; Test prompts list with JSON output format
  (testing "prompts-list-json-format"
    (testing "can list prompts in JSON format"
      (let [result (call-cli "--format" "json" "prompts" "list")]
        (is (= 0 (:exit result)))
        (let [parsed (json/parse-string (:out result) keyword)]
          (is (map? parsed))
          (is (contains? parsed :prompts))
          (is (contains? parsed :metadata))
          (is (vector? (:prompts parsed)))
          (is (pos? (count (:prompts parsed))))

          (testing "metadata structure"
            (let [metadata (:metadata parsed)]
              (is (number? (:totalCount metadata)))
              (is (number? (:categoryCount metadata)))
              (is (number? (:workflowCount metadata)))))

          (testing "prompts have correct structure"
            (doseq [prompt (:prompts parsed)]
              (is (string? (:name prompt)))
              (is (string? (:type prompt)))
              (is (string? (:description prompt)))
              (is (contains? #{"category" "workflow"} (:type prompt))))))))

    (testing "JSON format with -f alias in subcommand"
      (let [result (call-cli "prompts" "list" "-f" "json")]
        (is (= 0 (:exit result)))
        (let [parsed (json/parse-string (:out result) keyword)]
          (is (contains? parsed :prompts)))))))

(deftest prompts-list-no-duplicates-test
  ;; Verify no duplicate prompts in list output
  (testing "prompts-list-no-duplicates"
    (testing "EDN format has no duplicates"
      (let [result (call-cli "--format" "edn" "prompts" "list")
            parsed (edn/read-string (:out result))
            prompt-names (map :name (:prompts parsed))]
        (is (= (count prompt-names)
               (count (distinct prompt-names)))
            "Prompt names should be unique")))

    (testing "JSON format has no duplicates"
      (let [result (call-cli "--format" "json" "prompts" "list")
            parsed (json/parse-string (:out result) keyword)
            prompt-names (map :name (:prompts parsed))]
        (is (= (count prompt-names)
               (count (distinct prompt-names)))
            "Prompt names should be unique")))))

(deftest prompts-customize-single-category-test
  ;; Test installing a single category prompt
  (testing "prompts-install-single-category"
    (testing "can install simple category prompt"
      (let [result (call-cli "prompts" "customize" "simple")
            target-file (io/file *test-dir* ".mcp-tasks/category-prompts/simple.md")]
        (is (= 0 (:exit result))
            (format "Exit code should be 0. Actual: %s, Error: %s" (:exit result) (:err result)))
        (is (str/includes? (:out result) "simple")
            (format "Output should include 'simple'. Output: %s" (:out result)))
        (is (str/includes? (:out result) "category")
            (format "Output should include 'category'. Output: %s" (:out result)))
        (is (.exists target-file)
            (format "File should exist. Path: %s, Exists: %s" target-file (.exists target-file)))
        (let [content (slurp target-file)]
          (is (str/includes? content "---")
              (format "Content should include frontmatter. First 100 chars: %s"
                      (subs content 0 (min 100 (count content)))))
          (is (str/includes? content "description:")
              (format "Content should include description. First 200 chars: %s"
                      (subs content 0 (min 200 (count content))))))))

    (testing "human format shows installation status"
      (let [result (call-cli "prompts" "customize" "medium")]
        (is (= 0 (:exit result))
            (format "Exit code should be 0. Actual: %s, Error: %s" (:exit result) (:err result)))
        (is (or (str/includes? (:out result) "✓")
                (str/includes? (:out result) "installed"))
            (format "Output should show success indicator. Output: %s" (:out result)))
        (is (str/includes? (:out result) "category-prompts")
            (format "Output should include 'category-prompts'. Output: %s" (:out result)))))))

(deftest prompts-customize-single-workflow-test
  ;; Test installing a single workflow prompt
  (testing "prompts-install-single-workflow"
    (testing "can install execute-task workflow prompt"
      (let [result (call-cli "prompts" "customize" "execute-task")
            target-file (io/file *test-dir* ".mcp-tasks/prompt-overrides/execute-task.md")]
        (is (= 0 (:exit result))
            (format "Exit code should be 0. Actual: %s, Error: %s" (:exit result) (:err result)))
        (is (str/includes? (:out result) "execute-task")
            (format "Output should include 'execute-task'. Output: %s" (:out result)))
        (is (str/includes? (:out result) "workflow")
            (format "Output should include 'workflow'. Output: %s" (:out result)))
        (is (.exists target-file)
            (format "File should exist. Path: %s, Exists: %s" target-file (.exists target-file)))
        (let [content (slurp target-file)]
          (is (str/includes? content "---")
              (format "Content should include frontmatter. First 100 chars: %s"
                      (subs content 0 (min 100 (count content)))))
          (is (str/includes? content "description:")
              (format "Content should include description. First 200 chars: %s"
                      (subs content 0 (min 200 (count content))))))))

    (testing "human format shows installation path"
      (let [result (call-cli "prompts" "customize" "refine-task")]
        (is (= 0 (:exit result))
            (format "Exit code should be 0. Actual: %s, Error: %s" (:exit result) (:err result)))
        (is (str/includes? (:out result) "prompt-overrides")
            (format "Output should include 'prompt-overrides'. Output: %s" (:out result)))))))

(deftest prompts-customize-multiple-test
  ;; Test installing multiple prompts at once
  (testing "prompts-install-multiple"
    (testing "can install multiple prompts"
      (let [result (call-cli "prompts" "customize" "simple" "medium" "execute-task")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "simple"))
        (is (str/includes? (:out result) "medium"))
        (is (str/includes? (:out result) "execute-task"))
        ;; Verify files exist
        (is (.exists (io/file *test-dir* ".mcp-tasks/category-prompts/simple.md")))
        (is (.exists (io/file *test-dir* ".mcp-tasks/category-prompts/medium.md")))
        (is (.exists (io/file *test-dir* ".mcp-tasks/prompt-overrides/execute-task.md")))))

    (testing "human format shows summary"
      (let [result (call-cli "prompts" "customize" "simple" "large")]
        (is (= 0 (:exit result)))
        (is (or (str/includes? (:out result) "Summary")
                (str/includes? (:out result) "installed")))))))

(deftest prompts-customize-edn-format-test
  ;; Test prompts customize with EDN output
  (testing "prompts-customize-edn-format"
    (testing "can install with EDN output"
      (let [result (call-cli "--format" "edn" "prompts" "customize" "simple")]
        (is (= 0 (:exit result)))
        (let [parsed (edn/read-string (:out result))]
          (is (map? parsed))
          (is (contains? parsed :results))
          (is (contains? parsed :metadata))
          (is (vector? (:results parsed)))
          (is (= 1 (count (:results parsed))))

          (testing "result has correct structure"
            (let [res (first (:results parsed))]
              (is (= "simple" (:name res)))
              (is (= :category (:type res)))
              (is (#{:installed :exists} (:status res)))
              (is (string? (:path res)))
              (is (str/includes? (:path res) "category-prompts"))))

          (testing "metadata has counts"
            (let [metadata (:metadata parsed)
                  status (:status (first (:results parsed)))]
              (is (= 1 (:requested-count metadata)))
              ;; If status is :installed, installed-count=1, failed-count=0
              ;; If status is :exists, installed-count=0, failed-count=1
              (if (= :installed status)
                (do
                  (is (= 1 (:installed-count metadata)))
                  (is (= 0 (:failed-count metadata))))
                (do
                  (is (= 0 (:installed-count metadata)))
                  (is (= 1 (:failed-count metadata))))))))))

    (testing "multiple installs with EDN"
      (let [result (call-cli "prompts" "customize" "medium" "execute-task" "-f" "edn")
            parsed (edn/read-string (:out result))]
        (is (= 2 (count (:results parsed))))
        (is (= 2 (get-in parsed [:metadata :requested-count])))
        ;; Count successful ones (either :installed or :exists)
        (let [successful (count (filter #(#{:installed :exists} (:status %)) (:results parsed)))]
          (is (= 2 successful)))))))

(deftest prompts-customize-json-format-test
  ;; Test prompts customize with JSON output
  (testing "prompts-customize-json-format"
    (testing "can install with JSON output"
      (let [result (call-cli "--format" "json" "prompts" "customize" "simple")]
        (is (= 0 (:exit result)))
        (let [parsed (json/parse-string (:out result) keyword)]
          (is (contains? parsed :results))
          (is (contains? parsed :metadata))
          (is (= 1 (count (:results parsed))))

          (testing "result structure"
            (let [res (first (:results parsed))]
              (is (= "simple" (:name res)))
              (is (= "category" (:type res)))
              (is (contains? #{"installed" "exists"} (:status res)))
              (is (string? (:path res)))))

          (testing "metadata structure"
            (let [metadata (:metadata parsed)]
              (is (= 1 (:requestedCount metadata)))
              ;; Metadata counts depend on whether file existed
              (is (number? (:installedCount metadata)))
              (is (number? (:failedCount metadata))))))))

    (testing "multiple installs with JSON"
      (let [result (call-cli "prompts" "customize" "large" "refine-task" "-f" "json")
            parsed (json/parse-string (:out result) keyword)]
        (is (= 2 (count (:results parsed))))
        (is (= 2 (-> parsed :metadata :requestedCount)))
        ;; Verify all have valid statuses
        (is (every? #(contains? #{"installed" "exists"} (:status %)) (:results parsed)))))))

(deftest prompts-customize-error-invalid-name-test
  ;; Test error handling for invalid prompt names
  (testing "prompts-install-error-invalid-name"
    (testing "installing non-existent prompt shows error"
      (let [result (call-cli "prompts" "customize" "nonexistent")]
        (is (= 0 (:exit result)))
        (is (or (str/includes? (:out result) "✗")
                (str/includes? (:out result) "not found")
                (str/includes? (:out result) "failed")))))

    (testing "EDN format shows not-found status"
      (let [result (call-cli "--format" "edn" "prompts" "customize" "invalid-prompt")
            parsed (edn/read-string (:out result))]
        (is (= 1 (count (:results parsed))))
        (is (= :not-found (get-in parsed [:results 0 :status])))
        (is (= 0 (get-in parsed [:metadata :installed-count])))
        (is (= 1 (get-in parsed [:metadata :failed-count])))))

    (testing "JSON format shows not-found status"
      (let [result (call-cli "prompts" "customize" "bad-name" "-f" "json")
            parsed (json/parse-string (:out result) keyword)]
        (is (= "not-found" (get-in parsed [:results 0 :status])))
        ;; not-found counts as failed
        (is (number? (-> parsed :metadata :installedCount)))
        (is (number? (-> parsed :metadata :failedCount)))))))

(deftest prompts-customize-mixed-valid-invalid-test
  ;; Test installing mix of valid and invalid prompts
  (testing "prompts-install-mixed-valid-invalid"
    (testing "processes all prompts and reports status"
      (let [result (call-cli "prompts" "customize" "simple" "invalid" "medium")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "simple"))
        (is (str/includes? (:out result) "invalid"))
        (is (str/includes? (:out result) "medium"))
        ;; Valid ones should show success
        (is (or (str/includes? (:out result) "✓")
                (str/includes? (:out result) "installed")))
        ;; Invalid one should show failure
        (is (or (str/includes? (:out result) "✗")
                (str/includes? (:out result) "failed")))))

    (testing "EDN format shows mixed results"
      (let [result (call-cli "prompts" "customize" "large" "bad" "execute-task" "-f" "edn")
            parsed (edn/read-string (:out result))]
        (is (= 3 (count (:results parsed))))
        (is (= 3 (get-in parsed [:metadata :requested-count])))
        ;; Check individual statuses - should have success statuses and not-found
        (let [statuses (map :status (:results parsed))
              successful (count (filter #(#{:installed :exists} %) statuses))
              not-found (count (filter #(= :not-found %) statuses))]
          (is (= 2 successful) "Should have 2 successful installs")
          (is (= 1 not-found) "Should have 1 not-found"))))))

(deftest prompts-customize-skip-existing-test
  ;; Test that installing skips existing prompts without overwriting
  (testing "prompts-install-skip-existing"
    (testing "preserves modified files on re-install"
      (let [result (call-cli "prompts" "customize" "simple")
            target-file (io/file *test-dir* ".mcp-tasks/category-prompts/simple.md")]
        (is (= 0 (:exit result)))
        (is (.exists target-file))
        ;; Modify the file
        (spit target-file "Modified content")
        (is (= "Modified content" (slurp target-file)))

        ;; Install again
        (let [result2 (call-cli "prompts" "customize" "simple")]
          (is (= 0 (:exit result2)))
          ;; Should NOT be overwritten - modified content preserved
          (is (= "Modified content" (slurp target-file)))
          ;; Should indicate file already exists
          (is (str/includes? (:out result2) "already exists")))))))

(deftest prompts-help-test
  ;; Test help output for prompts commands
  (testing "prompts-help"
    (testing "prompts command has help"
      (let [result (call-cli "prompts" "--help")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "prompts"))
        (is (str/includes? (:out result) "list"))
        (is (str/includes? (:out result) "customize"))))

    (testing "prompts list has help"
      (let [result (call-cli "prompts" "list" "--help")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "list"))
        (is (str/includes? (:out result) "format"))))

    (testing "prompts install has help"
      (let [result (call-cli "prompts" "customize" "--help")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "customize"))
        (is (str/includes? (:out result) "format"))))))

(deftest prompts-list-installed-matches-discovered-test
  ;; Verify that installed prompts match what's discovered
  (testing "prompts-list-installed-matches-discovered"
    (testing "install all category prompts and verify"
      (let [list-result (call-cli "prompts" "list" "-f" "edn")
            parsed-list (edn/read-string (:out list-result))
            category-prompts (filter #(= :category (:type %)) (:prompts parsed-list))
            category-names (map :name category-prompts)
            ;; Install all category prompts
            install-result (apply call-cli "prompts" "customize" (concat category-names ["-f" "edn"]))
            parsed-install (edn/read-string (:out install-result))]
        ;; All should have successful status
        (is (every? #(#{:installed :exists} (:status %)) (:results parsed-install)))
        ;; Verify files exist
        (doseq [name category-names]
          (is (.exists (io/file *test-dir* ".mcp-tasks/category-prompts" (str name ".md")))))))

    (testing "install all workflow prompts and verify"
      (let [list-result (call-cli "prompts" "list" "-f" "edn")
            parsed-list (edn/read-string (:out list-result))
            workflow-prompts (filter #(= :workflow (:type %)) (:prompts parsed-list))
            workflow-names (map :name workflow-prompts)
            install-result (apply call-cli "prompts" "customize" (concat workflow-names ["-f" "edn"]))
            parsed-install (edn/read-string (:out install-result))]
        ;; All should have successful status
        (is (every? #(#{:installed :exists} (:status %)) (:results parsed-install)))
        ;; Verify files exist
        (doseq [name workflow-names]
          (is (.exists (io/file *test-dir* ".mcp-tasks/prompt-overrides" (str name ".md")))))))))

(deftest prompts-type-detection-test
  ;; Verify correct type detection for category vs workflow prompts
  (testing "prompts-type-detection"
    (testing "all category prompts have :category type"
      (let [result (call-cli "prompts" "list" "-f" "edn")
            parsed (edn/read-string (:out result))
            categories (filter #(= :category (:type %)) (:prompts parsed))]
        ;; Known category prompts
        (is (some #(= "simple" (:name %)) categories))
        (is (some #(= "medium" (:name %)) categories))
        (is (some #(= "large" (:name %)) categories))))

    (testing "all workflow prompts have :workflow type"
      (let [result (call-cli "prompts" "list" "-f" "edn")
            parsed (edn/read-string (:out result))
            workflows (filter #(= :workflow (:type %)) (:prompts parsed))]
        ;; Known workflow prompts
        (is (some #(= "execute-task" (:name %)) workflows))
        (is (some #(= "refine-task" (:name %)) workflows))
        (is (some #(= "create-story-tasks" (:name %)) workflows))))

    (testing "installation respects type for directory selection"
      (call-cli "prompts" "customize" "simple" "execute-task")
      ;; Category prompt goes to category-prompts/
      (is (.exists (io/file *test-dir* ".mcp-tasks/category-prompts/simple.md")))
      (is (not (.exists (io/file *test-dir* ".mcp-tasks/prompt-overrides/simple.md"))))
      ;; Workflow prompt goes to prompt-overrides/
      (is (.exists (io/file *test-dir* ".mcp-tasks/prompt-overrides/execute-task.md")))
      (is (not (.exists (io/file *test-dir* ".mcp-tasks/category-prompts/execute-task.md")))))))

(deftest prompts-customize-from-subdirectory-test
  ;; Test that prompts install works correctly when run from a subdirectory
  (testing "prompts-install-from-subdirectory"
    (testing "installs to parent .mcp-tasks when run from subdirectory"
      (let [subdir (io/file *test-dir* "src")]
        (fs/create-dirs subdir)
        (let [result (call-cli subdir "prompts" "customize" "simple")
              target-file (io/file *test-dir* ".mcp-tasks/category-prompts/simple.md")]
          (is (= 0 (:exit result)))
          (is (.exists target-file))
          (is (str/includes? (slurp target-file) "---"))
          (is (str/includes? (slurp target-file) "description:")))))

    (testing "works from nested subdirectory"
      (let [nested-dir (io/file *test-dir* "src/test/integration")]
        (fs/create-dirs nested-dir)
        (let [result (call-cli nested-dir "prompts" "customize" "medium")
              target-file (io/file *test-dir* ".mcp-tasks/category-prompts/medium.md")]
          (is (= 0 (:exit result)))
          (is (.exists target-file)))))))

(deftest prompts-customize-without-config-test
  ;; Test backward compatibility: prompts install works when no .mcp-tasks.edn exists
  (testing "prompts-install-without-config"
    (testing "falls back to .mcp-tasks in current directory when no config"
      (let [test-dir (str (fs/create-temp-dir {:prefix "mcp-tasks-no-config-"}))
            tasks-dir (io/file test-dir ".mcp-tasks")
            category-dir (io/file tasks-dir "category-prompts")]
        (try
          ;; Create .mcp-tasks directories but NO config file
          (fs/create-dirs category-dir)
          ;; Do NOT create .mcp-tasks.edn

          (let [result (call-cli (io/file test-dir) "prompts" "customize" "simple")
                target-file (io/file category-dir "simple.md")]
            (is (= 0 (:exit result)))
            (is (.exists target-file))
            (is (str/includes? (slurp target-file) "---"))
            (is (str/includes? (slurp target-file) "description:")))
          (finally
            (fs/delete-tree test-dir)))))))

(deftest prompts-customize-custom-absolute-tasks-dir-test
  ;; Test that prompts install respects custom absolute :tasks-dir in config
  (testing "prompts-install-custom-absolute-tasks-dir"
    (testing "installs to absolute custom tasks-dir when configured"
      (let [test-dir (str (fs/create-temp-dir {:prefix "mcp-tasks-abs-dir-"}))
            custom-tasks-dir (str (fs/create-temp-dir {:prefix "custom-tasks-"}))
            category-dir (io/file custom-tasks-dir "category-prompts")]
        (try
          ;; Create config with absolute custom tasks-dir
          (fs/create-dirs category-dir)
          (spit (io/file test-dir ".mcp-tasks.edn")
                (pr-str {:tasks-dir custom-tasks-dir}))

          (let [result (call-cli (io/file test-dir) "prompts" "customize" "simple")
                target-file (io/file category-dir "simple.md")]
            (is (= 0 (:exit result)))
            (is (.exists target-file))
            (is (str/includes? (slurp target-file) "---"))
            (is (str/includes? (slurp target-file) "description:")))
          (finally
            (fs/delete-tree test-dir)
            (fs/delete-tree custom-tasks-dir)))))))

(deftest prompts-customize-custom-relative-tasks-dir-test
  ;; Test that prompts install respects custom relative :tasks-dir in config
  (testing "prompts-install-custom-relative-tasks-dir"
    (testing "installs to relative custom tasks-dir when configured"
      (let [test-dir (str (fs/create-temp-dir {:prefix "mcp-tasks-rel-dir-"}))
            custom-dir-name "custom-tasks"
            custom-tasks-dir (io/file test-dir custom-dir-name)
            category-dir (io/file custom-tasks-dir "category-prompts")]
        (try
          ;; Create config with relative custom tasks-dir
          (fs/create-dirs category-dir)
          (spit (io/file test-dir ".mcp-tasks.edn")
                (pr-str {:tasks-dir custom-dir-name}))

          (let [result (call-cli (io/file test-dir) "prompts" "customize" "medium")
                target-file (io/file category-dir "medium.md")]
            (is (= 0 (:exit result)))
            (is (.exists target-file))
            (is (str/includes? (slurp target-file) "---"))
            (is (str/includes? (slurp target-file) "description:")))
          (finally
            (fs/delete-tree test-dir)))))))

(deftest prompts-customize-from-worktree-test
  ;; Test that prompts install works from a git worktree
  (testing "prompts-install-from-worktree"
    (testing "installs to main repo when run from worktree"
      (let [project-dir (str (fs/create-temp-dir {:prefix "project-"}))
            worktree-name "test-worktree"
            worktree-path (str project-dir "/" worktree-name)
            mcp-tasks-dir (io/file project-dir ".mcp-tasks")
            category-dir (io/file mcp-tasks-dir "category-prompts")]
        (try
          ;; Set up project directory with config and .mcp-tasks
          (fs/create-dirs category-dir)
          (spit (io/file project-dir ".mcp-tasks.edn") "{}")

          ;; Create worktree (git creates the directory under project-dir)
          (h/create-git-worktree project-dir worktree-path)

          ;; Install prompt from worktree
          (let [result (call-cli (io/file worktree-path) "prompts" "customize" "simple")
                target-file (io/file category-dir "simple.md")]
            (is (= 0 (:exit result)))
            (is (.exists target-file))
            (is (str/includes? (slurp target-file) "---"))
            (is (str/includes? (slurp target-file) "description:")))
          (finally
            (fs/delete-tree project-dir)))))

    (testing "works from nested subdirectory within worktree"
      (let [project-dir (str (fs/create-temp-dir {:prefix "project-"}))
            worktree-name "test-worktree-nested"
            worktree-path (str project-dir "/" worktree-name)
            mcp-tasks-dir (io/file project-dir ".mcp-tasks")
            category-dir (io/file mcp-tasks-dir "category-prompts")
            nested-dir (io/file worktree-path "src/test")]
        (try
          ;; Set up project directory with config and .mcp-tasks
          (fs/create-dirs category-dir)
          (spit (io/file project-dir ".mcp-tasks.edn") "{}")

          ;; Create worktree and nested directory
          (h/create-git-worktree project-dir worktree-path)
          (fs/create-dirs nested-dir)

          ;; Install prompt from nested directory in worktree
          (let [result (call-cli nested-dir "prompts" "customize" "medium")
                target-file (io/file category-dir "medium.md")]
            (is (= 0 (:exit result)))
            (is (.exists target-file)))
          (finally
            (fs/delete-tree project-dir)))))))

(deftest prompts-show-builtin-category-test
  ;; Test showing builtin category prompt with metadata verification
  (testing "prompts-show-builtin-category"
    (testing "can show builtin category prompt with default format"
      (let [result (call-cli "prompts" "show" "simple")
            output (:out result)]
        (is (= 0 (:exit result))
            (format "Exit code should be 0. Actual: %s, Error: %s" (:exit result) (:err result)))
        (is (str/includes? output "Source: builtin")
            (format "Should include 'Source: builtin'. Output: %s" output))
        (is (str/includes? output "Type: category")
            (format "Should include 'Type: category'. Output: %s" output))
        (is (str/includes? output "Prompt: simple")
            (format "Should include 'Prompt: simple'. Output: %s" output))
        (is (str/includes? output "---")
            (format "Should include frontmatter delimiter. Output: %s" output))
        (is (str/includes? output "Description: Execute simple tasks with basic workflow")
            (format "Should include description. Output: %s" output))))

    (testing "verifies metadata values in human format"
      (let [result (call-cli "prompts" "show" "medium")
            output (:out result)]
        (is (= 0 (:exit result))
            (format "Exit code should be 0. Actual: %s, Error: %s" (:exit result) (:err result)))
        (is (str/includes? output "Description:")
            (format "Should include 'Description:'. Output: %s" output))
        (is (str/includes? output "Execute medium complexity tasks")
            (format "Should include description text. Output: %s" output))))))

(deftest prompts-show-builtin-workflow-test
  ;; Test showing builtin workflow prompt with metadata verification
  (testing "prompts-show-builtin-workflow"
    (testing "can show builtin workflow prompt"
      (let [result (call-cli "prompts" "show" "execute-task")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "Source: builtin"))
        (is (str/includes? (:out result) "Type: workflow"))
        (is (str/includes? (:out result) "Prompt: execute-task"))
        (is (str/includes? (:out result) "---"))))

    (testing "verifies metadata values including multiple fields"
      (let [result (call-cli "prompts" "show" "execute-task")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "Description: Execute a task based on selection criteria or context"))
        (is (str/includes? (:out result) "Argument hint: [selection-criteria...]"))))))

(deftest prompts-show-override-category-test
  ;; Test showing overridden category prompt
  (testing "prompts-show-override-category"
    (testing "shows override when category prompt is customized"
      (let [override-content "---\ndescription: Custom simple workflow\n---\n\nCustom simple steps"
            override-file (io/file *test-dir* ".mcp-tasks/category-prompts/simple.md")]
        (fs/create-dirs (.getParentFile override-file))
        (spit override-file override-content)
        (let [result (call-cli "prompts" "show" "simple")]
          (is (= 0 (:exit result)))
          (is (str/includes? (:out result) "Source: override"))
          (is (str/includes? (:out result) "Type: category"))
          (is (str/includes? (:out result) "Custom simple steps")))))))

(deftest prompts-show-override-workflow-test
  ;; Test showing overridden workflow prompt
  (testing "prompts-show-override-workflow"
    (testing "shows override when workflow prompt is customized"
      (let [override-content "---\ndescription: Custom task execution\n---\n\nCustom task steps"
            override-file (io/file *test-dir* ".mcp-tasks/prompt-overrides/execute-task.md")]
        (fs/create-dirs (.getParentFile override-file))
        (spit override-file override-content)
        (let [result (call-cli "prompts" "show" "execute-task")]
          (is (= 0 (:exit result)))
          (is (str/includes? (:out result) "Source: override"))
          (is (str/includes? (:out result) "Type: workflow"))
          (is (str/includes? (:out result) "Custom task steps")))))))

(deftest prompts-show-edn-format-test
  ;; Test prompts show with EDN output and metadata verification
  (testing "prompts-show-edn-format"
    (testing "can show prompt in EDN format"
      (let [result (call-cli "--format" "edn" "prompts" "show" "simple")]
        (is (= 0 (:exit result)))
        (let [parsed (edn/read-string (:out result))]
          (is (map? parsed))
          (is (= "simple" (:name parsed)))
          (is (= :category (:type parsed)))
          (is (= :builtin (:source parsed)))
          (is (string? (:content parsed)))
          (is (string? (:path parsed))))))

    (testing "EDN format with -f alias"
      (let [result (call-cli "prompts" "show" "execute-task" "-f" "edn")
            parsed (edn/read-string (:out result))]
        (is (= "execute-task" (:name parsed)))
        (is (= :workflow (:type parsed)))))

    (testing "EDN format includes parsed metadata"
      (let [result (call-cli "prompts" "show" "simple" "-f" "edn")
            parsed (edn/read-string (:out result))]
        (is (contains? parsed :metadata))
        (is (map? (:metadata parsed)))
        (is (= "Execute simple tasks with basic workflow"
               (get (:metadata parsed) "description")))))

    (testing "EDN format includes metadata with multiple fields"
      (let [result (call-cli "prompts" "show" "execute-task" "-f" "edn")
            parsed (edn/read-string (:out result))]
        (is (contains? parsed :metadata))
        (is (= "Execute a task based on selection criteria or context"
               (get (:metadata parsed) "description")))
        (is (= "[selection-criteria...]"
               (get (:metadata parsed) "argument-hint")))))))

(deftest prompts-show-json-format-test
  ;; Test prompts show with JSON output and metadata verification
  (testing "prompts-show-json-format"
    (testing "can show prompt in JSON format"
      (let [result (call-cli "--format" "json" "prompts" "show" "medium")]
        (is (= 0 (:exit result)))
        (let [parsed (json/parse-string (:out result) keyword)]
          (is (map? parsed))
          (is (= "medium" (:name parsed)))
          (is (= "category" (:type parsed)))
          (is (= "builtin" (:source parsed)))
          (is (string? (:content parsed)))
          (is (string? (:path parsed))))))

    (testing "JSON format with -f alias"
      (let [result (call-cli "prompts" "show" "refine-task" "-f" "json")
            parsed (json/parse-string (:out result) keyword)]
        (is (= "refine-task" (:name parsed)))
        (is (= "workflow" (:type parsed)))))

    (testing "JSON format includes parsed metadata"
      (let [result (call-cli "prompts" "show" "simple" "-f" "json")
            parsed (json/parse-string (:out result) keyword)]
        (is (contains? parsed :metadata))
        (is (map? (:metadata parsed)))
        (is (= "Execute simple tasks with basic workflow"
               (:description (:metadata parsed))))))

    (testing "JSON format includes metadata with multiple fields"
      (let [result (call-cli "prompts" "show" "execute-task" "-f" "json")
            parsed (json/parse-string (:out result) keyword)]
        (is (contains? parsed :metadata))
        (is (= "Execute a task based on selection criteria or context"
               (:description (:metadata parsed))))
        (is (= "[selection-criteria...]"
               (:argumentHint (:metadata parsed))))))))

(deftest prompts-show-nonexistent-test
  ;; Test error handling for nonexistent prompts
  (testing "prompts-show-nonexistent"
    (testing "shows error for nonexistent prompt"
      (let [result (call-cli "prompts" "show" "nonexistent")]
        (is (= 1 (:exit result)))
        (is (str/includes? (:err result) "not found"))))

    (testing "EDN format shows error"
      (let [result (call-cli "prompts" "show" "invalid" "-f" "edn")]
        (is (= 1 (:exit result)))
        (let [parsed (edn/read-string (:err result))]
          (is (contains? parsed :error)))))

    (testing "JSON format shows error"
      (let [result (call-cli "prompts" "show" "bad-name" "-f" "json")]
        (is (= 1 (:exit result)))
        (let [parsed (json/parse-string (:err result) keyword)]
          (is (contains? parsed :error)))))))

(deftest prompts-show-from-subdirectory-test
  ;; Test prompts show works from subdirectories
  (testing "prompts-show-from-subdirectory"
    (testing "shows prompts when run from subdirectory"
      (let [subdir (io/file *test-dir* "src")]
        (fs/create-dirs subdir)
        (let [result (call-cli subdir "prompts" "show" "simple")]
          (is (= 0 (:exit result)))
          (is (str/includes? (:out result) "simple")))))

    (testing "shows overridden prompts from subdirectory"
      (let [override-file (io/file *test-dir* ".mcp-tasks/category-prompts/large.md")
            subdir (io/file *test-dir* "test/integration")]
        (fs/create-dirs (.getParentFile override-file))
        (fs/create-dirs subdir)
        (spit override-file "---\ndescription: Custom\n---\n\nCustom content")
        (let [result (call-cli subdir "prompts" "show" "large")]
          (is (= 0 (:exit result)))
          (is (str/includes? (:out result) "Source: override"))
          (is (str/includes? (:out result) "Custom content")))))))

(deftest prompts-show-from-worktree-test
  ;; Test prompts show works from git worktrees
  (testing "prompts-show-from-worktree"
    (testing "shows prompts when run from worktree"
      (let [project-dir (str (fs/create-temp-dir {:prefix "project-"}))
            worktree-name "show-worktree"
            worktree-path (str project-dir "/" worktree-name)]
        (try
          ;; Set up project directory
          (fs/create-dirs (io/file project-dir ".mcp-tasks"))
          (spit (io/file project-dir ".mcp-tasks.edn") "{}")

          ;; Create worktree
          (h/create-git-worktree project-dir worktree-path)

          ;; Show prompt from worktree
          (let [result (call-cli (io/file worktree-path) "prompts" "show" "simple")]
            (is (= 0 (:exit result)))
            (is (str/includes? (:out result) "simple")))
          (finally
            (fs/delete-tree project-dir)))))

    (testing "shows overridden prompts from worktree"
      (let [project-dir (str (fs/create-temp-dir {:prefix "project-"}))
            worktree-name "show-override-worktree"
            worktree-path (str project-dir "/" worktree-name)
            override-file (io/file project-dir ".mcp-tasks/prompt-overrides/refine-task.md")]
        (try
          ;; Set up project with override
          (fs/create-dirs (.getParentFile override-file))
          (spit override-file "---\ndescription: Custom refine\n---\n\nCustom refine steps")
          (spit (io/file project-dir ".mcp-tasks.edn") "{}")

          ;; Create worktree
          (h/create-git-worktree project-dir worktree-path)

          ;; Show prompt from worktree
          (let [result (call-cli (io/file worktree-path) "prompts" "show" "refine-task")]
            (is (= 0 (:exit result)))
            (is (str/includes? (:out result) "Source: override"))
            (is (str/includes? (:out result) "Custom refine steps")))
          (finally
            (fs/delete-tree project-dir)))))

    (testing "shows prompts from nested directory in worktree"
      (let [project-dir (str (fs/create-temp-dir {:prefix "project-"}))
            worktree-name "show-nested-worktree"
            worktree-path (str project-dir "/" worktree-name)
            nested-dir (io/file worktree-path "src/test")]
        (try
          ;; Set up project directory
          (fs/create-dirs (io/file project-dir ".mcp-tasks"))
          (spit (io/file project-dir ".mcp-tasks.edn") "{}")

          ;; Create worktree and nested directory
          (h/create-git-worktree project-dir worktree-path)
          (fs/create-dirs nested-dir)

          ;; Show prompt from nested directory
          (let [result (call-cli nested-dir "prompts" "show" "medium")]
            (is (= 0 (:exit result)))
            (is (str/includes? (:out result) "medium")))
          (finally
            (fs/delete-tree project-dir)))))

    (testing "auto-detects prompt type in worktree environment"
      (let [project-dir (str (fs/create-temp-dir {:prefix "project-"}))
            worktree-name "type-detect-worktree"
            worktree-path (str project-dir "/" worktree-name)]
        (try
          ;; Set up project directory
          (fs/create-dirs (io/file project-dir ".mcp-tasks"))
          (spit (io/file project-dir ".mcp-tasks.edn") "{}")

          ;; Create worktree
          (h/create-git-worktree project-dir worktree-path)

          ;; Show category prompt
          (let [result1 (call-cli (io/file worktree-path) "prompts" "show" "large")]
            (is (= 0 (:exit result1)))
            (is (str/includes? (:out result1) "Type: category")))

          ;; Show workflow prompt
          (let [result2 (call-cli (io/file worktree-path) "prompts" "show" "create-story-tasks")]
            (is (= 0 (:exit result2)))
            (is (str/includes? (:out result2) "Type: workflow")))
          (finally
            (fs/delete-tree project-dir)))))))

(deftest prompts-show-help-test
  ;; Test help output for prompts show command
  (testing "prompts-show-help"
    (testing "prompts show has help"
      (let [result (call-cli "prompts" "show" "--help")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "show"))
        (is (str/includes? (:out result) "prompt-name"))
        (is (str/includes? (:out result) "format"))))))

(deftest prompts-install-generates-slash-commands-test
  ;; Test that prompts install generates Claude Code slash command files.
  ;; Contracts: Generates files for all prompts, uses correct naming convention,
  ;; renders with cli=true context, and returns proper metadata.
  (testing "prompts install generates slash commands"
    (testing "generates files with explicit target directory"
      (let [;; Use explicit path since user.dir change doesn't affect relative path resolution
            target-dir (io/file *test-dir* ".claude/commands")
            result (call-cli "prompts" "install" (str target-dir))]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "Installing prompts"))
        (is (str/includes? (:out result) "generated"))
        (is (fs/exists? target-dir) "Should create target directory")
        (let [files (vec (fs/list-dir target-dir))]
          (is (pos? (count files)) "Should generate at least one file")
          (doseq [f files]
            (is (str/starts-with? (fs/file-name f) "mcp-tasks-"))
            (is (str/ends-with? (str f) ".md"))))))

    (testing "generates files in custom directory"
      (let [custom-dir (io/file *test-dir* "custom-commands")
            result (call-cli "prompts" "install" (str custom-dir))]
        (is (= 0 (:exit result)))
        (is (fs/exists? custom-dir))
        (let [files (vec (fs/list-dir custom-dir))]
          (is (pos? (count files))))))))

(deftest prompts-install-human-format-test
  ;; Test human-readable output format for prompts install
  (testing "prompts install human format"
    (testing "shows success indicators and paths"
      (let [result (call-cli "prompts" "install")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "✓"))
        (is (str/includes? (:out result) "mcp-tasks-"))
        (is (str/includes? (:out result) ".md"))
        (is (str/includes? (:out result) "Summary"))))

    (testing "shows skipped infrastructure files"
      (let [result (call-cli "prompts" "install")]
        (is (str/includes? (:out result) "skipped"))))))

(deftest prompts-install-edn-format-test
  ;; Test EDN output format for prompts install
  (testing "prompts install EDN format"
    (testing "returns structured EDN data"
      (let [target-dir (io/file *test-dir* "edn-test-commands")
            result (call-cli "prompts" "install" (str target-dir) "--format" "edn")
            parsed (edn/read-string (:out result))]
        (is (= 0 (:exit result)))
        (is (map? parsed))
        (is (contains? parsed :results))
        (is (contains? parsed :metadata))
        (is (vector? (:results parsed)))

        (testing "metadata has correct counts"
          (let [metadata (:metadata parsed)
                generated (filter #(= :generated (:status %)) (:results parsed))]
            (is (= (:generated-count metadata) (count generated)))
            (is (number? (:skipped-count metadata)))
            (is (number? (:failed-count metadata)))
            (is (number? (:overwritten-count metadata)))
            (is (= (str target-dir) (:target-dir metadata)))))

        (testing "generated results have correct structure"
          (doseq [res (filter #(= :generated (:status %)) (:results parsed))]
            (is (string? (:name res)))
            (is (#{:category :workflow} (:type res)))
            (is (string? (:path res)))
            (is (boolean? (:overwritten res)))))))))

(deftest prompts-install-json-format-test
  ;; Test JSON output format for prompts install
  (testing "prompts install JSON format"
    (testing "returns valid JSON data"
      (let [target-dir (io/file *test-dir* "json-test-commands")
            result (call-cli "prompts" "install" (str target-dir) "-f" "json")
            parsed (json/parse-string (:out result) keyword)]
        (is (= 0 (:exit result)))
        (is (contains? parsed :results))
        (is (contains? parsed :metadata))

        (testing "metadata uses camelCase"
          (let [metadata (:metadata parsed)]
            (is (contains? metadata :generatedCount))
            (is (contains? metadata :skippedCount))
            (is (contains? metadata :failedCount))
            (is (contains? metadata :overwrittenCount))
            (is (contains? metadata :targetDir))))

        (testing "results have string status"
          (doseq [res (:results parsed)]
            (is (string? (:status res)))))))))

(deftest prompts-install-uses-mcp-tool-syntax-test
  ;; Test that generated slash commands contain MCP tool references.
  ;; This verifies that templates render with MCP tool syntax for agent use.
  (testing "prompts install uses MCP tool syntax"
    (testing "generated files use MCP tools not CLI commands"
      (let [target-dir (io/file *test-dir* "mcp-tool-commands")
            result (call-cli "prompts" "install" (str target-dir))]
        (is (= 0 (:exit result)))

        ;; Check a few key files for MCP tool references
        (let [execute-task (io/file target-dir "mcp-tasks-execute-task.md")]
          (when (fs/exists? execute-task)
            (let [content (slurp execute-task)]
              ;; Should contain MCP tool references
              (is (str/includes? content "select-tasks")
                  "execute-task should reference select-tasks tool")
              (is (str/includes? content "complete-task")
                  "execute-task should reference complete-task tool")
              ;; Should NOT contain mcp-tasks CLI commands
              (is (not (or (str/includes? content "mcp-tasks show")
                           (str/includes? content "mcp-tasks list")
                           (str/includes? content "mcp-tasks complete")
                           (str/includes? content "mcp-tasks add")
                           (str/includes? content "mcp-tasks update")))
                  "execute-task should not reference mcp-tasks CLI commands"))))))))

(deftest prompts-install-no-cli-commands-test
  ;; Test that generated slash commands do NOT contain CLI command references.
  ;; This verifies slash commands use MCP tool syntax, not CLI commands.
  (testing "prompts install no CLI commands"
    (testing "generated files do not reference CLI commands"
      (let [target-dir (io/file *test-dir* "no-cli-commands")
            result (call-cli "prompts" "install" (str target-dir))]
        (is (= 0 (:exit result)))

        ;; Check execute-task prompt specifically
        (let [execute-task-file (io/file target-dir "mcp-tasks-execute-task.md")]
          (when (fs/exists? execute-task-file)
            (let [content (slurp execute-task-file)]
              (is (not (or (str/includes? content "mcp-tasks show")
                           (str/includes? content "mcp-tasks list")
                           (str/includes? content "mcp-tasks complete")
                           (str/includes? content "mcp-tasks add")
                           (str/includes? content "mcp-tasks update")))
                  "Should not reference mcp-tasks CLI commands")
              (is (str/includes? content "select-tasks")
                  "Should contain MCP tool references"))))))))

(deftest prompts-install-valid-frontmatter-test
  ;; Test that generated slash commands have valid YAML frontmatter
  (testing "prompts install valid frontmatter"
    (testing "generated files start with YAML frontmatter"
      (let [target-dir (io/file *test-dir* "frontmatter-commands")
            result (call-cli "prompts" "install" (str target-dir))]
        (is (= 0 (:exit result)))

        (let [files (vec (fs/list-dir target-dir))]
          (doseq [f files]
            (let [content (slurp (str f))
                  fname (fs/file-name f)]
              ;; Should have frontmatter delimiters
              (is (str/starts-with? content "---\n")
                  (str fname " should start with frontmatter"))
              ;; Should have closing delimiter
              (is (str/includes? content "\n---\n")
                  (str fname " should have closing frontmatter delimiter"))
              ;; Should have description field
              (is (str/includes? content "description:")
                  (str fname " should have description in frontmatter")))))))))

(deftest prompts-install-overwrite-warning-test
  ;; Test that overwriting existing files shows warnings
  (testing "prompts install overwrite warning"
    (testing "shows overwrite indicator when files exist"
      (let [target-dir (io/file *test-dir* "overwrite-test")
            _ (fs/create-dirs target-dir)
            ;; Create an existing file (note: category prompts get next- prefix)
            _ (spit (io/file target-dir "mcp-tasks-next-simple.md") "old content")
            result (call-cli "prompts" "install" (str target-dir))]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "overwritten"))
        (is (str/includes? (:out result) "Warning"))

        ;; Verify file was actually overwritten
        (let [new-content (slurp (io/file target-dir "mcp-tasks-next-simple.md"))]
          (is (not= "old content" new-content))
          (is (str/starts-with? new-content "---")))))))

(deftest prompts-install-help-test
  ;; Test help output for prompts install command
  (testing "prompts install help"
    (testing "displays help text"
      (let [result (call-cli "prompts" "install" "--help")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "install"))
        (is (str/includes? (:out result) "slash command"))
        (is (str/includes? (:out result) ".claude/commands"))
        (is (str/includes? (:out result) "format"))))))

(deftest prompts-install-from-subdirectory-integration-test
  ;; Test prompts install works when run from a subdirectory
  (testing "prompts install from subdirectory"
    (testing "discovers config and installs correctly"
      (let [subdir (io/file *test-dir* "src/main")
            target-dir (io/file *test-dir* "subdir-commands")]
        (fs/create-dirs subdir)
        (let [result (call-cli subdir "prompts" "install" (str target-dir))]
          (is (= 0 (:exit result)))
          (is (fs/exists? target-dir))
          (is (pos? (count (fs/list-dir target-dir)))))))))

;; Hooks Installation Tests

(deftest prompts-install-hooks-scripts-test
  ;; Test that hook scripts are copied to .mcp-tasks/hooks/
  (testing "prompts install hooks scripts"
    (testing "copies hook scripts to .mcp-tasks/hooks/"
      (let [target-dir (io/file *test-dir* "commands")
            hooks-dir (io/file *test-dir* ".mcp-tasks/hooks")
            result (call-cli "prompts" "install" (str target-dir))]
        (is (= 0 (:exit result)))
        (is (fs/exists? hooks-dir)
            "hooks directory should be created")
        (is (fs/exists? (io/file hooks-dir "user-prompt-submit.bb"))
            "user-prompt-submit.bb should exist")
        (is (fs/exists? (io/file hooks-dir "pre-compact.bb"))
            "pre-compact.bb should exist")
        (is (fs/exists? (io/file hooks-dir "session-start.bb"))
            "session-start.bb should exist")))

    (testing "hook scripts contain valid babashka code"
      (let [target-dir (io/file *test-dir* "commands2")
            hooks-dir (io/file *test-dir* ".mcp-tasks/hooks")]
        (call-cli "prompts" "install" (str target-dir))
        (let [content (slurp (io/file hooks-dir "user-prompt-submit.bb"))]
          (is (str/includes? content "#!/usr/bin/env bb")
              "Should have babashka shebang")
          (is (str/includes? content "UserPromptSubmit")
              "Should mention UserPromptSubmit"))))))

(deftest prompts-install-hooks-settings-test
  ;; Test that hooks configuration is installed in .claude/settings.json
  (testing "prompts install hooks settings"
    (testing "creates .claude/settings.json with hooks configuration"
      (let [target-dir (io/file *test-dir* "commands")
            settings-file (io/file *test-dir* ".claude/settings.json")
            result (call-cli "prompts" "install" (str target-dir))]
        (is (= 0 (:exit result)))
        (is (fs/exists? settings-file)
            ".claude/settings.json should be created")
        (let [settings (json/parse-string (slurp settings-file) keyword)]
          (is (contains? settings :hooks)
              "settings should have hooks key")
          (is (contains? (:hooks settings) :UserPromptSubmit)
              "hooks should have UserPromptSubmit")
          (is (contains? (:hooks settings) :PreCompact)
              "hooks should have PreCompact")
          (is (contains? (:hooks settings) :SessionStart)
              "hooks should have SessionStart"))))

    (testing "hook commands reference correct paths"
      (let [settings-file (io/file *test-dir* ".claude/settings.json")
            settings (json/parse-string (slurp settings-file) keyword)
            user-prompt-hooks (get-in settings [:hooks :UserPromptSubmit 0 :hooks 0])]
        (is (= "command" (:type user-prompt-hooks)))
        (is (str/includes? (:command user-prompt-hooks) "bb")
            "command should invoke bb")
        (is (str/includes? (:command user-prompt-hooks) "user-prompt-submit.bb")
            "command should reference user-prompt-submit.bb")))))

(deftest prompts-install-hooks-merge-test
  ;; Test that existing settings.json is merged correctly
  (testing "prompts install hooks merge"
    (testing "preserves existing settings when adding hooks"
      (let [target-dir (io/file *test-dir* "commands")
            claude-dir (io/file *test-dir* ".claude")
            settings-file (io/file claude-dir "settings.json")
            existing-settings {"includeCoAuthoredBy" false
                               "customSetting" "value"}]
        (fs/create-dirs claude-dir)
        (spit settings-file (json/generate-string existing-settings))
        (let [result (call-cli "prompts" "install" (str target-dir))]
          (is (= 0 (:exit result)))
          (let [settings (json/parse-string (slurp settings-file) keyword)]
            (is (= false (:includeCoAuthoredBy settings))
                "includeCoAuthoredBy should be preserved")
            (is (= "value" (:customSetting settings))
                "customSetting should be preserved")
            (is (contains? settings :hooks)
                "hooks should be added")))))

    (testing "merges with existing hooks without duplicating"
      (let [target-dir (io/file *test-dir* "commands2")
            claude-dir (io/file *test-dir* ".claude")
            settings-file (io/file claude-dir "settings.json")
            existing-hooks {"SessionStart" [{"hooks" [{"type" "command"
                                                       "command" "./scripts/web-session-start"}]}]}
            existing-settings {"hooks" existing-hooks}]
        (spit settings-file (json/generate-string existing-settings))
        (let [result (call-cli "prompts" "install" (str target-dir))]
          (is (= 0 (:exit result)))
          (let [settings (json/parse-string (slurp settings-file) keyword)
                session-hooks (get-in settings [:hooks :SessionStart])]
            ;; Should have both the existing hook and the new one
            (is (= 2 (count session-hooks))
                "Should have both existing and new SessionStart hooks")
            ;; Existing hook should still be there
            (is (some #(= "./scripts/web-session-start"
                          (get-in % [:hooks 0 :command]))
                      session-hooks)
                "Existing hook should be preserved")
            ;; New hook should be added
            (is (some #(str/includes? (get-in % [:hooks 0 :command] "")
                                      "session-start.bb")
                      session-hooks)
                "New hook should be added")))))))

(deftest prompts-install-hooks-edn-output-test
  ;; Test that EDN output includes hooks data
  (testing "prompts install hooks EDN output"
    (testing "EDN output includes hooks section"
      (let [target-dir (io/file *test-dir* "commands")
            result (call-cli "prompts" "install" (str target-dir) "-f" "edn")
            parsed (edn/read-string (:out result))]
        (is (= 0 (:exit result)))
        (is (contains? parsed :hooks)
            "EDN output should have :hooks key")
        (is (contains? (:hooks parsed) :scripts)
            "hooks should have :scripts")
        (is (contains? (:hooks parsed) :settings)
            "hooks should have :settings")

        (testing "scripts section has correct structure"
          (let [scripts (:scripts (:hooks parsed))]
            (is (= 3 (count scripts))
                "Should have 3 hook scripts")
            (doseq [script scripts]
              (is (contains? script :script))
              (is (contains? script :status))
              (is (= :installed (:status script))))))

        (testing "settings section has correct structure"
          (let [settings (:settings (:hooks parsed))]
            (is (= :installed (:status settings)))
            (is (string? (:path settings)))))

        (testing "metadata includes hooks counts"
          (let [metadata (:metadata parsed)]
            (is (= 3 (:hooks-installed metadata)))
            (is (= 0 (:hooks-failed metadata)))))))))

(deftest prompts-install-hooks-json-output-test
  ;; Test that JSON output includes hooks data
  (testing "prompts install hooks JSON output"
    (testing "JSON output includes hooks section"
      (let [target-dir (io/file *test-dir* "commands")
            result (call-cli "prompts" "install" (str target-dir) "-f" "json")
            parsed (json/parse-string (:out result) keyword)]
        (is (= 0 (:exit result)))
        (is (contains? parsed :hooks)
            "JSON output should have :hooks key")

        (testing "metadata uses camelCase"
          (let [metadata (:metadata parsed)]
            (is (contains? metadata :hooksInstalled))
            (is (contains? metadata :hooksFailed))
            (is (= 3 (:hooksInstalled metadata)))
            (is (= 0 (:hooksFailed metadata)))))))))

(deftest prompts-install-hooks-human-output-test
  ;; Test that human-readable output includes hooks information
  (testing "prompts install hooks human output"
    (testing "human output shows hooks installation"
      (let [target-dir (io/file *test-dir* "commands")
            result (call-cli "prompts" "install" (str target-dir))]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "Installing event capture hooks")
            "Should show hooks installation header")
        (is (str/includes? (:out result) "Hook Scripts")
            "Should show Hook Scripts section")
        (is (str/includes? (:out result) "user-prompt-submit.bb")
            "Should show user-prompt-submit.bb")
        (is (str/includes? (:out result) "pre-compact.bb")
            "Should show pre-compact.bb")
        (is (str/includes? (:out result) "session-start.bb")
            "Should show session-start.bb")
        (is (str/includes? (:out result) "Settings")
            "Should show Settings section")
        (is (str/includes? (:out result) "settings.json")
            "Should mention settings.json")
        (is (str/includes? (:out result) "Hooks Summary")
            "Should show Hooks Summary")))))
