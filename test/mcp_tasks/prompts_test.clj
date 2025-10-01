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

(deftest create-prompts-test
  ;; Test that create-prompts generates valid MCP prompts for categories
  ;; with both custom and default instructions.
  (testing "create-prompts"
    (testing "creates prompts for each category"
      (let [prompts (sut/create-prompts ["simple" "test-category"])]
        (is (vector? prompts))
        (is (= 2 (count prompts)))
        (is (every? map? prompts))))

    (testing "generates correct prompt structure"
      (let [prompts (sut/create-prompts ["simple"])
            prompt (first prompts)]
        (is (contains? prompt :name))
        (is (contains? prompt :description))
        (is (contains? prompt :messages))
        (is (= "next-simple" (:name prompt)))
        (is (string? (:description prompt)))
        (is (vector? (:messages prompt)))))

    (testing "uses default template when no custom prompt file exists"
      (let [prompts (sut/create-prompts ["nonexistent"])
            prompt (first prompts)
            message-text (get-in prompt [:messages 0 :content :text])]
        (is (= "next-nonexistent" (:name prompt)))
        (is (re-find #"nonexistent task" message-text))
        (is (re-find #"\.mcp-tasks/tasks/nonexistent\.md" message-text))))

    (testing "generates prompts with proper category substitution"
      (let [prompts (sut/create-prompts ["simple"])
            prompt (first prompts)
            message-text (get-in prompt [:messages 0 :content :text])]
        (is (= "next-simple" (:name prompt)))
        (is (re-find #"simple task" message-text))
        (is (re-find #"\.mcp-tasks/tasks/simple\.md" message-text))
        (is (re-find #"\.mcp-tasks/complete/simple\.md" message-text))))))
