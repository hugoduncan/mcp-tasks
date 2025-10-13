(ns mcp-tasks.story-tools-test
  (:require
    [clojure.data.json :as json]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-tasks.story-tools :as sut]
    [mcp-tasks.tasks-file :as tasks-file]))

(def test-fixtures-dir
  "Temporary directory for test fixtures"
  (str (System/getProperty "java.io.tmpdir") "/mcp-tasks-story-test-" (rand-int 100000)))

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

(defn test-fixture
  [f]
  (setup-test-dir)
  (try
    (f)
    (finally
      (cleanup-test-fixtures))))

(use-fixtures :each test-fixture)

(defn- write-tasks-ednl
  "Write tasks to tasks.ednl file"
  [tasks]
  (let [file (io/file test-fixtures-dir ".mcp-tasks" "tasks.ednl")]
    (.mkdirs (.getParentFile file))
    (tasks-file/write-tasks (.getPath file) tasks)))

(defn- read-tasks-ednl
  "Read tasks from tasks.ednl file"
  []
  (let [file (io/file test-fixtures-dir ".mcp-tasks" "tasks.ednl")]
    (when (.exists file)
      (tasks-file/read-ednl (.getPath file)))))

(defn- read-complete-ednl
  "Read tasks from complete.ednl file"
  []
  (let [file (io/file test-fixtures-dir ".mcp-tasks" "complete.ednl")]
    (when (.exists file)
      (tasks-file/read-ednl (.getPath file)))))

;; next-story-task Tests

(deftest next-story-task-returns-first-incomplete-child
  ;; Test that next-story-task-impl returns the first incomplete child task
  (testing "next-story-task"
    (testing "returns first incomplete child task"
      (let [story {:id 1
                   :type :story
                   :title "test-story"
                   :description ""
                   :design ""
                   :category "story"
                   :status :open
                   :meta {}
                   :relations []}
            completed-task {:id 2
                            :parent-id 1
                            :type :task
                            :title "Already done"
                            :description ""
                            :design ""
                            :category "simple"
                            :status :closed
                            :meta {}
                            :relations []}
            first-incomplete {:id 3
                              :parent-id 1
                              :type :task
                              :title "First incomplete"
                              :description "With details"
                              :design ""
                              :category "medium"
                              :status :open
                              :meta {}
                              :relations []}
            second-incomplete {:id 4
                               :parent-id 1
                               :type :task
                               :title "Second incomplete"
                               :description ""
                               :design ""
                               :category "simple"
                               :status :open
                               :meta {}
                               :relations []}]
        (write-tasks-ednl [story completed-task first-incomplete second-incomplete])
        (let [config {:base-dir test-fixtures-dir :use-git? false}
              result (#'sut/next-story-task-impl
                      config
                      nil
                      {:story-name "test-story"})]
          (is (false? (:isError result)))
          (let [data (edn/read-string (get-in result [:content 0 :text]))]
            (is (= "First incomplete\nWith details" (:task-text data)))
            (is (= "medium" (:category data)))
            (is (= 3 (:task-id data)))
            (is (= 1 (:task-index data)))))))))

(deftest next-story-task-returns-nil-when-no-incomplete
  ;; Test that next-story-task-impl returns nil values when no incomplete tasks
  (testing "next-story-task"
    (testing "returns nil values when no incomplete tasks"
      (let [story {:id 1
                   :type :story
                   :title "test-story"
                   :description ""
                   :design ""
                   :category "story"
                   :status :open
                   :meta {}
                   :relations []}
            completed-task {:id 2
                            :parent-id 1
                            :type :task
                            :title "Already done"
                            :description ""
                            :design ""
                            :category "simple"
                            :status :closed
                            :meta {}
                            :relations []}]
        (write-tasks-ednl [story completed-task])
        (let [config {:base-dir test-fixtures-dir :use-git? false}
              result (#'sut/next-story-task-impl
                      config
                      nil
                      {:story-name "test-story"})]
          (is (false? (:isError result)))
          (let [data (edn/read-string (get-in result [:content 0 :text]))]
            (is (nil? (:task-text data)))
            (is (nil? (:category data)))
            (is (nil? (:task-id data)))
            (is (nil? (:task-index data)))))))))

