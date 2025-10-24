(ns mcp-tasks.config-test
  (:require
    [babashka.fs :as fs]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-tasks.config :as sut]))

(def test-project-dir "test-resources/config-test")

(defn- cleanup-test-project
  []
  (when (fs/exists? test-project-dir)
    (fs/delete-tree test-project-dir)))

(defn- setup-test-project
  []
  (cleanup-test-project)
  (fs/create-dirs test-project-dir))

(defn- write-config-file
  [content]
  (spit (str test-project-dir "/.mcp-tasks.edn") content))

(defn- with-test-project
  [f]
  (setup-test-project)
  (try
    (f)
    (finally
      (cleanup-test-project))))

(use-fixtures :each with-test-project)

(deftest validate-config-with-valid-maps
  ;; Test that validate-config accepts valid config maps with various structures
  (testing "validate-config"
    (testing "accepts empty config"
      (is (= {} (sut/validate-config {}))))

    (testing "accepts config with use-git? true"
      (is (= {:use-git? true} (sut/validate-config {:use-git? true}))))

    (testing "accepts config with use-git? false"
      (is (= {:use-git? false} (sut/validate-config {:use-git? false}))))

    (testing "accepts config with branch-management? true"
      (is (= {:branch-management? true}
             (sut/validate-config {:branch-management? true}))))

    (testing "accepts config with branch-management? false"
      (is (= {:branch-management? false}
             (sut/validate-config {:branch-management? false}))))

    (testing "accepts config with worktree-management? true"
      (is (= {:worktree-management? true}
             (sut/validate-config {:worktree-management? true}))))

    (testing "accepts config with worktree-management? false"
      (is (= {:worktree-management? false}
             (sut/validate-config {:worktree-management? false}))))

    (testing "accepts config with both use-git? and branch-management?"
      (is (= {:use-git? true :branch-management? false}
             (sut/validate-config {:use-git? true :branch-management? false}))))

    (testing "accepts config with worktree-prefix :project-name"
      (is (= {:worktree-prefix :project-name}
             (sut/validate-config {:worktree-prefix :project-name}))))

    (testing "accepts config with worktree-prefix :none"
      (is (= {:worktree-prefix :none}
             (sut/validate-config {:worktree-prefix :none}))))

    (testing "accepts config with positive lock-poll-interval-ms"
      (is (= {:lock-poll-interval-ms 50}
             (sut/validate-config {:lock-poll-interval-ms 50}))))

    (testing "accepts config with unknown keys for forward compatibility"
      (is (= {:use-git? true :unknown-key "value"}
             (sut/validate-config {:use-git? true :unknown-key "value"}))))))

(deftest validate-config-accepts-tasks-dir
  ;; Test that validate-config accepts valid :tasks-dir strings
  (testing "validate-config"
    (testing "accepts config with :tasks-dir string"
      (is (= {:tasks-dir ".mcp-tasks"}
             (sut/validate-config {:tasks-dir ".mcp-tasks"}))))

    (testing "accepts config with absolute :tasks-dir path"
      (is (= {:tasks-dir "/home/user/.mcp-tasks"}
             (sut/validate-config {:tasks-dir "/home/user/.mcp-tasks"}))))

    (testing "accepts config with relative :tasks-dir path"
      (is (= {:tasks-dir "../shared-tasks"}
             (sut/validate-config {:tasks-dir "../shared-tasks"}))))))

