(ns mcp-tasks.story-tools-test
  "Tests for story task filtering using enhanced select-tasks tool"
  (:require
    [babashka.fs :as fs]
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

;; select-tasks with parent-id filter Tests

(deftest select-tasks-returns-first-incomplete-child-by-parent-id
  ;; Test that select-tasks with parent-id filter returns the first incomplete child task
  (testing "select-tasks with parent-id filter"
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
              result (#'tools/select-tasks-impl
                      config
                      nil
                      {:parent-id 1 :limit 1})]
          (is (false? (:isError result)))
          (let [response (edn/read-string (get-in result [:content 0 :text]))
                task (first (:tasks response))]
            (is (= "First incomplete" (:title task)))
            (is (= "With details" (:description task)))
            (is (= "medium" (:category task)))
            (is (= 3 (:id task)))))))))

(deftest select-tasks-returns-no-match-when-no-incomplete-children
  ;; Test that select-tasks with parent-id filter returns empty tasks when no incomplete children
  (testing "select-tasks with parent-id filter"
    (testing "returns empty tasks when no incomplete children"
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
              result (#'tools/select-tasks-impl
                      config
                      nil
                      {:parent-id 1})]
          (is (false? (:isError result)))
          (let [response (edn/read-string (get-in result [:content 0 :text]))]
            (is (empty? (:tasks response)))))))))

(deftest select-tasks-by-title-pattern-finds-story
  ;; Test that select-tasks with title-pattern can find story tasks
  (testing "select-tasks with title-pattern filter"
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
              result (#'tools/select-tasks-impl
                      config
                      nil
                      {:title-pattern "test-story" :limit 1})]
          (is (false? (:isError result)))
          (let [response (edn/read-string (get-in result [:content 0 :text]))
                task (first (:tasks response))]
            (is (= "test-story" (:title task)))
            (is (= "story" (:category task)))
            (is (= 1 (:id task)))))))))

(deftest select-tasks-combines-multiple-filters
  ;; Test that select-tasks can combine parent-id and category filters
  (testing "select-tasks with multiple filters"
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
              result (#'tools/select-tasks-impl
                      config
                      nil
                      {:parent-id 1 :category "medium" :limit 1})]
          (is (false? (:isError result)))
          (let [response (edn/read-string (get-in result [:content 0 :text]))
                task (first (:tasks response))]
            (is (= "Medium task" (:title task)))
            (is (= "medium" (:category task)))
            (is (= 3 (:id task)))))))))
