(ns mcp-tasks.util-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.util :as util]))

(deftest sanitize-branch-name-test
  ;; Test basic sanitization behavior including the contracts for:
  ;; - Lowercase conversion
  ;; - Space to dash replacement
  ;; - Special character removal
  ;; - Consecutive dash consolidation
  ;; - Leading/trailing dash trimming
  ;; - Edge case handling (short, empty, long)

  (testing "sanitize-branch-name"
    (testing "converts to lowercase"
      (is (= "complete-remaining-work"
             (util/sanitize-branch-name "Complete Remaining Work" 1))))

    (testing "replaces spaces with dashes"
      (is (= "fix-bug-123"
             (util/sanitize-branch-name "fix bug 123" 1))))

    (testing "removes special characters"
      (is (= "fix-bug-123"
             (util/sanitize-branch-name "Fix bug #123" 1)))
      (is (= "implement-feature-xyz"
             (util/sanitize-branch-name "Implement feature: xyz!" 1)))
      (is (= "update-docsreadmemd"
             (util/sanitize-branch-name "Update docs/README.md" 1))))

    (testing "replaces multiple consecutive dashes with single dash"
      (is (= "fix-multiple-spaces"
             (util/sanitize-branch-name "fix    multiple   spaces" 1)))
      (is (= "removespecialchars"
             (util/sanitize-branch-name "remove!!!special###chars" 1))))

    (testing "trims leading and trailing dashes"
      (is (= "trimmed"
             (util/sanitize-branch-name "---trimmed---" 1)))
      (is (= "spaces-at-ends"
             (util/sanitize-branch-name "  spaces at ends  " 1))))

    (testing "handles very short titles (2-3 chars)"
      (is (= "ui"
             (util/sanitize-branch-name "UI" 1)))
      (is (= "api"
             (util/sanitize-branch-name "api" 1)))
      (is (= "x"
             (util/sanitize-branch-name "x" 1))))

    (testing "handles empty after sanitization with task-id fallback"
      (is (= "task-45"
             (util/sanitize-branch-name "!!!" 45)))
      (is (= "task-100"
             (util/sanitize-branch-name "###" 100)))
      (is (= "task-1"
             (util/sanitize-branch-name "   " 1)))
      (is (= "task-1"
             (util/sanitize-branch-name "" 1))))

    (testing "truncates long titles to 200 characters"
      (let [long-title (apply str (repeat 100 "ab"))
            result (util/sanitize-branch-name long-title 1)]
        (is (= 200 (count result)))
        (is (= (apply str (repeat 100 "ab")) result))))

    (testing "truncates after sanitization"
      (let [long-title (str (apply str (repeat 50 "Word ")) "End")
            result (util/sanitize-branch-name long-title 1)]
        (is (= 200 (count result)))
        (is (every? #{\w \o \r \d \- \e \n} result))))

    (testing "handles mixed edge cases"
      (is (= "complete-remaining-work-for-edn-storage-migration"
             (util/sanitize-branch-name "Complete Remaining Work for EDN Storage Migration" 1)))
      (is (= "fix-bug-with-special-chars"
             (util/sanitize-branch-name "Fix bug with @#$% special chars!!!" 1)))
      (is (= "123-numeric-start"
             (util/sanitize-branch-name "123 numeric start" 1))))))