(deftest validate-config-rejects-invalid-structures
  ;; Test that validate-config throws clear errors for invalid structures
  (testing "validate-config"
    (testing "rejects non-map config"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Config must be a map"
            (sut/validate-config "not a map")))
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Config must be a map"
            (sut/validate-config []))))

    (testing "rejects non-boolean use-git? value"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Expected boolean for :use-git\?, got .*String"
            (sut/validate-config {:use-git? "true"})))
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Expected boolean for :use-git\?, got .*Long"
            (sut/validate-config {:use-git? 1}))))

    (testing "rejects non-boolean branch-management? value"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Expected boolean for :branch-management\?, got .*String"
            (sut/validate-config {:branch-management? "true"})))
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Expected boolean for :branch-management\?, got .*Long"
            (sut/validate-config {:branch-management? 1}))))

    (testing "rejects non-boolean worktree-management? value"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Expected boolean for :worktree-management\?, got .*String"
            (sut/validate-config {:worktree-management? "true"})))
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Expected boolean for :worktree-management\?, got .*Long"
            (sut/validate-config {:worktree-management? 1}))))

    (testing "rejects non-keyword worktree-prefix value"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Expected keyword for :worktree-prefix, got .*String"
            (sut/validate-config {:worktree-prefix "project-name"})))
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Expected keyword for :worktree-prefix, got .*Boolean"
            (sut/validate-config {:worktree-prefix true}))))

    (testing "rejects invalid keyword worktree-prefix value"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Invalid value for :worktree-prefix, must be :project-name or :none"
            (sut/validate-config {:worktree-prefix :custom}))))

    (testing "rejects non-integer lock-poll-interval-ms value"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Expected integer for :lock-poll-interval-ms, got .*String"
            (sut/validate-config {:lock-poll-interval-ms "100"})))
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Expected integer for :lock-poll-interval-ms, got .*Double"
            (sut/validate-config {:lock-poll-interval-ms 100.5}))))

    (testing "rejects non-positive lock-poll-interval-ms value"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Value for :lock-poll-interval-ms must be positive"
            (sut/validate-config {:lock-poll-interval-ms 0})))
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Value for :lock-poll-interval-ms must be positive"
            (sut/validate-config {:lock-poll-interval-ms -100}))))))

(deftest validate-config-rejects-invalid-tasks-dir
  ;; Test that validate-config rejects non-string :tasks-dir values
  (testing "validate-config"
    (testing "rejects non-string :tasks-dir value"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Expected string for :tasks-dir, got .*Long"
            (sut/validate-config {:tasks-dir 123})))
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Expected string for :tasks-dir, got .*Boolean"
            (sut/validate-config {:tasks-dir true})))
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Expected string for :tasks-dir, got .*Keyword"
            (sut/validate-config {:tasks-dir :mcp-tasks}))))))

