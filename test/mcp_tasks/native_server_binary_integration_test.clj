(ns mcp-tasks.native-server-binary-integration-test
  "Integration tests for native binary MCP server executable.

  Tests that the GraalVM native-image server binary implements the MCP
  protocol correctly using stdio transport. Includes smoke tests suitable
  for CI and comprehensive protocol tests for Linux.

  ## Binary Requirement

  All tests require the native server binary to exist. If the binary is not
  found, the test fixture throws an exception with clear error message and
  binary path. This ensures test skips are explicit and visible in test output
  rather than being silently ignored.

  ## Test Metadata Tags

  This namespace uses two metadata tags for test filtering:

  - `:native-binary` - All tests in this namespace have this tag. Used to run
    only native binary tests when the binary exists.

  - `:comprehensive` - Subset of tests that perform more thorough protocol
    validation and performance testing. These tests run on Linux in CI but are
    skipped on macOS/Windows to reduce CI time while maintaining coverage.

  Test filtering in CI:
  - macOS/Windows: Run only `:native-binary` tests (smoke tests)
  - Linux: Run both `:native-binary` and `:comprehensive` tests (full suite)"
  (:require
    [babashka.fs :as fs]
    [babashka.process :as process]
    [build :as build]
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.set]
    [clojure.string]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-clj.mcp-client.core :as mcp-client]))

(def ^:dynamic *test-dir* nil)
(def ^:dynamic *binary-path* nil)

(defn- setup-test-dir
  [test-dir]
  (fs/create-dirs (io/file test-dir ".mcp-tasks"))
  (fs/create-dirs (io/file test-dir ".mcp-tasks/category-prompts"))
  ;; Create config file
  (spit (io/file test-dir ".mcp-tasks.edn") "{}")
  (spit (io/file test-dir ".mcp-tasks/category-prompts/simple.md")
        "---\ndescription: Simple tasks\n---\nSimple task execution"))

(defn- binary-test-fixture
  "Test fixture that sets up temporary directory and locates binary.
  Throws an exception to explicitly skip tests if binary is not found.

  Uses BINARY_TARGET_OS and BINARY_TARGET_ARCH environment variables if available
  (set by CI to test cross-compiled binaries), otherwise detects current platform."
  [f]
  (let [;; Check for env vars first (for CI cross-platform testing)
        target-os (System/getenv "BINARY_TARGET_OS")
        target-arch (System/getenv "BINARY_TARGET_ARCH")
        platform (if (and target-os target-arch)
                   {:os (keyword target-os)
                    :arch (keyword target-arch)}
                   (build/detect-platform))
        binary-name (build/platform-binary-name "mcp-tasks-server" platform)
        binary (io/file "target" binary-name)]
    (when-not (.exists binary)
      (throw (ex-info "Native server binary not found - build with: bb build-native-server"
                      {:type ::binary-not-found
                       :binary-path (.getAbsolutePath binary)
                       :platform platform})))
    (let [test-dir (str (fs/create-temp-dir {:prefix "mcp-tasks-native-server-"}))]
      (try
        (setup-test-dir test-dir)
        (binding [*test-dir* test-dir
                  *binary-path* (.getAbsolutePath binary)]
          (f))
        (finally
          (fs/delete-tree test-dir))))))

(use-fixtures :each binary-test-fixture)

(defn- send-jsonrpc
  "Send a JSON-RPC request to the server via stdin and read response from stdout.
  Returns the parsed response map."
  [process-handle request-map]
  (let [request-json (str (json/generate-string request-map) "\n")
        stdin (io/writer (:in process-handle))]
    (.write stdin request-json)
    (.flush stdin)
    ;; Read response line from stdout
    (let [stdout (io/reader (:out process-handle))
          response-line (.readLine stdout)]
      (when response-line
        (json/parse-string response-line keyword)))))

(defn- start-server
  "Start the native server binary as a subprocess.
  Returns process handle with :in, :out, :err streams."
  []
  (process/process [*binary-path*]
                   {:dir *test-dir*
                    :in :stream
                    :out :stream
                    :err :stream}))

(defn- stop-server
  "Stop the server process gracefully."
  [proc]
  (try
    (.destroy (:proc proc))
    (catch Exception _
      ;; Already stopped
      nil)))

;; MCP Client Helpers

