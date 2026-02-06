(ns mcp-tasks.cli-integration-test
  "End-to-end integration tests for the CLI.

  Tests complete workflows chaining multiple commands together and verifies
  that the CLI correctly calls underlying tool functions."
  (:require
    [babashka.fs :as fs]
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.java.shell :as sh]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-tasks.cli :as cli]
    [mcp-tasks.tasks-file :as tasks-file]
    [mcp-tasks.test-helpers :as h]))

(def ^:dynamic *test-dir* nil)

(defn- setup-test-dir
  [test-dir]
  (fs/create-dirs (io/file test-dir ".mcp-tasks"))
  (fs/create-dirs (io/file test-dir ".mcp-tasks/category-prompts"))
  ;; Create a simple category prompt for testing
  (spit (io/file test-dir ".mcp-tasks/category-prompts/simple.md")
        "---\ndescription: Simple tasks\n---\nSimple task execution"))

(defn- cli-test-fixture
  "CLI-specific test fixture that also sets up prompts directory."
  [f]
  (let [test-dir (str (fs/create-temp-dir {:prefix "mcp-tasks-cli-integration-"}))]
    (try
      (setup-test-dir test-dir)
      (binding [*test-dir* test-dir]
        (h/reset-tasks-state!)
        (f))
      (finally
        (fs/delete-tree test-dir)))))

(use-fixtures :each cli-test-fixture)

(defn- call-cli
  "Call CLI main function capturing stdout and exit code.
  Changes working directory to *test-dir* for config discovery.
  Returns {:exit exit-code :out output-string :err error-string}"
  [& args]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        exit-code (atom nil)
        original-dir (System/getProperty "user.dir")]
    (try
      ;; Change to test directory for config discovery
      (System/setProperty "user.dir" *test-dir*)
      (binding [*out* out
                *err* err]
        (with-redefs [cli/exit (fn [code] (reset! exit-code code))]
          (apply cli/-main args)))
      (finally
        ;; Restore original directory
        (System/setProperty "user.dir" original-dir)))
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
      (let [result (call-cli
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
      (let [result (call-cli
                     "--format" "edn"
                     "show"
                     "--task-id" "1")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= 1 (-> parsed :task :id)))
          (is (= "Test task" (-> parsed :task :title))))))

    (testing "can update the task status"
      (let [result (call-cli
                     "--format" "edn"
                     "update"
                     "--task-id" "1"
                     "--status" "in-progress")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= "in-progress" (:status (:task parsed)))))))

    (testing "can complete the task"
      (let [result (call-cli
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
      (let [result (call-cli
                     "--format" "edn"
                     "list"
                     "--status" "closed")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= 1 (-> parsed :metadata :open-task-count)))
          (is (= "Test task" (-> parsed :tasks first :title))))))))

(deftest complete-workflow-json-format-test
  ;; Test the same workflow using JSON format
  (testing "complete-workflow-json-format"
    (testing "can add a task and get JSON response"
      (let [result (call-cli
                     "--format" "json"
                     "add"
                     "--category" "simple"
                     "--title" "JSON test"
                     "--description" "Test with JSON")]
        (is (= 0 (:exit result)))
        (let [parsed (json/parse-string (:out result) keyword)]
          (is (map? parsed))
          (is (= 1 (:id (:task parsed)))))))

    (testing "can show task in JSON format"
      (let [result (call-cli
                     "--format" "json"
                     "show"
                     "--task-id" "1")]
        (is (= 0 (:exit result)))
        (let [parsed (json/parse-string (:out result) keyword)]
          (is (= 1 (-> parsed :task :id)))
          (is (= "JSON test" (-> parsed :task :title))))))

    (testing "can update in JSON format"
      (let [result (call-cli
                     "--format" "json"
                     "update"
                     "--task-id" "1"
                     "--title" "Updated JSON test")]
        (is (= 0 (:exit result)))
        (let [parsed (json/parse-string (:out result) keyword)]
          (is (= "Updated JSON test" (:title (:task parsed)))))))

    (testing "can complete in JSON format"
      (let [result (call-cli
                     "--format" "json"
                     "complete"
                     "--task-id" "1")]
        (is (= 0 (:exit result)))
        (let [parsed (json/parse-string (:out result) keyword)]
          (is (= "closed" (:status (:task parsed)))))))))