(deftest validate-config-error-data-structure
  ;; Test that validation errors include structured data for programmatic handling
  (testing "validate-config error data"
    (testing "includes type and config for non-map"
      (try
        (sut/validate-config "not a map")
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :invalid-config (:type (ex-data e))))
          (is (= "not a map" (:config (ex-data e)))))))

    (testing "includes detailed data for invalid type"
      (try
        (sut/validate-config {:use-git? "true"})
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :invalid-config-type (:type (ex-data e))))
          (is (= :use-git? (:key (ex-data e))))
          (is (= "true" (:value (ex-data e))))
          (is (= 'boolean? (:expected (ex-data e)))))))))

(deftest find-config-file-in-current-directory
  ;; Test that find-config-file finds config in the start directory
  (testing "find-config-file"
    (testing "finds config in current directory"
      (write-config-file "{:use-git? true}")
      (let [result (sut/find-config-file test-project-dir)
            canonical-dir (str (fs/canonicalize test-project-dir))]
        (is (some? result))
        (is (= canonical-dir (:config-dir result)))
        (is (string? (:config-file result)))
        (is (.endsWith (:config-file result) ".mcp-tasks.edn"))))))

(deftest find-config-file-in-parent-directory
  ;; Test that find-config-file traverses up to find config in parent directory
  (testing "find-config-file"
    (testing "finds config in parent directory"
      (write-config-file "{}")
      (let [subdir (str test-project-dir "/subdir")
            _ (fs/create-dirs subdir)
            result (sut/find-config-file subdir)
            canonical-dir (str (fs/canonicalize test-project-dir))]
        (is (some? result))
        (is (= canonical-dir (:config-dir result)))))))

(deftest find-config-file-multiple-levels-up
  ;; Test that find-config-file can traverse multiple directory levels
  (testing "find-config-file"
    (testing "finds config multiple levels up"
      (write-config-file "{}")
      (let [deep-subdir (str test-project-dir "/a/b/c")
            _ (fs/create-dirs deep-subdir)
            result (sut/find-config-file deep-subdir)
            canonical-dir (str (fs/canonicalize test-project-dir))]
        (is (some? result))
        (is (= canonical-dir (:config-dir result)))))))

(deftest find-config-file-returns-nil-at-root
  ;; Test that find-config-file returns nil when reaching filesystem root without finding config
  (testing "find-config-file"
    (testing "returns nil when reaching filesystem root"
      ;; Start from a temp dir without .mcp-tasks.edn in any parent
      (let [temp-dir (str (fs/create-temp-dir))
            result (sut/find-config-file temp-dir)]
        (is (nil? result))
        ;; Clean up
        (fs/delete-tree temp-dir)))))

(deftest find-config-file-resolves-symlinks
  ;; Test that find-config-file resolves symlinks correctly
  (testing "find-config-file"
    (testing "resolves symlinks in paths"
      (write-config-file "{}")
      (let [link-path (str test-project-dir "/link-to-subdir")
            subdir (str test-project-dir "/subdir")
            _ (fs/create-dirs subdir)
            _ (fs/create-sym-link link-path subdir)
            result (sut/find-config-file link-path)
            canonical-dir (str (fs/canonicalize test-project-dir))]
        (is (some? result))
        ;; The canonical config-dir should be test-project-dir, not the symlink path
        (is (= canonical-dir (:config-dir result)))
        ;; Clean up symlink
        (fs/delete link-path)))))

(deftest read-config-with-missing-file
  ;; Test that read-config returns empty config when config file doesn't exist
  (testing "read-config"
    (testing "returns empty config when file doesn't exist"
      ;; Use a temp dir that won't have any .mcp-tasks.edn in parent dirs
      (let [temp-dir (str (fs/create-temp-dir))
            result (sut/read-config temp-dir)]
        (is (map? result))
        (is (= {} (:raw-config result)))
        (is (string? (:config-dir result)))
        ;; Clean up
        (fs/delete-tree temp-dir)))))

(deftest read-config-with-valid-files
  ;; Test that read-config successfully parses valid config files
  (testing "read-config"
    (testing "reads and validates empty config"
      (write-config-file "{}")
      (let [result (sut/read-config test-project-dir)]
        (is (= {} (:raw-config result)))
        (is (string? (:config-dir result)))))

    (testing "reads and validates config with use-git? true"
      (write-config-file "{:use-git? true}")
      (let [result (sut/read-config test-project-dir)]
        (is (= {:use-git? true} (:raw-config result)))
        (is (string? (:config-dir result)))))

    (testing "reads and validates config with use-git? false"
      (write-config-file "{:use-git? false}")
      (let [result (sut/read-config test-project-dir)]
        (is (= {:use-git? false} (:raw-config result)))
        (is (string? (:config-dir result)))))

    (testing "reads and validates config with extra keys"
      (write-config-file "{:use-git? true :other-key \"value\"}")
      (let [result (sut/read-config test-project-dir)]
        (is (= {:use-git? true :other-key "value"} (:raw-config result)))
        (is (string? (:config-dir result)))))))

(deftest read-config-with-malformed-edn
  ;; Test that read-config provides clear errors for malformed EDN
  (testing "read-config"
    (testing "throws clear error for malformed EDN"
      (write-config-file "{:use-git? true")
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Failed to parse \.mcp-tasks\.edn:"
            (sut/read-config test-project-dir))))

    (testing "includes error type and file path in ex-data"
      (write-config-file "{:key")
      (try
        (sut/read-config test-project-dir)
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :malformed-edn (:type (ex-data e))))
          (is (string? (:file (ex-data e))))
          (is (.endsWith (:file (ex-data e)) ".mcp-tasks.edn")))))))

(deftest read-config-with-invalid-schema
  ;; Test that read-config validates schema and provides clear errors
  (testing "read-config"
    (testing "throws clear error for invalid use-git? type"
      (write-config-file "{:use-git? \"true\"}")
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Expected boolean for :use-git\?"
            (sut/read-config test-project-dir))))

    (testing "includes validation error details in ex-data"
      (write-config-file "{:use-git? 123}")
      (try
        (sut/read-config test-project-dir)
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :invalid-config-type (:type (ex-data e))))
          (is (= :use-git? (:key (ex-data e))))
          (is (= 123 (:value (ex-data e)))))))))

(deftest git-repo-exists-detects-git-directory
  ;; Test that git-repo-exists? correctly detects presence of .mcp-tasks/.git
  (testing "git-repo-exists?"
    (testing "returns false when .mcp-tasks/.git doesn't exist"
      (is (false? (sut/git-repo-exists? (str (fs/canonicalize test-project-dir))))))

    (testing "returns true when .mcp-tasks/.git exists"
      (fs/create-dirs (str test-project-dir "/.mcp-tasks/.git"))
      (is (true? (sut/git-repo-exists? (str (fs/canonicalize test-project-dir))))))))

