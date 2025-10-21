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
    [mcp-tasks.execution-state :as execution-state]
    [mcp-tasks.response :as response]
    [mcp-tasks.tasks :as tasks]
    [mcp-tasks.tools.helpers :as helpers]))

(defn- work-on-impl
  "Implementation of work-on tool.

  Validates that the specified task exists, writes execution state, and returns task details.

  Parameters:
  - task-id (required, integer): The ID of the task to work on

  Returns JSON-encoded response with:
  - Task details (id, title, category, type, status)
  - Execution state file path
  - Success indicator

  Writes execution state to .mcp-tasks-current.edn with:
  - task-id: The task being worked on
  - story-id: The parent story ID if this is a story task, nil otherwise
  - started-at: ISO-8601 timestamp when work started

  Branch management will be added in a subsequent iteration."
  [config _context {:keys [task-id]}]
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
    (let [tasks-path (helpers/task-path config ["tasks.ednl"])
          tasks-file (:absolute tasks-path)
          complete-path (helpers/task-path config ["complete.ednl"])
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

        ;; Write execution state
        (let [base-dir (:base-dir config)
              story-id (:parent-id task)
              started-at (java.time.Instant/now)
              state {:task-id task-id
                     :story-id story-id
                     :started-at (str started-at)}]
          (execution-state/write-execution-state! base-dir state)

          ;; Build success response with task details
          (let [state-file-path (str base-dir "/.mcp-tasks-current.edn")
                response-data {:task-id (:id task)
                               :title (:title task)
                               :category (:category task)
                               :type (:type task)
                               :status (:status task)
                               :execution-state-file state-file-path
                               :message "Task validated successfully and execution state written"}]
            {:content [{:type "text"
                        :text (json/write-str response-data)}]
             :isError false}))))

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
