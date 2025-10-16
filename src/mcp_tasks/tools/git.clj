(ns mcp-tasks.tools.git
  "Git-related helper functions for task management"
  (:require
    [clojure.java.shell :as sh]
    [clojure.string :as str]))

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
  - task-id: ID of the task being operated on
  - task-title: Title of the task being operated on
  - files-to-commit: Collection of relative file paths to add and commit
  - operation: Operation name (e.g., \"Complete\", \"Delete\") for commit message

  Returns a map with:
  - :success - boolean indicating if commit succeeded
  - :commit-sha - commit SHA string (or nil if failed)
  - :error - error message string (or nil if successful)

  Never throws - all errors are caught and returned in the map."
  [base-dir task-id task-title files-to-commit operation]
  (let [git-dir (str base-dir "/.mcp-tasks")
        commit-msg (str operation " task #" task-id ": " task-title)]
    (perform-git-commit git-dir files-to-commit commit-msg)))
