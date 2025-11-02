(ns mcp-tasks.tool.execution-state
  "MCP tool for managing current execution state.

  This namespace implements the execution-state tool, which records
  or clears which story and task are currently being executed by the agent.
  The state is written to .mcp-tasks-current.edn in the project root for
  external discoverability."
  (:require
    [cheshire.core :as json]
    [mcp-tasks.execution-state :as es]
    [mcp-tasks.tools.helpers :as helpers]))

(defn- write-state
  "Write execution state.

  Parameters map must contain:
  - :task-id - ID of task being executed
  - :task-start-time - ISO-8601 timestamp
  - :story-id - (optional) ID of story containing task"
  [base-dir {:keys [story-id task-id task-start-time]}]
  (let [state (cond-> {:task-id task-id
                       :task-start-time task-start-time}
                story-id (assoc :story-id story-id))]
    (es/write-execution-state! base-dir state)
    {:content [{:type "text"
                :text (json/generate-string
                        {:message (str "Execution state written: task-id=" task-id
                                       (when story-id (str ", story-id=" story-id)))
                         :state-file (str base-dir "/.mcp-tasks-current.edn")
                         :state state})}]
     :isError false}))

(defn- clear-state
  "Clear execution state."
  [base-dir]
  (let [deleted? (es/clear-execution-state! base-dir)]
    {:content [{:type "text"
                :text (json/generate-string
                        {:message (if deleted?
                                    "Execution state cleared"
                                    "No execution state to clear")
                         :state-file (str base-dir "/.mcp-tasks-current.edn")
                         :deleted? deleted?})}]
     :isError false}))

(defn- execution-state-impl
  "Implementation of execution-state tool.

  Parameters:
  - action: (required) Either \"write\" to record state or \"clear\" to remove it
  - task-id: (required if action=write) ID of the task being executed
  - task-start-time: (required if action=write) ISO-8601 timestamp when execution started
  - story-id: (optional) ID of the story being executed

  Returns:
  - Success response with state file path
  - Error response if validation fails"
  [config _context {:keys [action story-id task-id task-start-time]}]
  (let [base-dir (:base-dir config)]
    (try
      (case action
        "write"
        (if (and task-id task-start-time)
          (write-state base-dir {:story-id story-id
                                 :task-id task-id
                                 :task-start-time task-start-time})
          (helpers/build-tool-error-response
            "Missing required parameters for write action"
            "execution-state"
            {:action action
             :missing (cond-> []
                        (not task-id) (conj "task-id")
                        (not task-start-time) (conj "task-start-time"))}))

        "clear"
        (clear-state base-dir)

        ;; Invalid action
        (helpers/build-tool-error-response
          (str "Invalid action: " action ". Must be \"write\" or \"clear\"")
          "execution-state"
          {:action action
           :valid-actions ["write" "clear"]}))

      (catch clojure.lang.ExceptionInfo e
        (helpers/build-tool-error-response
          (str "Invalid execution state: " (.getMessage e))
          "execution-state"
          (merge {:base-dir base-dir}
                 (ex-data e))))
      (catch Exception e
        (helpers/build-tool-error-response
          (str "Failed to manage execution state: " (.getMessage e))
          "execution-state"
          {:base-dir base-dir
           :error (.getMessage e)})))))

(defn- description
  "Generate description for execution-state tool."
  [_config]
  "Manage execution state to track which story and task are being executed.

   Supports two actions:
   - \"write\": Record execution state to .mcp-tasks-current.edn
   - \"clear\": Remove execution state file

   This enables external tools to monitor agent progress and coordinate work.
   Stale executions can be detected via the task-start-time timestamp.

   Parameters:
   - action: (required) \"write\" or \"clear\"
   - task-id: (required if action=write) ID of the task being executed
   - task-start-time: (required if action=write) ISO-8601 timestamp when execution started
   - story-id: (optional) ID of the story containing this task")

(defn execution-state-tool
  "Tool to manage current execution state for agent tracking.

  Records or clears which story and task are currently being executed to enable
  external monitoring and coordination."
  [config]
  {:name "execution-state"
   :description (description config)
   :inputSchema
   {:type "object"
    :properties
    {"action"
     {:type "string"
      :enum ["write" "clear"]
      :description "Action to perform: \"write\" to record state, \"clear\" to remove it"}
     "task-id"
     {:type "integer"
      :description "ID of the task being executed (required if action=write)"}
     "task-start-time"
     {:type "string"
      :description "ISO-8601 timestamp when execution started (required if action=write, e.g., \"2025-10-20T14:30:00Z\")"}
     "story-id"
     {:type "integer"
      :description "(Optional) ID of the story containing this task"}}
    :required ["action"]}
   :implementation (partial execution-state-impl config)})
