(ns mcp-tasks.tools-test
  (:require
    [babashka.fs :as fs]
    [clojure.data.json :as json]
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
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 1
          :parent-id nil
          :title "first task"
          :description "detail line"
          :design ""
          :category "test"
          :type :task
          :status :open
          :meta {}
          :relations []}
         {:id 2
          :parent-id nil
          :title "second task"
          :description ""
          :design ""
          :category "test"
          :type :task
          :status :open
          :meta {}
          :relations []}])
      (let [result (#'sut/complete-task-impl
                    (test-config)
                    nil
                    {:category "test"
                     :title "first task"})]
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
                     :title "task with comment"
                     :completion-comment "Added feature X"})]
        (is (false? (:isError result)))
        (let [complete-tasks (read-ednl-test-file "complete.ednl")]
          (is (= 1 (count complete-tasks)))
          (is (str/includes? (:description (first complete-tasks)) "Added feature X")))))))

(deftest completes-task-by-id
  ;; Tests that complete-task-impl can find and complete a task by exact ID
  (testing "complete-task"
    (testing "completes task by exact task-id"
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "first task" :description "detail" :design "" :category "test" :type :task :status :open :meta {} :relations []}
                             {:id 2 :parent-id nil :title "second task" :description "" :design "" :category "other" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/complete-task-impl
                    (test-config)
                    nil
                    {:task-id 2})]
        (is (false? (:isError result)))
        ;; Verify task 2 is complete
        (let [complete-tasks (read-ednl-test-file "complete.ednl")]
          (is (= 1 (count complete-tasks)))
          (is (= "second task" (:title (first complete-tasks))))
          (is (= :closed (:status (first complete-tasks)))))
        ;; Verify task 1 remains in tasks
        (let [tasks (read-ednl-test-file "tasks.ednl")]
          (is (= 1 (count tasks)))
          (is (= "first task" (:title (first tasks)))))))))

(deftest completes-task-by-exact-title
  ;; Tests that complete-task-impl finds tasks by exact title match
  (testing "complete-task"
    (testing "completes task by exact title match"
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 1
          :parent-id nil
          :title "first task"
          :description "detail"
          :design ""
          :category "test"
          :type :task
          :status :open
          :meta {}
          :relations []}
         {:id 2
          :parent-id nil
          :title "second task"
          :description ""
          :design ""
          :category "test"
          :type :task
          :status :open
          :meta {}
          :relations []}])
      (let [result (#'sut/complete-task-impl
                    (test-config)
                    nil
                    {:title "second task"})]
        (is (false? (:isError result)))
        ;; Verify second task is complete
        (let [complete-tasks (read-ednl-test-file "complete.ednl")]
          (is (= 1 (count complete-tasks)))
          (is (= "second task" (:title (first complete-tasks)))))))))

(deftest rejects-ambiguous-title
  ;; Tests that complete-task-impl rejects when multiple tasks have the same title
  (testing "complete-task"
    (testing "rejects multiple tasks with same title"
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 1 :parent-id nil :title "duplicate" :description "first" :design "" :category "test" :type :task :status :open :meta {} :relations []}
         {:id 2 :parent-id nil :title "duplicate" :description "second" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/complete-task-impl
                    (test-config)
                    nil
                    {:title "duplicate"})]
        (is (true? (:isError result)))
        (is (str/includes?
              (get-in result [:content 0 :text])
              "Multiple tasks found"))))))

(deftest verifies-id-and-text-match
  ;; Tests that when both task-id and title are provided, they must refer to the same task
  (testing "complete-task"
    (testing "verifies task-id and title refer to same task"
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "first task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}
                             {:id 2 :parent-id nil :title "second task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      ;; Mismatched ID and text
      (let [result (#'sut/complete-task-impl
                    (test-config)
                    nil
                    {:task-id 1 :title "second task"})]
        (is (true? (:isError result)))
        (is (str/includes? (get-in result [:content 0 :text]) "do not refer to the same task")))
      ;; Matching ID and text
      (let [result (#'sut/complete-task-impl
                    (test-config)
                    nil
                    {:task-id 2 :title "second task"})]
        (is (false? (:isError result)))))))

(deftest requires-at-least-one-identifier
  ;; Tests that either task-id or title must be provided
  (testing "complete-task"
    (testing "requires either task-id or title"
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/complete-task-impl
                    (test-config)
                    nil
                    {})]
        (is (true? (:isError result)))
        (is (str/includes? (get-in result [:content 0 :text]) "Must provide either"))))))

;; Integration Tests

;; add-task-impl tests

