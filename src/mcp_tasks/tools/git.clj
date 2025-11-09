(ns mcp-tasks.tools.git
  "Git-related helper functions for task management.

  ## Directory Parameters

  Functions in this namespace accept directory parameters that determine where
  git operations are executed. Understanding the difference is crucial when
  working with git worktrees.

  ### Main Repository Directory (:main-repo-dir)

  Use for repository-wide operations that must run from the main repository:
  - list-worktrees - Lists all worktrees in the repository
  - find-worktree-for-branch - Searches for a branch across all worktrees
  - worktree-exists? - Checks if a worktree path exists
  - create-worktree - Creates a new worktree
  - remove-worktree - Removes a worktree
  - derive-worktree-path - Derives the path for a new worktree
  - derive-project-name - Gets the project name from main repository

  ### Base Directory (:base-dir)

  Use for context-specific operations in the current working directory:
  - get-current-branch - Gets the branch in current directory
  - check-uncommitted-changes - Checks changes in current directory
  - check-all-pushed? - Checks if all commits are pushed to remote
  - branch-exists? - Checks if a branch exists (works from any directory)
  - checkout-branch - Checks out a branch in current directory
  - create-and-checkout-branch - Creates and checks out branch in current directory
  - pull-latest - Pulls latest changes in current directory
  - get-default-branch - Gets default branch (works from any directory)
  - commit-task-changes - Commits changes in current directory

  ### Worktree-Specific

  - worktree-branch - Uses provided worktree path parameter (not base-dir)"
  (:require
    [babashka.fs :as fs]
    [clojure.java.shell :as sh]
    [clojure.string :as str]
    [mcp-tasks.util :as util]))

;; Worktree Detection

(defn in-worktree?
  "Returns true if dir is a git worktree (not the main repository).
  
  In a worktree, .git is a file pointing to the main repo's worktree metadata.
  In the main repo, .git is a directory containing the actual git database.
  
  Parameters:
  - dir: Path to check (string or File)
  
  Returns:
  - true if dir is a worktree
  - false if dir is a main repository or not a git directory"
  [dir]
  (let [git-file (fs/file dir ".git")]
    (and (fs/exists? git-file)
         (fs/regular-file? git-file))))

