(ns mcp-tasks.cli.format
  "Output formatters for the CLI.

  Supports EDN, JSON, and human-readable formats."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]))

;; Key transformation

(defn kebab->camel
  "Convert a kebab-case keyword to camelCase string.

  Examples:
    :task-id => \"taskId\"
    :parent-id => \"parentId\"
    :status => \"status\""
  [k]
  (let [s (name k)
        parts (str/split s #"-")]
    (if (= 1 (count parts))
      s
      (str (first parts)
           (str/join (map str/capitalize (rest parts)))))))

(defn transform-keys
  "Recursively transform all keys in a map using the provided function.

  Handles nested maps, vectors of maps, and preserves other data types."
  [m key-fn]
  (cond
    (map? m)
    (into {} (map (fn [[k v]]
                    [(key-fn k) (transform-keys v key-fn)])
                  m))

    (vector? m)
    (mapv #(transform-keys % key-fn) m)

    :else
    m))

;; Text formatting helpers

(defn truncate-text
  "Truncate text to max-length characters, adding ellipsis if truncated."
  [text max-length]
  (let [text (or text "")]
    (if (<= (count text) max-length)
      text
      (str (subs text 0 (- max-length 3)) "..."))))

(defn format-relations
  "Format relations vector to human-readable string.

  Examples:
    [{:as-type :blocked-by :relates-to 4}] => \"blocked-by->#4\"
    [{:as-type :related :relates-to 5} {:as-type :blocked-by :relates-to 6}]
      => \"related->#5, blocked-by->#6\""
  [relations]
  (when (seq relations)
    (str/join ", "
              (map (fn [{:keys [as-type relates-to]}]
                     (str (name as-type) "->#" relates-to))
                   relations))))

(defn format-status
  "Format status with visual indicator."
  [status]
  (case status
    :open "○ open"
    :closed "✓ closed"
    :in-progress "◐ in-progress"
    :blocked "✗ blocked"
    :deleted "⊗ deleted"
    nil "○ open"
    (name status)))

(defn format-meta
  "Format meta map for table display.

  Returns a string representation, using '-' for empty meta."
  [meta]
  (if (seq meta)
    (pr-str meta)
    "-"))

(defn format-blocked-indicator
  "Format blocked indicator for table display.

  Returns '⊠' if task is blocked, empty string otherwise."
  [is-blocked]
  (if is-blocked "⊠" ""))

;; Table formatting

(defn format-table-row
  "Format a single row of the table with proper column widths."
  [blocked-indicator id parent-id status category meta title max-title-width]
  (format "%-2s  %4s  %-8s  %-12s  %-10s  %-20s  %s"
          blocked-indicator
          (or id "")
          (if parent-id (str parent-id) "")
          (truncate-text status 12)
          (truncate-text category 10)
          (truncate-text meta 20)
          (truncate-text title max-title-width)))

(defn format-blocking-details
  "Format blocking task details for display.

  Returns formatted string showing which tasks are blocking each task.
  Only includes tasks that have blocking tasks."
  [tasks]
  (let [blocked-tasks (filter :is-blocked tasks)]
    (when (seq blocked-tasks)
      (str "\n\nBlocking Details:\n"
           (str/join "\n"
                     (map (fn [task]
                            (let [blocking-ids (:blocking-task-ids task)]
                              (str "  Task #" (:id task) " blocked by: "
                                   (str/join ", " (map #(str "#" %) blocking-ids)))))
                          blocked-tasks))))))

(defn format-table
  "Format a vector of tasks as an ASCII table.

  Columns: B (blocked indicator), ID, Parent, Status, Category, Meta, Title (truncated)

  Options (from opts map):
  - :show-blocking - If true, append blocking details after table"
  ([tasks]
   (format-table tasks {}))
  ([tasks opts]
   (if (empty? tasks)
     "No tasks found"
     (let [max-title-width 50
           header (format "%-2s  %4s  %-8s  %-12s  %-10s  %-20s  %s" "B" "ID" "Parent" "Status" "Category" "Meta" "Title")
           separator (str/join (repeat (+ 2 2 4 2 8 2 12 2 10 2 20 2 max-title-width) "-"))
           rows (map (fn [task]
                       (format-table-row
                         (format-blocked-indicator (:is-blocked task))
                         (:id task)
                         (:parent-id task)
                         (format-status (:status task))
                         (:category task)
                         (format-meta (:meta task))
                         (:title task)
                         max-title-width))
                     tasks)
           table-output (str/join "\n" (concat [header separator] rows))
           blocking-details (when (:show-blocking opts)
                              (format-blocking-details tasks))]
       (str table-output blocking-details)))))

;; Single task formatting

(defn format-single-task
  "Format a single task with all details in multi-line format."
  [task]
  (let [lines [(str "Task #" (or (:id task) "?") ": " (or (:title task) "Untitled"))
               (str "Status: " (name (or (:status task) :open)))
               (str "Category: " (or (:category task) "unknown"))
               (str "Type: " (name (or (:type task) :task)))]]
    (str/join "\n"
              (cond-> lines
                ;; Add parent-id if present
                (:parent-id task)
                (conj (str "Parent: #" (:parent-id task)))

                ;; Add relations if present
                (seq (:relations task))
                (conj (str "Relations: " (format-relations (:relations task))))

                ;; Add metadata if present
                (seq (:meta task))
                (conj (str "Meta: " (pr-str (:meta task))))

                ;; Add description if non-empty
                (and (:description task) (not (str/blank? (:description task))))
                (conj "Description:"
                      (str "  " (str/replace (:description task) #"\n" "\n  ")))

                ;; Add design if non-empty
                (and (:design task) (not (str/blank? (:design task))))
                (conj "Design:"
                      (str "  " (str/replace (:design task) #"\n" "\n  ")))))))

(defn format-why-blocked
  "Format why-blocked response showing task blocking status.

  Shows task ID, title, blocked status, and details about blocking tasks."
  [task]
  (let [task-id (:id task)
        title (:title task)
        is-blocked (:is-blocked task)
        blocking-ids (:blocking-task-ids task)
        circular-dep (:circular-dependency task)
        error (:error task)]
    (str/join "\n"
              (filter some?
                      [(str "Task #" task-id ": " title)
                       (if is-blocked
                         "Status: BLOCKED"
                         "Status: Not blocked")
                       (when (seq blocking-ids)
                         (str "Blocked by tasks: " (str/join ", " (map #(str "#" %) blocking-ids))))
                       (when circular-dep
                         (str "Circular dependency detected: " (str/join " → " (map #(str "#" %) circular-dep))))
                       (when error
                         (str "Error: " error))]))))

;; Error formatting

(defn format-error
  "Format error response for human-readable output."
  [data]
  (let [error-msg (:error data)
        metadata (:metadata data)]
    (str "Error: " error-msg "\n"
         (when (seq metadata)
           (str/join "\n"
                     (map (fn [[k v]]
                            (str "  " (name k) ": " v))
                          metadata))))))

;; Git metadata formatting

(defn format-git-metadata
  "Format git metadata (commit SHA, status, errors)."
  [git-data]
  (when git-data
    (let [status (:git-status git-data)
          sha (:git-commit git-data)
          error (:git-error git-data)
          lines (cond-> []
                  status
                  (conj (str "Git Status: " status))

                  sha
                  (conj (str "Commit: " sha))

                  error
                  (conj (str "Git Error: " error)))]
      (when (seq lines)
        (str/join "\n" lines)))))

;; Prompts formatting

(defn format-prompts-list
  "Format prompts list response for human-readable output.

  Displays prompts grouped by type (category/workflow) with aligned columns."
  [prompts metadata]
  (let [category-prompts (filter #(= :category (:type %)) prompts)
        workflow-prompts (filter #(= :workflow (:type %)) prompts)
        max-name-width (apply max 0 (map #(count (:name %)) prompts))
        format-prompt (fn [p]
                        (str "  "
                             (format (str "%-" max-name-width "s") (:name p))
                             "  "
                             (:description p)))]
    (str/join "\n"
              (filter some?
                      ["Available Prompts:"
                       ""
                       (when (seq category-prompts)
                         (str "Category Prompts (" (count category-prompts) "):"))
                       (str/join "\n" (map format-prompt category-prompts))
                       (when (and (seq category-prompts) (seq workflow-prompts))
                         "")
                       (when (seq workflow-prompts)
                         (str "Workflow Prompts (" (count workflow-prompts) "):"))
                       (str/join "\n" (map format-prompt workflow-prompts))
                       ""
                       (str "Total: " (:total-count metadata)
                            " prompts (" (:category-count metadata)
                            " category, " (:workflow-count metadata) " workflow)")]))))

(defn format-prompts-install
  "Format prompts install response for human-readable output.

  Shows installation results with status indicators and paths."
  [results metadata]
  (let [format-result (fn [r]
                        (case (:status r)
                          :installed
                          (str "✓ " (:name r) " (" (name (:type r)) ")\n"
                               "  → " (:path r))

                          :exists
                          (str "✓ " (:name r) " (" (name (:type r)) ")\n"
                               "  → " (:path r) " (already exists)")

                          :not-found
                          (str "✗ " (:name r) "\n"
                               "  Error: " (:error r))

                          :error
                          (str "✗ " (:name r) " (" (name (:type r)) ")\n"
                               "  Error: " (:error r))

                          (str "? " (:name r) "\n"
                               "  Unknown status: " (:status r))))]
    (str/join "\n\n"
              (filter some?
                      ["Installing prompts..."
                       ""
                       (str/join "\n\n" (map format-result results))
                       ""
                       (str "Summary: " (:installed-count metadata) " installed, "
                            (:failed-count metadata) " failed")]))))

(defn format-prompts-show
  "Format prompts show response for human-readable output.

  Displays prompt metadata header followed by content."
  [data]
  (let [name (:name data)
        type (:type data)
        source (:source data)
        path (:path data)
        content (:content data)
        metadata (:metadata data)
        ;; Format metadata fields with proper labels
        metadata-lines (when (seq metadata)
                         (keep (fn [[k v]]
                                 (when v
                                   (let [label (-> k
                                                   (str/replace "-" " ")
                                                   str/capitalize)]
                                     (str label ": " v))))
                               (sort-by first metadata)))]
    (str/join "\n"
              (concat [(str "Prompt: " name)
                       (str "Type: " (clojure.core/name type))
                       (str "Source: " (clojure.core/name source))]
                      metadata-lines
                      [(str "Path: " path)
                       ""
                       "---"
                       ""
                       content]))))

;; Multimethod for format dispatch

(defmulti render
  "Render data in the specified format.

  Dispatches on format-type (:edn, :json, or :human).
  Returns formatted string output."
  (fn [format-type _data] format-type))

(defmethod render :edn
  [_ data]
  (pr-str data))

(defmethod render :json
  [_ data]
  (json/generate-string (transform-keys data kebab->camel)))

(defmethod render :human
  [_ data]
  (cond
    ;; Error response
    (:error data)
    (format-error data)

    ;; Response with tasks
    (:tasks data)
    (let [tasks (:tasks data)
          metadata (:metadata data)
          task-count (count tasks)
          show-blocking (:show-blocking data)
          task-output (format-table tasks {:show-blocking show-blocking})
          git-output (format-git-metadata data)]
      (str/join "\n\n"
                (filter some?
                        [task-output
                         git-output
                         ;; Only show metadata if there are tasks
                         (when (and metadata (pos? task-count))
                           (str "Total: " (:total-matches metadata)
                                (when (:limited? metadata)
                                  (str " (showing " (:returned-count metadata) ")"))))])))

    ;; Single task response (for add/update/complete operations)
    (:task data)
    (let [task (:task data)
          metadata (:metadata data)
          operation (:operation metadata)
          ;; Add operation-specific success message
          success-msg (case operation
                        "complete-task" (str "Task #" (:id task) " completed")
                        "add-task" (str "Added task #" (:id task))
                        "update-task" (str "Updated task #" (:id task))
                        nil)
          task-output (format-single-task task)
          git-output (format-git-metadata data)]
      (str/join "\n\n"
                (filter some?
                        [success-msg
                         task-output
                         (when metadata
                           (str "File: " (:file metadata)))
                         git-output])))

    ;; Deleted task response
    (:deleted data)
    (let [task (:deleted data)
          task-output (str "Deleted Task #" (:id task) ": " (:title task))
          git-output (format-git-metadata data)]
      (str/join "\n\n" (filter some? [task-output git-output])))

    ;; Why-blocked response
    (:why-blocked data)
    (format-why-blocked (:why-blocked data))

    ;; Prompts list response
    (:prompts data)
    (format-prompts-list (:prompts data) (:metadata data))

    ;; Prompts install response
    (:results data)
    (format-prompts-install (:results data) (:metadata data))

    ;; Prompts show response (has :name and :content)
    (and (:name data) (:content data))
    (format-prompts-show data)

    ;; Generic response with modified files
    (:modified-files data)
    (str "Modified files:\n"
         (str/join "\n" (map #(str "  " %) (:modified-files data))))

    ;; Git status only
    (:git-status data)
    (format-git-metadata data)

    ;; Fallback: just pr-str
    :else
    (pr-str data)))

(defmethod render :default
  [format-type _data]
  (throw (ex-info (str "Unknown format type: " format-type)
                  {:format-type format-type
                   :valid-formats [:edn :json :human]})))
