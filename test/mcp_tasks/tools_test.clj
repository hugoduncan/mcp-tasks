(ns mcp-tasks.tools-test
  (:require
    [babashka.fs :as fs]
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.java.shell :as sh]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-tasks.tasks :as tasks]
    [mcp-tasks.tasks-file :as tasks-file]
    [mcp-tasks.tools :as sut]))

(def ^:dynamic *test-dir* nil)

(defn- setup-test-dir
  [test-dir]
  (fs/create-dirs (io/file test-dir ".mcp-tasks")))

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
  (reset! tasks/child-parent {})
  (vreset! tasks/next-id 1))

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

(deftest completes-story-child-without-archiving
  ;; Tests that completing a story child task keeps it in tasks.ednl with :status :closed
  ;; and does NOT move it to complete.ednl
  (testing "complete-task"
    (testing "story child remains in tasks.ednl, closed, not archived"
      ;; Prepare two tasks: one story, one child
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 10 :parent-id nil :title "Parent story" :description "" :design "" :category "story" :type :story :status :open :meta {} :relations []}
         {:id 11 :parent-id 10 :title "Child task" :description "" :design "" :category "simple" :type :task :status :open :meta {} :relations []}])
      ;; Invoke completion on the child
      (let [result (#'sut/complete-task-impl
                    (test-config)
                    nil
                    {:task-id 11})]
        (is (false? (:isError result)))
        ;; Verify message doesn't say "moved to"
        (is (str/includes? (get-in result [:content 0 :text]) "Task 11 completed"))
        (is (not (str/includes? (get-in result [:content 0 :text]) "moved to")))
        ;; tasks.ednl: child turns :closed but stays in file
        (let [tasks (read-ednl-test-file "tasks.ednl")]
          (is (= 2 (count tasks)))
          (is (= :open (:status (first tasks))))
          (is (= :closed (:status (second tasks))))
          (is (= 11 (:id (second tasks)))))
        ;; complete.ednl remains empty
        (is (empty? (read-ednl-test-file "complete.ednl")))))))

;; Integration Tests

;; add-task-impl tests

(deftest add-task-returns-structured-data
  ;; Tests that add-task-impl returns both text message and structured data
  (testing "add-task"
    (testing "returns text message and structured data"
      (let [result (#'sut/add-task-impl
                    (test-config)
                    nil
                    {:category "test"
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
                    {:category "simple"
                     :title "Child task"
                     :parent-id 1})]
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

(deftest add-task-error-parent-not-found
  ;; Tests error response when parent-id references non-existent task
  (testing "add-task"
    (testing "returns error when parent task not found"
      (let [result (#'sut/add-task-impl
                    (test-config)
                    nil
                    {:category "simple"
                     :title "Child task"
                     :parent-id 999})]
        (is (true? (:isError result)))
        (is (= 2 (count (:content result))))
        ;; First content is error message
        (let [text-content (first (:content result))]
          (is (= "text" (:type text-content)))
          (is (= "Parent story not found" (:text text-content))))
        ;; Second content is structured error data
        (let [data-content (second (:content result))
              data (json/read-str (:text data-content) :key-fn keyword)]
          (is (= "text" (:type data-content)))
          (is (contains? data :error))
          (is (contains? data :metadata))
          (is (= "Parent story not found" (:error data)))
          ;; Verify metadata contains operation context
          (let [metadata (:metadata data)]
            (is (= "add-task" (:attempted-operation metadata)))
            (is (= 999 (:parent-id metadata)))
            (is (= "Child task" (:title metadata)))
            (is (= "simple" (:category metadata)))
            (is (contains? metadata :file))))))))

(deftest add-task-error-response-structure
  ;; Tests that all error responses follow the expected format
  (testing "add-task"
    (testing "error responses have correct structure"
      (let [result (#'sut/add-task-impl
                    (test-config)
                    nil
                    {:category "test"
                     :title "Test task"
                     :parent-id 888})]
        (is (true? (:isError result)))
        ;; Verify response structure
        (is (map? result))
        (is (contains? result :content))
        (is (contains? result :isError))
        (is (vector? (:content result)))
        (is (= 2 (count (:content result))))
        ;; Both content items should be text type
        (is (every? #(= "text" (:type %)) (:content result)))
        ;; Second content should be valid JSON with error/metadata
        (let [data-text (:text (second (:content result)))
              data (json/read-str data-text :key-fn keyword)]
          (is (contains? data :error))
          (is (string? (:error data)))
          (is (contains? data :metadata))
          (is (map? (:metadata data))))))))

(deftest add-task-schema-validation-error
  ;; Tests that schema validation errors throw exceptions
  (testing "add-task"
    (testing "throws exception for invalid schema"
      (testing "missing required title field"
        ;; category and title are required by the tool's inputSchema
        ;; but if we bypass that and pass invalid data to add-task-impl,
        ;; the schema validation in tasks/add-task should catch it
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"Invalid task schema"
              (#'sut/add-task-impl
               (test-config)
               nil
               {:category "test"
                :title nil}))))
      (testing "invalid status value"
        ;; Status must be one of: :open, :closed, :in-progress, :blocked
        ;; The task map created in add-task-impl sets status to :open,
        ;; but we can test by creating an invalid task directly
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"Invalid task schema"
              (tasks/add-task
                {:title "Test task"
                 :description ""
                 :design ""
                 :category "test"
                 :status :invalid-status
                 :type :task
                 :meta {}
                 :relations []}))))
      (testing "invalid type value"
        ;; Type must be one of: :task, :bug, :feature, :story, :chore
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"Invalid task schema"
              (tasks/add-task
                {:title "Test task"
                 :description ""
                 :design ""
                 :category "test"
                 :status :open
                 :type :invalid-type
                 :meta {}
                 :relations []}))))
      (testing "missing required description field"
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"Invalid task schema"
              (tasks/add-task
                {:title "Test task"
                 :design ""
                 :category "test"
                 :status :open
                 :type :task
                 :meta {}
                 :relations []})))))))

