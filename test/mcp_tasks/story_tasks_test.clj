(ns mcp-tasks.story-tasks-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.story-tasks :as sut]))

(deftest parses-single-incomplete-task
  ;; Tests that parse-story-tasks correctly parses a single incomplete task
  ;; with multi-line description and category
  (testing "parse-story-tasks"
    (testing "parses single incomplete task with multi-line description"
      (let [content "- [ ] STORY: test - Do something\n  With details\n\nCATEGORY: medium"
            result (sut/parse-story-tasks content)]
        (is (= 1 (count result)))
        (is (= "- [ ] STORY: test - Do something\n  With details"
               (:text (first result))))
        (is (= "medium" (:category (first result))))
        (is (false? (:complete? (first result))))
        (is (= 0 (:index (first result))))))))

(deftest parses-single-complete-task
  ;; Tests that parse-story-tasks correctly identifies complete tasks
  (testing "parse-story-tasks"
    (testing "parses single complete task"
      (let [content "- [x] STORY: test - Done task\n\nCATEGORY: simple"
            result (sut/parse-story-tasks content)]
        (is (= 1 (count result)))
        (is (true? (:complete? (first result))))
        (is (= "simple" (:category (first result))))))))

(deftest parses-multiple-tasks
  ;; Tests that parse-story-tasks handles multiple tasks with correct indexing
  (testing "parse-story-tasks"
    (testing "parses multiple tasks with correct indexes"
      (let [content (str "- [ ] First task\n  line 2\n\nCATEGORY: medium\n\n"
                         "- [x] Second task\n\nCATEGORY: simple\n\n"
                         "- [ ] Third task\n\nCATEGORY: large")
            result (sut/parse-story-tasks content)]
        (is (= 3 (count result)))
        (is (= 0 (:index (nth result 0))))
        (is (= 1 (:index (nth result 1))))
        (is (= 2 (:index (nth result 2))))
        (is (false? (:complete? (nth result 0))))
        (is (true? (:complete? (nth result 1))))
        (is (false? (:complete? (nth result 2))))
        (is (= "medium" (:category (nth result 0))))
        (is (= "simple" (:category (nth result 1))))
        (is (= "large" (:category (nth result 2))))))))

(deftest handles-tasks-with-blank-lines-in-description
  ;; Tests that blank lines within task descriptions are preserved
  (testing "parse-story-tasks"
    (testing "handles blank lines within task descriptions"
      (let [content "- [ ] Task with blanks\n\n  More content\n\nCATEGORY: medium"
            result (sut/parse-story-tasks content)]
        (is (= 1 (count result)))
        (is (= "- [ ] Task with blanks\n\n  More content"
               (:text (first result))))))))

(deftest errors-when-category-missing
  ;; Tests that parse-story-tasks throws exception when CATEGORY line is missing
  (testing "parse-story-tasks"
    (testing "throws exception when CATEGORY line missing"
      (let [content "- [ ] Task without category\n  Some details"]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"missing CATEGORY line"
              (sut/parse-story-tasks content)))))))

(deftest errors-when-category-empty
  ;; Tests that parse-story-tasks throws exception when CATEGORY value is empty
  (testing "parse-story-tasks"
    (testing "throws exception when CATEGORY value is empty"
      (let [content "- [ ] Task with empty category\n\nCATEGORY:   \n"]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"empty CATEGORY"
              (sut/parse-story-tasks content)))))))

(deftest errors-when-checkbox-format-invalid
  ;; Tests that parse-story-tasks throws exception for invalid checkbox format
  (testing "parse-story-tasks"
    (testing "throws exception for invalid checkbox format"
      (let [content "- Task without checkbox\n\nCATEGORY: medium"]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"missing checkbox"
              (sut/parse-story-tasks content)))))))

(deftest handles-empty-content
  ;; Tests that parse-story-tasks returns empty sequence for empty content
  (testing "parse-story-tasks"
    (testing "returns empty sequence for empty content"
      (let [result (sut/parse-story-tasks "")]
        (is (= [] result))))))

(deftest ignores-content-before-first-task
  ;; Tests that parse-story-tasks ignores headers and content before first task
  (testing "parse-story-tasks"
    (testing "ignores content before first task"
      (let [content "# Header\n\nSome intro text\n\n- [ ] First task\n\nCATEGORY: simple"
            result (sut/parse-story-tasks content)]
        (is (= 1 (count result)))
        (is (= 0 (:index (first result))))))))

