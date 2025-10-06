(ns mcp-tasks.tools-test
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.tools :as sut]))

(def ^:private test-fixtures-dir "test-resources/tools-test")

(defn- cleanup-test-fixtures
  []
  (let [dir (io/file test-fixtures-dir)]
    (when (.exists dir)
      (doseq [file (reverse (file-seq dir))]
        (.delete file)))))

(defn- setup-test-dir
  []
  (cleanup-test-fixtures)
  (.mkdirs (io/file test-fixtures-dir "tasks"))
  (.mkdirs (io/file test-fixtures-dir "complete")))

(defn- write-test-file
  [path content]
  (spit (str test-fixtures-dir "/" path) content))

(defn- read-test-file
  [path]
  (let [file (io/file (str test-fixtures-dir "/" path))]
    (if (.exists file)
      (slurp file)
      "")))

(defn- with-test-files
  [f]
  (with-redefs [sut/read-task-file
                (fn [path]
                  (read-test-file (str/replace path #"^\.mcp-tasks/" "")))
                sut/write-task-file
                (fn [path content]
                  (write-test-file (str/replace path #"^\.mcp-tasks/" "")
                                   content))]
    (f)))

;; complete-task-impl tests

(deftest moves-first-task-from-tasks-to-complete
  ;; Tests that the complete-task-impl function correctly moves the first
  ;; task from tasks/<category>.md to complete/<category>.md
  (testing "complete-task"
    (testing "moves first task from tasks to complete"
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
      (cleanup-test-fixtures))))

(deftest adds-completion-comment-when-provided
  ;; Tests that completion comments are appended to completed tasks
  (testing "complete-task"
    (testing "adds completion comment when provided"
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
      (cleanup-test-fixtures))))

(deftest appends-to-existing-complete-file
  ;; Tests that completed tasks are appended to existing complete file
  (testing "complete-task"
    (testing "appends to existing complete file"
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
      (cleanup-test-fixtures))))

(deftest leaves-tasks-file-empty-when-completing-last-task
  ;; Tests that the tasks file is left empty (not deleted) when the last
  ;; task is completed
  (testing "complete-task"
    (testing "leaves tasks file empty when completing last task"
      (setup-test-dir)
      (write-test-file "tasks/test.md" "- [ ] only task")
      (with-test-files
        #(let [result (sut/complete-task-impl
                        {:category "test"
                         :task-text "only task"})]
           (is (false? (:isError result)))
           (is (= "" (read-test-file "tasks/test.md")))))
      (cleanup-test-fixtures))))

(deftest errors-when-task-text-does-not-match
  ;; Tests that an error is returned when the provided task text doesn't
  ;; match the first task
  (testing "complete-task"
    (testing "errors when task text does not match"
      (setup-test-dir)
      (write-test-file "tasks/test.md" "- [ ] actual task")
      (with-test-files
        #(let [result (sut/complete-task-impl
                        {:category "test"
                         :task-text "wrong task"})]
           (is (true? (:isError result)))
           (is (re-find #"does not match"
                        (get-in result [:content 0 :text])))))
      (cleanup-test-fixtures))))

(deftest errors-when-category-has-no-tasks
  ;; Tests that an error is returned when trying to complete a task in an
  ;; empty category
  (testing "complete-task"
    (testing "errors when category has no tasks"
      (setup-test-dir)
      (write-test-file "tasks/test.md" "")
      (with-test-files
        #(let [result (sut/complete-task-impl
                        {:category "test"
                         :task-text "any task"})]
           (is (true? (:isError result)))
           (is (re-find #"No tasks found"
                        (get-in result [:content 0 :text])))))
      (cleanup-test-fixtures))))

(deftest matches-tasks-case-insensitively
  ;; Tests that task matching is case-insensitive
  (testing "complete-task"
    (testing "matches tasks case-insensitively"
      (setup-test-dir)
      (write-test-file "tasks/test.md" "- [ ] Task With Capitals")
      (with-test-files
        #(let [result (sut/complete-task-impl
                        {:category "test"
                         :task-text "task with capitals"})]
           (is (false? (:isError result)))))
      (cleanup-test-fixtures))))

(deftest handles-multi-line-tasks-correctly
  ;; Tests that multi-line tasks are handled correctly, preserving all
  ;; continuation lines
  (testing "complete-task"
    (testing "handles multi-line tasks correctly"
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
      (cleanup-test-fixtures))))

;; next-task-impl tests

(deftest next-task-returns-first-task
  ;; Tests that next-task-impl returns the first task from a category
  (testing "next-task"
    (testing "returns first task when tasks exist"
      (setup-test-dir)
      (write-test-file "tasks/test.md" "- [ ] first task\n- [ ] second task")
      (with-test-files
        #(let [result (sut/next-task-impl {:category "test"})]
           (is (false? (:isError result)))
           (is (= "{:category \"test\", :task \"first task\"}"
                  (get-in result [:content 0 :text])))))
      (cleanup-test-fixtures))))

(deftest next-task-returns-status-when-no-tasks
  ;; Tests that next-task-impl returns a status message when no tasks exist
  (testing "next-task"
    (testing "returns status message when no tasks exist"
      (setup-test-dir)
      (write-test-file "tasks/test.md" "")
      (with-test-files
        #(let [result (sut/next-task-impl {:category "test"})]
           (is (false? (:isError result)))
           (is (= "{:category \"test\", :status \"No more tasks in this category\"}"
                  (get-in result [:content 0 :text])))))
      (cleanup-test-fixtures))))

