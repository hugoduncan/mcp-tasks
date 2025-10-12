(ns mcp-tasks.prompts-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.prompts :as sut]))

(deftest discover-categories-test
  ;; Test that discover-categories finds all unique categories across
  ;; tasks, complete, and prompts subdirectories, returning them sorted
  ;; without .md extensions.
  (testing "discover-categories"
    (testing "returns sorted unique categories from .mcp-tasks subdirectories"
      (let [categories (sut/discover-categories)]
        (is (vector? categories))
        (is (every? string? categories))
        (is (= categories (sort categories)))
        (is (not-any? #(re-find #"\.md$" %) categories))))

    (testing "finds categories across all subdirectories"
      (let [categories (sut/discover-categories)
            category-set (set categories)]
        ;; Should find "simple" which exists in both tasks and complete
        (is (contains? category-set "simple"))))

    (testing "returns unique categories when files exist in multiple subdirectories"
      (let [categories (sut/discover-categories)]
        ;; "simple" exists in both tasks and complete subdirectories
        ;; but should only appear once in the result
        (is (= 1 (count (filter #(= "simple" %) categories))))))))

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
        (is (re-find #"\.mcp-tasks/tasks/nonexistent\.md" message-text))))

    (testing "generates prompts with proper category substitution"
      (let [prompts (sut/create-prompts {:use-git? true} ["simple"])
            prompt (first prompts)
            message-text (get-in prompt [:messages 0 :content :text])]
        (is (= "next-simple" (:name prompt)))
        (is (re-find #"simple task" message-text))
        (is (re-find #"\.mcp-tasks/tasks/simple\.md" message-text))
        (is (re-find #"\.mcp-tasks/complete/simple\.md" message-text))))

    (testing "uses metadata description when available"
      (let [prompts (sut/create-prompts {:use-git? true} ["simple"])
            prompt (first prompts)]
        (is (= "next-simple" (:name prompt)))
        (is (= "Execute simple tasks with basic workflow" (:description prompt)))))

    (testing "uses default description when no metadata"
      (let [prompts (sut/create-prompts {:use-git? true} ["nonexistent"])
            prompt (first prompts)]
        (is (= "next-nonexistent" (:name prompt)))
        (is (= "Process the next incomplete task from .mcp-tasks/tasks/nonexistent.md"
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
        (is (re-find #"\.mcp-tasks/tasks/simple\.md" text))
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
        (is (re-find #"\.mcp-tasks/complete/simple\.md" text))
        (is (re-find #"\.mcp-tasks/tasks/simple\.md" text))
        (is (re-find #"Commit the task tracking changes in the \.mcp-tasks git repository" text))))

    (testing "omits git instructions when use-git? is false"
      (let [text (#'sut/complete-task-prompt-text {:use-git? false} "simple")]
        (is (string? text))
        (is (re-find #"\.mcp-tasks/complete/simple\.md" text))
        (is (re-find #"\.mcp-tasks/tasks/simple\.md" text))
        (is (not (re-find #"Commit" text)))
        (is (not (re-find #"git" text)))))

    (testing "includes file operation instructions in both modes"
      (let [text-git (#'sut/complete-task-prompt-text {:use-git? true} "simple")
            text-no-git (#'sut/complete-task-prompt-text {:use-git? false} "simple")]
        (is (re-find #"Move the completed task" text-git))
        (is (re-find #"Remove the task" text-git))
        (is (re-find #"Move the completed task" text-no-git))
        (is (re-find #"Remove the task" text-no-git))))))
