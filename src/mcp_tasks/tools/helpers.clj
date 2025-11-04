(ns mcp-tasks.tools.helpers
  "General helper functions for tool implementations"
  (:require
    [babashka.fs :as fs]
    [cheshire.core :as json]
    [clojure.string :as str]
    [mcp-clj.log :as log]
    [mcp-tasks.tasks :as tasks]
    [mcp-tasks.tools.git :as git])
  (:import
    (java.io
      RandomAccessFile)))

(defn file-exists?
  "Check if a file exists"
  [file-path]
  (fs/exists? file-path))

(defn ensure-file-exists!
  "Ensure a file exists, creating parent directories and empty file if needed.

  On Windows, uses Java File.createNewFile() instead of spit to avoid
  file locking conflicts when immediately opening with RandomAccessFile.

  Parameters:
  - file-path: Absolute path to the file

  Side effects:
  - Creates parent directories if they don't exist
  - Creates empty file if it doesn't exist

  Returns: nil"
  [file-path]
  (when-not (file-exists? file-path)
    (fs/create-dirs (fs/parent file-path))
    (.createNewFile (java.io.File. ^String file-path))))

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
  "Prepare task file for operations WITHOUT git sync.

  **When to use this function:**
  - Read-only operations (e.g., select-tasks) that only query tasks
  - Operations that don't modify tasks.ednl
  - When you explicitly want local-only behavior without network dependency
  - Called internally by `sync-and-prepare-task-file` after successful pull

  **When NOT to use this function:**
  - Tools that MODIFY tasks.ednl - use `sync-and-prepare-task-file` instead
  - Operations where you need the latest remote state before proceeding

  **What it does:**
  1. Loads tasks from tasks.ednl into memory (from local filesystem)
  2. Returns the absolute file path to tasks.ednl

  **Behavior:**
  - No git operations performed
  - Always works with the current local state
  - Fast and predictable (no network dependency)

  **File locking:**
  Read-only operations typically don't need file locking. However, if this
  is called as part of a modification workflow, ensure it's inside a
  `with-task-lock` block.

  **Parameters:**
  - config: Configuration map

  **Returns:**
  - String: Absolute path to tasks.ednl file

  **Trade-offs:**
  - Pros: Fast, no network dependency, simple behavior
  - Cons: May work with stale data if remote has updates
  - Alternative: Use `sync-and-prepare-task-file` for modification operations

  See also: `sync-and-prepare-task-file` for the syncing version used by
  modification tools."
  [config]
  (let [tasks-path (task-path config ["tasks.ednl"])
        tasks-file (:absolute tasks-path)
        complete-path (task-path config ["complete.ednl"])
        complete-file (:absolute complete-path)]
    (when (file-exists? tasks-file)
      (tasks/load-tasks! tasks-file :complete-file complete-file))
    tasks-file))