(deftest ^:integration complete-workflow-add-next-complete
  ;; Integration test for complete workflow: add task → select task → complete task
  (testing "complete workflow with EDN storage"
    (testing "add → select → complete workflow"
      ;; Add first task
      (let [result (#'sut/add-task-impl
                    (test-config)
                    nil
                    {:category "test"
                     :title "First task"
                     :description "With description"})]
        (is (false? (:isError result))))

      ;; Add second task
      (let [result (#'sut/add-task-impl
                    (test-config)
                    nil
                    {:category "test"
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
                    {:category "test"
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
                    {:category "test"
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

(deftest select-tasks-status-filter
  ;; Test :status parameter filters tasks by status
  (testing "select-tasks :status filter"
    (testing "filters by status open (default behavior)"
      ;; Add tasks and complete one
      (#'sut/add-task-impl (test-config) nil {:category "test" :title "Open task 1"})
      (#'sut/add-task-impl (test-config) nil {:category "test" :title "Open task 2"})
      (#'sut/add-task-impl (test-config) nil {:category "test" :title "To be closed"})
      ;; Complete one task
      (#'sut/complete-task-impl (test-config) nil {:title "To be closed"})

      ;; Without status filter, should only return open tasks
      (let [result (#'sut/select-tasks-impl (test-config) nil {:category "test"})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
        (is (false? (:isError result)))
        (is (= 2 (count (:tasks response))))
        (is (= ["Open task 1" "Open task 2"]
               (map :title (:tasks response))))))))

(deftest select-tasks-status-open-explicit
  ;; Test explicitly filtering by status open
  (testing "select-tasks :status filter"
    (testing "explicitly filters by status open"
      (#'sut/add-task-impl (test-config) nil {:category "test" :title "Open task"})
      (#'sut/add-task-impl (test-config) nil {:category "test" :title "To close"})
      (#'sut/complete-task-impl (test-config) nil {:title "To close"})

      ;; Explicitly filter by open status
      (let [result (#'sut/select-tasks-impl (test-config) nil {:status "open"})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
        (is (false? (:isError result)))
        (is (= 1 (count (:tasks response))))
        (is (= "Open task" (get-in response [:tasks 0 :title])))
        (is (= "open" (name (get-in response [:tasks 0 :status]))))))))

(deftest select-tasks-status-in-progress
  ;; Test filtering by status in-progress
  (testing "select-tasks :status filter"
    (testing "filters by status in-progress"
      ;; Write tasks with different statuses directly to file
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 1 :parent-id nil :title "Open task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}
         {:id 2 :parent-id nil :title "In progress task 1" :description "" :design "" :category "test" :type :task :status :in-progress :meta {} :relations []}
         {:id 3 :parent-id nil :title "In progress task 2" :description "" :design "" :category "test" :type :task :status :in-progress :meta {} :relations []}])

      ;; Filter by in-progress status
      (let [result (#'sut/select-tasks-impl (test-config) nil {:status "in-progress"})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
        (is (false? (:isError result)))
        (is (= 2 (count (:tasks response))))
        (is (= #{"In progress task 1" "In progress task 2"}
               (set (map :title (:tasks response)))))
        (is (every? #(= "in-progress" (name (:status %))) (:tasks response)))))))

(deftest select-tasks-status-with-category
  ;; Test combining status filter with category filter
  (testing "select-tasks :status filter"
    (testing "combines status filter with category filter"
      (#'sut/add-task-impl (test-config) nil {:category "test" :title "Test open"})
      (#'sut/add-task-impl (test-config) nil {:category "other" :title "Other open"})
      (#'sut/add-task-impl (test-config) nil {:category "test" :title "Test to close"})
      (#'sut/complete-task-impl (test-config) nil {:title "Test to close"})

      ;; Filter by category test and status open
      (let [result (#'sut/select-tasks-impl (test-config) nil {:category "test" :status "open"})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
        (is (false? (:isError result)))
        (is (= 1 (count (:tasks response))))
        (is (= "Test open" (get-in response [:tasks 0 :title])))))))

(deftest select-tasks-task-id-filter
  ;; Test :task-id parameter filters tasks by ID
  (testing "select-tasks :task-id filter"
    ;; Add tasks
    (let [task1 (#'sut/add-task-impl (test-config) nil {:category "test" :title "Task One"})
          task1-data (json/read-str (get-in task1 [:content 1 :text]) :key-fn keyword)
          task1-id (get-in task1-data [:task :id])

          task2 (#'sut/add-task-impl (test-config) nil {:category "test" :title "Task Two"})
          task2-data (json/read-str (get-in task2 [:content 1 :text]) :key-fn keyword)
          task2-id (get-in task2-data [:task :id])

          task3 (#'sut/add-task-impl (test-config) nil {:category "other" :title "Task Three"})
          task3-data (json/read-str (get-in task3 [:content 1 :text]) :key-fn keyword)
          task3-id (get-in task3-data [:task :id])]

      (testing "filters by task-id to return single task"
        (let [result (#'sut/select-tasks-impl (test-config) nil {:task-id task1-id})
              response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
          (is (false? (:isError result)))
          (is (= 1 (count (:tasks response))))
          (is (= task1-id (get-in response [:tasks 0 :id])))
          (is (= "Task One" (get-in response [:tasks 0 :title])))))

      (testing "returns empty when task-id does not exist"
        (let [result (#'sut/select-tasks-impl (test-config) nil {:task-id 99999})
              response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
          (is (false? (:isError result)))
          (is (= 0 (count (:tasks response))))
          (is (= 0 (get-in response [:metadata :count])))
          (is (= 0 (get-in response [:metadata :total-matches])))))

      (testing "combines task-id filter with category filter"
        ;; Task 2 has category "test", task 3 has category "other"
        ;; Filtering by task3-id and category "test" should return empty
        (let [result (#'sut/select-tasks-impl (test-config) nil {:task-id task3-id :category "test"})
              response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
          (is (false? (:isError result)))
          (is (= 0 (count (:tasks response)))))

        ;; Filtering by task3-id and category "other" should return task 3
        (let [result (#'sut/select-tasks-impl (test-config) nil {:task-id task3-id :category "other"})
              response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
          (is (false? (:isError result)))
          (is (= 1 (count (:tasks response))))
          (is (= task3-id (get-in response [:tasks 0 :id])))
          (is (= "Task Three" (get-in response [:tasks 0 :title])))))

      (testing "task-id filter works with unique constraint"
        (let [result (#'sut/select-tasks-impl (test-config) nil {:task-id task2-id :unique true})
              response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
          (is (false? (:isError result)))
          (is (= 1 (count (:tasks response))))
          (is (= task2-id (get-in response [:tasks 0 :id])))
          (is (= "Task Two" (get-in response [:tasks 0 :title]))))))))

;; Git Integration Tests

(defn- git-test-config
  "Config with git enabled for testing."
  []
  {:base-dir *test-dir* :use-git? true})

(defn- init-git-repo
  "Initialize a git repository in the test .mcp-tasks directory."
  [test-dir]
  (let [git-dir (str test-dir "/.mcp-tasks")]
    (sh/sh "git" "init" :dir git-dir)
    (sh/sh "git" "config" "user.email" "test@test.com" :dir git-dir)
    (sh/sh "git" "config" "user.name" "Test User" :dir git-dir)))

(defn- git-log-last-commit
  "Get the last commit message from the git repo."
  [test-dir]
  (let [git-dir (str test-dir "/.mcp-tasks")
        result (sh/sh "git" "log" "-1" "--pretty=%B" :dir git-dir)]
    (str/trim (:out result))))

(defn- git-commit-exists?
  "Check if there are any commits in the git repo."
  [test-dir]
  (let [git-dir (str test-dir "/.mcp-tasks")
        result (sh/sh "git" "rev-parse" "HEAD" :dir git-dir)]
    (zero? (:exit result))))

(deftest add-task-returns-two-items-without-git
  ;; Tests that add-task returns 2 content items when git is disabled
  (testing "add-task with git disabled"
    (testing "returns two content items"
      (let [result (#'sut/add-task-impl
                    (test-config)
                    nil
                    {:category "test"
                     :title "test task"
                     :description "description"})]
        (is (false? (:isError result)))
        (is (= 2 (count (:content result))))

        ;; First content item: text message
        (let [text-content (first (:content result))]
          (is (= "text" (:type text-content)))
          (is (str/includes? (:text text-content) "Task added to")))

        ;; Second content item: task data
        (let [data-content (second (:content result))
              data (json/read-str (:text data-content) :key-fn keyword)]
          (is (= "text" (:type data-content)))
          (is (contains? data :task))
          (is (contains? data :metadata)))))))

(deftest add-task-returns-three-items-with-git
  ;; Tests that add-task returns 3 content items when git is enabled
  (testing "add-task with git enabled"
    (testing "returns three content items"
      (init-git-repo *test-dir*)
      (let [result (#'sut/add-task-impl
                    (git-test-config)
                    nil
                    {:category "test"
                     :title "test task"
                     :description "description"})]
        (is (false? (:isError result)))
        (is (= 3 (count (:content result))))

        ;; First content item: text message
        (let [text-content (first (:content result))]
          (is (= "text" (:type text-content)))
          (is (str/includes? (:text text-content) "Task added to")))

        ;; Second content item: task data
        (let [data-content (second (:content result))
              data (json/read-str (:text data-content) :key-fn keyword)]
          (is (= "text" (:type data-content)))
          (is (contains? data :task))
          (is (contains? data :metadata)))

        ;; Third content item: git status
        (let [git-content (nth (:content result) 2)
              git-data (json/read-str (:text git-content) :key-fn keyword)]
          (is (= "text" (:type git-content)))
          (is (contains? git-data :git-status))
          (is (contains? git-data :git-commit)))))))

(deftest ^:integration add-task-creates-git-commit
  ;; Integration test verifying git commit is actually created with correct message
  (testing "add-task with git enabled"
    (testing "creates git commit with correct message format"
      (init-git-repo *test-dir*)
      (let [result (#'sut/add-task-impl
                    (git-test-config)
                    nil
                    {:category "test"
                     :title "implement feature Y"
                     :description "Feature description"})]
        (is (false? (:isError result)))

        ;; Extract task ID from response
        (let [data-content (second (:content result))
              data (json/read-str (:text data-content) :key-fn keyword)
              task-id (get-in data [:task :id])]

          ;; Verify git commit was created
          (is (git-commit-exists? *test-dir*))

          ;; Verify commit message format: "add task #<id>: <title>"
          (let [commit-msg (git-log-last-commit *test-dir*)]
            (is (= (str "Add task #" task-id ": implement feature Y") commit-msg)))

          ;; Verify git status in response
          (let [git-content (nth (:content result) 2)
                git-data (json/read-str (:text git-content) :key-fn keyword)]
            (is (= "success" (:git-status git-data)))
            (is (string? (:git-commit git-data)))
            (is (= 40 (count (:git-commit git-data)))) ; SHA is 40 chars
            (is (nil? (:git-error git-data)))))))))

(deftest ^:integration add-task-truncates-long-titles
  ;; Tests that long titles are truncated in commit messages
  (testing "add-task with git enabled"
    (testing "truncates titles longer than 50 chars in commit message"
      (init-git-repo *test-dir*)
      (let [long-title "This is a very long title that exceeds fifty characters in length"
            result (#'sut/add-task-impl
                    (git-test-config)
                    nil
                    {:category "test"
                     :title long-title
                     :description "Description"})]
        (is (false? (:isError result)))

        ;; Extract task ID from response
        (let [data-content (second (:content result))
              data (json/read-str (:text data-content) :key-fn keyword)
              task-id (get-in data [:task :id])]

          ;; Verify commit message has truncated title
          (let [commit-msg (git-log-last-commit *test-dir*)]
            (is (str/starts-with? commit-msg (str "Add task #" task-id ": ")))
            ;; Truncated title should be 50 chars (47 + "...")
            (is (str/includes? commit-msg "...")))

          ;; But task data should have full title
          (let [tasks (read-ednl-test-file "tasks.ednl")]
            (is (= long-title (:title (first tasks))))))))))

(deftest ^:integration add-task-succeeds-despite-git-failure
  ;; Tests that task addition succeeds even when git operations fail
  (testing "add-task with git enabled but no git repo"
    (testing "task added successfully despite git error"
      ;; Do not initialize git repo - this will cause git operations to fail
      (let [result (#'sut/add-task-impl
                    (git-test-config)
                    nil
                    {:category "test"
                     :title "test task"
                     :description "description"})]
        ;; Task addition should succeed
        (is (false? (:isError result)))
        (is (= 3 (count (:content result))))

        ;; Verify task was actually added
        (let [tasks (read-ednl-test-file "tasks.ednl")]
          (is (= 1 (count tasks)))
          (is (= "test task" (:title (first tasks)))))

        ;; Verify git error is reported in response
        (let [git-content (nth (:content result) 2)
              git-data (json/read-str (:text git-content) :key-fn keyword)]
          (is (= "error" (:git-status git-data)))
          (is (nil? (:git-commit git-data)))
          (is (string? (:git-error git-data)))
          (is (not (str/blank? (:git-error git-data)))))))))

(deftest complete-task-returns-three-content-items-with-git
  ;; Tests that complete-task returns 3 content items when git is enabled
  (testing "complete-task with git enabled"
    (testing "returns three content items"
      (init-git-repo *test-dir*)
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "test task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/complete-task-impl
                    (git-test-config)
                    nil
                    {:task-id 1})]
        (is (false? (:isError result)))
        (is (= 3 (count (:content result))))

        ;; First content item: completion message
        (let [text-content (first (:content result))]
          (is (= "text" (:type text-content)))
          (is (str/includes? (:text text-content) "Task 1 completed")))

        ;; Second content item: modified files
        (let [files-content (second (:content result))
              files-data (json/read-str (:text files-content) :key-fn keyword)]
          (is (= "text" (:type files-content)))
          (is (contains? files-data :modified-files))
          (is (= 2 (count (:modified-files files-data)))))

        ;; Third content item: git status
        (let [git-content (nth (:content result) 2)
              git-data (json/read-str (:text git-content) :key-fn keyword)]
          (is (= "text" (:type git-content)))
          (is (contains? git-data :git-status))
          (is (contains? git-data :git-commit)))))))

(deftest complete-task-returns-one-content-item-without-git
  ;; Tests that complete-task returns 1 content item when git is disabled
  (testing "complete-task with git disabled"
    (testing "returns one content item"
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "test task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/complete-task-impl
                    (test-config)
                    nil
                    {:task-id 1})]
        (is (false? (:isError result)))
        (is (= 1 (count (:content result))))

        ;; Only content item: completion message
        (let [text-content (first (:content result))]
          (is (= "text" (:type text-content)))
          (is (str/includes? (:text text-content) "Task 1 completed")))))))

(deftest ^:integration complete-task-creates-git-commit
  ;; Integration test verifying git commit is actually created
  (testing "complete-task with git enabled"
    (testing "creates git commit with correct message"
      (init-git-repo *test-dir*)
      (write-ednl-test-file "tasks.ednl"
                            [{:id 42 :parent-id nil :title "implement feature X" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])

      ;; Complete the task
      (let [result (#'sut/complete-task-impl
                    (git-test-config)
                    nil
                    {:task-id 42})]
        (is (false? (:isError result)))

        ;; Verify git commit was created
        (is (git-commit-exists? *test-dir*))

        ;; Verify commit message format
        (let [commit-msg (git-log-last-commit *test-dir*)]
          (is (= "Complete task #42: implement feature X" commit-msg)))

        ;; Verify git status in response
        (let [git-content (nth (:content result) 2)
              git-data (json/read-str (:text git-content) :key-fn keyword)]
          (is (= "success" (:git-status git-data)))
          (is (string? (:git-commit git-data)))
          (is (= 40 (count (:git-commit git-data)))) ; SHA is 40 chars
          (is (nil? (:git-error git-data))))))))

(deftest ^:integration complete-task-succeeds-despite-git-failure
  ;; Tests that task completion succeeds even when git operations fail
  (testing "complete-task with git enabled but no git repo"
    (testing "task completes successfully despite git error"
      ;; Do not initialize git repo - this will cause git operations to fail
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "test task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])

      (let [result (#'sut/complete-task-impl
                    (git-test-config)
                    nil
                    {:task-id 1})]
        ;; Task completion should succeed
        (is (false? (:isError result)))

        ;; Verify task was actually completed
        (let [complete-tasks (read-ednl-test-file "complete.ednl")]
          (is (= 1 (count complete-tasks)))
          (is (= "test task" (:title (first complete-tasks)))))

        ;; Verify git error is reported in response
        (let [git-content (nth (:content result) 2)
              git-data (json/read-str (:text git-content) :key-fn keyword)]
          (is (= "error" (:git-status git-data)))
          (is (nil? (:git-commit git-data)))
          (is (string? (:git-error git-data)))
          (is (not (str/blank? (:git-error git-data)))))))))

(deftest ^:integration complete-task-git-commit-sha-format
  ;; Tests that git commit SHA is returned in correct format
  (testing "complete-task with git enabled"
    (testing "returns valid git commit SHA"
      (init-git-repo *test-dir*)
      (write-ednl-test-file "tasks.ednl"
                            [{:id 99 :parent-id nil :title "task title" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])

      (let [result (#'sut/complete-task-impl
                    (git-test-config)
                    nil
                    {:task-id 99})
            git-content (nth (:content result) 2)
            git-data (json/read-str (:text git-content) :key-fn keyword)
            sha (:git-commit git-data)]

        ;; Verify SHA format
        (is (string? sha))
        (is (= 40 (count sha)))
        (is (re-matches #"[0-9a-f]{40}" sha))))))

(deftest ^:integration completes-story-child-with-git
  ;; Tests that completing a story child with git only modifies tasks.ednl
  (testing "complete-task with git"
    (testing "only tasks.ednl is modified for story child"
      (init-git-repo *test-dir*)
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 20 :parent-id nil :title "Story" :description "" :design "" :category "story" :type :story :status :open :meta {} :relations []}
         {:id 21 :parent-id 20 :title "Child" :description "" :design "" :category "simple" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/complete-task-impl
                    (git-test-config)
                    nil
                    {:task-id 21})]
        (is (false? (:isError result)))
        ;; Expect 3 content items
        (is (= 3 (count (:content result))))

        ;; First item: message doesn't say "moved to"
        (is (str/includes? (get-in result [:content 0 :text]) "Task 21 completed"))
        (is (not (str/includes? (get-in result [:content 0 :text]) "moved to")))

        ;; Second item: modified-files contains only tasks.ednl
        (let [files-data (json/read-str (get-in result [:content 1 :text]) :key-fn keyword)]
          (is (= ["tasks.ednl"] (:modified-files files-data))))

        ;; Third item: has git-status and commit-sha
        (let [git-data (json/read-str (get-in result [:content 2 :text]) :key-fn keyword)]
          (is (= "success" (:git-status git-data)))
          (is (string? (:git-commit git-data))))

        ;; Verify git commit was created
        (is (git-commit-exists? *test-dir*))

        ;; Verify task stayed in tasks.ednl with :status :closed
        (let [tasks (read-ednl-test-file "tasks.ednl")]
          (is (= 2 (count tasks)))
          (is (= :closed (:status (second tasks)))))

        ;; Verify complete.ednl is still empty
        (is (empty? (read-ednl-test-file "complete.ednl")))))))

(deftest completes-story-with-unclosed-children-returns-error
  ;; Tests that attempting to complete a story with unclosed children returns an error
  (testing "complete-task"
    (testing "returns error when story has unclosed children"
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 30 :parent-id nil :title "My Story" :description "" :design "" :category "story" :type :story :status :open :meta {} :relations []}
         {:id 31 :parent-id 30 :title "Child 1" :description "" :design "" :category "simple" :type :task :status :open :meta {} :relations []}
         {:id 32 :parent-id 30 :title "Child 2" :description "" :design "" :category "simple" :type :task :status :closed :meta {} :relations []}
         {:id 33 :parent-id 30 :title "Child 3" :description "" :design "" :category "simple" :type :task :status :in-progress :meta {} :relations []}])

      (let [result (#'sut/complete-task-impl
                    (test-config)
                    nil
                    {:task-id 30})]
        ;; Verify error response
        (is (true? (:isError result)))
        (is (= 2 (count (:content result))))

        ;; First content: error message
        (let [msg (get-in result [:content 0 :text])]
          (is (str/includes? msg "Cannot complete story"))
          (is (str/includes? msg "2 child tasks"))
          (is (str/includes? msg "not closed")))

        ;; Second content: error metadata with unclosed children
        (let [error-data (json/read-str (get-in result [:content 1 :text]) :key-fn keyword)]
          (is (= "Cannot complete story: 2 child tasks still are not closed" (:error error-data)))
          (is (= 30 (get-in error-data [:metadata :task-id])))
          (is (= 2 (count (get-in error-data [:metadata :unclosed-children]))))
          ;; Verify unclosed children details
          (let [unclosed (get-in error-data [:metadata :unclosed-children])]
            (is (some #(= 31 (:id %)) unclosed))
            (is (some #(= 33 (:id %)) unclosed))
            (is (every? #(not= :closed (:status %)) unclosed))))

        ;; Verify nothing was moved to complete.ednl
        (is (empty? (read-ednl-test-file "complete.ednl")))

        ;; Verify all tasks remain in tasks.ednl unchanged
        (let [tasks (read-ednl-test-file "tasks.ednl")]
          (is (= 4 (count tasks)))
          (is (= :open (:status (first tasks)))))))))

(deftest completes-story-with-all-children-closed
  ;; Tests that completing a story with all children closed archives everything atomically
  (testing "complete-task"
    (testing "archives story and all children atomically when all closed"
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 40 :parent-id nil :title "Complete Story" :description "Story desc" :design "" :category "story" :type :story :status :open :meta {} :relations []}
         {:id 41 :parent-id 40 :title "Child 1" :description "" :design "" :category "simple" :type :task :status :closed :meta {} :relations []}
         {:id 42 :parent-id 40 :title "Child 2" :description "" :design "" :category "simple" :type :task :status :closed :meta {} :relations []}])

      (let [result (#'sut/complete-task-impl
                    (test-config)
                    nil
                    {:task-id 40})]
        ;; Verify success response
        (is (false? (:isError result)))
        (is (= 1 (count (:content result))))

        ;; Verify completion message
        (let [msg (get-in result [:content 0 :text])]
          (is (str/includes? msg "Story 40 completed and archived"))
          (is (str/includes? msg "with 2 child tasks")))

        ;; Verify all tasks moved to complete.ednl
        (let [completed-tasks (read-ednl-test-file "complete.ednl")]
          (is (= 3 (count completed-tasks)))
          ;; Verify story is first
          (is (= 40 (:id (first completed-tasks))))
          (is (= :story (:type (first completed-tasks))))
          (is (= :closed (:status (first completed-tasks))))
          ;; Verify children follow
          (is (= 41 (:id (second completed-tasks))))
          (is (= 42 (:id (nth completed-tasks 2)))))

        ;; Verify tasks.ednl is now empty
        (is (empty? (read-ednl-test-file "tasks.ednl")))))))

(deftest completes-story-with-no-children
  ;; Tests that completing a story with no children archives it immediately
  (testing "complete-task"
    (testing "archives story immediately when it has no children"
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 50 :parent-id nil :title "Empty Story" :description "" :design "" :category "story" :type :story :status :open :meta {} :relations []}])

      (let [result (#'sut/complete-task-impl
                    (test-config)
                    nil
                    {:task-id 50})]
        ;; Verify success response
        (is (false? (:isError result)))

        ;; Verify completion message doesn't mention children
        (let [msg (get-in result [:content 0 :text])]
          (is (str/includes? msg "Story 50 completed and archived"))
          (is (not (str/includes? msg "child"))))

        ;; Verify story moved to complete.ednl
        (let [completed-tasks (read-ednl-test-file "complete.ednl")]
          (is (= 1 (count completed-tasks)))
          (is (= 50 (:id (first completed-tasks))))
          (is (= :closed (:status (first completed-tasks)))))

        ;; Verify tasks.ednl is now empty
        (is (empty? (read-ednl-test-file "tasks.ednl")))))))

(deftest ^:integration completes-story-with-git-creates-commit
  ;; Tests that completing a story with git creates a commit with custom message
  (testing "complete-task with git"
    (testing "creates commit with story-specific message and child count"
      (init-git-repo *test-dir*)
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 60 :parent-id nil :title "Git Story" :description "" :design "" :category "story" :type :story :status :open :meta {} :relations []}
         {:id 61 :parent-id 60 :title "Child A" :description "" :design "" :category "simple" :type :task :status :closed :meta {} :relations []}
         {:id 62 :parent-id 60 :title "Child B" :description "" :design "" :category "simple" :type :task :status :closed :meta {} :relations []}
         {:id 63 :parent-id 60 :title "Child C" :description "" :design "" :category "simple" :type :task :status :closed :meta {} :relations []}])

      (let [result (#'sut/complete-task-impl
                    (git-test-config)
                    nil
                    {:task-id 60})]
        (is (false? (:isError result)))
        (is (= 3 (count (:content result))))

        ;; Verify completion message
        (let [msg (get-in result [:content 0 :text])]
          (is (str/includes? msg "Story 60 completed and archived"))
          (is (str/includes? msg "with 3 child tasks")))

        ;; Verify modified files includes both tasks.ednl and complete.ednl
        (let [files-data (json/read-str (get-in result [:content 1 :text]) :key-fn keyword)]
          (is (= ["tasks.ednl" "complete.ednl"] (:modified-files files-data))))

        ;; Verify git status is success
        (let [git-data (json/read-str (get-in result [:content 2 :text]) :key-fn keyword)]
          (is (= "success" (:git-status git-data)))
          (is (string? (:git-commit git-data)))
          (is (= 40 (count (:git-commit git-data)))))

        ;; Verify git commit was created with correct message
        (is (git-commit-exists? *test-dir*))
        (let [commit-msg (git-log-last-commit *test-dir*)]
          (is (= "Complete story #60: Git Story (with 3 tasks)" commit-msg)))

        ;; Verify all tasks archived
        (let [completed-tasks (read-ednl-test-file "complete.ednl")]
          (is (= 4 (count completed-tasks))))
        (is (empty? (read-ednl-test-file "tasks.ednl")))))))

;; update-task-impl tests

(deftest update-task-updates-title-field
  ;; Tests updating the title field of an existing task
  (testing "update-task"
    (testing "updates title field"
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "orig title" :description "desc" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (test-config)
                    nil
                    {:task-id 1 :title "new title"})]
        (is (false? (:isError result)))
        (is (str/includes? (get-in result [:content 0 :text]) "Task 1 updated"))
        ;; Verify task file has updated title
        (let [tasks (read-ednl-test-file "tasks.ednl")
              task (first tasks)]
          (is (= "new title" (:title task)))
          (is (= "desc" (:description task))))))))

(deftest update-task-updates-description-field
  ;; Tests updating the description field of an existing task
  (testing "update-task"
    (testing "updates description field"
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "task" :description "old desc" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (test-config)
                    nil
                    {:task-id 1 :description "new desc"})]
        (is (false? (:isError result)))
        (let [tasks (read-ednl-test-file "tasks.ednl")
              task (first tasks)]
          (is (= "new desc" (:description task)))
          (is (= "task" (:title task))))))))

(deftest update-task-updates-design-field
  ;; Tests updating the design field of an existing task
  (testing "update-task"
    (testing "updates design field"
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "task" :description "" :design "old design" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (test-config)
                    nil
                    {:task-id 1 :design "new design"})]
        (is (false? (:isError result)))
        (let [tasks (read-ednl-test-file "tasks.ednl")
              task (first tasks)]
          (is (= "new design" (:design task))))))))

(deftest update-task-updates-status-field
  ;; Tests updating the status field of an existing task
  (testing "update-task"
    (testing "updates status field"
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (test-config)
                    nil
                    {:task-id 1 :status "in-progress"})]
        (is (false? (:isError result)))
        (let [tasks (read-ednl-test-file "tasks.ednl")
              task (first tasks)]
          (is (= :in-progress (:status task))))))))

(deftest update-task-updates-category-field
  ;; Tests updating the category field of an existing task
  (testing "update-task"
    (testing "updates category field"
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "old-cat" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (test-config)
                    nil
                    {:task-id 1 :category "new-cat"})]
        (is (false? (:isError result)))
        (let [tasks (read-ednl-test-file "tasks.ednl")
              task (first tasks)]
          (is (= "new-cat" (:category task))))))))

(deftest update-task-updates-type-field
  ;; Tests updating the type field of an existing task
  (testing "update-task"
    (testing "updates type field"
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (test-config)
                    nil
                    {:task-id 1 :type "bug"})]
        (is (false? (:isError result)))
        (let [tasks (read-ednl-test-file "tasks.ednl")
              task (first tasks)]
          (is (= :bug (:type task))))))))

(deftest update-task-updates-parent-id-field
  ;; Tests updating the parent-id field to link a task to a parent
  (testing "update-task"
    (testing "updates parent-id field"
      ;; Create parent and child task
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "parent" :description "" :design "" :category "test" :type :story :status :open :meta {} :relations []}
                             {:id 2 :parent-id nil :title "child" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (test-config)
                    nil
                    {:task-id 2 :parent-id 1})]
        (is (false? (:isError result)))
        (let [tasks (read-ednl-test-file "tasks.ednl")
              child-task (second tasks)]
          (is (= 1 (:parent-id child-task))))))))

(deftest update-task-updates-meta-field
  ;; Tests updating the meta field with a new map
  (testing "update-task"
    (testing "updates meta field"
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {"old-key" "old-val"} :relations []}])
      (let [result (#'sut/update-task-impl
                    (test-config)
                    nil
                    {:task-id 1 :meta {"new-key" "new-val"}})]
        (is (false? (:isError result)))
        (let [tasks (read-ednl-test-file "tasks.ednl")
              task (first tasks)]
          (is (= {"new-key" "new-val"} (:meta task))))))))

(deftest update-task-updates-relations-field
  ;; Tests updating the relations field with a new vector
  (testing "update-task"
    (testing "updates relations field"
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "task1" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}
                             {:id 2 :parent-id nil :title "task2" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (test-config)
                    nil
                    {:task-id 1 :relations [{"id" 1 "relates-to" 2 "as-type" "blocked-by"}]})]
        (is (false? (:isError result)))
        (let [tasks (read-ednl-test-file "tasks.ednl")
              task (first tasks)]
          (is (= [{:id 1 :relates-to 2 :as-type :blocked-by}] (:relations task))))))))

(deftest update-task-updates-multiple-fields
  ;; Tests updating multiple fields in a single call
  (testing "update-task"
    (testing "updates multiple fields in single call"
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "old" :description "old desc" :design "" :category "old-cat" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (test-config)
                    nil
                    {:task-id 1
                     :title "new"
                     :description "new desc"
                     :status "in-progress"})]
        (is (false? (:isError result)))
        (let [tasks (read-ednl-test-file "tasks.ednl")
              task (first tasks)]
          (is (= "new" (:title task)))
          (is (= "new desc" (:description task)))
          (is (= :in-progress (:status task)))
          (is (= "old-cat" (:category task))))))))

(deftest update-task-clears-parent-id-with-nil
  ;; Tests clearing parent-id by passing nil
  (testing "update-task"
    (testing "clears parent-id when nil is provided"
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "parent" :description "" :design "" :category "test" :type :story :status :open :meta {} :relations []}
                             {:id 2 :parent-id 1 :title "child" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (test-config)
                    nil
                    {:task-id 2 :parent-id nil})]
        (is (false? (:isError result)))
        (let [tasks (read-ednl-test-file "tasks.ednl")
              child-task (second tasks)]
          (is (nil? (:parent-id child-task))))))))

(deftest update-task-clears-meta-with-nil
  ;; Tests clearing meta map by passing nil
  (testing "update-task"
    (testing "clears meta map when nil is provided"
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {"key" "val"} :relations []}])
      (let [result (#'sut/update-task-impl
                    (test-config)
                    nil
                    {:task-id 1 :meta nil})]
        (is (false? (:isError result)))
        (let [tasks (read-ednl-test-file "tasks.ednl")
              task (first tasks)]
          (is (= {} (:meta task))))))))

(deftest update-task-clears-relations-with-nil
  ;; Tests clearing relations vector by passing nil
  (testing "update-task"
    (testing "clears relations vector when nil is provided"
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "task1" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations [{:id 1 :relates-to 2 :as-type :related}]}
                             {:id 2 :parent-id nil :title "task2" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (test-config)
                    nil
                    {:task-id 1 :relations nil})]
        (is (false? (:isError result)))
        (let [tasks (read-ednl-test-file "tasks.ednl")
              task (first tasks)]
          (is (= [] (:relations task))))))))

(deftest update-task-validates-invalid-status
  ;; Tests that invalid status values return a validation error
  (testing "update-task"
    (testing "returns error for invalid status value"
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (test-config)
                    nil
                    {:task-id 1 :status "invalid-status"})]
        (is (true? (:isError result)))
        (is (str/includes? (get-in result [:content 0 :text]) "Invalid task field values"))))))