(deftest determine-git-mode-with-explicit-config
  ;; Test that explicit config values take precedence over auto-detection
  (testing "determine-git-mode with explicit config"
    (let [config-dir (str (fs/canonicalize test-project-dir))]
      (testing "returns true when config has :use-git? true"
        (is (true? (sut/determine-git-mode config-dir {:use-git? true}))))

      (testing "returns false when config has :use-git? false"
        (is (false? (sut/determine-git-mode config-dir {:use-git? false}))))

      (testing "explicit true overrides missing git repo"
        (is (false? (sut/git-repo-exists? config-dir)))
        (is (true? (sut/determine-git-mode config-dir {:use-git? true}))))

      (testing "explicit false overrides existing git repo"
        (fs/create-dirs (str test-project-dir "/.mcp-tasks/.git"))
        (is (true? (sut/git-repo-exists? config-dir)))
        (is (false? (sut/determine-git-mode config-dir {:use-git? false})))))))

(deftest determine-git-mode-with-auto-detection
  ;; Test that auto-detection works when no explicit config is provided
  (testing "determine-git-mode with auto-detection"
    (let [config-dir (str (fs/canonicalize test-project-dir))]
      (testing "returns false when no config and no git repo"
        (is (false? (sut/determine-git-mode config-dir {}))))

      (testing "returns true when no config but git repo exists"
        (fs/create-dirs (str test-project-dir "/.mcp-tasks/.git"))
        (is (true? (sut/determine-git-mode config-dir {})))))))

(deftest resolve-config-adds-use-git
  ;; Test that resolve-config adds :use-git?, :base-dir, :resolved-tasks-dir, and :worktree-prefix to config map
  (testing "resolve-config"
    (let [canonical-base-dir (str (fs/canonicalize test-project-dir))]
      (testing "adds :use-git? false, :base-dir, :resolved-tasks-dir, :worktree-prefix  amd :lock-timeout-ms when no config and no git repo"
        (let [result (sut/resolve-config canonical-base-dir {})]
          (is (= false (:use-git? result)))
          (is (= canonical-base-dir (:base-dir result)))
          (is (= canonical-base-dir (:main-repo-dir result)))
          (is (= :project-name (:worktree-prefix result)))
          (is (string? (:resolved-tasks-dir result)))
          (is (int? (:lock-timeout-ms result)))
          (is (int? (:lock-poll-interval-ms result)))))

      (testing "adds :use-git? true, :base-dir, :lock-timeout-ms, :resolved-tasks-dir, and :worktree-prefix when no config but git repo exists"
        (fs/create-dirs (str test-project-dir "/.mcp-tasks/.git"))
        (let [result (sut/resolve-config canonical-base-dir {})]
          (is (= true (:use-git? result)))
          (is (= canonical-base-dir (:base-dir result)))
          (is (= canonical-base-dir (:main-repo-dir result)))
          (is (= :project-name (:worktree-prefix result)))
          (is (string? (:resolved-tasks-dir result)))
          (is (int? (:lock-poll-interval-ms result)))
          (is (int? (:lock-timeout-ms result)))))

      (testing "preserves explicit :use-git? true and adds :base-dir, :lock-timeout-ms, :resolved-tasks-dir, and :worktree-prefix"
        (let [result (sut/resolve-config canonical-base-dir {:use-git? true})]
          (is (= true (:use-git? result)))
          (is (= canonical-base-dir (:base-dir result)))
          (is (= canonical-base-dir (:main-repo-dir result)))
          (is (= :project-name (:worktree-prefix result)))
          (is (string? (:resolved-tasks-dir result)))
          (is (int? (:lock-poll-interval-ms result)))
          (is (int? (:lock-timeout-ms result)))))

      (testing "preserves explicit :use-git? false even with git repo and adds :base-dir, :resolved-tasks-dir, and :worktree-prefix"
        (fs/create-dirs (str test-project-dir "/.mcp-tasks/.git"))
        (let [result (sut/resolve-config canonical-base-dir {:use-git? false})]
          (is (= false (:use-git? result)))
          (is (= canonical-base-dir (:base-dir result)))
          (is (= :project-name (:worktree-prefix result)))
          (is (string? (:resolved-tasks-dir result)))
          (is (int? (:lock-poll-interval-ms result)))
          (is (int? (:lock-timeout-ms result)))))

      (testing "preserves other config keys and adds :use-git?, :base-dir, :resolved-tasks-dir, and :worktree-prefix"
        (cleanup-test-project)
        (setup-test-project)
        (let [result (sut/resolve-config canonical-base-dir {:other-key "value"})]
          (is (= false (:use-git? result)))
          (is (= canonical-base-dir (:base-dir result)))
          (is (= :project-name (:worktree-prefix result)))
          (is (= canonical-base-dir (:main-repo-dir result)))
          (is (= "value" (:other-key result)))
          (is (string? (:resolved-tasks-dir result)))
          (is (int? (:lock-poll-interval-ms result)))
          (is (int? (:lock-timeout-ms result))))))))

