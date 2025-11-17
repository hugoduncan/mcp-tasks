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

(deftest build-slash-command-frontmatter-generates-yaml
  ;; Test build-slash-command-frontmatter creates proper YAML frontmatter
  ;; for Claude Code slash commands from prompt metadata.
  (testing "build-slash-command-frontmatter"
    (let [build-fn #'sut/build-slash-command-frontmatter]
      (testing "includes both description and argument-hint"
        (let [result (build-fn {"description" "Test desc"
                                "argument-hint" "[task-id] [ctx...]"})]
          (is (str/starts-with? result "---\n"))
          (is (str/ends-with? result "---\n\n"))
          (is (str/includes? result "description: Test desc"))
          (is (str/includes? result "argument-hint: [task-id] [ctx...]"))))

      (testing "includes only description when no argument-hint"
        (let [result (build-fn {"description" "Just desc"})]
          (is (str/starts-with? result "---\n"))
          (is (str/includes? result "description: Just desc"))
          (is (not (str/includes? result "argument-hint")))))

      (testing "includes only argument-hint when no description"
        (let [result (build-fn {"argument-hint" "[args]"})]
          (is (str/starts-with? result "---\n"))
          (is (not (str/includes? result "description:")))
          (is (str/includes? result "argument-hint: [args]"))))

      (testing "returns empty string when no relevant fields"
        (let [result (build-fn {"title" "Just title"})]
          (is (= "" result))))

      (testing "returns empty string for empty metadata"
        (let [result (build-fn {})]
          (is (= "" result))))

      (testing "supports keyword keys for backwards compatibility"
        (let [result (build-fn {:description "Keyword desc"
                                :argument-hint "[kw-args]"})]
          (is (str/includes? result "description: Keyword desc"))
          (is (str/includes? result "argument-hint: [kw-args]")))))))

(deftest prompts-install-command-generates-slash-commands
  ;; Test prompts-install-command generates Claude Code slash command files
  ;; with cli=true context for template conditionals and proper frontmatter.
  ;; Contracts being tested:
  ;; - Generates files for all category and workflow prompts
  ;; - Skips infrastructure files (not actual prompts)
  ;; - Renders templates with {:cli true} context
  ;; - Preserves argument-hint and description in frontmatter
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

        (testing "generates frontmatter with argument-hint"
          (let [override-dir (str temp-dir "/.mcp-tasks/prompt-overrides")
                commands-dir (str temp-dir "/commands-frontmatter")
                test-prompt (str "---\n"
                                 "description: Test with args\n"
                                 "argument-hint: [task-id] [context...]\n"
                                 "---\n"
                                 "Parse $ARGUMENTS for task.\n")]
            (fs/create-dirs override-dir)
            (spit (str override-dir "/refine-task.md") test-prompt)
            (let [config {:resolved-tasks-dir (str temp-dir "/.mcp-tasks")}
                  parsed-args {:target-dir commands-dir}
                  result (sut/prompts-install-command config parsed-args)
                  refine-task (first (filter #(= "refine-task" (:name %))
                                             (:results result)))]
              (is (= :generated (:status refine-task)))
              (let [content (slurp (:path refine-task))]
                (is (str/starts-with? content "---\n")
                    "Should start with frontmatter delimiter")
                (is (str/includes? content "description: Test with args")
                    "Should preserve description in frontmatter")
                (is (str/includes? content "argument-hint: [task-id] [context...]")
                    "Should preserve argument-hint in frontmatter")
                (is (str/includes? content "Parse $ARGUMENTS")
                    "Should preserve $ARGUMENTS placeholder in body")))))

        (testing "generates frontmatter without argument-hint when not present"
          (let [override-dir (str temp-dir "/.mcp-tasks2/category-prompts")
                commands-dir (str temp-dir "/commands-no-args")
                test-prompt (str "---\n"
                                 "description: Simple task\n"
                                 "---\n"
                                 "Execute simple task.\n")]
            (fs/create-dirs override-dir)
            (spit (str override-dir "/simple.md") test-prompt)
            (let [config {:resolved-tasks-dir (str temp-dir "/.mcp-tasks2")}
                  parsed-args {:target-dir commands-dir}
                  result (sut/prompts-install-command config parsed-args)
                  ;; Category prompts get "next-" prefix
                  simple (first (filter #(= "next-simple" (:name %))
                                        (:results result)))]
              (is (= :generated (:status simple)))
              (let [content (slurp (:path simple))]
                (is (str/starts-with? content "---\n")
                    "Should start with frontmatter delimiter")
                (is (str/includes? content "description: Simple task")
                    "Should have description")
                (is (not (str/includes? content "argument-hint:"))
                    "Should not have argument-hint when not in source")))))

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

(deftest prompts-install-overwrites-existing-files
  ;; Test prompts-install-command overwrites existing files and tracks this
  ;; behavior in the results and metadata. Contracts being tested:
  ;; - Overwrites existing files without error
  ;; - Sets :overwritten true in result when file existed
  ;; - Sets :overwritten false when file is newly created
  ;; - Tracks :overwritten-count in metadata

  (testing "prompts-install-command overwrite behavior"
    (let [temp-dir (str (fs/create-temp-dir {:prefix "overwrite-test"}))]
      (try
        (testing "marks files as overwritten when they exist"
          (let [override-dir (str temp-dir "/.mcp-tasks/category-prompts")
                commands-dir (str temp-dir "/commands")
                test-prompt (str "---\n"
                                 "description: Test prompt\n"
                                 "---\n"
                                 "Original content.\n")]
            (fs/create-dirs override-dir)
            (fs/create-dirs commands-dir)
            (spit (str override-dir "/simple.md") test-prompt)
            ;; Create an existing file that will be overwritten
            ;; Category prompts get "next-" prefix in filename
            (spit (str commands-dir "/mcp-tasks-next-simple.md") "old content")

            (let [config {:resolved-tasks-dir (str temp-dir "/.mcp-tasks")}
                  parsed-args {:target-dir commands-dir}
                  result (sut/prompts-install-command config parsed-args)
                  ;; Category prompts get "next-" prefix
                  simple (first (filter #(= "next-simple" (:name %))
                                        (:results result)))]
              (is (= :generated (:status simple)))
              (is (true? (:overwritten simple))
                  "Should mark as overwritten when file existed")

              (testing "new file content replaces old"
                (let [content (slurp (:path simple))]
                  (is (str/includes? content "Original content")
                      "Should contain new content")
                  (is (not (str/includes? content "old content"))
                      "Should not contain old content"))))))

        (testing "marks files as not overwritten when newly created"
          (let [commands-dir (str temp-dir "/fresh-commands")
                config {:resolved-tasks-dir ".mcp-tasks"}
                parsed-args {:target-dir commands-dir}
                result (sut/prompts-install-command config parsed-args)
                generated (filter #(= :generated (:status %)) (:results result))]
            (is (pos? (count generated)))
            (doseq [res generated]
              (is (false? (:overwritten res))
                  (str "Should mark " (:name res) " as not overwritten")))))

        (testing "tracks overwritten count in metadata"
          (let [override-dir (str temp-dir "/.mcp-tasks2/category-prompts")
                commands-dir (str temp-dir "/partial-overwrite")
                test-prompt-1 "---\ndescription: P1\n---\nContent 1.\n"
                test-prompt-2 "---\ndescription: P2\n---\nContent 2.\n"]
            (fs/create-dirs override-dir)
            (fs/create-dirs commands-dir)
            (spit (str override-dir "/simple.md") test-prompt-1)
            (spit (str override-dir "/medium.md") test-prompt-2)
            ;; Create only one existing file - category prompts get "next-" prefix
            (spit (str commands-dir "/mcp-tasks-next-simple.md") "old simple")

            (let [config {:resolved-tasks-dir (str temp-dir "/.mcp-tasks2")}
                  parsed-args {:target-dir commands-dir}
                  result (sut/prompts-install-command config parsed-args)
                  metadata (:metadata result)
                  ;; Category prompts get "next-" prefix
                  simple (first (filter #(= "next-simple" (:name %)) (:results result)))
                  medium (first (filter #(= "next-medium" (:name %)) (:results result)))]
              (is (true? (:overwritten simple)))
              (is (false? (:overwritten medium)))
              (is (= 1 (:overwritten-count metadata))
                  "Should count exactly one overwritten file"))))

        (finally
          (fs/delete-tree temp-dir))))))
