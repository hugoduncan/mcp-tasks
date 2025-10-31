(ns mcp-tasks.config
  "Configuration management for mcp-tasks"
  (:require
    [babashka.fs :as fs]
    [clojure.edn :as edn]
    [clojure.string :as str]))

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
  ;; :branch-title-words can be nil (unlimited words) or a positive integer
  (when-let [branch-title-words (:branch-title-words config)]
    (when-not (int? branch-title-words)
      (throw (ex-info (str "Expected integer for :branch-title-words, got " (type branch-title-words))
                      {:type :invalid-config-type
                       :key :branch-title-words
                       :value branch-title-words
                       :expected 'int?})))
    (when-not (pos? branch-title-words)
      (throw (ex-info "Value for :branch-title-words must be positive"
                      {:type :invalid-config-value
                       :key :branch-title-words
                       :value branch-title-words}))))
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

(defn validate-git-repository-path
  "Validates that a path is a valid git repository (main repo, not worktree).
  
  Checks:
  - Path exists
  - .git exists in path
  - .git is a directory (not a file, which indicates a worktree)
  
  Returns nil if valid, or throws ex-info with descriptive error.
  
  Parameters:
  - path: Directory path to validate
  - context-info: Map with additional context for error messages (optional)"
  ([path]
   (validate-git-repository-path path {}))
  ([path context-info]
   {:pre [(string? path)
          (not (str/blank? path))]}
   (let [canonical-path (str (fs/canonicalize path))]
     ;; Check if path exists
     (when-not (fs/exists? path)
       (throw (ex-info
                (str "Repository path does not exist: " canonical-path)
                (merge {:extracted-path canonical-path} context-info))))

     ;; Check if .git exists
     (let [git-path (fs/file path ".git")]
       (when-not (fs/exists? git-path)
         (throw (ex-info
                  (str "Path is not a valid git repository (missing .git): " canonical-path)
                  (merge {:extracted-path canonical-path} context-info))))

       ;; Check if .git is a directory (not a file indicating a worktree)
       (when-not (fs/directory? git-path)
         (throw (ex-info
                  (str "Path is a nested worktree (expected .git directory, found file): " canonical-path)
                  (merge {:extracted-path canonical-path
                          :git-path (str git-path)}
                         context-info)))))
     nil)))

(defn find-main-repo
  "Extracts main repo path from .git file in worktree.
  The .git file contains: gitdir: /path/to/main/.git/worktrees/name
  Returns the main repository root directory.

  Throws ex-info if:
  - The .git file format is malformed
  - The extracted path doesn't exist
  - The path isn't a valid git repository"
  [worktree-dir]
  {:pre [(string? worktree-dir)
         (not (str/blank? worktree-dir))]}
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

        ;; Validate repository using helper
        (validate-git-repository-path
          canonical-path
          {:worktree-dir worktree-dir
           :git-file (str git-file)})

        canonical-path)
      ;; Malformed .git file - provide helpful error message
      (throw (ex-info
               "Malformed .git file in worktree. Expected format: 'gitdir: /path/to/.git/worktrees/name'"
               {:worktree-dir worktree-dir
                :git-file (str git-file)
                :content content})))))

(defn find-main-repo-in-subdirs
  "Searches for main git repository in subdirectories when base-dir has no .git.

  Common pattern: config in parent directory with main repo as subdirectory.
  Example structure:
    /project/
      .mcp-tasks.edn
      project-main/  <- main repo
      worktree-1/
      worktree-2/

  Search strategy:
  1. Look for directories matching *-main pattern
  2. Look for directory named 'bare'
  3. Check each for valid .git directory

  Returns path to main repo if found, nil otherwise."
  [base-dir]
  {:pre [(string? base-dir)
         (not (str/blank? base-dir))]}
  (when (fs/directory? base-dir)
    (let [candidates (concat
                       ;; Look for *-main directories
                       (fs/glob base-dir "*-main")
                       ;; Look for 'bare' directory
                       (when (fs/exists? (fs/file base-dir "bare"))
                         [(fs/file base-dir "bare")]))
          ;; Filter to only directories with .git subdirectory
          main-repos (filter (fn [dir]
                               (and (fs/directory? dir)
                                    (fs/directory? (fs/file dir ".git"))))
                             candidates)]
      (when (seq main-repos)
        ;; Return first match, canonicalized
        (fs/canonicalize (first main-repos))))))

(defn resolve-config
  "Returns final config map with :use-git?, :base-dir, :main-repo-dir, and :resolved-tasks-dir resolved.
  Uses explicit config value if present, otherwise auto-detects from git
  repo presence. Base directory is set to start-dir (current working directory).
  Main repo directory is determined by checking worktree status of base-dir.

  Main repo resolution logic:
  - If base-dir is a worktree → find main repo from base-dir
  - Otherwise → use base-dir as main repo

  This handles cases where:
  - Starting from a worktree directory (config inherited or local)
  - Starting from the main repository directory
  - Normal non-worktree repositories

  When :worktree-management? is true, automatically enables :branch-management?.
  When :worktree-prefix is not set, defaults to :project-name.

  Defaults:
  - :worktree-prefix defaults to :project-name if not set
  - :lock-timeout-ms defaults to 30000 (30 seconds) if not set
  - :lock-poll-interval-ms defaults to 100 (100 milliseconds) if not set
  - :enable-git-sync? defaults to :use-git? value if not set
  
  Parameters:
  - config-dir: Directory where config file was found
  - config: Raw configuration map
  - start-dir (optional): Directory where config search started. Defaults to config-dir."
  ([config-dir config]
   (resolve-config config-dir config config-dir))
  ([config-dir config start-dir]
   (let [;; base-dir represents the current working directory (canonicalized start-dir)
         ;; This is where operations like git status should run
         base-dir (str (fs/canonicalize start-dir))

         ;; Determine main repo directory for repository-wide operations
         ;; Strategy: Check if base-dir (which is start-dir) is a worktree
         ;;
         ;; This handles three scenarios:
         ;; 1. Config in worktree:
         ;;    - start-dir = worktree (e.g., /projects/mcp-tasks-fix-bug/)
         ;;    - config-dir = worktree (same as start-dir)
         ;;    - base-dir = start-dir = worktree
         ;;    - in-worktree?(base-dir) → true → use find-main-repo
         ;;
         ;; 2. Config inherited (config in parent, running in worktree):
         ;;    - start-dir = worktree (e.g., /projects/mcp-tasks-fix-bug/)
         ;;    - config-dir = parent directory (e.g., /projects/mcp-tasks/)
         ;;    - base-dir = start-dir = worktree
         ;;    - in-worktree?(base-dir) → true → use find-main-repo
         ;;
         ;; 3. Started from parent directory (no .git):
         ;;    - start-dir = parent (e.g., /projects/mcp-tasks/)
         ;;    - config-dir = parent (same as start-dir)
         ;;    - base-dir = start-dir = parent (no .git)
         ;;    - in-worktree?(base-dir) → false
         ;;    - Look for main repo in subdirectories (*-main, bare)
         ;;
         ;; Both cases 1 and 2 are handled identically because base-dir is always set
         ;; to start-dir, regardless of where the config file was found.
         ;; The key insight: we care about WHERE WE'RE RUNNING (start-dir),
         ;; not where the config file is located (config-dir).
         main-repo-dir (cond
                         ;; Case 1 & 2: In a worktree
                         (in-worktree? base-dir)
                         (find-main-repo base-dir)

                         ;; Case 3a: Has .git directory (normal repo)
                         (git-repo-exists? base-dir)
                         base-dir

                         ;; Case 3b: No .git - search subdirectories
                         :else
                         (or (find-main-repo-in-subdirs base-dir)
                             base-dir)) ; fallback to base-dir if nothing found

         resolved-tasks-dir (resolve-tasks-dir config-dir config)
         config-with-branch-mgmt (if (:worktree-management? config)
                                   (assoc config :branch-management? true)
                                   config)
         use-git-value (determine-git-mode config-dir config)
         config-with-defaults (cond-> config-with-branch-mgmt
                                (not (contains? config-with-branch-mgmt :worktree-prefix))
                                (assoc :worktree-prefix :project-name)

                                (not (contains? config-with-branch-mgmt :lock-timeout-ms))
                                (assoc :lock-timeout-ms 30000)

                                (not (contains? config-with-branch-mgmt :lock-poll-interval-ms))
                                (assoc :lock-poll-interval-ms 100)

                                (not (contains? config-with-branch-mgmt :enable-git-sync?))
                                (assoc :enable-git-sync? use-git-value))]
     (assoc config-with-defaults
            :use-git? use-git-value
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