(deftest resolve-config-auto-enables-branch-management
  ;; Test that resolve-config auto-enables :branch-management? when :worktree-management? is true
  (testing "resolve-config auto-enables branch management"
    (let [canonical-base-dir (str (fs/canonicalize test-project-dir))]
      (testing "enables :branch-management? when :worktree-management? is true"
        (let [result (sut/resolve-config canonical-base-dir {:worktree-management? true})]
          (is (= true (:worktree-management? result)))
          (is (= true (:branch-management? result)))
          (is (= false (:use-git? result)))
          (is (= canonical-base-dir (:base-dir result)))
          (is (= canonical-base-dir (:main-repo-dir result)))
          (is (= :project-name (:worktree-prefix result)))
          (is (string? (:resolved-tasks-dir result)))
          (is (int? (:lock-poll-interval-ms result)))
          (is (int? (:lock-timeout-ms result)))))

      (testing "preserves explicit :branch-management? false when :worktree-management? is false"
        (let [result (sut/resolve-config canonical-base-dir {:worktree-management? false
                                                             :branch-management? false})]
          (is (= false (:worktree-management? result)))
          (is (= false (:branch-management? result)))
          (is (= false (:use-git? result)))
          (is (= canonical-base-dir (:base-dir result)))
          (is (= canonical-base-dir (:main-repo-dir result)))
          (is (= :project-name (:worktree-prefix result)))
          (is (string? (:resolved-tasks-dir result)))
          (is (int? (:lock-poll-interval-ms result)))
          (is (int? (:lock-timeout-ms result)))))

      (testing "overrides :branch-management? false when :worktree-management? is true"
        (let [result (sut/resolve-config canonical-base-dir {:worktree-management? true
                                                             :branch-management? false})]
          (is (= true (:worktree-management? result)))
          (is (= true (:branch-management? result)))
          (is (= false (:use-git? result)))
          (is (= canonical-base-dir (:base-dir result)))
          (is (= canonical-base-dir (:main-repo-dir result)))
          (is (= :project-name (:worktree-prefix result)))
          (is (string? (:resolved-tasks-dir result)))
          (is (int? (:lock-poll-interval-ms result)))
          (is (int? (:lock-timeout-ms result))))))))

