(ns mcp-tasks.cli.commands
  "Command implementations for the CLI.
  
  Thin wrappers around mcp-tasks.tools functions."
  (:require
    [clojure.data.json :as json]
    [mcp-tasks.tools :as tools]))

(defn- parse-tool-response
  "Parse JSON response from tool *-impl functions.
  
  Returns the parsed data map, or throws if the response indicates an error."
  [response]
  (let [content (get-in response [:content 0 :text])
        is-error (:isError response)]
    (if is-error
      (throw (ex-info content {:type :tool-error}))
      (json/read-str content :key-fn keyword))))

(defn list-command
  "Execute the list command.
  
  Calls tools/select-tasks-tool implementation and returns the parsed response data."
  [config parsed-args]
  (let [tool-args (dissoc parsed-args :format)
        tool (tools/select-tasks-tool config)
        impl-fn (:implementation tool)
        response (impl-fn nil tool-args)]
    (parse-tool-response response)))

(defn show-command
  "Execute the show command.
  
  Calls tools/select-tasks-tool with unique: true and returns the parsed response data."
  [config parsed-args]
  (let [tool-args (-> parsed-args
                      (dissoc :format)
                      (assoc :unique true))
        tool (tools/select-tasks-tool config)
        impl-fn (:implementation tool)
        response (impl-fn nil tool-args)]
    (parse-tool-response response)))

(defn add-command
  "Execute the add command.
  
  Calls tools/add-task-tool and returns the parsed response data."
  [config parsed-args]
  (let [tool-args (dissoc parsed-args :format)
        tool (tools/add-task-tool config)
        impl-fn (:implementation tool)
        response (impl-fn nil tool-args)]
    (parse-tool-response response)))

(defn complete-command
  "Execute the complete command.
  
  Calls tools/complete-task-tool and returns the parsed response data."
  [config parsed-args]
  (let [tool-args (dissoc parsed-args :format)
        tool (tools/complete-task-tool config)
        impl-fn (:implementation tool)
        response (impl-fn nil tool-args)]
    (parse-tool-response response)))

(defn update-command
  "Execute the update command.
  
  Calls tools/update-task-tool and returns the parsed response data."
  [config parsed-args]
  (let [tool-args (dissoc parsed-args :format)
        tool (tools/update-task-tool config)
        impl-fn (:implementation tool)
        response (impl-fn nil tool-args)]
    (parse-tool-response response)))

(defn delete-command
  "Execute the delete command.
  
  Calls tools/delete-task-tool and returns the parsed response data."
  [config parsed-args]
  (let [tool-args (dissoc parsed-args :format)
        tool (tools/delete-task-tool config)
        impl-fn (:implementation tool)
        response (impl-fn nil tool-args)]
    (parse-tool-response response)))