(defn sync-and-prepare-task-file
  "Synchronizes with git remote and prepares task file for modification.

  **When to use this function:**
  Use this for tools that MODIFY tasks.ednl (add/update/delete operations).
  Ensures the agent works with the latest git state when starting modifications.

  **When NOT to use this function:**
  - Read-only operations (e.g., select-tasks, work-on) - these can load tasks directly
  - Operations that don't modify tasks.ednl

  **What it does:**
  1. Checks if git sync is enabled via :enable-git-sync? config
  2. If disabled, immediately returns prepare-task-file (skips git operations)
  3. If enabled, pulls latest changes from the git remote (if configured)
  4. Loads tasks from tasks.ednl into memory
  5. Returns the tasks file path for subsequent operations

  **Git sync behavior:**
  - Sync disabled (:enable-git-sync? false): Skips all git operations, loads tasks directly
  - Not a git repository: Skips sync, loads tasks normally (local-only repo)
  - Empty git repository: Skips sync, loads tasks normally (no commits yet)
  - No remote configured: Skips sync, loads tasks normally (acceptable)
  - Pull succeeds: Reloads tasks with latest changes
  - Pull conflicts: Returns error map - operation must be aborted
  - Network errors: Returns error map - operation must be aborted

  **File locking:**
  This function should be called INSIDE a `with-task-lock` block to prevent
  concurrent file modifications. The git pull happens after lock acquisition
  but before modification. Git operations themselves are NOT locked - last
  writer wins for commits/pushes.

  **Parameters:**
  - config: Configuration map with :resolved-tasks-dir and :enable-git-sync?

  **Returns:**
  - Success: String path to tasks.ednl file
  - Failure: {:success false :error \"...\" :error-type :conflict|:network|:other}

  **Error handling:**
  Tools should check if the return value is a map with `:success false`:
  ```clojure
  (let [sync-result (helpers/sync-and-prepare-task-file config)]
    (if (and (map? sync-result) (false? (:success sync-result)))
      ;; Handle error - return tool error response
      (helpers/build-tool-error-response
        (case (:error-type sync-result)
          :conflict (str \"Pull failed with conflicts. Resolve manually in \" tasks-dir)
          :network (str \"Pull failed: \" (:error sync-result))
          (str \"Pull failed: \" (:error sync-result)))
        \"tool-name\"
        {:error-type (:error-type sync-result)})
      ;; Success - sync-result is the tasks-file path
      (let [tasks-file sync-result]
        ;; ... modify tasks ...
        (tasks/save-tasks! tasks-file)
        ;; Return result for git commit outside lock
        {...})))))
  ```

  **Trade-offs:**
  - Pros: Agents always work with latest state, reduces conflicts
  - Cons: Slightly slower due to network round-trip, requires network connectivity
  - Alternative: Use `prepare-task-file` for faster local-only operations

  See also: `prepare-task-file` for the simpler non-syncing version."
  [config]
  ;; Check if git sync is enabled
  (if-not (:enable-git-sync? config)
    ;; Sync disabled - skip git operations and just load tasks
    (prepare-task-file config)
    ;; Sync enabled - proceed with git pull
    (let [tasks-dir (:resolved-tasks-dir config)
          branch-result (git/get-current-branch tasks-dir)]
      (if-not (:success branch-result)
        ;; Failed to get current branch - check if it's an acceptable condition
        (let [error-msg (:error branch-result)]
          (if (or (str/includes? error-msg "not a git repository")
                  (str/includes? error-msg "unknown revision"))
            ;; Not a git repository or empty git repository - skip git sync and just load tasks
            (prepare-task-file config)
            ;; Other git error - return error map
            {:success false
             :error error-msg
             :error-type :other}))
        ;; Got branch name - proceed with pull
        (let [pull-result (git/pull-latest tasks-dir (:branch branch-result))]
          (if (:success pull-result)
            ;; Pull succeeded or no remote configured - proceed with loading tasks
            (prepare-task-file config)
            ;; Pull failed - return error map
            {:success false
             :error (:error pull-result)
             :error-type (:error-type pull-result)}))))))

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

  On Windows, tryLock() can throw IOException even when no other process
  holds the lock. This is a Windows-specific behavior where file locking
  can temporarily fail due to OS-level file access conflicts. We retry
  these transient errors until timeout.

  Returns the acquired lock on success, nil on timeout."
  [^java.nio.channels.FileChannel file-channel ^long timeout-ms ^long poll-interval-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [lock-attempt (try
                           (.tryLock file-channel)
                           (catch java.io.IOException e
                             ;; On Windows, tryLock can throw IOException for
                             ;; transient locking conflicts. Treat as if lock
                             ;; was unavailable and retry.
                             (log/debug :lock-attempt-failed-retrying
                                        {:error (.getMessage e)})
                             nil))]
        (if lock-attempt
          lock-attempt
          (let [now (System/currentTimeMillis)]
            (when (< now deadline)
              (Thread/sleep poll-interval-ms)
              (recur))))))))

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
      (let [random-access-file (RandomAccessFile. ^String tasks-file "rw")
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

(defn convert-relations-field
  "Convert relations from JSON structure to Clojure keyword-based structure.

  Handles both keyword keys (from MCP JSON parsing) and string keys (from
  direct function calls). Transforms string keys to keywords for :as-type field.
  Returns [] if relations is nil.

  Note: When updating a task's :relations field, the entire vector is
  replaced, not appended or merged. This design decision ensures
  predictable behavior - users provide the complete desired state rather
  than incremental updates. This matches the story requirements and
  simplifies the mental model for task updates."
  [relations]
  (if relations
    (mapv (fn [rel]
            (let [id (or (get rel :id) (get rel "id"))
                  relates-to (or (get rel :relates-to) (get rel "relates-to"))
                  as-type-val (or (get rel :as-type) (get rel "as-type"))
                  as-type (if (keyword? as-type-val) as-type-val (keyword as-type-val))]
              {:id id
               :relates-to relates-to
               :as-type as-type}))
          relations)
    []))

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