(deftest resolve-config-canonicalizes-base-dir
  ;; Test that resolve-config always returns an absolute path for :base-dir
  ;; This prevents bugs where relative paths like "." cause fs/parent to return nil/empty
  (testing "resolve-config canonicalizes base-dir"
    (testing "converts relative path '.' to absolute path"
      (let [result (sut/resolve-config "." {})
            base-dir (:base-dir result)]
        (is (string? base-dir))
        (is (not (= "." base-dir)))
        (is (fs/absolute? base-dir))
        ;; Should be an actual absolute path (starts with /)
        (is (re-matches #"/.*" base-dir))))

    (testing "canonicalizes relative paths to absolute paths"
      (let [result (sut/resolve-config test-project-dir {})
            base-dir (:base-dir result)]
        ;; test-project-dir is relative, so it should be canonicalized
        (is (string? base-dir))
        (is (fs/absolute? base-dir))
        (is (= (str (fs/canonicalize test-project-dir)) base-dir))))))

(deftest in-worktree-detection
  ;; Test detection of git worktree environments
  (testing "in-worktree?"
    (testing "returns false when .git is a directory (main repo)"
      (fs/create-dirs (str test-project-dir "/.git"))
      (is (false? (sut/in-worktree? test-project-dir))))

    (testing "returns true when .git is a file (worktree)"
      (cleanup-test-project)
      (setup-test-project)
      (spit (str test-project-dir "/.git") "gitdir: /some/path/.git/worktrees/test")
      (is (true? (sut/in-worktree? test-project-dir))))

    (testing "returns false when .git does not exist"
      (cleanup-test-project)
      (setup-test-project)
      (is (false? (sut/in-worktree? test-project-dir))))))

(deftest find-main-repo-from-worktree
  ;; Test extraction of main repository path from worktree .git file
  (testing "find-main-repo"
    (testing "extracts main repo path from .git file"
      (let [main-repo-path (str (fs/create-temp-dir {:prefix "main-repo-"}))
            worktree-name "test-worktree"
            gitdir-path (str main-repo-path "/.git/worktrees/" worktree-name)]
        (fs/create-dirs gitdir-path)
        (spit (str test-project-dir "/.git") (str "gitdir: " gitdir-path))
        (let [result (sut/find-main-repo test-project-dir)]
          (is (= (str (fs/canonicalize main-repo-path)) result)))
        (fs/delete-tree main-repo-path)))

    (testing "handles paths with spaces"
      (let [main-repo-path (str (fs/create-temp-dir {:prefix "main repo with spaces-"}))
            worktree-name "test-worktree"
            gitdir-path (str main-repo-path "/.git/worktrees/" worktree-name)]
        (fs/create-dirs gitdir-path)
        (spit (str test-project-dir "/.git") (str "gitdir: " gitdir-path))
        (let [result (sut/find-main-repo test-project-dir)]
          (is (= (str (fs/canonicalize main-repo-path)) result)))
        (fs/delete-tree main-repo-path)))))

(deftest find-main-repo-with-malformed-git-file
  ;; Test that find-main-repo provides clear error message for malformed .git files
  (testing "find-main-repo with malformed .git file"
    (testing "throws clear error when .git file doesn't contain gitdir line"
      (spit (str test-project-dir "/.git") "this is malformed content")
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Malformed \.git file in worktree\. Expected format: 'gitdir: /path/to/\.git/worktrees/name'"
            (sut/find-main-repo test-project-dir))))

    (testing "includes error details in ex-data"
      (spit (str test-project-dir "/.git") "invalid content")
      (try
        (sut/find-main-repo test-project-dir)
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (string? (:worktree-dir (ex-data e))))
          (is (string? (:git-file (ex-data e))))
          (is (= "invalid content" (:content (ex-data e)))))))

    (testing "throws clear error for empty .git file"
      (spit (str test-project-dir "/.git") "")
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Malformed \.git file in worktree\. Expected format:"
            (sut/find-main-repo test-project-dir))))

    (testing "throws clear error when gitdir keyword is present but malformed"
      (spit (str test-project-dir "/.git") "gitdir /missing/colon")
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Malformed \.git file in worktree\. Expected format:"
            (sut/find-main-repo test-project-dir))))))

(deftest resolve-config-includes-main-repo-dir
  ;; Test that resolve-config includes :main-repo-dir field
  (testing "resolve-config includes :main-repo-dir"
    (let [canonical-base-dir (str (fs/canonicalize test-project-dir))]
      (testing "sets :main-repo-dir equal to :base-dir when not in worktree"
        ;; Explicitly pass start-dir=base-dir to avoid using actual CWD
        (let [result (sut/resolve-config canonical-base-dir {} canonical-base-dir)]
          (is (contains? result :main-repo-dir))
          (is (= canonical-base-dir (:main-repo-dir result)))
          (is (= (:base-dir result) (:main-repo-dir result)))))

      (testing "sets :main-repo-dir to main repo path when config-dir is in worktree"
        (let [main-repo-path (str (fs/create-temp-dir {:prefix "main-repo-"}))
              worktree-name "test-worktree"
              gitdir-path (str main-repo-path "/.git/worktrees/" worktree-name)]
          (try
            (fs/create-dirs gitdir-path)
            (spit (str test-project-dir "/.git") (str "gitdir: " gitdir-path))
            ;; Explicitly pass start-dir=base-dir
            (let [result (sut/resolve-config canonical-base-dir {} canonical-base-dir)
                  expected-main-repo (str (fs/canonicalize main-repo-path))]
              (is (contains? result :main-repo-dir))
              (is (= expected-main-repo (:main-repo-dir result)))
              (is (= canonical-base-dir (:base-dir result)))
              (is (not= (:base-dir result) (:main-repo-dir result))))
            (finally
              ;; Clean up .git file before deleting temp dir
              (fs/delete-if-exists (str test-project-dir "/.git"))
              (fs/delete-tree main-repo-path)))))

      (testing "sets :main-repo-dir from start-dir when start-dir is worktree but config-dir is not"
        ;; This tests the case where:
        ;; - Config file is in a parent directory that is NOT a git repo
        ;; - start-dir is a worktree
        ;; - resolve-config should detect start-dir's worktree status and find main repo
        (let [main-repo-path (str (fs/create-temp-dir {:prefix "main-repo-"}))
              worktree-dir (str test-project-dir "/worktree-subdir")
              worktree-name "start-dir-worktree"
              gitdir-path (str main-repo-path "/.git/worktrees/" worktree-name)]
          (try
            (fs/create-dirs gitdir-path)
            (fs/create-dirs worktree-dir)
            (spit (str worktree-dir "/.git") (str "gitdir: " gitdir-path))

            ;; Pass worktree-dir as start-dir, canonical-base-dir as config-dir
            (let [result (sut/resolve-config canonical-base-dir {} worktree-dir)
                  expected-main-repo (str (fs/canonicalize main-repo-path))]
              (is (contains? result :main-repo-dir))
              (is (= expected-main-repo (:main-repo-dir result)))
              (is (= canonical-base-dir (:base-dir result)))
              (is (not= (:base-dir result) (:main-repo-dir result))))
            (finally
              (fs/delete-tree main-repo-path))))))))