(deftest update-task-validates-invalid-type
  ;; Tests that invalid type values return a validation error
  (testing "update-task"
    (testing "returns error for invalid type value"
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (test-config)
                    nil
                    {:task-id 1 :type "invalid-type"})]
        (is (true? (:isError result)))
        (is (str/includes? (get-in result [:content 0 :text]) "Invalid task field values"))))))

(deftest update-task-validates-parent-id-exists
  ;; Tests that referencing a non-existent parent-id returns an error
  (testing "update-task"
    (testing "returns error when parent-id does not exist"
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (test-config)
                    nil
                    {:task-id 1 :parent-id 999})]
        (is (true? (:isError result)))
        (is (= "Parent task not found" (get-in result [:content 0 :text])))
        ;; Verify structured error data
        (let [data-content (second (:content result))
              data (json/read-str (:text data-content) :key-fn keyword)]
          (is (= "Parent task not found" (:error data)))
          (is (= 999 (get-in data [:metadata :parent-id]))))))))

(deftest update-task-validates-task-exists
  ;; Tests that updating a non-existent task returns an error
  (testing "update-task"
    (testing "returns error when task does not exist"
      (write-ednl-test-file "tasks.ednl" [])
      (let [result (#'sut/update-task-impl
                    (test-config)
                    nil
                    {:task-id 999 :title "new title"})]
        (is (true? (:isError result)))
        (is (str/includes? (get-in result [:content 0 :text]) "Task not found"))))))

