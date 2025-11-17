(ns mcp-tasks.cli.prompts-test
  (:require
    [babashka.fs :as fs]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.cli.prompts :as sut]))

(deftest prompts-list-command-returns-all-prompts
  ;; Test prompts-list-command returns all available prompts with metadata
  (testing "prompts-list-command"
    (testing "returns all prompts with counts"
      (let [result (sut/prompts-list-command {})]
        (is (contains? result :prompts))
        (is (contains? result :metadata))
        (is (vector? (:prompts result)))
        (is (pos? (count (:prompts result))))

        (testing "metadata contains correct counts"
          (let [metadata (:metadata result)
                prompts (:prompts result)]
            (is (= (:total-count metadata) (count prompts)))
            (is (= (:category-count metadata)
                   (count (filter #(= :category (:type %)) prompts))))
            (is (= (:workflow-count metadata)
                   (count (filter #(= :workflow (:type %)) prompts))))))

        (testing "each prompt has required fields"
          (doseq [prompt (:prompts result)]
            (is (contains? prompt :name))
            (is (contains? prompt :type))
            (is (contains? prompt :description))
            (is (string? (:name prompt)))
            (is (keyword? (:type prompt)))
            (is (#{:category :workflow} (:type prompt)))
            (is (string? (:description prompt)))))))))

(deftest prompts-customize-command-customizes-prompts
  ;; Test prompts-customize-command copies prompts and returns results
  (testing "prompts-customize-command"
    (let [test-config {:resolved-tasks-dir (str (System/getProperty "java.io.tmpdir") "/.mcp-tasks")}]
      (testing "with valid prompt names"
        (let [result (sut/prompts-customize-command test-config {:prompt-names ["simple" "medium"]})]
          (is (contains? result :results))
          (is (contains? result :metadata))
          (is (= 2 (count (:results result))))
          (is (= 2 (get-in result [:metadata :requested-count])))

          (testing "each result has required fields"
            (doseq [res (:results result)]
              (is (contains? res :name))
              (is (contains? res :type))
              (is (contains? res :status))
              (is (keyword? (:status res)))))))

      (testing "with non-existent prompt"
        (let [result (sut/prompts-customize-command test-config {:prompt-names ["nonexistent"]})]
          (is (= 1 (count (:results result))))
          (is (= :not-found (get-in result [:results 0 :status])))
          (is (= 0 (get-in result [:metadata :installed-count])))
          (is (= 1 (get-in result [:metadata :failed-count])))))

      (testing "with mix of valid and invalid prompts"
        (let [result (sut/prompts-customize-command test-config {:prompt-names ["simple" "invalid" "medium"]})]
          (is (= 3 (count (:results result))))
          (is (= 3 (get-in result [:metadata :requested-count])))
          (is (<= (get-in result [:metadata :installed-count]) 2))
          (is (>= (get-in result [:metadata :failed-count]) 1)))))))

(deftest prompts-install-command-generates-slash-commands
  ;; Test prompts-install-command generates Claude Code slash command files
  ;; with cli=true context for template conditionals.
  ;; Contracts being tested:
  ;; - Generates files for all category and workflow prompts
  ;; - Skips infrastructure files (not actual prompts)
  ;; - Renders templates with {:cli true} context
  ;; - Returns correct metadata counts
  ;; - Files are named mcp-tasks-<prompt-name>.md

  (testing "prompts-install-command"
    (let [temp-dir (str (fs/create-temp-dir {:prefix "prompts-install-test"}))]
      (try
        (testing "generates files for all available prompts"
          (let [config {:resolved-tasks-dir ".mcp-tasks"}
                parsed-args {:target-dir temp-dir}
                result (sut/prompts-install-command config parsed-args)]
            (is (contains? result :results))
            (is (contains? result :metadata))
            (is (vector? (:results result)))

            (testing "metadata contains correct counts"
              (let [metadata (:metadata result)
                    results (:results result)
                    generated (filter #(= :generated (:status %)) results)
                    skipped (filter #(= :skipped (:status %)) results)
                    failed (filter #(= :failed (:status %)) results)]
                (is (= (:generated-count metadata) (count generated)))
                (is (= (:skipped-count metadata) (count skipped)))
                (is (= (:failed-count metadata) (count failed)))
                (is (= temp-dir (:target-dir metadata)))
                (is (pos? (:generated-count metadata))
                    "Should generate at least one prompt")
                (is (zero? (:failed-count metadata))
                    "Should have no failures")))

            (testing "each generated result has required fields"
              (doseq [res (filter #(= :generated (:status %)) (:results result))]
                (is (string? (:name res)))
                (is (#{:category :workflow} (:type res)))
                (is (= :generated (:status res)))
                (is (string? (:path res)))
                (is (str/ends-with? (:path res) ".md"))))

            (testing "each skipped result has required fields"
              (doseq [res (filter #(= :skipped (:status %)) (:results result))]
                (is (string? (:name res)))
                (is (nil? (:type res)))
                (is (= :skipped (:status res)))
                (is (string? (:reason res)))))

            (testing "files are created with correct naming convention"
              (let [files (vec (fs/list-dir temp-dir))
                    file-names (map #(str (fs/file-name %)) files)]
                (is (pos? (count files)))
                (doseq [fname file-names]
                  (is (str/starts-with? fname "mcp-tasks-"))
                  (is (str/ends-with? fname ".md")))))))

        (testing "renders templates with cli=true context"
          (let [override-dir (str temp-dir "/.mcp-tasks/prompt-overrides")
                commands-dir (str temp-dir "/commands")
                ;; Create a test prompt with {% if cli %} conditionals
                test-prompt (str "---\ndescription: Test prompt\n---\n"
                                 "Use {% if cli %}CLI commands{% else %}MCP tools{% endif %}.\n"
                                 "{% if cli %}Run mcp-tasks list{% else %}Call select-tasks{% endif %}")]
            (fs/create-dirs override-dir)
            (spit (str override-dir "/execute-task.md") test-prompt)
            (let [config {:resolved-tasks-dir (str temp-dir "/.mcp-tasks")}
                  parsed-args {:target-dir commands-dir}
                  result (sut/prompts-install-command config parsed-args)
                  execute-task (first (filter #(= "execute-task" (:name %))
                                              (:results result)))]
              (is (= :generated (:status execute-task)))
              (let [content (slurp (:path execute-task))]
                (is (str/includes? content "CLI commands")
                    "Should render cli=true branch")
                (is (not (str/includes? content "MCP tools"))
                    "Should not render cli=false branch")
                (is (str/includes? content "mcp-tasks list")
                    "Should include CLI command")
                (is (not (str/includes? content "select-tasks"))
                    "Should not include MCP tool reference")))))

        (testing "uses custom target directory"
          (let [custom-dir (str temp-dir "/custom-commands")
                config {:resolved-tasks-dir ".mcp-tasks"}
                parsed-args {:target-dir custom-dir}
                result (sut/prompts-install-command config parsed-args)]
            (is (= custom-dir (get-in result [:metadata :target-dir])))
            (is (fs/exists? custom-dir))
            (is (pos? (count (fs/list-dir custom-dir))))))

        (finally
          (fs/delete-tree temp-dir))))))
