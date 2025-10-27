(ns mcp-tasks.util-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.util :as util]))

(deftest sanitize-branch-name-test
  ;; Test branch name sanitization with ID prefix and word limiting.
  ;; Contracts being tested:
  ;; - Task ID is always prefixed (variable width: 5-, 123-, 1234-)
  ;; - Word limiting takes first N words before slugification
  ;; - Lowercase conversion
  ;; - Space to dash replacement
  ;; - Special character removal (keeping only a-z, 0-9, -)
  ;; - Consecutive dash consolidation
  ;; - Leading/trailing dash trimming
  ;; - Edge case handling (empty, special chars only, very long)

  (testing "sanitize-branch-name"
    (testing "always prefixes task ID"
      (is (= "5-my-task"
             (util/sanitize-branch-name "My Task" 5 4)))
      (is (= "123-implement-feature"
             (util/sanitize-branch-name "Implement Feature" 123 4)))
      (is (= "1234-fix-bug"
             (util/sanitize-branch-name "Fix Bug" 1234 4))))

    (testing "limits words when word-limit is specified"
      (is (= "123-implement-user-authentication-with"
             (util/sanitize-branch-name "Implement user authentication with OAuth support" 123 4)))
      (is (= "123-implement-user"
             (util/sanitize-branch-name "Implement user authentication" 123 2)))
      (is (= "10-fix"
             (util/sanitize-branch-name "Fix bug with special chars" 10 1))))

    (testing "uses all words when word-limit is nil"
      (is (= "123-implement-user-authentication-with-oauth-support"
             (util/sanitize-branch-name "Implement user authentication with OAuth support" 123 nil)))
      (is (= "10-fix-bug-456"
             (util/sanitize-branch-name "Fix bug #456" 10 nil))))

    (testing "uses all words when word-limit exceeds available words"
      (is (= "123-short-title"
             (util/sanitize-branch-name "Short Title" 123 10)))
      (is (= "5-ui"
             (util/sanitize-branch-name "UI" 5 4))))

    (testing "handles empty and blank titles with task-id fallback"
      (is (= "task-45"
             (util/sanitize-branch-name "" 45 4)))
      (is (= "task-100"
             (util/sanitize-branch-name "   " 100 4)))
      (is (= "task-1"
             (util/sanitize-branch-name nil 1 4))))

    (testing "handles titles that become empty after slugification"
      (is (= "task-45"
             (util/sanitize-branch-name "!!!" 45 4)))
      (is (= "task-100"
             (util/sanitize-branch-name "###" 100 nil)))
      (is (= "task-50"
             (util/sanitize-branch-name "@#$%^&*()" 50 4))))

    (testing "converts to lowercase"
      (is (= "1-complete-remaining-work"
             (util/sanitize-branch-name "Complete Remaining Work" 1 4))))

    (testing "replaces spaces with dashes"
      (is (= "1-fix-bug-123"
             (util/sanitize-branch-name "fix bug 123" 1 4))))

    (testing "removes special characters"
      (is (= "1-fix-bug-123"
             (util/sanitize-branch-name "Fix bug #123" 1 4)))
      (is (= "1-implement-feature-xyz"
             (util/sanitize-branch-name "Implement feature: xyz!" 1 4)))
      (is (= "1-update-docsreadmemd"
             (util/sanitize-branch-name "Update docs/README.md" 1 4))))

    (testing "replaces multiple consecutive dashes with single dash"
      (is (= "1-fix-multiple-spaces"
             (util/sanitize-branch-name "fix    multiple   spaces" 1 4)))
      (is (= "1-removespecialchars"
             (util/sanitize-branch-name "remove!!!special###chars" 1 4))))

    (testing "trims leading and trailing dashes after slugification"
      (is (= "1-trimmed"
             (util/sanitize-branch-name "---trimmed---" 1 4)))
      (is (= "1-spaces-at-ends"
             (util/sanitize-branch-name "  spaces at ends  " 1 4))))

    (testing "handles very short titles"
      (is (= "1-ui"
             (util/sanitize-branch-name "UI" 1 4)))
      (is (= "1-api"
             (util/sanitize-branch-name "api" 1 4)))
      (is (= "1-x"
             (util/sanitize-branch-name "x" 1 4))))

    (testing "handles titles with single word"
      (is (= "5-refactor"
             (util/sanitize-branch-name "Refactor" 5 4)))
      (is (= "5-refactor"
             (util/sanitize-branch-name "Refactor" 5 1))))

    (testing "truncates to 200 characters if needed"
      (let [long-title (apply str (repeat 100 "word "))
            result (util/sanitize-branch-name long-title 1 nil)]
        (is (= 200 (count result)))
        (is (str/starts-with? result "1-")))
      (let [long-title (apply str (repeat 50 "LongWord "))
            result (util/sanitize-branch-name long-title 999 nil)]
        (is (= 200 (count result)))
        (is (clojure.string/starts-with? result "999-"))))

    (testing "handles very long individual words"
      (let [long-word (apply str (repeat 300 "a"))
            result (util/sanitize-branch-name long-word 5 1)]
        (is (= 200 (count result)))
        (is (clojure.string/starts-with? result "5-"))))

    (testing "handles unicode characters by removing them"
      (is (= "123-hello-world"
             (util/sanitize-branch-name "Hello 世界 World" 123 4)))
      (is (= "5-caf-noir"
             (util/sanitize-branch-name "Café Noir" 5 4))))

    (testing "handles titles with fewer words than limit"
      (is (= "10-one"
             (util/sanitize-branch-name "One" 10 4)))
      (is (= "10-one-two"
             (util/sanitize-branch-name "One Two" 10 4))))

    (testing "handles numeric-only titles"
      (is (= "1-123-456-789"
             (util/sanitize-branch-name "123 456 789" 1 4))))

    (testing "mixed edge cases"
      (is (= "1-complete-remaining-work-for"
             (util/sanitize-branch-name "Complete Remaining Work for EDN Storage Migration" 1 4)))
      (is (= "1-fix-bug-with"
             (util/sanitize-branch-name "Fix bug with @#$% special chars!!!" 1 4)))
      (is (= "1-123-numeric-start"
             (util/sanitize-branch-name "123 numeric start" 1 4))))))