(deftest update-task-meta-replaces-not-merges
  ;; Tests that meta field is replaced entirely, not merged
  (testing "update-task"
    (testing "meta field replacement behavior"
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {"old-key" "old-val" "keep-key" "keep-val"} :relations []}])
      (let [result (#'sut/update-task-impl
                    (test-config)
                    nil
                    {:task-id 1 :meta {"new-key" "new-val"}})]
        (is (false? (:isError result)))
        (let [tasks (read-ednl-test-file "tasks.ednl")
              task (first tasks)]
          ;; Meta should be completely replaced, not merged
          (is (= {"new-key" "new-val"} (:meta task)))
          (is (not (contains? (:meta task) "old-key")))
          (is (not (contains? (:meta task) "keep-key"))))))))

(deftest update-task-relations-replaces-not-appends
  ;; Tests that relations field is replaced entirely, not appended
  (testing "update-task"
    (testing "relations field replacement behavior"
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "task1" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations [{:id 1 :relates-to 2 :as-type :related}]}
                             {:id 2 :parent-id nil :title "task2" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}
                             {:id 3 :parent-id nil :title "task3" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/update-task-impl
                    (test-config)
                    nil
                    {:task-id 1 :relations [{"id" 2 "relates-to" 3 "as-type" "blocked-by"}]})]
        (is (false? (:isError result)))
        (let [tasks (read-ednl-test-file "tasks.ednl")
              task (first tasks)]
          ;; Relations should be completely replaced, not appended
          (is (= 1 (count (:relations task))))
          (is (= [{:id 2 :relates-to 3 :as-type :blocked-by}] (:relations task))))))))

