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
    [mcp-tasks.main :as main]
    [mcp-tasks.tasks-file :as tasks-file]))

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

(defn- create-test-server-and-client
  "Create server and client connected via in-memory transport.

  Uses main/create-server-config to ensure tests use the same server
  configuration as production code for better test fidelity.

  Waits for client to be ready before returning."
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
    ;; Wait for client to be ready (up to 5 seconds)
    (mcp-client/wait-for-ready client 5000)
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

(deftest ^:integ resources-available-test
  ;; Test that resources are advertised and can be read.
  ;; Resource content details are covered in resources unit tests.
  (testing "resources availability"
    (write-config-file "{:use-git? true}")
    (.mkdirs (io/file test-project-dir ".mcp-tasks" ".git"))

    (let [{:keys [server client]} (create-test-server-and-client)]
      (try
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

;; next-story-task tool removed - functionality replaced by next-task with parent-id filter

;; complete-story-task tool removed - use regular complete-task tool with EDN storage

(deftest ^:integ add-task-with-story-name-test
  ;; Tests that add-task with story-name creates child tasks with parent-id in EDN storage.
  (testing "add-task tool with story-name"
    (testing "adds child task with parent-id to tasks.ednl"
      (write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          ;; Create a story task in tasks.ednl
          (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                story-task {:id 1
                            :title "new-story"
                            :description "Story description"
                            :design ""
                            :category "large"
                            :status :open
                            :type :story
                            :meta {}
                            :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story-task]))

          (let [result @(mcp-client/call-tool client
                                              "add-task"
                                              {:category "simple"
                                               :task-text "First task for story"
                                               :story-name "new-story"})]
            (is (not (:isError result)))
            (is (re-find #"Task added" (-> result :content first :text)))

            ;; Verify task was added to tasks.ednl with parent-id
            (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                  tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                  child-tasks (filter #(= (:parent-id %) 1) tasks)]
              (is (= 2 (count tasks)) "Should have story + child task")
              (is (= 1 (count child-tasks)) "Should have one child task")
              (let [child-task (first child-tasks)]
                (is (= "First task for story" (:title child-task)))
                (is (= "simple" (:category child-task)))
                (is (= :task (:type child-task)))
                (is (= :open (:status child-task)))
                (is (= 1 (:parent-id child-task))))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ add-task-with-story-name-append-test
  ;; Tests that add-task with story-name appends child tasks in correct order.
  (testing "add-task tool with story-name"
    (testing "appends child tasks to tasks.ednl"
      (write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          ;; Create a story task with one existing child in tasks.ednl
          (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                story-task {:id 1
                            :title "existing-story"
                            :description ""
                            :design ""
                            :category "large"
                            :status :open
                            :type :story
                            :meta {}
                            :relations []}
                first-child {:id 2
                             :parent-id 1
                             :title "First task"
                             :description ""
                             :design ""
                             :category "simple"
                             :status :open
                             :type :task
                             :meta {}
                             :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story-task first-child]))

          (let [result @(mcp-client/call-tool client
                                              "add-task"
                                              {:category "medium"
                                               :task-text "Second task"
                                               :story-name "existing-story"})]
            (is (not (:isError result)))

            ;; Verify second task was appended
            (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                  tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                  child-tasks (filter #(= (:parent-id %) 1) tasks)]
              (is (= 3 (count tasks)) "Should have story + 2 child tasks")
              (is (= 2 (count child-tasks)) "Should have two child tasks")
              (let [first-task (first child-tasks)
                    second-task (second child-tasks)]
                (is (= "First task" (:title first-task)))
                (is (= "simple" (:category first-task)))
                (is (= "Second task" (:title second-task)))
                (is (= "medium" (:category second-task))))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ add-task-with-story-name-prepend-test
  ;; Tests that add-task with story-name and prepend flag prepends child tasks.
  (testing "add-task tool with story-name"
    (testing "prepends child tasks to tasks.ednl when prepend is true"
      (write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          ;; Create a story task with one existing child in tasks.ednl
          (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                story-task {:id 1
                            :title "prepend-story"
                            :description ""
                            :design ""
                            :category "large"
                            :status :open
                            :type :story
                            :meta {}
                            :relations []}
                existing-child {:id 2
                                :parent-id 1
                                :title "Existing task"
                                :description ""
                                :design ""
                                :category "simple"
                                :status :open
                                :type :task
                                :meta {}
                                :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story-task existing-child]))

          (let [result @(mcp-client/call-tool client
                                              "add-task"
                                              {:category "large"
                                               :task-text "New first task"
                                               :story-name "prepend-story"
                                               :prepend true})]
            (is (not (:isError result)))

            ;; Verify new task was prepended (appears first in file)
            (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                  tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))]
              (is (= 3 (count tasks)) "Should have story + 2 child tasks")
              ;; The prepended task should appear before the existing task in the file
              (let [task-titles (mapv :title tasks)]
                (is (= ["New first task" "prepend-story" "Existing task"] task-titles)))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ add-task-preserves-category-tasks-workflow-test
  ;; Tests that adding story tasks and category tasks both work in unified EDN storage.
  (testing "add-task tool"
    (testing "handles both story and category tasks in unified storage"
      (write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          ;; Create a story task in tasks.ednl
          (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                story-task {:id 1
                            :title "test-story"
                            :description ""
                            :design ""
                            :category "large"
                            :status :open
                            :type :story
                            :meta {}
                            :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story-task]))

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

            ;; Verify both tasks are in tasks.ednl
            (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                  tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                  story-children (filter #(= (:parent-id %) 1) tasks)
                  category-tasks (filter #(and (nil? (:parent-id %))
                                               (= (:type %) :task)) tasks)]
              (is (= 3 (count tasks)) "Should have story + story child + category task")
              (is (= 1 (count story-children)) "Should have one story child")
              (is (= 1 (count category-tasks)) "Should have one category task")
              (let [story-child (first story-children)
                    category-task (first category-tasks)]
                (is (= "Story task" (:title story-child)))
                (is (= "simple" (:category story-child)))
                (is (= 1 (:parent-id story-child)))
                (is (= "Category task" (:title category-task)))
                (is (= "simple" (:category category-task)))
                (is (nil? (:parent-id category-task))))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ add-task-story-validation-test
  ;; Tests that add-task returns error when story task doesn't exist in tasks.ednl.
  (testing "add-task tool with story-name"
    (testing "returns error when story task doesn't exist"
      (write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          (let [result @(mcp-client/call-tool client
                                              "add-task"
                                              {:category "simple"
                                               :task-text "Task for nonexistent"
                                               :story-name "nonexistent"})]
            (is (:isError result))
            (is (re-find #"Story not found"
                         (-> result :content first :text))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ update-task-tool-test
  ;; Tests that update-task tool can update task fields and persist changes to tasks.ednl.
  (testing "update-task tool"
    (testing "updates task fields and persists to tasks.ednl"
      (write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          ;; Create an initial task in tasks.ednl
          (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                initial-task {:id 1
                              :title "Original title"
                              :description "Original desc"
                              :design "Original design"
                              :category "simple"
                              :status :open
                              :type :task
                              :meta {}
                              :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [initial-task]))

          ;; Update the task using the tool
          (let [result @(mcp-client/call-tool client
                                              "update-task"
                                              {:task-id 1
                                               :title "Updated title"
                                               :description "Updated desc"
                                               :design "Updated design"})]
            (is (not (:isError result)))
            (is (re-find #"Task 1 updated"
                         (-> result :content first :text)))

            ;; Verify task was updated in tasks.ednl
            (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                  tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                  updated-task (first tasks)]
              (is (= 1 (count tasks)))
              (is (= "Updated title" (:title updated-task)))
              (is (= "Updated desc" (:description updated-task)))
              (is (= "Updated design" (:design updated-task)))
              ;; Other fields should remain unchanged
              (is (= :open (:status updated-task)))
              (is (= "simple" (:category updated-task)))
              (is (= :task (:type updated-task)))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "updates only specified fields"
      (write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          ;; Create an initial task
          (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                initial-task {:id 2
                              :title "Keep title"
                              :description "Change desc"
                              :design "Keep design"
                              :category "medium"
                              :status :open
                              :type :task
                              :meta {}
                              :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [initial-task]))

          ;; Update only description field
          (let [result @(mcp-client/call-tool client
                                              "update-task"
                                              {:task-id 2
                                               :description "New desc"})]
            (is (not (:isError result)))

            ;; Verify only description changed
            (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                  tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                  updated-task (first tasks)]
              (is (= "Keep title" (:title updated-task)))
              (is (= "New desc" (:description updated-task)))
              (is (= "Keep design" (:design updated-task)))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "returns error for non-existent task ID"
      (write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          (let [result @(mcp-client/call-tool client
                                              "update-task"
                                              {:task-id 999
                                               :title "New title"})]
            (is (:isError result))
            (is (re-find #"Task not found"
                         (-> result :content first :text))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))
