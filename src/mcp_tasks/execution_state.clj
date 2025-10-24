(ns mcp-tasks.execution-state
  "Management of current execution state for stories and tasks.
  
  Uses lazy-loading via requiring-resolve and compiled validators with delays
  to avoid loading Malli at namespace load time."
  (:require
    [babashka.fs :as fs]
    [clojure.edn :as edn]))

;; Schema

(def ExecutionState
  "Schema for current execution state.

  Tracks which story and task are currently being executed by the agent."
  [:map
   [:story-id {:optional true} [:maybe :int]]
   [:task-id :int]
   [:started-at :string]])

;; Validation

;; Compiled validators using delays
;; Both requiring-resolve AND validator compilation happen lazily

(def execution-state-validator
  "Compiled validator for ExecutionState schema."
  (delay ((requiring-resolve 'malli.core/validator) ExecutionState)))

(def execution-state-explainer
  "Compiled explainer for ExecutionState schema."
  (delay ((requiring-resolve 'malli.core/explain) ExecutionState)))

(defn valid-execution-state?
  "Validate an execution state map against the ExecutionState schema."
  [state]
  (@execution-state-validator state))

(defn explain-execution-state
  "Explain why an execution state map is invalid.
  Returns nil if valid, explanation map if invalid."
  [state]
  (@execution-state-explainer state))

;; File Path

(defn- state-file-path
  "Returns path to execution state file for the given base directory."
  [base-dir]
  (str base-dir "/.mcp-tasks-current.edn"))

;; Public API

(defn read-execution-state
  "Read current execution state from file.

  Returns execution state map if file exists and is valid, nil otherwise.
  Logs warnings for malformed or invalid state."
  [base-dir]
  (let [file-path (state-file-path base-dir)]
    (when (fs/exists? file-path)
      (try
        (let [state (edn/read-string (slurp file-path))]
          (if (valid-execution-state? state)
            state
            (do
              (binding [*out* *err*]
                (println (format "Warning: Invalid execution state: %s"
                                 (pr-str (explain-execution-state state)))))
              nil)))
        (catch Exception e
          (binding [*out* *err*]
            (println (format "Warning: Failed to read execution state: %s"
                             (.getMessage e))))
          nil)))))

(defn write-execution-state!
  "Write execution state to file atomically.

  Creates parent directories if needed. Validates state before writing.
  Throws ex-info if state is invalid."
  [base-dir state]
  (when-not (valid-execution-state? state)
    (throw (ex-info "Invalid execution state schema"
                    {:state state
                     :explanation (explain-execution-state state)})))
  (let [file-path (state-file-path base-dir)
        temp-file (str file-path ".tmp")]
    (when-let [parent (fs/parent file-path)]
      (fs/create-dirs parent))
    (spit temp-file (pr-str state))
    (fs/move temp-file file-path {:replace-existing true})))

(defn clear-execution-state!
  "Remove the execution state file if it exists.

  Returns true if file was deleted, false if it didn't exist."
  [base-dir]
  (let [file-path (state-file-path base-dir)]
    (when (fs/exists? file-path)
      (fs/delete file-path)
      true)))