(deftest next-story-task-errors-when-story-not-found
  ;; Test that next-story-task-impl errors when story not found
  (testing "next-story-task"
    (testing "errors when story not found"
      (write-tasks-ednl [])
      (let [config {:base-dir test-fixtures-dir :use-git? false}
            result (#'sut/next-story-task-impl
                    config
                    nil
                    {:story-name "nonexistent"})]
        (is (true? (:isError result)))
        (is (re-find #"Story not found"
                     (get-in result [:content 0 :text])))))))

;; complete-story-task Tests

(deftest complete-story-task-marks-task-complete-by-id
  ;; Test that complete-story-task-impl marks specified task complete by ID
  (testing "complete-story-task"
    (testing "marks specified task complete by ID"
      (let [story {:id 1
                   :type :story
                   :title "test-story"
                   :description ""
                   :design ""
                   :category "story"
                   :status :open
                   :meta {}
                   :relations []}
            task {:id 2
                  :parent-id 1
                  :type :task
                  :title "Task to complete"
                  :description ""
                  :design ""
                  :category "simple"
                  :status :open
                  :meta {}
                  :relations []}]
        (write-tasks-ednl [story task])
        (let [config {:base-dir test-fixtures-dir :use-git? false}
              result (#'sut/complete-story-task-impl
                      config
                      nil
                      {:story-name "test-story"
                       :task-id 2})]
          (is (false? (:isError result)))
          (is (re-find #"completed" (get-in result [:content 0 :text])))
          ;; Verify task was moved to complete.ednl
          (let [tasks (read-tasks-ednl)
                complete (read-complete-ednl)]
            (is (= 1 (count tasks))) ; Only story remains
            (is (= 1 (count complete))) ; Task moved to complete
            (is (= 2 (:id (first complete))))
            (is (= :closed (:status (first complete))))))))))

(deftest complete-story-task-adds-completion-comment
  ;; Test that complete-story-task-impl adds completion comment
  (testing "complete-story-task"
    (testing "adds completion comment when provided"
      (let [story {:id 1
                   :type :story
                   :title "test-story"
                   :description ""
                   :design ""
                   :category "story"
                   :status :open
                   :meta {}
                   :relations []}
            task {:id 2
                  :parent-id 1
                  :type :task
                  :title "Task to complete"
                  :description "Original description"
                  :design ""
                  :category "simple"
                  :status :open
                  :meta {}
                  :relations []}]
        (write-tasks-ednl [story task])
        (let [config {:base-dir test-fixtures-dir :use-git? false}
              result (#'sut/complete-story-task-impl
                      config
                      nil
                      {:story-name "test-story"
                       :task-id 2
                       :completion-comment "Added feature X"})]
          (is (false? (:isError result)))
          (let [complete (read-complete-ednl)
                completed-task (first complete)]
            (is (re-find #"Added feature X" (:description completed-task)))))))))

(deftest complete-story-task-errors-when-task-not-found
  ;; Test that complete-story-task-impl errors when task not found
  (testing "complete-story-task"
    (testing "errors when task not found"
      (let [story {:id 1
                   :type :story
                   :title "test-story"
                   :description ""
                   :design ""
                   :category "story"
                   :status :open
                   :meta {}
                   :relations []}]
        (write-tasks-ednl [story])
        (let [config {:base-dir test-fixtures-dir :use-git? false}
              result (#'sut/complete-story-task-impl
                      config
                      nil
                      {:story-name "test-story"
                       :task-id 999})]
          (is (true? (:isError result)))
          (is (re-find #"Task not found"
                       (get-in result [:content 0 :text]))))))))

(deftest complete-story-task-returns-modified-files-when-git-mode
  ;; Test that complete-story-task-impl returns modified files in git mode
  (testing "complete-story-task"
    (testing "returns modified files as JSON when git mode enabled"
      (let [story {:id 1
                   :type :story
                   :title "test-story"
                   :description ""
                   :design ""
                   :category "story"
                   :status :open
                   :meta {}
                   :relations []}
            task {:id 2
                  :parent-id 1
                  :type :task
                  :title "Task to complete"
                  :description ""
                  :design ""
                  :category "simple"
                  :status :open
                  :meta {}
                  :relations []}]
        (write-tasks-ednl [story task])
        (let [config {:base-dir test-fixtures-dir :use-git? true}
              result (#'sut/complete-story-task-impl
                      config
                      nil
                      {:story-name "test-story"
                       :task-id 2})
              content (:content result)]
          (is (false? (:isError result)))
          (is (= 2 (count content)))
          (is (= "text" (:type (first content))))
          (is (= "text" (:type (second content))))
          (let [json-data (json/read-str (:text (second content))
                                         :key-fn keyword)]
            (is (= ["tasks.ednl" "complete.ednl"]
                   (:modified-files json-data)))))))))

;; complete-story Tests

(deftest complete-story-moves-story-and-tasks-to-complete
  ;; Test that complete-story-impl moves story and all tasks to complete
  (testing "complete-story"
    (testing "moves story and all child tasks to complete"
      (let [story {:id 1
                   :type :story
                   :title "test-story"
                   :description "Story content"
                   :design ""
                   :category "story"
                   :status :open
                   :meta {}
                   :relations []}
            task1 {:id 2
                   :parent-id 1
                   :type :task
                   :title "Task 1"
                   :description ""
                   :design ""
                   :category "simple"
                   :status :closed
                   :meta {}
                   :relations []}
            task2 {:id 3
                   :parent-id 1
                   :type :task
                   :title "Task 2"
                   :description ""
                   :design ""
                   :category "medium"
                   :status :closed
                   :meta {}
                   :relations []}]
        (write-tasks-ednl [story task1 task2])
        (let [config {:base-dir test-fixtures-dir :use-git? false}
              result (#'sut/complete-story-impl
                      config
                      nil
                      {:story-name "test-story"})]
          (is (false? (:isError result)))
          (is (re-find #"marked as complete" (get-in result [:content 0 :text])))
          (is (re-find #"2 task\(s\) archived" (get-in result [:content 0 :text])))
          ;; Verify all moved to complete
          (let [tasks (read-tasks-ednl)
                complete (read-complete-ednl)]
            (is (empty? tasks))
            (is (= 3 (count complete)))
            (is (= #{1 2 3} (set (map :id complete))))))))))

(deftest complete-story-adds-completion-comment
  ;; Test that complete-story-impl adds completion comment to story
  (testing "complete-story"
    (testing "adds completion comment to story"
      (let [story {:id 1
                   :type :story
                   :title "test-story"
                   :description "Story content"
                   :design ""
                   :category "story"
                   :status :open
                   :meta {}
                   :relations []}]
        (write-tasks-ednl [story])
        (let [config {:base-dir test-fixtures-dir :use-git? false}
              result (#'sut/complete-story-impl
                      config
                      nil
                      {:story-name "test-story"
                       :completion-comment "Successfully implemented"})]
          (is (false? (:isError result)))
          (let [complete (read-complete-ednl)
                completed-story (first complete)]
            (is (re-find #"Successfully implemented" (:description completed-story)))))))))

(deftest complete-story-errors-when-story-not-found
  ;; Test that complete-story-impl errors when story not found
  (testing "complete-story"
    (testing "errors when story not found"
      (write-tasks-ednl [])
      (let [config {:base-dir test-fixtures-dir :use-git? false}
            result (#'sut/complete-story-impl
                    config
                    nil
                    {:story-name "nonexistent"})]
        (is (true? (:isError result)))
        (is (re-find #"Story not found"
                     (get-in result [:content 0 :text])))))))

(deftest complete-story-errors-when-incomplete-tasks-exist
  ;; Test that complete-story-impl errors when incomplete tasks exist
  (testing "complete-story"
    (testing "errors when incomplete tasks exist"
      (let [story {:id 1
                   :type :story
                   :title "test-story"
                   :description ""
                   :design ""
                   :category "story"
                   :status :open
                   :meta {}
                   :relations []}
            task {:id 2
                  :parent-id 1
                  :type :task
                  :title "Incomplete task"
                  :description ""
                  :design ""
                  :category "simple"
                  :status :open
                  :meta {}
                  :relations []}]
        (write-tasks-ednl [story task])
        (let [config {:base-dir test-fixtures-dir :use-git? false}
              result (#'sut/complete-story-impl
                      config
                      nil
                      {:story-name "test-story"})]
          (is (true? (:isError result)))
          (is (re-find #"incomplete tasks"
                       (get-in result [:content 0 :text]))))))))

(deftest complete-story-returns-modified-files-when-git-mode
  ;; Test that complete-story-impl returns modified files in git mode
  (testing "complete-story"
    (testing "returns modified files as JSON when git mode enabled"
      (let [story {:id 1
                   :type :story
                   :title "test-story"
                   :description ""
                   :design ""
                   :category "story"
                   :status :open
                   :meta {}
                   :relations []}]
        (write-tasks-ednl [story])
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
            (is (= ["tasks.ednl" "complete.ednl"]
                   (:modified-files json-data)))))))))