(deftest ^:integration end-to-end-story-workflow-with-git
  ;; Tests complete story workflow: add story, add children, complete children, complete story
  ;; Verifies file states and git commits at each step
  (testing "end-to-end story workflow"
    (testing "complete workflow with git integration"
      (init-git-repo *test-dir*)

      ;; Step 1: Create a story
      (let [result (#'sut/add-task-impl
                    (git-test-config)
                    nil
                    {:category "story"
                     :title "E2E Story"
                     :description "End-to-end test story"
                     :type "story"})]
        (is (false? (:isError result)))
        (let [tasks (read-ednl-test-file "tasks.ednl")]
          (is (= 1 (count tasks)))
          (is (= :story (:type (first tasks))))
          (is (= "E2E Story" (:title (first tasks))))))

      ;; Step 2: Add three child tasks
      (doseq [title ["Task A" "Task B" "Task C"]]
        (let [result (#'sut/add-task-impl
                      (git-test-config)
                      nil
                      {:category "simple"
                       :title title
                       :parent-id 1})]
          (is (false? (:isError result)))))

      (let [tasks (read-ednl-test-file "tasks.ednl")]
        (is (= 4 (count tasks)))
        (is (every? #(= 1 (:parent-id %)) (rest tasks))))

      ;; Step 3: Complete each child task and track commit SHAs
      (let [child-commits (atom [])]
        (doseq [child-id [2 3 4]]
          (let [result (#'sut/complete-task-impl
                        (git-test-config)
                        nil
                        {:task-id child-id})]
            (is (false? (:isError result)))
            (is (str/includes? (get-in result [:content 0 :text])
                               (str "Task " child-id " completed")))
            (is (not (str/includes? (get-in result [:content 0 :text])
                                    "moved to")))
            ;; Verify git commit was created for child completion
            (let [git-data (json/read-str (get-in result [:content 2 :text])
                                          :key-fn keyword)]
              (is (= "success" (:git-status git-data)))
              (is (string? (:git-commit git-data)))
              (swap! child-commits conj (:git-commit git-data)))))

        ;; Verify all children are closed but still in tasks.ednl
        (let [tasks (read-ednl-test-file "tasks.ednl")]
          (is (= 4 (count tasks)))
          (is (= :open (:status (first tasks))))
          (is (every? #(= :closed (:status %)) (rest tasks))))

        ;; Verify complete.ednl is still empty
        (is (empty? (read-ednl-test-file "complete.ednl")))

        ;; Step 4: Complete the story
        (let [result (#'sut/complete-task-impl
                      (git-test-config)
                      nil
                      {:task-id 1})]
          (is (false? (:isError result)))
          (is (= 3 (count (:content result))))

          ;; Verify completion message
          (let [msg (get-in result [:content 0 :text])]
            (is (str/includes? msg "Story 1 completed and archived"))
            (is (str/includes? msg "with 3 child tasks")))

          ;; Verify both files modified
          (let [files-data (json/read-str (get-in result [:content 1 :text])
                                          :key-fn keyword)]
            (is (= ["tasks.ednl" "complete.ednl"] (:modified-files files-data))))

          ;; Verify git commit for story completion
          (let [git-data (json/read-str (get-in result [:content 2 :text])
                                        :key-fn keyword)]
            (is (= "success" (:git-status git-data)))
            (is (string? (:git-commit git-data)))
            (is (= "Complete story #1: E2E Story (with 3 tasks)"
                   (git-log-last-commit *test-dir*)))))

        ;; Step 5: Verify final state
        (let [completed (read-ednl-test-file "complete.ednl")]
          (is (= 4 (count completed)))
          (is (= 1 (:id (first completed))))
          (is (= :story (:type (first completed))))
          (is (every? #(= :closed (:status %)) completed))
          (is (every? #(= 1 (:parent-id %)) (rest completed))))

        (is (empty? (read-ednl-test-file "tasks.ednl")))

        ;; Step 6: Verify git commits were created
        ;; We have 3 child completion commits + 1 story completion commit + 1 initial commit
        (is (= 3 (count @child-commits)))
        (is (every? string? @child-commits))
        (is (git-commit-exists? *test-dir*))))))

;; Delete task tests

(deftest delete-task-by-id
  ;; Tests that delete-task-impl can find and delete a task by exact ID
  (testing "delete-task"
    (testing "deletes task by exact task-id"
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "first task" :description "detail" :design "" :category "test" :type :task :status :open :meta {} :relations []}
                             {:id 2 :parent-id nil :title "second task" :description "" :design "" :category "other" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/delete-task-impl
                    (test-config)
                    nil
                    {:task-id 2})]
        (is (false? (:isError result)))
        ;; Verify response structure (2 items without git)
        (is (= 2 (count (:content result))))
        (is (= "Task 2 deleted successfully" (get-in result [:content 0 :text])))
        ;; Second content item: deleted task data
        (let [deleted-data (json/read-str (get-in result [:content 1 :text]) :key-fn keyword)]
          (is (contains? deleted-data :deleted))
          (is (= 2 (:id (:deleted deleted-data))))
          (is (= "deleted" (:status (:deleted deleted-data))))
          (is (contains? deleted-data :metadata))
          (is (= 1 (get-in deleted-data [:metadata :count])))
          (is (= "deleted" (get-in deleted-data [:metadata :status]))))
        ;; Verify task 2 is in complete.ednl with :status :deleted
        (let [complete-tasks (read-ednl-test-file "complete.ednl")]
          (is (= 1 (count complete-tasks)))
          (is (= "second task" (:title (first complete-tasks))))
          (is (= :deleted (:status (first complete-tasks)))))
        ;; Verify task 1 remains in tasks.ednl
        (let [tasks (read-ednl-test-file "tasks.ednl")]
          (is (= 1 (count tasks)))
          (is (= "first task" (:title (first tasks)))))))))

(deftest delete-task-by-title-pattern
  ;; Tests that delete-task-impl can find and delete a task by exact title match
  (testing "delete-task"
    (testing "deletes task by exact title-pattern match"
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "unique task" :description "detail" :design "" :category "test" :type :task :status :open :meta {} :relations []}
                             {:id 2 :parent-id nil :title "other task" :description "" :design "" :category "other" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/delete-task-impl
                    (test-config)
                    nil
                    {:title-pattern "unique task"})]
        (is (false? (:isError result)))
        ;; Verify task with title "unique task" is deleted
        (let [complete-tasks (read-ednl-test-file "complete.ednl")]
          (is (= 1 (count complete-tasks)))
          (is (= "unique task" (:title (first complete-tasks))))
          (is (= :deleted (:status (first complete-tasks)))))
        ;; Verify other task remains
        (let [tasks (read-ednl-test-file "tasks.ednl")]
          (is (= 1 (count tasks)))
          (is (= "other task" (:title (first tasks)))))))))

(deftest delete-task-rejects-ambiguous-title
  ;; Tests that delete-task-impl rejects when multiple tasks have the same title
  (testing "delete-task"
    (testing "rejects multiple tasks with same title"
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 1 :parent-id nil :title "duplicate" :description "first" :design "" :category "test" :type :task :status :open :meta {} :relations []}
         {:id 2 :parent-id nil :title "duplicate" :description "second" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/delete-task-impl
                    (test-config)
                    nil
                    {:title-pattern "duplicate"})]
        (is (true? (:isError result)))
        (is (str/includes?
              (get-in result [:content 0 :text])
              "Multiple tasks found"))
        ;; Verify no tasks were deleted
        (is (empty? (read-ednl-test-file "complete.ednl")))
        (is (= 2 (count (read-ednl-test-file "tasks.ednl"))))))))

(deftest delete-task-requires-identifier
  ;; Tests that delete-task-impl requires at least one identifier
  (testing "delete-task"
    (testing "requires at least one of task-id or title-pattern"
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/delete-task-impl
                    (test-config)
                    nil
                    {})]
        (is (true? (:isError result)))
        (is (str/includes?
              (get-in result [:content 0 :text])
              "Must provide either task-id or title"))))))

