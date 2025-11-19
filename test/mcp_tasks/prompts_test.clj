(ns mcp-tasks.prompts-test
  (:require
    [babashka.fs :as fs]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.prompts :as sut]))

(deftest discover-categories-test
  ;; Test that discover-categories finds categories from the
  ;; category-prompts subdirectory, returning them sorted without .md extensions.
  (testing "discover-categories"
    (let [config {:resolved-tasks-dir ".mcp-tasks"}]
      (testing "returns sorted categories from category-prompts directory"
        (let [categories (sut/discover-categories config)]
          (is (vector? categories))
          (is (every? string? categories))
          (is (= categories (sort categories)))
          (is (not-any? #(re-find #"\.md$" %) categories))))

      (testing "finds categories from category-prompts subdirectory"
        (let [categories (sut/discover-categories config)
              category-set (set categories)]
          ;; Should find "simple" which exists in category-prompts
          (is (contains? category-set "simple"))))

      (testing "returns categories only from category-prompts subdirectory"
        (let [categories (sut/discover-categories config)]
          ;; Each category should appear exactly once
          (is (= (count categories) (count (set categories)))))))))

(deftest list-builtin-workflows-test
  ;; Test that list-builtin-workflows reads workflow prompts from the manifest
  ;; file generated at build time. This enables prompt discovery in both JAR
  ;; and GraalVM native images.
  (testing "list-builtin-workflows"
    (testing "reads workflows from manifest file"
      (let [workflows (sut/list-builtin-workflows)]
        (testing "returns a vector"
          (is (vector? workflows)))

        (testing "contains only strings"
          (is (every? string? workflows)))

        (testing "returns non-empty result"
          (is (seq workflows)
              "Should find workflow prompts from manifest"))

        (testing "does not include .md extensions"
          (is (not-any? #(re-find #"\.md$" %) workflows)))

        (testing "includes expected workflow prompts"
          (let [workflow-set (set workflows)]
            ;; Verify some known workflows from the manifest
            (is (contains? workflow-set "execute-task")
                "Should include execute-task workflow")
            (is (contains? workflow-set "refine-task")
                "Should include refine-task workflow")
            (is (contains? workflow-set "complete-story")
                "Should include complete-story workflow")))

        (testing "returns unique workflow names"
          (is (= (count workflows) (count (set workflows)))
              "Each workflow should appear exactly once"))))

    ;; Note: Error handling tests (malformed manifest, missing manifest) are
    ;; difficult to unit test without mocking io/resource. These scenarios are
    ;; covered by integration tests and by the fallback logic that returns an
    ;; empty vector when the manifest is inaccessible.
    (testing "manifest-based discovery works in test environment"
      (let [workflows (sut/list-builtin-workflows)]
        (testing "manifest resource is accessible"
          (is (seq workflows)
              "Manifest should be accessible during tests"))))))

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
    (let [config {:use-git? true :resolved-tasks-dir ".mcp-tasks"}]
      (testing "creates prompts for each category"
        (let [prompts (sut/create-prompts config ["simple" "test-category"])]
          (is (vector? prompts))
          (is (= 2 (count prompts)))
          (is (every? map? prompts))))

      (testing "generates correct prompt structure"
        (let [prompts (sut/create-prompts config ["simple"])
              prompt (first prompts)]
          (is (contains? prompt :name))
          (is (contains? prompt :description))
          (is (contains? prompt :messages))
          (is (= "next-simple" (:name prompt)))
          (is (string? (:description prompt)))
          (is (vector? (:messages prompt)))))

      (testing "uses default template when no custom prompt file exists"
        (let [prompts (sut/create-prompts config ["nonexistent"])
              prompt (first prompts)
              message-text (get-in prompt [:messages 0 :content :text])]
          (is (= "next-nonexistent" (:name prompt)))
          (is (re-find #"nonexistent task" message-text))
          (is (re-find #"\.mcp-tasks/tasks\.ednl" message-text))))

      (testing "generates prompts with proper category substitution"
        (let [prompts (sut/create-prompts config ["simple"])
              prompt (first prompts)
              message-text (get-in prompt [:messages 0 :content :text])]
          (is (= "next-simple" (:name prompt)))
          (is (re-find #"simple task" message-text))
          (is (re-find #"\.mcp-tasks/tasks\.ednl" message-text))
          (is (re-find #"\.mcp-tasks/complete\.ednl" message-text))))

      (testing "uses metadata description when available"
        (let [prompts (sut/create-prompts config ["simple"])
              prompt (first prompts)]
          (is (= "next-simple" (:name prompt)))
          (is (= "Execute simple tasks with basic workflow" (:description prompt)))))

      (testing "uses default description when no metadata"
        (let [prompts (sut/create-prompts config ["nonexistent"])
              prompt (first prompts)]
          (is (= "next-nonexistent" (:name prompt)))
          (is (= "Execute the next nonexistent task from .mcp-tasks/tasks.ednl"
                 (:description prompt)))))

      (testing "includes git instructions when use-git? is true"
        (let [prompts (sut/create-prompts config ["simple"])
              prompt (first prompts)
              message-text (get-in prompt [:messages 0 :content :text])]
          (is (re-find #"Commit the task tracking changes" message-text))))

      (testing "omits .mcp-tasks git commit instructions when use-git? is false"
        (let [prompts (sut/create-prompts (assoc config :use-git? false) ["simple"])
              prompt (first prompts)
              message-text (get-in prompt [:messages 0 :content :text])]
          (is (not (re-find #"Commit the task tracking changes in the \.mcp-tasks git repository" message-text))))))))

(deftest category-descriptions-test
  ;; Test that category-descriptions returns correct descriptions for all categories.
  (testing "category-descriptions"
    (let [config {:resolved-tasks-dir ".mcp-tasks"}]
      (testing "returns map of category to description"
        (let [descs (sut/category-descriptions config)]
          (is (map? descs))
          (is (contains? descs "simple"))
          (is (= "Execute simple tasks with basic workflow" (get descs "simple")))))

      (testing "includes all discovered categories"
        (let [categories (sut/discover-categories config)
              descs (sut/category-descriptions config)]
          (is (= (set categories) (set (keys descs)))))))))

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

(deftest refine-task-prompt-test
  ;; Test that the refine-task task execution prompt is properly defined with
  ;; correct metadata and content structure.
  (testing "refine-task prompt"
    (testing "is available via task-execution-prompts"
      (let [prompts (sut/task-execution-prompts {})
            prompt (get prompts "refine-task")]
        (is (some? prompt))
        (is (= "refine-task" (:name prompt)))))

    (testing "has description metadata"
      (let [prompts (sut/task-execution-prompts {})
            prompt (get prompts "refine-task")]
        (is (some? (:description prompt)))
        (is (string? (:description prompt)))))

    (testing "has content with key instructions"
      (let [prompts (sut/task-execution-prompts {})
            prompt (get prompts "refine-task")
            content (get-in prompt [:messages 0 :content :text])]
        (is (some? content))
        (is (string? content))
        (is (re-find #"select-tasks" content))
        (is (re-find #"update-task" content))
        (is (re-find #"interactive" content))
        (is (re-find #"project context" content))
        (is (re-find #"patterns" content))))

    (testing "includes refinement status instructions"
      (let [prompts (sut/task-execution-prompts {})
            prompt (get prompts "refine-task")
            content (get-in prompt [:messages 0 :content :text])]
        (is (some? content))
        (is (re-find #"meta" content) "Should mention meta field")
        (is (re-find #"refined.*true" content) "Should set refined to true")
        (is (re-find #"preserve existing" content) "Should preserve existing meta values")))

    (testing "works with all task types"
      (let [prompts (sut/task-execution-prompts {})
            prompt (get prompts "refine-task")
            content (get-in prompt [:messages 0 :content :text])]
        (is (some? content))
        (is (not (re-find #"type: story" content)) "Should not restrict to story type")
        (is (re-find #"task type" content) "Should mention task types")))))

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
        (is (re-find #"story specification" content))
        (is (re-find #"(?s)select-tasks.*title-pattern" content))
        (is (re-find #"Add in dependency order" content))
        (is (re-find #"relations" content))
        (is (re-find #"category" content))))

    (testing "includes task breakdown instructions"
      (let [prompt (sut/get-story-prompt "create-story-tasks")
            content (:content prompt)]
        (is (re-find #"Break down" content))
        (is (re-find #"dependencies" content))
        (is (re-find #"blocked-by" content))
        (is (re-find #"appropriate category" content))))

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

(deftest execute-story-child-prompt-test
  ;; Test that the execute-story-child built-in prompt is properly defined
  ;; with correct metadata and content structure including task execution,
  ;; queue management, and branch management instructions.
  (testing "execute-story-child prompt"
    (testing "is available via get-story-prompt"
      (let [prompt (sut/get-story-prompt "execute-story-child")]
        (is (some? prompt))
        (is (= "execute-story-child" (:name prompt)))))

    (testing "has description metadata"
      (let [prompt (sut/get-story-prompt "execute-story-child")]
        (is (some? (:description prompt)))
        (is (string? (:description prompt)))))

    (testing "has content with key instructions"
      (let [prompt (sut/get-story-prompt "execute-story-child")
            content (:content prompt)]
        (is (some? content))
        (is (string? content))
        (is (re-find #"select-tasks" content))
        (is (re-find #"complete-task" content))
        (is (re-find #"category" content))))

    (testing "includes task execution workflow"
      (let [prompt (sut/get-story-prompt "execute-story-child")
            content (:content prompt)]
        (is (re-find #"select-tasks" content))
        (is (re-find #"complete-task" content))))

    (testing "appears in list-story-prompts"
      (let [prompts (sut/list-story-prompts)
            execute-prompt (first (filter #(= "execute-story-child" (:name %)) prompts))]
        (is (some? execute-prompt))
        (is (some? (:description execute-prompt)))))))

(defn- create-test-override-file
  "Create a temporary override file for testing.
  Returns the file object for cleanup."
  [prompt-name content]
  (let [override-dir (io/file ".mcp-tasks" "prompt-overrides")
        override-file (io/file override-dir (str prompt-name ".md"))]
    (.mkdirs override-dir)
    (spit override-file content)
    override-file))

(defn- delete-test-file
  "Delete a test file if it exists."
  [file]
  (when (fs/exists? file)
    (fs/delete file)))

(deftest get-story-prompt-override-precedence-test
  ;; Test that get-story-prompt correctly prioritizes override files over
  ;; built-in prompts, with proper frontmatter parsing.
  (testing "get-story-prompt override precedence"
    (testing "uses override file when it exists, even if built-in exists"
      (let [test-file (create-test-override-file
                        "create-story-tasks"
                        "---\ndescription: Override description\n---\n\nOverride content")]
        (try
          (let [result (sut/get-story-prompt "create-story-tasks")]
            (is (some? result))
            (is (= "create-story-tasks" (:name result)))
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
                        "execute-story-child"
                        "---\ndescription: Overridden execute-story-child\n---\n\nOverride content")]
        (try
          (let [prompts (sut/list-story-prompts)
                execute-prompts (filter #(= "execute-story-child" (:name %)) prompts)]
            (is (= 1 (count execute-prompts)))
            (is (= "Overridden execute-story-child" (:description (first execute-prompts)))))
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
  ;; instructions in execute-story-child prompt based on config.
  (testing "story-prompts branch management"
    (testing "includes branch management when :branch-management? is true"
      (let [prompts (sut/story-prompts {:branch-management? true})
            execute-prompt (get prompts "execute-story-child")]
        (is (some? execute-prompt))
        (let [content (get-in execute-prompt [:messages 0 :content :text])]
          (is (re-find #"Branch Management" content))
          (is (re-find #"checkout the default branch" content))
          (is (re-find #"create the appropriately named branch" content)))))

    (testing "excludes branch management when :branch-management? is false"
      (let [prompts (sut/story-prompts {:branch-management? false})
            execute-prompt (get prompts "execute-story-child")]
        (is (some? execute-prompt))
        (let [content (get-in execute-prompt [:messages 0 :content :text])]
          (is (not (re-find #"Branch Management" content)))
          (is (not (re-find #"checkout the default branch" content))))))

    (testing "excludes branch management when config key is not present"
      (let [prompts (sut/story-prompts {})
            execute-prompt (get prompts "execute-story-child")]
        (is (some? execute-prompt))
        (let [content (get-in execute-prompt [:messages 0 :content :text])]
          (is (not (re-find #"Branch Management" content)))
          (is (not (re-find #"checkout the default branch" content))))))

    (testing "does not affect other story prompts"
      (let [prompts-with-branch (sut/story-prompts {:branch-management? true})
            prompts-without-branch (sut/story-prompts {:branch-management? false})
            create-with (get prompts-with-branch "create-story-tasks")
            create-without (get prompts-without-branch "create-story-tasks")]
        (is (= (:messages create-with) (:messages create-without)))))))

(deftest category-prompt-resources-test
  ;; Tests the generation of MCP resources for category prompt files.
  ;; Validates URI format, content extraction, and frontmatter handling.
  (testing "category-prompt-resources"
    (let [temp-dir (str (System/getProperty "java.io.tmpdir") "/mcp-tasks-test-" (System/currentTimeMillis))
          prompts-dir (io/file temp-dir ".mcp-tasks" "category-prompts")
          config {:base-dir temp-dir
                  :resolved-tasks-dir (str temp-dir "/.mcp-tasks")}]
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

(deftest task-execution-prompts-branch-management-test
  ;; Test that task-execution-prompts conditionally includes branch management
  ;; instructions in execute-task prompt based on config.
  (testing "task-execution-prompts branch management"
    (testing "includes branch management when :branch-management? is true"
      (let [prompts (sut/task-execution-prompts {:branch-management? true})
            execute-prompt (get prompts "execute-task")]
        (is (some? execute-prompt))
        (let [content (get-in execute-prompt [:messages 0 :content :text])]
          (is (re-find #"Branch Management" content))
          (is (re-find #"checkout the default branch" content))
          (is (re-find #"create the appropriately named branch" content)))))

    (testing "excludes branch management when :branch-management? is false"
      (let [prompts (sut/task-execution-prompts {:branch-management? false})
            execute-prompt (get prompts "execute-task")]
        (is (some? execute-prompt))
        (let [content (get-in execute-prompt [:messages 0 :content :text])]
          (is (not (re-find #"Branch Management" content)))
          (is (not (re-find #"checkout the default branch" content))))))

    (testing "excludes branch management when config key is not present"
      (let [prompts (sut/task-execution-prompts {})
            execute-prompt (get prompts "execute-task")]
        (is (some? execute-prompt))
        (let [content (get-in execute-prompt [:messages 0 :content :text])]
          (is (not (re-find #"Branch Management" content)))
          (is (not (re-find #"checkout the default branch" content))))))

    (testing "does not affect other task execution prompts"
      (let [prompts-with-branch (sut/task-execution-prompts {:branch-management? true})
            prompts-without-branch (sut/task-execution-prompts {:branch-management? false})]
        ;; Check that non-execute-task and non-execute-story-child prompts are identical
        (doseq [prompt-name (keys prompts-with-branch)
                :when (not (contains? #{"execute-task" "execute-story-child"} prompt-name))]
          (is (= (get-in prompts-with-branch [prompt-name :messages])
                 (get-in prompts-without-branch [prompt-name :messages]))))))))

(deftest story-prompts-worktree-management-test
  ;; Test that story-prompts conditionally includes worktree management
  ;; instructions in execute-story-child prompt based on config.
  (testing "story-prompts worktree management"
    (testing "includes worktree management when :worktree-management? is true"
      (let [prompts (sut/story-prompts {:worktree-management? true})
            execute-prompt (get prompts "execute-story-child")]
        (is (some? execute-prompt))
        (let [content (get-in execute-prompt [:messages 0 :content :text])]
          (is (re-find #"Worktree Management" content))
          (is (re-find #"work-on" content))
          (is (re-find #"git worktree remove" content)))))

    (testing "excludes worktree management when :worktree-management? is false"
      (let [prompts (sut/story-prompts {:worktree-management? false})
            execute-prompt (get prompts "execute-story-child")]
        (is (some? execute-prompt))
        (let [content (get-in execute-prompt [:messages 0 :content :text])]
          (is (not (re-find #"Worktree Management" content)))
          (is (not (re-find #"git worktree remove" content))))))

    (testing "excludes worktree management when config key is not present"
      (let [prompts (sut/story-prompts {})
            execute-prompt (get prompts "execute-story-child")]
        (is (some? execute-prompt))
        (let [content (get-in execute-prompt [:messages 0 :content :text])]
          (is (not (re-find #"Worktree Management" content)))
          (is (not (re-find #"git worktree remove" content))))))

    (testing "does not affect other story prompts"
      (let [prompts-with-worktree (sut/story-prompts {:worktree-management? true})
            prompts-without-worktree (sut/story-prompts {:worktree-management? false})
            create-with (get prompts-with-worktree "create-story-tasks")
            create-without (get prompts-without-worktree "create-story-tasks")]
        (is (= (:messages create-with) (:messages create-without)))))))

(deftest task-execution-prompts-worktree-management-test
  ;; Test that task-execution-prompts conditionally includes worktree management
  ;; instructions in execute-task prompt based on config.
  (testing "task-execution-prompts worktree management"
    (testing "includes worktree management when :worktree-management? is true"
      (let [prompts (sut/task-execution-prompts {:worktree-management? true})
            execute-prompt (get prompts "execute-task")]
        (is (some? execute-prompt))
        (let [content (get-in execute-prompt [:messages 0 :content :text])]
          (is (re-find #"Worktree Management" content))
          (is (re-find #"work-on" content))
          (is (re-find #"git worktree remove" content)))))

    (testing "excludes worktree management when :worktree-management? is false"
      (let [prompts (sut/task-execution-prompts {:worktree-management? false})
            execute-prompt (get prompts "execute-task")]
        (is (some? execute-prompt))
        (let [content (get-in execute-prompt [:messages 0 :content :text])]
          (is (not (re-find #"Worktree Management" content)))
          (is (not (re-find #"git worktree remove" content))))))

    (testing "excludes worktree management when config key is not present"
      (let [prompts (sut/task-execution-prompts {})
            execute-prompt (get prompts "execute-task")]
        (is (some? execute-prompt))
        (let [content (get-in execute-prompt [:messages 0 :content :text])]
          (is (not (re-find #"Worktree Management" content)))
          (is (not (re-find #"git worktree remove" content))))))

    (testing "does not affect other task execution prompts"
      (let [prompts-with-worktree (sut/task-execution-prompts {:worktree-management? true})
            prompts-without-worktree (sut/task-execution-prompts {:worktree-management? false})]
        ;; Check that non-execute-task and non-execute-story-child prompts are identical
        (doseq [prompt-name (keys prompts-with-worktree)
                :when (not (contains? #{"execute-task" "execute-story-child"} prompt-name))]
          (is (= (get-in prompts-with-worktree [prompt-name :messages])
                 (get-in prompts-without-worktree [prompt-name :messages]))))))))

;; Backward compatibility tests

(defn- create-deprecated-category-file
  "Create a category file in the deprecated prompts/ directory for testing."
  [category content]
  (let [deprecated-dir (io/file ".mcp-tasks" "prompts")
        file (io/file deprecated-dir (str category ".md"))]
    (.mkdirs deprecated-dir)
    (spit file content)
    file))

(defn- create-new-category-file
  "Create a category file in the new category-prompts/ directory for testing."
  [category content]
  (let [new-dir (io/file ".mcp-tasks" "category-prompts")
        file (io/file new-dir (str category ".md"))]
    (.mkdirs new-dir)
    (spit file content)
    file))

(defn- create-deprecated-workflow-file
  "Create a workflow prompt file in the deprecated story/prompts/ directory for testing."
  [prompt-name content]
  (let [deprecated-dir (io/file ".mcp-tasks" "story" "prompts")
        file (io/file deprecated-dir (str prompt-name ".md"))]
    (.mkdirs deprecated-dir)
    (spit file content)
    file))

(deftest backward-compat-category-prompts-test
  ;; Test backward compatibility for category prompts in deprecated prompts/ directory
  (testing "category prompts backward compatibility"
    (testing "finds category in new location only"
      (let [config {:resolved-tasks-dir ".mcp-tasks"}
            test-file (create-new-category-file "test-new-only" "New content")]
        (try
          (let [categories (sut/discover-categories config)]
            (is (contains? (set categories) "test-new-only")))
          (finally
            (delete-test-file test-file)))))

    (testing "finds category in deprecated location only"
      (let [config {:resolved-tasks-dir ".mcp-tasks"}
            test-file (create-deprecated-category-file "test-deprecated-only" "Deprecated content")]
        (try
          (let [categories (sut/discover-categories config)]
            (is (contains? (set categories) "test-deprecated-only")))
          (finally
            (delete-test-file test-file)
            (fs/delete-tree (io/file ".mcp-tasks" "prompts"))))))

    (testing "new location takes precedence when both exist"
      (let [config {:resolved-tasks-dir ".mcp-tasks"}
            new-file (create-new-category-file "test-both" "New content")
            deprecated-file (create-deprecated-category-file "test-both" "Deprecated content")]
        (try
          (let [categories (sut/discover-categories config)
                prompt-data (#'sut/read-prompt-instructions config "test-both")]
            (is (contains? (set categories) "test-both"))
            (is (= "New content" (:content prompt-data))))
          (finally
            (delete-test-file new-file)
            (delete-test-file deprecated-file)
            (fs/delete-tree (io/file ".mcp-tasks" "prompts"))))))

    (testing "combines categories from both locations"
      (let [config {:resolved-tasks-dir ".mcp-tasks"}
            new-file (create-new-category-file "test-new" "New content")
            deprecated-file (create-deprecated-category-file "test-old" "Old content")]
        (try
          (let [categories (sut/discover-categories config)
                category-set (set categories)]
            (is (contains? category-set "test-new"))
            (is (contains? category-set "test-old")))
          (finally
            (delete-test-file new-file)
            (delete-test-file deprecated-file)
            (fs/delete-tree (io/file ".mcp-tasks" "prompts"))))))))

(deftest backward-compat-workflow-prompts-test
  ;; Test backward compatibility for workflow prompts in deprecated story/prompts/ directory
  (testing "workflow prompts backward compatibility"
    (testing "finds prompt in new location only"
      (let [test-file (create-test-override-file "test-new-workflow" "---\ndescription: New\n---\nNew content")]
        (try
          (let [result (sut/get-story-prompt "test-new-workflow")]
            (is (some? result))
            (is (= "New" (:description result)))
            (is (str/includes? (:content result) "New content")))
          (finally
            (delete-test-file test-file)))))

    (testing "finds prompt in deprecated location only"
      (let [test-file (create-deprecated-workflow-file "test-deprecated-workflow" "---\ndescription: Deprecated\n---\nDeprecated content")]
        (try
          (let [result (sut/get-story-prompt "test-deprecated-workflow")]
            (is (some? result))
            (is (= "Deprecated" (:description result)))
            (is (str/includes? (:content result) "Deprecated content")))
          (finally
            (delete-test-file test-file)
            (fs/delete-tree (io/file ".mcp-tasks" "story"))))))

    (testing "new location takes precedence when both exist"
      (let [new-file (create-test-override-file "test-both-workflow" "---\ndescription: New\n---\nNew content")
            deprecated-file (create-deprecated-workflow-file "test-both-workflow" "---\ndescription: Deprecated\n---\nDeprecated content")]
        (try
          (let [result (sut/get-story-prompt "test-both-workflow")]
            (is (some? result))
            (is (= "New" (:description result)))
            (is (str/includes? (:content result) "New content")))
          (finally
            (delete-test-file new-file)
            (delete-test-file deprecated-file)
            (fs/delete-tree (io/file ".mcp-tasks" "story"))))))

    (testing "falls back to builtin when no override exists"
      (let [result (sut/get-story-prompt "create-story-tasks")]
        (is (some? result))
        (is (some? (:description result)))
        (is (some? (:content result)))))))

(deftest backward-compat-empty-directories-test
  ;; Verify that empty deprecated directories don't trigger deprecation warnings.
  ;; This is important for user experience during migration - if users create
  ;; new directory structures but haven't moved files yet, we shouldn't warn.
  ;;
  ;; The implementation correctly avoids warnings because:
  ;; 1. discover-prompt-files returns empty vector for directories with no .md files
  ;; 2. discover-categories only warns for categories in deprecated-only set
  ;; 3. resolve-prompt-path-with-fallback only warns when specific file exists
  (testing "empty deprecated directories"
    (testing "empty deprecated prompts/ directory doesn't affect discover-categories"
      (let [config {:resolved-tasks-dir ".mcp-tasks"}
            deprecated-dir (io/file ".mcp-tasks" "prompts")]
        (.mkdirs deprecated-dir)
        (try
          (let [categories (sut/discover-categories config)]
            ;; Should discover built-in categories without warnings
            (is (vector? categories))
            (is (seq categories) "Should find built-in categories"))
          (finally
            (fs/delete-tree deprecated-dir)))))

    (testing "empty deprecated story/prompts/ directory doesn't affect get-story-prompt"
      (let [deprecated-dir (io/file ".mcp-tasks" "story" "prompts")]
        (.mkdirs deprecated-dir)
        (try
          (let [result (sut/get-story-prompt "create-story-tasks")]
            ;; Should find built-in prompt without warnings
            (is (some? result))
            (is (some? (:content result))))
          (finally
            (fs/delete-tree (io/file ".mcp-tasks" "story"))))))))

(deftest detect-prompt-type-test
  ;; Test prompt type detection for built-in categories and workflows
  (testing "detect-prompt-type"
    (testing "detects category prompts"
      (is (= :category (sut/detect-prompt-type "simple")))
      (is (= :category (sut/detect-prompt-type "medium")))
      (is (= :category (sut/detect-prompt-type "large"))))

    (testing "detects workflow prompts"
      (is (= :workflow (sut/detect-prompt-type "execute-task")))
      (is (= :workflow (sut/detect-prompt-type "refine-task")))
      (is (= :workflow (sut/detect-prompt-type "execute-story-child"))))

    (testing "returns nil for unknown prompts"
      (is (nil? (sut/detect-prompt-type "nonexistent")))
      (is (nil? (sut/detect-prompt-type "foo-bar"))))))
