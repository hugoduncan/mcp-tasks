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

;; Session Event Analysis

(def CompactionFinding
  "Schema for a compaction finding.

  Records compaction events from a story that indicate potential issues."
  [:map
   [:finding-type [:= :compaction]]
   [:story-id :int]
   [:story-title :string]
   [:category :string]
   [:events [:vector [:map
                      [:timestamp :string]
                      [:trigger :string]]]]])

(def CorrectionFinding
  "Schema for a correction finding.

  Records user prompts that appear to be corrections or clarifications."
  [:map
   [:finding-type [:= :correction]]
   [:story-id :int]
   [:story-title :string]
   [:category :string]
   [:events [:vector [:map
                      [:timestamp :string]
                      [:content :string]
                      [:matched-patterns [:vector :string]]]]]])

(def RestartFinding
  "Schema for a restart finding.

  Records session restarts that may indicate workflow problems."
  [:map
   [:finding-type [:= :restart]]
   [:story-id :int]
   [:story-title :string]
   [:category :string]
   [:events [:vector [:map
                      [:timestamp :string]
                      [:trigger :string]
                      [:session-id {:optional true} :string]]]]])

(def AnalysisResult
  "Schema for the complete analysis result.

  Contains all findings categorized by type."
  [:map
   [:compactions [:vector CompactionFinding]]
   [:corrections [:vector CorrectionFinding]]
   [:restarts [:vector RestartFinding]]
   [:story-count :int]
   [:event-count :int]])

(def ^:private correction-patterns
  "Patterns that suggest a user prompt is a correction or clarification.

  Each pattern is a case-insensitive regex that matches common correction
  phrases in user prompts."
  [#"(?i)^no[,\s]"
   #"(?i)^wait[,\s]"
   #"(?i)^actually[,\s]"
   #"(?i)^instead[,\s]"
   #"(?i)\bthat'?s\s+(not\s+)?(wrong|incorrect)"
   #"(?i)\bi\s+meant"
   #"(?i)\bi\s+said"
   #"(?i)\bnot\s+what\s+i"
   #"(?i)\bfix\s+(that|this|it)"
   #"(?i)\bundo\s+(that|this|it)"
   #"(?i)\brevert\s+(that|this|it)"
   #"(?i)\bwrong\s+(file|path|function|method)"
   #"(?i)\bshould\s+(be|have\s+been)"
   #"(?i)\bdon'?t\s+do\s+that"
   #"(?i)\bstop\s+and"])

(defn- matches-correction-pattern?
  "Check if content matches any correction pattern.
  Returns vector of matched pattern descriptions, or nil if no matches."
  [content]
  (when (and content (string? content))
    (let [matches (->> correction-patterns
                       (keep (fn [pattern]
                               (when (re-find pattern content)
                                 (str pattern))))
                       vec)]
      (when (seq matches)
        matches))))

(defn- extract-compactions
  "Extract compaction events from session events.
  Returns vector of maps with :timestamp and :trigger."
  [events]
  (->> events
       (filter #(= :compaction (:event-type %)))
       (mapv (fn [e]
               {:timestamp (:timestamp e)
                :trigger (or (:trigger e) "unknown")}))))

(defn- extract-corrections
  "Extract user prompts that appear to be corrections.
  Returns vector of maps with :timestamp, :content, and :matched-patterns."
  [events]
  (->> events
       (filter #(= :user-prompt (:event-type %)))
       (keep (fn [e]
               (when-let [patterns (matches-correction-pattern? (:content e))]
                 {:timestamp (:timestamp e)
                  :content (:content e)
                  :matched-patterns patterns})))
       vec))

(defn- extract-restarts
  "Extract session restart events (excluding initial startup).
  Returns vector of maps with :timestamp, :trigger, and optional :session-id."
  [events]
  (let [session-starts (->> events
                            (filter #(= :session-start (:event-type %)))
                            vec)]
    ;; First session-start is expected, subsequent ones are restarts
    (->> (rest session-starts)
         (mapv (fn [e]
                 (cond-> {:timestamp (:timestamp e)
                          :trigger (or (:trigger e) "unknown")}
                   (:session-id e) (assoc :session-id (:session-id e))))))))

(defn analyze-story-events
  "Analyze session events from a single story.

  Returns a map with :compactions, :corrections, and :restarts findings
  for the story, or nil if no issues found."
  [story]
  (let [events (:session-events story [])
        story-id (:id story)
        story-title (:title story "")
        category (:category story "unknown")
        compactions (extract-compactions events)
        corrections (extract-corrections events)
        restarts (extract-restarts events)]
    (when (or (seq compactions) (seq corrections) (seq restarts))
      {:story-id story-id
       :story-title story-title
       :category category
       :compactions compactions
       :corrections corrections
       :restarts restarts})))

(defn analyze-session-events
  "Analyze session events from multiple stories.

  Takes a collection of stories with :session-events and returns
  an AnalysisResult map with all findings categorized by type.

  Parameters:
  - stories: Collection of story maps with :session-events

  Returns:
  {:compactions [...] :corrections [...] :restarts [...]
   :story-count N :event-count M}"
  [stories]
  (let [analyses (->> stories
                      (map analyze-story-events)
                      (remove nil?)
                      vec)
        compaction-findings (->> analyses
                                 (filter #(seq (:compactions %)))
                                 (mapv (fn [a]
                                         {:finding-type :compaction
                                          :story-id (:story-id a)
                                          :story-title (:story-title a)
                                          :category (:category a)
                                          :events (:compactions a)})))
        correction-findings (->> analyses
                                 (filter #(seq (:corrections %)))
                                 (mapv (fn [a]
                                         {:finding-type :correction
                                          :story-id (:story-id a)
                                          :story-title (:story-title a)
                                          :category (:category a)
                                          :events (:corrections a)})))
        restart-findings (->> analyses
                              (filter #(seq (:restarts %)))
                              (mapv (fn [a]
                                      {:finding-type :restart
                                       :story-id (:story-id a)
                                       :story-title (:story-title a)
                                       :category (:category a)
                                       :events (:restarts a)})))
        total-events (->> stories
                          (mapcat :session-events)
                          count)]
    {:compactions compaction-findings
     :corrections correction-findings
     :restarts restart-findings
     :story-count (count stories)
     :event-count total-events}))
