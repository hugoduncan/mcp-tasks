(ns mcp-tasks.prompt-optimisation
  "Management of prompt optimisation state.

  Tracks which stories have been analyzed for prompt improvement opportunities
  and records modifications made to prompts."
  (:require
    [babashka.fs :as fs]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [mcp-tasks.schema :as schema]
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

(def modification-validator
  "Compiled validator for Modification schema."
  (schema/malli-validator Modification))

(def optimisation-state-validator
  "Compiled validator for OptimisationState schema."
  (schema/malli-validator OptimisationState))

(def modification-explainer
  "Compiled explainer for Modification schema."
  (schema/malli-explainer Modification))

(def optimisation-state-explainer
  "Compiled explainer for OptimisationState schema."
  (schema/malli-explainer OptimisationState))

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

;; Formatting Constants

(def ^:private sample-content-max-length
  "Maximum length for sample content in findings display.
  Content longer than this is truncated with '...' suffix."
  60)

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

  Each entry is a vector of [pattern description] where pattern is a
  case-insensitive regex and description is a human-readable label."
  [[#"(?i)^no[,\s]" "starts with 'no'"]
   [#"(?i)^wait[,\s]" "starts with 'wait'"]
   [#"(?i)^actually[,\s]" "starts with 'actually'"]
   [#"(?i)^instead[,\s]" "starts with 'instead'"]
   [#"(?i)\bthat'?s\s+(not\s+)?(wrong|incorrect)" "says 'that's wrong/incorrect'"]
   [#"(?i)\bi\s+meant" "contains 'I meant'"]
   [#"(?i)\bi\s+said" "contains 'I said'"]
   [#"(?i)\bnot\s+what\s+i" "contains 'not what I'"]
   [#"(?i)\bfix\s+(that|this|it)" "requests 'fix that/this/it'"]
   [#"(?i)\bundo\s+(that|this|it)" "requests 'undo that/this/it'"]
   [#"(?i)\brevert\s+(that|this|it)" "requests 'revert that/this/it'"]
   [#"(?i)\bwrong\s+(file|path|function|method)" "mentions 'wrong file/path/function/method'"]
   [#"(?i)\bshould\s+(be|have\s+been)" "contains 'should be/have been'"]
   [#"(?i)\bdon'?t\s+do\s+that" "says 'don't do that'"]
   [#"(?i)\bstop\s+and" "says 'stop and'"]])

(def ^:private combined-correction-pattern
  "Pre-compiled combined regex for fast initial matching.

  Combines all correction patterns using alternation for O(1) initial check.
  If this matches, individual patterns are checked for specific descriptions."
  (re-pattern
    (str "(?i)"
         (str/join "|"
                   ["^no[,\\s]"
                    "^wait[,\\s]"
                    "^actually[,\\s]"
                    "^instead[,\\s]"
                    "\\bthat'?s\\s+(not\\s+)?(wrong|incorrect)"
                    "\\bi\\s+meant"
                    "\\bi\\s+said"
                    "\\bnot\\s+what\\s+i"
                    "\\bfix\\s+(that|this|it)"
                    "\\bundo\\s+(that|this|it)"
                    "\\brevert\\s+(that|this|it)"
                    "\\bwrong\\s+(file|path|function|method)"
                    "\\bshould\\s+(be|have\\s+been)"
                    "\\bdon'?t\\s+do\\s+that"
                    "\\bstop\\s+and"]))))

(defn- matches-correction-pattern?
  "Check if content matches any correction pattern.

  Uses pre-compiled combined pattern for fast initial check. Only iterates
  through individual patterns when the combined pattern matches.
  Returns vector of matched pattern descriptions, or nil if no matches."
  [content]
  (when (and content (string? content)
             (re-find combined-correction-pattern content))
    ;; Combined pattern matched - identify specific patterns for descriptions
    (let [matches (->> correction-patterns
                       (keep (fn [[pattern description]]
                               (when (re-find pattern content)
                                 description)))
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

;; Findings Presentation

(defn- format-timestamp-list
  "Format a list of timestamps for display.
  Returns comma-separated short time strings."
  [events]
  (->> events
       (map :timestamp)
       (map #(if (and (string? %) (> (count %) 11))
               (subs % 11 16)  ; Extract HH:MM from ISO timestamp
               %))
       (str/join ", ")))

(defn- format-compaction-diagnosis
  "Generate diagnosis text for compaction events."
  [events]
  (let [auto-count (count (filter #(= "auto" (:trigger %)) events))
        manual-count (count (filter #(= "manual" (:trigger %)) events))]
    (cond
      (> auto-count 1)
      "Multiple auto-compactions suggest context exceeded limits repeatedly"

      (and (pos? auto-count) (pos? manual-count))
      "Mix of auto and manual compactions indicates context pressure"

      (pos? auto-count)
      "Auto-compaction indicates context limit reached"

      :else
      "Manual compaction may indicate user perceived context issues")))

(defn- format-compaction-fix
  "Generate proposed fix for compaction finding."
  [category]
  (format "Consider: (a) simplify category-prompts/%s.md, (b) break tasks into smaller pieces, (c) improve task specifications"
          category))

(defn format-compaction-finding
  "Format a single compaction finding as markdown.

  Parameters:
  - finding: CompactionFinding map
  - index: 1-based index for numbering

  Returns markdown string for this finding."
  [finding index]
  (let [{:keys [story-id story-title category events]} finding
        event-summary (str (count events) " compaction(s) at "
                           (format-timestamp-list events))
        triggers (->> events (map :trigger) distinct (str/join ", "))]
    (format (str "**%d. Story #%d \"%s\" (category: %s)**\n"
                 "- Events: %s (triggers: %s)\n"
                 "- Diagnosis: %s\n"
                 "- Proposed fix: %s\n")
            index story-id story-title category
            event-summary triggers
            (format-compaction-diagnosis events)
            (format-compaction-fix category))))

(defn- format-correction-diagnosis
  "Generate diagnosis text for correction events."
  [events]
  (let [pattern-count (->> events (mapcat :matched-patterns) distinct count)]
    (if (> (count events) 1)
      (format "Multiple corrections (%d) detected, suggesting unclear or ambiguous instructions"
              (count events))
      (format "User correction detected (matched %d pattern%s)"
              pattern-count (if (> pattern-count 1) "s" "")))))

(defn- format-correction-fix
  "Generate proposed fix for correction finding."
  [category]
  (format "Review category-prompts/%s.md for ambiguous language; add explicit examples or constraints"
          category))

(defn format-correction-finding
  "Format a single correction finding as markdown.

  Parameters:
  - finding: CorrectionFinding map
  - index: 1-based index for numbering

  Returns markdown string for this finding."
  [finding index]
  (let [{:keys [story-id story-title category events]} finding
        sample-content (-> events first :content)
        truncated (if (> (count sample-content) sample-content-max-length)
                    (str (subs sample-content 0 (- sample-content-max-length 3)) "...")
                    sample-content)]
    (format (str "**%d. Story #%d \"%s\" (category: %s)**\n"
                 "- Sample: \"%s\"\n"
                 "- Diagnosis: %s\n"
                 "- Proposed fix: %s\n")
            index story-id story-title category
            truncated
            (format-correction-diagnosis events)
            (format-correction-fix category))))

(defn- format-restart-diagnosis
  "Generate diagnosis text for restart events."
  [events]
  (let [triggers (->> events (map :trigger) frequencies)]
    (cond
      (get triggers "resume")
      "Session resumed, possibly after interruption or context issues"

      (get triggers "clear")
      "Context was cleared, indicating workflow reset"

      (get triggers "compact")
      "Session restarted after compaction"

      :else
      (format "Session restarted %d time(s)" (count events)))))

(defn- format-restart-fix
  "Generate proposed fix for restart finding."
  [_category]
  "Investigate why restarts occurred; may indicate task too large or unclear workflow")

(defn format-restart-finding
  "Format a single restart finding as markdown.

  Parameters:
  - finding: RestartFinding map
  - index: 1-based index for numbering

  Returns markdown string for this finding."
  [finding index]
  (let [{:keys [story-id story-title category events]} finding
        triggers (->> events (map :trigger) (str/join ", "))]
    (format (str "**%d. Story #%d \"%s\" (category: %s)**\n"
                 "- Restarts: %d (triggers: %s)\n"
                 "- Diagnosis: %s\n"
                 "- Proposed fix: %s\n")
            index story-id story-title category
            (count events) triggers
            (format-restart-diagnosis events)
            (format-restart-fix category))))

(defn- format-findings-section
  "Format a section of findings with header.

  Parameters:
  - title: Section title (e.g., \"Compactions\")
  - findings: Vector of findings
  - format-fn: Function to format each finding (takes finding and index)
  - start-index: Starting index for numbering

  Returns [markdown-string next-index]."
  [title findings format-fn start-index]
  (if (empty? findings)
    ["" start-index]
    (let [header (format "### %s (%d)\n\n" title (count findings))
          formatted (->> findings
                         (map-indexed (fn [i f]
                                        (format-fn f (+ start-index i 1))))
                         (str/join "\n"))]
      [(str header formatted) (+ start-index (count findings))])))

(defn format-findings
  "Format all findings as markdown for presentation.

  Takes an AnalysisResult map and returns a markdown string suitable
  for display to the user. Findings are grouped by type and numbered
  sequentially for interactive selection.

  Parameters:
  - analysis: AnalysisResult map from analyze-session-events

  Returns markdown string with all findings, or a message if no findings."
  [analysis]
  (let [{:keys [compactions corrections restarts story-count event-count]} analysis
        total-findings (+ (count compactions) (count corrections) (count restarts))]
    (if (zero? total-findings)
      (format "## Prompt Optimization Findings\n\nAnalyzed %d stories with %d events. No issues found.\n"
              story-count event-count)
      (let [summary (format (str "## Prompt Optimization Findings\n\n"
                                 "Analyzed %d stories with %d events. "
                                 "Found %d compaction(s), %d correction(s), %d restart(s).\n\n")
                            story-count event-count
                            (count compactions) (count corrections) (count restarts))
            [compaction-md idx1] (format-findings-section "Compactions" compactions
                                                          format-compaction-finding 0)
            [correction-md idx2] (format-findings-section "Corrections" corrections
                                                          format-correction-finding idx1)
            [restart-md _] (format-findings-section "Restarts" restarts
                                                    format-restart-finding idx2)]
        (str summary compaction-md correction-md restart-md)))))

;; State Update

(defn- validate-modifications!
  "Validate each modification individually.

  Throws ex-info with specific error for the first invalid modification."
  [modifications]
  (doseq [[idx modification] (map-indexed vector modifications)]
    (when-not (valid-modification? modification)
      (throw (ex-info (format "Invalid modification at index %d" idx)
                      {:index idx
                       :modification modification
                       :explanation (explain-modification modification)})))))

(defn record-optimization-run!
  "Record the results of an optimization run to the state file.

  Updates the state with:
  - :last-run set to the provided timestamp
  - :processed-story-ids merged with the new story IDs
  - :modifications appended with new modifications

  Parameters:
  - config-dir: Directory containing .mcp-tasks/
  - timestamp: ISO-8601 timestamp for :last-run
  - story-ids: Collection of story IDs that were processed
  - modifications: Vector of Modification maps to append

  Returns the updated state map.
  Throws ex-info if any modification is invalid or state validation fails."
  [config-dir timestamp story-ids modifications]
  (validate-modifications! modifications)
  (let [current-state (or (read-state config-dir) initial-state)
        updated-state (-> current-state
                          (assoc :last-run timestamp)
                          (update :processed-story-ids into story-ids)
                          (update :modifications into modifications))]
    (write-state! config-dir updated-state)
    updated-state))
