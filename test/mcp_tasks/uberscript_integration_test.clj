(ns mcp-tasks.uberscript-integration-test
  "Integration tests for the babashka uberscript executable.

  Tests run from a temp directory outside the project to validate standalone
  execution without access to project dependencies. This ensures the uberscript
  doesn't leak Malli or other dependencies that shouldn't be bundled."
  (:require
    [babashka.fs :as fs]
    [clojure.java.io :as io]
    [clojure.java.shell :as sh]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.tasks-file :as tasks-file]
    [mcp-tasks.test-helpers :as h]))

(def ^:dynamic *test-dir* nil)
(def ^:dynamic *uberscript-path* nil)

(defn- get-uberscript-path
  "Get absolute path to the uberscript executable."
  []
  (let [project-root (System/getProperty "user.dir")]
    (str project-root "/mcp-tasks")))

(defn- bb-available?
  "Check if bb (Babashka) is available on the system."
  []
  (try
    (let [result (sh/sh "bb" "--version")]
      (zero? (:exit result)))
    (catch java.io.IOException _
      false)))

(def ^:private uberscript-built? (atom false))

(defmacro with-uberscript-build
  "Ensures uberscript is built once before running tests WITHOUT Malli dependencies.
  Skips test body if bb is not available.

  The uberscript is intentionally built without USE_MALLI=true to validate
  standalone operation without Malli on the classpath."
  [& body]
  `(if (bb-available?)
     (do
       (when-not @uberscript-built?
         (println "Building uberscript for integration tests (without Malli)...")
         ;; Explicitly clear USE_MALLI env var to build without Malli dependencies
         (let [clean-env# (dissoc (into {} (System/getenv)) "USE_MALLI")
               result# (sh/sh "bb" "build-uberscript" :env clean-env#)]
           (when-not (zero? (:exit result#))
             (throw (ex-info "Failed to build uberscript"
                             {:exit (:exit result#)
                              :out (:out result#)
                              :err (:err result#)}))))
         (println "Uberscript built successfully")
         (reset! uberscript-built? true))
       ~@body)
     (println "Skipping uberscript integration test: bb (Babashka) not available")))

(defn- setup-test-dir
  "Set up test directory with .mcp-tasks structure and copy uberscript.
  Returns the path to the copied uberscript."
  [test-dir]
  (fs/create-dirs (io/file test-dir ".mcp-tasks"))
  (fs/create-dirs (io/file test-dir ".mcp-tasks/prompts"))
  ;; Create a simple category prompt for testing
  (spit (io/file test-dir ".mcp-tasks/prompts/simple.md")
        "---\ndescription: Simple tasks\n---\nSimple task execution")
  ;; Copy uberscript to temp dir
  (let [source-path (get-uberscript-path)
        dest-path (str test-dir "/mcp-tasks")]
    (fs/copy source-path dest-path {:replace-existing true})
    ;; Make executable
    (sh/sh "chmod" "+x" dest-path)
    dest-path))

(defmacro with-uberscript-test
  "Sets up isolated test environment with temp directory and uberscript copy.

  **Lifecycle:**
  1. Creates temporary directory with .mcp-tasks structure and category prompts
  2. Copies uberscript to temp dir and makes it executable
  3. Binds dynamic vars for use in test body
  4. Executes test body
  5. Automatically cleans up temp directory (even on exception)

  **Isolation:**
  Tests run in a temp directory OUTSIDE the project to validate standalone
  execution. This prevents dependency leakage - the uberscript must work
  without access to project dependencies like Malli. Running from the project
  directory would mask bugs where dependencies leak into the uberscript.

  **Bindings:**
  - *test-dir*: Absolute path to isolated temp directory
  - *uberscript-path*: Absolute path to copied uberscript executable

  **Cleanup:**
  The temp directory and all contents are automatically deleted via finally
  block, ensuring cleanup even when tests fail."
  [& body]
  `(let [test-dir# (str (fs/create-temp-dir {:prefix "mcp-tasks-uberscript-"}))]
     (try
       (let [uberscript-path# (setup-test-dir test-dir#)]
         (binding [*test-dir* test-dir#
                   *uberscript-path* uberscript-path#]
           (h/reset-tasks-state!)
           ~@body))
       (finally
         (fs/delete-tree test-dir#)))))

(defn- call-uberscript
  "Call the uberscript executable from temp dir capturing stdout, stderr, and exit code.
  Uses *uberscript-path* from temp dir and *test-dir* for working directory.
  Returns {:exit exit-code :out output-string :err error-string}"
  [& args]
  (let [clean-env (dissoc (into {} (System/getenv)) "USE_MALLI")
        result (apply sh/sh *uberscript-path* (concat args [:dir *test-dir* :env clean-env]))]
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
  ;; Runs from temp dir to validate standalone execution
  (with-uberscript-build
    (with-uberscript-test
      (testing "uberscript-build"
        (testing "executable exists and is executable"
          (let [file (io/file *uberscript-path*)]
            (is (.exists file) "Uberscript executable should exist")
            (is (.canExecute file) "Uberscript should be executable")))

        (testing "malli namespace is not included in uberscript"
          (let [content (slurp *uberscript-path*)
                ;; Look for malli namespace declarations
                has-malli-ns? (or (str/includes? content "(ns malli.")
                                  (str/includes? content "(ns malli.core")
                                  (str/includes? content "(ns malli.impl"))]
            (is (not has-malli-ns?)
                "Uberscript should not contain malli namespace declarations")))))))

(deftest ^:integration uberscript-cli-commands-test
  ;; Test that all CLI commands work through the uberscript from temp dir
  (with-uberscript-build
    (with-uberscript-test
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
            (is (= 2 (count completed)))))))))