(deftest complete-workflow-human-format-test
  ;; Test workflow with human-readable format
  (testing "complete-workflow-human-format"
    (testing "can add a task with human output"
      (let [result (call-cli
                     "--format" "human"
                     "add"
                     "--category" "simple"
                     "--title" "Human test")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "Task #1"))
        (is (str/includes? (:out result) "Human test"))))

    (testing "can list tasks in human format"
      (let [result (call-cli
                     "--format" "human"
                     "list")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "1"))
        (is (str/includes? (:out result) "Human test"))))

    (testing "can show task in human format"
      (let [result (call-cli
                     "--format" "human"
                     "show"
                     "--task-id" "1")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "Task #1"))
        (is (str/includes? (:out result) "Human test"))))

    (testing "can complete task with human output"
      (let [result (call-cli
                     "--format" "human"
                     "complete"
                     "--task-id" "1")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "completed"))))))

(deftest error-scenarios-test
  (testing "error-scenarios"

    (testing "invalid task ID returns error"
      ;; Create a task first so the tasks file exists
      (call-cli
        "add"
        "--category" "simple"
        "--title" "Test task")
      (let [result (call-cli
                     "--format" "edn"
                     "show"
                     "--task-id" "999")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))
        (is (str/includes? (:err result) "No task found"))))

    (testing "invalid command returns error"
      (let [result (call-cli
                     "nonexistent-command")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))))

    (testing "validation error for invalid type"
      (let [result (call-cli
                     "add"
                     "--category" "simple"
                     "--title" "Test"
                     "--type" "invalid-type")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))))

    (testing "completing non-existent task returns error"
      (let [result (call-cli
                     "--format" "edn"
                     "complete"
                     "--task-id" "999")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))
        (is (or (str/includes? (:err result) "Task ID not found")
                (str/includes? (:err result) "not found")))))

    (testing "invalid JSON in --meta"
      (testing "malformed JSON returns error"
        (let [result (call-cli
                       "update"
                       "--task-id" "1"
                       "--meta" "{invalid json}")]
          (is (= 1 (:exit result)))
          (is (not (str/blank? (:err result))))
          (is (str/includes? (:err result) "Invalid JSON"))))

      (testing "non-object JSON returns error"
        (let [result (call-cli
                       "update"
                       "--task-id" "1"
                       "--meta" "[1, 2, 3]")]
          (is (= 1 (:exit result)))
          (is (not (str/blank? (:err result))))
          (is (str/includes? (:err result) "Expected JSON object")))))

    (testing "invalid JSON in --relations"
      (testing "malformed JSON returns error"
        (let [result (call-cli
                       "update"
                       "--task-id" "1"
                       "--relations" "{not an array}")]
          (is (= 1 (:exit result)))
          (is (not (str/blank? (:err result))))
          (is (or (str/includes? (:err result) "Invalid JSON")
                  (str/includes? (:err result) "Expected JSON array")))))

      (testing "non-array JSON returns error"
        (let [result (call-cli
                       "update"
                       "--task-id" "1"
                       "--relations" "{\"key\": \"value\"}")]
          (is (= 1 (:exit result)))
          (is (not (str/blank? (:err result))))
          (is (str/includes? (:err result) "Expected JSON array")))))

    (testing "missing required arguments"
      (testing "add without --category returns error"
        (let [result (call-cli
                       "add"
                       "--title" "Test")]
          (is (= 1 (:exit result)))
          (is (not (str/blank? (:err result))))
          (is (str/includes? (:err result) "category"))))

      (testing "add without --title returns error"
        (let [result (call-cli
                       "add"
                       "--category" "simple")]
          (is (= 1 (:exit result)))
          (is (not (str/blank? (:err result))))
          (is (str/includes? (:err result) "title"))))

      (testing "update without --task-id returns error"
        (let [result (call-cli
                       "update"
                       "--title" "Updated")]
          (is (= 1 (:exit result)))
          (is (not (str/blank? (:err result))))
          (is (str/includes? (:err result) "task-id"))))

      (testing "show without --task-id returns error"
        (let [result (call-cli
                       "show")]
          (is (= 1 (:exit result)))
          (is (not (str/blank? (:err result))))
          (is (str/includes? (:err result) "task-id"))))

      (testing "complete without identifier returns error"
        (let [result (call-cli
                       "complete")]
          (is (= 1 (:exit result)))
          (is (not (str/blank? (:err result))))
          (is (or (str/includes? (:err result) "task-id")
                  (str/includes? (:err result) "title")))))

      (testing "delete without identifier returns error"
        (let [result (call-cli
                       "delete")]
          (is (= 1 (:exit result)))
          (is (not (str/blank? (:err result))))
          (is (or (str/includes? (:err result) "task-id")
                  (str/includes? (:err result) "title-pattern"))))))

    (testing "invalid format values"
      (let [result (call-cli
                     "--format" "xml"
                     "list")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))
        (is (or (str/includes? (:err result) "Invalid format")
                (str/includes? (:err result) "Unknown format")))))

    (testing "invalid status values"
      (let [result (call-cli
                     "update"
                     "--task-id" "1"
                     "--status" "invalid-status")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))))))

