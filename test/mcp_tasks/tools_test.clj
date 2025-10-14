(ns mcp-tasks.tools-test
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-tasks.tasks :as tasks]
    [mcp-tasks.tasks-file :as tasks-file]
    [mcp-tasks.tools :as sut]))

(def ^:private test-fixtures-dir
  "Temporary directory for test fixtures"
  (str (System/getProperty "java.io.tmpdir") "/mcp-tasks-tools-test-" (rand-int 100000)))

(defn- cleanup-test-fixtures
  []
  (let [dir (io/file test-fixtures-dir)]
    (when (.exists dir)
      (doseq [file (reverse (file-seq dir))]
        (.delete file)))))

(defn- setup-test-dir
  []
  (cleanup-test-fixtures)
  (.mkdirs (io/file test-fixtures-dir ".mcp-tasks" "tasks"))
  (.mkdirs (io/file test-fixtures-dir ".mcp-tasks" "complete")))

(defn- write-test-file
  [path content]
  (let [file (io/file (str test-fixtures-dir "/" path))
        parent (.getParentFile file)]
    (when parent
      (.mkdirs parent))
    (spit file content)))

(defn- read-test-file
  [path]
  (let [file (io/file (str test-fixtures-dir "/" path))]
    (if (.exists file)
      (slurp file)
      "")))

(defn- write-ednl-test-file
  "Write tasks as EDNL format to test file."
  [path tasks]
  (let [file-path (str test-fixtures-dir "/.mcp-tasks/" path)]
    (tasks-file/write-tasks file-path tasks)))

(defn- read-ednl-test-file
  "Read tasks from EDNL test file."
  [path]
  (let [file-path (str test-fixtures-dir "/.mcp-tasks/" path)]
    (tasks-file/read-ednl file-path)))

(defn- reset-tasks-state!
  "Reset the tasks namespace global state for testing."
  []
  (reset! tasks/task-ids [])
  (reset! tasks/tasks {})
  (reset! tasks/parent-children {})
  (reset! tasks/child-parent {}))

(def ^:private test-config
  "Config that points to test fixtures directory."
  {:base-dir test-fixtures-dir :use-git? false})

(defn- with-test-files
  "Stub wrapper for old tests - most old tests will need updating."
  [f]
  (f))

(defn- test-fixture
  "Fixture that sets up and cleans up test directory for each test."
  [f]
  (setup-test-dir)
  (reset-tasks-state!)
  (try
    (f)
    (finally
      (cleanup-test-fixtures))))

(use-fixtures :each test-fixture)

;; complete-task-impl tests

(deftest moves-first-task-from-tasks-to-complete
  ;; Tests that the complete-task-impl function correctly moves the first
  ;; task from tasks/<category>.ednl to complete/<category>.ednl
  (testing "complete-task"
    (testing "moves first task from tasks to complete"
      ;; Create EDNL file with two tasks
      (write-ednl-test-file "tasks/test.ednl"
                            [{:id 1 :parent-id nil :title "first task" :description "detail line" :design "" :category "test" :type :task :status :open :meta {} :relations []}
                             {:id 2 :parent-id nil :title "second task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/complete-task-impl
                    test-config
                    nil
                    {:category "test"
                     :task-text "first task"})]
        (is (false? (:isError result)))
        ;; Verify complete file has the completed task
        (let [complete-tasks (read-ednl-test-file "complete/test.ednl")]
          (is (= 1 (count complete-tasks)))
          (is (= "first task" (:title (first complete-tasks))))
          (is (= :closed (:status (first complete-tasks)))))
        ;; Verify tasks file has only the second task
        (let [tasks (read-ednl-test-file "tasks/test.ednl")]
          (is (= 1 (count tasks)))
          (is (= "second task" (:title (first tasks)))))))))

