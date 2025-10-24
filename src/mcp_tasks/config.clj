(ns mcp-tasks.config
  "Configuration management for mcp-tasks"
  (:require
    [babashka.fs :as fs]
    [clojure.edn :as edn]))

(defn validate-config
  "Validates config map structure.
  Returns config if valid, throws ex-info with descriptive error otherwise."
  [config]
  (when-not (map? config)
    (throw (ex-info "Config must be a map"
                    {:type :invalid-config
                     :config config})))
  (when-let [use-git (:use-git? config)]
    (when-not (boolean? use-git)
      (throw (ex-info (str "Expected boolean for :use-git?, got " (type use-git))
                      {:type :invalid-config-type
                       :key :use-git?
                       :value use-git
                       :expected 'boolean?}))))
  (when-let [branch-mgmt (:branch-management? config)]
    (when-not (boolean? branch-mgmt)
      (throw (ex-info (str "Expected boolean for :branch-management?, got " (type branch-mgmt))
                      {:type :invalid-config-type
                       :key :branch-management?
                       :value branch-mgmt
                       :expected 'boolean?}))))
  (when-let [worktree-mgmt (:worktree-management? config)]
    (when-not (boolean? worktree-mgmt)
      (throw (ex-info (str "Expected boolean for :worktree-management?, got " (type worktree-mgmt))
                      {:type :invalid-config-type
                       :key :worktree-management?
                       :value worktree-mgmt
                       :expected 'boolean?}))))
  (when-let [worktree-prefix (:worktree-prefix config)]
    (when-not (keyword? worktree-prefix)
      (throw (ex-info (str "Expected keyword for :worktree-prefix, got " (type worktree-prefix))
                      {:type :invalid-config-type
                       :key :worktree-prefix
                       :value worktree-prefix
                       :expected 'keyword?})))
    (when-not (#{:project-name :none} worktree-prefix)
      (throw (ex-info (str "Invalid value for :worktree-prefix, must be :project-name or :none, got " worktree-prefix)
                      {:type :invalid-config-value
                       :key :worktree-prefix
                       :value worktree-prefix
                       :expected #{:project-name :none}}))))
  (when-let [base-branch (:base-branch config)]
    (when-not (string? base-branch)
      (throw (ex-info (str "Expected string for :base-branch, got " (type base-branch))
                      {:type :invalid-config-type
                       :key :base-branch
                       :value base-branch
                       :expected 'string?})))
    (when (empty? base-branch)
      (throw (ex-info "Value for :base-branch cannot be empty string"
                      {:type :invalid-config-value
                       :key :base-branch
                       :value base-branch}))))
  (when-let [tasks-dir (:tasks-dir config)]
    (when-not (string? tasks-dir)
      (throw (ex-info (str "Expected string for :tasks-dir, got " (type tasks-dir))
                      {:type :invalid-config-type
                       :key :tasks-dir
                       :value tasks-dir
                       :expected 'string?}))))
  (when-let [lock-timeout (:lock-timeout-ms config)]
    (when-not (int? lock-timeout)
      (throw (ex-info (str "Expected integer for :lock-timeout-ms, got " (type lock-timeout))
                      {:type :invalid-config-type
                       :key :lock-timeout-ms
                       :value lock-timeout
                       :expected 'int?})))
    (when-not (pos? lock-timeout)
      (throw (ex-info "Value for :lock-timeout-ms must be positive"
                      {:type :invalid-config-value
                       :key :lock-timeout-ms
                       :value lock-timeout}))))
  (when-let [poll-interval (:lock-poll-interval-ms config)]
    (when-not (int? poll-interval)
      (throw (ex-info (str "Expected integer for :lock-poll-interval-ms, got " (type poll-interval))
                      {:type :invalid-config-type
                       :key :lock-poll-interval-ms
                       :value poll-interval
                       :expected 'int?})))
    (when-not (pos? poll-interval)
      (throw (ex-info "Value for :lock-poll-interval-ms must be positive"
                      {:type :invalid-config-value
                       :key :lock-poll-interval-ms
                       :value poll-interval}))))
  config)

(defn find-config-file
  "Searches for .mcp-tasks.edn by traversing up the directory tree from start-dir.
  Returns {:config-file <path> :config-dir <dir>} when found, nil otherwise.
  Resolves symlinks in paths using fs/canonicalize.

  Parameters:
  - start-dir (optional): Directory to start search from. Defaults to CWD."
  ([]
   (find-config-file (System/getProperty "user.dir")))
  ([start-dir]
   (loop [dir (fs/canonicalize start-dir)]
     (when dir
       (let [config-file (fs/file dir ".mcp-tasks.edn")]
         (if (fs/exists? config-file)
           {:config-file (str (fs/canonicalize config-file))
            :config-dir (str dir)}
           (recur (fs/parent dir))))))))

(defn read-config
  "Searches for and reads .mcp-tasks.edn from current directory or parent directories.
  Returns map with :raw-config, :config-dir, and :start-dir.
  If no config file found, returns {:raw-config {} :config-dir <start-dir> :start-dir <start-dir>}.
  Throws ex-info with clear message for malformed EDN or invalid schema.

  Parameters:
  - start-dir (optional): Directory to start search from. Defaults to CWD."
  ([]
   (read-config (System/getProperty "user.dir")))
  ([start-dir]
   (let [canonical-start-dir (str (fs/canonicalize start-dir))]
     (if-let [{:keys [config-file config-dir]} (find-config-file start-dir)]
       (try
         (let [config (edn/read-string (slurp config-file))]
           {:raw-config (validate-config config)
            :config-dir config-dir
            :start-dir canonical-start-dir})
         (catch clojure.lang.ExceptionInfo e
           ;; Re-throw validation errors as-is
           (throw e))
         (catch Exception e
           ;; Wrap EDN parsing errors with context
           (throw (ex-info (str "Failed to parse .mcp-tasks.edn: " (.getMessage e))
                           {:type :malformed-edn
                            :file config-file
                            :cause e}
                           e))))
       ;; No config file found, use defaults
       {:raw-config {}
        :config-dir canonical-start-dir
        :start-dir canonical-start-dir}))))

;; Git auto-detection

(defn git-repo-exists?
  "Checks if .mcp-tasks/.git directory exists in the config directory.
  Returns true if the git repository exists, false otherwise."
  [config-dir]
  (let [git-dir (str config-dir "/.mcp-tasks/.git")]
    (fs/exists? git-dir)))

(defn determine-git-mode
  "Determines whether to use git mode based on config and auto-detection.
  Returns boolean indicating if git mode should be enabled.

  Precedence:
  1. Explicit config value (:use-git?) if present
  2. Auto-detected presence of .mcp-tasks/.git directory"
  [config-dir config]
  (if (contains? config :use-git?)
    (:use-git? config)
    (git-repo-exists? config-dir)))

(defn resolve-tasks-dir
  "Resolves :tasks-dir to an absolute canonical path.

  Resolution logic:
  - If :tasks-dir is absolute → canonicalize and use as-is
  - If :tasks-dir is relative → resolve relative to config-dir
  - If :tasks-dir not specified → default to .mcp-tasks relative to config-dir

  Validates that explicitly specified :tasks-dir exists.
  Default .mcp-tasks doesn't need to exist yet."
  [config-dir config]
  (let [tasks-dir (:tasks-dir config ".mcp-tasks")
        resolved-path (if (fs/absolute? tasks-dir)
                        tasks-dir
                        (str config-dir "/" tasks-dir))]
    ;; Validate explicitly specified paths exist
    (when (and (contains? config :tasks-dir)
               (not (fs/exists? resolved-path)))
      (throw (ex-info (str "Configured :tasks-dir does not exist: " tasks-dir "\n\n"
                           "Resolved path: " resolved-path "\n"
                           "Config directory: " config-dir "\n\n"
                           "Suggestions:\n"
                           "  - Create the directory if this path is intended\n"
                           "  - Check for typos in the :tasks-dir value\n"
                           "  - Note: relative paths are resolved from the config file directory, not CWD")
                      {:type :invalid-config-value
                       :key :tasks-dir
                       :value tasks-dir
                       :resolved-path resolved-path
                       :config-dir config-dir})))
    ;; Return canonical path if it exists, otherwise return resolved path
    (if (fs/exists? resolved-path)
      (str (fs/canonicalize resolved-path))
      resolved-path)))

(defn in-worktree?
  "Returns true if dir is a git worktree (not main repo).
  In worktrees, .git is a file pointing to the main repo, not a directory."
  [dir]
  (let [git-file (fs/file dir ".git")]
    (and (fs/exists? git-file)
         (not (fs/directory? git-file)))))

(defn find-main-repo
  "Extracts main repo path from .git file in worktree.
  The .git file contains: gitdir: /path/to/main/.git/worktrees/name
  Returns the main repository root directory.
  
  Throws ex-info if:
  - The .git file format is malformed
  - The extracted path doesn't exist
  - The path isn't a valid git repository"
  [worktree-dir]
  (let [git-file (fs/file worktree-dir ".git")
        content (slurp git-file)
        ;; Extract path from "gitdir: /path/to/main/.git/worktrees/name"
        gitdir-match (re-find #"gitdir:\s*(.+)" content)]
    (if gitdir-match
      (let [gitdir-path (second gitdir-match)
            ;; Navigate from .git/worktrees/name to main repo root
            ;; .git/worktrees/name -> .git/worktrees -> .git -> main-repo-root
            main-git-dir (-> gitdir-path
                             fs/parent ; .git/worktrees
                             fs/parent) ; .git
            main-repo-root (fs/parent main-git-dir)
            canonical-path (str (fs/canonicalize main-repo-root))]

        ;; Validate that the path exists
        (when-not (fs/exists? main-repo-root)
          (throw (ex-info
                   (str "Main repository path does not exist: " canonical-path)
                   {:worktree-dir worktree-dir
                    :extracted-path canonical-path
                    :git-file (str git-file)})))

        ;; Validate that it's a valid git repository
        (when-not (fs/exists? (fs/file main-repo-root ".git"))
          (throw (ex-info
                   (str "Path is not a valid git repository (missing .git): " canonical-path)
                   {:worktree-dir worktree-dir
                    :extracted-path canonical-path
                    :git-file (str git-file)})))

        canonical-path)
      ;; Malformed .git file - provide helpful error message
      (throw (ex-info
               (str "Malformed .git file in worktree. Expected format: 'gitdir: /path/to/.git/worktrees/name'")
               {:worktree-dir worktree-dir
                :git-file (str git-file)
                :content content})))))

(defn resolve-config
  "Returns final config map with :use-git?, :base-dir, :main-repo-dir, and :resolved-tasks-dir resolved.
  Uses explicit config value if present, otherwise auto-detects from git
  repo presence. Base directory is set to the config directory (canonicalized).
  Main repo directory is determined by checking worktree status.

  Main repo resolution logic:
  1. If base-dir is a worktree → find main repo from base-dir
  2. Else if start-dir != base-dir AND start-dir is a worktree → find main repo from start-dir
  3. Else → use base-dir as main repo

  This handles cases where:
  - Config file is in the current directory (worktree or not)
  - Config file is in a parent directory while start-dir is a worktree
  - Normal non-worktree repositories

  When :worktree-management? is true, automatically enables :branch-management?.
  When :worktree-prefix is not set, defaults to :project-name.

  Defaults:
  - :worktree-prefix defaults to :project-name if not set
  - :lock-timeout-ms defaults to 30000 (30 seconds) if not set
  - :lock-poll-interval-ms defaults to 100 (100 milliseconds) if not set
  
  Parameters:
  - config-dir: Directory where config file was found
  - config: Raw configuration map
  - start-dir (optional): Directory where config search started. Defaults to config-dir."
  ([config-dir config]
   (resolve-config config-dir config config-dir))
  ([config-dir config start-dir]
   (let [base-dir (str (fs/canonicalize config-dir))
         search-start (str (fs/canonicalize start-dir))
         ;; Determine main repo directory
         main-repo-dir (cond
                         ;; Base-dir is a worktree - use it
                         (in-worktree? base-dir)
                         (find-main-repo base-dir)

                         ;; Start-dir is different from base-dir and start-dir is a worktree
                         ;; (config was inherited from parent directory)
                         (and (not= search-start base-dir)
                              (in-worktree? search-start))
                         (find-main-repo search-start)

                         ;; Default: use base-dir
                         :else
                         base-dir)
         resolved-tasks-dir (resolve-tasks-dir config-dir config)
         config-with-branch-mgmt (if (:worktree-management? config)
                                   (assoc config :branch-management? true)
                                   config)
         config-with-defaults (cond-> config-with-branch-mgmt
                                (not (contains? config-with-branch-mgmt :worktree-prefix))
                                (assoc :worktree-prefix :project-name)

                                (not (contains? config-with-branch-mgmt :lock-timeout-ms))
                                (assoc :lock-timeout-ms 30000)

                                (not (contains? config-with-branch-mgmt :lock-poll-interval-ms))
                                (assoc :lock-poll-interval-ms 100))]
     (assoc config-with-defaults
            :use-git? (determine-git-mode config-dir config)
            :base-dir base-dir
            :main-repo-dir main-repo-dir
            :resolved-tasks-dir resolved-tasks-dir))))

;; Startup validation

(defn validate-git-repo
  "Validates that git repository exists when git mode is enabled.
  Returns nil on success.
  Throws ex-info with clear message if validation fails."
  [config-dir config]
  (when (:use-git? config)
    (when-not (git-repo-exists? config-dir)
      (throw (ex-info "Git mode enabled but .mcp-tasks/.git not found"
                      {:type :git-repo-missing
                       :config-dir config-dir
                       :git-dir (str config-dir "/.mcp-tasks/.git")}))))
  nil)

(defn validate-startup
  "Performs all startup validation (config + git repo).
  Returns nil on success.
  Throws ex-info with clear message if any validation fails."
  [config-dir config]
  (validate-git-repo config-dir config)
  nil)
