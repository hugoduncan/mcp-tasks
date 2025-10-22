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
    [clojure.data.json :as json]
    [mcp-tasks.config :as config]
    [mcp-tasks.execution-state :as execution-state]
    [mcp-tasks.response :as response]
    [mcp-tasks.tasks :as tasks]
    [mcp-tasks.tools.git :as git]
    [mcp-tasks.tools.helpers :as helpers]
    [mcp-tasks.util :as util]))

(defn- calculate-branch-name
  "Calculate the branch name for a task or story.

  Uses the parent story's title if present, otherwise uses the task's title.
  The title is sanitized using util/sanitize-branch-name.

  Parameters:
  - task: The task being worked on
  - parent-story: The parent story (nil if standalone task)

  Returns:
  - The sanitized branch name string

  Examples:
  (calculate-branch-name {:title \"My Task\" :id 42} nil)
  ;; => \"my-task-42\"

  (calculate-branch-name {:title \"Child Task\" :id 10} {:title \"Parent Story\" :id 5})
  ;; => \"parent-story-5\""
  [task parent-story]
  (let [title (if parent-story
                (:title parent-story)
                (:title task))
        branch-source-id (if parent-story
                           (:id parent-story)
                           (:id task))]
    (util/sanitize-branch-name title branch-source-id)))

(defn- manage-branch
  "Manages git branch for task execution.

  Parameters:
  - base-dir: Base directory of the git repository
  - branch-name: The sanitized branch name to use
  - config: Configuration map from read-config

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
    (let [;; Get current branch
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

        ;; Need to switch branches - check for uncommitted changes first
        (if (:has-changes? (git/ensure-git-success!
                             (git/check-uncommitted-changes base-dir)
                             "check-uncommitted-changes"))
          {:success false
           :error "Cannot switch branches with uncommitted changes. Please commit or stash your changes first."
           :metadata {:current-branch current-branch
                      :target-branch branch-name}}

          ;; Get base branch (from config or auto-detect)
          (let [configured-base-branch (:base-branch config)
                base-branch (if configured-base-branch
                              ;; Use configured base branch
                              (let [branch-check (git/ensure-git-success!
                                                   (git/branch-exists? base-dir configured-base-branch)
                                                   (str "branch-exists? " configured-base-branch))]
                                (when-not (:exists? branch-check)
                                  (throw (ex-info (str "Configured base branch " configured-base-branch " does not exist")
                                                  {:base-branch configured-base-branch
                                                   :operation "validate-base-branch"})))
                                configured-base-branch)
                              ;; Auto-detect default branch
                              (:branch (git/ensure-git-success!
                                         (git/get-default-branch base-dir)
                                         "get-default-branch")))]

            ;; Checkout base branch
            (git/ensure-git-success!
              (git/checkout-branch base-dir base-branch)
              (str "checkout-branch " base-branch))

            ;; Pull latest (ignore errors for local-only repos)
            (git/pull-latest base-dir base-branch)

            ;; Check if target branch exists and act accordingly
            (if (:exists? (git/ensure-git-success!
                            (git/branch-exists? base-dir branch-name)
                            (str "branch-exists? " branch-name)))
              ;; Branch exists, checkout
              (do
                (git/ensure-git-success!
                  (git/checkout-branch base-dir branch-name)
                  (str "checkout-branch " branch-name))
                {:success true
                 :branch-name branch-name
                 :branch-created? false
                 :branch-switched? true
                 :error nil})

              ;; Branch doesn't exist, create and checkout
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
  - base-dir: Base directory of the git repository
  - title: The title to use for deriving the worktree path
  - branch-name: The sanitized branch name to use
  - config: Configuration map from read-config

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
  (manage-worktree \"/path\" \"Fix Bug\" \"fix-bug\" config)
  ;; => {:success true :worktree-path \"../mcp-tasks-fix-bug\" :worktree-created? true
  ;;     :needs-directory-switch? true :branch-name \"fix-bug\" :clean? nil
  ;;     :message \"Worktree created at ../mcp-tasks-fix-bug. Please start a new Claude Code session in that directory.\"}

  ;; Worktree exists but not in it
  (manage-worktree \"/path\" \"Fix Bug\" \"fix-bug\" config)
  ;; => {:success true :worktree-path \"../mcp-tasks-fix-bug\" :worktree-created? false
  ;;     :needs-directory-switch? true :branch-name \"fix-bug\" :clean? nil
  ;;     :message \"Worktree exists at ../mcp-tasks-fix-bug. Please start a new Claude Code session in that directory.\"}

  ;; In worktree, correct branch, clean
  (manage-worktree \"/path\" \"Fix Bug\" \"fix-bug\" config)
  ;; => {:success true :worktree-path \"/path\" :worktree-created? false
  ;;     :needs-directory-switch? false :branch-name \"fix-bug\" :clean? true}

  ;; In worktree, correct branch, dirty
  (manage-worktree \"/path\" \"Fix Bug\" \"fix-bug\" config)
  ;; => {:success true :worktree-path \"/path\" :worktree-created? false
  ;;     :needs-directory-switch? false :branch-name \"fix-bug\" :clean? false}

  ;; In worktree, wrong branch (error)
  (manage-worktree \"/path\" \"Fix Bug\" \"fix-bug\" config)
  ;; => {:success false :error \"Worktree is on branch 'other' but expected 'fix-bug'\"}"
  [base-dir title branch-name config]
  (try
    (let [;; Get current working directory (canonical path)
          current-dir (fs/canonicalize (System/getProperty "user.dir"))

          ;; Check if branch already exists in any worktree
          find-result (git/ensure-git-success!
                        (git/find-worktree-for-branch base-dir branch-name)
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
        (let [;; Derive worktree path
              path-result (git/ensure-git-success!
                            (git/derive-worktree-path base-dir title config)
                            "derive-worktree-path")
              worktree-path (:path path-result)

              ;; Check if worktree exists at the derived path
              exists-result (git/ensure-git-success!
                              (git/worktree-exists? base-dir worktree-path)
                              "worktree-exists?")
              worktree-exists? (:exists? exists-result)]

          (cond
            ;; Worktree doesn't exist - create it
            (worktree-needs-creation? worktree-exists?)
            (do
              (git/ensure-git-success!
                (git/create-worktree base-dir worktree-path branch-name)
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
      (when-not task
        (throw (ex-info "Task not found"
                        {:response {:error "No task found with the specified task-id"
                                    :metadata {:task-id task-id
                                               :file tasks-file}}})))

      ;; Get parent story if this is a story task
      (let [parent-story (when-let [parent-id (:parent-id task)]
                           (let [story (first (tasks/get-tasks :task-id parent-id))]
                             ;; Validate parent story exists
                             (when-not story
                               (throw (ex-info "Parent story not found"
                                               {:response {:error "Task references a parent story that does not exist"
                                                           :metadata {:task-id task-id
                                                                      :parent-id parent-id
                                                                      :file tasks-file}}})))
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
  (let [base-response {:task-id (:id task)
                       :title (:title task)
                       :category (:category task)
                       :type (:type task)
                       :status (:status task)
                       :execution-state-file state-file-path
                       :message "Task validated successfully and execution state written"}
        response-with-branch (if branch-info
                               (assoc base-response
                                      :branch-name (:branch-name branch-info)
                                      :branch-created? (:branch-created? branch-info)
                                      :branch-switched? (:branch-switched? branch-info))
                               base-response)
        response-data (if worktree-info
                        (assoc response-with-branch
                               :worktree-path (:worktree-path worktree-info)
                               :worktree-created? (:worktree-created? worktree-info)
                               :worktree-clean? (:clean? worktree-info))
                        response-with-branch)]
    {:content [{:type "text"
                :text (json/write-str response-data)}]
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
          user-config (config/read-config (:base-dir cfg))
          base-dir (:base-dir cfg)
          worktree-mgmt-enabled? (:worktree-management? user-config)
          branch-mgmt-enabled? (or worktree-mgmt-enabled?
                                   (:branch-management? user-config))

          ;; Calculate branch name and title once for use in branch/worktree
          ;; management
          title (if parent-story
                  (:title parent-story)
                  (:title task))
          branch-name (calculate-branch-name task parent-story)

          ;; Handle worktree management if configured
          worktree-info (when worktree-mgmt-enabled?
                          (let [worktree-result (manage-worktree
                                                  base-dir
                                                  title
                                                  branch-name
                                                  user-config)]
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
                                              user-config)]
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
        (let [response-data {:task-id (:id task)
                             :title (:title task)
                             :category (:category task)
                             :type (:type task)
                             :status (:status task)
                             :worktree-path (:worktree-path worktree-info)
                             :worktree-created? (:worktree-created? worktree-info)
                             :branch-name (:branch-name worktree-info)
                             :message (:message worktree-info)}]
          {:content [{:type "text"
                      :text (json/write-str response-data)}]
           :isError false})

        ;; Otherwise proceed with execution state and normal response
        (let [story-id (:parent-id task)
              started-at (java.time.Instant/now)
              state {:task-id task-id
                     :story-id story-id
                     :started-at (str started-at)}
              _ (execution-state/write-execution-state! base-dir state)
              state-file-path (str base-dir "/.mcp-tasks-current.edn")]
          (build-success-response task branch-info worktree-info state-file-path))))

    (catch clojure.lang.ExceptionInfo e
      ;; Handle validation errors with structured response
      (if-let [response-data (:response (ex-data e))]
        {:content [{:type "text"
                    :text (json/write-str response-data)}]
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
