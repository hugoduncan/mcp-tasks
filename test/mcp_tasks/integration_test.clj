(ns mcp-tasks.integration-test
  "End-to-end integration tests for mcp-tasks server.

   Tests server startup and MCP protocol communication with different
   configurations using Java SDK client. File operation behavior is
   thoroughly tested in unit tests (tools_test.clj, prompts_test.clj)."
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-clj.java-sdk.interop :as java-sdk])
  (:import
    (java.lang
      AutoCloseable)))

(def test-project-dir (.getAbsolutePath (io/file "test-resources/integration-test")))

(defn- cleanup-test-project
  []
  (let [dir (io/file test-project-dir)]
    (when (.exists dir)
      (doseq [file (reverse (file-seq dir))]
        (.delete file)))))

(defn- setup-test-project
  []
  (cleanup-test-project)
  (.mkdirs (io/file test-project-dir ".mcp-tasks")))

(defn- write-config-file
  [content]
  (when content
    (spit (io/file test-project-dir ".mcp-tasks.edn") content)))

(defn- with-test-project
  [f]
  (setup-test-project)
  (try
    (f)
    (finally
      (cleanup-test-project))))

(use-fixtures :each with-test-project)

(defn- create-mcp-tasks-client
  "Create Java SDK client connected to mcp-tasks server subprocess"
  ^AutoCloseable []
  (let [transport (java-sdk/create-stdio-client-transport
                    {:command "clojure"
                     :args ["-M:run" "--config-path" test-project-dir]})
        client (java-sdk/create-java-client
                 {:transport transport
                  :async? false})]
    client))

(deftest ^:integ server-startup-with-no-config-test
  ;; Test that server starts successfully with no config file,
  ;; auto-detecting git mode based on .mcp-tasks/.git presence.
  (testing "server startup with no config"
    (testing "starts without git repo (auto-detects git mode off)"
      (with-open [client (create-mcp-tasks-client)]
        (let [result (java-sdk/initialize-client client)]
          (is (some? result))
          (is (contains? result :capabilities))
          (is (contains? (:capabilities result) :tools))
          (is (contains? (:capabilities result) :prompts)))))

    (testing "starts with git repo (auto-detects git mode on)"
      (.mkdirs (io/file test-project-dir ".mcp-tasks" ".git"))
      (with-open [client (create-mcp-tasks-client)]
        (let [result (java-sdk/initialize-client client)]
          (is (some? result))
          (is (contains? result :capabilities))
          (is (contains? (:capabilities result) :tools))
          (is (contains? (:capabilities result) :prompts)))))))

(deftest ^:integ server-startup-with-explicit-git-mode-test
  ;; Test that server starts successfully with explicit git mode config.
  (testing "server startup with explicit git mode enabled"
    (testing "starts successfully when git repo exists"
      (write-config-file "{:use-git? true}")
      (.mkdirs (io/file test-project-dir ".mcp-tasks" ".git"))
      (with-open [client (create-mcp-tasks-client)]
        (let [result (java-sdk/initialize-client client)]
          (is (some? result))
          (is (contains? result :capabilities))
          (is (contains? (:capabilities result) :tools))
          (is (contains? (:capabilities result) :prompts)))))))

(deftest ^:integ server-startup-with-explicit-non-git-mode-test
  ;; Test that server starts successfully with explicit non-git mode config.
  (testing "server startup with explicit git mode disabled"
    (testing "starts successfully without git repo"
      (write-config-file "{:use-git? false}")
      (with-open [client (create-mcp-tasks-client)]
        (let [result (java-sdk/initialize-client client)]
          (is (some? result))
          (is (contains? result :capabilities))
          (is (contains? (:capabilities result) :tools))
          (is (contains? (:capabilities result) :prompts)))))

    (testing "starts successfully even when git repo exists"
      (write-config-file "{:use-git? false}")
      (.mkdirs (io/file test-project-dir ".mcp-tasks" ".git"))
      (with-open [client (create-mcp-tasks-client)]
        (let [result (java-sdk/initialize-client client)]
          (is (some? result))
          (is (contains? result :capabilities))
          (is (contains? (:capabilities result) :tools))
          (is (contains? (:capabilities result) :prompts)))))))

(deftest ^:integ tools-available-test
  ;; Test that all expected tools are advertised and callable.
  ;; Tool behavior details are covered in tools_test.clj unit tests.
  (testing "tools availability"
    (write-config-file "{:use-git? false}")

    (with-open [client (create-mcp-tasks-client)]
      (java-sdk/initialize-client client)

      (testing "lists expected tools"
        (let [tools-response @(java-sdk/list-tools client)]
          (is (map? tools-response))
          (is (contains? tools-response :tools))
          (is (sequential? (:tools tools-response)))

          (let [tool-names (set (map :name (:tools tools-response)))]
            (is (contains? tool-names "complete-task"))
            (is (contains? tool-names "next-task"))
            (is (contains? tool-names "add-task"))

            (doseq [tool (:tools tools-response)]
              (is (contains? tool :name))
              (is (contains? tool :description))
              (is (string? (:name tool)))
              (is (string? (:description tool))))))))))

(deftest ^:integ prompts-available-test
  ;; Test that prompts are advertised with correct capabilities.
  ;; Prompt content details are covered in prompts_test.clj unit tests.
  (testing "prompts availability"
    (write-config-file "{:use-git? true}")
    (.mkdirs (io/file test-project-dir ".mcp-tasks" ".git"))

    (with-open [client (create-mcp-tasks-client)]
      (let [init-result (java-sdk/initialize-client client)]
        (testing "server advertises prompt capabilities"
          (is (some? init-result))
          (is (contains? init-result :capabilities))
          (is (contains? (:capabilities init-result) :prompts)))))))
