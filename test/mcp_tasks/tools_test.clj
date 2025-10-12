(ns mcp-tasks.tools-test
  (:require
    [clojure.data.json :as json]
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
        #(let [result (#'sut/complete-task-impl
                       nil
                       nil
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
        #(let [result (#'sut/complete-task-impl
                       nil
                       nil
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
        #(let [result (#'sut/complete-task-impl
                       nil
                       nil
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
        #(let [result (#'sut/complete-task-impl
                       nil
                       nil
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
        #(let [result (#'sut/complete-task-impl
                       nil
                       nil
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
        #(let [result (#'sut/complete-task-impl
                       nil
                       nil
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
        #(let [result (#'sut/complete-task-impl
                       nil
                       nil
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
        #(let [result (#'sut/complete-task-impl
                       nil
                       nil
                       {:category "test"
                        :task-text "first task"})]
           (is (false? (:isError result)))
           (is (= "- [x] first task\n      line 2\n      line 3"
                  (read-test-file "complete/test.md")))
           (is (= "- [ ] second task"
                  (read-test-file "tasks/test.md")))))
      (cleanup-test-fixtures))))

(deftest returns-single-message-with-nil-config
  ;; Tests that complete-task-impl returns modified files as JSON in second text item
  (testing "complete-task"
    (testing "returns modified files as JSON in second text item"
      (setup-test-dir)
      (write-test-file "tasks/test.md" "- [ ] task to complete")
      (with-test-files
        #(let [result (#'sut/complete-task-impl
                       nil
                       nil
                       {:category "test"
                        :task-text "task to complete"})
               content (:content result)]
           (is (false? (:isError result)))
           (is (= 1 (count content)))))
      (cleanup-test-fixtures))))

;; next-task-impl tests

(deftest next-task-returns-first-task
  ;; Tests that next-task-impl returns the first task from a category
  (testing "next-task"
    (testing "returns first task when tasks exist"
      (setup-test-dir)
      (write-test-file "tasks/test.md" "- [ ] first task\n- [ ] second task")
      (with-test-files
        #(let [result (sut/next-task-impl nil {:category "test"})]
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
        #(let [result (sut/next-task-impl nil {:category "test"})]
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
        #(let [result (sut/next-task-impl nil {:category "test"})]
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
        #(let [result (sut/next-task-impl nil {:category "test"})]
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
        #(let [result (sut/next-task-impl nil {:category "test"})]
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
        #(let [result (sut/add-task-impl nil {:category "test"
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
        #(let [result (sut/add-task-impl nil {:category "test"
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
        #(let [result (sut/add-task-impl nil {:category "test"
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
        #(let [result (sut/add-task-impl nil {:category "test"
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
        #(let [result (sut/add-task-impl nil {:category "test"
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
        #(let [result (sut/add-task-impl nil {:category "test"
                                              :task-text "new task"
                                              :prepend true})]
           (is (false? (:isError result)))
           (is (= "- [ ] new task"
                  (read-test-file "tasks/test.md")))))
      (cleanup-test-fixtures))))

(deftest returns-modified-files-when-git-mode-enabled
  ;; Tests that complete-task-impl returns modified files when git mode is enabled
  (testing "complete-task"
    (testing "returns modified files as JSON when git mode enabled"
      (setup-test-dir)
      (write-test-file "tasks/test.md" "- [ ] task to complete")
      (with-test-files
        #(let [config {:use-git? true}
               result (#'sut/complete-task-impl
                       config
                       nil
                       {:category "test"
                        :task-text "task to complete"})
               content (:content result)]
           (is (false? (:isError result)))
           (is (= 2 (count content)))
           (is (= "text" (:type (first content))))
           (is (= "text" (:type (second content))))
           (let [json-data (json/read-str (:text (second content))
                                          :key-fn keyword)]
             (is (= ["tasks/test.md" "complete/test.md"]
                    (:modified-files json-data))))))
      (cleanup-test-fixtures))))

