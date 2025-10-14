(ns mcp-tasks.tools-test
  (:require
    [babashka.fs :as fs]
    [clojure.data.json :as json]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-tasks.tasks :as tasks]
    [mcp-tasks.tasks-file :as tasks-file]
    [mcp-tasks.tools :as sut]))

(def ^:dynamic *test-dir* nil)

(defn- setup-test-dir
  [test-dir]
  (.mkdirs (io/file test-dir ".mcp-tasks")))

(defn- write-ednl-test-file
  "Write tasks as EDNL format to test file."
  [path tasks]
  (let [file-path (str *test-dir* "/.mcp-tasks/" path)]
    (tasks-file/write-tasks file-path tasks)))

(defn- read-ednl-test-file
  "Read tasks from EDNL test file."
  [path]
  (let [file-path (str *test-dir* "/.mcp-tasks/" path)]
    (tasks-file/read-ednl file-path)))

(defn- reset-tasks-state!
  "Reset the tasks namespace global state for testing."
  []
  (reset! tasks/task-ids [])
  (reset! tasks/tasks {})
  (reset! tasks/parent-children {})
  (reset! tasks/child-parent {}))

(defn- test-config
  "Config that points to test fixtures directory."
  []
  {:base-dir *test-dir* :use-git? false})

(defn- test-fixture
  "Fixture that sets up and cleans up test directory for each test."
  [f]
  (let [dir (fs/create-temp-dir {:prefix "mcp-tasks-tools-test-"})]
    (try
      (binding [*test-dir* (str dir)]
        (setup-test-dir *test-dir*)
        (reset-tasks-state!)
        (f))
      (finally
        (fs/delete-tree dir)))))

(use-fixtures :each test-fixture)

;; complete-task-impl tests

(deftest moves-first-task-from-tasks-to-complete
  ;; Tests that the complete-task-impl function correctly moves the first
  ;; task from tasks.ednl to complete.ednl
  (testing "complete-task"
    (testing "moves first task from tasks to complete"
      ;; Create EDNL file with two tasks
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "first task" :description "detail line" :design "" :category "test" :type :task :status :open :meta {} :relations []}
                             {:id 2 :parent-id nil :title "second task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/complete-task-impl
                    (test-config)
                    nil
                    {:category "test"
                     :task-text "first task"})]
        (is (false? (:isError result)))
        ;; Verify complete file has the completed task
        (let [complete-tasks (read-ednl-test-file "complete.ednl")]
          (is (= 1 (count complete-tasks)))
          (is (= "first task" (:title (first complete-tasks))))
          (is (= :closed (:status (first complete-tasks)))))
        ;; Verify tasks file has only the second task
        (let [tasks (read-ednl-test-file "tasks.ednl")]
          (is (= 1 (count tasks)))
          (is (= "second task" (:title (first tasks)))))))))

(deftest adds-completion-comment-when-provided
  ;; Tests that completion comments are appended to completed tasks
  (testing "complete-task"
    (testing "adds completion comment when provided"
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "task with comment" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/complete-task-impl
                    (test-config)
                    nil
                    {:category "test"
                     :task-text "task with comment"
                     :completion-comment "Added feature X"})]
        (is (false? (:isError result)))
        (let [complete-tasks (read-ednl-test-file "complete.ednl")]
          (is (= 1 (count complete-tasks)))
          (is (str/includes? (:description (first complete-tasks)) "Added feature X")))))))

;; Integration Tests

(deftest ^:integration complete-workflow-add-next-complete
  ;; Integration test for complete workflow: add task → next task → complete task
  (testing "complete workflow with EDN storage"
    (testing "add → next → complete workflow"
      ;; Add first task
      (let [result (#'sut/add-task-impl (test-config) nil {:category "test"
                                                           :task-text "First task\nWith description"})]
        (when (:isError result)
          (prn "Add first task error:" result))
        (is (false? (:isError result))))

      ;; Add second task
      (let [result (#'sut/add-task-impl (test-config) nil {:category "test"
                                                           :task-text "Second task"})]
        (when (:isError result)
          (prn "Add second task error:" result))
        (is (false? (:isError result))))

      ;; Get next task - should be first task
      (let [result (#'sut/next-task-impl (test-config) nil {:category "test"})]
        (is (false? (:isError result)))
        (let [response (edn/read-string (get-in result [:content 0 :text]))]
          (is (= "test" (:category response)))
          (is (str/starts-with? (:task response) "First task"))))

      ;; Complete first task
      (let [result (#'sut/complete-task-impl (test-config) nil {:category "test"
                                                                :task-text "First task"})]
        (is (false? (:isError result))))

      ;; Get next task - should now be second task
      (let [result (#'sut/next-task-impl (test-config) nil {:category "test"})]
        (is (false? (:isError result)))
        (let [response (edn/read-string (get-in result [:content 0 :text]))]
          (is (= "test" (:category response)))
          (is (str/starts-with? (:task response) "Second task"))))

      ;; Complete second task
      (let [result (#'sut/complete-task-impl (test-config) nil {:category "test"
                                                                :task-text "Second task"})]
        (is (false? (:isError result))))

      ;; Get next task - should have no more tasks
      (let [result (#'sut/next-task-impl (test-config) nil {:category "test"})]
        (is (false? (:isError result)))
        (let [response (edn/read-string (get-in result [:content 0 :text]))]
          (is (= "No matching tasks found" (:status response))))))))
