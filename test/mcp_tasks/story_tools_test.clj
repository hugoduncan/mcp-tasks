(ns mcp-tasks.story-tools-test
  (:require
    [babashka.fs :as fs]
    [clojure.data.json :as json]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-tasks.story-tools :as sut]
    [mcp-tasks.tasks-file :as tasks-file]))

(def ^:dynamic *test-dir* nil)

(defn- setup-test-dir
  "Create test fixtures directory"
  [test-dir]
  (.mkdirs (io/file test-dir ".mcp-tasks")))

(defn test-fixture
  [f]
  (let [dir (fs/create-temp-dir {:prefix "mcp-tasks-story-test-"})]
    (try
      (binding [*test-dir* (str dir)]
        (setup-test-dir *test-dir*)
        (f))
      (finally
        (fs/delete-tree dir)))))

(use-fixtures :each test-fixture)

(defn- write-tasks-ednl
  "Write tasks to tasks.ednl file"
  [tasks]
  (let [file (io/file *test-dir* ".mcp-tasks" "tasks.ednl")]
    (.mkdirs (.getParentFile file))
    (tasks-file/write-tasks (.getPath file) tasks)))

(defn- read-tasks-ednl
  "Read tasks from tasks.ednl file"
  []
  (let [file (io/file *test-dir* ".mcp-tasks" "tasks.ednl")]
    (when (.exists file)
      (tasks-file/read-ednl (.getPath file)))))

(defn- read-complete-ednl
  "Read tasks from complete.ednl file"
  []
  (let [file (io/file *test-dir* ".mcp-tasks" "complete.ednl")]
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
        (let [config {:base-dir *test-dir* :use-git? false}
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
        (let [config {:base-dir *test-dir* :use-git? false}
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
      (let [config {:base-dir *test-dir* :use-git? false}
            result (#'sut/next-story-task-impl
                    config
                    nil
                    {:story-name "nonexistent"})]
        (is (true? (:isError result)))
        (is (re-find #"Story not found"
                     (get-in result [:content 0 :text])))))))