(defn- create-binary-client
  "Create an MCP client connected to the native binary via stdio transport.

  Returns the client after waiting for it to be ready."
  []
  (let [client (mcp-client/create-client
                 {:transport {:type :stdio
                              :command *binary-path*
                              :args []
                              :cwd *test-dir*}
                  :client-info {:name "test-client" :version "1.0.0"}
                  :protocol-version "2025-06-18"})]
    ;; Wait for client to be ready (up to 5 seconds)
    (mcp-client/wait-for-ready client 5000)
    client))

(defn- list-prompts-via-client
  "List all prompts via MCP client.

  Returns the prompts/list response map."
  [client]
  @(mcp-client/list-prompts client))

(defn- get-prompt-via-client
  "Get a specific prompt via MCP client.

  Returns the prompts/get response map."
  [client prompt-name]
  @(mcp-client/get-prompt client prompt-name {}))

(defn- list-resources-via-client
  "List all resources via MCP client.

  Returns the resources/list response map."
  [client]
  @(mcp-client/list-resources client))

(defn- read-resource-via-client
  "Read a specific resource via MCP client.

  Returns the resources/read response map."
  [client uri]
  @(mcp-client/read-resource client uri))

;; Smoke Tests

(deftest ^:native-binary smoke-test-server-starts
  ;; Verify server binary starts without errors
  (testing "smoke-test-server-starts"
    (testing "server binary starts and responds to initialize"
      (let [proc (start-server)]
        (try
          ;; Send initialize request
          (let [response (send-jsonrpc proc
                                       {:jsonrpc "2.0"
                                        :id 1
                                        :method "initialize"
                                        :params {:protocolVersion "2025-06-18"
                                                 :capabilities {}
                                                 :clientInfo {:name "test-client"
                                                              :version "1.0.0"}}})]
            (when-not response
              ;; Print stderr if server didn't respond
              (let [stderr-reader (io/reader (:err proc))]
                (println "\n=== Server stderr output ===")
                (doseq [line (line-seq stderr-reader)]
                  (println line))
                (println "=== End stderr output ===\n")))
            (is (some? response)
                "Server should respond to initialize request")
            (is (= "2.0" (:jsonrpc response))
                "Response should use JSON-RPC 2.0")
            (is (= 1 (:id response))
                "Response should have matching request ID")
            (is (some? (:result response))
                "Response should contain result")
            (is (= "mcp-tasks" (get-in response [:result :serverInfo :name]))
                "Server should identify as mcp-tasks"))
          (finally
            (stop-server proc)))))))

(deftest ^:native-binary smoke-test-stdio-transport
  ;; Verify stdio transport works bidirectionally
  (testing "smoke-test-stdio-transport"
    (testing "server handles stdio communication"
      (let [proc (start-server)]
        (try
          ;; Initialize
          (send-jsonrpc proc
                        {:jsonrpc "2.0"
                         :id 1
                         :method "initialize"
                         :params {:protocolVersion "2025-06-18"
                                  :capabilities {}
                                  :clientInfo {:name "test-client"
                                               :version "1.0.0"}}})

          ;; Send initialized notification
          (send-jsonrpc proc
                        {:jsonrpc "2.0"
                         :method "initialized"})

          ;; List tools
          (let [response (send-jsonrpc proc
                                       {:jsonrpc "2.0"
                                        :id 2
                                        :method "tools/list"})]
            (is (some? response)
                "Server should respond to tools/list")
            (is (vector? (get-in response [:result :tools]))
                "Response should contain tools array"))
          (finally
            (stop-server proc)))))))

(deftest ^:native-binary smoke-test-server-info
  ;; Verify server identifies itself correctly with proper name, version, and title
  (testing "smoke-test-server-info"
    (testing "server returns correct server-info in initialize response"
      (let [proc (start-server)]
        (try
          (let [response (send-jsonrpc proc
                                       {:jsonrpc "2.0"
                                        :id 1
                                        :method "initialize"
                                        :params {:protocolVersion "2025-06-18"
                                                 :capabilities {}
                                                 :clientInfo {:name "test-client"
                                                              :version "1.0.0"}}})]
            (is (= "mcp-tasks" (get-in response [:result :serverInfo :name]))
                "Server name should be 'mcp-tasks'")
            (is (= "0.1.124" (get-in response [:result :serverInfo :version]))
                "Server version should be '0.1.124'")
            (is (= "MCP Tasks Server" (get-in response [:result :serverInfo :title]))
                "Server title should be 'MCP Tasks Server'"))
          (finally
            (stop-server proc)))))))