(deftest resolve-tasks-dir-with-default
  ;; Test that resolve-tasks-dir defaults to .mcp-tasks relative to config-dir
  (testing "resolve-tasks-dir with default"
    (let [config-dir (str (fs/canonicalize test-project-dir))
          expected (str (fs/canonicalize (str test-project-dir "/.mcp-tasks")))]
      (testing "returns .mcp-tasks when :tasks-dir not specified"
        (is (= expected (sut/resolve-tasks-dir config-dir {}))))

      (testing "returns .mcp-tasks even if directory doesn't exist"
        ;; Default .mcp-tasks doesn't need to exist yet
        (is (= expected (sut/resolve-tasks-dir config-dir {})))))))

(deftest resolve-tasks-dir-with-relative-path
  ;; Test that resolve-tasks-dir resolves relative paths from config-dir
  (testing "resolve-tasks-dir with relative path"
    (let [config-dir (str (fs/canonicalize test-project-dir))
          tasks-subdir (str test-project-dir "/tasks")]
      (fs/create-dirs tasks-subdir)
      (testing "resolves relative path from config-dir"
        (let [expected (str (fs/canonicalize tasks-subdir))
              result (sut/resolve-tasks-dir config-dir {:tasks-dir "tasks"})]
          (is (= expected result)))))))

(deftest resolve-tasks-dir-with-absolute-path
  ;; Test that resolve-tasks-dir handles absolute paths correctly
  (testing "resolve-tasks-dir with absolute path"
    (let [config-dir (str (fs/canonicalize test-project-dir))
          absolute-dir (str (fs/create-temp-dir))]
      (testing "uses absolute path as-is"
        (let [result (sut/resolve-tasks-dir config-dir {:tasks-dir absolute-dir})]
          (is (= (str (fs/canonicalize absolute-dir)) result))))
      ;; Clean up
      (fs/delete-tree absolute-dir))))

(deftest resolve-tasks-dir-validates-explicit-path-exists
  ;; Test that resolve-tasks-dir throws error when explicit :tasks-dir doesn't exist
  (testing "resolve-tasks-dir validation"
    (let [config-dir (str (fs/canonicalize test-project-dir))]
      (testing "throws error when explicit :tasks-dir doesn't exist"
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"Configured :tasks-dir does not exist: nonexistent"
              (sut/resolve-tasks-dir config-dir {:tasks-dir "nonexistent"}))))

      (testing "includes error details in ex-data"
        (try
          (sut/resolve-tasks-dir config-dir {:tasks-dir "nonexistent"})
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :invalid-config-value (:type (ex-data e))))
            (is (= :tasks-dir (:key (ex-data e))))
            (is (= "nonexistent" (:value (ex-data e))))
            (is (string? (:resolved-path (ex-data e))))))))))

