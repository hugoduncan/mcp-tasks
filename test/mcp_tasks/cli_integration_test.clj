(ns mcp-tasks.cli-integration-test
  "End-to-end integration tests for the CLI.
  
  Tests complete workflows chaining multiple commands together and verifies
  that the CLI correctly calls underlying tool functions."
  (:require
    [babashka.fs :as fs]
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-tasks.cli :as cli]
    [mcp-tasks.tasks-file :as tasks-file]
    [mcp-tasks.test-helpers :as h]))

(def ^:dynamic *test-dir* nil)

(defn- setup-test-dir
  [test-dir]
  (fs/create-dirs (io/file test-dir ".mcp-tasks"))
  (fs/create-dirs (io/file test-dir ".mcp-tasks/prompts"))
  ;; Create a simple category prompt for testing
  (spit (io/file test-dir ".mcp-tasks/prompts/simple.md")
        "---\ndescription: Simple tasks\n---\nSimple task execution"))

(defn- cli-test-fixture
  "CLI-specific test fixture that also sets up prompts directory."
  [f]
  (let [test-dir (str (fs/create-temp-dir {:prefix "mcp-tasks-cli-integration-"}))]
    (try
      (setup-test-dir test-dir)
      (binding [h/*test-dir* test-dir
                *test-dir* test-dir]
        (h/reset-tasks-state!)
        (f))
      (finally
        (fs/delete-tree test-dir)))))

(use-fixtures :each cli-test-fixture)

(defn- call-cli
  "Call CLI main function capturing stdout and exit code.
  Returns {:exit exit-code :out output-string :err error-string}"
  [& args]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        exit-code (atom nil)]
    (binding [*out* out
              *err* err]
      (with-redefs [cli/exit (fn [code] (reset! exit-code code))]
        (apply cli/-main args)))
    {:exit @exit-code
     :out (str out)
     :err (str err)}))

(defn- read-tasks-file
  []
  (let [tasks-file (io/file *test-dir* ".mcp-tasks/tasks.ednl")]
    (when (.exists tasks-file)
      (tasks-file/read-ednl (.getPath tasks-file)))))

(defn- read-complete-file
  []
  (let [complete-file (io/file *test-dir* ".mcp-tasks/complete.ednl")]
    (when (.exists complete-file)
      (tasks-file/read-ednl (.getPath complete-file)))))

(deftest complete-workflow-edn-format-test
  ;; Test a complete workflow: add task → show task → update task → complete task → list completed
  ;; Uses EDN format throughout
  (testing "complete-workflow-edn-format"
    (testing "can add a task and get EDN response"
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "edn"
                             "add"
                             "--category" "simple"
                             "--title" "Test task"
                             "--description" "A test task")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) ":id 1"))
        (is (str/includes? (:out result) ":title \"Test task\""))
        (let [parsed (read-string (:out result))]
          (is (map? parsed))
          (is (= 1 (-> parsed :task :id))))))

    (testing "can show the task by ID"
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "edn"
                             "show"
                             "--task-id" "1")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= 1 (-> parsed :task :id)))
          (is (= "Test task" (-> parsed :task :title))))))

    (testing "can update the task status"
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "edn"
                             "update"
                             "--task-id" "1"
                             "--status" "in-progress")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= "in-progress" (:status (:task parsed)))))))

    (testing "can complete the task"
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "edn"
                             "complete"
                             "--task-id" "1"
                             "--completion-comment" "All done")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= "closed" (:status (:task parsed))))
          (is (str/includes? (:description (:task parsed)) "All done")))))

    (testing "task moved to complete file"
      (let [tasks (read-tasks-file)
            completed (read-complete-file)]
        (is (empty? tasks))
        (is (= 1 (count completed)))
        (is (= "Test task" (:title (first completed))))
        (is (= :closed (:status (first completed))))))

    (testing "can list completed tasks"
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "edn"
                             "list"
                             "--status" "closed")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= 1 (-> parsed :metadata :count)))
          (is (= "Test task" (-> parsed :tasks first :title))))))))

(deftest complete-workflow-json-format-test
  ;; Test the same workflow using JSON format
  (testing "complete-workflow-json-format"
    (testing "can add a task and get JSON response"
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "json"
                             "add"
                             "--category" "simple"
                             "--title" "JSON test"
                             "--description" "Test with JSON")]
        (is (= 0 (:exit result)))
        (let [parsed (json/read-str (:out result) :key-fn keyword)]
          (is (map? parsed))
          (is (= 1 (:id (:task parsed)))))))

    (testing "can show task in JSON format"
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "json"
                             "show"
                             "--task-id" "1")]
        (is (= 0 (:exit result)))
        (let [parsed (json/read-str (:out result) :key-fn keyword)]
          (is (= 1 (-> parsed :task :id)))
          (is (= "JSON test" (-> parsed :task :title))))))

    (testing "can update in JSON format"
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "json"
                             "update"
                             "--task-id" "1"
                             "--title" "Updated JSON test")]
        (is (= 0 (:exit result)))
        (let [parsed (json/read-str (:out result) :key-fn keyword)]
          (is (= "Updated JSON test" (:title (:task parsed)))))))

    (testing "can complete in JSON format"
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "json"
                             "complete"
                             "--task-id" "1")]
        (is (= 0 (:exit result)))
        (let [parsed (json/read-str (:out result) :key-fn keyword)]
          (is (= "closed" (:status (:task parsed)))))))))

(deftest complete-workflow-human-format-test
  ;; Test workflow with human-readable format
  (testing "complete-workflow-human-format"
    (testing "can add a task with human output"
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "human"
                             "add"
                             "--category" "simple"
                             "--title" "Human test")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "Task #1"))
        (is (str/includes? (:out result) "Human test"))))

    (testing "can list tasks in human format"
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "human"
                             "list")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "1"))
        (is (str/includes? (:out result) "Human test"))))

    (testing "can show task in human format"
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "human"
                             "show"
                             "--task-id" "1")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "Task #1"))
        (is (str/includes? (:out result) "Human test"))))

    (testing "can complete task with human output"
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "human"
                             "complete"
                             "--task-id" "1")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "completed"))))))

(deftest error-scenarios-test
  (testing "error-scenarios"
    (testing "missing config directory returns error"
      (let [result (call-cli "--config-path" "/nonexistent/path"
                             "list")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))))

    (testing "invalid task ID returns error"
      ;; Create a task first so the tasks file exists
      (call-cli "--config-path" *test-dir*
                "add"
                "--category" "simple"
                "--title" "Test task")
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "edn"
                             "show"
                             "--task-id" "999")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))
        (is (str/includes? (:err result) "No task found"))))

    (testing "invalid command returns error"
      (let [result (call-cli "--config-path" *test-dir*
                             "nonexistent-command")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))))

    (testing "validation error for invalid type"
      (let [result (call-cli "--config-path" *test-dir*
                             "add"
                             "--category" "simple"
                             "--title" "Test"
                             "--type" "invalid-type")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))))

    (testing "completing non-existent task returns error"
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "edn"
                             "complete"
                             "--task-id" "999")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))
        (is (or (str/includes? (:err result) "Task ID not found")
                (str/includes? (:err result) "not found")))))

    (testing "invalid JSON in --meta"
      (testing "malformed JSON returns error"
        (let [result (call-cli "--config-path" *test-dir*
                               "update"
                               "--task-id" "1"
                               "--meta" "{invalid json}")]
          (is (= 1 (:exit result)))
          (is (not (str/blank? (:err result))))
          (is (str/includes? (:err result) "Invalid JSON"))))

      (testing "non-object JSON returns error"
        (let [result (call-cli "--config-path" *test-dir*
                               "update"
                               "--task-id" "1"
                               "--meta" "[1, 2, 3]")]
          (is (= 1 (:exit result)))
          (is (not (str/blank? (:err result))))
          (is (str/includes? (:err result) "Expected JSON object")))))

    (testing "invalid JSON in --relations"
      (testing "malformed JSON returns error"
        (let [result (call-cli "--config-path" *test-dir*
                               "update"
                               "--task-id" "1"
                               "--relations" "{not an array}")]
          (is (= 1 (:exit result)))
          (is (not (str/blank? (:err result))))
          (is (or (str/includes? (:err result) "Invalid JSON")
                  (str/includes? (:err result) "Expected JSON array")))))

      (testing "non-array JSON returns error"
        (let [result (call-cli "--config-path" *test-dir*
                               "update"
                               "--task-id" "1"
                               "--relations" "{\"key\": \"value\"}")]
          (is (= 1 (:exit result)))
          (is (not (str/blank? (:err result))))
          (is (str/includes? (:err result) "Expected JSON array")))))

    (testing "missing required arguments"
      (testing "add without --category returns error"
        (let [result (call-cli "--config-path" *test-dir*
                               "add"
                               "--title" "Test")]
          (is (= 1 (:exit result)))
          (is (not (str/blank? (:err result))))
          (is (str/includes? (:err result) "category"))))

      (testing "add without --title returns error"
        (let [result (call-cli "--config-path" *test-dir*
                               "add"
                               "--category" "simple")]
          (is (= 1 (:exit result)))
          (is (not (str/blank? (:err result))))
          (is (str/includes? (:err result) "title"))))

      (testing "update without --task-id returns error"
        (let [result (call-cli "--config-path" *test-dir*
                               "update"
                               "--title" "Updated")]
          (is (= 1 (:exit result)))
          (is (not (str/blank? (:err result))))
          (is (str/includes? (:err result) "task-id"))))

      (testing "show without --task-id returns error"
        (let [result (call-cli "--config-path" *test-dir*
                               "show")]
          (is (= 1 (:exit result)))
          (is (not (str/blank? (:err result))))
          (is (str/includes? (:err result) "task-id"))))

      (testing "complete without identifier returns error"
        (let [result (call-cli "--config-path" *test-dir*
                               "complete")]
          (is (= 1 (:exit result)))
          (is (not (str/blank? (:err result))))
          (is (or (str/includes? (:err result) "task-id")
                  (str/includes? (:err result) "title")))))

      (testing "delete without identifier returns error"
        (let [result (call-cli "--config-path" *test-dir*
                               "delete")]
          (is (= 1 (:exit result)))
          (is (not (str/blank? (:err result))))
          (is (or (str/includes? (:err result) "task-id")
                  (str/includes? (:err result) "title-pattern"))))))

    (testing "invalid format values"
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "xml"
                             "list")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))
        (is (or (str/includes? (:err result) "Invalid format")
                (str/includes? (:err result) "Unknown format")))))

    (testing "invalid status values"
      (let [result (call-cli "--config-path" *test-dir*
                             "update"
                             "--task-id" "1"
                             "--status" "invalid-status")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))))))

(deftest multi-task-workflow-test
  ;; Test workflows with multiple tasks
  (testing "multi-task-workflow"
    (testing "can add multiple tasks"
      (call-cli "--config-path" *test-dir*
                "add" "--category" "simple" "--title" "Task 1")
      (call-cli "--config-path" *test-dir*
                "add" "--category" "simple" "--title" "Task 2")
      (let [result (call-cli "--config-path" *test-dir*
                             "add" "--category" "simple" "--title" "Task 3")]
        (is (= 0 (:exit result)))))

    (testing "list shows all three tasks"
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "edn"
                             "list")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= 3 (-> parsed :metadata :count))))))

    (testing "can filter by title pattern"
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "edn"
                             "list"
                             "--title-pattern" "Task 2")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= 1 (-> parsed :metadata :count)))
          (is (= "Task 2" (-> parsed :tasks first :title))))))

    (testing "can complete specific task by title"
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "edn"
                             "complete"
                             "--title" "Task 2")]
        (is (= 0 (:exit result)))))

    (testing "list shows remaining two tasks"
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "edn"
                             "list"
                             "--status" "open")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= 2 (-> parsed :metadata :count))))))))

(deftest git-enabled-workflow-test
  ;; Test workflow with git enabled
  (testing "git-enabled-workflow"
    (testing "setup git repository"
      (h/init-git-repo *test-dir*)
      (is (.exists (io/file *test-dir* ".mcp-tasks/.git"))))

    (testing "add task creates git commit"
      (spit (io/file *test-dir* ".mcp-tasks.edn") "{:use-git? true}")
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "edn"
                             "add"
                             "--category" "simple"
                             "--title" "Git task")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          ;; Should have git commit info in response
          (is (contains? parsed :git-commit)))))

    (testing "complete task creates git commit"
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "edn"
                             "complete"
                             "--task-id" "1")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (contains? parsed :git-commit)))))

    (testing "update task creates git commit"
      (call-cli "--config-path" *test-dir*
                "add" "--category" "simple" "--title" "Another task")
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "edn"
                             "update"
                             "--task-id" "2"
                             "--title" "Updated title")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (contains? parsed :git-commit)))))

    (testing "delete task creates git commit"
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "edn"
                             "delete"
                             "--task-id" "2")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (contains? parsed :git-commit)))))))

(deftest non-git-workflow-test
  ;; Test workflow without git
  (testing "non-git-workflow"
    (testing "add task without git works"
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "edn"
                             "add"
                             "--category" "simple"
                             "--title" "Non-git task")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          ;; Should not have git commit info
          (is (not (contains? parsed :git-commit))))))

    (testing "complete task without git works"
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "edn"
                             "complete"
                             "--task-id" "1")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (not (contains? parsed :git-commit))))))))

(deftest parent-child-task-workflow-test
  ;; Test workflow with parent-child relationships
  (testing "parent-child-task-workflow"
    (testing "add parent story task"
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "edn"
                             "add"
                             "--category" "simple"
                             "--title" "Parent story"
                             "--type" "story")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= "story" (:type (:task parsed)))))))

    (testing "add child task with parent-id"
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "edn"
                             "add"
                             "--category" "simple"
                             "--title" "Child task"
                             "--parent-id" "1")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= 1 (:parent-id (:task parsed)))))))

    (testing "list child tasks by parent-id"
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "edn"
                             "list"
                             "--parent-id" "1")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= 1 (-> parsed :metadata :count)))
          (is (= "Child task" (-> parsed :tasks first :title))))))

    (testing "can update child task parent-id to nil"
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "edn"
                             "update"
                             "--task-id" "2"
                             "--parent-id" "null")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (nil? (:parent-id (:task parsed)))))))))

(deftest delete-workflow-test
  ;; Test delete command in workflow
  (testing "delete-workflow"
    (testing "add tasks to delete"
      (call-cli "--config-path" *test-dir*
                "add" "--category" "simple" "--title" "To delete 1")
      (call-cli "--config-path" *test-dir*
                "add" "--category" "simple" "--title" "To delete 2"))

    (testing "can delete by task-id"
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "edn"
                             "delete"
                             "--task-id" "1")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= "deleted" (:status (:deleted parsed)))))))

    (testing "deleted task moved to complete file"
      (let [tasks (read-tasks-file)
            completed (read-complete-file)]
        (is (= 1 (count tasks)))
        (is (= 1 (count completed)))
        (is (= :deleted (:status (first completed))))))

    (testing "can delete by title pattern"
      (let [result (call-cli "--config-path" *test-dir*
                             "--format" "edn"
                             "delete"
                             "--title-pattern" "To delete 2")]
        (is (= 0 (:exit result)))))

    (testing "both tasks deleted"
      (let [tasks (read-tasks-file)
            completed (read-complete-file)]
        (is (empty? tasks))
        (is (= 2 (count completed)))))))

(deftest help-text-workflow-test
  ;; Test help functionality
  (testing "help-text-workflow"
    (testing "global help works"
      (let [result (call-cli "--help")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "mcp-tasks"))))

    (testing "command-specific help works"
      (let [result (call-cli "list" "--help")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "list"))))

    (testing "help for each command"
      (doseq [cmd ["add" "show" "update" "complete" "delete"]]
        (let [result (call-cli cmd "--help")]
          (is (= 0 (:exit result)))
          (is (str/includes? (:out result) cmd)))))))

(deftest unknown-option-validation-test
  ;; Test that CLI rejects unknown options with clear error messages
  (testing "unknown-option-validation"
    (testing "add command rejects completely invalid option"
      (let [result (call-cli "--config-path" *test-dir*
                             "add"
                             "--category" "simple"
                             "--title" "Test"
                             "--foo" "bar")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))
        (is (str/includes? (:err result) "Unknown option: --foo"))
        (is (str/includes? (:err result) "Use --help"))))

    (testing "add command rejects typo in option name"
      (let [result (call-cli "--config-path" *test-dir*
                             "add"
                             "--category" "simple"
                             "--titl" "Test")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))
        (is (str/includes? (:err result) "Unknown option: --titl"))
        (is (str/includes? (:err result) "Use --help"))))

    (testing "show command rejects option valid for another command"
      (let [result (call-cli "--config-path" *test-dir*
                             "show"
                             "--task-id" "1"
                             "--parent-id" "5")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))
        (is (str/includes? (:err result) "Unknown option: --parent-id"))
        (is (str/includes? (:err result) "Use --help"))))

    (testing "list command rejects unknown option"
      (let [result (call-cli "--config-path" *test-dir*
                             "list"
                             "--invalid-filter" "value")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))
        (is (str/includes? (:err result) "Unknown option: --invalid-filter"))))

    (testing "complete command rejects unknown option"
      (let [result (call-cli "--config-path" *test-dir*
                             "complete"
                             "--task-id" "1"
                             "--unknown" "value")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))
        (is (str/includes? (:err result) "Unknown option: --unknown"))))))
