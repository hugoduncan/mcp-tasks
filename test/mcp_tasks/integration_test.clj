(ns mcp-tasks.integration-test
  "End-to-end integration tests for mcp-tasks server.

   Tests server startup and MCP protocol communication with different
   configurations using in-memory transport. File operation behavior is
   thoroughly tested in unit tests (tools_test.clj, prompts_test.clj)."
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-clj.in-memory-transport.shared :as shared]
    [mcp-clj.mcp-client.core :as mcp-client]
    [mcp-clj.mcp-server.core :as mcp-server]
    [mcp-tasks.config :as config]
    [mcp-tasks.main :as main]))

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
  (let [mcp-tasks-dir (io/file test-project-dir ".mcp-tasks")
        tasks-dir (io/file mcp-tasks-dir "tasks")]
    (.mkdirs tasks-dir)
    ;; Create a simple.md file so discover-categories finds at least one category
    (spit (io/file tasks-dir "simple.md") "- [ ] test task\n")))

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

(defn- load-test-config
  "Load config for test, using test-project-dir as the config path"
  []
  (let [raw-config (config/read-config test-project-dir)
        resolved-config (config/resolve-config test-project-dir (or raw-config {}))]
    (config/validate-startup test-project-dir resolved-config)
    resolved-config))

(defn- wait-for-client-ready
  "Wait for client to be ready, polling with exponential backoff.
  
  Returns true if client becomes ready within timeout, false otherwise.
  Max wait time is approximately timeout-ms."
  [client timeout-ms]
  (let [start-time (System/currentTimeMillis)
        max-wait timeout-ms]
    (loop [wait-time 10]
      (cond
        (mcp-client/client-ready? client)
        true

        (> (- (System/currentTimeMillis) start-time) max-wait)
        false

        :else
        (do
          (Thread/sleep wait-time)
          (recur (min (* wait-time 2) 100)))))))

(defn- create-test-server-and-client
  "Create server and client connected via in-memory transport.

  Uses main/create-server-config to ensure tests use the same server
  configuration as production code for better test fidelity."
  []
  (let [config (load-test-config)
        shared-transport (shared/create-shared-transport)
        server-config (main/create-server-config
                        config
                        {:type :in-memory :shared shared-transport})
        server (mcp-server/create-server server-config)
        client (mcp-client/create-client
                 {:transport {:type :in-memory
                              :shared shared-transport}
                  :client-info {:name "test-client" :version "1.0.0"}
                  :protocol-version "2025-06-18"})]
    {:server server
     :client client}))

