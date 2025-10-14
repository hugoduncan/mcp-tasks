(ns mcp-tasks.story-tools-test
  "Tests for story task filtering using enhanced next-task tool"
  (:require
    [babashka.fs :as fs]
    [clojure.data.json :as json]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-tasks.tasks-file :as tasks-file]
    [mcp-tasks.tools :as tools]))

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

;; next-task with parent-id filter Tests

(deftest next-task-returns-first-incomplete-child-by-parent-id
  ;; Test that next-task with parent-id filter returns the first incomplete child task
  (testing "next-task with parent-id filter"
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
              result (#'tools/next-task-impl
                      config
                      nil
                      {:parent-id 1})]
          (is (false? (:isError result)))
          (let [task (edn/read-string (get-in result [:content 0 :text]))]
            (is (= "First incomplete" (:title task)))
            (is (= "With details" (:description task)))
            (is (= "medium" (:category task)))
            (is (= 3 (:id task)))))))))

(deftest next-task-returns-no-match-when-no-incomplete-children
  ;; Test that next-task with parent-id filter returns no match when no incomplete children
  (testing "next-task with parent-id filter"
    (testing "returns no match when no incomplete children"
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
              result (#'tools/next-task-impl
                      config
                      nil
                      {:parent-id 1})]
          (is (false? (:isError result)))
          (let [data (edn/read-string (get-in result [:content 0 :text]))]
            (is (= "No matching tasks found" (:status data)))))))))

(deftest next-task-by-title-pattern-finds-story
  ;; Test that next-task with title-pattern can find story tasks
  (testing "next-task with title-pattern filter"
    (testing "finds story by title pattern"
      (let [story {:id 1
                   :type :story
                   :title "test-story"
                   :description ""
                   :design ""
                   :category "story"
                   :status :open
                   :meta {}
                   :relations []}
            other-task {:id 2
                        :type :task
                        :title "Some other task"
                        :description ""
                        :design ""
                        :category "simple"
                        :status :open
                        :meta {}
                        :relations []}]
        (write-tasks-ednl [story other-task])
        (let [config {:base-dir *test-dir* :use-git? false}
              result (#'tools/next-task-impl
                      config
                      nil
                      {:title-pattern "test-story"})]
          (is (false? (:isError result)))
          (let [task (edn/read-string (get-in result [:content 0 :text]))]
            (is (= "test-story" (:title task)))
            (is (= "story" (:category task)))
            (is (= 1 (:id task)))))))))

(deftest next-task-combines-multiple-filters
  ;; Test that next-task can combine parent-id and category filters
  (testing "next-task with multiple filters"
    (testing "combines parent-id and category filters"
      (let [story {:id 1
                   :type :story
                   :title "test-story"
                   :description ""
                   :design ""
                   :category "story"
                   :status :open
                   :meta {}
                   :relations []}
            simple-task {:id 2
                         :parent-id 1
                         :type :task
                         :title "Simple task"
                         :description ""
                         :design ""
                         :category "simple"
                         :status :open
                         :meta {}
                         :relations []}
            medium-task {:id 3
                         :parent-id 1
                         :type :task
                         :title "Medium task"
                         :description ""
                         :design ""
                         :category "medium"
                         :status :open
                         :meta {}
                         :relations []}]
        (write-tasks-ednl [story simple-task medium-task])
        (let [config {:base-dir *test-dir* :use-git? false}
              result (#'tools/next-task-impl
                      config
                      nil
                      {:parent-id 1 :category "medium"})]
          (is (false? (:isError result)))
          (let [task (edn/read-string (get-in result [:content 0 :text]))]
            (is (= "Medium task" (:title task)))
            (is (= "medium" (:category task)))
            (is (= 3 (:id task)))))))))
