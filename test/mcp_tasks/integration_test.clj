(ns mcp-tasks.integration-test
  "End-to-end integration tests for mcp-tasks server.

   Tests server startup and MCP protocol communication with different
   configurations using in-memory transport. File operation behavior is
   thoroughly tested in unit tests (tools_test.clj, prompts_test.clj)."
  (:require
    [babashka.fs :as fs]
    [clojure.edn :as edn]
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
    (when (fs/exists? dir)
      (doseq [file (reverse (file-seq dir))]
        (fs/delete file)))))

(defn- setup-test-project
  []
  (cleanup-test-project)
  (let [mcp-tasks-dir (io/file test-project-dir ".mcp-tasks")
        tasks-dir (io/file mcp-tasks-dir "tasks")
        prompts-dir (io/file mcp-tasks-dir "prompts")]
    (.mkdirs tasks-dir)
    (.mkdirs prompts-dir)
    ;; Create a simple.md file in prompts dir with proper frontmatter
    (spit (io/file prompts-dir "simple.md")
          "---\ndescription: Test category for simple tasks\n---\nTest execution instructions\n")
    ;; Also create a simple.md in tasks for backward compatibility with other tests
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
              (is (contains? tool-names "select-tasks"))
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
                (is (or (str/starts-with? (:uri resource) "prompt://")
                        (str/starts-with? (:uri resource) "resource://")))))

            (testing "includes resources for all configured prompts"
              (let [resource-names (set (map :name resources))]
                ;; Check for some known prompts
                (is (contains? resource-names "next-simple"))
                (is (contains? resource-names "execute-story-task"))
                (is (contains? resource-names "execute-task"))))))

        (testing "can read resource content"
          (let [resources-response @(mcp-client/list-resources client)
                resources (:resources resources-response)
                ;; Find a prompt resource to test (not resource://)
                prompt-resource (first (filter #(str/starts-with? (:uri %) "prompt://") resources))
                uri (:uri prompt-resource)
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
            (is (str/includes? text "[story-specification]"))
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
                    text (-> read-response :contents first :text)
                    is-category-resource? (str/starts-with? uri "prompt://category-")
                    is-prompt-resource? (str/starts-with? uri "prompt://")]
                (is (not (:isError read-response))
                    (str "Should read resource successfully: " uri))
                ;; Only prompt resources have frontmatter (not category or resource:// resources)
                (when (and is-prompt-resource? (not is-category-resource?))
                  (is (str/starts-with? text "---\n")
                      (str "Resource should have YAML frontmatter: " uri))
                  (is (str/includes? text "\n---\n")
                      (str "Resource should have complete frontmatter: " uri))
                  (is (str/includes? text "description:")
                      (str "Resource should have description metadata: " uri)))))))

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

          (let [result @(mcp-client/call-tool
                          client
                          "add-task"
                          {:category "simple"
                           :title "First task for story"
                           :parent-id 1})]
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

          (let [result @(mcp-client/call-tool
                          client
                          "add-task"
                          {:category "medium"
                           :title "Second task"
                           :parent-id 1})]
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
                                               :title "New first task"
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

          (let [story-result @(mcp-client/call-tool
                                client
                                "add-task"
                                {:category "simple"
                                 :title "Story task"
                                 :parent-id 1})
                category-result @(mcp-client/call-tool
                                   client
                                   "add-task"
                                   {:category "simple"
                                    :title "Category task"})]
            (is (not (:isError story-result)))
            (is (not (:isError category-result)))

            ;; Verify both tasks are in tasks.ednl
            (let [tasks-file (io/file
                               test-project-dir
                               ".mcp-tasks"
                               "tasks.ednl")
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
                                               :title "Task for nonexistent"
                                               :parent-id 99999})]
            (is (:isError result))
            (is (re-find #"Parent story not found"
                         (-> result :content first :text))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ add-task-parent-id-validation-with-file-io-test
  ;; Tests parent-id validation with actual file I/O and state management.
  ;; Verifies that attempting to add a task with non-existent parent-id fails
  ;; properly without modifying the tasks file.
  (testing "add-task tool with parent-id validation"
    (testing "verifies file state is unchanged after validation error"
      (write-config-file "{:use-git? false}")

      ;; Create a tasks.ednl file with a real task
      (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
            initial-task-content "{:id 1 :title \"Existing task\" :description \"Test\" :design \"\" :category \"simple\" :status :open :type :task :meta {} :relations []}\n"]
        (spit tasks-file initial-task-content)

        (let [{:keys [server client]} (create-test-server-and-client)]
          (try
            ;; Verify initial file content
            (is (= initial-task-content (slurp tasks-file)))

            ;; Attempt to add task with non-existent parent-id
            (let [result @(mcp-client/call-tool client
                                                "add-task"
                                                {:category "simple"
                                                 :title "Child task"
                                                 :parent-id 99999})]
              ;; Verify error response
              (is (:isError result))
              (is (re-find #"Parent story not found"
                           (-> result :content first :text)))

              ;; Verify file was not modified
              (is (= initial-task-content (slurp tasks-file))))

            (finally
              (mcp-client/close! client)
              ((:stop server)))))))))

(deftest ^:integ update-task-tool-test
  ;; Tests update-task tool functionality including field updates, validation,
  ;; nil handling, and replacement semantics.
  (testing "update-task tool"
    (testing "field updates"
      (testing "updates multiple fields and persists to tasks.ednl"
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
              ((:stop server)))))))

    (testing "validation"
      (testing "validates status field"
        (write-config-file "{:use-git? false}")

        (let [{:keys [server client]} (create-test-server-and-client)]
          (try
            ;; Create initial task
            (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                  initial-task {:id 1
                                :title "Test task"
                                :description "Desc"
                                :design ""
                                :category "simple"
                                :status :open
                                :type :task
                                :meta {}
                                :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [initial-task]))

            ;; Test valid status values
            (doseq [valid-status ["open" "closed" "in-progress" "blocked"]]
              (let [result @(mcp-client/call-tool client
                                                  "update-task"
                                                  {:task-id 1
                                                   :status valid-status})]
                (is (not (:isError result))
                    (str "Should accept valid status: " valid-status))))

            ;; Test invalid status value
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 1
                                                 :status "invalid-status"})]
              (is (:isError result))
              (is (re-find #"Invalid task field values"
                           (-> result :content first :text))))

            (finally
              (mcp-client/close! client)
              ((:stop server))))))

      (testing "validates type field"
        (write-config-file "{:use-git? false}")

        (let [{:keys [server client]} (create-test-server-and-client)]
          (try
            ;; Create initial task
            (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                  initial-task {:id 1
                                :title "Test task"
                                :description "Desc"
                                :design ""
                                :category "simple"
                                :status :open
                                :type :task
                                :meta {}
                                :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [initial-task]))

            ;; Test valid type values
            (doseq [valid-type ["task" "bug" "feature" "story" "chore"]]
              (let [result @(mcp-client/call-tool client
                                                  "update-task"
                                                  {:task-id 1
                                                   :type valid-type})]
                (is (not (:isError result))
                    (str "Should accept valid type: " valid-type))))

            ;; Test invalid type value
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 1
                                                 :type "invalid-type"})]
              (is (:isError result))
              (is (re-find #"Invalid task field values"
                           (-> result :content first :text))))

            (finally
              (mcp-client/close! client)
              ((:stop server))))))

      (testing "validates parent-id field"
        (write-config-file "{:use-git? false}")

        (let [{:keys [server client]} (create-test-server-and-client)]
          (try
            ;; Create parent and child tasks
            (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                  parent-task {:id 1
                               :title "Parent"
                               :description ""
                               :design ""
                               :category "large"
                               :status :open
                               :type :story
                               :meta {}
                               :relations []}
                  child-task {:id 2
                              :title "Child"
                              :description ""
                              :design ""
                              :category "simple"
                              :status :open
                              :type :task
                              :meta {}
                              :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [parent-task child-task]))

            ;; Test valid parent-id
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 2
                                                 :parent-id 1})]
              (is (not (:isError result))))

            ;; Test non-existent parent-id
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 2
                                                 :parent-id 9999})]
              (is (:isError result))
              (is (re-find #"Parent task not found"
                           (-> result :content first :text))))

            (finally
              (mcp-client/close! client)
              ((:stop server))))))

      (testing "validates meta field"
        (write-config-file "{:use-git? false}")

        (let [{:keys [server client]} (create-test-server-and-client)]
          (try
            ;; Create initial task
            (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                  initial-task {:id 1
                                :title "Test task"
                                :description "Desc"
                                :design ""
                                :category "simple"
                                :status :open
                                :type :task
                                :meta {}
                                :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [initial-task]))

            ;; Test valid meta with string keys and values
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 1
                                                 :meta {"priority" "high"
                                                        "assigned-to" "alice"}})]
              (is (not (:isError result))))

            ;; Test meta with non-string value gets coerced to string
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 1
                                                 :meta {"priority" 123}})]
              (is (not (:isError result)))
              ;; Verify the number was coerced to a string
              (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                    tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                    updated-task (first tasks)]
                (is (= {"priority" "123"} (:meta updated-task)))))

            (finally
              (mcp-client/close! client)
              ((:stop server))))))

      (testing "validates relations field"
        (write-config-file "{:use-git? false}")

        (let [{:keys [server client]} (create-test-server-and-client)]
          (try
            ;; Create initial tasks
            (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                  task-1 {:id 1
                          :title "Task 1"
                          :description ""
                          :design ""
                          :category "simple"
                          :status :open
                          :type :task
                          :meta {}
                          :relations []}
                  task-2 {:id 2
                          :title "Task 2"
                          :description ""
                          :design ""
                          :category "simple"
                          :status :open
                          :type :task
                          :meta {}
                          :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [task-1 task-2]))

            ;; Test valid relations with proper structure
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 1
                                                 :relations [{"id" 1
                                                              "relates-to" 2
                                                              "as-type" "blocked-by"}]})]
              (is (not (:isError result))))

            ;; Test multiple valid relation types
            (doseq [relation-type ["blocked-by" "related" "discovered-during"]]
              (let [result @(mcp-client/call-tool client
                                                  "update-task"
                                                  {:task-id 1
                                                   :relations [{"id" 1
                                                                "relates-to" 2
                                                                "as-type" relation-type}]})]
                (is (not (:isError result))
                    (str "Should accept valid relation type: " relation-type))))

            ;; Test invalid relation - wrong as-type enum
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 1
                                                 :relations [{"id" 1
                                                              "relates-to" 2
                                                              "as-type" "invalid-type"}]})]
              (is (:isError result))
              (is (re-find #"Invalid task field values"
                           (-> result :content first :text))))

            ;; Test invalid relation - missing required field
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 1
                                                 :relations [{"id" 1
                                                              "relates-to" 2}]})]
              (is (:isError result))
              (is (re-find #"Invalid task field values"
                           (-> result :content first :text))))

            (finally
              (mcp-client/close! client)
              ((:stop server)))))))

    (testing "nil handling"
      (testing "clears parent-id with nil"
        (write-config-file "{:use-git? false}")

        (let [{:keys [server client]} (create-test-server-and-client)]
          (try
            ;; Create parent and child tasks
            (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                  parent-task {:id 1
                               :title "Parent"
                               :description ""
                               :design ""
                               :category "large"
                               :status :open
                               :type :story
                               :meta {}
                               :relations []}
                  child-task {:id 2
                              :title "Child"
                              :description ""
                              :design ""
                              :category "simple"
                              :status :open
                              :type :task
                              :parent-id 1
                              :meta {}
                              :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [parent-task child-task]))

            ;; Clear parent-id with nil
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 2
                                                 :parent-id nil})]
              (is (not (:isError result)))

              ;; Verify parent-id was cleared
              (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                    tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                    updated-task (first (filter #(= (:id %) 2) tasks))]
                (is (nil? (:parent-id updated-task)))))

            (finally
              (mcp-client/close! client)
              ((:stop server))))))

      (testing "clears meta with nil"
        (write-config-file "{:use-git? false}")

        (let [{:keys [server client]} (create-test-server-and-client)]
          (try
            ;; Create task with meta
            (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                  initial-task {:id 1
                                :title "Test task"
                                :description "Desc"
                                :design ""
                                :category "simple"
                                :status :open
                                :type :task
                                :meta {"priority" "high"}
                                :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [initial-task]))

            ;; Clear meta with nil
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 1
                                                 :meta nil})]
              (is (not (:isError result)))

              ;; Verify meta was cleared to empty map
              (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                    tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                    updated-task (first tasks)]
                (is (= {} (:meta updated-task)))))

            (finally
              (mcp-client/close! client)
              ((:stop server))))))

      (testing "clears relations with nil"
        (write-config-file "{:use-git? false}")

        (let [{:keys [server client]} (create-test-server-and-client)]
          (try
            ;; Create task with relations
            (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                  task-1 {:id 1
                          :title "Task 1"
                          :description ""
                          :design ""
                          :category "simple"
                          :status :open
                          :type :task
                          :meta {}
                          :relations [{:id 1 :relates-to 2 :as-type :blocked-by}]}
                  task-2 {:id 2
                          :title "Task 2"
                          :description ""
                          :design ""
                          :category "simple"
                          :status :open
                          :type :task
                          :meta {}
                          :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [task-1 task-2]))

            ;; Clear relations with nil
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 1
                                                 :relations nil})]
              (is (not (:isError result)))

              ;; Verify relations were cleared to empty vector
              (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                    tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                    updated-task (first (filter #(= (:id %) 1) tasks))]
                (is (= [] (:relations updated-task)))))

            (finally
              (mcp-client/close! client)
              ((:stop server)))))))

    (testing "replacement semantics"
      (testing "replaces entire meta map"
        (write-config-file "{:use-git? false}")

        (let [{:keys [server client]} (create-test-server-and-client)]
          (try
            ;; Create task with initial meta
            (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                  initial-task {:id 1
                                :title "Test task"
                                :description "Desc"
                                :design ""
                                :category "simple"
                                :status :open
                                :type :task
                                :meta {"old-key" "old-value"
                                       "keep-key" "keep-value"}
                                :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [initial-task]))

            ;; Replace meta entirely (not merge)
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 1
                                                 :meta {"new-key" "new-value"}})]
              (is (not (:isError result)))

              ;; Verify old keys are gone and only new keys exist
              (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                    tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                    updated-task (first tasks)]
                (is (= {"new-key" "new-value"} (:meta updated-task)))
                (is (not (contains? (:meta updated-task) "old-key")))
                (is (not (contains? (:meta updated-task) "keep-key")))))

            (finally
              (mcp-client/close! client)
              ((:stop server))))))

      (testing "replaces entire relations vector"
        (write-config-file "{:use-git? false}")

        (let [{:keys [server client]} (create-test-server-and-client)]
          (try
            ;; Create tasks with initial relations
            (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                  task-1 {:id 1
                          :title "Task 1"
                          :description ""
                          :design ""
                          :category "simple"
                          :status :open
                          :type :task
                          :meta {}
                          :relations [{:id 1 :relates-to 2 :as-type :blocked-by}
                                      {:id 2 :relates-to 3 :as-type :related}]}
                  task-2 {:id 2
                          :title "Task 2"
                          :description ""
                          :design ""
                          :category "simple"
                          :status :open
                          :type :task
                          :meta {}
                          :relations []}
                  task-3 {:id 3
                          :title "Task 3"
                          :description ""
                          :design ""
                          :category "simple"
                          :status :open
                          :type :task
                          :meta {}
                          :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [task-1 task-2 task-3]))

            ;; Replace relations entirely (not append)
            (let [result @(mcp-client/call-tool client
                                                "update-task"
                                                {:task-id 1
                                                 :relations [{"id" 3
                                                              "relates-to" 3
                                                              "as-type" "discovered-during"}]})]
              (is (not (:isError result)))

              ;; Verify old relations are gone and only new relation exists
              (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                    tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                    updated-task (first (filter #(= (:id %) 1) tasks))]
                (is (= 1 (count (:relations updated-task))))
                (let [relation (first (:relations updated-task))]
                  (is (= 3 (:id relation)))
                  (is (= 3 (:relates-to relation)))
                  (is (= :discovered-during (:as-type relation))))))

            (finally
              (mcp-client/close! client)
              ((:stop server)))))))))

