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
      (let [result (call-cli "prompts" "list")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "Available Prompts"))
        (is (str/includes? (:out result) "Category Prompts"))
        (is (str/includes? (:out result) "Workflow Prompts"))
        ;; Check for known category prompts
        (is (str/includes? (:out result) "simple"))
        (is (str/includes? (:out result) "medium"))
        (is (str/includes? (:out result) "large"))
        ;; Check for known workflow prompts
        (is (str/includes? (:out result) "execute-task"))
        (is (str/includes? (:out result) "refine-task"))))

    (testing "human format explicitly specified"
      (let [result (call-cli "--format" "human" "prompts" "list")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "Available Prompts"))))))

(deftest prompts-list-edn-format-test
  ;; Test prompts list with EDN output format
  (testing "prompts-list-edn-format"
    (testing "can list prompts in EDN format"
      (let [result (call-cli "--format" "edn" "prompts" "list")]
        (is (= 0 (:exit result)))
        (let [parsed (edn/read-string (:out result))]
          (is (map? parsed))
          (is (contains? parsed :prompts))
          (is (contains? parsed :metadata))
          (is (vector? (:prompts parsed)))
          (is (pos? (count (:prompts parsed))))

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

(deftest prompts-install-single-category-test
  ;; Test installing a single category prompt
  (testing "prompts-install-single-category"
    (testing "can install simple category prompt"
      (let [result (call-cli "prompts" "install" "simple")
            target-file (io/file *test-dir* ".mcp-tasks/category-prompts/simple.md")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "simple"))
        (is (str/includes? (:out result) "category"))
        (is (.exists target-file))
        (let [content (slurp target-file)]
          (is (str/includes? content "---"))
          (is (str/includes? content "description:")))))

    (testing "human format shows installation status"
      (let [result (call-cli "prompts" "install" "medium")]
        (is (= 0 (:exit result)))
        (is (or (str/includes? (:out result) "✓")
                (str/includes? (:out result) "installed")))
        (is (str/includes? (:out result) "category-prompts"))))))

(deftest prompts-install-single-workflow-test
  ;; Test installing a single workflow prompt
  (testing "prompts-install-single-workflow"
    (testing "can install execute-task workflow prompt"
      (let [result (call-cli "prompts" "install" "execute-task")
            target-file (io/file *test-dir* ".mcp-tasks/prompt-overrides/execute-task.md")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "execute-task"))
        (is (str/includes? (:out result) "workflow"))
        (is (.exists target-file))
        (let [content (slurp target-file)]
          (is (str/includes? content "---"))
          (is (str/includes? content "description:")))))

    (testing "human format shows installation path"
      (let [result (call-cli "prompts" "install" "refine-task")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "prompt-overrides"))))))

(deftest prompts-install-multiple-test
  ;; Test installing multiple prompts at once
  (testing "prompts-install-multiple"
    (testing "can install multiple prompts"
      (let [result (call-cli "prompts" "install" "simple" "medium" "execute-task")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "simple"))
        (is (str/includes? (:out result) "medium"))
        (is (str/includes? (:out result) "execute-task"))
        ;; Verify files exist
        (is (.exists (io/file *test-dir* ".mcp-tasks/category-prompts/simple.md")))
        (is (.exists (io/file *test-dir* ".mcp-tasks/category-prompts/medium.md")))
        (is (.exists (io/file *test-dir* ".mcp-tasks/prompt-overrides/execute-task.md")))))

    (testing "human format shows summary"
      (let [result (call-cli "prompts" "install" "simple" "large")]
        (is (= 0 (:exit result)))
        (is (or (str/includes? (:out result) "Summary")
                (str/includes? (:out result) "installed")))))))

(deftest prompts-install-edn-format-test
  ;; Test prompts install with EDN output
  (testing "prompts-install-edn-format"
    (testing "can install with EDN output"
      (let [result (call-cli "--format" "edn" "prompts" "install" "simple")]
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
      (let [result (call-cli "prompts" "install" "medium" "execute-task" "-f" "edn")
            parsed (edn/read-string (:out result))]
        (is (= 2 (count (:results parsed))))
        (is (= 2 (get-in parsed [:metadata :requested-count])))
        ;; Count successful ones (either :installed or :exists)
        (let [successful (count (filter #(#{:installed :exists} (:status %)) (:results parsed)))]
          (is (= 2 successful)))))))

(deftest prompts-install-json-format-test
  ;; Test prompts install with JSON output
  (testing "prompts-install-json-format"
    (testing "can install with JSON output"
      (let [result (call-cli "--format" "json" "prompts" "install" "simple")]
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
      (let [result (call-cli "prompts" "install" "large" "refine-task" "-f" "json")
            parsed (json/parse-string (:out result) keyword)]
        (is (= 2 (count (:results parsed))))
        (is (= 2 (-> parsed :metadata :requestedCount)))
        ;; Verify all have valid statuses
        (is (every? #(contains? #{"installed" "exists"} (:status %)) (:results parsed)))))))

(deftest prompts-install-error-invalid-name-test
  ;; Test error handling for invalid prompt names
  (testing "prompts-install-error-invalid-name"
    (testing "installing non-existent prompt shows error"
      (let [result (call-cli "prompts" "install" "nonexistent")]
        (is (= 0 (:exit result)))
        (is (or (str/includes? (:out result) "✗")
                (str/includes? (:out result) "not found")
                (str/includes? (:out result) "failed")))))

    (testing "EDN format shows not-found status"
      (let [result (call-cli "--format" "edn" "prompts" "install" "invalid-prompt")
            parsed (edn/read-string (:out result))]
        (is (= 1 (count (:results parsed))))
        (is (= :not-found (get-in parsed [:results 0 :status])))
        (is (= 0 (get-in parsed [:metadata :installed-count])))
        (is (= 1 (get-in parsed [:metadata :failed-count])))))

    (testing "JSON format shows not-found status"
      (let [result (call-cli "prompts" "install" "bad-name" "-f" "json")
            parsed (json/parse-string (:out result) keyword)]
        (is (= "not-found" (get-in parsed [:results 0 :status])))
        ;; not-found counts as failed
        (is (number? (-> parsed :metadata :installedCount)))
        (is (number? (-> parsed :metadata :failedCount)))))))

(deftest prompts-install-mixed-valid-invalid-test
  ;; Test installing mix of valid and invalid prompts
  (testing "prompts-install-mixed-valid-invalid"
    (testing "processes all prompts and reports status"
      (let [result (call-cli "prompts" "install" "simple" "invalid" "medium")]
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
      (let [result (call-cli "prompts" "install" "large" "bad" "execute-task" "-f" "edn")
            parsed (edn/read-string (:out result))]
        (is (= 3 (count (:results parsed))))
        (is (= 3 (get-in parsed [:metadata :requested-count])))
        ;; Check individual statuses - should have success statuses and not-found
        (let [statuses (map :status (:results parsed))
              successful (count (filter #(#{:installed :exists} %) statuses))
              not-found (count (filter #(= :not-found %) statuses))]
          (is (= 2 successful) "Should have 2 successful installs")
          (is (= 1 not-found) "Should have 1 not-found"))))))

(deftest prompts-install-skip-existing-test
  ;; Test that installing skips existing prompts without overwriting
  (testing "prompts-install-skip-existing"
    (testing "preserves modified files on re-install"
      (let [result (call-cli "prompts" "install" "simple")
            target-file (io/file *test-dir* ".mcp-tasks/category-prompts/simple.md")]
        (is (= 0 (:exit result)))
        (is (.exists target-file))
        ;; Modify the file
        (spit target-file "Modified content")
        (is (= "Modified content" (slurp target-file)))

        ;; Install again
        (let [result2 (call-cli "prompts" "install" "simple")]
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
        (is (str/includes? (:out result) "install"))))

    (testing "prompts list has help"
      (let [result (call-cli "prompts" "list" "--help")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "list"))
        (is (str/includes? (:out result) "format"))))

    (testing "prompts install has help"
      (let [result (call-cli "prompts" "install" "--help")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "install"))
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
            install-result (apply call-cli "prompts" "install" (concat category-names ["-f" "edn"]))
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
            install-result (apply call-cli "prompts" "install" (concat workflow-names ["-f" "edn"]))
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
      (call-cli "prompts" "install" "simple" "execute-task")
      ;; Category prompt goes to category-prompts/
      (is (.exists (io/file *test-dir* ".mcp-tasks/category-prompts/simple.md")))
      (is (not (.exists (io/file *test-dir* ".mcp-tasks/prompt-overrides/simple.md"))))
      ;; Workflow prompt goes to prompt-overrides/
      (is (.exists (io/file *test-dir* ".mcp-tasks/prompt-overrides/execute-task.md")))
      (is (not (.exists (io/file *test-dir* ".mcp-tasks/category-prompts/execute-task.md")))))))

(deftest prompts-install-from-subdirectory-test
  ;; Test that prompts install works correctly when run from a subdirectory
  (testing "prompts-install-from-subdirectory"
    (testing "installs to parent .mcp-tasks when run from subdirectory"
      (let [subdir (io/file *test-dir* "src")]
        (fs/create-dirs subdir)
        (let [result (call-cli subdir "prompts" "install" "simple")
              target-file (io/file *test-dir* ".mcp-tasks/category-prompts/simple.md")]
          (is (= 0 (:exit result)))
          (is (.exists target-file))
          (is (str/includes? (slurp target-file) "---"))
          (is (str/includes? (slurp target-file) "description:")))))

    (testing "works from nested subdirectory"
      (let [nested-dir (io/file *test-dir* "src/test/integration")]
        (fs/create-dirs nested-dir)
        (let [result (call-cli nested-dir "prompts" "install" "medium")
              target-file (io/file *test-dir* ".mcp-tasks/category-prompts/medium.md")]
          (is (= 0 (:exit result)))
          (is (.exists target-file)))))))

(deftest prompts-install-without-config-test
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

          (let [result (call-cli (io/file test-dir) "prompts" "install" "simple")
                target-file (io/file category-dir "simple.md")]
            (is (= 0 (:exit result)))
            (is (.exists target-file))
            (is (str/includes? (slurp target-file) "---"))
            (is (str/includes? (slurp target-file) "description:")))
          (finally
            (fs/delete-tree test-dir)))))))

(deftest prompts-install-custom-absolute-tasks-dir-test
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

          (let [result (call-cli (io/file test-dir) "prompts" "install" "simple")
                target-file (io/file category-dir "simple.md")]
            (is (= 0 (:exit result)))
            (is (.exists target-file))
            (is (str/includes? (slurp target-file) "---"))
            (is (str/includes? (slurp target-file) "description:")))
          (finally
            (fs/delete-tree test-dir)
            (fs/delete-tree custom-tasks-dir)))))))

(deftest prompts-install-custom-relative-tasks-dir-test
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

          (let [result (call-cli (io/file test-dir) "prompts" "install" "medium")
                target-file (io/file category-dir "medium.md")]
            (is (= 0 (:exit result)))
            (is (.exists target-file))
            (is (str/includes? (slurp target-file) "---"))
            (is (str/includes? (slurp target-file) "description:")))
          (finally
            (fs/delete-tree test-dir)))))))

(deftest prompts-install-from-worktree-test
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
          (let [result (call-cli (io/file worktree-path) "prompts" "install" "simple")
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
          (let [result (call-cli nested-dir "prompts" "install" "medium")
                target-file (io/file category-dir "medium.md")]
            (is (= 0 (:exit result)))
            (is (.exists target-file)))
          (finally
            (fs/delete-tree project-dir)))))))
