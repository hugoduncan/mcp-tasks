(ns mcp-tasks.tasks-file
  "Low-level file operations for EDNL (EDN Lines) task storage.

  EDNL format stores one task per line as an EDN map. All write operations
  are atomic using temp files."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [mcp-tasks.schema :as schema]))

;; Helper Functions

(defn- ensure-parent-dir
  "Create parent directory if it doesn't exist."
  [file]
  (when-let [parent (.getParentFile (io/file file))]
    (.mkdirs parent)))

(defn- read-task-line
  "Parse a single line as EDN and validate against Task schema.

  Returns task map if valid, nil if invalid (with warning logged)."
  [line line-number]
  (when-not (str/blank? line)
    (try
      (let [task (edn/read-string line)]
        (if (schema/valid-task? task)
          task
          (do
            (binding [*out* *err*]
              (println (format "Warning: Invalid task at line %d: %s"
                               line-number
                               (pr-str (schema/explain-task task)))))
            nil)))
      (catch Exception e
        (binding [*out* *err*]
          (println (format "Warning: Malformed EDN at line %d: %s"
                           line-number
                           (.getMessage e))))
        nil))))

(defn- write-ednl-atomic
  "Write tasks to file atomically using temp file and rename.

  Creates parent directories if needed."
  [file-path tasks]
  (ensure-parent-dir file-path)
  (let [file (io/file file-path)
        temp-file (io/file (str file-path ".tmp"))
        content (str/join "\n" (map pr-str tasks))]
    (spit temp-file content)
    (.renameTo temp-file file)))

;; Public API

(defn read-ednl
  "Read all tasks from an EDNL (EDN Lines) file.

  Returns vector of task maps. Missing files return empty vector.
  Malformed or invalid lines are skipped with warnings."
  [file-path]
  (let [file (io/file file-path)]
    (if (.exists file)
      (let [content (slurp file-path)
            lines (str/split-lines content)]
        (into []
              (keep-indexed (fn [idx line]
                              (read-task-line line (inc idx))))
              lines))
      [])))

(defn append-task
  "Append a task to the end of an EDNL file.

  Write operation is atomic. Validates task against schema before writing.
  Creates parent directories if needed."
  [file-path task]
  (when-not (schema/valid-task? task)
    (throw (ex-info "Invalid task schema"
                    {:task task
                     :explanation (schema/explain-task task)})))
  (let [existing-tasks (read-ednl file-path)
        new-tasks (conj existing-tasks task)]
    (write-ednl-atomic file-path new-tasks)))

(defn prepend-task
  "Prepend a task to the beginning of an EDNL file.

  Write operation is atomic. Validates task against schema before writing.
  Creates parent directories if needed."
  [file-path task]
  (when-not (schema/valid-task? task)
    (throw (ex-info "Invalid task schema"
                    {:task task
                     :explanation (schema/explain-task task)})))
  (let [existing-tasks (read-ednl file-path)
        new-tasks (into [task] existing-tasks)]
    (write-ednl-atomic file-path new-tasks)))

(defn replace-task
  "Replace a task by id in an EDNL file.

  Write operation is atomic. Validates task against schema before writing.
  Throws ex-info if task id not found."
  [file-path task]
  (when-not (schema/valid-task? task)
    (throw (ex-info "Invalid task schema"
                    {:task task
                     :explanation (schema/explain-task task)})))
  (let [existing-tasks (read-ednl file-path)
        task-id (:id task)
        task-index (first (keep-indexed
                            (fn [idx t]
                              (when (= (:id t) task-id)
                                idx))
                            existing-tasks))]
    (when (nil? task-index)
      (throw (ex-info "Task not found"
                      {:id task-id
                       :file file-path})))
    (let [new-tasks (assoc existing-tasks task-index task)]
      (write-ednl-atomic file-path new-tasks))))

(defn delete-task
  "Remove a task by id from an EDNL file.

  Write operation is atomic. Throws ex-info if task id not found."
  [file-path id]
  (let [existing-tasks (read-ednl file-path)
        new-tasks (into []
                        (remove #(= (:id %) id))
                        existing-tasks)]
    (when (= (count new-tasks) (count existing-tasks))
      (throw (ex-info "Task not found"
                      {:id id
                       :file file-path})))
    (write-ednl-atomic file-path new-tasks)))

(defn write-tasks
  "Write a collection of tasks to an EDNL file.

  Write operation is atomic. Validates all tasks against schema before writing.
  Creates parent directories if needed."
  [file-path tasks]
  (doseq [task tasks]
    (when-not (schema/valid-task? task)
      (throw (ex-info "Invalid task schema"
                      {:task task
                       :explanation (schema/explain-task task)}))))
  (write-ednl-atomic file-path tasks))
