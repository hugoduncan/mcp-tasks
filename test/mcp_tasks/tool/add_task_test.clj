(ns mcp-tasks.tool.add-task-test
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.tasks :as tasks]
    [mcp-tasks.test-helpers :as h]
    [mcp-tasks.tool.add-task :as sut]))

(deftest add-task-returns-structured-data
  (h/with-test-setup [test-dir]
    (testing "add-task"
      (testing "returns text message and structured data"
        (let [result (#'sut/add-task-impl
                      (h/test-config test-dir)
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
                data (json/parse-string (:text data-content) keyword)]
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
              (is (= "add-task" (:operation metadata))))))))))

(deftest add-task-includes-parent-id-for-story-tasks
  (h/with-test-setup [test-dir]
    (testing "add-task"
      (testing "includes parent-id for story tasks"
        ;; First create a story task
        (h/write-ednl-test-file
          test-dir
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
        (tasks/load-tasks! (str test-dir "/.mcp-tasks/tasks.ednl"))
        ;; Add a child task to the story
        (let [result (#'sut/add-task-impl
                      (h/test-config test-dir)
                      nil
                      {:category "simple"
                       :title "Child task"
                       :parent-id 1})]
          (is (false? (:isError result)))
          ;; Verify structured data includes parent-id
          (let [data-content (second (:content result))
                data (json/parse-string (:text data-content) keyword)
                task (:task data)]
            (is (= 1 (:parent-id task)))
            (is (= "Child task" (:title task)))
            (is (= "simple" (:category task)))))))))

(deftest add-task-omits-nil-parent-id
  (h/with-test-setup [test-dir]
    (testing "add-task"
      (testing "includes parent-id field (as nil) for non-story tasks"
        (let [result (#'sut/add-task-impl
                      (h/test-config test-dir)
                      nil
                      {:category "test"
                       :title "Regular task"})]
          (is (false? (:isError result)))
          (let [data-content (second (:content result))
                data (json/parse-string (:text data-content) keyword)
                task (:task data)]
            ;; parent-id should be present in select-keys but will be nil
            (is (nil? (:parent-id task)))
            (is (= "Regular task" (:title task)))))))))

(deftest add-task-error-parent-not-found
  (h/with-test-setup [test-dir]
    (testing "add-task"
      (testing "returns error when parent task not found"
        (let [result (#'sut/add-task-impl
                      (h/test-config test-dir)
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
                data (json/parse-string (:text data-content) keyword)]
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
              (is (contains? metadata :file)))))))))

(deftest add-task-error-response-structure
  (h/with-test-setup [test-dir]
    (testing "add-task"
      (testing "error responses have correct structure"
        (let [result (#'sut/add-task-impl
                      (h/test-config test-dir)
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
                data (json/parse-string data-text keyword)]
            (is (contains? data :error))
            (is (string? (:error data)))
            (is (contains? data :metadata))
            (is (map? (:metadata data)))))))))

(deftest add-task-schema-validation-error
  (h/with-test-setup [test-dir]
    (testing "add-task"
      (testing "returns error map for invalid schema"
        (testing "missing required title field"
          ;; category and title are required by the tool's inputSchema
          ;; but if we bypass that and pass invalid data to add-task-impl,
          ;; the schema validation in tasks/add-task should catch it
          ;; With new error handling, exceptions are converted to error maps
          (let [result (#'sut/add-task-impl
                        (h/test-config test-dir)
                        nil
                        {:category "test"
                         :title nil})]
            (is (map? result))
            (is (true? (:isError result)))))
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
                   :relations []}))))))))

(deftest add-task-returns-two-items-without-git
  (h/with-test-setup [test-dir]
    (testing "add-task with git disabled"
      (testing "returns two content items"
        (let [result (#'sut/add-task-impl
                      (h/test-config test-dir)
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
                data (json/parse-string (:text data-content) keyword)]
            (is (= "text" (:type data-content)))
            (is (contains? data :task))
            (is (contains? data :metadata))))))))

