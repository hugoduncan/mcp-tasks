(ns mcp-tasks.uberscript-integration-test
  "Integration tests for the babashka uberscript executable.

  Tests that the built uberscript works correctly without malli dependency."
  (:require
    [babashka.fs :as fs]
    [clojure.java.io :as io]
    [clojure.java.shell :as sh]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-tasks.tasks-file :as tasks-file]
    [mcp-tasks.test-helpers :as h]))

(def ^:dynamic *test-dir* nil)

(defn- get-uberscript-path
  "Get absolute path to the uberscript executable."
  []
  (let [project-root (System/getProperty "user.dir")]
    (str project-root "/mcp-tasks")))

(defn- build-uberscript-once
  "Build the uberscript once before all tests."
  [f]
  (println "Building uberscript for integration tests...")
  (let [result (sh/sh "bb" "build-uberscript")]
    (when-not (zero? (:exit result))
      (throw (ex-info "Failed to build uberscript"
                      {:exit (:exit result)
                       :out (:out result)
                       :err (:err result)}))))
  (println "Uberscript built successfully")
  (f))

(defn- setup-test-dir
  [test-dir]
  (fs/create-dirs (io/file test-dir ".mcp-tasks"))
  (fs/create-dirs (io/file test-dir ".mcp-tasks/prompts"))
  ;; Create a simple category prompt for testing
  (spit (io/file test-dir ".mcp-tasks/prompts/simple.md")
        "---\ndescription: Simple tasks\n---\nSimple task execution"))

(defn- uberscript-test-fixture
  "Test fixture that sets up test directory for each test."
  [f]
  (let [test-dir (str (fs/create-temp-dir {:prefix "mcp-tasks-uberscript-"}))]
    (try
      (setup-test-dir test-dir)
      (binding [*test-dir* test-dir]
        (h/reset-tasks-state!)
        (f))
      (finally
        (fs/delete-tree test-dir)))))

(use-fixtures :once build-uberscript-once)
(use-fixtures :each uberscript-test-fixture)

(defn- call-uberscript
  "Call the uberscript executable capturing stdout, stderr, and exit code.
  Changes working directory to *test-dir* for config discovery.
  Returns {:exit exit-code :out output-string :err error-string}"
  [& args]
  (let [result (apply sh/sh (get-uberscript-path) (concat args [:dir *test-dir*]))]
    {:exit (:exit result)
     :out (:out result)
     :err (:err result)}))

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

(deftest ^:integration uberscript-build-test
  ;; Test that the uberscript was built correctly and does not contain malli
  (testing "uberscript-build"
    (testing "executable exists and is executable"
      (let [file (io/file (get-uberscript-path))]
        (is (.exists file) "Uberscript executable should exist")
        (is (.canExecute file) "Uberscript should be executable")))

    (testing "malli namespace is not included in uberscript"
      (let [content (slurp (get-uberscript-path))
            ;; Look for malli namespace declarations
            has-malli-ns? (or (str/includes? content "(ns malli.")
                              (str/includes? content "(ns malli.core")
                              (str/includes? content "(ns malli.impl"))]
        (is (not has-malli-ns?)
            "Uberscript should not contain malli namespace declarations")))))

(deftest ^:integration uberscript-cli-commands-test
  ;; Test that all CLI commands work through the uberscript
  (testing "uberscript-cli-commands"
    (testing "can add a task"
      (let [result (call-uberscript
                     "--format" "edn"
                     "add"
                     "--category" "simple"
                     "--title" "Test task"
                     "--description" "A test task")]
        (is (= 0 (:exit result))
            (str "Add command should succeed. stderr: " (:err result)))
        (is (str/includes? (:out result) ":id 1"))
        (is (str/blank? (:err result))
            "Should not have errors when adding task")
        (is (not (str/includes? (:err result) "malli"))
            "Should not have malli-related errors")))

    (testing "can list tasks"
      (let [result (call-uberscript
                     "--format" "edn"
                     "list")]
        (is (= 0 (:exit result))
            (str "List command should succeed. stderr: " (:err result)))
        (is (str/includes? (:out result) "Test task"))
        (is (not (str/includes? (:err result) "malli"))
            "Should not have malli-related errors")))

    (testing "can show task by ID"
      (let [result (call-uberscript
                     "--format" "edn"
                     "show"
                     "--task-id" "1")]
        (is (= 0 (:exit result))
            (str "Show command should succeed. stderr: " (:err result)))
        (let [parsed (read-string (:out result))]
          (is (= 1 (-> parsed :task :id)))
          (is (= "Test task" (-> parsed :task :title))))
        (is (not (str/includes? (:err result) "malli"))
            "Should not have malli-related errors")))

    (testing "can update task"
      (let [result (call-uberscript
                     "--format" "edn"
                     "update"
                     "--task-id" "1"
                     "--status" "in-progress")]
        (is (= 0 (:exit result))
            (str "Update command should succeed. stderr: " (:err result)))
        (let [parsed (read-string (:out result))]
          (is (= "in-progress" (:status (:task parsed)))))
        (is (not (str/includes? (:err result) "malli"))
            "Should not have malli-related errors")))

    (testing "can complete task"
      (let [result (call-uberscript
                     "--format" "edn"
                     "complete"
                     "--task-id" "1"
                     "--completion-comment" "Done")]
        (is (= 0 (:exit result))
            (str "Complete command should succeed. stderr: " (:err result)))
        (let [parsed (read-string (:out result))]
          (is (= "closed" (:status (:task parsed))))
          (is (str/includes? (:description (:task parsed)) "Done")))
        (is (not (str/includes? (:err result) "malli"))
            "Should not have malli-related errors")))

    (testing "task moved to complete file"
      (let [tasks (read-tasks-file)
            completed (read-complete-file)]
        (is (empty? tasks))
        (is (= 1 (count completed)))
        (is (= "Test task" (:title (first completed))))))

    (testing "can add another task for delete test"
      (let [result (call-uberscript
                     "--format" "edn"
                     "add"
                     "--category" "simple"
                     "--title" "Delete me")]
        (is (= 0 (:exit result))
            (str "Add command should succeed. stderr: " (:err result)))))

    (testing "can delete task"
      (let [result (call-uberscript
                     "--format" "edn"
                     "delete"
                     "--task-id" "2")]
        (is (= 0 (:exit result))
            (str "Delete command should succeed. stderr: " (:err result)))
        (let [parsed (read-string (:out result))]
          (is (= "deleted" (:status (:deleted parsed)))))
        (is (not (str/includes? (:err result) "malli"))
            "Should not have malli-related errors")))

    (testing "deleted task moved to complete file"
      (let [tasks (read-tasks-file)
            completed (read-complete-file)]
        (is (empty? tasks))
        (is (= 2 (count completed)))))))