(defn find-main-repo
  "Extracts the main repository path from a worktree's .git file.
  
  The .git file in a worktree contains a line like:
  gitdir: /path/to/main/.git/worktrees/name
  
  This function parses that line and resolves to the main repo root.
  
  Parameters:
  - worktree-dir: Path to the worktree directory
  
  Returns the absolute path to the main repository root directory."
  [worktree-dir]
  {:pre [(in-worktree? worktree-dir)]}
  (let [git-file (fs/file worktree-dir ".git")
        content (slurp git-file)
        ;; Extract the gitdir path from "gitdir: /path/to/.git/worktrees/name"
        gitdir-match (re-find #"gitdir:\s*(.+)" content)]
    (when-not gitdir-match
      (throw (ex-info "Invalid .git file format in worktree"
                      {:worktree-dir worktree-dir
                       :content content})))
    (let [gitdir-path (second gitdir-match)
          ;; The gitdir points to .git/worktrees/name
          ;; We need to go up to .git, then up to the main repo root
          main-git-dir (-> gitdir-path
                           fs/file
                           fs/parent ; Go from .git/worktrees/name to .git/worktrees
                           fs/parent) ; Go from .git/worktrees to .git
          main-repo-dir (fs/parent main-git-dir)]
      (str (fs/canonicalize main-repo-dir)))))

(defn get-main-repo-dir
  "Returns the main repository directory for the given path.
  
  If the path is a worktree, returns the main repository path.
  If the path is already the main repository, returns it unchanged.
  
  Parameters:
  - dir: Path to check (can be worktree or main repo)
  
  Returns the absolute path to the main repository root directory."
  [dir]
  (if (in-worktree? dir)
    (find-main-repo dir)
    (str (fs/canonicalize dir))))

(defn ensure-git-success!
  "Throws ex-info if git operation failed. Returns result on success.

  Parameters:
  - result: Map with :success and :error keys from a git operation
  - operation: String describing the operation for error context

  Returns the result map unchanged if successful.
  Throws ex-info with operation details if failed."
  [result operation]
  (when-not (:success result)
    (throw (ex-info (str "Git operation failed: " operation)
                    {:error (:error result)
                     :operation operation})))
  result)

(defn perform-git-commit
  "Performs git add and commit operations.

  Parameters:
  - git-dir: Path to the git repository
  - files-to-commit: Collection of relative file paths to add and commit
  - commit-msg: The commit message string

  Returns a map with:
  - :success - boolean indicating if commit succeeded
  - :commit-sha - commit SHA string (or nil if failed)
  - :error - error message string (or nil if successful)

  Never throws - all errors are caught and returned in the map."
  [git-dir files-to-commit commit-msg]
  (try
    ;; Stage modified files
    (apply sh/sh "git" "-C" git-dir "add" files-to-commit)

    ;; Commit changes
    (let [commit-result (sh/sh "git" "-C" git-dir "commit" "-m" commit-msg)]
      (if (zero? (:exit commit-result))
        ;; Success - get commit SHA
        (let [sha-result (sh/sh "git" "-C" git-dir "rev-parse" "HEAD")
              sha (str/trim (:out sha-result))]
          {:success true
           :commit-sha sha
           :error nil})

        ;; Commit failed
        {:success false
         :commit-sha nil
         :error (str/trim (:err commit-result))}))

    (catch Exception e
      {:success false
       :commit-sha nil
       :error (.getMessage e)})))

(defn commit-task-changes
  "Commits task file changes to .mcp-tasks git repository.

  Parameters:
  - base-dir: Base directory containing .mcp-tasks
  - files-to-commit: Collection of relative file paths to add and commit
  - commit-msg: The commit message string

  Returns a map with:
  - :success - boolean indicating if commit succeeded
  - :commit-sha - commit SHA string (or nil if failed)
  - :error - error message string (or nil if successful)

  Never throws - all errors are caught and returned in the map."
  [base-dir files-to-commit commit-msg]
  (let [git-dir (str base-dir "/.mcp-tasks")]
    (perform-git-commit git-dir files-to-commit commit-msg)))

(defn get-current-branch
  "Returns the current git branch name.

  Parameters:
  - base-dir: Base directory of the git repository

  Returns a map with:
  - :success - boolean indicating if operation succeeded
  - :branch - branch name string (or nil if failed)
  - :error - error message string (or nil if successful)"
  [base-dir]
  {:pre [(string? base-dir)
         (not (clojure.string/blank? base-dir))]}
  (try
    (let [result (sh/sh "git" "-C" base-dir "rev-parse" "--abbrev-ref" "HEAD")]
      (if (zero? (:exit result))
        {:success true
         :branch (str/trim (:out result))
         :error nil}
        {:success false
         :branch nil
         :error (str/trim (:err result))}))
    (catch Exception e
      {:success false
       :branch nil
       :error (.getMessage e)})))

(defn get-default-branch
  "Returns the default branch name.

  First attempts to read from git config (origin/HEAD).
  Falls back to 'main', then 'master' if no remote is configured.

  Parameters:
  - base-dir: Base directory of the git repository

  Returns a map with:
  - :success - boolean indicating if operation succeeded
  - :branch - branch name string (or nil if failed)
  - :error - error message string (or nil if successful)"
  [base-dir]
  {:pre [(string? base-dir)
         (not (clojure.string/blank? base-dir))]}
  (try
    ;; Try to get remote default branch
    (let [result (sh/sh "git" "-C" base-dir "symbolic-ref" "refs/remotes/origin/HEAD" "--short")]
      (if (zero? (:exit result))
        ;; Remote exists, extract branch name (strip "origin/" prefix)
        (let [full-ref (str/trim (:out result))
              branch (str/replace full-ref #"^origin/" "")]
          {:success true
           :branch branch
           :error nil})
        ;; No remote, fall back to common defaults
        (let [main-result (sh/sh "git" "-C" base-dir "rev-parse" "--verify" "main")]
          (if (zero? (:exit main-result))
            {:success true
             :branch "main"
             :error nil}
            (let [master-result (sh/sh "git" "-C" base-dir "rev-parse" "--verify" "master")]
              (if (zero? (:exit master-result))
                {:success true
                 :branch "master"
                 :error nil}
                {:success false
                 :branch nil
                 :error "Could not determine default branch"}))))))
    (catch Exception e
      {:success false
       :branch nil
       :error (.getMessage e)})))

(defn check-uncommitted-changes
  "Checks if there are uncommitted changes in the working directory.

  Parameters:
  - base-dir: Base directory of the git repository

  Returns a map with:
  - :success - boolean indicating if check succeeded
  - :has-changes? - boolean indicating if uncommitted changes exist
  - :error - error message string (or nil if successful)"
  [base-dir]
  {:pre [(string? base-dir)
         (not (clojure.string/blank? base-dir))]}
  (try
    (let [result (sh/sh "git" "-C" base-dir "status" "--porcelain")]
      (if (zero? (:exit result))
        {:success true
         :has-changes? (not (str/blank? (str/trim (:out result))))
         :error nil}
        {:success false
         :has-changes? nil
         :error (str/trim (:err result))}))
    (catch Exception e
      {:success false
       :has-changes? nil
       :error (.getMessage e)})))

(defn check-all-pushed?
  "Checks if all commits in the current branch are pushed to remote.

  Uses git rev-list to compare local HEAD with remote tracking branch.
  This ensures no local work will be lost if the worktree is removed.

  Parameters:
  - base-dir: Base directory of the git repository

  Returns a map with:
  - :success - boolean indicating if check succeeded
  - :all-pushed? - boolean indicating if all commits are pushed (or nil if check failed)
  - :reason - descriptive string explaining the result"
  [base-dir]
  {:pre [(string? base-dir)
         (not (clojure.string/blank? base-dir))]}
  (try
    ;; First check if there's a tracking branch configured
    (let [tracking-check (sh/sh "git" "-C" base-dir "rev-parse" "--abbrev-ref" "@{u}")]
      (if (zero? (:exit tracking-check))
        ;; Has tracking branch - check for unpushed commits
        (let [result (sh/sh "git" "-C" base-dir "rev-list" "@{u}..HEAD")]
          (if (zero? (:exit result))
            (let [unpushed-commits (str/trim (:out result))]
              (if (str/blank? unpushed-commits)
                {:success true
                 :all-pushed? true
                 :reason "All commits are pushed to remote"}
                {:success true
                 :all-pushed? false
                 :reason "Unpushed commits exist"}))
            {:success false
             :all-pushed? nil
             :reason (str "Failed to check commits: " (str/trim (:err result)))}))
        ;; No tracking branch configured
        (let [stderr (str/trim (:err tracking-check))]
          (if (str/includes? stderr "no upstream configured")
            {:success true
             :all-pushed? false
             :reason "No remote tracking branch configured"}
            {:success false
             :all-pushed? nil
             :reason (str "Failed to check tracking branch: " stderr)}))))
    (catch Exception e
      {:success false
       :all-pushed? nil
       :reason (str "Exception checking pushed status: " (.getMessage e))})))

(defn branch-exists?
  "Checks if a branch exists.

  Parameters:
  - base-dir: Base directory of the git repository
  - branch-name: Name of the branch to check

  Returns a map with:
  - :success - boolean indicating if check succeeded
  - :exists? - boolean indicating if branch exists
  - :error - error message string (or nil if successful)"
  [base-dir branch-name]
  {:pre [(some? base-dir)
         (string? branch-name)
         (not (str/blank? branch-name))]}
  (try
    (let [result (sh/sh "git" "-C" base-dir "rev-parse" "--verify" branch-name)]
      {:success true
       :exists? (zero? (:exit result))
       :error nil})
    (catch Exception e
      {:success false
       :exists? nil
       :error (ex-message e)})))

(defn checkout-branch
  "Checks out an existing branch.

  Parameters:
  - base-dir: Base directory of the git repository
  - branch-name: Name of the branch to checkout

  Returns a map with:
  - :success - boolean indicating if checkout succeeded
  - :error - error message string (or nil if successful)"
  [base-dir branch-name]
  {:pre [(string? base-dir)
         (not (clojure.string/blank? base-dir))
         (string? branch-name)
         (not (clojure.string/blank? branch-name))]}
  (try
    (let [result (sh/sh "git" "-C" base-dir "checkout" branch-name)]
      (if (zero? (:exit result))
        {:success true
         :error nil}
        {:success false
         :error (str/trim (:err result))}))
    (catch Exception e
      {:success false
       :error (.getMessage e)})))

(defn create-and-checkout-branch
  "Creates and checks out a new branch.

  Parameters:
  - base-dir: Base directory of the git repository
  - branch-name: Name of the branch to create

  Returns a map with:
  - :success - boolean indicating if operation succeeded
  - :error - error message string (or nil if successful)"
  [base-dir branch-name]
  {:pre [(string? base-dir)
         (not (clojure.string/blank? base-dir))
         (string? branch-name)
         (not (clojure.string/blank? branch-name))]}
  (try
    (let [result (sh/sh "git" "-C" base-dir "checkout" "-b" branch-name)]
      (if (zero? (:exit result))
        {:success true
         :error nil}
        {:success false
         :error (str/trim (:err result))}))
    (catch Exception e
      {:success false
       :error (.getMessage e)})))

(defn- conflict-pattern?
  "Returns true if stderr contains conflict indicators"
  [stderr]
  (or (str/includes? stderr "CONFLICT")
      (str/includes? stderr "Automatic merge failed")
      (str/includes? stderr "fix conflicts")
      (str/includes? stderr "unresolved conflict")
      (str/includes? stderr "unmerged files")
      ;; Git 2.51+ returns this for divergent branches before attempting merge
      (str/includes? stderr "Not possible to fast-forward")
      ;; Rebase conflict patterns
      (str/includes? stderr "could not apply")
      (str/includes? stderr "Resolve all conflicts manually")))

(defn- no-remote-pattern?
  "Returns true if stderr indicates no remote is configured"
  [stderr]
  (or (str/includes? stderr "does not appear to be a git repository")
      (str/includes? stderr "No configured push destination")
      (str/includes? stderr "No remote repository specified")
      (re-find #"repository '.*' not found" stderr)))

(defn- network-pattern?
  "Returns true if stderr indicates network connectivity issues"
  [stderr]
  (or (str/includes? stderr "Could not resolve host")
      (str/includes? stderr "Connection refused")
      (str/includes? stderr "Failed to connect")
      (str/includes? stderr "timed out")
      (str/includes? stderr "Network is unreachable")
      (str/includes? stderr "unable to access")
      (str/includes? stderr "The requested URL returned error")))

(defn- detect-by-pattern
  "Fallback pattern matching for unknown exit codes"
  [stderr]
  (cond
    (conflict-pattern? stderr) :conflict
    (no-remote-pattern? stderr) :no-remote
    (network-pattern? stderr) :network
    :else :other))

(defn- detect-exit-1-error
  "Exit 1 is typically conflicts, but could be other errors"
  [stderr]
  (cond
    (conflict-pattern? stderr) :conflict
    (no-remote-pattern? stderr) :no-remote
    (network-pattern? stderr) :network
    :else :other))

(defn- detect-fatal-error
  "Exit 128 indicates fatal errors"
  [stderr]
  (cond
    ;; Check for conflicts first - newer git (2.51+) uses exit 128 for divergent branches
    (conflict-pattern? stderr) :conflict
    (no-remote-pattern? stderr) :no-remote
    (network-pattern? stderr) :network
    :else :other))

(defn- detect-error-type
  "Detects error type using exit code and stderr patterns.
  
  Uses a multi-layered approach:
  - Exit code 1: typically conflicts, check patterns to confirm
  - Exit code 128: fatal errors (no remote, network issues)
  - Other codes: fall back to pattern matching"
  [exit-code stderr]
  (let [error-type (cond
                     (= 1 exit-code)
                     (detect-exit-1-error stderr)

                     (= 128 exit-code)
                     (detect-fatal-error stderr)

                     :else
                     (detect-by-pattern stderr))]

    ;; Log unrecognized patterns to stderr for debugging
    (when (and (= error-type :other)
               (not (str/blank? stderr)))
      (binding [*out* *err*]
        (println "Unrecognized git pull error pattern:"
                 "exit-code:" exit-code
                 "stderr:" stderr)))

    ;; Build response
    (if (= error-type :no-remote)
      {:success true :pulled? false :error nil :error-type :no-remote}
      {:success false :pulled? false :error stderr :error-type error-type})))

(defn pull-latest
  "Pulls the latest changes from remote.

  Distinguishes between different failure types to enable appropriate handling.

  Parameters:
  - base-dir: Base directory of the git repository
  - branch-name: Name of the branch to pull

  Returns a map with:
  - :success - boolean indicating if operation succeeded (true for exit 0 or :no-remote)
  - :pulled? - boolean indicating if changes were pulled (true only for exit 0)
  - :error - error message string (or nil if successful/no-remote)
  - :error-type - keyword indicating error type (:no-remote | :conflict | :network | :other, or nil if successful)"
  [base-dir branch-name]
  {:pre [(string? base-dir)
         (not (clojure.string/blank? base-dir))
         (string? branch-name)
         (not (clojure.string/blank? branch-name))]}
  (try
    (let [result (sh/sh "git" "-C" base-dir "pull" "origin" branch-name)
          stderr (str/trim (:err result))
          exit-code (:exit result)]
      (if (zero? exit-code)
        {:success true
         :pulled? true
         :error nil
         :error-type nil}
        ;; Non-zero exit - use multi-layered error detection
        (detect-error-type exit-code stderr)))
    (catch Exception e
      {:success false
       :pulled? false
       :error (.getMessage e)
       :error-type :other})))

(defn derive-project-name
  "Extracts the project name from a project directory path.

  Parameters:
  - project-dir: Path to the project directory

  Returns a map with:
  - :success - boolean indicating if operation succeeded
  - :name - project name string (last component of path)
  - :error - error message string (or nil if successful)"
  [project-dir]
  {:pre [(string? project-dir)
         (not (clojure.string/blank? project-dir))]}
  (try
    (let [file (fs/file project-dir)
          name (.getName file)]
      (if (str/blank? name)
        {:success false
         :name nil
         :error "Could not extract project name from path"}
        {:success true
         :name name
         :error nil}))
    (catch Exception e
      {:success false
       :name nil
       :error (.getMessage e)})))

(defn derive-worktree-path
  "Generates a worktree path from a project directory, title, and task ID.

  The worktree path includes the task ID prefix and respects the configured
  word limit from :branch-title-words (defaults to 4 words).

  The worktree path format depends on the :worktree-prefix config:
  - :project-name (default): <parent-dir>/<project-name>-<id>-<title-slug>
  - :none: <parent-dir>/<id>-<title-slug>

  The title slug is generated using util/sanitize-branch-name which:
  - Takes first N words from title (N = :branch-title-words, default 4)
  - Converts to lowercase
  - Replaces spaces with dashes
  - Removes all special characters (keeping only a-z, 0-9, -)
  - Prepends task ID

  Parameters:
  - project-dir: Path to the project directory
  - title: Story or task title to convert to path
  - task-id: The task or story ID number
  - config: Configuration map containing :worktree-prefix and :branch-title-words

  Returns a map with:
  - :success - boolean indicating if operation succeeded
  - :path - worktree path string
  - :error - error message string (or nil if successful)

  Examples:
  (derive-worktree-path \"/Users/test/mcp-tasks\" \"Fix parser bug\" 123
                        {:worktree-prefix :project-name :branch-title-words 4})
  ;; => {:success true :path \"/Users/test/mcp-tasks-123-fix-parser-bug\" :error nil}

  (derive-worktree-path \"/Users/test/mcp-tasks\" \"Fix parser bug\" 123
                        {:worktree-prefix :none :branch-title-words 2})
  ;; => {:success true :path \"/Users/test/123-fix-parser\" :error nil}"
  [project-dir title task-id config]
  {:pre [(string? project-dir)
         (not (clojure.string/blank? project-dir))
         (string? title)
         (not (clojure.string/blank? title))
         (int? task-id)
         (map? config)]}
  (try
    (let [worktree-prefix (:worktree-prefix config :project-name)
          word-limit (get config :branch-title-words 4)
          ;; Generate ID-prefixed slug using sanitize-branch-name
          id-slug (util/sanitize-branch-name title task-id word-limit)
          parent-dir (fs/parent project-dir)

          ;; Build path based on prefix mode
          worktree-path (if (= worktree-prefix :none)
                          (str parent-dir "/" id-slug)
                          (let [parent-name (fs/file-name parent-dir)]
                            (if (str/blank? parent-name)
                              (throw (ex-info "Could not extract parent directory name"
                                              {:operation "derive-parent-name"}))
                              (str parent-dir "/" parent-name "-" id-slug))))]

      {:success true
       :path worktree-path
       :error nil})
    (catch Exception e
      {:success false
       :path nil
       :error (.getMessage e)})))

(defn list-worktrees
  "Lists all git worktrees in the repository.

  Parses the porcelain format output from 'git worktree list --porcelain'.

  Parameters:
  - project-dir: Path to the project directory

  Returns a map with:
  - :success - boolean indicating if operation succeeded
  - :worktrees - vector of maps with :path, :head, :branch (or :detached)
  - :error - error message string (or nil if successful)"
  [project-dir]
  {:pre [(string? project-dir)
         (not (clojure.string/blank? project-dir))]}
  (try
    (let [result (sh/sh "git" "-C" project-dir "worktree" "list" "--porcelain")]
      (if (zero? (:exit result))
        (let [output (str/trim (:out result))
              entries (if (str/blank? output)
                        []
                        (str/split output #"\n\n"))
              worktrees (for [entry entries]
                          (let [lines (str/split-lines entry)
                                parse-line (fn [line]
                                             (if-let [[_ k v] (re-matches #"(\w+)\s+(.*)" line)]
                                               [k v]
                                               (when-let [[_ k] (re-matches #"(\w+)" line)]
                                                 [k true])))
                                pairs (keep parse-line lines)
                                data (into {} pairs)]
                            (cond-> {:path (get data "worktree")
                                     :head (get data "HEAD")}
                              (contains? data "branch")
                              (assoc :branch (str/replace (get data "branch") #"^refs/heads/" ""))

                              (get data "detached")
                              (assoc :detached true))))]
          {:success true
           :worktrees (vec worktrees)
           :error nil})
        {:success false
         :worktrees nil
         :error (str/trim (:err result))}))
    (catch Exception e
      {:success false
       :worktrees nil
       :error (.getMessage e)})))

(defn find-worktree-for-branch
  "Finds the worktree (if any) that has the given branch checked out.

  Parameters:
  - project-dir: Path to the project directory
  - branch-name: Name of the branch to search for

  Returns:
  - {:success true :worktree {...} :error nil} if branch found in a worktree
  - {:success true :worktree nil :error nil} if branch not in any worktree
  - {:success false :error \"...\"} on error"
  [project-dir branch-name]
  {:pre [(string? project-dir)
         (not (clojure.string/blank? project-dir))
         (string? branch-name)
         (not (clojure.string/blank? branch-name))]}
  (let [result (list-worktrees project-dir)]
    (if (:success result)
      (let [matching-worktree (->> (:worktrees result)
                                   (filter #(= branch-name (:branch %)))
                                   first)]
        {:success true
         :worktree matching-worktree
         :error nil})
      {:success false
       :worktree nil
       :error (:error result)})))

(defn worktree-exists?
  "Checks if a worktree exists at the given path.

  Parameters:
  - project-dir: Path to the project directory
  - worktree-path: Path to check for worktree existence

  Returns a map with:
  - :success - boolean indicating if check succeeded
  - :exists? - boolean indicating if worktree exists
  - :worktree - worktree info map (or nil if doesn't exist)
  - :error - error message string (or nil if successful)"
  [project-dir worktree-path]
  {:pre [(string? project-dir)
         (not (clojure.string/blank? project-dir))
         (string? worktree-path)
         (not (clojure.string/blank? worktree-path))]}
  (let [result (list-worktrees project-dir)]
    (if-not (:success result)
      (assoc result :exists? nil :worktree nil)
      (let [worktree (first (filter #(= (:path %) worktree-path)
                                    (:worktrees result)))]
        {:success true
         :exists? (some? worktree)
         :worktree worktree
         :error nil}))))

(defn worktree-branch
  "Returns the branch name of a worktree.

  Parameters:
  - worktree-path: Path to the worktree directory

  Returns a map with:
  - :success - boolean indicating if operation succeeded
  - :branch - branch name string (or nil if failed/detached)
  - :detached? - boolean indicating if HEAD is detached
  - :error - error message string (or nil if successful)"
  [worktree-path]
  {:pre [(string? worktree-path)
         (not (clojure.string/blank? worktree-path))]}
  (try
    (let [result (sh/sh "git" "-C" worktree-path "rev-parse" "--abbrev-ref" "HEAD")]
      (if (zero? (:exit result))
        (let [branch (str/trim (:out result))]
          (if (= "HEAD" branch)
            {:success true
             :branch nil
             :detached? true
             :error nil}
            {:success true
             :branch branch
             :detached? false
             :error nil}))
        {:success false
         :branch nil
         :detached? nil
         :error (str/trim (:err result))}))
    (catch Exception e
      {:success false
       :branch nil
       :detached? nil
       :error (.getMessage e)})))

(defn create-worktree
  "Creates a new git worktree.

  Parameters:
  - project-dir: Path to the project directory
  - worktree-path: Path where the worktree should be created
  - branch-name: Name of the branch for the worktree
  - base-branch: Name of the branch to base branch-name on

  With base-branch, creates the branch.

  Returns a map with:
  - :success - boolean indicating if creation succeeded
  - :error - error message string (or nil if successful)"
  ([project-dir worktree-path branch-name]
   (create-worktree project-dir worktree-path branch-name nil))
  ([project-dir worktree-path branch-name base-branch]
   {:pre [(string? project-dir)
          (not (clojure.string/blank? project-dir))
          (string? worktree-path)
          (not (clojure.string/blank? worktree-path))
          (string? branch-name)
          (not (clojure.string/blank? branch-name))]}
   (try
     (let [result (if base-branch
                    (sh/sh "git" "-C" project-dir
                           "worktree" "add"
                           worktree-path
                           "-b"
                           branch-name
                           base-branch)
                    (sh/sh "git" "-C" project-dir
                           "worktree" "add"
                           worktree-path
                           branch-name))]
       (if (zero? (:exit result))
         {:success true
          :error nil}
         {:success false
          :error (str/trim (:err result))}))
     (catch Exception e
       {:success false
        :error (.getMessage e)}))))

(defn remove-worktree
  "Removes a git worktree.

  Parameters:
  - project-dir: Path to the project directory
  - worktree-path: Path to the worktree to remove

  Returns a map with:
  - :success - boolean indicating if removal succeeded
  - :error - error message string (or nil if successful)"
  [project-dir worktree-path]
  {:pre [(string? project-dir)
         (not (clojure.string/blank? project-dir))
         (string? worktree-path)
         (not (clojure.string/blank? worktree-path))]}
  (try
    (let [result (sh/sh "git" "-C" project-dir "worktree" "remove" worktree-path)]
      (if (zero? (:exit result))
        {:success true
         :error nil}
        {:success false
         :error (str/trim (:err result))}))
    (catch Exception e
      {:success false
       :error (.getMessage e)})))