(deftest add-task-returns-three-items-with-git
  (h/with-test-setup [test-dir]
    (testing "add-task with git enabled"
      (testing "returns three content items"
        (h/init-git-repo test-dir)
        (let [result (#'sut/add-task-impl
                      (h/git-test-config test-dir)
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
                data (json/parse-string (:text data-content) keyword)]
            (is (= "text" (:type data-content)))
            (is (contains? data :task))
            (is (contains? data :metadata)))

          ;; Third content item: git status
          (let [git-content (nth (:content result) 2)
                git-data (json/parse-string (:text git-content) keyword)]
            (is (= "text" (:type git-content)))
            (is (contains? git-data :git-status))
            (is (contains? git-data :git-commit))))))))

(deftest ^:integration add-task-creates-git-commit
  (h/with-test-setup [test-dir]
    (testing "add-task with git enabled"
      (testing "creates git commit with correct message format"
        (h/init-git-repo test-dir)
        (let [result (#'sut/add-task-impl
                      (h/git-test-config test-dir)
                      nil
                      {:category "test"
                       :title "implement feature Y"
                       :description "Feature description"})]
          (is (false? (:isError result)))

          ;; Extract task ID from response
          (let [data-content (second (:content result))
                data (json/parse-string (:text data-content) keyword)
                task-id (get-in data [:task :id])]

            ;; Verify git commit was created
            (is (h/git-commit-exists? test-dir))

            ;; Verify commit message format: "Add task #<id>: <title>"
            (let [commit-msg (h/git-log-last-commit test-dir)]
              (is (= (str "Add task #" task-id ": implement feature Y") commit-msg)))

            ;; Verify git status in response
            (let [git-content (nth (:content result) 2)
                  git-data (json/parse-string (:text git-content) keyword)]
              (is (= "success" (:git-status git-data)))
              (is (string? (:git-commit git-data)))
              (is (= 40 (count (:git-commit git-data)))) ; SHA is 40 chars
              (is (nil? (:git-error git-data))))))))))

(deftest ^:integration add-task-truncates-long-titles
  (h/with-test-setup [test-dir]
    (testing "add-task with git enabled"
      (testing "truncates titles longer than 50 chars in commit message"
        (h/init-git-repo test-dir)
        (let [long-title "This is a very long title that exceeds fifty characters in length"
              result (#'sut/add-task-impl
                      (h/git-test-config test-dir)
                      nil
                      {:category "test"
                       :title long-title
                       :description "Description"})]
          (is (false? (:isError result)))

          ;; Extract task ID from response
          (let [data-content (second (:content result))
                data (json/parse-string (:text data-content) keyword)
                task-id (get-in data [:task :id])]

            ;; Verify commit message has truncated title
            (let [commit-msg (h/git-log-last-commit test-dir)]
              (is (str/starts-with? commit-msg (str "Add task #" task-id ": ")))
              ;; Truncated title should be 50 chars (47 + "...")
              (is (str/includes? commit-msg "...")))

            ;; But task data should have full title
            (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")]
              (is (= long-title (:title (first tasks)))))))))))

(deftest ^:integration add-task-succeeds-despite-git-failure
  (h/with-test-setup [test-dir]
    (testing "add-task with git enabled but no git repo"
      (testing "task added successfully despite git error"
        ;; Do not initialize git repo - this will cause git operations to fail
        (let [result (#'sut/add-task-impl
                      (h/git-test-config test-dir)
                      nil
                      {:category "test"
                       :title "test task"
                       :description "description"})]
          ;; Task addition should succeed
          (is (false? (:isError result)))
          (is (= 3 (count (:content result))))

          ;; Verify task was actually added
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")]
            (is (= 1 (count tasks)))
            (is (= "test task" (:title (first tasks)))))

          ;; Verify git error is reported in response
          (let [git-content (nth (:content result) 2)
                git-data (json/parse-string (:text git-content) keyword)]
            (is (= "error" (:git-status git-data)))
            (is (nil? (:git-commit git-data)))
            (is (string? (:git-error git-data)))
            (is (not (str/blank? (:git-error git-data))))))))))

