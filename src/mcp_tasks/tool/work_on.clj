(ns mcp-tasks.tool.work-on
  "MCP tool for setting up the environment to work on a task.

  This tool handles the setup steps for task execution, including:
  - Task validation
  - Branch management (if configured)
  - Execution state tracking

  It provides a reusable setup mechanism for both standalone and story tasks,
  making it available for individual task execution, story task execution,
  manual workflow preparation, and external tool integration."
  (:require
    [babashka.fs :as fs]
    [cheshire.core :as json]
    [clojure.string :as str]
    [mcp-clj.log :as log]
    [mcp-tasks.execution-state :as execution-state]
    [mcp-tasks.response :as response]
    [mcp-tasks.tasks :as tasks]
    [mcp-tasks.tools.git :as git]
    [mcp-tasks.tools.helpers :as helpers]
    [mcp-tasks.util :as util]))

(defn- calculate-branch-name
  "Calculate the branch name for a task or story.

  Uses the parent story's title if present, otherwise uses the task's title.
  The title is sanitized using util/sanitize-branch-name with the configured
  word limit (defaults to 4 words).

  Parameters:
  - task: The task being worked on
  - parent-story: The parent story (nil if standalone task)
  - config: Configuration map containing :branch-title-words (optional, defaults to 4)

  Returns:
  - The sanitized branch name string with ID prefix

  Examples:
  (calculate-branch-name {:title \"My Task\" :id 42} nil {:branch-title-words 4})
  ;; => \"42-my-task\"

  (calculate-branch-name
    {:title \"Child Task\" :id 10} {:title \"Parent Story\" :id 5} {:branch-title-words 4})
  ;; => \"5-parent-story\""
  [task parent-story config]
  (let [title (if parent-story
                (:title parent-story)
                (:title task))
        branch-source-id (if parent-story
                           (:id parent-story)
                           (:id task))
        word-limit (get config :branch-title-words 4)]
    (util/sanitize-branch-name title branch-source-id word-limit)))

(defn- calculate-base-branch
  [configured-base-branch main-repo-dir]
  (if configured-base-branch
    ;; Use configured base branch
    (let [branch-check (git/ensure-git-success!
                         (git/branch-exists? main-repo-dir configured-base-branch)
                         (str "branch-exists? " configured-base-branch))]
      (when-not (:exists? branch-check)
        (throw (ex-info (str "Configured base branch " configured-base-branch " does not exist")
                        {:base-branch configured-base-branch
                         :operation "validate-base-branch"})))
      configured-base-branch)
    ;; Auto-detect default branch
    (:branch (git/ensure-git-success!
               (git/get-default-branch main-repo-dir)
               "get-default-branch"))))

(defn- manage-branch
  "Manages git branch for task execution.

  Parameters:
  - base-dir: Base directory (for context-specific operations like checking uncommitted changes)
  - branch-name: The sanitized branch name to use
  - config: Configuration map from read-config (must include :main-repo-dir)

  Uses :main-repo-dir from config for repository-wide branch operations.
  Uses base-dir for context-specific operations in the current directory.

  Returns a map with:
  - :success - boolean indicating if operation succeeded
  - :branch-name - the target branch name
  - :branch-created? - boolean indicating if branch was created
  - :branch-switched? - boolean indicating if branch was switched
  - :error - error message string (or nil if successful)

  Examples:
  ;; Creates new branch
  (manage-branch \"/path\" \"my-story\" config)
  ;; => {:success true :branch-name \"my-story\" :branch-created? true :branch-switched? true}

  ;; Branch exists, switches to it
  (manage-branch \"/path\" \"my-story\" config)
  ;; => {:success true :branch-name \"my-story\" :branch-created? false :branch-switched? true}

  ;; Already on correct branch (no-op case)
  (manage-branch \"/path\" \"my-task\" config)
  ;; => {:success true :branch-name \"my-task\" :branch-created? false :branch-switched? false}"
  [base-dir branch-name config]
  (try
    (let [;; Extract main-repo-dir for repository operations
          main-repo-dir (:main-repo-dir config)

          ;; Get current branch (uses base-dir for current context)
          current-branch (:branch (git/ensure-git-success!
                                    (git/get-current-branch base-dir)
                                    "get-current-branch"))]

      (if (= current-branch branch-name)
        ;; Already on target branch
        {:success true
         :branch-name branch-name
         :branch-created? false
         :branch-switched? false
         :error nil}

        ;; Need to switch branches - check for uncommitted changes first (uses base-dir)
        (if (:has-changes? (git/ensure-git-success!
                             (git/check-uncommitted-changes base-dir)
                             "check-uncommitted-changes"))
          {:success false
           :error (str "Cannot switch branches with uncommitted changes. "
                       "Please commit or stash your changes first.")
           :metadata {:current-branch current-branch
                      :target-branch branch-name}}

          ;; Get base branch (from config or auto-detect, uses main-repo-dir)
          (let [configured-base-branch (:base-branch config)
                base-branch (calculate-base-branch
                              configured-base-branch
                              main-repo-dir)]

            ;; Checkout base branch (uses base-dir for checkout)
            (git/ensure-git-success!
              (git/checkout-branch base-dir base-branch)
              (str "checkout-branch " base-branch))

            ;; Pull latest (ignore errors for local-only repos, uses base-dir)
            (git/pull-latest base-dir base-branch)

            ;; Check if target branch exists (uses main-repo-dir)
            (if (:exists? (git/ensure-git-success!
                            (git/branch-exists? main-repo-dir branch-name)
                            (str "branch-exists? " branch-name)))
              ;; Branch exists, checkout (uses base-dir)
              (do
                (git/ensure-git-success!
                  (git/checkout-branch base-dir branch-name)
                  (str "checkout-branch " branch-name))
                {:success true
                 :branch-name branch-name
                 :branch-created? false
                 :branch-switched? true
                 :error nil})

              ;; Branch doesn't exist, create and checkout (uses base-dir)
              (do
                (git/ensure-git-success!
                  (git/create-and-checkout-branch base-dir branch-name)
                  (str "create-and-checkout-branch " branch-name))
                {:success true
                 :branch-name branch-name
                 :branch-created? true
                 :branch-switched? true
                 :error nil}))))))

    (catch clojure.lang.ExceptionInfo e
      {:success false
       :error (:error (ex-data e) (.getMessage e))
       :metadata (dissoc (ex-data e) :error)})))

(defn- extract-worktree-name
  "Extract the worktree name from a worktree path.

  Returns the final path component (directory name).

  Example: /Users/duncan/projects/mcp-tasks-fix-bug/ -> mcp-tasks-fix-bug"
  [worktree-path]
  (when worktree-path
    (fs/file-name worktree-path)))

(defn- current-working-directory
  "Get the current working directory as a canonical path.

  Extracted into a separate function to allow mocking in tests."
  []
  (fs/canonicalize (System/getProperty "user.dir")))

(defn- in-worktree?
  "Check if the current directory is inside the specified worktree path.

  Compares canonical paths to handle symlinks and relative paths correctly."
  [current-dir worktree-path]
  (when worktree-path
    (let [worktree-canonical (fs/canonicalize worktree-path)]
      (= current-dir worktree-canonical))))

(defn- worktree-needs-creation?
  "Check if a worktree needs to be created (doesn't exist yet)."
  [worktree-exists?]
  (not worktree-exists?))

(defn- worktree-needs-switch?
  "Check if we need to switch to an existing worktree.
   If the worktree exists but we're not in it,"
  [worktree-exists? current-dir worktree-path]
  (and worktree-exists?
       (not (in-worktree? current-dir worktree-path))))

(defn- worktree-switch-message
  "Create a user-facing message for worktree directory switch.

  Parameters:
  - status: Either :created or :exists
  - worktree-path: Path to the worktree

  Returns:
  - String message instructing user to switch to the worktree directory"
  [status worktree-path]
  (str "Worktree "
       (case status
         :created "created"
         :exists "exists")
       " at "
       worktree-path
       ". Please start a new Claude Code session in that directory."))

(defn- manage-worktree
  "Manages git worktree for task execution.

  Parameters:
  - base-dir: Base directory (current working directory, may be a worktree)
  - title: The title to use for deriving the worktree path
  - task-id: The task or story ID number (used for worktree path generation)
  - branch-name: The sanitized branch name to use
  - config: Configuration map from read-config (must include :main-repo-dir)

  Uses :main-repo-dir from config for repository-wide worktree operations.
  Uses base-dir for context-specific operations in the current directory.

  Returns a map with:
  - :success - boolean indicating if operation succeeded
  - :worktree-path - path to the worktree
  - :worktree-created? - boolean indicating if worktree was created
  - :needs-directory-switch? - boolean indicating if user needs to switch directories
  - :branch-name - the branch name for the worktree
  - :clean? - boolean indicating if worktree has no uncommitted changes (nil if not in worktree)
  - :error - error message string (or nil if successful)
  - :message - user-facing message about required actions

  Examples:
  ;; Worktree doesn't exist, needs creation
  (manage-worktree \"/path\" \"Fix Bug\" 123 \"123-fix-bug\" config)
  ;; => {:success true :worktree-path \"../mcp-tasks-123-fix-bug\" :worktree-created? true
  ;;     :needs-directory-switch? true :branch-name \"123-fix-bug\" :clean? nil
  ;;     :message \"Worktree created at ../mcp-tasks-123-fix-bug. Please start a new Claude Code session in that directory.\"}

  ;; Worktree exists but not in it
  (manage-worktree \"/path\" \"Fix Bug\" 123 \"123-fix-bug\" config)
  ;; => {:success true :worktree-path \"../mcp-tasks-123-fix-bug\" :worktree-created? false
  ;;     :needs-directory-switch? true :branch-name \"123-fix-bug\" :clean? nil
  ;;     :message \"Worktree exists at ../mcp-tasks-123-fix-bug. Please start a new Claude Code session in that directory.\"}

  ;; In worktree, correct branch, clean
  (manage-worktree \"/path\" \"Fix Bug\" 123 \"123-fix-bug\" config)
  ;; => {:success true :worktree-path \"/path\" :worktree-created? false
  ;;     :needs-directory-switch? false :branch-name \"123-fix-bug\" :clean? true}

  ;; In worktree, correct branch, dirty
  (manage-worktree \"/path\" \"Fix Bug\" 123 \"123-fix-bug\" config)
  ;; => {:success true :worktree-path \"/path\" :worktree-created? false
  ;;     :needs-directory-switch? false :branch-name \"123-fix-bug\" :clean? false}

  ;; In worktree, wrong branch (error)
  (manage-worktree \"/path\" \"Fix Bug\" 123 \"other-branch\" config)
  ;; => {:success false :error \"Worktree is on branch 'other' but expected '123-fix-bug'\"}"
  [_base-dir title task-id branch-name config]
  (try
    (let [;; Extract main-repo-dir for worktree operations
          main-repo-dir (:main-repo-dir config)

          ;; Get current working directory (canonical path)
          current-dir (current-working-directory)

          ;; Check if branch already exists in any worktree (uses main-repo-dir)
          find-result (git/ensure-git-success!
                        (git/find-worktree-for-branch main-repo-dir branch-name)
                        "find-worktree-for-branch")
          existing-worktree (:worktree find-result)]

      (if existing-worktree
        ;; Branch exists in a worktree - use that worktree
        (let [worktree-path (:path existing-worktree)]
          (if (in-worktree? current-dir worktree-path)
            ;; Already in the correct worktree - check if clean
            (let [is-clean? (-> (git/check-uncommitted-changes worktree-path)
                                (git/ensure-git-success!
                                  "check-uncommitted-changes")
                                :has-changes?
                                not)]
              {:success true
               :worktree-path worktree-path
               :worktree-created? false
               :needs-directory-switch? false
               :branch-name branch-name
               :clean? is-clean?
               :error nil})

            ;; Need to switch to existing worktree
            {:success true
             :worktree-path worktree-path
             :worktree-created? false
             :needs-directory-switch? true
             :branch-name branch-name
             :clean? nil
             :error nil
             :message (worktree-switch-message :exists worktree-path)}))

        ;; Branch not in any worktree - proceed with deriving path and
        ;; creating/checking worktree
        (let [;; Derive worktree path (uses main-repo-dir)
              path-result (git/ensure-git-success!
                            (git/derive-worktree-path main-repo-dir title task-id config)
                            "derive-worktree-path")
              worktree-path (:path path-result)

              ;; Check if worktree exists at the derived path (uses main-repo-dir)
              exists-result (git/ensure-git-success!
                              (git/worktree-exists? main-repo-dir worktree-path)
                              "worktree-exists?")
              worktree-exists? (:exists? exists-result)
              branch-exists? (:exists? (git/ensure-git-success!
                                         (git/branch-exists? main-repo-dir branch-name)
                                         "branch-exists?"))]

          (cond
            ;; Worktree doesn't exist - create it (uses main-repo-dir)
            (worktree-needs-creation? worktree-exists?)
            (do
              (git/ensure-git-success!
                (git/create-worktree
                  main-repo-dir
                  worktree-path
                  branch-name
                  (when-not branch-exists?
                    (calculate-base-branch
                      (:base-branch config)
                      main-repo-dir)))
                (str "create-worktree " worktree-path " " branch-name))
              {:success true
               :worktree-path worktree-path
               :worktree-created? true
               :needs-directory-switch? true
               :branch-name branch-name
               :clean? nil
               :error nil
               :message (worktree-switch-message :created worktree-path)})

            ;; Worktree exists but we're not in it
            (worktree-needs-switch? worktree-exists? current-dir worktree-path)
            {:success true
             :worktree-path worktree-path
             :worktree-created? false
             :needs-directory-switch? true
             :branch-name branch-name
             :clean? nil
             :error nil
             :message (worktree-switch-message :exists worktree-path)}

            ;; We're in the worktree - verify branch and check clean status
            :else
            (let [current-branch (-> (git/worktree-branch worktree-path)
                                     (git/ensure-git-success! "worktree-branch")
                                     :branch)]

              ;; Verify we're on the correct branch
              (when (not= current-branch branch-name)
                (throw
                  (ex-info
                    (str "Worktree is on branch " current-branch " but expected " branch-name)
                    {:current-branch current-branch
                     :expected-branch branch-name
                     :worktree-path worktree-path
                     :operation "verify-worktree-branch"})))

              ;; Check if worktree is clean
              (let [is-clean? (-> (git/check-uncommitted-changes worktree-path)
                                  (git/ensure-git-success! "check-uncommitted-changes")
                                  :has-changes?
                                  not)]
                {:success true
                 :worktree-path worktree-path
                 :worktree-created? false
                 :needs-directory-switch? false
                 :branch-name branch-name
                 :clean? is-clean?
                 :error nil}))))))

    (catch clojure.lang.ExceptionInfo e
      {:success false
       :error (:error (ex-data e) (.getMessage e))
       :metadata (dissoc (ex-data e) :error)})))

(defn- safe-to-remove-worktree?
  "Checks if a worktree is safe to remove.

  Performs safety checks to ensure no work will be lost:
  1. Verifies no uncommitted changes exist
  2. Verifies all commits are pushed to remote

  Parameters:
  - worktree-path: Path to the worktree to check

  Returns a map with:
  - :safe? - boolean indicating if safe to remove
  - :reason - string describing the result

  Examples:
  ;; Safe to remove
  {:safe? true :reason \"Worktree is clean and all commits are pushed\"}

  ;; Not safe - uncommitted changes
  {:safe? false :reason \"Uncommitted changes exist in worktree\"}

  ;; Not safe - unpushed commits
  {:safe? false :reason \"Unpushed commits exist\"}"
  [worktree-path]
  {:pre [(string? worktree-path)
         (not (str/blank? worktree-path))]}
  (let [uncommitted-result (git/check-uncommitted-changes worktree-path)
        pushed-result (git/check-all-pushed? worktree-path)
        result (or
                 ;; Return first failure found, or nil to continue to success case
                 (when-not (:success uncommitted-result)
                   {:safe? false
                    :reason (str "Failed to check uncommitted changes: " (:error uncommitted-result))})

                 (when (:has-changes? uncommitted-result)
                   {:safe? false
                    :reason "Uncommitted changes exist in worktree"})

                 (when-not (:success pushed-result)
                   {:safe? false
                    :reason (:reason pushed-result)})

                 (when-not (:all-pushed? pushed-result)
                   {:safe? false
                    :reason (:reason pushed-result)})

                 ;; All checks passed
                 {:safe? true
                  :reason "Worktree is clean and all commits are pushed"})]
    (log/info :worktree-safety-check
              {:worktree-path worktree-path
               :safe? (:safe? result)
               :reason (:reason result)})
    result))

(defn cleanup-worktree-after-completion
  "Removes a worktree after verifying it is safe to remove.

  This function should be called after task completion to clean up
  worktrees that were created for task isolation. It performs safety
  checks before removal to prevent data loss.

  Parameters:
  - main-repo-dir: Path to the main repository directory
  - worktree-path: Path to the worktree to remove
  - config: Configuration map (reserved for future use)

  Returns a map with:
  - :success - boolean indicating if removal succeeded
  - :message - string describing success (or nil on failure)
  - :error - string describing failure (or nil on success)

  Examples:
  ;; Successful removal
  {:success true
   :message \"Worktree removed at /path/to/worktree\"
   :error nil}

  ;; Failed safety checks
  {:success false
   :message nil
   :error \"Cannot remove worktree: Uncommitted changes exist in worktree\"}

  ;; Failed removal operation
  {:success false
   :message nil
   :error \"Failed to remove worktree: <git error>\"}"
  [main-repo-dir worktree-path config]
  {:pre [(string? main-repo-dir)
         (not (str/blank? main-repo-dir))
         (string? worktree-path)
         (not (str/blank? worktree-path))
         (map? config)]}
  (let [safety-check (safe-to-remove-worktree? worktree-path)]
    (log/info :worktree-cleanup-attempt
              {:main-repo-dir main-repo-dir
               :worktree-path worktree-path
               :safe-to-remove? (:safe? safety-check)})
    (if (:safe? safety-check)
      ;; Safe to remove - attempt removal
      (let [remove-result (git/remove-worktree main-repo-dir worktree-path)]
        (if (:success remove-result)
          (do
            (log/info :worktree-cleanup-success
                      {:worktree-path worktree-path})
            {:success true
             :message (str "Worktree removed at " worktree-path)
             :error nil})
          (do
            (log/warn :worktree-cleanup-failed
                      {:worktree-path worktree-path
                       :error (:error remove-result)})
            {:success false
             :message nil
             :error (str "Failed to remove worktree: " (:error remove-result))})))
      ;; Not safe to remove
      (do
        (log/warn :worktree-cleanup-skipped
                  {:worktree-path worktree-path
                   :reason (:reason safety-check)})
        {:success false
         :message nil
         :error (str "Cannot remove worktree: " (:reason safety-check))}))))

(defn- validate-task-id-param
  "Validates the task-id parameter.

  Parameters:
  - task-id: The task ID to validate (can be any type)

  Returns:
  - task-id if valid

  Throws:
  - ExceptionInfo with :response key if validation fails"
  [task-id]
  (when-not task-id
    (throw (ex-info "Missing required parameter"
                    {:response {:error "task-id parameter is required"
                                :metadata {}}})))

  (when-not (integer? task-id)
    (throw (ex-info "Invalid parameter type"
                    {:response {:error "task-id must be an integer"
                                :metadata {:provided-value task-id
                                           :provided-type (str (type task-id))}}})))
  task-id)

(defn- have-task!
  [task task-id tasks-file]
  (when-not task
    (throw
      (ex-info
        "Task not found"
        {:response {:error "No task found with the specified task-id"
                    :metadata {:task-id task-id
                               :file tasks-file}}}))))

(defn- have-story!
  [story task-id parent-id tasks-file]
  (when-not story
    (throw
      (ex-info
        "Parent story not found"
        {:response
         {:error "Task references a parent story that does not exist"
          :metadata {:task-id task-id
                     :parent-id parent-id
                     :file tasks-file}}}))))

(defn- load-task-and-story
  "Loads and validates a task and its optional parent story.

  Parameters:
  - cfg: Configuration map
  - task-id: The task ID to load

  Returns:
  - Map with :task and :parent-story keys (parent-story is nil if not a story task)

  Throws:
  - ExceptionInfo with :response key if task or parent story not found"
  [cfg task-id]
  (let [tasks-path (helpers/task-path cfg ["tasks.ednl"])
        tasks-file (:absolute tasks-path)
        complete-path (helpers/task-path cfg ["complete.ednl"])
        complete-file (:absolute complete-path)]

    ;; Load tasks from EDNL file
    (when (helpers/file-exists? tasks-file)
      (tasks/load-tasks! tasks-file :complete-file complete-file))

    ;; Get the specific task
    (let [matching-tasks (tasks/get-tasks :task-id task-id)
          task (first matching-tasks)]
      ;; Validate task exists
      (have-task! task task-id tasks-file)

      ;; Get parent story if this is a story task
      (let [parent-story (when-let [parent-id (:parent-id task)]
                           (let [story (first
                                         (tasks/get-tasks :task-id parent-id))]
                             ;; Validate parent story exists
                             (have-story! story task-id parent-id tasks-file)
                             story))]
        {:task task
         :parent-story parent-story}))))

(defn- build-success-response
  "Builds a success response for the work-on tool.

  Parameters:
  - task: The task being worked on
  - branch-info: Optional branch management result (nil if not enabled)
  - worktree-info: Optional worktree management result (nil if not enabled)
  - state-file-path: Path to the execution state file

  Returns:
  - MCP response map with :content and :isError keys"
  [task branch-info worktree-info state-file-path]
  (let [blocking-status (tasks/is-task-blocked? (:id task))
        response-data (cond-> {:task-id (:id task)
                               :title (:title task)
                               :category (:category task)
                               :type (:type task)
                               :status (:status task)
                               :execution-state-file state-file-path
                               :is-blocked (:blocked? blocking-status)
                               :blocking-task-ids (:blocking-ids blocking-status)
                               :message "Task validated successfully and execution state written"}
                        branch-info
                        (assoc :branch-name (:branch-name branch-info)
                               :branch-created? (:branch-created? branch-info)
                               :branch-switched? (:branch-switched? branch-info))

                        worktree-info
                        (merge {:worktree-path (:worktree-path worktree-info)
                                :worktree-created? (:worktree-created? worktree-info)
                                :worktree-clean? (:clean? worktree-info)}
                               (when-let [wt-path (:worktree-path worktree-info)]
                                 {:worktree-name (extract-worktree-name wt-path)})))]
    {:content [{:type "text"
                :text (json/generate-string response-data)}]
     :isError false}))

(defn- work-on-impl
  "Implementation of work-on tool.

  Validates that the specified task exists, optionally manages branches,
  writes execution state, and returns task details.

  Parameters:
  - task-id (required, integer): The ID of the task to work on

  Returns JSON-encoded response with:
  - Task details (id, title, category, type, status)
  - Branch information (if branch management enabled)
  - Execution state file path
  - Success indicator

  Writes execution state to .mcp-tasks-current.edn with:
  - task-id: The task being worked on
  - story-id: The parent story ID if this is a story task, nil otherwise
  - started-at: ISO-8601 timestamp when work started"
  [cfg _context {:keys [task-id]}]
  (try
    ;; Validate parameters
    (validate-task-id-param task-id)

    ;; Load task, story, config, and setup base directory
    (let [{:keys [task parent-story]} (load-task-and-story cfg task-id)

          base-dir (:base-dir cfg)
          worktree-mgmt-enabled? (:worktree-management? cfg)
          branch-mgmt-enabled? (or worktree-mgmt-enabled?
                                   (:branch-management? cfg))

          ;; Calculate branch name, title, and ID once for use in branch/worktree
          ;; management
          title (if parent-story
                  (:title parent-story)
                  (:title task))
          branch-source-id (if parent-story
                             (:id parent-story)
                             (:id task))
          branch-name (calculate-branch-name task parent-story cfg)

          ;; Handle worktree management if configured
          worktree-info (when worktree-mgmt-enabled?
                          (let [worktree-result (manage-worktree
                                                  base-dir
                                                  title
                                                  branch-source-id
                                                  branch-name
                                                  cfg)]
                            (when-not (:success worktree-result)
                              (throw
                                (ex-info
                                  "Worktree management failed"
                                  {:response
                                   {:error (:error worktree-result)
                                    :metadata (:metadata worktree-result {})}})))
                            worktree-result))

          ;; Handle branch management if configured
          branch-info (when (and branch-mgmt-enabled?
                                 (not worktree-mgmt-enabled?))
                        (let [branch-result (manage-branch
                                              base-dir
                                              branch-name
                                              cfg)]
                          (when-not (:success branch-result)
                            (throw
                              (ex-info
                                "Branch management failed"
                                {:response
                                 {:error (:error branch-result)
                                  :metadata (:metadata branch-result {})}})))
                          branch-result))]

      ;; If worktree management requires directory switch, return early with message
      (if (and worktree-info (:needs-directory-switch? worktree-info))
        (let [worktree-path (:worktree-path worktree-info)
              response-data (cond-> {:task-id (:id task)
                                     :title (:title task)
                                     :category (:category task)
                                     :type (:type task)
                                     :status (:status task)
                                     :worktree-path (str worktree-path)
                                     :worktree-created? (:worktree-created? worktree-info)
                                     :branch-name (:branch-name worktree-info)
                                     :message (:message worktree-info)}
                              worktree-path
                              (assoc :worktree-name (extract-worktree-name worktree-path)))]
          {:content [{:type "text"
                      :text (json/generate-string response-data)}]
           :isError false})

        ;; Otherwise proceed with execution state and normal response
        (let [task-start-time (java.time.Instant/now)
              state (if (= (:type task) :story)
                      ;; Working on a story directly
                      {:story-id task-id
                       :task-start-time (str task-start-time)}
                      ;; Working on a regular task
                      {:task-id task-id
                       :story-id (:parent-id task)
                       :task-start-time (str task-start-time)})
              _ (execution-state/write-execution-state! base-dir state)
              state-file-path (str base-dir "/.mcp-tasks-current.edn")]
          (build-success-response task branch-info worktree-info state-file-path))))

    (catch clojure.lang.ExceptionInfo e
      ;; Handle validation errors with structured response
      (if-let [response-data (:response (ex-data e))]
        {:content [{:type "text"
                    :text (json/generate-string response-data)}]
         :isError false}
        (response/error-response e)))

    (catch Exception e
      (response/error-response e))))

(defn work-on-tool
  "Tool to set up the environment for working on a task.

  This tool prepares the development environment for task execution by:
  - Validating the task exists
  - Managing git branches (if configured)
  - Setting execution state

  Parameters:
  - task-id (required, integer): The ID of the task to work on

  Returns JSON-encoded response:
  Success: {\"task-id\": N, \"title\": \"...\", \"category\": \"...\", ...}
  Error: {\"error\": \"...\", \"metadata\": {...}}"
  [config]
  {:name "work-on"
   :description "Set up the environment for working on a task"
   :inputSchema
   {:type "object"
    :properties
    {"task-id"
     {:type "integer"
      :description "The ID of the task to work on"}}
    :required ["task-id"]}
   :implementation (partial work-on-impl config)})