(deftest ^:integ category-prompt-resources-integration-test
  ;; Test comprehensive validation of category prompt resources through MCP protocol.
  ;; Validates that category instructions are accessible as standalone resources
  ;; without task lookup/completion workflow.
  (testing "category prompt resources integration"
    (write-config-file "{:use-git? true}")
    (.mkdirs (io/file test-project-dir ".mcp-tasks" ".git"))

    ;; Create test prompt files
    (let [prompts-dir (io/file test-project-dir ".mcp-tasks" "prompts")]
      (.mkdirs prompts-dir)
      (spit (io/file prompts-dir "simple.md")
            "---\ndescription: Execute simple tasks with basic workflow\n---\n\n- Analyze the task\n- Implement solution")
      (spit (io/file prompts-dir "medium.md")
            "---\ndescription: Execute medium complexity tasks\n---\n\n- Deep analysis\n- Design approach\n- Implementation")
      (spit (io/file prompts-dir "large.md")
            "---\ndescription: Execute large tasks with detailed planning\n---\n\n- Comprehensive analysis\n- Detailed design\n- Implementation\n- Testing")
      (spit (io/file prompts-dir "clarify-task.md")
            "---\ndescription: Transform informal instructions into clear specifications\n---\n\n- Analyze requirements\n- Clarify ambiguities"))

    (let [{:keys [server client]} (create-test-server-and-client)]
      (try
        (testing "category prompt resources are listed"
          (let [resources-response @(mcp-client/list-resources client)
                resources (:resources resources-response)
                category-resources (filter #(str/includes? (:uri %) "prompt://category-")
                                           resources)
                category-uris (set (map :uri category-resources))]
            (is (>= (count category-resources) 4)
                "Should have at least 4 category prompt resources")
            (is (contains? category-uris "prompt://category-simple"))
            (is (contains? category-uris "prompt://category-medium"))
            (is (contains? category-uris "prompt://category-large"))
            (is (contains? category-uris "prompt://category-clarify-task"))))

        (testing "category prompt resources have correct structure"
          (let [resources-response @(mcp-client/list-resources client)
                resources (:resources resources-response)
                category-resources (filter #(str/includes? (:uri %) "prompt://category-")
                                           resources)]
            (doseq [resource category-resources]
              (is (string? (:name resource)))
              (is (string? (:uri resource)))
              (is (= "text/markdown" (:mimeType resource)))
              (is (string? (:description resource))))))

        (testing "can read category prompt resource content"
          (let [read-response @(mcp-client/read-resource client "prompt://category-simple")
                text (-> read-response :contents first :text)]
            (is (not (:isError read-response)))
            (is (string? text))
            (is (pos? (count text)))
            (testing "content includes frontmatter"
              (is (str/includes? text "---")
                  "Should contain frontmatter delimiters")
              (is (str/includes? text "description:")
                  "Should contain frontmatter fields"))
            (testing "content includes instructions"
              (is (str/includes? text "Analyze the task")))))

        (testing "each category resource has distinct content"
          (let [simple-text (-> @(mcp-client/read-resource client "prompt://category-simple")
                                :contents first :text)
                medium-text (-> @(mcp-client/read-resource client "prompt://category-medium")
                                :contents first :text)
                large-text (-> @(mcp-client/read-resource client "prompt://category-large")
                               :contents first :text)]
            (is (not= simple-text medium-text))
            (is (not= simple-text large-text))
            (is (not= medium-text large-text))
            (is (str/includes? simple-text "Analyze the task"))
            (is (str/includes? medium-text "Deep analysis"))
            (is (str/includes? large-text "Comprehensive analysis"))))

        (testing "description comes from frontmatter"
          (let [resources-response @(mcp-client/list-resources client)
                resources (:resources resources-response)
                simple-resource (first (filter #(= (:uri %) "prompt://category-simple")
                                               resources))]
            (is (= "Execute simple tasks with basic workflow"
                   (:description simple-resource)))))

        (testing "existing next-<category> prompts still work"
          (let [prompts-response @(mcp-client/list-prompts client)
                prompts (:prompts prompts-response)
                prompt-names (set (map :name prompts))]
            (is (contains? prompt-names "next-simple"))
            (is (contains? prompt-names "next-medium"))
            (is (contains? prompt-names "next-large"))
            (is (contains? prompt-names "next-clarify-task"))))

        (finally
          (mcp-client/close! client)
          ((:stop server)))))))