(deftest add-task-with-relations
  (h/with-test-setup [test-dir]
    (testing "add-task"
      (testing "accepts and stores relations parameter"
        ;; First create a task to reference
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1
            :parent-id nil
            :title "First task"
            :description ""
            :design ""
            :category "test"
            :type :task
            :status :open
            :meta {}
            :relations []}])
        ;; Load the first task into memory
        (tasks/load-tasks! (str test-dir "/.mcp-tasks/tasks.ednl"))
        ;; Add a task with relations
        (let [result (#'sut/add-task-impl
                      (h/test-config test-dir)
                      nil
                      {:category "test"
                       :title "Second task"
                       :relations [{"id" 1
                                    "relates-to" 1
                                    "as-type" "blocked-by"}]})]
          (is (false? (:isError result)))
          ;; Verify task was added with relations
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                second-task (first (filter #(= "Second task" (:title %)) tasks))]
            (is (= "Second task" (:title second-task)))
            (is (= [{:id 1 :relates-to 1 :as-type :blocked-by}] (:relations second-task)))))))))

(deftest add-task-with-no-relations
  (h/with-test-setup [test-dir]
    (testing "add-task"
      (testing "stores empty relations when not provided"
        (let [result (#'sut/add-task-impl
                      (h/test-config test-dir)
                      nil
                      {:category "test"
                       :title "Task without relations"})]
          (is (false? (:isError result)))
          ;; Verify task was added with empty relations
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task (first tasks)]
            (is (= "Task without relations" (:title task)))
            (is (= [] (:relations task)))))))))

(deftest add-task-with-multiple-relations
  (h/with-test-setup [test-dir]
    (testing "add-task"
      (testing "accepts multiple relations"
        ;; Create tasks to reference
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1
            :parent-id nil
            :title "First task"
            :description ""
            :design ""
            :category "test"
            :type :task
            :status :open
            :meta {}
            :relations []}
           {:id 2
            :parent-id nil
            :title "Second task"
            :description ""
            :design ""
            :category "test"
            :type :task
            :status :open
            :meta {}
            :relations []}])
        (tasks/load-tasks! (str test-dir "/.mcp-tasks/tasks.ednl"))
        ;; Add a task with multiple relations
        (let [result (#'sut/add-task-impl
                      (h/test-config test-dir)
                      nil
                      {:category "test"
                       :title "Third task"
                       :relations [{"id" 1
                                    "relates-to" 1
                                    "as-type" "blocked-by"}
                                   {"id" 2
                                    "relates-to" 2
                                    "as-type" "related"}]})]
          (is (false? (:isError result)))
          ;; Verify task was added with multiple relations
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                third-task (first (filter #(= "Third task" (:title %)) tasks))]
            (is (= "Third task" (:title third-task)))
            (is (= [{:id 1 :relates-to 1 :as-type :blocked-by}
                    {:id 2 :relates-to 2 :as-type :related}]
                   (:relations third-task)))))))))

(deftest add-task-error-invalid-relation-task-id
  ;; Test adding task with relation referencing non-existent task ID
  (h/with-test-setup [test-dir]
    (testing "add-task"
      (testing "returns error when relation references non-existent task ID"
        (let [result (#'sut/add-task-impl
                      (h/test-config test-dir)
                      nil
                      {:category "test"
                       :title "Task with invalid relation"
                       :relations [{"id" 1
                                    "relates-to" 999
                                    "as-type" "blocked-by"}]})]
          (is (true? (:isError result)))
          (is (= 2 (count (:content result))))
          ;; First content is error message
          (let [text-content (first (:content result))]
            (is (= "text" (:type text-content)))
            (is (= "Task ID 999 referenced in relations does not exist" (:text text-content))))
          ;; Second content is structured error data
          (let [data-content (second (:content result))
                data (json/parse-string (:text data-content) keyword)]
            (is (= "text" (:type data-content)))
            (is (contains? data :error))
            (is (contains? data :metadata))
            (is (= "Task ID 999 referenced in relations does not exist" (:error data)))
            (let [metadata (:metadata data)]
              (is (= "add-task" (:attempted-operation metadata)))
              (is (= [999] (:missing-task-ids metadata)))
              (is (contains? metadata :file)))))))))

