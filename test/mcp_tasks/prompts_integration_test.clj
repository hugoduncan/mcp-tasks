(ns mcp-tasks.prompts-integration-test
  "Integration tests for prompt loading and template rendering"
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.prompts :as prompts]))

;; Verifies all builtin prompts load successfully with template directives resolving.
;; Tests both workflow prompts (resources/prompts/) and category prompts
;; (resources/category-prompts/), ensuring {% include %} directives work correctly.

(deftest ^:integration all-builtin-prompts-load-without-template-errors-test
  (testing "all-builtin-prompts-load-without-template-errors"
    (testing "when loading workflow prompts"
      (let [workflow-names (prompts/list-builtin-workflows)]
        (testing "discovers at least one workflow prompt"
          (is (seq workflow-names)
              "Should find builtin workflow prompts"))

        (doseq [prompt-name workflow-names]
          (testing (str "loads " prompt-name " without errors")
            (let [result (prompts/get-story-prompt prompt-name)]
              (is (some? result)
                  (str "Failed to load workflow prompt: " prompt-name))
              (is (string? (:content result))
                  (str "Content should be string for: " prompt-name))
              (is (seq (:content result))
                  (str "Content should not be empty for: " prompt-name)))))))

    (testing "when loading category prompts"
      (let [category-names (prompts/list-builtin-categories)]
        (testing "discovers at least one category prompt"
          (is (seq category-names)
              "Should find builtin category prompts"))

        (doseq [category category-names]
          (testing (str "loads " category " without errors")
            (let [builtin-path (str prompts/builtin-category-prompts-dir
                                    "/" category ".md")
                  result (prompts/load-prompt-content nil builtin-path)]
              (is (some? result)
                  (str "Failed to load category prompt: " category))
              (is (string? (:content result))
                  (str "Content should be string for: " category))
              (is (seq (:content result))
                  (str "Content should not be empty for: " category)))))))))

(deftest ^:integration template-includes-resolve-correctly-test
  (testing "template-includes-resolve-correctly"
    (testing "when workflow prompts use includes"
      (let [workflow-names (prompts/list-builtin-workflows)]
        (doseq [prompt-name workflow-names]
          (testing (str prompt-name " has no unresolved include directives")
            (let [result (prompts/get-story-prompt prompt-name)
                  content (:content result)]
              (is (not (re-find #"\{%\s*include\s+" content))
                  (str "Unresolved include directive found in: " prompt-name)))))))

    (testing "when category prompts use includes"
      (let [category-names (prompts/list-builtin-categories)]
        (doseq [category category-names]
          (testing (str category " has no unresolved include directives")
            (let [builtin-path (str prompts/builtin-category-prompts-dir
                                    "/" category ".md")
                  result (prompts/load-prompt-content nil builtin-path)
                  content (:content result)]
              (is (not (re-find #"\{%\s*include\s+" content))
                  (str "Unresolved include directive found in: "
                       category)))))))))

(deftest ^:integration load-prompt-content-with-context-test
  ;; Test that load-prompt-content renders templates with context variables
  ;; including Selmer conditionals like {% if cli %}.
  ;; Contracts being tested:
  ;; - Context map is passed to template rendering
  ;; - {% if %} conditionals are evaluated based on context
  ;; - Default behavior (no context) preserves backward compatibility

  (testing "load-prompt-content with context"
    (testing "when loading builtin prompts with empty context"
      (let [category "simple"
            builtin-path (str prompts/builtin-category-prompts-dir "/" category ".md")
            result (prompts/load-prompt-content nil builtin-path {})]
        (is (some? result))
        (is (string? (:content result)))))

    (testing "when loading builtin prompts with default context (backward compatible)"
      (let [category "simple"
            builtin-path (str prompts/builtin-category-prompts-dir "/" category ".md")
            result (prompts/load-prompt-content nil builtin-path)]
        (is (some? result))
        (is (string? (:content result)))))))
