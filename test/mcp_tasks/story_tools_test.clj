(ns mcp-tasks.story-tools-test
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.story-tools :as sut]))

(def test-fixtures-dir
  "Temporary directory for test fixtures"
  (str (System/getProperty "java.io.tmpdir") "/mcp-tasks-test-" (rand-int 100000)))

(defn- cleanup-test-fixtures
  "Remove test fixtures directory"
  []
  (let [dir (io/file test-fixtures-dir)]
    (when (.exists dir)
      (doseq [file (reverse (file-seq dir))]
        (.delete file)))))

(defn- setup-test-dir
  "Create test fixtures directory"
  []
  (cleanup-test-fixtures)
  (.mkdirs (io/file test-fixtures-dir ".mcp-tasks")))

(defn- write-test-file
  "Write content to a test file"
  [path content]
  (let [file (io/file test-fixtures-dir path)]
    (.mkdirs (.getParentFile file))
    (spit file content)))

(defn- read-test-file
  "Read content from a test file"
  [path]
  (slurp (io/file test-fixtures-dir path)))

(deftest complete-story-task-marks-first-incomplete-as-complete
  ;; Tests that complete-story-task-impl marks the first incomplete task as complete
  (testing "complete-story-task"
    (testing "marks first incomplete task as complete"
      (setup-test-dir)
      (.mkdirs (io/file test-fixtures-dir ".mcp-tasks" "story" "story-tasks"))
      (write-test-file ".mcp-tasks/story/story-tasks/test-story-tasks.md"
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
        (let [updated (read-test-file ".mcp-tasks/story/story-tasks/test-story-tasks.md")]
          (is (re-find #"- \[x\] STORY: test-story - First incomplete" updated))
          (is (re-find #"- \[ \] STORY: test-story - Second incomplete" updated))))
      (cleanup-test-fixtures))))

(deftest complete-story-task-adds-completion-comment
  ;; Tests that complete-story-task-impl adds completion comment when provided
  (testing "complete-story-task"
    (testing "adds completion comment when provided"
      (setup-test-dir)
      (.mkdirs (io/file test-fixtures-dir ".mcp-tasks" "story" "story-tasks"))
      (write-test-file ".mcp-tasks/story/story-tasks/test-story-tasks.md"
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
        (let [updated (read-test-file ".mcp-tasks/story/story-tasks/test-story-tasks.md")]
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
      (.mkdirs (io/file test-fixtures-dir ".mcp-tasks" "story" "story-tasks"))
      (write-test-file ".mcp-tasks/story/story-tasks/test-story-tasks.md"
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
      (.mkdirs (io/file test-fixtures-dir ".mcp-tasks" "story" "story-tasks"))
      (write-test-file ".mcp-tasks/story/story-tasks/test-story-tasks.md"
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
      (.mkdirs (io/file test-fixtures-dir ".mcp-tasks" "story" "story-tasks"))
      (write-test-file ".mcp-tasks/story/story-tasks/test-story-tasks.md"
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
          (is (= ["story/story-tasks/test-story-tasks.md"]
                 (:modified-files json-data)))))
      (cleanup-test-fixtures))))

(deftest complete-story-task-returns-only-message-when-non-git-mode
  ;; Tests that complete-story-task-impl returns only message in non-git mode
  (testing "complete-story-task"
    (testing "returns only completion message when git mode disabled"
      (setup-test-dir)
      (.mkdirs (io/file test-fixtures-dir ".mcp-tasks" "story" "story-tasks"))
      (write-test-file ".mcp-tasks/story/story-tasks/test-story-tasks.md"
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

(deftest complete-story-moves-story-and-tasks-to-complete
  ;; Tests that complete-story-impl moves both story and tasks files to complete directories
  (testing "complete-story"
    (testing "moves story and tasks files to complete directories"
      (setup-test-dir)
      (.mkdirs (io/file test-fixtures-dir ".mcp-tasks" "story" "stories"))
      (.mkdirs (io/file test-fixtures-dir ".mcp-tasks" "story" "story-tasks"))
      (write-test-file ".mcp-tasks/story/stories/test-story.md"
                       "# Test Story\n\nStory content here")
      (write-test-file ".mcp-tasks/story/story-tasks/test-story-tasks.md"
                       "- [x] Task 1\n\nCATEGORY: simple\n")
      (let [config {:base-dir test-fixtures-dir :use-git? false}
            result (#'sut/complete-story-impl
                    config
                    nil
                    {:story-name "test-story"})]
        (is (false? (:isError result)))
        (is (re-find #"marked as complete" (get-in result [:content 0 :text])))
        ;; Check story moved
        (is (not (.exists (io/file test-fixtures-dir ".mcp-tasks" "story" "stories" "test-story.md"))))
        (is (.exists (io/file test-fixtures-dir ".mcp-tasks" "story" "complete" "test-story.md")))
        (is (= "# Test Story\n\nStory content here"
               (read-test-file ".mcp-tasks/story/complete/test-story.md")))
        ;; Check tasks moved
        (is (not (.exists (io/file test-fixtures-dir ".mcp-tasks" "story" "story-tasks" "test-story-tasks.md"))))
        (is (.exists (io/file test-fixtures-dir ".mcp-tasks" "story" "story-tasks-complete" "test-story-tasks.md")))
        (is (= "- [x] Task 1\n\nCATEGORY: simple\n"
               (read-test-file ".mcp-tasks/story/story-tasks-complete/test-story-tasks.md"))))
      (cleanup-test-fixtures))))

(deftest complete-story-adds-completion-comment
  ;; Tests that complete-story-impl adds completion comment to story
  (testing "complete-story"
    (testing "adds completion comment to story"
      (setup-test-dir)
      (.mkdirs (io/file test-fixtures-dir ".mcp-tasks" "story" "stories"))
      (write-test-file ".mcp-tasks/story/stories/test-story.md"
                       "# Test Story\n\nStory content")
      (let [config {:base-dir test-fixtures-dir :use-git? false}
            result (#'sut/complete-story-impl
                    config
                    nil
                    {:story-name "test-story"
                     :completion-comment "Successfully implemented"})]
        (is (false? (:isError result)))
        (let [completed (read-test-file ".mcp-tasks/story/complete/test-story.md")]
          (is (re-find #"# Test Story" completed))
          (is (re-find #"---" completed))
          (is (re-find #"Successfully implemented" completed))))
      (cleanup-test-fixtures))))

(deftest complete-story-works-without-tasks-file
  ;; Tests that complete-story-impl works when tasks file doesn't exist
  (testing "complete-story"
    (testing "works when tasks file doesn't exist"
      (setup-test-dir)
      (.mkdirs (io/file test-fixtures-dir ".mcp-tasks" "story" "stories"))
      (write-test-file ".mcp-tasks/story/stories/test-story.md"
                       "# Test Story\n\nStory content")
      (let [config {:base-dir test-fixtures-dir :use-git? false}
            result (#'sut/complete-story-impl
                    config
                    nil
                    {:story-name "test-story"})]
        (is (false? (:isError result)))
        (is (re-find #"marked as complete" (get-in result [:content 0 :text])))
        (is (re-find #"No tasks file found" (get-in result [:content 0 :text])))
        (is (.exists (io/file test-fixtures-dir ".mcp-tasks" "story" "complete" "test-story.md"))))
      (cleanup-test-fixtures))))

(deftest complete-story-errors-when-story-not-found
  ;; Tests that complete-story-impl errors when story file not found
  (testing "complete-story"
    (testing "errors when story file not found"
      (setup-test-dir)
      (let [config {:base-dir test-fixtures-dir :use-git? false}
            result (#'sut/complete-story-impl
                    config
                    nil
                    {:story-name "nonexistent"})]
        (is (true? (:isError result)))
        (is (re-find #"Story file not found"
                     (get-in result [:content 0 :text]))))
      (cleanup-test-fixtures))))

(deftest complete-story-errors-when-already-completed
  ;; Tests that complete-story-impl errors when story already completed
  (testing "complete-story"
    (testing "errors when story already completed"
      (setup-test-dir)
      (.mkdirs (io/file test-fixtures-dir ".mcp-tasks" "story" "stories"))
      (.mkdirs (io/file test-fixtures-dir ".mcp-tasks" "story" "complete"))
      (write-test-file ".mcp-tasks/story/stories/test-story.md" "# Story")
      (write-test-file ".mcp-tasks/story/complete/test-story.md" "# Already Done")
      (let [config {:base-dir test-fixtures-dir :use-git? false}
            result (#'sut/complete-story-impl
                    config
                    nil
                    {:story-name "test-story"})]
        (is (true? (:isError result)))
        (is (re-find #"already completed"
                     (get-in result [:content 0 :text]))))
      (cleanup-test-fixtures))))

(deftest complete-story-returns-modified-files-when-git-mode
  ;; Tests that complete-story-impl returns modified files in git mode
  (testing "complete-story"
    (testing "returns modified files as JSON when git mode enabled"
      (setup-test-dir)
      (.mkdirs (io/file test-fixtures-dir ".mcp-tasks" "story" "stories"))
      (.mkdirs (io/file test-fixtures-dir ".mcp-tasks" "story" "story-tasks"))
      (write-test-file ".mcp-tasks/story/stories/test-story.md" "# Story")
      (write-test-file ".mcp-tasks/story/story-tasks/test-story-tasks.md" "- [x] Task\n\nCATEGORY: simple\n")
      (let [config {:base-dir test-fixtures-dir :use-git? true}
            result (#'sut/complete-story-impl
                    config
                    nil
                    {:story-name "test-story"})
            content (:content result)]
        (is (false? (:isError result)))
        (is (= 2 (count content)))
        (is (= "text" (:type (first content))))
        (is (= "text" (:type (second content))))
        (let [json-data (json/read-str (:text (second content))
                                       :key-fn keyword)]
          (is (= ["story/stories/test-story.md"
                  "story/complete/test-story.md"
                  "story/story-tasks/test-story-tasks.md"
                  "story/story-tasks-complete/test-story-tasks.md"]
                 (:modified-files json-data)))))
      (cleanup-test-fixtures))))

(deftest complete-story-returns-only-message-when-non-git-mode
  ;; Tests that complete-story-impl returns only message in non-git mode
  (testing "complete-story"
    (testing "returns only completion message when git mode disabled"
      (setup-test-dir)
      (.mkdirs (io/file test-fixtures-dir ".mcp-tasks" "story" "stories"))
      (write-test-file ".mcp-tasks/story/stories/test-story.md" "# Story")
      (let [config {:base-dir test-fixtures-dir :use-git? false}
            result (#'sut/complete-story-impl
                    config
                    nil
                    {:story-name "test-story"})
            content (:content result)]
        (is (false? (:isError result)))
        (is (= 1 (count content)) "Should only return completion message")
        (is (= "text" (:type (first content))))
        (is (re-find #"marked as complete" (:text (first content)))))
      (cleanup-test-fixtures))))
