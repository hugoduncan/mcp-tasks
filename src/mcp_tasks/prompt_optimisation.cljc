(ns mcp-tasks.prompt-optimisation
  "Management of prompt optimisation state.

  Tracks which stories have been analyzed for prompt improvement opportunities
  and records modifications made to prompts.

  Uses lazy-loading via dynaload and compiled validators to avoid loading
  Malli at namespace load time. When AOT-compiled with
  -Dborkdude.dynaload.aot=true, dynaload enables direct linking for reduced
  binary size."
  (:require
    [babashka.fs :as fs]
    [borkdude.dynaload :refer [dynaload]]
    [clojure.edn :as edn]
    [mcp-tasks.tasks-file :as tasks-file]))

;; Schema Definitions

(def Modification
  "Schema for a prompt modification record.

  Records a single change made to a prompt file during optimization."
  [:map
   [:timestamp :string]
   [:prompt-path :string]
   [:change-summary :string]])

(def OptimisationState
  "Schema for prompt optimisation state.

  Tracks:
  - When the last optimization run occurred
  - Which stories have been processed
  - What modifications have been made"
  [:map
   [:last-run [:maybe :string]]
   [:processed-story-ids [:set :int]]
   [:modifications [:vector Modification]]])

;; Validation

(def ^:private malli-validator
  "Lazy reference to malli.core/validator.
  Falls back to a function returning always-true validator when Malli unavailable."
  (dynaload 'malli.core/validator {:default (constantly (fn [_] true))}))

(def ^:private malli-explainer
  "Lazy reference to malli.core/explainer.
  Falls back to a function returning always-nil explainer when Malli unavailable."
  (dynaload 'malli.core/explainer {:default (constantly (fn [_] nil))}))

(def modification-validator
  "Compiled validator for Modification schema."
  (malli-validator Modification))

(def optimisation-state-validator
  "Compiled validator for OptimisationState schema."
  (malli-validator OptimisationState))

(def modification-explainer
  "Compiled explainer for Modification schema."
  (malli-explainer Modification))

(def optimisation-state-explainer
  "Compiled explainer for OptimisationState schema."
  (malli-explainer OptimisationState))

(defn valid-modification?
  "Validate a modification map against the Modification schema."
  [modification]
  (modification-validator modification))

(defn valid-optimisation-state?
  "Validate an optimisation state map against the OptimisationState schema."
  [state]
  (optimisation-state-validator state))

(defn explain-modification
  "Explain why a modification map is invalid.
  Returns nil if valid, explanation map if invalid."
  [modification]
  (modification-explainer modification))

(defn explain-optimisation-state
  "Explain why an optimisation state map is invalid.
  Returns nil if valid, explanation map if invalid."
  [state]
  (optimisation-state-explainer state))

;; State File Path

(defn state-file-path
  "Returns path to optimisation state file for the given config directory."
  [config-dir]
  (str config-dir "/.mcp-tasks/prompt-optimisation.edn"))

;; Initial State

(def initial-state
  "Initial state for a new optimisation tracking file."
  {:last-run nil
   :processed-story-ids #{}
   :modifications []})

;; Public API

(defn read-state
  "Read optimisation state from file.

  Returns state map if file exists and is valid, nil otherwise.
  Logs warnings for malformed or invalid state."
  [config-dir]
  (let [file-path (state-file-path config-dir)]
    (when (fs/exists? file-path)
      (try
        (let [state (edn/read-string (slurp file-path))]
          (if (valid-optimisation-state? state)
            state
            (do
              (binding [*out* *err*]
                (println (format "Warning: Invalid optimisation state: %s"
                                 (pr-str (explain-optimisation-state state)))))
              nil)))
        (catch Exception e
          (binding [*out* *err*]
            (println (format "Warning: Failed to read optimisation state: %s"
                             (.getMessage e))))
          nil)))))

(defn write-state!
  "Write optimisation state to file atomically.

  Creates parent directories if needed. Validates state before writing.
  Throws ex-info if state is invalid."
  [config-dir state]
  (when-not (valid-optimisation-state? state)
    (throw (ex-info "Invalid optimisation state schema"
                    {:state state
                     :explanation (explain-optimisation-state state)})))
  (let [file-path (state-file-path config-dir)
        temp-file (str file-path ".tmp")]
    (when-let [parent (fs/parent file-path)]
      (fs/create-dirs parent))
    (spit temp-file (pr-str state))
    (fs/move temp-file file-path {:replace-existing true})))

(defn init-state!
  "Initialize optimisation state file if it doesn't exist.

  Returns the state (either existing or newly created initial state)."
  [config-dir]
  (if-let [existing (read-state config-dir)]
    existing
    (do
      (write-state! config-dir initial-state)
      initial-state)))

;; Example Data

(def example-modification
  "Example modification for testing and documentation."
  {:timestamp "2025-01-15T10:30:00Z"
   :prompt-path "category-prompts/simple.md"
   :change-summary "Reduced verbosity"})

(def example-state
  "Example optimisation state for testing and documentation."
  {:last-run "2025-01-15T10:30:00Z"
   :processed-story-ids #{100 101 102}
   :modifications [example-modification]})

;; Story Collection

(defn complete-file-path
  "Returns path to complete.ednl file for the given config directory."
  [config-dir]
  (str config-dir "/.mcp-tasks/complete.ednl"))

(defn collect-unprocessed-stories
  "Collect stories from complete.ednl that need analysis.

  Returns stories that:
  - Have :type :story
  - Have non-empty :session-events
  - Are not in the state's :processed-story-ids

  Parameters:
  - config-dir: Directory containing .mcp-tasks/
  - state: OptimisationState map (from read-state or init-state!)

  Returns vector of story task maps ready for analysis.
  Returns empty vector if no unprocessed stories found."
  [config-dir state]
  (let [complete-path (complete-file-path config-dir)
        processed-ids (:processed-story-ids state #{})
        all-tasks (tasks-file/read-ednl complete-path)]
    (->> all-tasks
         (filter #(= :story (:type %)))
         (filter #(seq (:session-events %)))
         (remove #(contains? processed-ids (:id %)))
         vec)))