(deftest multi-task-workflow-test
  ;; Test workflows with multiple tasks
  (testing "multi-task-workflow"
    (testing "can add multiple tasks"
      (call-cli
        "add" "--category" "simple" "--title" "Task 1")
      (call-cli
        "add" "--category" "simple" "--title" "Task 2")
      (let [result (call-cli
                     "add" "--category" "simple" "--title" "Task 3")]
        (is (= 0 (:exit result)))))

    (testing "list shows all three tasks"
      (let [result (call-cli
                     "--format" "edn"
                     "list")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= 3 (-> parsed :metadata :open-task-count))))))

    (testing "can filter by title pattern"
      (let [result (call-cli
                     "--format" "edn"
                     "list"
                     "--title-pattern" "Task 2")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= 1 (-> parsed :metadata :open-task-count)))
          (is (= "Task 2" (-> parsed :tasks first :title))))))

    (testing "can complete specific task by title"
      (let [result (call-cli
                     "--format" "edn"
                     "complete"
                     "--title" "Task 2")]
        (is (= 0 (:exit result)))))

    (testing "list shows remaining two tasks"
      (let [result (call-cli
                     "--format" "edn"
                     "list"
                     "--status" "open")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= 2 (-> parsed :metadata :open-task-count))))))))

(deftest git-enabled-workflow-test
  ;; Test workflow with git enabled
  (testing "git-enabled-workflow"
    (testing "setup git repository"
      (h/init-git-repo *test-dir*)
      (is (.exists (io/file *test-dir* ".mcp-tasks/.git"))))

    (testing "add task creates git commit"
      (spit (io/file *test-dir* ".mcp-tasks.edn") "{:use-git? true}")
      (let [result (call-cli
                     "--format" "edn"
                     "add"
                     "--category" "simple"
                     "--title" "Git task")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          ;; Should have git commit info in response
          (is (contains? parsed :git-commit)))))

    (testing "complete task creates git commit"
      (let [result (call-cli
                     "--format" "edn"
                     "complete"
                     "--task-id" "1")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (contains? parsed :git-commit)))))

    (testing "update task creates git commit"
      (call-cli
        "add" "--category" "simple" "--title" "Another task")
      (let [result (call-cli
                     "--format" "edn"
                     "update"
                     "--task-id" "2"
                     "--title" "Updated title")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (contains? parsed :git-commit)))))

    (testing "delete task creates git commit"
      (let [result (call-cli
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
      (let [result (call-cli
                     "--format" "edn"
                     "add"
                     "--category" "simple"
                     "--title" "Non-git task")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          ;; Should not have git commit info
          (is (not (contains? parsed :git-commit))))))

    (testing "complete task without git works"
      (let [result (call-cli
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
      (let [result (call-cli
                     "--format" "edn"
                     "add"
                     "--category" "simple"
                     "--title" "Parent story"
                     "--type" "story")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= "story" (:type (:task parsed)))))))

    (testing "add child task with parent-id"
      (let [result (call-cli
                     "--format" "edn"
                     "add"
                     "--category" "simple"
                     "--title" "Child task"
                     "--parent-id" "1")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= 1 (:parent-id (:task parsed)))))))

    (testing "list child tasks by parent-id"
      (let [result (call-cli
                     "--format" "edn"
                     "list"
                     "--parent-id" "1")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= 1 (-> parsed :metadata :open-task-count)))
          (is (= "Child task" (-> parsed :tasks first :title))))))

    (testing "can update child task parent-id to nil"
      (let [result (call-cli
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
      (call-cli
        "add" "--category" "simple" "--title" "To delete 1")
      (call-cli
        "add" "--category" "simple" "--title" "To delete 2"))

    (testing "can delete by task-id"
      (let [result (call-cli
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
      (let [result (call-cli
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
      (let [result (call-cli
                     "add"
                     "--category" "simple"
                     "--title" "Test"
                     "--foo" "bar")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))
        (is (str/includes? (:err result) "Unknown option: --foo"))
        (is (str/includes? (:err result) "Use --help"))))

    (testing "add command rejects typo in option name"
      (let [result (call-cli
                     "add"
                     "--category" "simple"
                     "--titl" "Test")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))
        (is (str/includes? (:err result) "Unknown option: --titl"))
        (is (str/includes? (:err result) "Use --help"))))

    (testing "show command rejects option valid for another command"
      (let [result (call-cli
                     "show"
                     "--task-id" "1"
                     "--parent-id" "5")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))
        (is (str/includes? (:err result) "Unknown option: --parent-id"))
        (is (str/includes? (:err result) "Use --help"))))

    (testing "list command rejects unknown option"
      (let [result (call-cli
                     "list"
                     "--invalid-filter" "value")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))
        (is (str/includes? (:err result) "Unknown option: --invalid-filter"))))

    (testing "complete command rejects unknown option"
      (let [result (call-cli
                     "complete"
                     "--task-id" "1"
                     "--unknown" "value")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))
        (is (str/includes? (:err result) "Unknown option: --unknown"))))))

(deftest default-format-test
  ;; Test that the default format (when --format is not specified) is human-readable
  (testing "default-format"
    (testing "list command uses human format by default"
      ;; Add a task first
      (call-cli
        "add"
        "--category" "simple"
        "--title" "Default format test")

      (let [result (call-cli
                     "list")]
        (is (= 0 (:exit result)))
        ;; Human format should contain table headers
        (is (str/includes? (:out result) "ID"))
        (is (str/includes? (:out result) "Status"))
        (is (str/includes? (:out result) "Category"))
        (is (str/includes? (:out result) "Title"))
        ;; Should NOT contain EDN-style output like ":tasks" or ":metadata"
        (is (not (str/includes? (:out result) ":tasks")))
        (is (not (str/includes? (:out result) ":metadata")))))

    (testing "show command uses human format by default"
      (let [result (call-cli
                     "show"
                     "--task-id" "1")]
        (is (= 0 (:exit result)))
        ;; Human format should contain "Task #" prefix
        (is (str/includes? (:out result) "Task #1"))
        (is (str/includes? (:out result) "Default format test"))
        ;; Should NOT contain EDN-style output like ":task" or ":id"
        (is (not (str/includes? (:out result) ":task")))
        (is (not (str/includes? (:out result) ":id")))))))

(deftest status-any-workflow-test
  ;; Test --status any returns both active and completed tasks
  (testing "status-any-workflow"
    (testing "add multiple tasks"
      (call-cli "add" "--category" "simple" "--title" "Active Task 1")
      (call-cli "add" "--category" "simple" "--title" "Active Task 2")
      (call-cli "add" "--category" "simple" "--title" "Complete Task 1")
      (let [result (call-cli "add" "--category" "simple" "--title" "Complete Task 2")]
        (is (= 0 (:exit result)))))

    (testing "complete two tasks"
      (call-cli "complete" "--title" "Complete Task 1")
      (let [result (call-cli "complete" "--title" "Complete Task 2")]
        (is (= 0 (:exit result)))))

    (testing "list --status any returns all tasks"
      (let [result (call-cli "--format" "edn" "list" "--status" "any")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= 4 (count (:tasks parsed)))
              "Should have 4 tasks in result")
          (is (= 4 (-> parsed :metadata :total-matches))
              "Should show 4 total matches"))))

    (testing "tasks are in correct order (active then completed)"
      (let [result (call-cli "--format" "edn" "list" "--status" "any")
            parsed (read-string (:out result))
            tasks (:tasks parsed)
            titles (mapv :title tasks)]
        (is (= ["Active Task 1" "Active Task 2" "Complete Task 1" "Complete Task 2"]
               titles)
            "Active tasks should come before completed tasks")))

    (testing "status any works with parent-id filter"
      (call-cli "add" "--category" "simple" "--title" "Parent" "--type" "story")
      (call-cli "add" "--category" "simple" "--title" "Child 1" "--parent-id" "5")
      (call-cli "add" "--category" "simple" "--title" "Child 2" "--parent-id" "5")
      (call-cli "complete" "--title" "Child 1")
      (let [result (call-cli "--format" "edn" "list" "--status" "any" "--parent-id" "5")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= 2 (count (:tasks parsed)))
              "Should return both completed and open child tasks")
          (is (= 2 (-> parsed :metadata :open-task-count))
              "Should show 2 total matching tasks")
          (is (nil? (-> parsed :metadata :completed-task-count))
              "completed-task-count should not be present when status is explicit"))))))