(deftest adds-completion-comment-when-provided
  ;; Tests that completion comments are appended to completed tasks
  (testing "complete-task"
    (testing "adds completion comment when provided"
      (write-ednl-test-file "tasks/test.ednl"
                            [{:id 1 :parent-id nil :title "task with comment" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/complete-task-impl
                    test-config
                    nil
                    {:category "test"
                     :task-text "task with comment"
                     :completion-comment "Added feature X"})]
        (is (false? (:isError result)))
        (let [complete-tasks (read-ednl-test-file "complete/test.ednl")]
          (is (= 1 (count complete-tasks)))
          (is (str/includes? (:description (first complete-tasks)) "Added feature X")))))))

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
        #(let [result (sut/next-task-impl nil nil {:category "test"})]
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
        #(let [result (sut/next-task-impl nil nil {:category "test"})]
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
        #(let [result (sut/next-task-impl nil nil {:category "test"})]
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
        #(let [result (sut/next-task-impl nil nil {:category "test"})]
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
        #(let [result (sut/next-task-impl nil nil {:category "test"})]
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
        #(let [result (sut/add-task-impl nil nil {:category "test"
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
        #(let [result (sut/add-task-impl nil nil {:category "test"
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
        #(let [result (sut/add-task-impl nil nil {:category "test"
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
        #(let [result (sut/add-task-impl nil nil {:category "test"
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
        #(let [result (sut/add-task-impl nil nil {:category "test"
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
        #(let [result (sut/add-task-impl nil nil {:category "test"
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

;; Story task file creation tests

(deftest creates-story-tasks-file-when-it-doesnt-exist
  ;; Tests that add-task-impl creates story-tasks file with header when adding
  ;; first task to a story
  (testing "add-task"
    (testing "creates story-tasks file with header when it doesn't exist"
      (setup-test-dir)
      (.mkdirs (io/file test-fixtures-dir "story/stories"))
      (.mkdirs (io/file test-fixtures-dir "story/story-tasks"))
      (write-test-file "story/stories/test-story.md" "# Test Story\n\nDescription")
      (with-test-files
        #(let [result (sut/add-task-impl nil nil {:category "simple"
                                                  :task-text "First task"
                                                  :story-name "test-story"})]
           (when (:isError result)
             (prn "Error result:" result))
           (is (false? (:isError result)))
           (is (= "# Tasks for test-story Story\n- [ ] First task\nCATEGORY: simple"
                  (read-test-file "story/story-tasks/test-story-tasks.md")))))
      (cleanup-test-fixtures))))

(deftest appends-to-existing-story-tasks-file
  ;; Tests that add-task-impl appends to existing story-tasks file without
  ;; adding duplicate header
  (testing "add-task"
    (testing "appends to existing story-tasks file"
      (setup-test-dir)
      (.mkdirs (io/file test-fixtures-dir "story/stories"))
      (.mkdirs (io/file test-fixtures-dir "story/story-tasks"))
      (write-test-file "story/stories/test-story.md" "# Test Story\n\nDescription")
      (write-test-file "story/story-tasks/test-story-tasks.md"
                       "# Tasks for test-story Story\n- [ ] Existing task\nCATEGORY: simple")
      (with-test-files
        #(let [result (sut/add-task-impl nil nil {:category "medium"
                                                  :task-text "Second task"
                                                  :story-name "test-story"})]
           (is (false? (:isError result)))
           (is (= (str "# Tasks for test-story Story\n"
                       "- [ ] Existing task\nCATEGORY: simple\n\n"
                       "- [ ] Second task\nCATEGORY: medium")
                  (read-test-file "story/story-tasks/test-story-tasks.md")))))
      (cleanup-test-fixtures))))

