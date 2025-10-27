(ns mcp-tasks.integration-test-fixtures
  "Common test fixtures and utilities for integration tests."
  (:require
    [babashka.fs :as fs]
    [clojure.java.io :as io]
    [clojure.java.shell :as sh]
    [mcp-clj.in-memory-transport.shared :as shared]
    [mcp-clj.mcp-client.core :as mcp-client]
    [mcp-clj.mcp-server.core :as mcp-server]
    [mcp-tasks.config :as config]
    [mcp-tasks.main :as main]))

(def ^:dynamic *test-project-dir* nil)

(defn test-project-dir
  "Get the current test project directory from dynamic binding."
  []
  *test-project-dir*)

(defn cleanup-test-project
  [dir]
  (when (and dir (fs/exists? dir))
    (doseq [file (reverse (file-seq (io/file dir)))]
      (fs/delete file))))

(defn setup-test-project
  []
  (let [temp-dir (str (fs/create-temp-dir {:prefix "mcp-tasks-test-"}))
        mcp-tasks-dir (io/file temp-dir ".mcp-tasks")
        tasks-dir (io/file mcp-tasks-dir "tasks")
        prompts-dir (io/file mcp-tasks-dir "prompts")]
    (.mkdirs tasks-dir)
    (.mkdirs prompts-dir)
    ;; Create a simple.md file in prompts dir with proper frontmatter
    (spit (io/file prompts-dir "simple.md")
          "---\ndescription: Test category for simple tasks\n---\nTest execution instructions\n")
    ;; Also create a simple.md in tasks for backward compatibility with other tests
    (spit (io/file tasks-dir "simple.md") "- [ ] test task\n")
    temp-dir))

(defn init-test-git-repo
  "Initialize a real git repository in the test .mcp-tasks directory.

  This ensures git commands stay isolated to the test directory.
  
  Configures pull.rebase=false to prevent rebase-related failures when pulling
  with unstaged changes (common in test scenarios). This overrides any global
  git configuration that may have pull.rebase=true."
  []
  (let [git-dir (io/file (test-project-dir) ".mcp-tasks")]
    (sh/sh "git" "init" (.getAbsolutePath git-dir))
    (sh/sh "git" "-C" (.getAbsolutePath git-dir) "config" "user.email" "test@example.com")
    (sh/sh "git" "-C" (.getAbsolutePath git-dir) "config" "user.name" "Test User")
    ;; Explicitly disable rebase to avoid "unstaged changes" errors in tests
    ;; This overrides any global pull.rebase=true configuration
    (sh/sh "git" "-C" (.getAbsolutePath git-dir) "config" "pull.rebase" "false")))

(defn write-config-file
  [content]
  (when content
    (spit (io/file (test-project-dir) ".mcp-tasks.edn") content)))

(defn with-test-project
  [f]
  (let [temp-dir (setup-test-project)]
    (try
      (binding [*test-project-dir* temp-dir]
        (f))
      (finally
        (cleanup-test-project temp-dir)))))

(defn load-test-config
  "Load config for test, using test-project-dir as the config path"
  []
  (let [dir (test-project-dir)
        {:keys [raw-config config-dir]} (config/read-config dir)
        resolved-config (config/resolve-config config-dir raw-config)]
    (config/validate-startup config-dir resolved-config)
    resolved-config))

(defn create-test-server-and-client
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