(deftest resolve-tasks-dir-resolves-symlinks
  ;; Test that resolve-tasks-dir resolves symlinks in paths
  (testing "resolve-tasks-dir symlink resolution"
    (let [config-dir (str (fs/canonicalize test-project-dir))
          real-dir (str test-project-dir "/real-tasks")
          link-path (str test-project-dir "/link-to-tasks")]
      (fs/create-dirs real-dir)
      ;; Create symlink with relative target (relative to link's directory)
      (fs/create-sym-link link-path "real-tasks")
      (testing "resolves symlinks to canonical path"
        (let [result (sut/resolve-tasks-dir config-dir {:tasks-dir "link-to-tasks"})
              expected (str (fs/canonicalize real-dir))]
          (is (= expected result))))
      ;; Clean up
      (fs/delete link-path)
      (fs/delete-tree real-dir))))

(deftest resolve-config-includes-resolved-tasks-dir
  ;; Test that resolve-config includes :resolved-tasks-dir in output
  (testing "resolve-config includes :resolved-tasks-dir"
    (let [config-dir (str (fs/canonicalize test-project-dir))]
      (testing "includes default .mcp-tasks path"
        (let [result (sut/resolve-config config-dir {})
              expected (str (fs/canonicalize (str test-project-dir "/.mcp-tasks")))]
          (is (contains? result :resolved-tasks-dir))
          (is (= expected (:resolved-tasks-dir result)))))

      (testing "includes custom relative path"
        (let [tasks-dir (str test-project-dir "/custom-tasks")]
          (fs/create-dirs tasks-dir)
          (let [result (sut/resolve-config config-dir {:tasks-dir "custom-tasks"})
                expected (str (fs/canonicalize tasks-dir))]
            (is (= expected (:resolved-tasks-dir result))))))

      (testing "includes custom absolute path"
        (let [absolute-dir (str (fs/create-temp-dir))
              result (sut/resolve-config config-dir {:tasks-dir absolute-dir})
              expected (str (fs/canonicalize absolute-dir))]
          (is (= expected (:resolved-tasks-dir result)))
          ;; Clean up
          (fs/delete-tree absolute-dir))))))

(deftest validate-git-repo-with-git-disabled
  ;; Test that validation passes when git mode is disabled
  (testing "validate-git-repo with git disabled"
    (let [config-dir (str (fs/canonicalize test-project-dir))]
      (testing "returns nil when :use-git? is false"
        (is (nil? (sut/validate-git-repo config-dir {:use-git? false}))))

      (testing "returns nil when :use-git? is not present"
        (is (nil? (sut/validate-git-repo config-dir {})))))))

(deftest validate-git-repo-with-git-enabled
  ;; Test that validation checks for git repository when git mode is enabled
  (testing "validate-git-repo with git enabled"
    (let [config-dir (str (fs/canonicalize test-project-dir))]
      (testing "returns nil when git repo exists"
        (fs/create-dirs (str test-project-dir "/.mcp-tasks/.git"))
        (is (nil? (sut/validate-git-repo config-dir {:use-git? true}))))

      (testing "throws clear error when git repo missing"
        (cleanup-test-project)
        (setup-test-project)
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"Git mode enabled but \.mcp-tasks/\.git not found"
              (sut/validate-git-repo config-dir {:use-git? true}))))

      (testing "includes error details in ex-data"
        (cleanup-test-project)
        (setup-test-project)
        (try
          (sut/validate-git-repo config-dir {:use-git? true})
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :git-repo-missing (:type (ex-data e))))
            (is (= config-dir (:config-dir (ex-data e))))
            (is (string? (:git-dir (ex-data e))))))))))

(deftest validate-startup-passes-with-valid-config
  ;; Test that startup validation passes with valid configurations
  (testing "validate-startup"
    (let [config-dir (str (fs/canonicalize test-project-dir))]
      (testing "passes with git disabled and no git repo"
        (is (nil? (sut/validate-startup config-dir {:use-git? false}))))

      (testing "passes with git disabled and git repo present"
        (fs/create-dirs (str test-project-dir "/.mcp-tasks/.git"))
        (is (nil? (sut/validate-startup config-dir {:use-git? false}))))

      (testing "passes with git enabled and git repo present"
        (fs/create-dirs (str test-project-dir "/.mcp-tasks/.git"))
        (is (nil? (sut/validate-startup config-dir {:use-git? true})))))))

(deftest validate-startup-fails-with-invalid-config
  ;; Test that startup validation fails with invalid configurations
  (testing "validate-startup"
    (let [config-dir (str (fs/canonicalize test-project-dir))]
      (testing "fails when git enabled but repo missing"
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"Git mode enabled but \.mcp-tasks/\.git not found"
              (sut/validate-startup config-dir {:use-git? true})))))))
