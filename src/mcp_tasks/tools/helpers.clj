(ns mcp-tasks.tools.helpers
  "General helper functions for tool implementations"
  (:require
    [babashka.fs :as fs]
    [cheshire.core :as json]
    [clojure.string :as str]
    [mcp-clj.log :as log]
    [mcp-tasks.tasks :as tasks])
  (:import
    (java.io
      RandomAccessFile)))

(defn file-exists?
  "Check if a file exists"
  [file-path]
  (fs/exists? file-path))

(defn ensure-file-exists!
  "Ensure a file exists, creating parent directories and empty file if needed.

  Parameters:
  - file-path: Absolute path to the file

  Side effects:
  - Creates parent directories if they don't exist
  - Creates empty file if it doesn't exist

  Returns: nil"
  [file-path]
  (when-not (file-exists? file-path)
    (fs/create-dirs (fs/parent file-path))
    (spit file-path "")))

(defn task-path
  "Construct task directory paths using resolved tasks directory from config.

  Parameters:
  - config: Configuration map containing :resolved-tasks-dir

  - path-segments: Vector of path segments
                   (e.g., [\"tasks.ednl\"] or [\"story\" \"stories\" \"foo.md\"])

  Returns map with:
  - :absolute - Full filesystem path
  - :relative - Path relative to tasks directory root (for git operations)

  Examples:
    (task-path {:resolved-tasks-dir \"/home/user/.mcp-tasks\"} [\"tasks.ednl\"])
    => {:absolute \"/home/user/.mcp-tasks/tasks.ednl\"
        :relative \"tasks.ednl\"}

    (task-path {:resolved-tasks-dir \"/custom/tasks\"} [\"complete.ednl\"])
    => {:absolute \"/custom/tasks/complete.ednl\"
        :relative \"complete.ednl\"}"
  [config path-segments]
  (let [resolved-tasks-dir (:resolved-tasks-dir config)
        relative-path (str/join "/" path-segments)
        absolute-path (str resolved-tasks-dir "/" relative-path)]
    {:absolute absolute-path
     :relative relative-path}))

(defn prepare-task-file
  "Prepare task file for adding a task.

  Loads tasks from tasks.ednl into memory.
  Returns the absolute file path."
  [config]
  (let [tasks-path (task-path config ["tasks.ednl"])
        tasks-file (:absolute tasks-path)
        complete-path (task-path config ["complete.ednl"])
        complete-file (:absolute complete-path)]
    (when (file-exists? tasks-file)
      (tasks/load-tasks! tasks-file :complete-file complete-file))
    tasks-file))

(defn truncate-title
  "Truncate a title to a maximum length, adding ellipsis if needed.

  Parameters:
  - title: The title string to truncate
  - max-length: Maximum length (default 50)

  Returns truncated string with '...' suffix if longer than max-length."
  ([title]
   (truncate-title title 50))
  ([title max-length]
   (if (> (count title) max-length)
     (str (subs title 0 (- max-length 3)) "...")
     title)))

(defn build-tool-error-response
  "Build standardized two-content-item error response.

  Parameters:
  - error-message: Human-readable error message (string)
  - operation: Operation that failed (string)
  - error-metadata: Additional metadata map to include

  Returns error response map with :content and :isError keys."
  [error-message operation error-metadata]
  {:content [{:type "text"
              :text error-message}
             {:type "text"
              :text (json/generate-string
                      {:error error-message
                       :metadata (merge {:attempted-operation operation}
                                        error-metadata)})}]
   :isError true})

(defn- try-acquire-lock-with-timeout
  "Attempt to acquire file lock with timeout using polling.
  
  Polling is necessary because Java's FileChannel.tryLock() doesn't support
  timeout parameters - it either succeeds immediately or returns nil.
  
  Returns the acquired lock on success, nil on timeout."
  [file-channel timeout-ms poll-interval-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (if-let [acquired-lock (.tryLock file-channel)]
        acquired-lock
        (let [now (System/currentTimeMillis)]
          (when (< now deadline)
            (Thread/sleep poll-interval-ms)
            (recur)))))))

(defn with-task-lock
  "Execute function f while holding an exclusive file lock on tasks.ednl.

  Provides cross-process file locking to prevent concurrent task modifications.
  Uses polling-based timeout (required by Java FileLock API limitations).

  Parameters:
  - config: Configuration map with optional :lock-timeout-ms (default 30000ms)
            and :lock-poll-interval-ms (default 100ms)
  - f: Function to execute while holding the lock (no arguments)

  Returns:
  - Always returns a map (never throws exceptions)
  - Success: Result of calling (f) - typically a map with :isError false
  - Timeout: Tool error map {:content [...] :isError true}
  - Lock error: Tool error map {:content [...] :isError true}
  - Function error: Tool error map {:content [...] :isError true}

  Resource management:
  - Opens RandomAccessFile and gets its FileChannel
  - Acquires exclusive lock with timeout
  - Ensures lock release, channel close, and RAF close in finally block
  - Cleans up resources even on exceptions

  Lock acquisition:
  - Uses .tryLock() with polling (configurable interval, default 100ms)
  - Default timeout: 30000ms (30 seconds)
  - Polling is necessary due to Java FileLock API constraints"
  [config f]
  (let [tasks-path (task-path config ["tasks.ednl"])
        tasks-file (:absolute tasks-path)
        lock-timeout-ms (or (:lock-timeout-ms config) 30000)
        poll-interval-ms (or (:lock-poll-interval-ms config) 100)
        raf (atom nil)
        channel (atom nil)
        lock (atom nil)]
    (try
      ;; Ensure file exists before attempting lock
      (ensure-file-exists! tasks-file)

      ;; Open RandomAccessFile and get its channel
      (let [random-access-file (RandomAccessFile. tasks-file "rw")
            file-channel (.getChannel random-access-file)]
        (reset! raf random-access-file)
        (reset! channel file-channel)

        ;; Try to acquire lock with timeout
        (if-let [acquired-lock (try-acquire-lock-with-timeout
                                 file-channel
                                 lock-timeout-ms
                                 poll-interval-ms)]
          (do
            (reset! lock acquired-lock)
            ;; Lock acquired - execute function with error handling
            (try
              (f)
              (catch Exception e
                ;; Convert any exception from function execution to error map
                (build-tool-error-response
                  (str "Error during task operation: " (.getMessage e))
                  "with-task-lock"
                  {:file tasks-file
                   :error-type (-> e class .getName)
                   :message (.getMessage e)}))))
          ;; Lock acquisition timed out
          (build-tool-error-response
            (str "Failed to acquire lock on tasks file after "
                 lock-timeout-ms "ms. "
                 "Another process may be modifying tasks.")
            "with-task-lock"
            {:file tasks-file
             :timeout-ms lock-timeout-ms})))

      (catch java.io.IOException e
        (build-tool-error-response
          (str "Failed to access lock file: " (.getMessage e))
          "with-task-lock"
          {:file tasks-file
           :error-type "io-error"
           :message (.getMessage e)}))

      (finally
        ;; Always release lock, close channel, and close RAF
        (when-let [l @lock]
          (try
            (.release l)
            (catch Exception e
              (log/warn :lock-release-failed
                        {:error (.getMessage e)
                         :file tasks-file}))))
        (when-let [ch @channel]
          (try
            (.close ch)
            (catch Exception e
              (log/warn :channel-close-failed
                        {:error (.getMessage e)
                         :file tasks-file}))))
        (when-let [r @raf]
          (try
            (.close r)
            (catch Exception e
              (log/warn :raf-close-failed
                        {:error (.getMessage e)
                         :file tasks-file}))))))))

(defn setup-completion-context
  "Prepares common context for task completion and deletion operations.

  Parameters:
  - config: Configuration map with :use-git?, :base-dir
  - tool-name: Name of the calling tool (for error messages)

  Returns either:
  - Error response map (with :isError true) if tasks file not found
  - Context map with:
    - :use-git? - Whether git integration is enabled
    - :tasks-file - Absolute path to tasks.ednl
    - :complete-file - Absolute path to complete.ednl
    - :tasks-rel-path - Relative path to tasks.ednl
    - :complete-rel-path - Relative path to complete.ednl
    - :base-dir - Base directory (may be nil)"
  [config tool-name]
  (let [use-git? (:use-git? config)
        tasks-path (task-path config ["tasks.ednl"])
        complete-path (task-path config ["complete.ednl"])
        tasks-file (:absolute tasks-path)
        complete-file (:absolute complete-path)
        tasks-rel-path (:relative tasks-path)
        complete-rel-path (:relative complete-path)]

    (if-not (file-exists? tasks-file)
      (build-tool-error-response
        "Tasks file not found"
        tool-name
        {:file tasks-file})

      (do
        (tasks/load-tasks! tasks-file :complete-file complete-file)
        {:use-git? use-git?
         :tasks-file tasks-file
         :complete-file complete-file
         :tasks-rel-path tasks-rel-path
         :complete-rel-path complete-rel-path
         :base-dir (:base-dir config)}))))

(defn build-completion-response
  "Build standardized completion response with optional git integration.

  Parameters:
  - msg-text: Human-readable completion message (string)
  - modified-files: Vector of relative file paths that were modified
  - use-git?: Whether git integration is enabled (boolean)
  - git-result: Optional map with :success, :commit-sha, :error keys
  - task-data: Optional map with completed task data (for complete/delete operations)

  Returns response map with :content and :isError keys.
  
  Response structure:
  - Git disabled, no task-data: 1 item (message)
  - Git disabled, with task-data: 2 items (message, task-data JSON)
  - Git enabled, no task-data: 3 items (message, modified-files data, git status)
  - Git enabled, with task-data: 3 items (message, task-data + modified-files, git status)"
  ([msg-text modified-files use-git? git-result]
   (build-completion-response msg-text modified-files use-git? git-result nil))
  ([msg-text modified-files use-git? git-result task-data]
   (if use-git?
     ;; Git enabled: always 3 items
     (let [task-data-with-files (if task-data
                                  (assoc task-data :modified-files modified-files)
                                  {:modified-files modified-files})]
       {:content [{:type "text" :text msg-text}
                  {:type "text" :text (json/generate-string task-data-with-files)}
                  {:type "text"
                   :text (json/generate-string
                           (cond-> {:git-status (if (:success git-result)
                                                  "success"
                                                  "error")
                                    :git-commit (:commit-sha git-result)}
                             (:error git-result)
                             (assoc :git-error (:error git-result))))}]
        :isError false})
     ;; Git disabled: 1 or 2 items depending on task-data
     (if task-data
       {:content [{:type "text" :text msg-text}
                  {:type "text" :text (json/generate-string task-data)}]
        :isError false}
       {:content [{:type "text" :text msg-text}]
        :isError false}))))
