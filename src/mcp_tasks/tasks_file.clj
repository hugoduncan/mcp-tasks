(ns mcp-tasks.tasks-file
  "Low-level file operations for EDNL (EDN Lines) task storage.

  EDNL format stores one task per line as an EDN map. All write operations
  are atomic using temp files."
  (:require
    [babashka.fs :as fs]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [mcp-tasks.schema :as schema]))

(def ^:dynamic *locked-file-content*
  "When bound, contains pre-read content of a locked file.

  Used on Windows where exclusive file locks prevent opening additional
  file handles. The with-task-lock macro binds this to a map with:
  - :file-path - absolute path of the locked file
  - :content - file content read through the locked RandomAccessFile

  This avoids the need to open a second FileInputStream via slurp."
  nil)

(def ^:dynamic *locked-file-handle*
  "When bound, contains the RandomAccessFile handle for a locked file.

  Used on Windows where exclusive file locks prevent moving/renaming the
  locked file. The with-task-lock macro binds this to a map with:
  - :file-path - absolute path of the locked file
  - :raf - RandomAccessFile instance

  Allows writing through the existing handle instead of atomic write."
  nil)

;; Helper Functions

(defn- ensure-parent-dir
  "Create parent directory if it doesn't exist."
  [file]
  (when-let [parent (fs/parent file)]
    (fs/create-dirs parent)))

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

  On Windows, when the file is locked by with-task-lock, writes directly
  through the locked RandomAccessFile handle to avoid mandatory locking
  issues with file move operations.

  Creates parent directories if needed."
  [file-path tasks]
  (let [use-locked-handle? (and *locked-file-handle*
                                (= file-path (:file-path *locked-file-handle*)))]
    (if use-locked-handle?
      ;; Write through the locked RAF handle
      (let [raf (:raf *locked-file-handle*)
            content (str/join "\n" (map pr-str tasks))
            bytes (.getBytes content java.nio.charset.StandardCharsets/UTF_8)]
        (.seek raf 0)             ; Reset to start of file
        (.write raf bytes)        ; Write content
        (.setLength raf (alength bytes)))  ; Truncate to new size
      ;; Normal atomic write via temp file
      (do
        (ensure-parent-dir file-path)
        (let [temp-file (str file-path ".tmp")
              content (str/join "\n" (map pr-str tasks))]
          (spit temp-file content)
          (fs/move temp-file file-path {:replace-existing true}))))))

;; Public API

(defn read-ednl
  "Read all tasks from an EDNL (EDN Lines) file.

  Returns vector of task maps. Missing files return empty vector.
  Malformed or invalid lines are skipped with warnings.

  On Windows, when the file is locked by with-task-lock, uses the
  pre-read content from *locked-file-content* instead of opening
  a new file handle via slurp. Only uses cached content if reading
  the same file that was locked."
  [file-path]
  (let [use-cached? (and *locked-file-content*
                         (= file-path (:file-path *locked-file-content*)))]
    (if (or use-cached? (fs/exists? file-path))
      (let [content (if use-cached?
                      (:content *locked-file-content*)
                      (slurp file-path))
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