(deftest reopen-workflow-edn-format-test
  ;; Test reopening tasks with EDN format
  (testing "reopen-workflow-edn-format"
    (testing "can add and complete a task"
      (call-cli
        "add"
        "--category" "simple"
        "--title" "Task to reopen"
        "--description" "Test reopen")
      (let [result (call-cli
                     "--format" "edn"
                     "complete"
                     "--task-id" "1")]
        (is (= 0 (:exit result)))))

    (testing "task is in complete file after completion"
      (let [tasks (read-tasks-file)
            completed (read-complete-file)]
        (is (empty? tasks))
        (is (= 1 (count completed)))
        (is (= :closed (:status (first completed))))))

    (testing "can reopen the archived task by task-id"
      (let [result (call-cli
                     "--format" "edn"
                     "reopen"
                     "--task-id" "1")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= "open" (:status (:task parsed))))
          (is (= "Task to reopen" (:title (:task parsed)))))))

    (testing "task moved back to tasks file with open status"
      (let [tasks (read-tasks-file)
            completed (read-complete-file)]
        (is (= 1 (count tasks)))
        (is (empty? completed))
        (is (= :open (:status (first tasks))))
        (is (= "Task to reopen" (:title (first tasks))))))

    (testing "can close and reopen again"
      (call-cli "complete" "--task-id" "1")
      (let [result (call-cli
                     "--format" "edn"
                     "reopen"
                     "--task-id" "1")]
        (is (= 0 (:exit result)))))))