(deftest add-task-error-multiple-invalid-relation-task-ids
  ;; Test adding task with multiple relations referencing non-existent task IDs
  (h/with-test-setup [test-dir]
    (testing "add-task"
      (testing "returns error listing all non-existent task IDs"
        ;; Create one valid task
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1
            :parent-id nil
            :title "Valid task"
            :description ""
            :design ""
            :category "test"
            :type :task
            :status :open
            :meta {}
            :relations []}])
        (tasks/load-tasks! (str test-dir "/.mcp-tasks/tasks.ednl"))
        ;; Attempt to add task with mix of valid and invalid relations
        (let [result (#'sut/add-task-impl
                      (h/test-config test-dir)
                      nil
                      {:category "test"
                       :title "Task with invalid relations"
                       :relations [{"id" 1
                                    "relates-to" 1
                                    "as-type" "blocked-by"}
                                   {"id" 2
                                    "relates-to" 888
                                    "as-type" "related"}
                                   {"id" 3
                                    "relates-to" 999
                                    "as-type" "discovered-during"}]})]
          (is (true? (:isError result)))
          (is (= 2 (count (:content result))))
          ;; First content is error message
          (let [text-content (first (:content result))]
            (is (= "text" (:type text-content)))
            (is (= "Task IDs 888, 999 referenced in relations do not exist" (:text text-content))))
          ;; Second content is structured error data
          (let [data-content (second (:content result))
                data (json/parse-string (:text data-content) keyword)]
            (is (= "text" (:type data-content)))
            (is (contains? data :error))
            (is (contains? data :metadata))
            (is (= "Task IDs 888, 999 referenced in relations do not exist" (:error data)))
            (let [metadata (:metadata data)]
              (is (= "add-task" (:attempted-operation metadata)))
              (is (= [888 999] (:missing-task-ids metadata)))
              (is (contains? metadata :file)))))))))