(deftest delete-task-verifies-id-and-title-match
  ;; Tests that delete-task-impl verifies both identifiers refer to the same task
  (testing "delete-task"
    (testing "verifies task-id and title-pattern match same task"
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 1 :parent-id nil :title "first" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}
         {:id 2 :parent-id nil :title "second" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/delete-task-impl
                    (test-config)
                    nil
                    {:task-id 1 :title-pattern "second"})]
        (is (true? (:isError result)))
        (is (str/includes?
              (get-in result [:content 0 :text])
              "do not refer to the same task"))))))

(deftest delete-task-rejects-nonexistent-task
  ;; Tests that delete-task-impl returns error for non-existent task
  (testing "delete-task"
    (testing "returns error for non-existent task ID"
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/delete-task-impl
                    (test-config)
                    nil
                    {:task-id 999})]
        (is (true? (:isError result)))
        (is (str/includes?
              (get-in result [:content 0 :text])
              "Task ID not found"))))
    (testing "returns error for non-existent title-pattern"
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/delete-task-impl
                    (test-config)
                    nil
                    {:title-pattern "nonexistent"})]
        (is (true? (:isError result)))
        (is (str/includes?
              (get-in result [:content 0 :text])
              "No task found with exact title match"))))))

(deftest delete-task-prevents-deletion-with-non-closed-children
  ;; Tests that delete-task-impl prevents deletion of parent with non-closed children
  (testing "delete-task"
    (testing "prevents deletion of parent with non-closed children"
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 1 :parent-id nil :title "parent" :description "" :design "" :category "test" :type :story :status :open :meta {} :relations []}
         {:id 2 :parent-id 1 :title "child 1" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}
         {:id 3 :parent-id 1 :title "child 2" :description "" :design "" :category "test" :type :task :status :closed :meta {} :relations []}])
      (let [result (#'sut/delete-task-impl
                    (test-config)
                    nil
                    {:task-id 1})]
        (is (true? (:isError result)))
        (is (str/includes?
              (get-in result [:content 0 :text])
              "Cannot delete task with children"))
        ;; Verify error metadata includes child info
        (let [data (json/read-str (get-in result [:content 1 :text]) :key-fn keyword)]
          (is (= 1 (get-in data [:metadata :child-count])))
          (is (= 1 (count (get-in data [:metadata :non-closed-children])))))
        ;; Verify no deletion occurred
        (is (empty? (read-ednl-test-file "complete.ednl")))
        (is (= 3 (count (read-ednl-test-file "tasks.ednl"))))))))