(deftest prepends-to-story-tasks-file-when-prepend-is-true
  ;; Tests that add-task-impl prepends to story-tasks file when prepend is true
  (testing "add-task"
    (testing "prepends to story-tasks file when prepend is true"
      (setup-test-dir)
      (.mkdirs (io/file test-fixtures-dir "story/stories"))
      (.mkdirs (io/file test-fixtures-dir "story/story-tasks"))
      (write-test-file "story/stories/test-story.md" "# Test Story\n\nDescription")
      (write-test-file "story/story-tasks/test-story-tasks.md"
                       "# Tasks for test-story Story\n- [ ] Existing task\nCATEGORY: simple")
      (with-test-files
        #(let [result (sut/add-task-impl nil nil {:category "simple"
                                                  :task-text "New task"
                                                  :story-name "test-story"
                                                  :prepend true})]
           (is (false? (:isError result)))
           (is (= (str "- [ ] New task\nCATEGORY: simple\n\n"
                       "# Tasks for test-story Story\n"
                       "- [ ] Existing task\nCATEGORY: simple")
                  (read-test-file "story/story-tasks/test-story-tasks.md")))))
      (cleanup-test-fixtures))))

(deftest errors-when-story-does-not-exist
  ;; Tests that add-task-impl returns error when trying to add task to
  ;; non-existent story
  (testing "add-task"
    (testing "errors when story does not exist"
      (setup-test-dir)
      (.mkdirs (io/file test-fixtures-dir "story/stories"))
      (with-test-files
        #(let [result (sut/add-task-impl nil nil {:category "simple"
                                                  :task-text "Task for missing story"
                                                  :story-name "nonexistent"})]
           (is (true? (:isError result)))
           (is (re-find #"Story does not exist"
                        (get-in result [:content 0 :text])))))
      (cleanup-test-fixtures))))

(deftest story-task-includes-category-line
  ;; Tests that story tasks include CATEGORY metadata line
  (testing "add-task"
    (testing "includes CATEGORY line for story tasks"
      (setup-test-dir)
      (.mkdirs (io/file test-fixtures-dir "story/stories"))
      (.mkdirs (io/file test-fixtures-dir "story/story-tasks"))
      (write-test-file "story/stories/test-story.md" "# Test Story")
      (with-test-files
        #(let [result (sut/add-task-impl nil nil {:category "large"
                                                  :task-text "Complex task"
                                                  :story-name "test-story"})]
           (is (false? (:isError result)))
           (is (str/includes? (read-test-file "story/story-tasks/test-story-tasks.md")
                              "CATEGORY: large"))))
      (cleanup-test-fixtures))))

(deftest story-task-with-multi-line-text-has-category-after-task
  ;; Tests that CATEGORY line appears after multi-line task text
  (testing "add-task"
    (testing "places CATEGORY line after multi-line task text"
      (setup-test-dir)
      (.mkdirs (io/file test-fixtures-dir "story/stories"))
      (.mkdirs (io/file test-fixtures-dir "story/story-tasks"))
      (write-test-file "story/stories/test-story.md" "# Test Story")
      (with-test-files
        #(let [result (sut/add-task-impl nil nil {:category "medium"
                                                  :task-text "First line\nSecond line\nThird line"
                                                  :story-name "test-story"})]
           (is (false? (:isError result)))
           (is (= "# Tasks for test-story Story\n- [ ] First line\nSecond line\nThird line\nCATEGORY: medium"
                  (read-test-file "story/story-tasks/test-story-tasks.md")))))
      (cleanup-test-fixtures))))

(deftest regular-task-does-not-include-category-line
  ;; Tests that regular (non-story) tasks do not include CATEGORY metadata
  (testing "add-task"
    (testing "does not include CATEGORY line for regular tasks"
      (setup-test-dir)
      (with-test-files
        #(let [result (sut/add-task-impl nil nil {:category "test"
                                                  :task-text "Regular task"})]
           (is (false? (:isError result)))
           (is (= "- [ ] Regular task"
                  (read-test-file "tasks/test.md")))
           (is (not (str/includes? (read-test-file "tasks/test.md")
                                   "CATEGORY:")))))
      (cleanup-test-fixtures))))

