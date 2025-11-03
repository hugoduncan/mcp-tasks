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
    [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:dynamic *test-dir* nil)
(def ^:dynamic *binary-path* nil)

(defn- setup-test-dir
  [test-dir]
  (fs/create-dirs (io/file test-dir ".mcp-tasks"))
  (fs/create-dirs (io/file test-dir ".mcp-tasks/prompts"))
  (spit (io/file test-dir ".mcp-tasks/prompts/simple.md")
        "---\ndescription: Simple tasks\n---\nSimple task execution"))

(defn- binary-test-fixture
  "Test fixture that sets up temporary directory and locates binary.
  Throws an exception to explicitly skip tests if binary is not found."
  [f]
  (let [platform (build/detect-platform)
        binary-name (build/platform-binary-name "mcp-tasks-server" platform)
        binary (io/file "target" binary-name)]
    (when-not (.exists binary)
      (throw (ex-info "Native server binary not found - build with: bb build-native-server"
                      {:type ::binary-not-found
                       :binary-path (.getAbsolutePath binary)})))
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
                                        :params {:protocolVersion "2024-11-05"
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
                         :params {:protocolVersion "2024-11-05"
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
                                        :params {:protocolVersion "2024-11-05"
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
                                        :params {:protocolVersion "2024-11-05"
                                                 :capabilities {}
                                                 :clientInfo {:name "test-client"
                                                              :version "1.0.0"}}})]
            (is (= "mcp-tasks" (get-in response [:result :serverInfo :name])))
            (is (some? (get-in response [:result :capabilities])))
            (is (= "2024-11-05" (get-in response [:result :protocolVersion])))))

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
                       :params {:protocolVersion "2024-11-05"
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
                       :params {:protocolVersion "2024-11-05"
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

        (testing "invalid tool name returns error"
          (let [response (send-jsonrpc proc
                                       {:jsonrpc "2.0"
                                        :id 3
                                        :method "tools/call"
                                        :params {:name "nonexistent-tool"
                                                 :arguments {}}})]
            (is (some? (:error response))
                "Invalid tool should return error")))

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
                             :params {:protocolVersion "2024-11-05"
                                      :capabilities {}
                                      :clientInfo {:name "test-client"
                                                   :version "1.0.0"}}})
            elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)]
        (try
          (is (< elapsed-ms 1000)
              (str "Server should initialize in under 1000ms (took " elapsed-ms "ms)"))
          (finally
            (stop-server proc)))))))
