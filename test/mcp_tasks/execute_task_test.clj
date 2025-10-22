(ns mcp-tasks.execute-task-test
  "Integration tests for execute-task prompt functionality."
  (:require
    [babashka.fs :as fs]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-clj.in-memory-transport.shared :as shared]
    [mcp-clj.mcp-client.core :as mcp-client]
    [mcp-clj.mcp-server.core :as mcp-server]
    [mcp-tasks.config :as config]
    [mcp-tasks.main :as main]))

(def test-project-dir (.getAbsolutePath (io/file "test-resources/execute-task-test")))

(defn- cleanup-test-project
  []
  (let [dir (io/file test-project-dir)]
    (when (fs/exists? dir)
      (fs/delete-tree dir))))

(defn- setup-test-project
  []
  (cleanup-test-project)
  (let [mcp-tasks-dir (io/file test-project-dir ".mcp-tasks")
        prompts-dir (io/file mcp-tasks-dir "prompts")]
    (.mkdirs prompts-dir)
    ;; Create category prompt files so resources are available
    (spit (io/file prompts-dir "simple.md")
          "---\ndescription: Execute simple tasks with basic workflow\n---\n\n- Analyze the task\n- Implement solution\n- Test")
    (spit (io/file prompts-dir "medium.md")
          "---\ndescription: Execute medium complexity tasks\n---\n\n- Deep analysis\n- Design approach\n- Implementation\n- Testing")))

(defn- with-test-project
  [f]
  (setup-test-project)
  (try
    (f)
    (finally
      (cleanup-test-project))))

(use-fixtures :each with-test-project)

(defn- load-test-config
  []
  (let [{:keys [raw-config config-dir]} (config/read-config test-project-dir)
        resolved-config (config/resolve-config config-dir raw-config)]
    (config/validate-startup config-dir resolved-config)
    resolved-config))

(defn- create-test-server-and-client
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
    (mcp-client/wait-for-ready client 5000)
    {:server server
     :client client}))

(deftest ^:integ execute-task-prompt-available-test
  ;; Test that execute-task prompt is exposed via MCP with correct structure.
  (testing "execute-task prompt availability"
    (let [{:keys [server client]} (create-test-server-and-client)]
      (try
        (testing "prompt is listed"
          (let [prompts-response @(mcp-client/list-prompts client)
                prompts (:prompts prompts-response)
                prompt-names (set (map :name prompts))]
            (is (contains? prompt-names "execute-task")
                "execute-task prompt should be in prompts list")))

        (testing "prompt has correct structure"
          (let [prompt-response @(mcp-client/get-prompt client "execute-task" {})
                prompt (:messages prompt-response)]
            (is (not (:isError prompt-response)))
            (is (vector? prompt))
            (is (pos? (count prompt)))
            (let [first-message (first prompt)]
              (is (= "user" (:role first-message)))
              (is (contains? first-message :content)))))

        (testing "prompt has description"
          (let [prompts-response @(mcp-client/list-prompts client)
                prompts (:prompts prompts-response)
                execute-task-prompt (first (filter #(= "execute-task" (:name %)) prompts))]
            (is (string? (:description execute-task-prompt)))
            (is (pos? (count (:description execute-task-prompt))))))

        (testing "prompt content includes expected instructions"
          (let [prompt-response @(mcp-client/get-prompt client "execute-task" {})
                content (-> prompt-response :messages first :content :text)]
            (is (str/includes? content "Argument Parsing"))
            (is (str/includes? content "Find the Task"))
            (is (str/includes? content "Retrieve Category Instructions"))
            (is (str/includes? content "Execute the Task"))
            (is (str/includes? content "Mark Task Complete"))
            (is (str/includes? content "select-tasks"))
            (is (str/includes? content "prompt://category-"))
            (is (str/includes? content "complete-task"))))

        (finally
          (mcp-client/close! client)
          ((:stop server)))))))

(deftest ^:integ execute-task-prompt-arguments-test
  ;; Test that execute-task prompt has proper arguments structure.
  (testing "execute-task prompt arguments"
    (let [{:keys [server client]} (create-test-server-and-client)]
      (try
        (testing "has arguments field"
          (let [prompts-response @(mcp-client/list-prompts client)
                prompts (:prompts prompts-response)
                execute-task-prompt (first (filter #(= "execute-task" (:name %)) prompts))]
            (is (contains? execute-task-prompt :arguments))
            (is (vector? (:arguments execute-task-prompt)))))

        (testing "arguments have correct structure"
          (let [prompts-response @(mcp-client/list-prompts client)
                prompts (:prompts prompts-response)
                execute-task-prompt (first (filter #(= "execute-task" (:name %)) prompts))
                args (:arguments execute-task-prompt)]
            (is (pos? (count args)))
            (doseq [arg args]
              (is (contains? arg :name))
              (is (contains? arg :description))
              (is (contains? arg :required))
              (is (string? (:name arg)))
              (is (string? (:description arg)))
              (is (boolean? (:required arg))))))

        (finally
          (mcp-client/close! client)
          ((:stop server)))))))

(deftest ^:integ category-resources-for-execute-task-test
  ;; Test that category resources are accessible and have correct content
  ;; for use by execute-task prompt.
  (testing "category resources for execute-task"
    (let [{:keys [server client]} (create-test-server-and-client)]
      (try
        (testing "category resources are listed"
          (let [resources-response @(mcp-client/list-resources client)
                resources (:resources resources-response)
                category-resources (filter #(str/includes? (:uri %) "prompt://category-")
                                           resources)]
            (is (>= (count category-resources) 2)
                "Should have at least simple and medium category resources")
            (let [resource-uris (set (map :uri category-resources))]
              (is (contains? resource-uris "prompt://category-simple"))
              (is (contains? resource-uris "prompt://category-medium")))))

        (testing "category resources can be read"
          (let [read-response @(mcp-client/read-resource client "prompt://category-simple")]
            (is (not (:isError read-response)))
            (is (vector? (:contents read-response)))
            (is (pos? (count (:contents read-response))))))

        (testing "category resources provide instructions with frontmatter"
          (let [read-response @(mcp-client/read-resource client "prompt://category-simple")
                text (-> read-response :contents first :text)]
            (is (str/includes? text "---")
                "Category resource should contain frontmatter delimiters")
            (is (str/includes? text "description:")
                "Category resource should contain frontmatter fields")
            (is (str/includes? text "Analyze the task")
                "Category resource should contain execution instructions")))

        (testing "category resources have correct mime type"
          (let [resources-response @(mcp-client/list-resources client)
                resources (:resources resources-response)
                simple-resource (first (filter #(= (:uri %) "prompt://category-simple")
                                               resources))]
            (is (= "text/markdown" (:mimeType simple-resource)))))

        (testing "each category resource has distinct content"
          (let [simple-response @(mcp-client/read-resource client "prompt://category-simple")
                simple-text (-> simple-response :contents first :text)
                medium-response @(mcp-client/read-resource client "prompt://category-medium")
                medium-text (-> medium-response :contents first :text)]
            (is (not= simple-text medium-text))
            (is (str/includes? simple-text "Analyze the task"))
            (is (str/includes? medium-text "Deep analysis"))))

        (finally
          (mcp-client/close! client)
          ((:stop server)))))))