(deftest reopen-by-title-workflow-test
  ;; Test reopening using exact title match
  (testing "reopen-by-title-workflow"
    (testing "add and complete task"
      (call-cli
        "add"
        "--category" "simple"
        "--title" "Unique reopen title")
      (call-cli "complete" "--task-id" "1"))

    (testing "can reopen by exact title"
      (let [result (call-cli
                     "--format" "edn"
                     "reopen"
                     "--title" "Unique reopen title")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= "open" (:status (:task parsed))))
          (is (= "Unique reopen title" (:title (:task parsed)))))))

    (testing "task is back in tasks file"
      (let [tasks (read-tasks-file)]
        (is (= 1 (count tasks)))
        (is (= :open (:status (first tasks))))))))

(deftest reopen-formats-test
  ;; Test reopen with different output formats
  (testing "reopen-formats"
    (testing "JSON format"
      (call-cli
        "add"
        "--category" "simple"
        "--title" "JSON reopen test")
      (call-cli "complete" "--task-id" "1")
      (let [result (call-cli
                     "--format" "json"
                     "reopen"
                     "--task-id" "1")]
        (is (= 0 (:exit result)))
        (let [parsed (json/parse-string (:out result) keyword)]
          (is (= "open" (:status (:task parsed))))
          (is (= "JSON reopen test" (:title (:task parsed)))))))

    (testing "human format"
      (call-cli "complete" "--task-id" "1")
      (let [result (call-cli
                     "--format" "human"
                     "reopen"
                     "--task-id" "1")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "Task #1"))
        (is (str/includes? (:out result) "JSON reopen test"))
        (is (str/includes? (:out result) "Status: open"))))))

(deftest reopen-error-scenarios-test
  ;; Test error handling for reopen command
  (testing "reopen-error-scenarios"
    (testing "task not found returns error"
      (call-cli
        "add"
        "--category" "simple"
        "--title" "Error test task")
      (let [result (call-cli
                     "--format" "edn"
                     "reopen"
                     "--task-id" "999")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))
        (is (or (str/includes? (:err result) "Task not found")
                (str/includes? (:err result) "not found")))))

    (testing "already open task returns error"
      (let [result (call-cli
                     "--format" "edn"
                     "reopen"
                     "--task-id" "1")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))
        (is (or (str/includes? (:err result) "already open")
                (str/includes? (:err result) "Task is already open")))))

    (testing "missing required arguments returns error"
      (let [result (call-cli
                     "reopen")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))
        (is (or (str/includes? (:err result) "task-id")
                (str/includes? (:err result) "title")))))))

(deftest reopen-git-workflow-test
  ;; Test reopen with git integration
  (testing "reopen-git-workflow"
    (testing "setup git repository"
      (h/init-git-repo *test-dir*)
      (spit (io/file *test-dir* ".mcp-tasks.edn") "{:use-git? true}")
      (is (.exists (io/file *test-dir* ".mcp-tasks/.git"))))

    (testing "add and complete task"
      (call-cli
        "add"
        "--category" "simple"
        "--title" "Git reopen task")
      (call-cli "complete" "--task-id" "1"))

    (testing "reopen creates git commit"
      (let [result (call-cli
                     "--format" "edn"
                     "reopen"
                     "--task-id" "1")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (contains? parsed :git-commit))
          (is (string? (:git-commit parsed)))
          (is (not (str/blank? (:git-commit parsed)))))))))