(deftest add-task-returns-structured-data
  ;; Tests that add-task-impl returns both text message and structured data
  (testing "add-task"
    (testing "returns text message and structured data"
      (let [result (#'sut/add-task-impl
                    (test-config)
                    nil
                    {:category  "test"
                     :title "Test task"
                     :description "With description"})]
        (is (false? (:isError result)))
        (is (= 2 (count (:content result))))
        ;; First content item is text message
        (let [text-content (first (:content result))]
          (is (= "text" (:type text-content)))
          (is (str/includes? (:text text-content) "Task added to")))
        ;; Second content item is structured data
        (let [data-content (second (:content result))
              data (json/read-str (:text data-content) :key-fn keyword)]
          (is (= "text" (:type data-content)))
          (is (contains? data :task))
          (is (contains? data :metadata))
          ;; Verify task fields
          (let [task (:task data)]
            (is (contains? task :id))
            (is (= "Test task" (:title task)))
            (is (= "test" (:category task)))
            (is (= "task" (:type task)))
            (is (= "open" (:status task))))
          ;; Verify metadata
          (let [metadata (:metadata data)]
            (is (contains? metadata :file))
            (is (= "add-task" (:operation metadata)))))))))

(deftest add-task-includes-parent-id-for-story-tasks
  ;; Tests that add-task includes parent-id in structured data for story tasks
  (testing "add-task"
    (testing "includes parent-id for story tasks"
      ;; First create a story task
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 1
          :parent-id nil
          :title "test story"
          :description ""
          :design ""
          :category "story"
          :type :story
          :status :open
          :meta {}
          :relations []}])
      ;; Load the story into memory
      (tasks/load-tasks! (str *test-dir* "/.mcp-tasks/tasks.ednl"))
      ;; Add a child task to the story
      (let [result (#'sut/add-task-impl
                    (test-config)
                    nil
                    {:category   "simple"
                     :title      "Child task"
                     :parent-id  1})]
        (is (false? (:isError result)))
        ;; Verify structured data includes parent-id
        (let [data-content (second (:content result))
              data (json/read-str (:text data-content) :key-fn keyword)
              task (:task data)]
          (is (= 1 (:parent-id task)))
          (is (= "Child task" (:title task)))
          (is (= "simple" (:category task))))))))

(deftest add-task-omits-nil-parent-id
  ;; Tests that parent-id is included in response but is nil for non-story tasks
  (testing "add-task"
    (testing "includes parent-id field (as nil) for non-story tasks"
      (let [result (#'sut/add-task-impl
                    (test-config)
                    nil
                    {:category "test"
                     :title "Regular task"})]
        (is (false? (:isError result)))
        (let [data-content (second (:content result))
              data (json/read-str (:text data-content) :key-fn keyword)
              task (:task data)]
          ;; parent-id should be present in select-keys but will be nil
          (is (nil? (:parent-id task)))
          (is (= "Regular task" (:title task))))))))

(deftest ^:integration complete-workflow-add-next-complete
  ;; Integration test for complete workflow: add task → select task → complete task
  (testing "complete workflow with EDN storage"
    (testing "add → select → complete workflow"
      ;; Add first task
      (let [result (#'sut/add-task-impl
                    (test-config)
                    nil
                    {:category  "test"
                     :title "First task"
                     :description "With description"})]
        (is (false? (:isError result))))

      ;; Add second task
      (let [result (#'sut/add-task-impl
                    (test-config)
                    nil
                    {:category  "test"
                     :title "Second task"})]
        (is (false? (:isError result))))

      ;; Get next task - should be first task
      (let [result (#'sut/select-tasks-impl
                    (test-config)
                    nil
                    {:category "test" :limit 1})]
        (is (false? (:isError result)))
        (let [response (json/read-str
                         (get-in result [:content 0 :text])
                         :key-fn keyword)
              task (first (:tasks response))]
          (is (= "test" (:category task)))
          (is (= "First task" (:title task)))
          (is (= "With description" (:description task)))))

      ;; Complete first task
      (let [result (#'sut/complete-task-impl
                    (test-config)
                    nil
                    {:category  "test"
                     :title "First task"})]
        (is (false? (:isError result))))

      ;; Get next task - should now be second task
      (let [result (#'sut/select-tasks-impl
                    (test-config)
                    nil
                    {:category "test" :limit 1})]
        (is (false? (:isError result)))
        (let [response (json/read-str
                         (get-in result [:content 0 :text])
                         :key-fn keyword)
              task (first (:tasks response))]
          (is (= "test" (:category task)))
          (is (= "Second task" (:title task)))))

      ;; Complete second task
      (let [result (#'sut/complete-task-impl
                    (test-config)
                    nil
                    {:category  "test"
                     :title "Second task"})]
        (is (false? (:isError result))))

      ;; Get next task - should have no more tasks
      (let [result (#'sut/select-tasks-impl
                    (test-config)
                    nil
                    {:category "test" :limit 1})]
        (is (false? (:isError result)))
        (let [response (json/read-str
                         (get-in result [:content 0 :text]) :key-fn keyword)]
          (is (empty? (:tasks response))))))))

