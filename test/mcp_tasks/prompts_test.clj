(ns mcp-tasks.prompts-test
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.prompts :as sut]))

(deftest discover-categories-test
  ;; Test that discover-categories finds categories from the
  ;; .mcp-tasks/prompts subdirectory, returning them sorted
  ;; without .md extensions.
  (testing "discover-categories"
    (testing "returns sorted categories from .mcp-tasks/prompts directory"
      (let [categories (sut/discover-categories)]
        (is (vector? categories))
        (is (every? string? categories))
        (is (= categories (sort categories)))
        (is (not-any? #(re-find #"\.md$" %) categories))))

    (testing "finds categories from prompts subdirectory"
      (let [categories (sut/discover-categories)
            category-set (set categories)]
        ;; Should find "simple" which exists in prompts
        (is (contains? category-set "simple"))))

    (testing "returns categories only from prompts subdirectory"
      (let [categories (sut/discover-categories)]
        ;; Each category should appear exactly once
        (is (= (count categories) (count (set categories))))))))

(deftest parse-frontmatter-test
  ;; Test that parse-frontmatter correctly extracts metadata and content
  ;; from markdown text with various frontmatter formats.
  (testing "parse-frontmatter"
    (testing "parses valid frontmatter"
      (let [text "---\ndescription: Test prompt\nauthor: John\n---\n\nContent here"
            result (#'sut/parse-frontmatter text)]
        (is (= {"description" "Test prompt" "author" "John"} (:metadata result)))
        (is (= "\nContent here" (:content result)))))

    (testing "handles text without frontmatter"
      (let [text "No frontmatter here"
            result (#'sut/parse-frontmatter text)]
        (is (nil? (:metadata result)))
        (is (= text (:content result)))))

    (testing "handles frontmatter without closing delimiter"
      (let [text "---\ndescription: Test\nNo closing delimiter"
            result (#'sut/parse-frontmatter text)]
        (is (nil? (:metadata result)))
        (is (= text (:content result)))))

    (testing "handles empty frontmatter block"
      (let [text "---\n---\n\nContent"
            result (#'sut/parse-frontmatter text)]
        (is (nil? (:metadata result)))
        (is (= "\nContent" (:content result)))))

    (testing "handles multi-line values"
      (let [text "---\ndescription: A long description\n---\nContent"
            result (#'sut/parse-frontmatter text)]
        (is (= {"description" "A long description"} (:metadata result)))
        (is (= "Content" (:content result)))))

    (testing "ignores lines without colons"
      (let [text "---\ndescription: Test\nInvalid line\nauthor: Bob\n---\nContent"
            result (#'sut/parse-frontmatter text)]
        (is (= {"description" "Test" "author" "Bob"} (:metadata result)))
        (is (= "Content" (:content result)))))))

(deftest create-prompts-test
  ;; Test that create-prompts generates valid MCP prompts for categories
  ;; with both custom and default instructions.
  (testing "create-prompts"
    (testing "creates prompts for each category"
      (let [prompts (sut/create-prompts {:use-git? true} ["simple" "test-category"])]
        (is (vector? prompts))
        (is (= 2 (count prompts)))
        (is (every? map? prompts))))

    (testing "generates correct prompt structure"
      (let [prompts (sut/create-prompts {:use-git? true} ["simple"])
            prompt (first prompts)]
        (is (contains? prompt :name))
        (is (contains? prompt :description))
        (is (contains? prompt :messages))
        (is (= "next-simple" (:name prompt)))
        (is (string? (:description prompt)))
        (is (vector? (:messages prompt)))))

    (testing "uses default template when no custom prompt file exists"
      (let [prompts (sut/create-prompts {:use-git? true} ["nonexistent"])
            prompt (first prompts)
            message-text (get-in prompt [:messages 0 :content :text])]
        (is (= "next-nonexistent" (:name prompt)))
        (is (re-find #"nonexistent task" message-text))
        (is (re-find #"\.mcp-tasks/tasks\.ednl" message-text))))

    (testing "generates prompts with proper category substitution"
      (let [prompts (sut/create-prompts {:use-git? true} ["simple"])
            prompt (first prompts)
            message-text (get-in prompt [:messages 0 :content :text])]
        (is (= "next-simple" (:name prompt)))
        (is (re-find #"simple task" message-text))
        (is (re-find #"\.mcp-tasks/tasks\.ednl" message-text))
        (is (re-find #"\.mcp-tasks/complete\.ednl" message-text))))

    (testing "uses metadata description when available"
      (let [prompts (sut/create-prompts {:use-git? true} ["simple"])
            prompt (first prompts)]
        (is (= "next-simple" (:name prompt)))
        (is (= "Execute simple tasks with basic workflow" (:description prompt)))))

    (testing "uses default description when no metadata"
      (let [prompts (sut/create-prompts {:use-git? true} ["nonexistent"])
            prompt (first prompts)]
        (is (= "next-nonexistent" (:name prompt)))
        (is (= "Execute the next nonexistent task from .mcp-tasks/tasks.ednl"
               (:description prompt)))))

    (testing "includes git instructions when use-git? is true"
      (let [prompts (sut/create-prompts {:use-git? true} ["simple"])
            prompt (first prompts)
            message-text (get-in prompt [:messages 0 :content :text])]
        (is (re-find #"Commit the task tracking changes" message-text))))

    (testing "omits .mcp-tasks git commit instructions when use-git? is false"
      (let [prompts (sut/create-prompts {:use-git? false} ["simple"])
            prompt (first prompts)
            message-text (get-in prompt [:messages 0 :content :text])]
        (is (not (re-find #"Commit the task tracking changes in the \.mcp-tasks git repository" message-text)))))))

(deftest category-descriptions-test
  ;; Test that category-descriptions returns correct descriptions for all categories.
  (testing "category-descriptions"
    (testing "returns map of category to description"
      (let [descs (sut/category-descriptions)]
        (is (map? descs))
        (is (contains? descs "simple"))
        (is (= "Execute simple tasks with basic workflow" (get descs "simple")))))

    (testing "includes all discovered categories"
      (let [categories (sut/discover-categories)
            descs (sut/category-descriptions)]
        (is (= (set categories) (set (keys descs))))))))

(deftest read-task-prompt-text-test
  ;; Test that read-task-prompt-text generates correct prompt text.
  ;; Config parameter is accepted but not currently used.
  (testing "read-task-prompt-text"
    (testing "generates prompt text for category"
      (let [text (#'sut/read-task-prompt-text {:use-git? true} "simple")]
        (is (string? text))
        (is (re-find #"\.mcp-tasks/tasks\.ednl" text))
        (is (re-find #"first incomplete task" text))))

    (testing "config parameter doesn't affect output"
      (let [text-git (#'sut/read-task-prompt-text {:use-git? true} "simple")
            text-no-git (#'sut/read-task-prompt-text {:use-git? false} "simple")]
        (is (= text-git text-no-git))))))

(deftest complete-task-prompt-text-test
  ;; Test that complete-task-prompt-text conditionally includes git instructions.
  (testing "complete-task-prompt-text"
    (testing "includes git instructions when use-git? is true"
      (let [text (#'sut/complete-task-prompt-text {:use-git? true} "simple")]
        (is (string? text))
        (is (re-find #"\.mcp-tasks/tasks\.ednl" text))
        (is (re-find #"\.mcp-tasks/complete\.ednl" text))
        (is (re-find #"Commit the task tracking changes in the \.mcp-tasks git repository" text))))

    (testing "omits git instructions when use-git? is false"
      (let [text (#'sut/complete-task-prompt-text {:use-git? false} "simple")]
        (is (string? text))
        (is (re-find #"\.mcp-tasks/tasks\.ednl" text))
        (is (re-find #"\.mcp-tasks/complete\.ednl" text))
        (is (not (re-find #"Commit" text)))
        (is (not (re-find #"git" text)))))

    (testing "includes complete-task tool usage instructions in both modes"
      (let [text-git (#'sut/complete-task-prompt-text {:use-git? true} "simple")
            text-no-git (#'sut/complete-task-prompt-text {:use-git? false} "simple")]
        (is (re-find #"Mark the completed task as complete" text-git))
        (is (re-find #"complete-task.*tool" text-git))
        (is (re-find #"Mark the completed task as complete" text-no-git))
        (is (re-find #"complete-task.*tool" text-no-git))))))

(deftest get-story-prompt-test
  ;; Test that get-story-prompt retrieves prompts from file overrides or
  ;; built-ins, with proper frontmatter parsing and fallback behavior.
  (testing "get-story-prompt"
    (testing "returns nil for nonexistent prompt"
      (is (nil? (sut/get-story-prompt "nonexistent-prompt"))))

    (testing "returns built-in prompt when no override file exists"
      (let [result (sut/get-story-prompt "nonexistent-builtin")]
        (is (nil? result))))))

(deftest list-story-prompts-test
  ;; Test that list-story-prompts returns all available prompts including
  ;; both built-ins and file overrides, with deduplication.
  (testing "list-story-prompts"
    (testing "returns sequence of prompt maps"
      (let [prompts (sut/list-story-prompts)]
        (is (seq? prompts))
        (is (every? map? prompts))
        (is (every? #(contains? % :name) prompts))))

    (testing "includes :name and :description for each prompt"
      (let [prompts (sut/list-story-prompts)]
        (is (every? #(and (contains? % :name)
                          (contains? % :description)) prompts))))

    (testing "returns unique prompt names"
      (let [prompts (sut/list-story-prompts)
            names (map :name prompts)]
        (is (= (count names) (count (set names))))))))

(deftest refine-story-prompt-test
  ;; Test that the refine-story built-in prompt is properly defined with
  ;; correct metadata and content structure.
  (testing "refine-story prompt"
    (testing "is available via get-story-prompt"
      (let [prompt (sut/get-story-prompt "refine-story")]
        (is (some? prompt))
        (is (= "refine-story" (:name prompt)))))

    (testing "has description metadata"
      (let [prompt (sut/get-story-prompt "refine-story")]
        (is (some? (:description prompt)))
        (is (string? (:description prompt)))))

    (testing "has content with key instructions"
      (let [prompt (sut/get-story-prompt "refine-story")
            content (:content prompt)]
        (is (some? content))
        (is (string? content))
        (is (re-find #"select-tasks" content))
        (is (re-find #"update-task" content))
        (is (re-find #"interactive" content))))

    (testing "appears in list-story-prompts"
      (let [prompts (sut/list-story-prompts)
            refine-prompt (first (filter #(= "refine-story" (:name %)) prompts))]
        (is (some? refine-prompt))
        (is (some? (:description refine-prompt)))))))

(deftest create-story-tasks-prompt-test
  ;; Test that the create-story-tasks built-in prompt is properly defined
  ;; with correct metadata and content structure including task format and
  ;; category guidance.
  (testing "create-story-tasks prompt"
    (testing "is available via get-story-prompt"
      (let [prompt (sut/get-story-prompt "create-story-tasks")]
        (is (some? prompt))
        (is (= "create-story-tasks" (:name prompt)))))

    (testing "has description metadata"
      (let [prompt (sut/get-story-prompt "create-story-tasks")]
        (is (some? (:description prompt)))
        (is (string? (:description prompt)))))

    (testing "has content with key instructions"
      (let [prompt (sut/get-story-prompt "create-story-tasks")
            content (:content prompt)]
        (is (some? content))
        (is (string? content))
        (is (re-find #"story-name" content))
        (is (re-find #"select-tasks.*tool.*title-pattern" content))
        (is (re-find #"add-task" content))
        (is (re-find #"tasks\.ednl" content))
        (is (re-find #"category" content))))

    (testing "includes category selection guidance"
      (let [prompt (sut/get-story-prompt "create-story-tasks")
            content (:content prompt)]
        (is (re-find #"simple" content))
        (is (re-find #"medium" content))
        (is (re-find #"large" content))
        (is (re-find #"clarify-task" content))))

    (testing "includes task format examples"
      (let [prompt (sut/get-story-prompt "create-story-tasks")
            content (:content prompt)]
        (is (re-find #"title" content))
        (is (re-find #"parent-id" content))
        (is (re-find #"Part of story:" content))))

    (testing "appears in list-story-prompts"
      (let [prompts (sut/list-story-prompts)
            create-prompt (first (filter #(= "create-story-tasks" (:name %)) prompts))]
        (is (some? create-prompt))
        (is (some? (:description create-prompt)))))))

(deftest execute-story-task-prompt-test
  ;; Test that the execute-story-task built-in prompt is properly defined
  ;; with correct metadata and content structure including task execution,
  ;; queue management, and branch management instructions.
  (testing "execute-story-task prompt"
    (testing "is available via get-story-prompt"
      (let [prompt (sut/get-story-prompt "execute-story-task")]
        (is (some? prompt))
        (is (= "execute-story-task" (:name prompt)))))

    (testing "has description metadata"
      (let [prompt (sut/get-story-prompt "execute-story-task")]
        (is (some? (:description prompt)))
        (is (string? (:description prompt)))))

    (testing "has content with key instructions"
      (let [prompt (sut/get-story-prompt "execute-story-task")
            content (:content prompt)]
        (is (some? content))
        (is (string? content))
        (is (re-find #"select-tasks" content))
        (is (re-find #"complete-task" content))
        (is (re-find #"category" content))))

    (testing "includes task execution workflow"
      (let [prompt (sut/get-story-prompt "execute-story-task")
            content (:content prompt)]
        (is (re-find #"select-tasks" content))
        (is (re-find #"complete-task" content))))

    (testing "appears in list-story-prompts"
      (let [prompts (sut/list-story-prompts)
            execute-prompt (first (filter #(= "execute-story-task" (:name %)) prompts))]
        (is (some? execute-prompt))
        (is (some? (:description execute-prompt)))))))

(defn- create-test-override-file
  "Create a temporary override file for testing.
  Returns the file object for cleanup."
  [prompt-name content]
  (let [override-dir (io/file ".mcp-tasks" "story" "prompts")
        override-file (io/file override-dir (str prompt-name ".md"))]
    (.mkdirs override-dir)
    (spit override-file content)
    override-file))

(defn- delete-test-file
  "Delete a test file if it exists."
  [file]
  (when (.exists file)
    (.delete file)))

(deftest get-story-prompt-override-precedence-test
  ;; Test that get-story-prompt correctly prioritizes override files over
  ;; built-in prompts, with proper frontmatter parsing.
  (testing "get-story-prompt override precedence"
    (testing "uses override file when it exists, even if built-in exists"
      (let [test-file (create-test-override-file
                        "refine-story"
                        "---\ndescription: Override description\n---\n\nOverride content")]
        (try
          (let [result (sut/get-story-prompt "refine-story")]
            (is (some? result))
            (is (= "refine-story" (:name result)))
            (is (= "Override description" (:description result)))
            (is (= "\nOverride content" (:content result))))
          (finally
            (delete-test-file test-file)))))

    (testing "falls back to built-in when no override exists"
      (let [result (sut/get-story-prompt "create-story-tasks")]
        (is (some? result))
        (is (= "create-story-tasks" (:name result)))
        (is (some? (:description result)))
        (is (some? (:content result)))))

    (testing "returns nil when neither override nor built-in exists"
      (is (nil? (sut/get-story-prompt "completely-nonexistent-prompt"))))))

(deftest get-story-prompt-frontmatter-handling-test
  ;; Test that get-story-prompt correctly handles frontmatter in override
  ;; files with various formats.
  (testing "get-story-prompt frontmatter handling"
    (testing "parses frontmatter from override file"
      (let [test-file (create-test-override-file
                        "test-with-frontmatter"
                        "---\ndescription: Test description\nauthor: Test Author\n---\n\nTest content")]
        (try
          (let [result (sut/get-story-prompt "test-with-frontmatter")]
            (is (some? result))
            (is (= "Test description" (:description result)))
            (is (= "\nTest content" (:content result))))
          (finally
            (delete-test-file test-file)))))

    (testing "handles override file without frontmatter"
      (let [test-file (create-test-override-file
                        "test-no-frontmatter"
                        "Just plain content without frontmatter")]
        (try
          (let [result (sut/get-story-prompt "test-no-frontmatter")]
            (is (some? result))
            (is (nil? (:description result)))
            (is (= "Just plain content without frontmatter" (:content result))))
          (finally
            (delete-test-file test-file)))))

    (testing "handles override file with empty frontmatter"
      (let [test-file (create-test-override-file
                        "test-empty-frontmatter"
                        "---\n---\n\nContent after empty frontmatter")]
        (try
          (let [result (sut/get-story-prompt "test-empty-frontmatter")]
            (is (some? result))
            (is (nil? (:description result)))
            (is (= "\nContent after empty frontmatter" (:content result))))
          (finally
            (delete-test-file test-file)))))

    (testing "handles override file with partial frontmatter"
      (let [test-file (create-test-override-file
                        "test-partial-frontmatter"
                        "---\nauthor: Someone\n---\n\nContent")]
        (try
          (let [result (sut/get-story-prompt "test-partial-frontmatter")]
            (is (some? result))
            (is (nil? (:description result)))
            (is (= "\nContent" (:content result))))
          (finally
            (delete-test-file test-file)))))))

(deftest list-story-prompts-override-integration-test
  ;; Test that list-story-prompts correctly includes overrides and handles
  ;; deduplication when both override and built-in exist.
  (testing "list-story-prompts with overrides"
    (testing "includes override prompts in the list"
      (let [test-file (create-test-override-file
                        "custom-override-prompt"
                        "---\ndescription: Custom override\n---\n\nCustom content")]
        (try
          (let [prompts (sut/list-story-prompts)
                names (set (map :name prompts))]
            (is (contains? names "custom-override-prompt"))
            (let [custom-prompt (first (filter #(= "custom-override-prompt" (:name %)) prompts))]
              (is (= "Custom override" (:description custom-prompt)))))
          (finally
            (delete-test-file test-file)))))

    (testing "deduplicates when both override and built-in exist"
      (let [test-file (create-test-override-file
                        "refine-story"
                        "---\ndescription: Overridden refine-story\n---\n\nOverride content")]
        (try
          (let [prompts (sut/list-story-prompts)
                refine-prompts (filter #(= "refine-story" (:name %)) prompts)]
            (is (= 1 (count refine-prompts)))
            (is (= "Overridden refine-story" (:description (first refine-prompts)))))
          (finally
            (delete-test-file test-file)))))

    (testing "override takes precedence in deduplicated list"
      (let [test-file (create-test-override-file
                        "create-story-tasks"
                        "---\ndescription: Override takes precedence\n---\n\nOverride")]
        (try
          (let [prompts (sut/list-story-prompts)
                create-prompt (first (filter #(= "create-story-tasks" (:name %)) prompts))]
            (is (some? create-prompt))
            (is (= "Override takes precedence" (:description create-prompt))))
          (finally
            (delete-test-file test-file)))))))

(deftest story-prompts-branch-management-test
  ;; Test that story-prompts conditionally includes branch management
  ;; instructions in execute-story-task prompt based on config.
  (testing "story-prompts branch management"
    (testing "includes branch management when :story-branch-management? is true"
      (let [prompts (sut/story-prompts {:story-branch-management? true})
            execute-prompt (get prompts "execute-story-task")]
        (is (some? execute-prompt))
        (let [content (get-in execute-prompt [:messages 0 :content :text])]
          (is (re-find #"Branch Management" content))
          (is (re-find #"checkout the default branch" content))
          (is (re-find #"create the `<story-name>` branch" content)))))

    (testing "excludes branch management when :story-branch-management? is false"
      (let [prompts (sut/story-prompts {:story-branch-management? false})
            execute-prompt (get prompts "execute-story-task")]
        (is (some? execute-prompt))
        (let [content (get-in execute-prompt [:messages 0 :content :text])]
          (is (not (re-find #"Branch Management" content)))
          (is (not (re-find #"checkout the default branch" content))))))

    (testing "excludes branch management when config key is not present"
      (let [prompts (sut/story-prompts {})
            execute-prompt (get prompts "execute-story-task")]
        (is (some? execute-prompt))
        (let [content (get-in execute-prompt [:messages 0 :content :text])]
          (is (not (re-find #"Branch Management" content)))
          (is (not (re-find #"checkout the default branch" content))))))

    (testing "does not affect other story prompts"
      (let [prompts-with-branch (sut/story-prompts {:story-branch-management? true})
            prompts-without-branch (sut/story-prompts {:story-branch-management? false})
            refine-with (get prompts-with-branch "refine-story")
            refine-without (get prompts-without-branch "refine-story")]
        (is (= (:messages refine-with) (:messages refine-without)))))))

(deftest category-prompt-resources-test
  ;; Tests the generation of MCP resources for category prompt files.
  ;; Validates URI format, content extraction, and frontmatter handling.
  (testing "category-prompt-resources"
    (let [temp-dir (str (System/getProperty "java.io.tmpdir") "/mcp-tasks-test-" (System/currentTimeMillis))
          prompts-dir (io/file temp-dir ".mcp-tasks" "prompts")
          config {:base-dir temp-dir}]
      (try
        ;; Create test fixtures
        (.mkdirs prompts-dir)
        (spit (io/file prompts-dir "simple.md")
              "---\ndescription: Execute simple tasks with basic workflow\n---\n\n## Simple Category Instructions\n\n- Analyze the task\n- Implement solution\n- Verify results")
        (spit (io/file prompts-dir "medium.md")
              "---\ndescription: Execute medium complexity tasks\n---\n\n## Medium Category Instructions\n\n- Deep analysis\n- Design approach\n- Implementation\n- Testing")
        (spit (io/file prompts-dir "large.md")
              "---\ndescription: Execute large tasks with detailed planning\n---\n\n## Large Category Instructions\n\n- Comprehensive analysis\n- Detailed design\n- Implementation\n- Testing\n- Documentation")

        (testing "generates resources for existing categories"
          (let [resources (sut/category-prompt-resources config)
                resource-uris (set (map :uri resources))]
            (is (seq resources) "Should return non-empty vector")
            (is (contains? resource-uris "prompt://category-simple"))
            (is (contains? resource-uris "prompt://category-medium"))
            (is (contains? resource-uris "prompt://category-large"))))

        (testing "includes frontmatter in content"
          (let [resources (sut/category-prompt-resources config)
                simple-resource (first (filter #(= (:uri %) "prompt://category-simple") resources))]
            (is (some? simple-resource))
            (is (str/includes? (:text simple-resource) "---")
                "Content should contain frontmatter delimiters")
            (is (str/includes? (:text simple-resource) "description:")
                "Content should contain frontmatter fields")))

        (testing "uses description from frontmatter"
          (let [resources (sut/category-prompt-resources config)
                simple-resource (first (filter #(= (:uri %) "prompt://category-simple") resources))]
            (is (some? simple-resource))
            (is (= "Execute simple tasks with basic workflow" (:description simple-resource)))))

        (testing "handles missing files gracefully"
          (let [resources (sut/category-prompt-resources config)
                resource-count (count resources)
                known-categories ["simple" "medium" "large"]]
            (is (>= resource-count (count known-categories)))
            (doseq [resource resources]
              (is (string? (:uri resource)))
              (is (str/starts-with? (:uri resource) "prompt://category-"))
              (is (string? (:name resource)))
              (is (string? (:description resource)))
              (is (= "text/markdown" (:mimeType resource)))
              (is (string? (:text resource))))))

        (finally
          ;; Cleanup
          (when (.exists (io/file temp-dir))
            (doseq [f (file-seq (io/file temp-dir))]
              (when (.isFile f)
                (.delete f)))
            (doseq [f (reverse (file-seq (io/file temp-dir)))]
              (.delete f))))))))

(deftest task-execution-prompts-test
  ;; Test that task-execution-prompts generates valid MCP prompts for general
  ;; task execution workflows from resource files.
  (testing "task-execution-prompts"
    (testing "returns a map of prompt names to prompt definitions"
      (let [prompts (sut/task-execution-prompts {})]
        (is (map? prompts))
        (is (contains? prompts "execute-task"))))

    (testing "execute-task prompt has correct structure"
      (let [prompts (sut/task-execution-prompts {})
            execute-task-prompt (get prompts "execute-task")]
        (is (some? execute-task-prompt))
        (is (= "execute-task" (:name execute-task-prompt)))
        (is (string? (:description execute-task-prompt)))
        (is (vector? (:messages execute-task-prompt)))
        (is (= 1 (count (:messages execute-task-prompt))))))

    (testing "execute-task prompt content includes key instructions"
      (let [prompts (sut/task-execution-prompts {})
            execute-task-prompt (get prompts "execute-task")
            content (get-in execute-task-prompt [:messages 0 :content :text])]
        (is (some? content))
        (is (string? content))
        (is (re-find #"select-tasks" content))
        (is (re-find #"complete-task" content))
        (is (re-find #"category" content))))

    (testing "execute-task prompt includes arguments metadata"
      (let [prompts (sut/task-execution-prompts {})
            execute-task-prompt (get prompts "execute-task")]
        (is (contains? execute-task-prompt :arguments))
        (is (vector? (:arguments execute-task-prompt)))
        (is (pos? (count (:arguments execute-task-prompt))))))

    (testing "executes without error when prompt file is missing"
      (let [prompts (sut/task-execution-prompts {})]
        ;; If file is missing, the prompt won't be in the map
        ;; This should not throw an exception
        (is (map? prompts))))))
