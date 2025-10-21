(ns mcp-tasks.tools.git
  "Git-related helper functions for task management"
  (:require
    [clojure.java.shell :as sh]
    [clojure.string :as str]))

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
  (try
    (let [result (sh/sh "git" "-C" base-dir "rev-parse" "--verify" branch-name)]
      {:success true
       :exists? (zero? (:exit result))
       :error nil})
    (catch Exception e
      {:success false
       :exists? nil
       :error (.getMessage e)})))

(defn checkout-branch
  "Checks out an existing branch.

  Parameters:
  - base-dir: Base directory of the git repository
  - branch-name: Name of the branch to checkout

  Returns a map with:
  - :success - boolean indicating if checkout succeeded
  - :error - error message string (or nil if successful)"
  [base-dir branch-name]
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

(defn pull-latest
  "Pulls the latest changes from remote.

  Gracefully handles local-only repos by ignoring pull errors.

  Parameters:
  - base-dir: Base directory of the git repository
  - branch-name: Name of the branch to pull

  Returns a map with:
  - :success - boolean indicating if operation succeeded
  - :pulled? - boolean indicating if changes were pulled (false for local-only repos)
  - :error - error message string (or nil if successful/local-only)"
  [base-dir branch-name]
  (try
    (let [result (sh/sh "git" "-C" base-dir "pull" "origin" branch-name)]
      (if (zero? (:exit result))
        {:success true
         :pulled? true
         :error nil}
        ;; Pull failed - likely local-only repo, which is fine
        {:success true
         :pulled? false
         :error nil}))
    (catch Exception _
      ;; Exception during pull - treat as local-only repo
      {:success true
       :pulled? false
       :error nil})))

(defn derive-project-name
  "Extracts the project name from a project directory path.

  Parameters:
  - project-dir: Path to the project directory

  Returns a map with:
  - :success - boolean indicating if operation succeeded
  - :name - project name string (last component of path)
  - :error - error message string (or nil if successful)"
  [project-dir]
  (try
    (let [file (java.io.File. project-dir)
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
  "Generates a worktree path from a project directory and title.

  The worktree path format depends on the :worktree-prefix config:
  - :project-name (default when not specified): <parent-dir>/<project-name>-<sanitized-title>
  - :none: <parent-dir>/<sanitized-title>

  The title is sanitized by:
  - Converting to lowercase
  - Replacing spaces with dashes
  - Removing all characters except a-z, 0-9, and -

  Parameters:
  - project-dir: Path to the project directory
  - title: Story or task title to convert to path
  - config: Configuration map containing :worktree-prefix

  Returns a map with:
  - :success - boolean indicating if operation succeeded
  - :path - worktree path string
  - :error - error message string (or nil if successful)"
  [project-dir title config]
  (try
    (let [worktree-prefix (:worktree-prefix config :project-name)
          sanitized (-> title
                        str/lower-case
                        (str/replace #"\s+" "-")
                        (str/replace #"[^a-z0-9-]" ""))
          parent-dir (.getParent (java.io.File. project-dir))

          ;; Build path based on prefix mode
          worktree-path (if (= worktree-prefix :none)
                          (str parent-dir "/" sanitized)
                          (let [name-result (derive-project-name project-dir)]
                            (if-not (:success name-result)
                              (throw (ex-info (:error name-result)
                                              {:operation "derive-project-name"}))
                              (let [project-name (:name name-result)]
                                (str parent-dir "/" project-name "-" sanitized)))))]

      (if (str/blank? sanitized)
        {:success false
         :path nil
         :error "Title produced empty path after sanitization"}
        {:success true
         :path worktree-path
         :error nil}))
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

  Returns a map with:
  - :success - boolean indicating if creation succeeded
  - :error - error message string (or nil if successful)"
  [project-dir worktree-path branch-name]
  (try
    (let [result (sh/sh "git" "-C" project-dir "worktree" "add" worktree-path branch-name)]
      (if (zero? (:exit result))
        {:success true
         :error nil}
        {:success false
         :error (str/trim (:err result))}))
    (catch Exception e
      {:success false
       :error (.getMessage e)})))

(defn remove-worktree
  "Removes a git worktree.

  Parameters:
  - project-dir: Path to the project directory
  - worktree-path: Path to the worktree to remove

  Returns a map with:
  - :success - boolean indicating if removal succeeded
  - :error - error message string (or nil if successful)"
  [project-dir worktree-path]
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
