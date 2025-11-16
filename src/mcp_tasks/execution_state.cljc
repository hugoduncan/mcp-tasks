(ns mcp-tasks.execution-state
  "Management of current execution state for stories and tasks.

  Uses lazy-loading via dynaload and compiled validators to avoid loading
  Malli at namespace load time. When AOT-compiled with
  -Dborkdude.dynaload.aot=true, dynaload enables direct linking for reduced
  binary size."
  (:require
    [babashka.fs :as fs]
    [borkdude.dynaload :refer [dynaload]]
    [clojure.edn :as edn]))

;; Schema

(def ExecutionState
  "Schema for current execution state.

  Tracks which story and task are currently being executed by the agent.

  Valid states:
  - Story-level (after child task completion): {:story-id 123, :task-start-time \"...\"}
  - Active task with story: {:story-id 123, :task-id 456, :task-start-time \"...\"}
  - Standalone task: {:task-id 789, :task-start-time \"...\"}

  Validation logic:
  - :task-start-time is always required
  - :task-id is optional when :story-id is present
  - :task-id is required when :story-id is absent"
  [:and
   [:map
    [:story-id {:optional true} [:maybe :int]]
    [:task-id {:optional true} :int]
    [:task-start-time :string]]
   [:fn
    {:error/message "task-id is required when story-id is absent"}
    (fn [{:keys [story-id task-id]}]
      (or (some? story-id) (some? task-id)))]])

;; Validation

(def ^:private malli-validator
  "Lazy reference to malli.core/validator.
  Falls back to a function returning always-true validator when Malli unavailable."
  (dynaload 'malli.core/validator {:default (constantly (fn [_] true))}))

(def ^:private malli-explainer
  "Lazy reference to malli.core/explainer.
  Falls back to a function returning always-nil explainer when Malli unavailable."
  (dynaload 'malli.core/explainer {:default (constantly (fn [_] nil))}))

(def execution-state-validator
  "Compiled validator for ExecutionState schema."
  (malli-validator ExecutionState))

(def execution-state-explainer
  "Compiled explainer for ExecutionState schema."
  (malli-explainer ExecutionState))

(defn valid-execution-state?
  "Validate an execution state map against the ExecutionState schema."
  [state]
  (execution-state-validator state))

(defn explain-execution-state
  "Explain why an execution state map is invalid.
  Returns nil if valid, explanation map if invalid."
  [state]
  (execution-state-explainer state))

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

(defn update-execution-state-for-child-completion!
  "Update execution state after child task completion.

  Reads current state and:
  - If state has :story-id: writes back with only :story-id and :task-start-time
    (removes :task-id to indicate story-level state)
  - If state has no :story-id: clears the file (defensive fallback)

  Returns the updated state map if successful, or nil if state was cleared.
  Uses atomic file operations for consistency."
  [base-dir]
  (let [current-state (read-execution-state base-dir)]
    (if (and current-state (:story-id current-state))
      (let [story-level-state {:story-id (:story-id current-state)
                               :task-start-time (:task-start-time current-state)}]
        (write-execution-state! base-dir story-level-state)
        story-level-state)
      (do
        (clear-execution-state! base-dir)
        nil))))