(deftest reopen-preserves-metadata-test
  ;; Test that reopening preserves task fields
  (testing "reopen-preserves-metadata"
    (testing "add task with description and parent"
      (call-cli
        "add"
        "--category" "simple"
        "--title" "Parent task"
        "--type" "story")
      (let [result (call-cli
                     "--format" "edn"
                     "add"
                     "--category" "simple"
                     "--title" "Child task"
                     "--description" "Test description"
                     "--parent-id" "1")]
        (is (= 0 (:exit result)))))

    (testing "complete task"
      (call-cli "complete" "--task-id" "2"))

    (testing "reopen preserves description and parent-id"
      (let [result (call-cli
                     "--format" "edn"
                     "reopen"
                     "--task-id" "2")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))
              task (:task parsed)]
          (is (= "open" (:status task)))
          (is (= "Child task" (:title task)))
          (is (str/includes? (:description task) "Test description"))
          (is (= 1 (:parent-id task))))))))

(deftest reopen-appends-to-tasks-test
  ;; Test that reopened tasks are appended to the end of tasks.ednl
  (testing "reopen-appends-to-tasks"
    (testing "add multiple tasks"
      (call-cli "add" "--category" "simple" "--title" "Task 1")
      (call-cli "add" "--category" "simple" "--title" "Task 2")
      (call-cli "add" "--category" "simple" "--title" "Task 3"))

    (testing "complete middle task"
      (call-cli "complete" "--task-id" "2"))

    (testing "verify task order before reopen"
      (let [tasks (read-tasks-file)
            titles (mapv :title tasks)]
        (is (= ["Task 1" "Task 3"] titles))))

    (testing "reopen the completed task"
      (call-cli "reopen" "--task-id" "2"))

    (testing "reopened task is appended to end"
      (let [tasks (read-tasks-file)
            titles (mapv :title tasks)]
        (is (= ["Task 1" "Task 3" "Task 2"] titles)
            "Task 2 should be appended to the end after reopening")))))

(deftest why-blocked-workflow-test
  ;; Test why-blocked command with different scenarios and formats
  (testing "why-blocked-workflow"
    (testing "setup tasks with blocked-by relationships"
      (call-cli "add" "--category" "simple" "--title" "Base task")
      (call-cli "add" "--category" "simple" "--title" "Blocking task 1")
      (call-cli "add" "--category" "simple" "--title" "Blocking task 2")
      (call-cli "add" "--category" "simple" "--title" "Blocked task")
      (let [result (call-cli
                     "--format" "edn"
                     "update"
                     "--task-id" "4"
                     "--relations" "[{\"id\":1,\"relates-to\":2,\"as-type\":\"blocked-by\"},{\"id\":2,\"relates-to\":3,\"as-type\":\"blocked-by\"}]")]
        (is (= 0 (:exit result)))))

    (testing "can query why-blocked with task-id in human format"
      (let [result (call-cli
                     "--format" "human"
                     "why-blocked"
                     "--task-id" "4")]
        (is (= 0 (:exit result)))
        ;; Human format for why-blocked shows detailed task info
        (is (str/includes? (:out result) "4"))
        (is (str/includes? (:out result) "Blocked task"))
        (is (or (str/includes? (:out result) "Blocked by")
                (str/includes? (:out result) "⊠")))))

    (testing "can query why-blocked in edn format"
      (let [result (call-cli
                     "--format" "edn"
                     "why-blocked"
                     "--task-id" "4")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (contains? parsed :why-blocked))
          (is (= 4 (-> parsed :why-blocked :id)))
          (is (= "Blocked task" (-> parsed :why-blocked :title)))
          (is (contains? (:why-blocked parsed) :is-blocked))
          (is (contains? (:why-blocked parsed) :blocking-task-ids)))))

    (testing "can query why-blocked in json format"
      (let [result (call-cli
                     "--format" "json"
                     "why-blocked"
                     "--task-id" "4")]
        (is (= 0 (:exit result)))
        (let [parsed (json/parse-string (:out result) keyword)]
          (is (contains? parsed :whyBlocked))
          (is (= 4 (-> parsed :whyBlocked :id)))
          (is (= "Blocked task" (-> parsed :whyBlocked :title))))))

    (testing "can query unblocked task"
      (let [result (call-cli
                     "--format" "edn"
                     "why-blocked"
                     "--task-id" "1")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (contains? parsed :why-blocked))
          (is (= 1 (-> parsed :why-blocked :id)))
          (is (= "Base task" (-> parsed :why-blocked :title)))
          (is (contains? (:why-blocked parsed) :is-blocked))
          (is (contains? (:why-blocked parsed) :blocking-task-ids)))))

    (testing "why-blocked with invalid task-id returns error"
      (let [result (call-cli
                     "--format" "edn"
                     "why-blocked"
                     "--task-id" "999")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))
        (is (or (str/includes? (:err result) "No task found")
                (str/includes? (:err result) "not found")))))

    (testing "why-blocked without task-id returns error"
      (let [result (call-cli "why-blocked")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))
        (is (str/includes? (:err result) "task-id"))))))

