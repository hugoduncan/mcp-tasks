(ns mcp-tasks.cli.commands
  "Command implementations for the CLI.
  
  Thin wrappers around mcp-tasks.tools functions."
  (:require
    [clojure.data.json :as json]
    [mcp-tasks.tool.add-task :as add-task]
    [mcp-tasks.tool.complete-task :as complete-task]
    [mcp-tasks.tool.delete-task :as delete-task]
    [mcp-tasks.tool.select-tasks :as select-tasks]
    [mcp-tasks.tool.update-task :as update-task]))

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
                               (json/read-str text :key-fn keyword)
                               (catch Exception _ nil))))
                         content-items)]
    ;; Merge all parsed JSON maps, with later items taking precedence
    (apply merge {} json-items)))

(def ^:private tool-map
  "Map of command names to their corresponding tool functions."
  {:list select-tasks/select-tasks-tool
   :show select-tasks/select-tasks-tool
   :add add-task/add-task-tool
   :complete complete-task/complete-task-tool
   :update update-task/update-task-tool
   :delete delete-task/delete-task-tool})

(defn- execute-command
  "Execute a command by calling its corresponding tool implementation.
  
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
  
  Calls tools/select-tasks-tool implementation and returns the parsed response data."
  [config parsed-args]
  (execute-command config :list parsed-args))

(defn show-command
  "Execute the show command.
  
  Calls tools/select-tasks-tool with unique: true and returns the parsed response data."
  [config parsed-args]
  (execute-command config :show parsed-args #(assoc % :unique true)))

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