(deftest finds-first-incomplete-task
  ;; Tests that find-first-incomplete-task returns the first incomplete task
  (testing "find-first-incomplete-task"
    (testing "returns first incomplete task"
      (let [tasks [{:complete? true :text "done" :index 0}
                   {:complete? false :text "todo1" :index 1}
                   {:complete? false :text "todo2" :index 2}]
            result (sut/find-first-incomplete-task tasks)]
        (is (= {:complete? false :text "todo1" :index 1} result))))))

(deftest returns-nil-when-all-tasks-complete
  ;; Tests that find-first-incomplete-task returns nil when all tasks are complete
  (testing "find-first-incomplete-task"
    (testing "returns nil when all tasks complete"
      (let [tasks [{:complete? true :text "done1" :index 0}
                   {:complete? true :text "done2" :index 1}]
            result (sut/find-first-incomplete-task tasks)]
        (is (nil? result))))))

(deftest returns-nil-for-empty-task-list
  ;; Tests that find-first-incomplete-task returns nil for empty list
  (testing "find-first-incomplete-task"
    (testing "returns nil for empty task list"
      (let [result (sut/find-first-incomplete-task [])]
        (is (nil? result))))))

(deftest marks-first-task-complete
  ;; Tests that mark-task-complete correctly marks the first task as complete
  (testing "mark-task-complete"
    (testing "marks first task complete"
      (let [content "- [ ] First task\n  Details\n\nCATEGORY: medium\n\n- [ ] Second task\n\nCATEGORY: simple"
            result (sut/mark-task-complete content 0)]
        (is (re-find #"- \[x\] First task" result))
        (is (re-find #"- \[ \] Second task" result))))))

(deftest marks-middle-task-complete
  ;; Tests that mark-task-complete correctly marks a middle task as complete
  (testing "mark-task-complete"
    (testing "marks middle task complete"
      (let [content (str "- [ ] First\n\nCATEGORY: simple\n\n"
                         "- [ ] Second\n\nCATEGORY: medium\n\n"
                         "- [ ] Third\n\nCATEGORY: simple")
            result (sut/mark-task-complete content 1)]
        (is (re-find #"- \[ \] First" result))
        (is (re-find #"- \[x\] Second" result))
        (is (re-find #"- \[ \] Third" result))))))

(deftest marks-last-task-complete
  ;; Tests that mark-task-complete correctly marks the last task as complete
  (testing "mark-task-complete"
    (testing "marks last task complete"
      (let [content "- [ ] First\n\nCATEGORY: simple\n\n- [ ] Second\n\nCATEGORY: medium"
            result (sut/mark-task-complete content 1)]
        (is (re-find #"- \[ \] First" result))
        (is (re-find #"- \[x\] Second" result))))))

(deftest adds-completion-comment
  ;; Tests that mark-task-complete adds completion comment after task
  (testing "mark-task-complete"
    (testing "adds completion comment after task"
      (let [content "- [ ] Task to complete\n\nCATEGORY: simple"
            result (sut/mark-task-complete content 0 "Completed successfully")]
        (is (re-find #"- \[x\] Task to complete" result))
        (is (re-find #"Completed successfully" result))))))

(deftest is-idempotent-for-already-complete-task
  ;; Tests that mark-task-complete is idempotent when task already complete
  (testing "mark-task-complete"
    (testing "is idempotent for already complete task"
      (let [content "- [x] Already done\n\nCATEGORY: simple"
            result (sut/mark-task-complete content 0)]
        (is (re-find #"- \[x\] Already done" result))))))

(deftest errors-when-index-out-of-bounds
  ;; Tests that mark-task-complete throws exception for invalid index
  (testing "mark-task-complete"
    (testing "throws exception when index out of bounds"
      (let [content "- [ ] Only task\n\nCATEGORY: simple"]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"out of bounds"
              (sut/mark-task-complete content 5)))))))

(deftest errors-when-negative-index
  ;; Tests that mark-task-complete throws exception for negative index
  (testing "mark-task-complete"
    (testing "throws exception for negative index"
      (let [content "- [ ] Task\n\nCATEGORY: simple"]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"out of bounds"
              (sut/mark-task-complete content -1)))))))

(deftest preserves-multi-line-task-content
  ;; Tests that mark-task-complete preserves multi-line content when marking complete
  (testing "mark-task-complete"
    (testing "preserves multi-line task content"
      (let [content "- [ ] Multi-line task\n  Line 2\n  Line 3\n\nCATEGORY: medium"
            result (sut/mark-task-complete content 0)]
        (is (re-find #"- \[x\] Multi-line task" result))
        (is (re-find #"Line 2" result))
        (is (re-find #"Line 3" result))
        (is (re-find #"CATEGORY: medium" result))))))
