(ns mcp-tasks.tools-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [mcp-tasks.tools :as sut]))

(def ^:private test-fixtures-dir "test-resources/tools-test")

(defn- cleanup-test-fixtures []
  (let [dir (io/file test-fixtures-dir)]
    (when (.exists dir)
      (doseq [file (reverse (file-seq dir))]
        (.delete file)))))

(defn- setup-test-dir []
  (cleanup-test-fixtures)
  (.mkdirs (io/file test-fixtures-dir "tasks"))
  (.mkdirs (io/file test-fixtures-dir "complete")))

(defn- write-test-file [path content]
  (spit (str test-fixtures-dir "/" path) content))

(defn- read-test-file [path]
  (let [file (io/file (str test-fixtures-dir "/" path))]
    (if (.exists file)
      (slurp file)
      "")))

(defn- with-test-files [f]
  (with-redefs [sut/read-task-file
                (fn [path]
                  (read-test-file (str/replace path #"^\.mcp-tasks/" "")))
                sut/write-task-file
                (fn [path content]
                  (write-test-file (str/replace path #"^\.mcp-tasks/" "")
                                   content))]
    (f)))

(deftest moves-first-task-from-tasks-to-complete
  ;; Tests that the complete-task-impl function correctly moves the first
  ;; task from tasks/<category>.md to complete/<category>.md
  (setup-test-dir)
  (write-test-file "tasks/test.md"
                   "- [ ] first task\n      detail line\n- [ ] second task")
  (with-test-files
    #(let [result (sut/complete-task-impl
                   {:category "test"
                    :task-text "first task"})]
       (is (false? (:isError result)))
       (is (= "- [x] first task\n      detail line"
              (read-test-file "complete/test.md")))
       (is (= "- [ ] second task"
              (read-test-file "tasks/test.md")))))
  (cleanup-test-fixtures))

(deftest adds-completion-comment-when-provided
  ;; Tests that completion comments are appended to completed tasks
  (setup-test-dir)
  (write-test-file "tasks/test.md" "- [ ] task with comment")
  (with-test-files
    #(let [result (sut/complete-task-impl
                   {:category "test"
                    :task-text "task with comment"
                    :completion-comment "Added feature X"})]
       (is (false? (:isError result)))
       (is (= "- [x] task with comment\n\nAdded feature X"
              (read-test-file "complete/test.md")))))
  (cleanup-test-fixtures))

(deftest appends-to-existing-complete-file
  ;; Tests that completed tasks are appended to existing complete file
  (setup-test-dir)
  (write-test-file "tasks/test.md" "- [ ] new task")
  (write-test-file "complete/test.md" "- [x] old task")
  (with-test-files
    #(let [result (sut/complete-task-impl
                   {:category "test"
                    :task-text "new task"})]
       (is (false? (:isError result)))
       (is (= "- [x] old task\n- [x] new task"
              (read-test-file "complete/test.md")))))
  (cleanup-test-fixtures))

(deftest leaves-tasks-file-empty-when-completing-last-task
  ;; Tests that the tasks file is left empty (not deleted) when the last
  ;; task is completed
  (setup-test-dir)
  (write-test-file "tasks/test.md" "- [ ] only task")
  (with-test-files
    #(let [result (sut/complete-task-impl
                   {:category "test"
                    :task-text "only task"})]
       (is (false? (:isError result)))
       (is (= "" (read-test-file "tasks/test.md")))))
  (cleanup-test-fixtures))

(deftest errors-when-task-text-does-not-match
  ;; Tests that an error is returned when the provided task text doesn't
  ;; match the first task
  (setup-test-dir)
  (write-test-file "tasks/test.md" "- [ ] actual task")
  (with-test-files
    #(let [result (sut/complete-task-impl
                   {:category "test"
                    :task-text "wrong task"})]
       (is (true? (:isError result)))
       (is (re-find #"does not match"
                    (get-in result [:content 0 :text])))))
  (cleanup-test-fixtures))

(deftest errors-when-category-has-no-tasks
  ;; Tests that an error is returned when trying to complete a task in an
  ;; empty category
  (setup-test-dir)
  (write-test-file "tasks/test.md" "")
  (with-test-files
    #(let [result (sut/complete-task-impl
                   {:category "test"
                    :task-text "any task"})]
       (is (true? (:isError result)))
       (is (re-find #"No tasks found"
                    (get-in result [:content 0 :text])))))
  (cleanup-test-fixtures))

(deftest matches-tasks-case-insensitively
  ;; Tests that task matching is case-insensitive
  (setup-test-dir)
  (write-test-file "tasks/test.md" "- [ ] Task With Capitals")
  (with-test-files
    #(let [result (sut/complete-task-impl
                   {:category "test"
                    :task-text "task with capitals"})]
       (is (false? (:isError result)))))
  (cleanup-test-fixtures))

(deftest handles-multi-line-tasks-correctly
  ;; Tests that multi-line tasks are handled correctly, preserving all
  ;; continuation lines
  (setup-test-dir)
  (write-test-file "tasks/test.md"
                   "- [ ] first task\n      line 2\n      line 3\n- [ ] second task")
  (with-test-files
    #(let [result (sut/complete-task-impl
                   {:category "test"
                    :task-text "first task"})]
       (is (false? (:isError result)))
       (is (= "- [x] first task\n      line 2\n      line 3"
              (read-test-file "complete/test.md")))
       (is (= "- [ ] second task"
              (read-test-file "tasks/test.md")))))
  (cleanup-test-fixtures))