(deftest story-tasks-use-double-newline-separator
  ;; Tests that story tasks are separated by double newline instead of single
  (testing "add-task"
    (testing "uses double newline separator for story tasks"
      (setup-test-dir)
      (.mkdirs (io/file test-fixtures-dir "story/stories"))
      (.mkdirs (io/file test-fixtures-dir "story/story-tasks"))
      (write-test-file "story/stories/test-story.md" "# Test Story")
      (write-test-file "story/story-tasks/test-story-tasks.md"
                       "# Tasks for test-story Story\n- [ ] First task\nCATEGORY: simple")
      (with-test-files
        #(let [result (sut/add-task-impl nil nil {:category "medium"
                                                  :task-text "Second task"
                                                  :story-name "test-story"})]
           (is (false? (:isError result)))
           (let [content (read-test-file "story/story-tasks/test-story-tasks.md")]
             ;; Verify double newline separator between tasks
             (is (str/includes? content "CATEGORY: simple\n\n- [ ] Second task")))))
      (cleanup-test-fixtures))))

(deftest category-line-format-is-exact
  ;; Tests that CATEGORY line follows exact format "CATEGORY: <category>"
  (testing "add-task"
    (testing "formats CATEGORY line correctly"
      (setup-test-dir)
      (.mkdirs (io/file test-fixtures-dir "story/stories"))
      (.mkdirs (io/file test-fixtures-dir "story/story-tasks"))
      (write-test-file "story/stories/test-story.md" "# Test Story")
      (with-test-files
        #(let [result (sut/add-task-impl nil nil {:category "simple"
                                                  :task-text "Test task"
                                                  :story-name "test-story"})]
           (is (false? (:isError result)))
           (let [content (read-test-file "story/story-tasks/test-story-tasks.md")]
             ;; Verify exact format: "CATEGORY: " followed by category name
             (is (str/includes? content "\nCATEGORY: simple"))
             ;; Ensure no extra spaces or formatting
             (is (not (str/includes? content "CATEGORY:simple")))
             (is (not (str/includes? content "CATEGORY:  simple"))))))
      (cleanup-test-fixtures))))

;; Integration Tests

(deftest ^:integration complete-workflow-add-next-complete
  ;; Integration test for complete workflow: add task → next task → complete task
  (testing "complete workflow with EDN storage"
    (testing "add → next → complete workflow"
      ;; Add first task
      (let [result (#'sut/add-task-impl test-config nil {:category "test"
                                                         :task-text "First task\nWith description"})]
        (when (:isError result)
          (prn "Add first task error:" result))
        (is (false? (:isError result))))

      ;; Add second task
      (let [result (#'sut/add-task-impl test-config nil {:category "test"
                                                         :task-text "Second task"})]
        (when (:isError result)
          (prn "Add second task error:" result))
        (is (false? (:isError result))))

      ;; Get next task - should be first task
      (let [result (#'sut/next-task-impl test-config nil {:category "test"})]
        (is (false? (:isError result)))
        (let [response (clojure.edn/read-string (get-in result [:content 0 :text]))]
          (is (= "test" (:category response)))
          (is (str/starts-with? (:task response) "First task"))))

      ;; Complete first task
      (let [result (#'sut/complete-task-impl test-config nil {:category "test"
                                                              :task-text "First task"})]
        (is (false? (:isError result))))

      ;; Get next task - should now be second task
      (let [result (#'sut/next-task-impl test-config nil {:category "test"})]
        (is (false? (:isError result)))
        (let [response (clojure.edn/read-string (get-in result [:content 0 :text]))]
          (is (= "test" (:category response)))
          (is (str/starts-with? (:task response) "Second task"))))

      ;; Complete second task
      (let [result (#'sut/complete-task-impl test-config nil {:category "test"
                                                              :task-text "Second task"})]
        (is (false? (:isError result))))

      ;; Get next task - should have no more tasks
      (let [result (#'sut/next-task-impl test-config nil {:category "test"})]
        (is (false? (:isError result)))
        (let [response (clojure.edn/read-string (get-in result [:content 0 :text]))]
          (is (= "test" (:category response)))
          (is (= "No more tasks in this category" (:status response))))))))