(deftest shared-context-cli-workflow-test
  ;; Test end-to-end shared-context behavior via CLI, verifying entry appending
  ;; and automatic prefixing based on execution state.
  (testing "shared-context-cli-workflow"
    (testing "setup: add a story task"
      (let [result (call-cli
                     "--format" "edn"
                     "add"
                     "--category" "simple"
                     "--title" "Story with context"
                     "--type" "story")]
        (is (= 0 (:exit result)))))

    (testing "update with --shared-context appends entry (no prefix without execution state)"
      (let [result (call-cli
                     "--format" "edn"
                     "update"
                     "--task-id" "1"
                     "--shared-context" "First context entry")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))
              task (:task parsed)]
          (is (= ["First context entry"] (:shared-context task))))))

    (testing "update with -C alias appends to existing context"
      (let [result (call-cli
                     "--format" "edn"
                     "update"
                     "--task-id" "1"
                     "-C" "Second context entry")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))
              task (:task parsed)]
          (is (= ["First context entry" "Second context entry"]
                 (:shared-context task))))))

    (testing "shared-context respects execution state for prefixing"
      (let [state {:story-id 1
                   :task-id 42
                   :task-start-time "2025-01-01T12:00:00Z"}
            state-file (io/file *test-dir* ".mcp-tasks-current.edn")]
        (spit state-file (pr-str state)))
      (let [result (call-cli
                     "--format" "edn"
                     "update"
                     "--task-id" "1"
                     "--shared-context" "Entry with task prefix")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))
              task (:task parsed)]
          (is (= ["First context entry"
                  "Second context entry"
                  "Task 42: Entry with task prefix"]
                 (:shared-context task))))))

    (testing "can combine --shared-context with other update options"
      (let [result (call-cli
                     "--format" "edn"
                     "update"
                     "--task-id" "1"
                     "--title" "Updated story title"
                     "--shared-context" "Context with title change")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))
              task (:task parsed)]
          (is (= "Updated story title" (:title task)))
          (is (= ["First context entry"
                  "Second context entry"
                  "Task 42: Entry with task prefix"
                  "Task 42: Context with title change"]
                 (:shared-context task))))))))

(deftest shared-context-size-limit-cli-test
  ;; Test that 50KB size limit is enforced via CLI
  (testing "shared-context-size-limit-cli"
    (testing "setup: add a task"
      (let [result (call-cli
                     "add"
                     "--category" "simple"
                     "--title" "Size limit test")]
        (is (= 0 (:exit result)))))

    (testing "rejects context exceeding 50KB limit"
      (let [large-entry (apply str (repeat 52000 "x"))
            result (call-cli
                     "--format" "edn"
                     "update"
                     "--task-id" "1"
                     "--shared-context" large-entry)]
        (is (= 1 (:exit result)))
        (is (str/includes? (:err result) "Shared context size limit (50KB) exceeded"))))

    (testing "task shared-context unchanged after failed update"
      (let [result (call-cli
                     "--format" "edn"
                     "show"
                     "--task-id" "1")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))
              task (:task parsed)]
          (is (or (nil? (:shared-context task))
                  (empty? (:shared-context task)))))))))

