(ns mcp-tasks.cli.format
  "Output formatters for the CLI.
  
  Supports EDN, JSON, and human-readable formats."
  (:require
    [clojure.data.json :as json]
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

;; Table formatting

(defn format-table-row
  "Format a single row of the table with proper column widths."
  [id parent-id status category title max-title-width]
  (format "%4s  %-8s  %-12s  %-10s  %s"
          (or id "")
          (if parent-id (str parent-id) "")
          (truncate-text status 12)
          (truncate-text category 10)
          (truncate-text title max-title-width)))

(defn format-table
  "Format a vector of tasks as an ASCII table.
  
  Columns: ID, Parent, Status, Category, Title (truncated)"
  [tasks]
  (if (empty? tasks)
    "No tasks found"
    (let [max-title-width 50
          header (format "%4s  %-8s  %-12s  %-10s  %s" "ID" "Parent" "Status" "Category" "Title")
          separator (str/join (repeat (+ 4 2 8 2 12 2 10 2 max-title-width) "-"))
          rows (map (fn [task]
                      (format-table-row
                        (:id task)
                        (:parent-id task)
                        (format-status (:status task))
                        (:category task)
                        (:title task)
                        max-title-width))
                    tasks)]
      (str/join "\n" (concat [header separator] rows)))))

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
  (json/write-str (transform-keys data kebab->camel)))

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
          task-output (if (= 1 task-count)
                        (format-single-task (first tasks))
                        (format-table tasks))
          git-output (format-git-metadata data)]
      (str/join "\n\n"
                (filter some?
                        [task-output
                         git-output
                         ;; Only show metadata if there are tasks
                         (when (and metadata (pos? task-count))
                           (str "Total: " (:total-matches metadata)
                                (when (:limited? metadata)
                                  (str " (showing " (:count metadata) ")"))))])))

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
