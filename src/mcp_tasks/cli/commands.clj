(ns mcp-tasks.cli.commands
  "Command implementations for the CLI.
  
  Thin wrappers around mcp-tasks.tools functions.
  
  Uses lazy-loading via dynaload to only load the tool namespaces
  that are actually used, improving startup time for simple commands."
  (:require
    [borkdude.dynaload :refer [dynaload]]
    [cheshire.core :as json]))

(defn- parse-tool-response
  "Parse JSON response from tool *-impl functions.
  
  Different tools return different numbers of content items:
  - select-tasks: 1 content item with JSON
  - add-task/update-task/complete-task/delete-task:
    - Without git: 2 content items (message + JSON with task data)
    - With git: 3 content items (message + JSON with task data + JSON with git status)
  - errors: 2 content items (message + JSON with :error key)
  
  This function parses all JSON content items and merges them into a single map.
  Returns the parsed data map (including error responses)."
  [response]
  (let [content-items (:content response)
        ;; Parse all JSON content items and merge them
        json-items (keep (fn [item]
                           (when-let [text (:text item)]
                             (try
                               (json/parse-string text keyword)
                               (catch Exception _ nil))))
                         content-items)]
    ;; Merge all parsed JSON maps, with later items taking precedence
    (apply merge {} json-items)))

;; Tool function dynaload definitions
;; Lazy-loaded tool functions for CLI commands

(def ^:private select-tasks-tool-fn
  (dynaload 'mcp-tasks.tool.select-tasks/select-tasks-tool))

(def ^:private add-task-tool-fn
  (dynaload 'mcp-tasks.tool.add-task/add-task-tool))

(def ^:private complete-task-tool-fn
  (dynaload 'mcp-tasks.tool.complete-task/complete-task-tool))

(def ^:private update-task-tool-fn
  (dynaload 'mcp-tasks.tool.update-task/update-task-tool))

(def ^:private delete-task-tool-fn
  (dynaload 'mcp-tasks.tool.delete-task/delete-task-tool))

(def ^:private reopen-task-tool-fn
  (dynaload 'mcp-tasks.tool.reopen-task/reopen-task-tool))

(def ^:private tool-map
  "Map of command names to their tool functions.

  Uses dynaload vars for lazy loading."
  {:list select-tasks-tool-fn
   :show select-tasks-tool-fn
   :add add-task-tool-fn
   :complete complete-task-tool-fn
   :update update-task-tool-fn
   :delete delete-task-tool-fn
   :reopen reopen-task-tool-fn
   :why-blocked select-tasks-tool-fn})

(defn- execute-command
  "Execute a command by calling its corresponding tool implementation.
  
  Uses dynaload to lazily load only the tool namespace needed for this command.
  
  Parameters:
  - config: The loaded configuration map
  - command-key: Keyword identifying the command (:list, :show, etc.)
  - parsed-args: Parsed command-line arguments
  - arg-transform-fn: Optional function to transform args before passing to tool (default identity)
  
  Returns the parsed response data from the tool."
  ([config command-key parsed-args]
   (execute-command config command-key parsed-args identity))
  ([config command-key parsed-args arg-transform-fn]
   (let [tool-args (-> parsed-args
                       (dissoc :format)
                       arg-transform-fn)
         tool-fn (get tool-map command-key)
         tool (tool-fn config)
         impl-fn (:implementation tool)
         response (impl-fn nil tool-args)]
     (parse-tool-response response))))

(defn list-command
  "Execute the list command.

  Calls tools/select-tasks-tool implementation and returns the parsed response data.
  Extracts :show-blocking from args and adds it to the response data for formatting."
  [config parsed-args]
  (let [show-blocking (:show-blocking parsed-args)
        response (execute-command config :list parsed-args)]
    (if show-blocking
      (assoc response :show-blocking true)
      response)))

(defn show-command
  "Execute the show command.
  
  Calls tools/select-tasks-tool with unique: true and transforms the response
  to use :task (singular) instead of :tasks to trigger detailed format."
  [config parsed-args]
  (let [response (execute-command config :show parsed-args #(assoc % :unique true))
        ;; Transform {:tasks [...]} to {:task ...} for detailed format
        tasks (:tasks response)]
    (if (seq tasks)
      (-> response
          (assoc :task (first tasks))
          (dissoc :tasks))
      response)))

(defn add-command
  "Execute the add command.
  
  Calls tools/add-task-tool and returns the parsed response data."
  [config parsed-args]
  (execute-command config :add parsed-args))

(defn complete-command
  "Execute the complete command.
  
  Calls tools/complete-task-tool and returns the parsed response data."
  [config parsed-args]
  (execute-command config :complete parsed-args))

(defn update-command
  "Execute the update command.
  
  Calls tools/update-task-tool and returns the parsed response data."
  [config parsed-args]
  (execute-command config :update parsed-args))

(defn delete-command
  "Execute the delete command.

  Calls tools/delete-task-tool and returns the parsed response data."
  [config parsed-args]
  (execute-command config :delete parsed-args))

(defn reopen-command
  "Execute the reopen command.

  Calls tools/reopen-task-tool and returns the parsed response data."
  [config parsed-args]
  (execute-command config :reopen parsed-args))

(defn why-blocked-command
  "Execute the why-blocked command.

  Calls select-tasks-tool with unique: true to get blocking status for a single task.
  Transforms response to :why-blocked format for specialized formatting."
  [config parsed-args]
  (let [response (execute-command config :why-blocked parsed-args #(assoc % :unique true))
        tasks (:tasks response)]
    (if (seq tasks)
      (assoc response :why-blocked (first tasks))
      response)))