(deftest select-tasks-returns-multiple-tasks
  ;; Test select-tasks returns all matching tasks
  (testing "select-tasks returns multiple matching tasks"
    ;; Add three tasks
    (#'sut/add-task-impl (test-config) nil {:category "test" :title "Task One"})
    (#'sut/add-task-impl (test-config) nil {:category "test" :title "Task Two"})
    (#'sut/add-task-impl (test-config) nil {:category "test" :title "Task Three"})

    (let [result (#'sut/select-tasks-impl (test-config) nil {:category "test"})
          response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
      (is (false? (:isError result)))
      (is (= 3 (count (:tasks response))))
      (is (= 3 (get-in response [:metadata :count])))
      (is (= 3 (get-in response [:metadata :total-matches])))
      (is (false? (get-in response [:metadata :limited?])))
      (is (= ["Task One" "Task Two" "Task Three"]
             (map :title (:tasks response)))))))

(deftest select-tasks-limit-parameter
  ;; Test :limit parameter correctly limits results
  (testing "select-tasks :limit parameter"
    ;; Add five tasks
    (#'sut/add-task-impl (test-config) nil {:category "test" :title "Task 1"})
    (#'sut/add-task-impl (test-config) nil {:category "test" :title "Task 2"})
    (#'sut/add-task-impl (test-config) nil {:category "test" :title "Task 3"})
    (#'sut/add-task-impl (test-config) nil {:category "test" :title "Task 4"})
    (#'sut/add-task-impl (test-config) nil {:category "test" :title "Task 5"})

    (testing "returns up to limit tasks"
      (let [result (#'sut/select-tasks-impl (test-config) nil {:category "test" :limit 3})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
        (is (false? (:isError result)))
        (is (= 3 (count (:tasks response))))
        (is (= 3 (get-in response [:metadata :count])))
        (is (= 5 (get-in response [:metadata :total-matches])))
        (is (true? (get-in response [:metadata :limited?])))
        (is (= ["Task 1" "Task 2" "Task 3"]
               (map :title (:tasks response))))))

    (testing "uses default limit of 5"
      (let [result (#'sut/select-tasks-impl (test-config) nil {:category "test"})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
        (is (false? (:isError result)))
        (is (= 5 (count (:tasks response))))
        (is (= 5 (get-in response [:metadata :count])))
        (is (= 5 (get-in response [:metadata :total-matches])))
        (is (false? (get-in response [:metadata :limited?])))))))

(deftest select-tasks-unique-constraint
  ;; Test :unique enforces 0 or 1 task
  (testing "select-tasks :unique constraint"
    (testing "returns task when exactly one matches"
      (#'sut/add-task-impl (test-config) nil {:category "test" :title "Unique Task"})

      (let [result (#'sut/select-tasks-impl (test-config) nil {:category "test" :unique true})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
        (is (false? (:isError result)))
        (is (= 1 (count (:tasks response))))
        (is (= 1 (get-in response [:metadata :count])))
        (is (= 1 (get-in response [:metadata :total-matches])))
        (is (= "Unique Task" (get-in response [:tasks 0 :title])))))

    (testing "returns empty when no matches"
      (let [result (#'sut/select-tasks-impl (test-config) nil {:category "nonexistent" :unique true})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
        (is (false? (:isError result)))
        (is (= 0 (count (:tasks response))))
        (is (= 0 (get-in response [:metadata :count])))
        (is (= 0 (get-in response [:metadata :total-matches])))))

    (testing "returns error when multiple tasks match"
      (#'sut/add-task-impl (test-config) nil {:category "test" :title "Task One"})
      (#'sut/add-task-impl (test-config) nil {:category "test" :title "Task Two"})

      (let [result (#'sut/select-tasks-impl (test-config) nil {:category "test" :unique true})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
        (is (false? (:isError result)))
        (is (contains? response :error))
        (is (str/includes? (:error response) "Multiple tasks matched"))
        (is (= 3 (get-in response [:metadata :total-matches])))))))

(deftest select-tasks-validation-errors
  ;; Test parameter validation errors
  (testing "select-tasks validation errors"
    (testing "non-positive limit returns error"
      (let [result (#'sut/select-tasks-impl (test-config) nil {:limit 0})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
        (is (false? (:isError result)))
        (is (contains? response :error))
        (is (str/includes? (:error response) "positive integer"))
        (is (= 0 (get-in response [:metadata :provided-limit])))))

    (testing "negative limit returns error"
      (let [result (#'sut/select-tasks-impl (test-config) nil {:limit -5})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
        (is (false? (:isError result)))
        (is (contains? response :error))
        (is (str/includes? (:error response) "positive integer"))))

    (testing "limit > 1 with unique true returns error"
      (let [result (#'sut/select-tasks-impl (test-config) nil {:limit 5 :unique true})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
        (is (false? (:isError result)))
        (is (contains? response :error))
        (is (str/includes? (:error response) "limit must be 1 when unique"))
        (is (= 5 (get-in response [:metadata :provided-limit])))
        (is (true? (get-in response [:metadata :unique])))))))

(deftest select-tasks-empty-results
  ;; Test empty results return empty tasks vector
  (testing "select-tasks empty results"
    (let [result (#'sut/select-tasks-impl (test-config) nil {:category "nonexistent"})
          response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
      (is (false? (:isError result)))
      (is (= [] (:tasks response)))
      (is (= 0 (get-in response [:metadata :count])))
      (is (= 0 (get-in response [:metadata :total-matches])))
      (is (false? (get-in response [:metadata :limited?]))))))

(deftest select-tasks-metadata-accuracy
  ;; Test metadata contains accurate information
  (testing "select-tasks metadata accuracy"
    ;; Add tasks
    (#'sut/add-task-impl (test-config) nil {:category "test" :title "Task A"})
    (#'sut/add-task-impl (test-config) nil {:category "test" :title "Task B"})
    (#'sut/add-task-impl (test-config) nil {:category "other" :title "Task C"})

    (testing "metadata reflects filtering and limiting"
      (let [result (#'sut/select-tasks-impl (test-config) nil {:category "test" :limit 1})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
        (is (= 1 (get-in response [:metadata :count])))
        (is (= 2 (get-in response [:metadata :total-matches])))
        (is (true? (get-in response [:metadata :limited?])))))

    (testing "metadata when not limited"
      (let [result (#'sut/select-tasks-impl (test-config) nil {:category "test" :limit 10})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
        (is (= 2 (get-in response [:metadata :count])))
        (is (= 2 (get-in response [:metadata :total-matches])))
        (is (false? (get-in response [:metadata :limited?])))))))

(deftest select-tasks-type-filter
  ;; Test :type parameter filters tasks by type
  (testing "select-tasks :type filter"
    ;; Add tasks with different types
    (#'sut/add-task-impl (test-config) nil {:category "test" :title "Regular task" :type "task"})
    (#'sut/add-task-impl (test-config) nil {:category "test" :title "Bug fix" :type "bug"})
    (#'sut/add-task-impl (test-config) nil {:category "test" :title "New feature" :type "feature"})
    (#'sut/add-task-impl (test-config) nil {:category "test" :title "Another task" :type "task"})

    (testing "filters by type task"
      (let [result (#'sut/select-tasks-impl (test-config) nil {:type "task"})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
        (is (false? (:isError result)))
        (is (= 2 (count (:tasks response))))
        (is (= ["Regular task" "Another task"]
               (map :title (:tasks response))))))

    (testing "filters by type bug"
      (let [result (#'sut/select-tasks-impl (test-config) nil {:type "bug"})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
        (is (false? (:isError result)))
        (is (= 1 (count (:tasks response))))
        (is (= "Bug fix" (get-in response [:tasks 0 :title])))))

    (testing "filters by type feature"
      (let [result (#'sut/select-tasks-impl (test-config) nil {:type "feature"})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
        (is (false? (:isError result)))
        (is (= 1 (count (:tasks response))))
        (is (= "New feature" (get-in response [:tasks 0 :title])))))

    (testing "combines type filter with category filter"
      (#'sut/add-task-impl (test-config) nil {:category "other" :title "Other bug" :type "bug"})

      (let [result (#'sut/select-tasks-impl (test-config) nil {:category "test" :type "bug"})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
        (is (false? (:isError result)))
        (is (= 1 (count (:tasks response))))
        (is (= "Bug fix" (get-in response [:tasks 0 :title])))))))