(deftest add-task-error-circular-dependency
  ;; Test that creating a task with blocked-by relation that creates a cycle fails
  (h/with-test-setup [test-dir]
    (testing "add-task"
      (testing "returns error when creating task would create circular dependency"
        ;; Create Task A
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1
            :parent-id nil
            :title "Task A"
            :description ""
            :design ""
            :category "test"
            :type :task
            :status :open
            :meta {}
            :relations []}])
        (tasks/load-tasks! (str test-dir "/.mcp-tasks/tasks.ednl"))

        ;; Attempt to add Task B that is blocked-by Task A
        ;; but then update Task A to be blocked-by Task B (creating A→B→A cycle)
        (let [result-b (#'sut/add-task-impl
                        (h/test-config test-dir)
                        nil
                        {:category "test"
                         :title "Task B"
                         :relations [{"id" 1
                                      "relates-to" 1
                                      "as-type" "blocked-by"}]})]
          (is (false? (:isError result-b)))

          ;; Now try to add Task C that is blocked-by Task B,
          ;; while Task B is blocked-by Task A,
          ;; and Task A is blocked-by Task C (creating cycle A→C→B→A)
          ;; But we need to first update A to be blocked by something
          ;; Let's create a simpler cycle: Task C blocks itself via Task B

          ;; Actually, let's test the direct A→B, B→A cycle
          ;; Update Task A to be blocked-by the newly created Task B
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task-b (first (filter #(= "Task B" (:title %)) tasks))
                task-b-id (:id task-b)]

            ;; Write Task A with relation to Task B
            (h/write-ednl-test-file
              test-dir
              "tasks.ednl"
              [{:id 1
                :parent-id nil
                :title "Task A"
                :description ""
                :design ""
                :category "test"
                :type :task
                :status :open
                :meta {}
                :relations [{:id 1 :relates-to task-b-id :as-type :blocked-by}]}
               task-b])
            (tasks/load-tasks! (str test-dir "/.mcp-tasks/tasks.ednl"))

            ;; Now try to add Task C that is blocked-by Task A
            ;; This creates: Task A → Task B (via A's relation)
            ;;               Task B → Task A (via B's relation)
            ;;               Task C → Task A (via C's relation)
            ;; So Task C would see: C → A → B → A (cycle)
            (let [result-c (#'sut/add-task-impl
                            (h/test-config test-dir)
                            nil
                            {:category "test"
                             :title "Task C"
                             :relations [{"id" 1
                                          "relates-to" 1
                                          "as-type" "blocked-by"}]})]
              (is (true? (:isError result-c)))
              (is (= 2 (count (:content result-c))))

              ;; Verify error message mentions circular dependency
              (let [text-content (first (:content result-c))]
                (is (= "text" (:type text-content)))
                (is (str/includes? (:text text-content) "Circular dependency detected")))

              ;; Verify structured error data includes cycle information
              (let [data-content (second (:content result-c))
                    data (json/parse-string (:text data-content) keyword)]
                (is (contains? data :error))
                (is (contains? data :metadata))
                (is (str/includes? (:error data) "Circular dependency detected"))
                (let [metadata (:metadata data)]
                  (is (= "add-task" (:attempted-operation metadata)))
                  (is (contains? metadata :cycle))
                  (is (vector? (:cycle metadata)))
                  (is (contains? metadata :file))))

              ;; Verify Task C was NOT added to the file
              (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")]
                (is (= 2 (count tasks))) ; Only Task A and Task B
                (is (nil? (first (filter #(= "Task C" (:title %)) tasks))))))))))))

(deftest add-task-success-valid-dependency-chain
  ;; Test that creating tasks with valid multi-level dependencies succeeds
  (h/with-test-setup [test-dir]
    (testing "add-task"
      (testing "succeeds with valid multi-level dependency chain"
        ;; Create Task A (no dependencies)
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1
            :parent-id nil
            :title "Task A"
            :description ""
            :design ""
            :category "test"
            :type :task
            :status :open
            :meta {}
            :relations []}])
        (tasks/load-tasks! (str test-dir "/.mcp-tasks/tasks.ednl"))

        ;; Add Task B blocked-by Task A
        (let [result-b (#'sut/add-task-impl
                        (h/test-config test-dir)
                        nil
                        {:category "test"
                         :title "Task B"
                         :relations [{"id" 1
                                      "relates-to" 1
                                      "as-type" "blocked-by"}]})]
          (is (false? (:isError result-b)))

          ;; Get Task B ID from file
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task-b (first (filter #(= "Task B" (:title %)) tasks))
                task-b-id (:id task-b)]

            ;; Add Task C blocked-by Task B (creates chain A→B→C, no cycle)
            (let [result-c (#'sut/add-task-impl
                            (h/test-config test-dir)
                            nil
                            {:category "test"
                             :title "Task C"
                             :relations [{"id" 1
                                          "relates-to" task-b-id
                                          "as-type" "blocked-by"}]})]
              (is (false? (:isError result-c)))

              ;; Verify all three tasks exist
              (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")]
                (is (= 3 (count tasks)))
                (is (some #(= "Task A" (:title %)) tasks))
                (is (some #(= "Task B" (:title %)) tasks))
                (is (some #(= "Task C" (:title %)) tasks))

                ;; Verify Task C has correct relation
                (let [task-c (first (filter #(= "Task C" (:title %)) tasks))]
                  (is (= [{:id 1 :relates-to task-b-id :as-type :blocked-by}]
                         (:relations task-c))))))))))))

(deftest add-task-success-no-cycle-with-relations
  ;; Test creating a task with blocked-by relation that doesn't create a cycle
  (h/with-test-setup [test-dir]
    (testing "add-task"
      (testing "succeeds when blocked-by relation doesn't create cycle"
        ;; Create Task A (no dependencies)
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1
            :parent-id nil
            :title "Task A"
            :description ""
            :design ""
            :category "test"
            :type :task
            :status :open
            :meta {}
            :relations []}])
        (tasks/load-tasks! (str test-dir "/.mcp-tasks/tasks.ednl"))

        ;; Add Task B blocked-by Task A (no cycle, valid)
        (let [result (#'sut/add-task-impl
                      (h/test-config test-dir)
                      nil
                      {:category "test"
                       :title "Task B"
                       :relations [{"id" 1
                                    "relates-to" 1
                                    "as-type" "blocked-by"}]})]
          (is (false? (:isError result)))

          ;; Verify Task B was added
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")
                task-b (first (filter #(= "Task B" (:title %)) tasks))]
            (is (some? task-b))
            (is (= [{:id 1 :relates-to 1 :as-type :blocked-by}]
                   (:relations task-b)))))))))