(deftest does-not-return-modified-files-when-git-mode-disabled
  ;; Tests that complete-task-impl does not return modified files when git mode is disabled
  (testing "complete-task"
    (testing "returns only completion message when git mode disabled"
      (setup-test-dir)
      (write-test-file "tasks/test.md" "- [ ] task to complete")
      (with-test-files
        #(let [config {:use-git? false}
               result (#'sut/complete-task-impl
                       config
                       nil
                       {:category "test"
                        :task-text "task to complete"})
               content (:content result)]
           (is (false? (:isError result)))
           (is (= 1 (count content)) "Should only return completion message")
           (is (= "text" (:type (first content))))
           (is (re-find #"Task completed" (:text (first content))))))
      (cleanup-test-fixtures))))

(deftest treats-nil-config-as-non-git-mode
  ;; Tests that complete-task-impl treats nil config as non-git mode
  (testing "complete-task"
    (testing "treats nil config as non-git mode"
      (setup-test-dir)
      (write-test-file "tasks/test.md" "- [ ] task to complete")
      (with-test-files
        #(let [result (#'sut/complete-task-impl
                       nil
                       nil
                       {:category "test"
                        :task-text "task to complete"})
               content (:content result)]
           (is (false? (:isError result)))
           (is (= 1 (count content)) "Should only return completion message for nil config")
           (is (= "text" (:type (first content))))))
      (cleanup-test-fixtures))))

;; complete-story-task-impl tests