(deftest ^:integ server-startup-with-no-config-test
  ;; Test that server starts successfully with no config file,
  ;; auto-detecting git mode based on .mcp-tasks/.git presence.
  (testing "server startup with no config"
    (testing "starts without git repo (auto-detects git mode off)"
      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          (is (mcp-client/client-ready? client))
          (is (mcp-client/available-tools? client))
          (is (mcp-client/available-prompts? client))
          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "starts with git repo (auto-detects git mode on)"
      (.mkdirs (io/file test-project-dir ".mcp-tasks" ".git"))
      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          (is (mcp-client/client-ready? client))
          (is (mcp-client/available-tools? client))
          (is (mcp-client/available-prompts? client))
          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ server-startup-with-explicit-git-mode-test
  ;; Test that server starts successfully with explicit git mode config.
  (testing "server startup with explicit git mode enabled"
    (testing "starts successfully when git repo exists"
      (write-config-file "{:use-git? true}")
      (.mkdirs (io/file test-project-dir ".mcp-tasks" ".git"))
      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          (is (mcp-client/client-ready? client))
          (is (mcp-client/available-tools? client))
          (is (mcp-client/available-prompts? client))
          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ server-startup-with-explicit-non-git-mode-test
  ;; Test that server starts successfully with explicit non-git mode config.
  (testing "server startup with explicit git mode disabled"
    (testing "starts successfully without git repo"
      (write-config-file "{:use-git? false}")
      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          (is (wait-for-client-ready client 5000) "Client should become ready within 5 seconds")
          (is (mcp-client/client-ready? client))
          (is (mcp-client/available-tools? client))
          (is (mcp-client/available-prompts? client))
          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "starts successfully even when git repo exists"
      (write-config-file "{:use-git? false}")
      (.mkdirs (io/file test-project-dir ".mcp-tasks" ".git"))
      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          (is (wait-for-client-ready client 5000) "Client should become ready within 5 seconds")
          (is (mcp-client/client-ready? client))
          (is (mcp-client/available-tools? client))
          (is (mcp-client/available-prompts? client))
          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ tools-available-test
  ;; Test that all expected tools are advertised and callable.
  ;; Tool behavior details are covered in tools_test.clj unit tests.
  (testing "tools availability"
    (write-config-file "{:use-git? false}")

    (let [{:keys [server client]} (create-test-server-and-client)]
      (try
        (testing "lists expected tools"
          (let [tools-response @(mcp-client/list-tools client)
                tools (:tools tools-response)]
            (is (map? tools-response))
            (is (vector? tools))

            (let [tool-names (set (map :name tools))]
              (is (contains? tool-names "complete-task"))
              (is (contains? tool-names "next-task"))
              (is (contains? tool-names "add-task"))
              (is (contains? tool-names "next-story-task"))

              (doseq [tool tools]
                (is (contains? tool :name))
                (is (contains? tool :description))
                (is (string? (:name tool)))
                (is (string? (:description tool)))))))
        (finally
          (mcp-client/close! client)
          ((:stop server)))))))

(deftest ^:integ prompts-available-test
  ;; Test that prompts are advertised with correct capabilities.
  ;; Prompt content details are covered in prompts_test.clj unit tests.
  (testing "prompts availability"
    (write-config-file "{:use-git? true}")
    (.mkdirs (io/file test-project-dir ".mcp-tasks" ".git"))

    (let [{:keys [server client]} (create-test-server-and-client)]
      (try
        (testing "server advertises prompt capabilities"
          (is (mcp-client/available-prompts? client))
          (let [prompts-response @(mcp-client/list-prompts client)
                prompts (:prompts prompts-response)]
            (is (map? prompts-response))
            (is (vector? prompts))
            (is (pos? (count prompts)))))
        (finally
          (mcp-client/close! client)
          ((:stop server)))))))

(deftest ^:integ next-story-task-tool-test
  ;; Test the next-story-task tool integration.
  (testing "next-story-task tool"
    (write-config-file "{:use-git? false}")

    (let [{:keys [server client]} (create-test-server-and-client)]
      (try
        (testing "returns task info when story tasks exist"
          (let [story-tasks-dir (io/file test-project-dir ".mcp-tasks" "story" "story-tasks")]
            (.mkdirs story-tasks-dir)
            (spit (io/file story-tasks-dir "test-story-tasks.md")
                  (str "# Test Story Tasks\n\n"
                       "- [ ] STORY: test-story - First incomplete task\n"
                       "  With more details\n"
                       "\n"
                       "CATEGORY: medium\n"
                       "\n"
                       "- [x] STORY: test-story - Completed task\n"
                       "\n"
                       "CATEGORY: simple\n")))

          (let [tools-response @(mcp-client/list-tools client)
                tools (:tools tools-response)
                tool-names (set (map :name tools))]
            (is (contains? tool-names "next-story-task"))

            (let [result @(mcp-client/call-tool client
                                                "next-story-task"
                                                {:story-name "test-story"})
                  text (-> result :content first :text)]
              (is (not (:isError result)))
              (is (string? text))
              (let [parsed (read-string text)]
                (is (map? parsed))
                (is (= "medium" (:category parsed)))
                (is (= 0 (:task-index parsed)))
                (is (string? (:task-text parsed)))
                (is (re-find #"First incomplete task" (:task-text parsed)))))))

        (testing "returns nil values when no incomplete tasks"
          (let [story-tasks-dir (io/file test-project-dir ".mcp-tasks" "story" "story-tasks")]
            (.mkdirs story-tasks-dir)
            (spit (io/file story-tasks-dir "complete-story-tasks.md")
                  (str "# Complete Story Tasks\n\n"
                       "- [x] STORY: complete-story - All done\n"
                       "\n"
                       "CATEGORY: simple\n")))

          (let [result @(mcp-client/call-tool client
                                              "next-story-task"
                                              {:story-name "complete-story"})
                text (-> result :content first :text)]
            (is (not (:isError result)))
            (let [parsed (read-string text)]
              (is (map? parsed))
              (is (nil? (:task-text parsed)))
              (is (nil? (:category parsed)))
              (is (nil? (:task-index parsed))))))

        (testing "returns error when story file not found"
          (let [result @(mcp-client/call-tool client
                                              "next-story-task"
                                              {:story-name "nonexistent"})]
            (is (:isError result))
            (is (re-find #"Story tasks file not found"
                         (-> result :content first :text)))))

        (finally
          (mcp-client/close! client)
          ((:stop server)))))))

(deftest ^:integ complete-story-task-tool-test
  ;; Test the complete-story-task tool integration.
  (testing "complete-story-task tool"
    (write-config-file "{:use-git? false}")

    (let [{:keys [server client]} (create-test-server-and-client)]
      (try
        (testing "completes task when story tasks exist"
          (let [story-tasks-dir (io/file test-project-dir ".mcp-tasks" "story" "story-tasks")]
            (.mkdirs story-tasks-dir)
            (spit (io/file story-tasks-dir "test-story-tasks.md")
                  (str "# Test Story Tasks\n\n"
                       "- [ ] STORY: test-story - First incomplete task\n"
                       "  With more details\n"
                       "\n"
                       "CATEGORY: medium\n"
                       "\n"
                       "- [ ] STORY: test-story - Second incomplete task\n"
                       "\n"
                       "CATEGORY: simple\n"))

            (let [tools-response @(mcp-client/list-tools client)
                  tools (:tools tools-response)
                  tool-names (set (map :name tools))]
              (is (contains? tool-names "complete-story-task"))

              (let [result @(mcp-client/call-tool client
                                                  "complete-story-task"
                                                  {:story-name "test-story"
                                                   :task-text "STORY: test-story - First"})]
                (is (not (:isError result)))
                (is (= 1 (count (:content result))))
                (is (re-find #"completed" (-> result :content first :text)))

                ;; Verify file was updated
                (let [updated-content (slurp (io/file story-tasks-dir "test-story-tasks.md"))]
                  (is (re-find #"- \[x\] STORY: test-story - First incomplete task" updated-content))
                  (is (re-find #"- \[ \] STORY: test-story - Second incomplete task" updated-content)))))))

        (testing "returns modified files when git mode enabled"
          (write-config-file "{:use-git? true}")
          (.mkdirs (io/file test-project-dir ".mcp-tasks" ".git"))
          (let [{:keys [server client]} (create-test-server-and-client)]
            (try
              (let [story-tasks-dir (io/file test-project-dir ".mcp-tasks" "story" "story-tasks")]
                (.mkdirs story-tasks-dir)
                (spit (io/file story-tasks-dir "git-test-tasks.md")
                      (str "- [ ] STORY: git-test - Task to complete\n\n"
                           "CATEGORY: simple\n")))

              (let [result @(mcp-client/call-tool client
                                                  "complete-story-task"
                                                  {:story-name "git-test"
                                                   :task-text "STORY: git-test - Task"})]
                (is (not (:isError result)))
                (is (= 2 (count (:content result))))
                (let [json-text (-> result :content second :text)
                      parsed (json/read-str json-text :key-fn keyword)]
                  (is (= ["story/story-tasks/git-test-tasks.md"]
                         (:modified-files parsed)))))
              (finally
                (mcp-client/close! client)
                ((:stop server))))))

        (testing "returns error when story file not found"
          (let [result @(mcp-client/call-tool client
                                              "complete-story-task"
                                              {:story-name "nonexistent"
                                               :task-text "some task"})]
            (is (:isError result))
            (is (re-find #"Story tasks file not found"
                         (-> result :content first :text)))))

        (testing "returns error when task text does not match"
          (let [story-tasks-dir (io/file test-project-dir ".mcp-tasks" "story" "story-tasks")]
            (.mkdirs story-tasks-dir)
            (spit (io/file story-tasks-dir "mismatch-tasks.md")
                  (str "- [ ] STORY: mismatch - Actual task\n\n"
                       "CATEGORY: simple\n")))

          (let [result @(mcp-client/call-tool client
                                              "complete-story-task"
                                              {:story-name "mismatch"
                                               :task-text "STORY: mismatch - Wrong task"})]
            (is (:isError result))
            (is (re-find #"does not match"
                         (-> result :content first :text)))))

        (finally
          (mcp-client/close! client)
          ((:stop server)))))))