(deftest ^:native-binary test-all-prompts-exposed
  ;; Verify all expected prompts are available via prompts/list.
  ;; Tests category, task, and story prompts are exposed from the binary.
  ;; Expected to FAIL before fix is applied (category prompts missing).
  (testing "test-all-prompts-exposed"
    (testing "all expected prompts available via prompts/list"
      (let [client (create-binary-client)]
        (try
          (let [response (list-prompts-via-client client)
                prompt-names (set (map :name (:prompts response)))
                expected-prompts #{"next-simple"
                                   "next-medium"
                                   "next-large"
                                   "next-clarify-task"
                                   "execute-task"
                                   "refine-task"
                                   "execute-story-child"
                                   "create-story-tasks"
                                   "review-story-implementation"
                                   "complete-story"
                                   "create-story-pr"}
                missing-prompts (clojure.set/difference expected-prompts prompt-names)
                unexpected-prompts (clojure.set/difference prompt-names expected-prompts)]
            (is (>= (count prompt-names) 11)
                (str "Expected at least 11 prompts, got " (count prompt-names)))
            (is (empty? missing-prompts)
                (str "Missing expected prompts:\n"
                     "  Expected: " (pr-str (sort expected-prompts)) "\n"
                     "  Actual:   " (pr-str (sort prompt-names)) "\n"
                     "  Missing:  " (pr-str (sort missing-prompts))))
            ;; Informational: report unexpected prompts if any
            (when (seq unexpected-prompts)
              (println "\nNote: Found additional prompts:" (pr-str (sort unexpected-prompts)))))
          (finally
            (mcp-client/close! client)))))))

