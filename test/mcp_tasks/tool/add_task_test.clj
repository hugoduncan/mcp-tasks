(ns mcp-tasks.tool.add-task-test
  (:require
    [clojure.data.json :as json]
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
                data (json/read-str (:text data-content) :key-fn keyword)
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
                data (json/read-str (:text data-content) :key-fn keyword)
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
                data (json/read-str data-text :key-fn keyword)]
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
                data (json/read-str (:text data-content) :key-fn keyword)]
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
                data (json/read-str (:text data-content) :key-fn keyword)]
            (is (= "text" (:type data-content)))
            (is (contains? data :task))
            (is (contains? data :metadata)))

          ;; Third content item: git status
          (let [git-content (nth (:content result) 2)
                git-data (json/read-str (:text git-content) :key-fn keyword)]
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
                data (json/read-str (:text data-content) :key-fn keyword)
                task-id (get-in data [:task :id])]

            ;; Verify git commit was created
            (is (h/git-commit-exists? test-dir))

            ;; Verify commit message format: "Add task #<id>: <title>"
            (let [commit-msg (h/git-log-last-commit test-dir)]
              (is (= (str "Add task #" task-id ": implement feature Y") commit-msg)))

            ;; Verify git status in response
            (let [git-content (nth (:content result) 2)
                  git-data (json/read-str (:text git-content) :key-fn keyword)]
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
                data (json/read-str (:text data-content) :key-fn keyword)
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
                git-data (json/read-str (:text git-content) :key-fn keyword)]
            (is (= "error" (:git-status git-data)))
            (is (nil? (:git-commit git-data)))
            (is (string? (:git-error git-data)))
            (is (not (str/blank? (:git-error git-data))))))))))