(deftest ^:integ story-prompts-arguments-test
  ;; Test that all story prompts properly document their arguments as optional.
  ;; Validates argument parsing, MCP schema, markdown documentation, and consistency.
  (testing "story prompts arguments validation"
    (write-config-file "{:use-git? true}")
    (.mkdirs (io/file test-project-dir ".mcp-tasks" ".git"))

    (let [{:keys [server client]} (create-test-server-and-client)
          story-prompt-names ["execute-story-task"
                              "create-story-tasks"
                              "review-story-implementation"
                              "complete-story"
                              "create-story-pr"]]
      (try
        (testing "all story prompts are registered"
          (let [prompts-response @(mcp-client/list-prompts client)
                prompts (:prompts prompts-response)
                prompt-names (set (map :name prompts))]
            (doseq [story-prompt story-prompt-names]
              (is (contains? prompt-names story-prompt)
                  (str "Should have prompt: " story-prompt)))))

        (testing "all story prompts have arguments with :required false"
          (let [prompts-response @(mcp-client/list-prompts client)
                prompts (:prompts prompts-response)]
            (doseq [story-prompt-name story-prompt-names]
              (let [prompt (first (filter #(= (:name %) story-prompt-name) prompts))]
                (testing (str story-prompt-name " has arguments field")
                  (is (contains? prompt :arguments)
                      (str story-prompt-name " should have :arguments field"))
                  (when (contains? prompt :arguments)
                    (let [arguments (:arguments prompt)]
                      (is (seq arguments)
                          (str story-prompt-name " should have at least one argument"))
                      (testing (str story-prompt-name " arguments are all optional")
                        (doseq [arg arguments]
                          (is (false? (:required arg))
                              (str story-prompt-name " argument " (:name arg)
                                   " should have :required false")))))))))))

        (testing "prompt resources include frontmatter with argument-hint"
          (doseq [story-prompt-name story-prompt-names]
            (let [uri (str "prompt://" story-prompt-name)
                  read-response @(mcp-client/read-resource client uri)
                  text (-> read-response :contents first :text)]
              (testing (str story-prompt-name " resource has frontmatter")
                (is (not (:isError read-response)))
                (is (str/starts-with? text "---\n")
                    (str story-prompt-name " should start with frontmatter"))
                (is (str/includes? text "\n---\n")
                    (str story-prompt-name " should have complete frontmatter")))

              (testing (str story-prompt-name " has argument-hint in frontmatter")
                (let [frontmatter-end (str/index-of text "\n---\n")
                      frontmatter (subs text 0 frontmatter-end)]
                  (is (str/includes? frontmatter "argument-hint:")
                      (str story-prompt-name " should have argument-hint in frontmatter"))

                  (testing (str story-prompt-name " argument-hint uses square brackets")
                    (when (str/includes? frontmatter "argument-hint:")
                      (is (str/includes? frontmatter "[")
                          (str story-prompt-name " argument-hint should use square brackets for optional args")))))))))

        (testing "prompt content describes argument flexibility"
          (doseq [story-prompt-name story-prompt-names]
            (let [uri (str "prompt://" story-prompt-name)
                  read-response @(mcp-client/read-resource client uri)
                  text (-> read-response :contents first :text)]
              (testing (str story-prompt-name " documents parsing logic or argument flexibility")
                (is (or (str/includes? text "Parsing Logic")
                        (str/includes? text "Parse the arguments")
                        (str/includes? text "optional"))
                    (str story-prompt-name " should document argument parsing or flexibility"))))))

        (finally
          (mcp-client/close! client)
          ((:stop server)))))))

(deftest ^:integ refinement-status-meta-setting-test
  ;; Test that update-task correctly sets and merges refinement status in meta field.
  ;; This supports the refine-task prompt workflow which marks tasks as refined.
  (testing "refinement status meta setting"
    (testing "sets refined meta on task without existing meta"
      (write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                initial-task {:id 1
                              :title "Unrefined task"
                              :description "Task description"
                              :design ""
                              :category "simple"
                              :status :open
                              :type :task
                              :meta {}
                              :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [initial-task]))

          (let [result @(mcp-client/call-tool client
                                              "update-task"
                                              {:task-id 1
                                               :meta {"refined" "true"}})]
            (is (not (:isError result)))

            (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                  tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                  updated-task (first tasks)]
              (is (= {"refined" "true"} (:meta updated-task)))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "merges refined meta with existing meta values"
      (write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                initial-task {:id 1
                              :title "Task with meta"
                              :description "Task description"
                              :design ""
                              :category "medium"
                              :status :open
                              :type :task
                              :meta {"priority" "high"
                                     "assigned-to" "alice"}
                              :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [initial-task]))

          (let [result @(mcp-client/call-tool
                          client
                          "update-task"
                          {:task-id 1
                           :meta {"priority" "high"
                                  "assigned-to" "alice"
                                  "refined" "true"}})]
            (is (not (:isError result)))

            (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                  tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                  updated-task (first tasks)]
              (is (= {"priority" "high"
                      "assigned-to" "alice"
                      "refined" "true"}
                     (:meta updated-task)))
              (is (contains? (:meta updated-task) "refined"))
              (is (= "true" (get (:meta updated-task) "refined")))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "updates existing refined flag"
      (write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                initial-task {:id 1
                              :title "Already refined"
                              :description "Task description"
                              :design ""
                              :category "large"
                              :status :open
                              :type :task
                              :meta {"refined" "false"}
                              :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [initial-task]))

          (let [result @(mcp-client/call-tool client
                                              "update-task"
                                              {:task-id 1
                                               :meta {"refined" "true"}})]
            (is (not (:isError result)))

            (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                  tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                  updated-task (first tasks)]
              (is (= {"refined" "true"} (:meta updated-task)))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "sets refined meta on story task"
      (write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                story-task {:id 1
                            :title "Unrefined story"
                            :description "Story description"
                            :design ""
                            :category "large"
                            :status :open
                            :type :story
                            :meta {}
                            :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story-task]))

          (let [result @(mcp-client/call-tool client
                                              "update-task"
                                              {:task-id 1
                                               :meta {"refined" "true"}})]
            (is (not (:isError result)))

            (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                  tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                  updated-task (first tasks)]
              (is (= :story (:type updated-task)))
              (is (= {"refined" "true"} (:meta updated-task)))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ refinement-status-detection-test
  ;; Test that refinement status can be correctly detected from task meta field.
  ;; This supports category prompts and create-story-tasks prompt which check refinement.
  (testing "refinement status detection"
    (testing "detects refined task via file read"
      (write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                refined-task {:id 1
                              :title "Refined task"
                              :description "Task description"
                              :design ""
                              :category "simple"
                              :status :open
                              :type :task
                              :meta {"refined" "true"}
                              :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [refined-task]))

          ;; Verify via file read
          (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                task (first tasks)]
            (is (= "true" (get-in task [:meta "refined"])))
            (is (contains? (:meta task) "refined")))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "detects unrefined task via file read"
      (write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                unrefined-task {:id 1
                                :title "Unrefined task"
                                :description "Task description"
                                :design ""
                                :category "medium"
                                :status :open
                                :type :task
                                :meta {}
                                :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [unrefined-task]))

          ;; Verify via file read
          (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                task (first tasks)]
            (is (= {} (:meta task)))
            (is (not (contains? (:meta task) "refined"))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "detects refined story via file read"
      (write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                refined-story {:id 1
                               :title "Refined story"
                               :description "Story description"
                               :design ""
                               :category "large"
                               :status :open
                               :type :story
                               :meta {"refined" "true"}
                               :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [refined-story]))

          ;; Verify via file read
          (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                story (first tasks)]
            (is (= :story (:type story)))
            (is (= "true" (get-in story [:meta "refined"]))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "distinguishes refined from skip-refinement-check flag"
      (write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                refined-task {:id 1
                              :title "Refined task"
                              :description ""
                              :design ""
                              :category "simple"
                              :status :open
                              :type :task
                              :meta {"refined" "true"}
                              :relations []}
                bypass-task {:id 2
                             :title "Bypass task"
                             :description ""
                             :design ""
                             :category "simple"
                             :status :open
                             :type :task
                             :meta {"skip-refinement-check" "true"}
                             :relations []}
                both-task {:id 3
                           :title "Both flags"
                           :description ""
                           :design ""
                           :category "simple"
                           :status :open
                           :type :task
                           :meta {"refined" "true"
                                  "skip-refinement-check" "true"}
                           :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file)
                                    [refined-task bypass-task both-task]))

          ;; Verify via file read
          (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                refined-task (first tasks)
                bypass-task (second tasks)
                both-task (nth tasks 2)]
            (is (= "true" (get-in refined-task [:meta "refined"])))
            (is (not (contains? (:meta refined-task) "skip-refinement-check")))

            (is (= "true" (get-in bypass-task [:meta "skip-refinement-check"])))
            (is (not (contains? (:meta bypass-task) "refined")))

            (is (= "true" (get-in both-task [:meta "refined"])))
            (is (= "true" (get-in both-task [:meta "skip-refinement-check"]))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ refinement-workflow-integration-test
  ;; Test complete refinement workflow from task creation through refinement to execution.
  ;; Verifies the end-to-end integration of refinement status tracking.
  (testing "refinement workflow integration"
    (testing "complete workflow: create → refine → verify"
      (write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          ;; Ensure tasks file is empty before starting
          (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) []))

          ;; Step 1: Create an unrefined task
          (let [add-result @(mcp-client/call-tool client
                                                  "add-task"
                                                  {:category "medium"
                                                   :title "New feature task"})]
            (is (not (:isError add-result))))

          ;; Step 2: Verify task is unrefined initially
          (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                task (first tasks)]
            (is (not (contains? (:meta task) "refined")))
            (is (= {} (:meta task))))

          ;; Step 3: Simulate refine-task by updating with refined meta
          (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                task-id (:id (first tasks))
                update-result @(mcp-client/call-tool client
                                                     "update-task"
                                                     {:task-id task-id
                                                      :meta {"refined" "true"}})]
            (is (not (:isError update-result))))

          ;; Step 4: Verify task is now refined
          (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                task (first tasks)]
            (is (contains? (:meta task) "refined"))
            (is (= "true" (get-in task [:meta "refined"]))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "story workflow: create story → refine → create tasks"
      (write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          ;; Step 1: Create story task
          (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                story-task {:id 1
                            :title "User authentication"
                            :description "Implement user auth"
                            :design ""
                            :category "large"
                            :status :open
                            :type :story
                            :meta {}
                            :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story-task]))

          ;; Step 2: Verify story is unrefined
          (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                story (first tasks)]
            (is (= :story (:type story)))
            (is (not (contains? (:meta story) "refined"))))

          ;; Step 3: Refine the story
          (let [update-result @(mcp-client/call-tool client
                                                     "update-task"
                                                     {:task-id 1
                                                      :meta {"refined" "true"}})]
            (is (not (:isError update-result))))

          ;; Step 4: Verify story is refined
          (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                story (first tasks)]
            (is (= "true" (get-in story [:meta "refined"]))))

          ;; Step 5: Add child tasks to refined story
          (let [add-result @(mcp-client/call-tool client
                                                  "add-task"
                                                  {:category "simple"
                                                   :title "Login endpoint"
                                                   :parent-id 1})]
            (is (not (:isError add-result))))

          ;; Step 6: Verify child task was created
          (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                child-tasks (filter #(= 1 (:parent-id %)) tasks)]
            (is (= 1 (count child-tasks)))
            (let [child-task (first child-tasks)]
              (is (= "Login endpoint" (:title child-task)))
              (is (= 1 (:parent-id child-task)))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "preserves meta when updating other fields"
      (write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          ;; Create a refined task with additional meta
          (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                initial-task {:id 1
                              :title "Original title"
                              :description "Original desc"
                              :design ""
                              :category "medium"
                              :status :open
                              :type :task
                              :meta {"refined" "true"
                                     "priority" "high"}
                              :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [initial-task]))

          ;; Update only title and description
          (let [update-result @(mcp-client/call-tool client
                                                     "update-task"
                                                     {:task-id 1
                                                      :title "Updated title"
                                                      :description "Updated desc"})]
            (is (not (:isError update-result))))

          ;; Verify meta is preserved (since we didn't update it)
          (let [tasks-file (io/file test-project-dir ".mcp-tasks" "tasks.ednl")
                tasks (tasks-file/read-ednl (.getAbsolutePath tasks-file))
                updated-task (first tasks)]
            (is (= "Updated title" (:title updated-task)))
            (is (= "Updated desc" (:description updated-task)))
            ;; Meta should remain unchanged
            (is (= {"refined" "true" "priority" "high"} (:meta updated-task))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ current-execution-resource-test
  ;; Test that current-execution resource exposes execution state correctly.
  ;; Validates resource reads .mcp-tasks-current.edn and returns proper format.
  (testing "current-execution resource"
    (testing "returns nil when no execution state exists"
      (write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          (let [read-response @(mcp-client/read-resource client "resource://current-execution")
                text (-> read-response :contents first :text)]
            (is (not (:isError read-response)))
            (is (= "nil" text)))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "returns execution state when file exists"
      (write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          ;; Write execution state file
          (let [state-file (io/file test-project-dir ".mcp-tasks-current.edn")
                state {:story-id 177
                       :task-id 181
                       :started-at "2025-10-20T14:30:00Z"}]
            (spit state-file (pr-str state)))

          (let [read-response @(mcp-client/read-resource client "resource://current-execution")
                text (-> read-response :contents first :text)
                state (edn/read-string text)]
            (is (not (:isError read-response)))
            (is (= 177 (:story-id state)))
            (is (= 181 (:task-id state)))
            (is (= "2025-10-20T14:30:00Z" (:started-at state))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "returns nil when execution state file is invalid"
      (write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          ;; Write invalid execution state file (missing required field)
          (let [state-file (io/file test-project-dir ".mcp-tasks-current.edn")
                invalid-state {:story-id 177}]
            (spit state-file (pr-str invalid-state)))

          (let [read-response @(mcp-client/read-resource client "resource://current-execution")
                text (-> read-response :contents first :text)]
            (is (not (:isError read-response)))
            (is (= "nil" text)))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "returns execution state with nil story-id for standalone task"
      (write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          ;; Write execution state file with nil story-id
          (let [state-file (io/file test-project-dir ".mcp-tasks-current.edn")
                state {:story-id nil
                       :task-id 42
                       :started-at "2025-10-20T15:00:00Z"}]
            (spit state-file (pr-str state)))

          (let [read-response @(mcp-client/read-resource client "resource://current-execution")
                text (-> read-response :contents first :text)
                state (edn/read-string text)]
            (is (not (:isError read-response)))
            (is (nil? (:story-id state)))
            (is (= 42 (:task-id state)))
            (is (= "2025-10-20T15:00:00Z" (:started-at state))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "resource is listed in available resources"
      (write-config-file "{:use-git? false}")

      (let [{:keys [server client]} (create-test-server-and-client)]
        (try
          (let [resources-response @(mcp-client/list-resources client)
                resources (:resources resources-response)
                current-exec-resource (first (filter #(= "resource://current-execution" (:uri %))
                                                     resources))]
            (is (some? current-exec-resource))
            (is (= "current-execution" (:name current-exec-resource)))
            (is (= "resource://current-execution" (:uri current-exec-resource)))
            (is (= "application/json" (:mimeType current-exec-resource)))
            (is (= "Current story and task execution state" (:description current-exec-resource))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))