(deftest next-task-returns-status-when-file-doesnt-exist
  ;; Tests that next-task-impl returns a status message when tasks file doesn't exist
  (testing "next-task"
    (testing "returns status message when tasks file doesn't exist"
      (setup-test-dir)
      (with-test-files
        #(let [result (sut/next-task-impl {:category "test"})]
           (is (false? (:isError result)))
           (is (= "{:category \"test\", :status \"No more tasks in this category\"}"
                  (get-in result [:content 0 :text])))))
      (cleanup-test-fixtures))))

(deftest next-task-strips-checkbox-prefix
  ;; Tests that next-task-impl strips the checkbox prefix from the task text
  (testing "next-task"
    (testing "strips checkbox prefix from task text"
      (setup-test-dir)
      (write-test-file "tasks/test.md" "- [ ] task description here")
      (with-test-files
        #(let [result (sut/next-task-impl {:category "test"})]
           (is (false? (:isError result)))
           (is (= "{:category \"test\", :task \"task description here\"}"
                  (get-in result [:content 0 :text])))))
      (cleanup-test-fixtures))))

(deftest next-task-handles-multi-line-tasks
  ;; Tests that next-task-impl returns multi-line tasks with continuation lines
  (testing "next-task"
    (testing "returns multi-line tasks with continuation lines"
      (setup-test-dir)
      (write-test-file "tasks/test.md" "- [ ] first line\n      second line")
      (with-test-files
        #(let [result (sut/next-task-impl {:category "test"})]
           (is (false? (:isError result)))
           (is (= "{:category \"test\", :task \"first line\\n      second line\"}"
                  (get-in result [:content 0 :text])))))
      (cleanup-test-fixtures))))

;; add-task-impl tests

(deftest add-task-appends-to-empty-file
  ;; Tests that add-task-impl creates a new task in an empty file
  (testing "add-task"
    (testing "creates task in empty file"
      (setup-test-dir)
      (write-test-file "tasks/test.md" "")
      (with-test-files
        #(let [result (sut/add-task-impl {:category "test"
                                          :task-text "new task"})]
           (is (false? (:isError result)))
           (is (= "- [ ] new task"
                  (read-test-file "tasks/test.md")))))
      (cleanup-test-fixtures))))

(deftest add-task-appends-to-existing-tasks
  ;; Tests that add-task-impl appends a new task to existing tasks
  (testing "add-task"
    (testing "appends to existing tasks"
      (setup-test-dir)
      (write-test-file "tasks/test.md" "- [ ] existing task")
      (with-test-files
        #(let [result (sut/add-task-impl {:category "test"
                                          :task-text "new task"})]
           (is (false? (:isError result)))
           (is (= "- [ ] existing task\n- [ ] new task"
                  (read-test-file "tasks/test.md")))))
      (cleanup-test-fixtures))))

(deftest add-task-returns-success-message
  ;; Tests that add-task-impl returns a success message with the file path
  (testing "add-task"
    (testing "returns success message with file path"
      (setup-test-dir)
      (with-test-files
        #(let [result (sut/add-task-impl {:category "test"
                                          :task-text "new task"})]
           (is (false? (:isError result)))
           (is (= "Task added to .mcp-tasks/tasks/test.md"
                  (get-in result [:content 0 :text])))))
      (cleanup-test-fixtures))))

(deftest add-task-handles-multi-line-text
  ;; Tests that add-task-impl correctly handles multi-line task text
  (testing "add-task"
    (testing "handles multi-line task text"
      (setup-test-dir)
      (with-test-files
        #(let [result (sut/add-task-impl {:category "test"
                                          :task-text "first line\nsecond line"})]
           (is (false? (:isError result)))
           (is (= "- [ ] first line\nsecond line"
                  (read-test-file "tasks/test.md")))))
      (cleanup-test-fixtures))))

(deftest add-task-prepends-when-prepend-is-true
  ;; Tests that add-task-impl prepends task when prepend option is true
  (testing "add-task"
    (testing "prepends task when prepend is true"
      (setup-test-dir)
      (write-test-file "tasks/test.md" "- [ ] existing task")
      (with-test-files
        #(let [result (sut/add-task-impl {:category "test"
                                          :task-text "new task"
                                          :prepend true})]
           (is (false? (:isError result)))
           (is (= "- [ ] new task\n- [ ] existing task"
                  (read-test-file "tasks/test.md")))))
      (cleanup-test-fixtures))))

(deftest add-task-prepends-to-empty-file-when-prepend-is-true
  ;; Tests that add-task-impl works correctly with empty file and prepend option
  (testing "add-task"
    (testing "prepends to empty file when prepend is true"
      (setup-test-dir)
      (write-test-file "tasks/test.md" "")
      (with-test-files
        #(let [result (sut/add-task-impl {:category "test"
                                          :task-text "new task"
                                          :prepend true})]
           (is (false? (:isError result)))
           (is (= "- [ ] new task"
                  (read-test-file "tasks/test.md")))))
      (cleanup-test-fixtures))))
