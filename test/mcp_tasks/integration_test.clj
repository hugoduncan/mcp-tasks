(ns mcp-tasks.integration-test
  "End-to-end integration tests for mcp-tasks server.

   Tests server startup and MCP protocol communication with different
   configurations using in-memory transport. File operation behavior is
   thoroughly tested in unit tests (tools_test.clj, prompts_test.clj)."
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
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
          (is (wait-for-client-ready client 5000) "Client should become ready within 5 seconds")
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
          (is (wait-for-client-ready client 5000) "Client should become ready within 5 seconds")
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
          (is (wait-for-client-ready client 5000) "Client should become ready within 5 seconds")
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
        (is (wait-for-client-ready client 5000) "Client should become ready within 5 seconds")
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
        (is (wait-for-client-ready client 5000) "Client should become ready within 5 seconds")
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

(deftest ^:integ resources-available-test
  ;; Test that resources are advertised and can be read.
  ;; Resource content details are covered in resources unit tests.
  (testing "resources availability"
    (write-config-file "{:use-git? true}")
    (.mkdirs (io/file test-project-dir ".mcp-tasks" ".git"))

    (let [{:keys [server client]} (create-test-server-and-client)]
      (try
        (is (wait-for-client-ready client 5000) "Client should become ready within 5 seconds")
        (testing "server advertises resource capabilities"
          (is (mcp-client/available-resources? client))
          (let [resources-response @(mcp-client/list-resources client)
                resources (:resources resources-response)]
            (is (map? resources-response))
            (is (vector? resources))
            (is (pos? (count resources)))

            (testing "all resources have required fields"
              (doseq [resource resources]
                (is (contains? resource :name))
                (is (contains? resource :uri))
                (is (contains? resource :mimeType))
                (is (string? (:name resource)))
                (is (string? (:uri resource)))
                (is (string? (:mimeType resource)))
                (is (= "text/markdown" (:mimeType resource)))
                (is (str/starts-with? (:uri resource) "prompt://"))))

            (testing "includes resources for all configured prompts"
              (let [resource-names (set (map :name resources))]
                ;; Check for some known prompts
                (is (contains? resource-names "next-simple"))
                (is (contains? resource-names "execute-story-task"))))))

        (testing "can read resource content"
          (let [resources-response @(mcp-client/list-resources client)
                resources (:resources resources-response)
                first-resource (first resources)
                uri (:uri first-resource)
                read-response @(mcp-client/read-resource client uri)
                contents (:contents read-response)]
            (is (not (:isError read-response)))
            (is (vector? contents))
            (is (pos? (count contents)))
            (let [content (first contents)]
              (is (= uri (:uri content)))
              (is (= "text/markdown" (:mimeType content)))
              (is (string? (:text content)))
              (is (pos? (count (:text content)))))))

        (testing "returns error for non-existent resource"
          (let [read-response @(mcp-client/read-resource client "prompt://nonexistent")]
            (is (:isError read-response))
            (is (re-find #"Resource not found"
                         (-> read-response :contents first :text)))))

        (finally
          (mcp-client/close! client)
          ((:stop server)))))))

(deftest ^:integ prompt-resources-content-test
  ;; Test comprehensive validation of prompt resource content, metadata, and formatting.
  ;; Validates YAML frontmatter structure and prompt message text consistency.
  (testing "prompt resources content validation"
    (write-config-file "{:use-git? true}")
    (.mkdirs (io/file test-project-dir ".mcp-tasks" ".git"))

    (let [{:keys [server client]} (create-test-server-and-client)]
      (try
        (is (wait-for-client-ready client 5000) "Client should become ready within 5 seconds")
        (testing "resource content includes YAML frontmatter with description"
          (let [read-response @(mcp-client/read-resource client "prompt://next-simple")
                text (-> read-response :contents first :text)]
            (is (not (:isError read-response)))
            (is (str/starts-with? text "---\n"))
            (is (str/includes? text "\n---\n"))
            (is (str/includes? text "description:"))
            (let [frontmatter-end (str/index-of text "\n---\n")
                  frontmatter (subs text 0 frontmatter-end)]
              (is (str/includes? frontmatter "simple")))))

        (testing "resource content includes prompt message text"
          (let [read-response @(mcp-client/read-resource client "prompt://next-simple")
                text (-> read-response :contents first :text)]
            (is (not (:isError read-response)))
            (is (str/includes? text "complete the next simple task"))
            (let [frontmatter-end (+ (str/index-of text "\n---\n") 5)
                  message-text (subs text frontmatter-end)]
              (is (pos? (count (str/trim message-text)))))))

        (testing "story prompt includes argument-hint in frontmatter"
          (let [read-response @(mcp-client/read-resource client "prompt://execute-story-task")
                text (-> read-response :contents first :text)]
            (is (not (:isError read-response)))
            (is (str/starts-with? text "---\n"))
            (is (str/includes? text "argument-hint:"))
            (is (str/includes? text "<story-name>"))
            (is (str/includes? text "Execute the next incomplete task"))))

        (testing "multiple prompts return distinct content"
          (let [simple-response @(mcp-client/read-resource client "prompt://next-simple")
                simple-text (-> simple-response :contents first :text)
                story-response @(mcp-client/read-resource client "prompt://execute-story-task")
                story-text (-> story-response :contents first :text)]
            (is (not (:isError simple-response)))
            (is (not (:isError story-response)))
            (is (not= simple-text story-text))
            (is (str/includes? simple-text "simple"))
            (is (str/includes? story-text "story"))))

        (testing "all listed resources can be read successfully"
          (let [resources-response @(mcp-client/list-resources client)
                resources (:resources resources-response)]
            (doseq [resource resources]
              (let [uri (:uri resource)
                    read-response @(mcp-client/read-resource client uri)
                    text (-> read-response :contents first :text)]
                (is (not (:isError read-response))
                    (str "Should read resource successfully: " uri))
                (is (str/starts-with? text "---\n")
                    (str "Resource should have YAML frontmatter: " uri))
                (is (str/includes? text "\n---\n")
                    (str "Resource should have complete frontmatter: " uri))
                (is (str/includes? text "description:")
                    (str "Resource should have description metadata: " uri))))))

        (finally
          (mcp-client/close! client)
          ((:stop server)))))))

(deftest ^:integ next-story-task-tool-test
  ;; Test the next-story-task tool integration.
  (testing "next-story-task tool"
    (write-config-file "{:use-git? false}")

    (let [{:keys [server client]} (create-test-server-and-client)]
      (try
        (is (wait-for-client-ready client 5000) "Client should become ready within 5 seconds")
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
        (is (wait-for-client-ready client 5000) "Client should become ready within 5 seconds")
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
              (is (wait-for-client-ready client 5000) "Client should become ready within 5 seconds")
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

(deftest ^:integ add-task-to-new-story-tasks-file-test
  ;; Tests that add-task creates a new story-tasks file with header when adding
  ;; first task to a story.
  (testing "add-task tool with story-name"
    (testing "creates new story-tasks file with header"
      (write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          (is (wait-for-client-ready client 5000) "Client should become ready within 5 seconds")
          (let [story-dir (io/file test-project-dir ".mcp-tasks" "story" "stories")
                story-tasks-dir (io/file test-project-dir ".mcp-tasks" "story" "story-tasks")]
            (.mkdirs story-dir)
            (.mkdirs story-tasks-dir)
            (spit (io/file story-dir "new-story.md") "# New Story\n\nDescription"))

          (let [result @(mcp-client/call-tool client
                                              "add-task"
                                              {:category "simple"
                                               :task-text "First task for story"
                                               :story-name "new-story"})]
            (is (not (:isError result)))
            (is (re-find #"Task added" (-> result :content first :text)))

            (let [story-tasks-file (io/file test-project-dir ".mcp-tasks" "story" "story-tasks" "new-story-tasks.md")]
              (is (.exists story-tasks-file))
              (let [content (slurp story-tasks-file)]
                (is (re-find #"# Tasks for new-story Story" content))
                (is (re-find #"- \[ \] First task for story" content))
                (is (re-find #"CATEGORY: simple" content)))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ add-task-to-existing-story-tasks-file-append-test
  ;; Tests that add-task appends to existing story-tasks file.
  (testing "add-task tool with story-name"
    (testing "appends to existing story-tasks file"
      (write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          (is (wait-for-client-ready client 5000) "Client should become ready within 5 seconds")
          (let [story-dir (io/file test-project-dir ".mcp-tasks" "story" "stories")
                story-tasks-dir (io/file test-project-dir ".mcp-tasks" "story" "story-tasks")]
            (.mkdirs story-dir)
            (.mkdirs story-tasks-dir)
            (spit (io/file story-dir "existing-story.md") "# Existing Story")
            (spit (io/file story-tasks-dir "existing-story-tasks.md")
                  "# Tasks for existing-story Story\n- [ ] First task\nCATEGORY: simple"))

          (let [result @(mcp-client/call-tool client
                                              "add-task"
                                              {:category "medium"
                                               :task-text "Second task"
                                               :story-name "existing-story"})]
            (is (not (:isError result)))

            (let [story-tasks-file (io/file test-project-dir ".mcp-tasks" "story" "story-tasks" "existing-story-tasks.md")
                  content (slurp story-tasks-file)]
              (is (re-find #"- \[ \] First task" content))
              (is (re-find #"- \[ \] Second task" content))
              (is (re-find #"CATEGORY: medium" content))
              (is (re-find #"CATEGORY: simple\n\n- \[ \] Second task" content))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ add-task-to-existing-story-tasks-file-prepend-test
  ;; Tests that add-task prepends to existing story-tasks file when prepend is true.
  (testing "add-task tool with story-name"
    (testing "prepends to existing story-tasks file when prepend is true"
      (write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          (is (wait-for-client-ready client 5000) "Client should become ready within 5 seconds")
          (let [story-dir (io/file test-project-dir ".mcp-tasks" "story" "stories")
                story-tasks-dir (io/file test-project-dir ".mcp-tasks" "story" "story-tasks")]
            (.mkdirs story-dir)
            (.mkdirs story-tasks-dir)
            (spit (io/file story-dir "prepend-story.md") "# Prepend Story")
            (spit (io/file story-tasks-dir "prepend-story-tasks.md")
                  "# Tasks for prepend-story Story\n- [ ] Existing task\nCATEGORY: simple"))

          (let [result @(mcp-client/call-tool client
                                              "add-task"
                                              {:category "large"
                                               :task-text "New first task"
                                               :story-name "prepend-story"
                                               :prepend true})]
            (is (not (:isError result)))

            (let [story-tasks-file (io/file test-project-dir ".mcp-tasks" "story" "story-tasks" "prepend-story-tasks.md")
                  content (slurp story-tasks-file)
                  lines (str/split-lines content)]
              (is (re-find #"- \[ \] New first task" (first (filter #(re-find #"- \[ \]" %) lines))))
              (is (re-find #"CATEGORY: large" content))
              (is (re-find #"CATEGORY: simple" content))
              (is (re-find #"- \[ \] New first task\nCATEGORY: large\n\n" content))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ add-task-preserves-category-tasks-workflow-test
  ;; Tests that adding story tasks doesn't affect regular category task workflow.
  (testing "add-task tool"
    (testing "preserves category tasks workflow when story tasks are used"
      (write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          (is (wait-for-client-ready client 5000) "Client should become ready within 5 seconds")
          (let [story-dir (io/file test-project-dir ".mcp-tasks" "story" "stories")]
            (.mkdirs story-dir)
            (spit (io/file story-dir "test-story.md") "# Test Story"))

          (let [story-result @(mcp-client/call-tool client
                                                    "add-task"
                                                    {:category "simple"
                                                     :task-text "Story task"
                                                     :story-name "test-story"})
                category-result @(mcp-client/call-tool client
                                                       "add-task"
                                                       {:category "simple"
                                                        :task-text "Category task"})]
            (is (not (:isError story-result)))
            (is (not (:isError category-result)))

            (let [story-tasks-file (io/file test-project-dir ".mcp-tasks" "story" "story-tasks" "test-story-tasks.md")
                  category-tasks-file (io/file test-project-dir ".mcp-tasks" "tasks" "simple.md")]
              (is (.exists story-tasks-file))
              (is (.exists category-tasks-file))

              (let [story-content (slurp story-tasks-file)
                    category-content (slurp category-tasks-file)]
                (is (re-find #"Story task" story-content))
                (is (re-find #"CATEGORY: simple" story-content))
                (is (re-find #"Category task" category-content))
                (is (not (re-find #"CATEGORY:" category-content))))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ add-task-story-file-validation-test
  ;; Tests that add-task returns error when story file doesn't exist.
  (testing "add-task tool with story-name"
    (testing "returns error when story file doesn't exist"
      (write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          (is (wait-for-client-ready client 5000) "Client should become ready within 5 seconds")
          (let [result @(mcp-client/call-tool client
                                              "add-task"
                                              {:category "simple"
                                               :task-text "Task for nonexistent"
                                               :story-name "nonexistent"})]
            (is (:isError result))
            (is (re-find #"Story does not exist"
                         (-> result :content first :text))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))
