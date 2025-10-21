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
    [clojure.data.json :as json]
    [mcp-tasks.config :as config]
    [mcp-tasks.execution-state :as execution-state]
    [mcp-tasks.response :as response]
    [mcp-tasks.tasks :as tasks]
    [mcp-tasks.tools.git :as git]
    [mcp-tasks.tools.helpers :as helpers]
    [mcp-tasks.util :as util]))

(defn- manage-branch
  "Manages git branch for task execution.

  Parameters:
  - base-dir: Base directory of the git repository
  - task: The task being worked on
  - parent-story: The parent story (nil if standalone task)
  - config: Configuration map from read-config

  Returns a map with:
  - :success - boolean indicating if operation succeeded
  - :branch-name - the target branch name
  - :branch-created? - boolean indicating if branch was created
  - :branch-switched? - boolean indicating if branch was switched
  - :error - error message string (or nil if successful)

  Examples:
  ;; Story task, creates new branch from story title
  (manage-branch \"/path\" task story-task config)
  ;; => {:success true :branch-name \"my-story\" :branch-created? true :branch-switched? true}

  ;; Story task, existing branch
  (manage-branch \"/path\" task story-task config)
  ;; => {:success true :branch-name \"my-story\" :branch-created? false :branch-switched? true}

  ;; Standalone task
  (manage-branch \"/path\" task nil config)
  ;; => {:success true :branch-name \"my-task\" :branch-created? true :branch-switched? true}

  ;; Already on correct branch (no-op case)
  (manage-branch \"/path\" task nil config)
  ;; => {:success true :branch-name \"my-task\" :branch-created? false :branch-switched? false}"
  [base-dir task parent-story config]
  (try
    ;; Determine branch name from story or task title
    (let [title (if parent-story
                  (:title parent-story)
                  (:title task))
          branch-source-id (if parent-story
                             (:id parent-story)
                             (:id task))
          branch-name (util/sanitize-branch-name title branch-source-id)

          ;; Get current branch
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

(defn- manage-worktree
  "Manages git worktree for task execution.

  Parameters:
  - base-dir: Base directory of the git repository
  - task: The task being worked on
  - parent-story: The parent story (nil if standalone task)
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
  (manage-worktree \"/path\" task nil config)
  ;; => {:success true :worktree-path \"../mcp-tasks-fix-bug\" :worktree-created? true
  ;;     :needs-directory-switch? true :branch-name \"fix-bug\" :clean? nil
  ;;     :message \"Worktree created at ../mcp-tasks-fix-bug. Please start a new Claude Code session in that directory.\"}

  ;; Worktree exists but not in it
  (manage-worktree \"/path\" task nil config)
  ;; => {:success true :worktree-path \"../mcp-tasks-fix-bug\" :worktree-created? false
  ;;     :needs-directory-switch? true :branch-name \"fix-bug\" :clean? nil
  ;;     :message \"Worktree exists at ../mcp-tasks-fix-bug. Please start a new Claude Code session in that directory.\"}

  ;; In worktree, correct branch, clean
  (manage-worktree \"/path\" task nil config)
  ;; => {:success true :worktree-path \"/path\" :worktree-created? false
  ;;     :needs-directory-switch? false :branch-name \"fix-bug\" :clean? true}

  ;; In worktree, correct branch, dirty
  (manage-worktree \"/path\" task nil config)
  ;; => {:success true :worktree-path \"/path\" :worktree-created? false
  ;;     :needs-directory-switch? false :branch-name \"fix-bug\" :clean? false}

  ;; In worktree, wrong branch (error)
  (manage-worktree \"/path\" task nil config)
  ;; => {:success false :error \"Worktree is on branch 'other' but expected 'fix-bug'\"}"
  [base-dir task parent-story config]
  (try
    ;; Determine branch name and worktree path
    (let [title (if parent-story
                  (:title parent-story)
                  (:title task))
          branch-source-id (if parent-story
                             (:id parent-story)
                             (:id task))
          branch-name (util/sanitize-branch-name title branch-source-id)

          ;; Derive worktree path
          path-result (git/ensure-git-success!
                        (git/derive-worktree-path base-dir title config)
                        "derive-worktree-path")
          worktree-path (:path path-result)

          ;; Get current working directory (canonical path)
          current-dir (.getCanonicalPath (java.io.File. (System/getProperty "user.dir")))
          worktree-canonical-path (when worktree-path
                                    (.getCanonicalPath (java.io.File. worktree-path)))

          ;; Check if worktree exists
          exists-result (git/ensure-git-success!
                          (git/worktree-exists? base-dir worktree-path)
                          "worktree-exists?")
          worktree-exists? (:exists? exists-result)]

      (cond
        ;; Worktree doesn't exist - create it
        (not worktree-exists?)
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
           :message (str "Worktree created at " worktree-path ". Please start a new Claude Code session in that directory.")})

        ;; Worktree exists but we're not in it
        (not= current-dir worktree-canonical-path)
        {:success true
         :worktree-path worktree-path
         :worktree-created? false
         :needs-directory-switch? true
         :branch-name branch-name
         :clean? nil
         :error nil
         :message (str "Worktree exists at " worktree-path ". Please start a new Claude Code session in that directory.")}

        ;; We're in the worktree - verify branch and check clean status
        :else
        (let [;; Check current branch in worktree
              branch-result (git/ensure-git-success!
                              (git/worktree-branch worktree-path)
                              "worktree-branch")
              current-branch (:branch branch-result)]

          ;; Verify we're on the correct branch
          (when (not= current-branch branch-name)
            (throw (ex-info (str "Worktree is on branch " current-branch " but expected " branch-name)
                            {:current-branch current-branch
                             :expected-branch branch-name
                             :worktree-path worktree-path
                             :operation "verify-worktree-branch"})))

          ;; Check if worktree is clean
          (let [changes-result (git/ensure-git-success!
                                 (git/check-uncommitted-changes worktree-path)
                                 "check-uncommitted-changes")
                is-clean? (not (:has-changes? changes-result))]
            {:success true
             :worktree-path worktree-path
             :worktree-created? false
             :needs-directory-switch? false
             :branch-name branch-name
             :clean? is-clean?
             :error nil}))))

    (catch clojure.lang.ExceptionInfo e
      {:success false
       :error (:error (ex-data e) (.getMessage e))
       :metadata (dissoc (ex-data e) :error)})))

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
    ;; Validate task-id parameter
    (when-not task-id
      (let [response-data {:error "task-id parameter is required"
                           :metadata {}}]
        (throw (ex-info "Missing required parameter"
                        {:response response-data}))))

    (when-not (integer? task-id)
      (let [response-data {:error "task-id must be an integer"
                           :metadata {:provided-value task-id
                                      :provided-type (str (type task-id))}}]
        (throw (ex-info "Invalid parameter type"
                        {:response response-data}))))

    ;; Load tasks and validate task exists
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
          (let [response-data {:error "No task found with the specified task-id"
                               :metadata {:task-id task-id
                                          :file tasks-file}}]
            (throw (ex-info "Task not found"
                            {:response response-data}))))

        ;; Handle branch management if configured
        (let [user-config (config/read-config (:base-dir cfg))
              base-dir (:base-dir cfg)

              ;; Get parent story if this is a story task
              parent-story (when-let [parent-id (:parent-id task)]
                             (let [story (first (tasks/get-tasks :task-id parent-id))]
                               ;; Validate parent story exists
                               (when-not story
                                 (throw (ex-info "Parent story not found"
                                                 {:response {:error "Task references a parent story that does not exist"
                                                             :metadata {:task-id task-id
                                                                        :parent-id parent-id
                                                                        :file tasks-file}}})))
                               story))

              ;; Handle branch management if configured
              branch-mgmt-enabled? (:branch-management? user-config)
              branch-info (when branch-mgmt-enabled?
                            (let [branch-result (manage-branch base-dir task parent-story user-config)]
                              (if-not (:success branch-result)
                                ;; Branch management failed - return error response directly
                                (throw (ex-info "Branch management failed"
                                                {:response {:error (:error branch-result)
                                                            :metadata (:metadata branch-result {})}}))
                                branch-result)))

              ;; Handle worktree management if configured
              worktree-mgmt-enabled? (:worktree-management? user-config)
              worktree-info (when worktree-mgmt-enabled?
                              (let [worktree-result (manage-worktree base-dir task parent-story user-config)]
                                (if-not (:success worktree-result)
                                  ;; Worktree management failed - return error response directly
                                  (throw (ex-info "Worktree management failed"
                                                  {:response {:error (:error worktree-result)
                                                              :metadata (:metadata worktree-result {})}}))
                                  worktree-result)))]

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
            (let [;; Write execution state
                  story-id (:parent-id task)
                  started-at (java.time.Instant/now)
                  state {:task-id task-id
                         :story-id story-id
                         :started-at (str started-at)}
                  _ (execution-state/write-execution-state! base-dir state)

                  ;; Build success response with task details
                  state-file-path (str base-dir "/.mcp-tasks-current.edn")
                  base-response {:task-id (:id task)
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
               :isError false})))))

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
