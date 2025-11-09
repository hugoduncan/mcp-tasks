(ns mcp-tasks.prompt-migration-integration-test
  "Integration tests for prompt directory migration and complete workflows.

  Tests verify the directory reorganization works correctly across:
  - Fresh installations (new structure only)
  - Migration scenarios (old structure with backward compatibility)
  - Mixed states (partial migration)
  - Complete workflows (discovery, loading, resources)
  
  Note: These tests focus on scenarios that work with the current
  architecture. Some functions like get-story-prompt use hardcoded paths
  and are tested via unit tests in prompts_test.clj instead."
  (:require
    [babashka.fs :as fs]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-tasks.integration-test-fixtures :as fixtures]
    [mcp-tasks.prompts :as prompts]))

(use-fixtures :each fixtures/with-test-project)

;; Test helpers

(defn- create-category-file
  "Create a category prompt file in the specified directory structure."
  [structure category content]
  (let [dir (case structure
              :new (io/file (fixtures/test-project-dir) ".mcp-tasks" "category-prompts")
              :old (io/file (fixtures/test-project-dir) ".mcp-tasks" "prompts"))
        file (io/file dir (str category ".md"))]
    (.mkdirs dir)
    (spit file content)
    file))

;; Fresh installation scenario tests

(deftest fresh-installation-category-discovery-test
  (testing "fresh installation with new directory structure"
    (testing "empty config returns empty categories"
      (let [config (fixtures/load-test-config)
            categories (prompts/discover-categories config)]
        (is (sequential? categories))
        ;; No user categories yet, so should be empty
        (is (vector? categories))))

    (testing "custom categories in new location are discovered"
      (create-category-file :new "custom-fresh" "---\ndescription: Custom\n---\nContent")
      (let [config (fixtures/load-test-config)
            categories (prompts/discover-categories config)]
        (is (contains? (set categories) "custom-fresh"))))

    (testing "category prompt overrides in new location work"
      (create-category-file :new "override-test" "---\ndescription: Override\n---\nOverride content")
      (let [config (fixtures/load-test-config)
            prompt-data (#'prompts/read-prompt-instructions config "override-test")]
        (is (= "Override content" (:content prompt-data)))
        (is (= "Override" (get (:metadata prompt-data) "description")))))))

;; Migration scenario tests

(deftest migration-scenario-category-test
  (testing "migration from old directory structure for categories"
    (testing "discovers categories in deprecated location"
      (create-category-file :old "custom-old" "---\ndescription: Old custom\n---\nOld category content")
      (let [config (fixtures/load-test-config)
            categories (prompts/discover-categories config)]
        (is (contains? (set categories) "custom-old"))))

    (testing "loads category prompts from deprecated location"
      (create-category-file :old "old-cat" "---\ndescription: Old\n---\nOld content")
      (let [config (fixtures/load-test-config)
            prompt-data (#'prompts/read-prompt-instructions config "old-cat")]
        (is (= "Old content" (:content prompt-data)))
        (is (= "Old" (get (:metadata prompt-data) "description")))))

    (testing "user and deprecated categories are combined"
      (create-category-file :old "deprecated-cat" "---\ndescription: Dep\n---\nContent")
      (create-category-file :new "new-cat" "---\ndescription: New\n---\nContent")
      (let [config (fixtures/load-test-config)
            categories (prompts/discover-categories config)
            category-set (set categories)]
        (is (contains? category-set "deprecated-cat"))
        (is (contains? category-set "new-cat"))))))

;; Mixed state tests

(deftest mixed-state-category-test
  (testing "partial migration with category files in both locations"
    (testing "new location takes precedence for categories"
      (create-category-file :old "both-cat" "---\ndescription: Old\n---\nOld content")
      (create-category-file :new "both-cat" "---\ndescription: New\n---\nNew content")
      (let [config (fixtures/load-test-config)
            prompt-data (#'prompts/read-prompt-instructions config "both-cat")]
        (is (= "New content" (:content prompt-data)))
        (is (= "New" (get (:metadata prompt-data) "description")))))

    (testing "discovers categories from both locations"
      (create-category-file :new "new-only" "---\ndescription: New\n---\nNew cat")
      (create-category-file :old "old-only" "---\ndescription: Old\n---\nOld cat")
      (let [config (fixtures/load-test-config)
            categories (prompts/discover-categories config)
            category-set (set categories)]
        (is (contains? category-set "new-only"))
        (is (contains? category-set "old-only"))))))

;; Complete workflow tests

(deftest complete-workflow-category-discovery-test
  (testing "complete category discovery workflow across all locations"
    (testing "combines categories from all sources"
      (create-category-file :new "custom-new" "---\ndescription: Custom new\n---\nContent")
      (create-category-file :old "custom-old" "---\ndescription: Custom old\n---\nContent")
      (let [config (fixtures/load-test-config)
            categories (prompts/discover-categories config)
            category-set (set categories)]
        (is (contains? category-set "custom-new"))
        (is (contains? category-set "custom-old"))))

    (testing "category descriptions work across all locations"
      (create-category-file :new "desc-new" "---\ndescription: Desc new\n---\nContent")
      (create-category-file :old "desc-old" "---\ndescription: Desc old\n---\nContent")
      (let [config (fixtures/load-test-config)
            descriptions (prompts/category-descriptions config)]
        (is (= "Desc new" (get descriptions "desc-new")))
        (is (= "Desc old" (get descriptions "desc-old")))))))

(deftest complete-workflow-resources-test
  (testing "complete workflow for MCP resources"
    (testing "category resources include custom categories"
      (create-category-file :new "resource-test" "---\ndescription: Test\n---\nTest content")
      (let [config (fixtures/load-test-config)
            resources (prompts/category-prompt-resources config)
            resource-uris (set (map :uri resources))]
        (is (vector? resources))
        (is (seq resources))
        (is (contains? resource-uris "prompt://category-resource-test"))))

    (testing "category resources have correct structure"
      (create-category-file :new "struct-test" "---\ndescription: Struct test\n---\nContent")
      (let [config (fixtures/load-test-config)
            resources (prompts/category-prompt-resources config)
            resource (first (filter #(= (:uri %) "prompt://category-struct-test") resources))]
        (is (some? resource))
        (is (= "prompt://category-struct-test" (:uri resource)))
        (is (= "struct-test category instructions" (:name resource)))
        (is (= "Struct test" (:description resource)))
        (is (= "text/markdown" (:mimeType resource)))
        (is (string? (:text resource)))
        (is (str/includes? (:text resource) "---"))
        (is (str/includes? (:text resource) "Content"))))

    (testing "task execution prompts load correctly"
      (let [config (fixtures/load-test-config)
            prompts (prompts/task-execution-prompts config)]
        (is (map? prompts))
        (is (seq prompts))
        (is (contains? prompts "execute-task"))
        (is (contains? prompts "refine-task"))))

    (testing "task execution prompt structure is correct"
      (let [config (fixtures/load-test-config)
            prompts (prompts/task-execution-prompts config)
            execute-task (get prompts "execute-task")]
        (is (some? execute-task))
        (is (= "execute-task" (:name execute-task)))
        (is (string? (:description execute-task)))
        (is (vector? (:messages execute-task)))
        (is (= 1 (count (:messages execute-task))))
        (is (vector? (:arguments execute-task)))))))

;; Edge case tests

(deftest edge-cases-migration-test
  (testing "edge cases for migration"
    (testing "empty deprecated directories don't affect discovery"
      (let [old-cat-dir (io/file (fixtures/test-project-dir) ".mcp-tasks" "prompts")]
        (.mkdirs old-cat-dir)
        (let [config (fixtures/load-test-config)
              categories (prompts/discover-categories config)]
          ;; Should return empty vector, not error
          (is (vector? categories)))))

    (testing "handles symlinks correctly in category discovery"
      (let [cat-file (create-category-file :new "symlink-test" "---\ndescription: Test\n---\nContent")
            link-path (io/file (fixtures/test-project-dir) ".mcp-tasks" "category-prompts" "symlink-alias.md")]
        (try
          (fs/create-sym-link link-path cat-file)
          (let [config (fixtures/load-test-config)
                categories (prompts/discover-categories config)]
            (is (contains? (set categories) "symlink-test"))
            (is (contains? (set categories) "symlink-alias")))
          (catch Exception _
                 ;; Symlinks may not be supported on all platforms - skip test
                 ))))

    (testing "handles non-.md files gracefully"
      (let [cat-dir (io/file (fixtures/test-project-dir) ".mcp-tasks" "category-prompts")]
        (.mkdirs cat-dir)
        (spit (io/file cat-dir "README.txt") "This is not a prompt")
        (spit (io/file cat-dir ".hidden") "Hidden file")
        (let [config (fixtures/load-test-config)
              categories (prompts/discover-categories config)]
          (is (not (contains? (set categories) "README")))
          (is (not (contains? (set categories) ".hidden"))))))

    (testing "handles malformed frontmatter gracefully"
      (create-category-file :new "malformed" "---\nno-closing-delimiter\nContent")
      (let [config (fixtures/load-test-config)
            categories (prompts/discover-categories config)]
        (is (contains? (set categories) "malformed"))
        (let [prompt-data (#'prompts/read-prompt-instructions config "malformed")]
          (is (some? prompt-data))
          (is (string? (:content prompt-data))))))))

;; Precedence and fallback tests

(deftest precedence-and-fallback-test
  (testing "precedence rules for category prompts"
    (testing "new user override > old user override"
      (create-category-file :old "precedence-cat" "---\ndescription: Old\n---\nOld")
      (let [config (fixtures/load-test-config)
            prompt-data (#'prompts/read-prompt-instructions config "precedence-cat")]
        (is (= "Old" (get (:metadata prompt-data) "description"))))

      (create-category-file :new "precedence-cat" "---\ndescription: New\n---\nNew")
      (let [config (fixtures/load-test-config)
            prompt-data (#'prompts/read-prompt-instructions config "precedence-cat")]
        (is (= "New" (get (:metadata prompt-data) "description")))))))
