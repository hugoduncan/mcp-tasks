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
  Returns map with :raw-config and :config-dir.
  If no config file found, returns {:raw-config {} :config-dir <start-dir>}.
  Throws ex-info with clear message for malformed EDN or invalid schema.

  Parameters:
  - start-dir (optional): Directory to start search from. Defaults to CWD."
  ([]
   (read-config (System/getProperty "user.dir")))
  ([start-dir]
   (if-let [{:keys [config-file config-dir]} (find-config-file start-dir)]
     (try
       (let [config (edn/read-string (slurp config-file))]
         {:raw-config (validate-config config)
          :config-dir config-dir})
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
      :config-dir (str (fs/canonicalize start-dir))})))

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

(defn resolve-config
  "Returns final config map with :use-git?, :base-dir, and :resolved-tasks-dir resolved.
  Uses explicit config value if present, otherwise auto-detects from git
  repo presence. Base directory is set to the config directory (canonicalized).

  When :worktree-management? is true, automatically enables :branch-management?.
  When :worktree-prefix is not set, defaults to :project-name.

  Defaults:
  - :worktree-prefix defaults to :project-name if not set
  - :lock-timeout-ms defaults to 30000 (30 seconds) if not set"
  [config-dir config]
  (let [base-dir (str (fs/canonicalize config-dir))
        resolved-tasks-dir (resolve-tasks-dir config-dir config)
        config-with-branch-mgmt (if (:worktree-management? config)
                                  (assoc config :branch-management? true)
                                  config)
        config-with-defaults (cond-> config-with-branch-mgmt
                               (not (contains? config-with-branch-mgmt :worktree-prefix))
                               (assoc :worktree-prefix :project-name)

                               (not (contains? config-with-branch-mgmt :lock-timeout-ms))
                               (assoc :lock-timeout-ms 30000))]
    (assoc config-with-defaults
           :use-git? (determine-git-mode config-dir config)
           :base-dir base-dir
           :resolved-tasks-dir resolved-tasks-dir)))

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
