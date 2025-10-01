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
