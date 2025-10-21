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

  Returns a map with:
  - :success - boolean indicating if operation succeeded
  - :branch-name - the target branch name
  - :branch-created? - boolean indicating if branch was created
  - :branch-switched? - boolean indicating if branch was switched
  - :error - error message string (or nil if successful)"
  [base-dir task parent-story]
  ;; Determine branch name from story or task title
  (let [title (if parent-story
                (:title parent-story)
                (:title task))
        task-id (if parent-story
                  (:id parent-story)
                  (:id task))
        branch-name (util/sanitize-branch-name title task-id)]

    ;; Get current branch
    (let [current-branch-result (git/get-current-branch base-dir)]
      (if-not (:success current-branch-result)
        {:success false
         :error (:error current-branch-result)
         :metadata {:operation "get-current-branch"}}

        (let [current-branch (:branch current-branch-result)]
          (if (= current-branch branch-name)
            ;; Already on target branch
            {:success true
             :branch-name branch-name
             :branch-created? false
             :branch-switched? false
             :error nil}

            ;; Need to switch branches
            (let [;; Check for uncommitted changes
                  changes-result (git/check-uncommitted-changes base-dir)]
              (if-not (:success changes-result)
                {:success false
                 :error (:error changes-result)
                 :metadata {:operation "check-uncommitted-changes"}}

                (if (:has-changes? changes-result)
                  {:success false
                   :error "Cannot switch branches with uncommitted changes. Please commit or stash your changes first."
                   :metadata {:current-branch current-branch
                              :target-branch branch-name}}

                  ;; Get base branch (from config or auto-detect)
                  (let [user-config (config/read-config base-dir)
                        configured-base-branch (:base-branch user-config)
                        base-branch-result (if configured-base-branch
                                             ;; Use configured base branch
                                             (let [branch-exists-result (git/branch-exists? base-dir configured-base-branch)]
                                               (if-not (:success branch-exists-result)
                                                 {:success false
                                                  :error (:error branch-exists-result)
                                                  :metadata {:operation "branch-exists?"
                                                             :branch configured-base-branch}}
                                                 (if-not (:exists? branch-exists-result)
                                                   {:success false
                                                    :error (str "Configured base branch '" configured-base-branch "' does not exist")
                                                    :metadata {:base-branch configured-base-branch
                                                               :operation "validate-base-branch"}}
                                                   {:success true :branch configured-base-branch})))
                                             ;; Auto-detect default branch
                                             (let [default-branch-result (git/get-default-branch base-dir)]
                                               (if-not (:success default-branch-result)
                                                 {:success false
                                                  :error (:error default-branch-result)
                                                  :metadata {:operation "get-default-branch"}}
                                                 {:success true :branch (:branch default-branch-result)})))]

                    (if-not (:success base-branch-result)
                      base-branch-result

                      (let [base-branch (:branch base-branch-result)
                            ;; Checkout base branch
                            checkout-base-result (git/checkout-branch base-dir base-branch)]
                        (if-not (:success checkout-base-result)
                          {:success false
                           :error (:error checkout-base-result)
                           :metadata {:operation "checkout-branch"
                                      :branch base-branch}}

                          (do
                            ;; Pull latest (ignore errors for local-only repos)
                            (git/pull-latest base-dir base-branch)

                            ;; Check if target branch exists
                            (let [branch-exists-result (git/branch-exists? base-dir branch-name)]
                              (if-not (:success branch-exists-result)
                                {:success false
                                 :error (:error branch-exists-result)
                                 :metadata {:operation "branch-exists?"
                                            :branch branch-name}}

                                (if (:exists? branch-exists-result)
                                  ;; Branch exists, checkout
                                  (let [checkout-result (git/checkout-branch base-dir branch-name)]
                                    (if (:success checkout-result)
                                      {:success true
                                       :branch-name branch-name
                                       :branch-created? false
                                       :branch-switched? true
                                       :error nil}
                                      {:success false
                                       :error (:error checkout-result)
                                       :metadata {:operation "checkout-branch"
                                                  :branch branch-name}}))

                                  ;; Branch doesn't exist, create and checkout
                                  (let [create-result (git/create-and-checkout-branch base-dir branch-name)]
                                    (if (:success create-result)
                                      {:success true
                                       :branch-name branch-name
                                       :branch-created? true
                                       :branch-switched? true
                                       :error nil}
                                      {:success false
                                       :error (:error create-result)
                                       :metadata {:operation "create-and-checkout-branch"
                                                  :branch branch-name}})))))))))))))))))))

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
              branch-mgmt-enabled? (:branch-management? user-config)
              branch-info (when branch-mgmt-enabled?
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
                                                   story))
                                  base-dir (:base-dir cfg)
                                  branch-result (manage-branch base-dir task parent-story)]
                              (if-not (:success branch-result)
                                ;; Branch management failed - return error response directly
                                (throw (ex-info "Branch management failed"
                                                {:response {:error (:error branch-result)
                                                            :metadata (:metadata branch-result {})}}))
                                branch-result)))]

          ;; Write execution state
          (let [base-dir (:base-dir cfg)
                story-id (:parent-id task)
                started-at (java.time.Instant/now)
                state {:task-id task-id
                       :story-id story-id
                       :started-at (str started-at)}]
            (execution-state/write-execution-state! base-dir state)

            ;; Build success response with task details
            (let [state-file-path (str base-dir "/.mcp-tasks-current.edn")
                  base-response {:task-id (:id task)
                                 :title (:title task)
                                 :category (:category task)
                                 :type (:type task)
                                 :status (:status task)
                                 :execution-state-file state-file-path
                                 :message "Task validated successfully and execution state written"}
                  response-data (if branch-info
                                  (assoc base-response
                                         :branch-name (:branch-name branch-info)
                                         :branch-created? (:branch-created? branch-info)
                                         :branch-switched? (:branch-switched? branch-info))
                                  base-response)]
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
