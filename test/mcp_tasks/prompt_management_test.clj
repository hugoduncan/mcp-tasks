(ns mcp-tasks.prompt-management-test
  (:require
    [babashka.fs :as fs]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-tasks.prompt-management :as sut]))

(def test-base-dir "test-resources/prompt-management-test")

(defn- cleanup-fixtures
  []
  (when (fs/exists? test-base-dir)
    (fs/delete-tree test-base-dir))
  (when (fs/exists? ".mcp-tasks")
    (fs/delete-tree ".mcp-tasks")))

(defn- test-fixture
  [f]
  (cleanup-fixtures)
  (try
    (f)
    (finally
      (cleanup-fixtures))))

(use-fixtures :each test-fixture)

(deftest get-prompt-vars-discovers-all-prompts
  ;; Test that get-prompt-vars discovers prompts from all sources:
  ;; task-prompts, story-prompts, and resources/prompts
  (testing "get-prompt-vars"
    (testing "returns sequence of prompt maps"
      (let [prompts (sut/get-prompt-vars)]
        (is (seq prompts))
        (is (every? map? prompts))
        (is (every? #(contains? % :name) prompts))
        (is (every? #(contains? % :content) prompts))))

    (testing "discovers category prompts from task-prompts namespace"
      (let [prompts (sut/get-prompt-vars)
            prompt-names (map :name prompts)]
        (is (some #{"simple"} prompt-names))
        (is (some #{"medium"} prompt-names))
        (is (some #{"large"} prompt-names))))

    (testing "discovers workflow prompts from resources/prompts"
      (let [prompts (sut/get-prompt-vars)
            prompt-names (map :name prompts)]
        (is (some #{"execute-task"} prompt-names))
        (is (some #{"refine-task"} prompt-names))
        (is (some #{"create-story-tasks"} prompt-names))))))

(deftest list-builtin-categories-returns-category-names
  ;; Test that list-builtin-categories returns the expected set of
  ;; category names from resources/category-prompts
  (testing "list-builtin-categories"
    (testing "returns set of category names"
      (let [categories (sut/list-builtin-categories)]
        (is (set? categories))
        (is (seq categories))))

    (testing "includes known category prompts"
      (let [categories (sut/list-builtin-categories)]
        (is (contains? categories "simple"))
        (is (contains? categories "medium"))
        (is (contains? categories "large"))
        (is (contains? categories "clarify-task"))))

    (testing "does not include workflow prompts"
      (let [categories (sut/list-builtin-categories)]
        (is (not (contains? categories "execute-task")))
        (is (not (contains? categories "refine-task")))))))

(deftest list-available-prompts-with-type-detection
  ;; Test that list-available-prompts returns prompts with correct type
  ;; detection based on builtin categories
  (testing "list-available-prompts"
    (testing "returns sequence of prompt metadata"
      (let [prompts (sut/list-available-prompts)]
        (is (seq prompts))
        (is (every? map? prompts))
        (is (every? #(contains? % :name) prompts))
        (is (every? #(contains? % :type) prompts))
        (is (every? #(contains? % :description) prompts))))

    (testing "assigns :category type to category prompts"
      (let [prompts (sut/list-available-prompts)
            simple-prompt (first (filter #(= "simple" (:name %)) prompts))]
        (is (some? simple-prompt))
        (is (= :category (:type simple-prompt)))))

    (testing "assigns :workflow type to workflow prompts"
      (let [prompts (sut/list-available-prompts)
            execute-task-prompt (first (filter #(= "execute-task" (:name %)) prompts))]
        (is (some? execute-task-prompt))
        (is (= :workflow (:type execute-task-prompt)))))

    (testing "includes description for all prompts"
      (let [prompts (sut/list-available-prompts)]
        (is (every? #(string? (:description %)) prompts))
        (is (every? #(seq (:description %)) prompts))))))

(deftest install-prompt-to-correct-directory
  ;; Test that install-prompt installs category and workflow prompts to
  ;; the correct directories based on type detection
  (testing "install-prompt"
    (testing "installs category prompt to category-prompts directory"
      (let [result (sut/install-prompt "simple")]
        (is (= "simple" (:name result)))
        (is (= :category (:type result)))
        (is (= :installed (:status result)))
        (is (= ".mcp-tasks/category-prompts/simple.md" (:path result)))
        (is (fs/exists? ".mcp-tasks/category-prompts/simple.md"))
        (let [content (slurp ".mcp-tasks/category-prompts/simple.md")]
          (is (string? content))
          (is (pos? (count content))))))

    (testing "installs workflow prompt to prompt-overrides directory"
      (let [result (sut/install-prompt "execute-task")]
        (is (= "execute-task" (:name result)))
        (is (= :workflow (:type result)))
        (is (= :installed (:status result)))
        (is (= ".mcp-tasks/prompt-overrides/execute-task.md" (:path result)))
        (is (fs/exists? ".mcp-tasks/prompt-overrides/execute-task.md"))
        (let [content (slurp ".mcp-tasks/prompt-overrides/execute-task.md")]
          (is (string? content))
          (is (pos? (count content))))))))

(deftest install-prompt-handles-existing-files
  ;; Test that install-prompt correctly handles the case where the
  ;; target file already exists
  (testing "install-prompt"
    (testing "returns :exists status when file already exists"
      (fs/create-dirs ".mcp-tasks/category-prompts")
      (spit ".mcp-tasks/category-prompts/simple.md" "existing content")
      (let [result (sut/install-prompt "simple")]
        (is (= "simple" (:name result)))
        (is (= :category (:type result)))
        (is (= :exists (:status result)))
        (is (= ".mcp-tasks/category-prompts/simple.md" (:path result)))
        (is (= "existing content" (slurp ".mcp-tasks/category-prompts/simple.md")))))))

(deftest install-prompt-handles-missing-prompts
  ;; Test that install-prompt correctly handles the case where the
  ;; requested prompt does not exist
  (testing "install-prompt"
    (testing "returns :not-found status for non-existent prompt"
      (let [result (sut/install-prompt "nonexistent-prompt")]
        (is (= "nonexistent-prompt" (:name result)))
        (is (nil? (:type result)))
        (is (= :not-found (:status result)))
        (is (not (contains? result :path)))))))

;; Note: IO error testing is omitted as it's difficult to reliably reproduce
;; file system errors in a portable way. The implementation handles errors
;; by catching exceptions and returning {:status :error :error message}.

(deftest list-available-prompts-returns-consistent-count
  ;; Test that list-available-prompts returns a consistent count of
  ;; prompts across multiple calls
  (testing "list-available-prompts"
    (testing "returns same count on repeated calls"
      (let [prompts1 (sut/list-available-prompts)
            prompts2 (sut/list-available-prompts)]
        (is (= (count prompts1) (count prompts2)))))))

(deftest install-prompt-type-detection-matches-list
  ;; Test that install-prompt's type detection matches the type assigned
  ;; by list-available-prompts
  (testing "install-prompt type detection"
    (testing "matches list-available-prompts for each prompt"
      (let [prompts (sut/list-available-prompts)]
        (doseq [prompt (take 5 prompts)]
          (let [install-result (sut/install-prompt (:name prompt))]
            (is (or (= (:type prompt) (:type install-result))
                    (= :exists (:status install-result)))
                (str "Type mismatch for prompt: " (:name prompt)))))))))