(deftest work-on-workflow-test
  ;; Test work-on command end-to-end
  (testing "work-on-workflow"
    (testing "add a task to work on"
      (let [result (call-cli
                     "--format" "edn"
                     "add"
                     "--category" "simple"
                     "--title" "Test work-on task")]
        (is (= 0 (:exit result)))))

    (testing "work-on returns task details in human format"
      (let [result (call-cli
                     "--format" "human"
                     "work-on"
                     "--task-id" "1")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "Working on task 1"))
        (is (str/includes? (:out result) "Test work-on task"))))

    (testing "work-on returns task details in edn format"
      (let [result (call-cli
                     "--format" "edn"
                     "work-on"
                     "--task-id" "1")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= 1 (:task-id parsed)))
          (is (= "Test work-on task" (:title parsed)))
          (is (= "simple" (:category parsed)))
          (is (some? (:execution-state-file parsed))))))

    (testing "work-on returns task details in json format"
      (let [result (call-cli
                     "--format" "json"
                     "work-on"
                     "--task-id" "1")]
        (is (= 0 (:exit result)))
        (let [parsed (json/parse-string (:out result) keyword)]
          (is (= 1 (:taskId parsed)))
          (is (= "Test work-on task" (:title parsed))))))

    (testing "work-on creates execution state file"
      (let [state-file (io/file *test-dir* ".mcp-tasks-current.edn")]
        (is (.exists state-file))
        (let [state (read-string (slurp state-file))]
          (is (= 1 (:task-id state)))
          (is (some? (:task-start-time state))))))

    (testing "work-on with non-existent task returns error"
      (let [result (call-cli
                     "--format" "edn"
                     "work-on"
                     "--task-id" "999")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))
        (is (str/includes? (:err result) "No task found"))))

    (testing "work-on without task-id returns error"
      (let [result (call-cli "work-on")]
        (is (= 1 (:exit result)))
        (is (not (str/blank? (:err result))))
        (is (str/includes? (:err result) "task-id"))))))

(deftest work-on-story-child-workflow-test
  ;; Test work-on with story child tasks
  (testing "work-on-story-child-workflow"
    (testing "add parent story and child task"
      (call-cli
        "add"
        "--category" "large"
        "--title" "Parent story"
        "--type" "story")
      (call-cli
        "add"
        "--category" "simple"
        "--title" "Child task"
        "--parent-id" "1"))

    (testing "work-on child task sets story-id in execution state"
      (let [result (call-cli
                     "--format" "edn"
                     "work-on"
                     "--task-id" "2")]
        (is (= 0 (:exit result)))
        (let [parsed (read-string (:out result))]
          (is (= 2 (:task-id parsed)))
          (is (= "Child task" (:title parsed)))))
      (let [state-file (io/file *test-dir* ".mcp-tasks-current.edn")
            state (read-string (slurp state-file))]
        (is (= 2 (:task-id state)))
        (is (= 1 (:story-id state)))))))

(deftest work-on-help-test
  ;; Test work-on help functionality
  (testing "work-on-help"
    (testing "work-on --help shows usage"
      (let [result (call-cli "work-on" "--help")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "work-on"))
        (is (str/includes? (:out result) "--task-id"))))))

(deftest work-on-from-child-git-dir-test
  ;; Verify work-on succeeds when config is in parent dir and CLI runs
  ;; from a child directory with its own git repo. Regression test for
  ;; the fix where CLI passes start-dir to resolve-config.
  (testing "work-on-from-child-git-dir"
    (testing "adds a task from parent dir"
      (let [result (call-cli
                     "--format" "edn"
                     "add"
                     "--category" "simple"
                     "--title" "Child dir task")]
        (is (= 0 (:exit result)))))

    (testing "succeeds when called from child git directory"
      (let [child-dir (str *test-dir* "/child-project")]
        (fs/create-dirs child-dir)
        (sh/sh "git" "init" :dir child-dir)
        (sh/sh "git" "config" "user.email" "test@test.com" :dir child-dir)
        (sh/sh "git" "config" "user.name" "Test" :dir child-dir)

        (let [out (java.io.StringWriter.)
              err (java.io.StringWriter.)
              exit-code (atom nil)
              original-dir (System/getProperty "user.dir")]
          (try
            (System/setProperty "user.dir" child-dir)
            (binding [*out* out *err* err]
              (with-redefs [cli/exit (fn [code] (reset! exit-code code))]
                (cli/-main "--format" "edn" "work-on" "--task-id" "1")))
            (finally
              (System/setProperty "user.dir" original-dir)))
          (let [result {:exit @exit-code
                        :out (str out)
                        :err (str err)}]
            (is (= 0 (:exit result))
                (str "work-on failed: " (:err result)))
            (let [parsed (read-string (:out result))]
              (is (= 1 (:task-id parsed)))
              (is (= "Child dir task" (:title parsed)))
              (is (some? (:execution-state-file parsed))))))

        (testing "writes execution state to child directory"
          (let [state-file (io/file child-dir ".mcp-tasks-current.edn")]
            (is (.exists state-file)
                "Execution state should be in child directory")
            (let [state (read-string (slurp state-file))]
              (is (= 1 (:task-id state))))))))))
