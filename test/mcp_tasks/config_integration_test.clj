(ns mcp-tasks.config-integration-test
  "Integration tests for config discovery and path resolution workflow.
  
  Tests the complete end-to-end behavior of:
  - Config file discovery via directory traversal
  - Path resolution for :tasks-dir
  - Accessing task files through resolved paths"
  (:require
    [babashka.fs :as fs]
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.config :as config]
    [mcp-tasks.tools.helpers :as helpers]))

;; Test Fixtures

(def integration-test-dir "test-resources/config-integration-test")

(defn cleanup-integration-test
  []
  (when (fs/exists? integration-test-dir)
    (fs/delete-tree integration-test-dir)))

(defn setup-integration-test
  []
  (cleanup-integration-test)
  (fs/create-dirs integration-test-dir))

(defmacro with-integration-test
  "Execute body with integration test setup/teardown."
  [& body]
  `(do
     (setup-integration-test)
     (try
       ~@body
       (finally
         (cleanup-integration-test)))))

(defn write-config-at
  "Write a config file at the specified directory."
  [dir config-content]
  (let [config-file (str dir "/.mcp-tasks.edn")]
    (fs/create-dirs dir)
    (spit config-file config-content)))

(defn write-task-file
  "Write a tasks.ednl file at the specified directory."
  [dir content]
  (let [task-file (str dir "/tasks.ednl")]
    (fs/create-dirs dir)
    (spit task-file content)))

;; Integration Tests

(deftest config-in-parent-directory-invoke-from-subdirectory
  ;; Test complete workflow: config in parent, invoke from subdirectory, verify tasks found
  (with-integration-test
    (testing "config-in-parent-directory-invoke-from-subdirectory"
      (testing "discovers config and accesses tasks correctly"
        (let [project-root (str integration-test-dir "/project")
              subdir (str project-root "/src/module")
              tasks-dir (str project-root "/.mcp-tasks")
              _ (write-config-at project-root "{}")
              _ (write-task-file tasks-dir "{:id 1 :title \"Test task\"}")

              ;; Simulate invoking from subdirectory
              config-result (config/read-config subdir)]

          (is (some? config-result) "Should find config")
          (is (= (str (fs/canonicalize project-root))
                 (:config-dir config-result))
              "Config dir should be project root")

          ;; Test that paths resolve correctly for task file access
          (let [resolved-config (config/resolve-config (:config-dir config-result)
                                                       (:raw-config config-result))
                task-path (helpers/task-path resolved-config ["tasks.ednl"])]

            (is (= (str (fs/canonicalize tasks-dir) "/tasks.ednl")
                   (:absolute task-path))
                "Task path should resolve to .mcp-tasks directory")
            (is (fs/exists? (:absolute task-path))
                "Task file should be accessible via resolved path")))))))

(deftest absolute-tasks-dir-pointing-to-separate-location
  ;; Test absolute :tasks-dir pointing to separate location
  (with-integration-test
    (testing "absolute-tasks-dir-pointing-to-separate-location"
      (testing "resolves absolute paths correctly"
        (let [project-root (str integration-test-dir "/project")
              tasks-location (str integration-test-dir "/shared-tasks")
              _ (fs/create-dirs tasks-location)
              tasks-location-canonical (str (fs/canonicalize tasks-location))
              config-content (pr-str {:tasks-dir tasks-location-canonical})
              _ (write-config-at project-root config-content)
              _ (write-task-file tasks-location "{:id 1 :title \"Shared task\"}")

              config-result (config/read-config project-root)]

          (is (some? config-result))

          (let [resolved-config (config/resolve-config (:config-dir config-result)
                                                       (:raw-config config-result))
                task-path (helpers/task-path resolved-config ["tasks.ednl"])]

            (is (= (str tasks-location-canonical "/tasks.ednl")
                   (:absolute task-path))
                "Should use absolute tasks-dir path")
            (is (fs/exists? (:absolute task-path))
                "Task file should exist at absolute path")))))))

(deftest relative-tasks-dir-with-parent-paths
  ;; Test relative :tasks-dir with .. paths
  (with-integration-test
    (testing "relative-tasks-dir-with-parent-paths"
      (testing "resolves relative paths from config file directory"
        (let [project-root (str integration-test-dir "/project")
              config-dir (str project-root "/config")
              tasks-dir (str project-root "/tasks")
              config-content (pr-str {:tasks-dir "../tasks"})
              _ (write-config-at config-dir config-content)
              _ (write-task-file tasks-dir "{:id 1 :title \"Task\"}")

              config-result (config/read-config config-dir)]

          (is (some? config-result))

          (let [resolved-config (config/resolve-config (:config-dir config-result)
                                                       (:raw-config config-result))
                task-path (helpers/task-path resolved-config ["tasks.ednl"])]

            (is (= (str (fs/canonicalize tasks-dir) "/tasks.ednl")
                   (:absolute task-path))
                "Should resolve relative path from config directory")
            (is (fs/exists? (:absolute task-path))
                "Task file should be accessible via resolved path")))))))

(deftest symlinked-project-directory-with-config-discovery
  ;; Test symlinked project directories with config discovery
  (with-integration-test
    (testing "symlinked-project-directory-with-config-discovery"
      (testing "resolves symlinks during config discovery"
        (let [real-project (str integration-test-dir "/real-project")
              symlink-project (str integration-test-dir "/link-project")
              tasks-dir (str real-project "/.mcp-tasks")
              _ (write-config-at real-project "{}")
              _ (write-task-file tasks-dir "{:id 1 :title \"Task\"}")
              ;; Create symlink with relative target
              _ (fs/create-sym-link symlink-project "real-project")
              subdir-in-symlink (str symlink-project "/subdir")
              _ (fs/create-dirs subdir-in-symlink)

              ;; Find config via symlink subdirectory
              config-result (config/read-config subdir-in-symlink)]

          (is (some? config-result))
          (is (= (str (fs/canonicalize real-project))
                 (:config-dir config-result))
              "Should resolve to canonical directory")

          (let [resolved-config (config/resolve-config (:config-dir config-result)
                                                       (:raw-config config-result))
                task-path (helpers/task-path resolved-config ["tasks.ednl"])]

            (is (fs/exists? (:absolute task-path))
                "Task file should be accessible")))))))

(deftest symlinked-tasks-dir-paths
  ;; Test symlinked :tasks-dir paths
  (with-integration-test
    (testing "symlinked-tasks-dir-paths"
      (testing "resolves symlinks in tasks-dir paths"
        (let [project-root (str integration-test-dir "/project")
              real-tasks-dir (str integration-test-dir "/real-tasks")
              link-tasks-dir (str project-root "/link-tasks")
              _ (fs/create-dirs real-tasks-dir)
              _ (write-task-file real-tasks-dir "{:id 1 :title \"Task\"}")
              _ (fs/create-dirs project-root)
              ;; Create symlink with relative target (relative to project-root)
              _ (fs/create-sym-link link-tasks-dir "../real-tasks")
              config-content (pr-str {:tasks-dir "link-tasks"})
              _ (write-config-at project-root config-content)

              config-result (config/read-config project-root)]

          (is (some? config-result))

          (let [resolved-config (config/resolve-config (:config-dir config-result)
                                                       (:raw-config config-result))
                task-path (helpers/task-path resolved-config ["tasks.ednl"])]

            (is (= (str (fs/canonicalize real-tasks-dir) "/tasks.ednl")
                   (:absolute task-path))
                "Should resolve symlink to canonical path")
            (is (fs/exists? (:absolute task-path))
                "Task file should be accessible")))))))

(deftest multiple-subdirectory-levels-between-cwd-and-config
  ;; Test multiple subdirectory levels between CWD and config file
  (with-integration-test
    (testing "multiple-subdirectory-levels-between-cwd-and-config"
      (testing "finds config through deep directory traversal"
        (let [project-root (str integration-test-dir "/project")
              deep-subdir (str project-root "/a/b/c/d/e")
              tasks-dir (str project-root "/.mcp-tasks")
              _ (write-config-at project-root "{}")
              _ (write-task-file tasks-dir "{:id 1 :title \"Task\"}")
              _ (fs/create-dirs deep-subdir)

              config-result (config/read-config deep-subdir)]

          (is (some? config-result))
          (is (= (str (fs/canonicalize project-root))
                 (:config-dir config-result))
              "Should find config 5 levels up")

          (let [resolved-config (config/resolve-config (:config-dir config-result)
                                                       (:raw-config config-result))
                task-path (helpers/task-path resolved-config ["tasks.ednl"])]

            (is (fs/exists? (:absolute task-path))
                "Task file should be accessible from deep subdirectory")))))))

(deftest error-when-tasks-dir-does-not-exist
  ;; Test error messages when :tasks-dir doesn't exist
  (with-integration-test
    (testing "error-when-tasks-dir-does-not-exist"
      (testing "provides clear error for non-existent explicit :tasks-dir"
        (let [project-root (str integration-test-dir "/project")
              non-existent-dir "/this/path/does/not/exist"
              config-content (pr-str {:tasks-dir non-existent-dir})
              _ (write-config-at project-root config-content)

              config-result (config/read-config project-root)]

          (is (thrown-with-msg?
                clojure.lang.ExceptionInfo
                #"Configured :tasks-dir does not exist"
                (config/resolve-config (:config-dir config-result)
                                       (:raw-config config-result)))))))))

(deftest default-mcp-tasks-when-not-specified
  ;; Test default .mcp-tasks behavior when :tasks-dir not specified
  (with-integration-test
    (testing "default-mcp-tasks-when-not-specified"
      (testing "defaults to .mcp-tasks relative to config file"
        (let [project-root (str integration-test-dir "/project")
              tasks-dir (str project-root "/.mcp-tasks")
              _ (write-config-at project-root "{}")
              _ (write-task-file tasks-dir "{:id 1 :title \"Task\"}")

              config-result (config/read-config project-root)]

          (is (some? config-result))

          (let [resolved-config (config/resolve-config (:config-dir config-result)
                                                       (:raw-config config-result))
                task-path (helpers/task-path resolved-config ["tasks.ednl"])]

            (is (= (str (fs/canonicalize tasks-dir) "/tasks.ednl")
                   (:absolute task-path))
                "Should default to .mcp-tasks in config directory")
            (is (fs/exists? (:absolute task-path))
                "Task file should be accessible")))))))

(deftest config-dir-differs-from-cwd
  ;; Test that config file directory differs from CWD works correctly
  (with-integration-test
    (testing "config-dir-differs-from-cwd"
      (testing "handles config dir != CWD correctly"
        (let [project-root (str integration-test-dir "/project")
              subdir (str project-root "/src")
              other-dir (str project-root "/build")
              tasks-dir (str project-root "/.mcp-tasks")
              _ (write-config-at project-root "{}")
              _ (write-task-file tasks-dir "{:id 1 :title \"Task\"}")
              _ (fs/create-dirs subdir)
              _ (fs/create-dirs other-dir)

              ;; Find config from different subdirectories
              config-from-src (config/read-config subdir)
              config-from-build (config/read-config other-dir)]

          (is (some? config-from-src))
          (is (some? config-from-build))
          (is (= (:config-dir config-from-src)
                 (:config-dir config-from-build))
              "Both should find same config directory")

          ;; Both should resolve to same task paths
          (let [resolved-from-src (config/resolve-config
                                    (:config-dir config-from-src)
                                    (:raw-config config-from-src))
                resolved-from-build (config/resolve-config
                                      (:config-dir config-from-build)
                                      (:raw-config config-from-build))
                task-path-src (helpers/task-path resolved-from-src ["tasks.ednl"])
                task-path-build (helpers/task-path resolved-from-build ["tasks.ednl"])]

            (is (= (:absolute task-path-src)
                   (:absolute task-path-build))
                "Task paths should be identical regardless of CWD")
            (is (fs/exists? (:absolute task-path-src))
                "Task file should be accessible from any subdirectory")))))))