(deftest complete-story-task-marks-first-incomplete-as-complete
  ;; Tests that complete-story-task-impl marks the first incomplete task as complete
  (testing "complete-story-task"
    (testing "marks first incomplete task as complete"
      (setup-test-dir)
      (.mkdirs (io/file test-fixtures-dir ".mcp-tasks" "story-tasks"))
      (write-test-file ".mcp-tasks/story-tasks/test-story-tasks.md"
                       (str "# Test Story\n\n"
                            "- [x] STORY: test-story - Already done\n\n"
                            "CATEGORY: simple\n\n"
                            "- [ ] STORY: test-story - First incomplete\n"
                            "  With details\n\n"
                            "CATEGORY: medium\n\n"
                            "- [ ] STORY: test-story - Second incomplete\n\n"
                            "CATEGORY: simple\n"))
      (let [config {:base-dir test-fixtures-dir :use-git? false}
            result (#'sut/complete-story-task-impl
                    config
                    nil
                    {:story-name "test-story"
                     :task-text "STORY: test-story - First incomplete"})]
        (is (false? (:isError result)))
        (is (re-find #"completed" (get-in result [:content 0 :text])))
        (let [updated (read-test-file ".mcp-tasks/story-tasks/test-story-tasks.md")]
          (is (re-find #"- \[x\] STORY: test-story - First incomplete" updated))
          (is (re-find #"- \[ \] STORY: test-story - Second incomplete" updated))))
      (cleanup-test-fixtures))))

(deftest complete-story-task-adds-completion-comment
  ;; Tests that complete-story-task-impl adds completion comment when provided
  (testing "complete-story-task"
    (testing "adds completion comment when provided"
      (setup-test-dir)
      (.mkdirs (io/file test-fixtures-dir ".mcp-tasks" "story-tasks"))
      (write-test-file ".mcp-tasks/story-tasks/test-story-tasks.md"
                       (str "- [ ] STORY: test-story - Task to complete\n\n"
                            "CATEGORY: simple\n"))
      (let [config {:base-dir test-fixtures-dir :use-git? false}
            result (#'sut/complete-story-task-impl
                    config
                    nil
                    {:story-name "test-story"
                     :task-text "STORY: test-story - Task to complete"
                     :completion-comment "Added feature X"})]
        (is (false? (:isError result)))
        (let [updated (read-test-file ".mcp-tasks/story-tasks/test-story-tasks.md")]
          (is (re-find #"- \[x\] STORY: test-story - Task to complete" updated))
          (is (re-find #"Added feature X" updated))))
      (cleanup-test-fixtures))))

(deftest complete-story-task-errors-when-file-not-found
  ;; Tests that complete-story-task-impl errors when story file not found
  (testing "complete-story-task"
    (testing "errors when story file not found"
      (setup-test-dir)
      (let [config {:base-dir test-fixtures-dir :use-git? false}
            result (#'sut/complete-story-task-impl
                    config
                    nil
                    {:story-name "nonexistent"
                     :task-text "some task"})]
        (is (true? (:isError result)))
        (is (re-find #"Story tasks file not found"
                     (get-in result [:content 0 :text]))))
      (cleanup-test-fixtures))))

(deftest complete-story-task-errors-when-no-incomplete-tasks
  ;; Tests that complete-story-task-impl errors when no incomplete tasks exist
  (testing "complete-story-task"
    (testing "errors when no incomplete tasks"
      (setup-test-dir)
      (.mkdirs (io/file test-fixtures-dir ".mcp-tasks" "story-tasks"))
      (write-test-file ".mcp-tasks/story-tasks/test-story-tasks.md"
                       (str "- [x] STORY: test-story - Already done\n\n"
                            "CATEGORY: simple\n"))
      (let [config {:base-dir test-fixtures-dir :use-git? false}
            result (#'sut/complete-story-task-impl
                    config
                    nil
                    {:story-name "test-story"
                     :task-text "some task"})]
        (is (true? (:isError result)))
        (is (re-find #"No incomplete tasks found"
                     (get-in result [:content 0 :text]))))
      (cleanup-test-fixtures))))

(deftest complete-story-task-errors-when-task-text-does-not-match
  ;; Tests that complete-story-task-impl errors when task text doesn't match
  (testing "complete-story-task"
    (testing "errors when task text does not match"
      (setup-test-dir)
      (.mkdirs (io/file test-fixtures-dir ".mcp-tasks" "story-tasks"))
      (write-test-file ".mcp-tasks/story-tasks/test-story-tasks.md"
                       (str "- [ ] STORY: test-story - Actual task\n\n"
                            "CATEGORY: simple\n"))
      (let [config {:base-dir test-fixtures-dir :use-git? false}
            result (#'sut/complete-story-task-impl
                    config
                    nil
                    {:story-name "test-story"
                     :task-text "STORY: test-story - Wrong task"})]
        (is (true? (:isError result)))
        (is (re-find #"does not match"
                     (get-in result [:content 0 :text]))))
      (cleanup-test-fixtures))))

(deftest complete-story-task-returns-modified-files-when-git-mode
  ;; Tests that complete-story-task-impl returns modified files in git mode
  (testing "complete-story-task"
    (testing "returns modified files as JSON when git mode enabled"
      (setup-test-dir)
      (.mkdirs (io/file test-fixtures-dir ".mcp-tasks" "story-tasks"))
      (write-test-file ".mcp-tasks/story-tasks/test-story-tasks.md"
                       (str "- [ ] STORY: test-story - Task to complete\n\n"
                            "CATEGORY: simple\n"))
      (let [config {:base-dir test-fixtures-dir :use-git? true}
            result (#'sut/complete-story-task-impl
                    config
                    nil
                    {:story-name "test-story"
                     :task-text "STORY: test-story - Task to complete"})
            content (:content result)]
        (is (false? (:isError result)))
        (is (= 2 (count content)))
        (is (= "text" (:type (first content))))
        (is (= "text" (:type (second content))))
        (let [json-data (json/read-str (:text (second content))
                                       :key-fn keyword)]
          (is (= ["story-tasks/test-story-tasks.md"]
                 (:modified-files json-data)))))
      (cleanup-test-fixtures))))

(deftest complete-story-task-returns-only-message-when-non-git-mode
  ;; Tests that complete-story-task-impl returns only message in non-git mode
  (testing "complete-story-task"
    (testing "returns only completion message when git mode disabled"
      (setup-test-dir)
      (.mkdirs (io/file test-fixtures-dir ".mcp-tasks" "story-tasks"))
      (write-test-file ".mcp-tasks/story-tasks/test-story-tasks.md"
                       (str "- [ ] STORY: test-story - Task to complete\n\n"
                            "CATEGORY: simple\n"))
      (let [config {:base-dir test-fixtures-dir :use-git? false}
            result (#'sut/complete-story-task-impl
                    config
                    nil
                    {:story-name "test-story"
                     :task-text "STORY: test-story - Task to complete"})
            content (:content result)]
        (is (false? (:isError result)))
        (is (= 1 (count content)) "Should only return completion message")
        (is (= "text" (:type (first content))))
        (is (re-find #"completed" (:text (first content)))))
      (cleanup-test-fixtures))))