(deftest delete-task-allows-deletion-with-all-closed-children
  ;; Tests that delete-task-impl allows deletion of parent when all children are closed
  (testing "delete-task"
    (testing "allows deletion of parent with all closed children"
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 1 :parent-id nil :title "parent" :description "" :design "" :category "test" :type :story :status :open :meta {} :relations []}
         {:id 2 :parent-id 1 :title "child 1" :description "" :design "" :category "test" :type :task :status :closed :meta {} :relations []}
         {:id 3 :parent-id 1 :title "child 2" :description "" :design "" :category "test" :type :task :status :closed :meta {} :relations []}])
      (let [result (#'sut/delete-task-impl
                    (test-config)
                    nil
                    {:task-id 1})]
        (is (false? (:isError result)))
        ;; Verify parent was deleted
        (let [complete-tasks (read-ednl-test-file "complete.ednl")]
          (is (= 1 (count complete-tasks)))
          (is (= 1 (:id (first complete-tasks))))
          (is (= :deleted (:status (first complete-tasks)))))
        ;; Verify children remain in tasks.ednl
        (let [tasks (read-ednl-test-file "tasks.ednl")]
          (is (= 2 (count tasks)))
          (is (every? #(= 1 (:parent-id %)) tasks)))))))

(deftest delete-task-allows-deletion-with-no-children
  ;; Tests that delete-task-impl allows deletion of task with no children
  (testing "delete-task"
    (testing "allows deletion of task with no children"
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 1 :parent-id nil :title "parent" :description "" :design "" :category "test" :type :story :status :open :meta {} :relations []}])
      (let [result (#'sut/delete-task-impl
                    (test-config)
                    nil
                    {:task-id 1})]
        (is (false? (:isError result)))
        ;; Verify task was deleted
        (let [complete-tasks (read-ednl-test-file "complete.ednl")]
          (is (= 1 (count complete-tasks)))
          (is (= :deleted (:status (first complete-tasks)))))
        (is (empty? (read-ednl-test-file "tasks.ednl")))))))

(deftest delete-task-allows-deletion-of-child-task
  ;; Tests that delete-task-impl allows deletion of child tasks
  (testing "delete-task"
    (testing "allows deletion of child task"
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 1 :parent-id nil :title "parent" :description "" :design "" :category "test" :type :story :status :open :meta {} :relations []}
         {:id 2 :parent-id 1 :title "child" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/delete-task-impl
                    (test-config)
                    nil
                    {:task-id 2})]
        (is (false? (:isError result)))
        ;; Verify child was deleted
        (let [complete-tasks (read-ednl-test-file "complete.ednl")]
          (is (= 1 (count complete-tasks)))
          (is (= 2 (:id (first complete-tasks))))
          (is (= :deleted (:status (first complete-tasks)))))
        ;; Verify parent remains
        (let [tasks (read-ednl-test-file "tasks.ednl")]
          (is (= 1 (count tasks)))
          (is (= 1 (:id (first tasks)))))))))

