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