(deftest ^:native-binary test-category-prompts-content
  ;; Verify category prompts return complete content via prompts/get.
  ;; Tests that each category prompt (simple, medium, large, clarify-task)
  ;; has valid content with expected structure and keywords.
  ;; Expected to FAIL before fix is applied (category prompts missing from binary).
  (testing "test-category-prompts-content"
    (testing "category prompts return complete content via prompts/get"
      (let [client (create-binary-client)
            category-prompts ["next-simple" "next-medium" "next-large" "next-clarify-task"]]
        (try
          (doseq [prompt-name category-prompts]
            (testing (str "prompt " prompt-name " has complete content")
              (let [response (get-prompt-via-client client prompt-name)
                    messages (:messages response)]
                (is (vector? messages)
                    (str "Prompt '" prompt-name "' should have :messages vector"))
                (is (pos? (count messages))
                    (str "Prompt '" prompt-name "' should have at least one message"))
                (when (seq messages)
                  (let [first-message (first messages)
                        content (get-in first-message [:content :text])]
                    (is (string? content)
                        (str "Prompt '" prompt-name "' message should have text content"))
                    (is (> (count content) 100)
                        (str "Prompt '" prompt-name "' content should be substantial (>100 chars), got " (count content) " chars"))
                    (is (re-find #"(?i)task" content)
                        (str "Prompt '" prompt-name "' content should contain 'task' keyword"))
                    (is (re-find #"(?i)complete" content)
                        (str "Prompt '" prompt-name "' content should contain 'complete' keyword")))))))
          (finally
            (mcp-client/close! client)))))))

(deftest ^:native-binary test-mcp-client-infrastructure
  ;; Verify MCP client helpers work with the native binary.
  ;; Tests that we can use mcp-client library instead of raw JSON-RPC.
  (testing "test-mcp-client-infrastructure"
    (testing "MCP client connects and calls prompts/list"
      (let [client (create-binary-client)]
        (try
          (let [response (list-prompts-via-client client)]
            (is (map? response)
                "prompts/list should return a map")
            (is (vector? (:prompts response))
                "prompts/list should contain :prompts vector")
            (is (pos? (count (:prompts response)))
                "prompts/list should return at least one prompt"))
          (finally
            (mcp-client/close! client)))))

    (testing "MCP client calls prompts/get"
      (let [client (create-binary-client)]
        (try
          (let [response (get-prompt-via-client client "execute-task")]
            (is (map? response)
                "prompts/get should return a map")
            (is (vector? (:messages response))
                "prompts/get should contain :messages vector")
            (is (pos? (count (:messages response)))
                "prompts/get should return at least one message")
            (let [first-message (first (:messages response))
                  content (get-in first-message [:content :text])]
              (is (string? content)
                  "Message should have text content")
              (is (> (count content) 100)
                  "Prompt content should be substantial")))
          (finally
            (mcp-client/close! client)))))

    (testing "MCP client calls resources/list"
      (let [client (create-binary-client)]
        (try
          (let [response (list-resources-via-client client)]
            (is (map? response)
                "resources/list should return a map")
            (is (vector? (:resources response))
                "resources/list should contain :resources vector"))
          (finally
            (mcp-client/close! client)))))

    (testing "MCP client calls resources/read"
      (let [client (create-binary-client)]
        (try
          ;; First get a resource URI from resources/list
          (let [list-response (list-resources-via-client client)
                resources (:resources list-response)]
            (when (seq resources)
              (let [uri (:uri (first resources))
                    read-response (read-resource-via-client client uri)]
                (is (map? read-response)
                    "resources/read should return a map")
                (is (vector? (:contents read-response))
                    "resources/read should contain :contents vector")
                (when (seq (:contents read-response))
                  (let [first-content (first (:contents read-response))]
                    (is (or (:text first-content) (:blob first-content))
                        "Content should have :text or :blob"))))))
          (finally
            (mcp-client/close! client)))))))

(deftest ^:native-binary test-prompt-resources-exposed
  ;; Verify prompt resources are accessible via resources/list and resources/read.
  ;; Tests that category prompts are exposed as resources with prompt:// URIs.
  ;; Expected to FAIL before fix is applied (category prompts missing from binary).
  (testing "test-prompt-resources-exposed"
    (testing "prompt resources accessible via resources endpoints"
      (let [client (create-binary-client)]
        (try
          (testing "resources/list returns prompt resources"
            (let [response (list-resources-via-client client)
                  resources (:resources response)
                  prompt-uris (->> resources
                                   (map :uri)
                                   (filter #(clojure.string/starts-with? % "prompt://"))
                                   set)
                  category-prompt-uris (->> prompt-uris
                                            (filter #(clojure.string/starts-with? % "prompt://category-"))
                                            set)]
              (is (vector? resources)
                  "resources/list should return :resources vector")
              (is (pos? (count prompt-uris))
                  (str "Should have at least one prompt:// resource, got " (count prompt-uris)))
              (is (pos? (count category-prompt-uris))
                  (str "Should have at least one category prompt resource (prompt://category-*), got:\n"
                       "  All prompt URIs: " (pr-str (sort prompt-uris)) "\n"
                       "  Category URIs:   " (pr-str (sort category-prompt-uris))))

              (testing "category prompt resources return complete content"
                ;; Try to read first category prompt resource
                (when (seq category-prompt-uris)
                  (let [test-uri (first (sort category-prompt-uris))
                        read-response (read-resource-via-client client test-uri)
                        contents (:contents read-response)]
                    (is (vector? contents)
                        (str "resources/read for '" test-uri "' should return :contents vector"))
                    (is (pos? (count contents))
                        (str "resources/read for '" test-uri "' should have at least one content item"))
                    (when (seq contents)
                      (let [first-content (first contents)
                            text (:text first-content)]
                        (is (string? text)
                            (str "Content for '" test-uri "' should have :text field"))
                        (is (> (count text) 100)
                            (str "Content for '" test-uri "' should be substantial (>100 chars), got " (count text) " chars")))))))))
          (finally
            (mcp-client/close! client)))))))

(deftest ^:native-binary test-task-and-story-prompts-work
  ;; Verify task and story prompts work correctly (regression test).
  ;; Tests execute-task and execute-story-child prompts via prompts/get.
  ;; Expected to PASS (these prompts already work correctly).
  (testing "test-task-and-story-prompts-work"
    (testing "task and story prompts return complete content"
      (let [client (create-binary-client)
            test-prompts ["execute-task" "execute-story-child"]]
        (try
          (doseq [prompt-name test-prompts]
            (testing (str "prompt " prompt-name " has complete content")
              (let [response (get-prompt-via-client client prompt-name)
                    messages (:messages response)]
                (is (vector? messages)
                    (str "Prompt '" prompt-name "' should have :messages vector, got: " (type messages)))
                (is (pos? (count messages))
                    (str "Prompt '" prompt-name "' should have at least one message, got: " (count messages)))
                (when (seq messages)
                  (let [first-message (first messages)
                        content (get-in first-message [:content :text])]
                    (is (string? content)
                        (str "Prompt '" prompt-name "' message should have text content, got: " (type content)))
                    (is (> (count content) 100)
                        (str "Prompt '" prompt-name "' content should be substantial (>100 chars), got " (count content) " chars")))))))
          (finally
            (mcp-client/close! client)))))))

;; Comprehensive Tests

(deftest ^:native-binary ^:comprehensive comprehensive-mcp-protocol
  ;; Test full MCP protocol workflow
  (testing "comprehensive-mcp-protocol"
    (let [proc (start-server)]
      (try
        (testing "initialize handshake"
          (let [response (send-jsonrpc proc
                                       {:jsonrpc "2.0"
                                        :id 1
                                        :method "initialize"
                                        :params {:protocolVersion "2025-06-18"
                                                 :capabilities {}
                                                 :clientInfo {:name "test-client"
                                                              :version "1.0.0"}}})]
            (is (= "mcp-tasks" (get-in response [:result :serverInfo :name])))
            (is (some? (get-in response [:result :capabilities])))
            (is (= "2025-06-18" (get-in response [:result :protocolVersion])))))

        (testing "initialized notification"
          (send-jsonrpc proc
                        {:jsonrpc "2.0"
                         :method "initialized"}))

        (testing "list available tools"
          (let [response (send-jsonrpc proc
                                       {:jsonrpc "2.0"
                                        :id 2
                                        :method "tools/list"})]
            (is (vector? (get-in response [:result :tools])))
            (is (pos? (count (get-in response [:result :tools]))))
            (is (some #(= "select-tasks" (:name %))
                      (get-in response [:result :tools])))))

        (testing "list available prompts"
          (let [response (send-jsonrpc proc
                                       {:jsonrpc "2.0"
                                        :id 3
                                        :method "prompts/list"})]
            (is (vector? (get-in response [:result :prompts])))
            (is (pos? (count (get-in response [:result :prompts]))))))

        (finally
          (stop-server proc))))))

(deftest ^:native-binary ^:comprehensive comprehensive-tool-invocation
  ;; Test calling MCP tools through the server
  (testing "comprehensive-tool-invocation"
    (let [proc (start-server)]
      (try
        ;; Initialize
        (send-jsonrpc proc
                      {:jsonrpc "2.0"
                       :id 1
                       :method "initialize"
                       :params {:protocolVersion "2025-06-18"
                                :capabilities {}
                                :clientInfo {:name "test-client"
                                             :version "1.0.0"}}})
        (send-jsonrpc proc
                      {:jsonrpc "2.0"
                       :method "initialized"})

        (testing "can call select-tasks tool"
          (let [response (send-jsonrpc proc
                                       {:jsonrpc "2.0"
                                        :id 2
                                        :method "tools/call"
                                        :params {:name "select-tasks"
                                                 :arguments {:limit 5}}})]
            (is (some? response))
            (is (nil? (:error response))
                "Tool call should not return error")
            (is (vector? (get-in response [:result :content]))
                "Tool response should contain content array")))

        (finally
          (stop-server proc))))))

(deftest ^:native-binary ^:comprehensive comprehensive-error-handling
  ;; Test server error handling
  (testing "comprehensive-error-handling"
    (let [proc (start-server)]
      (try
        ;; Initialize
        (send-jsonrpc proc
                      {:jsonrpc "2.0"
                       :id 1
                       :method "initialize"
                       :params {:protocolVersion "2025-06-18"
                                :capabilities {}
                                :clientInfo {:name "test-client"
                                             :version "1.0.0"}}})
        (send-jsonrpc proc
                      {:jsonrpc "2.0"
                       :method "initialized"})

        (testing "unknown method returns error"
          (let [response (send-jsonrpc proc
                                       {:jsonrpc "2.0"
                                        :id 2
                                        :method "unknown/method"})]
            (is (some? (:error response))
                "Unknown method should return error")
            (is (number? (get-in response [:error :code]))
                "Error should have error code")))

        ;; TODO: Re-enable when mcp-clj properly handles invalid tool errors
        ;; in protocol version 2025-06-18
        ;; (testing "invalid tool name returns error"
        ;;   (let [response (send-jsonrpc proc
        ;;                                {:jsonrpc "2.0"
        ;;                                 :id 3
        ;;                                 :method "tools/call"
        ;;                                 :params {:name "nonexistent-tool"
        ;;                                          :arguments {}}})]
        ;;     (is (some? (:error response))
        ;;         "Invalid tool should return error")))

        (finally
          (stop-server proc))))))

(deftest ^:native-binary ^:comprehensive comprehensive-startup-performance
  ;; Measure server startup time
  (testing "comprehensive-startup-performance"
    (testing "server starts quickly"
      (let [start (System/nanoTime)
            proc (start-server)
            _ (send-jsonrpc proc
                            {:jsonrpc "2.0"
                             :id 1
                             :method "initialize"
                             :params {:protocolVersion "2025-06-18"
                                      :capabilities {}
                                      :clientInfo {:name "test-client"
                                                   :version "1.0.0"}}})
            elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)]
        (try
          (is (< elapsed-ms 1000)
              (str "Server should initialize in under 1000ms (took " elapsed-ms "ms)"))
          (finally
            (stop-server proc)))))))

(deftest ^:native-binary test-branch-and-worktree-management-resources
  ;; Verify native binary includes branch and worktree management resources
  ;; and can load them when those features are enabled in config
  (testing "test-branch-and-worktree-management-resources"
    (testing "server starts with branch and worktree management enabled"
      ;; Override config to enable branch and worktree management
      (spit (io/file *test-dir* ".mcp-tasks.edn")
            "{:branch-management? true\n :worktree-management? true}")

      ;; Start server
      (let [proc (process/process [*binary-path*]
                                  {:dir *test-dir*
                                   :in :stream
                                   :out :stream
                                   :err :stream})]
        (try
          ;; Initialize
          (let [init-response (send-jsonrpc proc
                                            {:jsonrpc "2.0"
                                             :id 1
                                             :method "initialize"
                                             :params {:protocolVersion "2025-06-18"
                                                      :capabilities {}
                                                      :clientInfo {:name "test-client"
                                                                   :version "1.0.0"}}})]
            (when-not init-response
              ;; Print stderr if server didn't respond
              (let [stderr-reader (io/reader (:err proc))]
                (println "\n=== Server stderr output ===")
                (doseq [line (line-seq stderr-reader)]
                  (println line))
                (println "=== End stderr output ===\n")))
            (is (some? init-response)
                "Server should start successfully with management features enabled")
            (is (nil? (:error init-response))
                "Initialize should not return error"))

          ;; Send initialized notification
          (send-jsonrpc proc
                        {:jsonrpc "2.0"
                         :method "initialized"})

          ;; List prompts and verify they're available
          (testing "prompts list includes story prompts"
            (let [prompts-response (send-jsonrpc proc
                                                 {:jsonrpc "2.0"
                                                  :id 2
                                                  :method "prompts/list"})]
              (is (some? prompts-response)
                  "Server should respond to prompts/list")
              (is (vector? (get-in prompts-response [:result :prompts]))
                  "Response should contain prompts array")
              (is (some #(= "execute-story-child" (:name %))
                        (get-in prompts-response [:result :prompts]))
                  "Prompts should include execute-story-child")))

          ;; Get execute-story-child prompt and verify it includes management instructions
          (testing "execute-story-child prompt includes management instructions"
            (let [prompt-response (send-jsonrpc proc
                                                {:jsonrpc "2.0"
                                                 :id 3
                                                 :method "prompts/get"
                                                 :params {:name "execute-story-child"
                                                          :arguments {}}})]
              (is (some? prompt-response)
                  "Server should respond to prompts/get")
              (is (nil? (:error prompt-response))
                  "prompts/get should not return error")
              (let [messages (get-in prompt-response [:result :messages])]
                (is (vector? messages)
                    "Prompt should contain messages array")
                (is (pos? (count messages))
                    "Prompt should have at least one message")
                ;; Check that the content includes branch management text
                (let [content (get-in messages [0 :content :text])]
                  (is (string? content)
                      "Message should have text content")
                  (is (re-find #"branch" (clojure.string/lower-case content))
                      "Prompt content should mention branch management")))))

          (finally
            (stop-server proc)))))))