(deftest delete-task-rejects-already-deleted
  ;; Tests that delete-task-impl rejects tasks that are already deleted
  (testing "delete-task"
    (testing "rejects task that is already deleted"
      (write-ednl-test-file
        "tasks.ednl"
        [{:id 1 :parent-id nil :title "task" :description "" :design "" :category "test" :type :task :status :deleted :meta {} :relations []}])
      (let [result (#'sut/delete-task-impl
                    (test-config)
                    nil
                    {:task-id 1})]
        (is (true? (:isError result)))
        (is (str/includes?
              (get-in result [:content 0 :text])
              "already deleted"))
        ;; Verify no change in complete.ednl
        (is (empty? (read-ednl-test-file "complete.ednl")))))))

(deftest delete-task-returns-three-content-items-with-git
  ;; Tests that delete-task returns 3 content items when git is enabled
  (testing "delete-task with git enabled"
    (testing "returns three content items"
      (init-git-repo *test-dir*)
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "test task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])
      (let [result (#'sut/delete-task-impl
                    (git-test-config)
                    nil
                    {:task-id 1})]
        (is (false? (:isError result)))
        (is (= 3 (count (:content result))))

        ;; First content item: deletion message
        (let [text-content (first (:content result))]
          (is (= "text" (:type text-content)))
          (is (str/includes? (:text text-content) "Task 1 deleted")))

        ;; Second content item: deleted task data
        (let [deleted-content (second (:content result))
              deleted-data (json/read-str (:text deleted-content) :key-fn keyword)]
          (is (= "text" (:type deleted-content)))
          (is (contains? deleted-data :deleted))
          (is (= 1 (:id (:deleted deleted-data))))
          (is (= "deleted" (:status (:deleted deleted-data))))
          (is (contains? deleted-data :metadata))
          (is (= 1 (get-in deleted-data [:metadata :count])))
          (is (= "deleted" (get-in deleted-data [:metadata :status]))))

        ;; Third content item: git status
        (let [git-content (nth (:content result) 2)
              git-data (json/read-str (:text git-content) :key-fn keyword)]
          (is (= "text" (:type git-content)))
          (is (contains? git-data :git-status))
          (is (contains? git-data :git-commit)))))))

(deftest ^:integration delete-task-creates-git-commit
  ;; Integration test verifying git commit is actually created
  (testing "delete-task with git enabled"
    (testing "creates git commit with correct message"
      (init-git-repo *test-dir*)
      (write-ednl-test-file "tasks.ednl"
                            [{:id 42 :parent-id nil :title "implement feature X" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])

      ;; Delete the task
      (let [result (#'sut/delete-task-impl
                    (git-test-config)
                    nil
                    {:task-id 42})]
        (is (false? (:isError result)))

        ;; Verify git commit was created
        (is (git-commit-exists? *test-dir*))

        ;; Verify commit message format
        (let [commit-msg (git-log-last-commit *test-dir*)]
          (is (= "Delete task #42: implement feature X" commit-msg)))

        ;; Verify deleted task data in second content item
        (let [deleted-content (second (:content result))
              deleted-data (json/read-str (:text deleted-content) :key-fn keyword)]
          (is (= "text" (:type deleted-content)))
          (is (contains? deleted-data :deleted))
          (is (= 42 (:id (:deleted deleted-data))))
          (is (= "deleted" (:status (:deleted deleted-data))))
          (is (contains? deleted-data :metadata))
          (is (= 1 (get-in deleted-data [:metadata :count])))
          (is (= "deleted" (get-in deleted-data [:metadata :status]))))

        ;; Verify git status in response
        (let [git-content (nth (:content result) 2)
              git-data (json/read-str (:text git-content) :key-fn keyword)]
          (is (= "success" (:git-status git-data)))
          (is (string? (:git-commit git-data)))
          (is (= 40 (count (:git-commit git-data)))) ; SHA is 40 chars
          (is (nil? (:git-error git-data))))))))

(deftest ^:integration delete-task-succeeds-despite-git-failure
  ;; Tests that task deletion succeeds even when git operations fail
  (testing "delete-task with git enabled but no git repo"
    (testing "task deletes successfully despite git error"
      ;; Do not initialize git repo - this will cause git operations to fail
      (write-ednl-test-file "tasks.ednl"
                            [{:id 1 :parent-id nil :title "test task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])

      (let [result (#'sut/delete-task-impl
                    (git-test-config)
                    nil
                    {:task-id 1})]
        ;; Task deletion should succeed
        (is (false? (:isError result)))

        ;; Verify task was actually deleted
        (let [complete-tasks (read-ednl-test-file "complete.ednl")]
          (is (= 1 (count complete-tasks)))
          (is (= "test task" (:title (first complete-tasks))))
          (is (= :deleted (:status (first complete-tasks)))))

        ;; Verify deleted task data in second content item
        (let [deleted-content (second (:content result))
              deleted-data (json/read-str (:text deleted-content) :key-fn keyword)]
          (is (= "text" (:type deleted-content)))
          (is (contains? deleted-data :deleted))
          (is (= 1 (:id (:deleted deleted-data))))
          (is (= "deleted" (:status (:deleted deleted-data))))
          (is (contains? deleted-data :metadata))
          (is (= 1 (get-in deleted-data [:metadata :count])))
          (is (= "deleted" (get-in deleted-data [:metadata :status]))))

        ;; Verify git error is reported in response
        (let [git-content (nth (:content result) 2)
              git-data (json/read-str (:text git-content) :key-fn keyword)]
          (is (= "error" (:git-status git-data)))
          (is (nil? (:git-commit git-data)))
          (is (string? (:git-error git-data)))
          (is (not (str/blank? (:git-error git-data)))))))))

(deftest ^:integration delete-task-git-commit-sha-format
  ;; Tests that git commit SHA is returned in correct format
  (testing "delete-task with git enabled"
    (testing "returns valid git commit SHA"
      (init-git-repo *test-dir*)
      (write-ednl-test-file "tasks.ednl"
                            [{:id 99 :parent-id nil :title "task title" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}])

      (let [result (#'sut/delete-task-impl
                    (git-test-config)
                    nil
                    {:task-id 99})]

        ;; Verify deleted task data in second content item
        (let [deleted-content (second (:content result))
              deleted-data (json/read-str (:text deleted-content) :key-fn keyword)]
          (is (= "text" (:type deleted-content)))
          (is (contains? deleted-data :deleted))
          (is (= 99 (:id (:deleted deleted-data))))
          (is (= "deleted" (:status (:deleted deleted-data))))
          (is (contains? deleted-data :metadata))
          (is (= 1 (get-in deleted-data [:metadata :count])))
          (is (= "deleted" (get-in deleted-data [:metadata :status]))))

        ;; Verify SHA format
        (let [git-content (nth (:content result) 2)
              git-data (json/read-str (:text git-content) :key-fn keyword)
              sha (:git-commit git-data)]
          (is (string? sha))
          (is (= 40 (count sha)))
          (is (re-matches #"[0-9a-f]{40}" sha)))))))
