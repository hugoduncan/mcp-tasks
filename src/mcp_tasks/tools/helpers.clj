(ns mcp-tasks.tools.helpers
  "General helper functions for tool implementations"
  (:require
    [babashka.fs :as fs]
    [clojure.data.json :as json]
    [clojure.string :as str]
    [mcp-tasks.tasks :as tasks]))

(defn file-exists?
  "Check if a file exists"
  [file-path]
  (fs/exists? file-path))

(defn task-path
  "Construct .mcp-tasks paths with base-dir handling.

  Parameters:
  - config: Configuration map containing optional :base-dir

  - path-segments: Vector of path segments
                   (e.g., [\"story\" \"stories\" \"foo.md\"])

  Returns map with:
  - :absolute - Full filesystem path
  - :relative - Path relative to .mcp-tasks root (for git operations)

  Examples:
    (task-path {} [\"tasks.ednl\"])
    => {:absolute \".mcp-tasks/tasks.ednl\"
        :relative \"tasks.ednl\"}

    (task-path {:base-dir \"/home/user\"} [\"complete.ednl\"])
    => {:absolute \"/home/user/.mcp-tasks/complete.ednl\"
        :relative \"complete.ednl\"}"
  [config path-segments]
  (let [base-dir (:base-dir config)
        relative-path (str/join "/" path-segments)
        absolute-path (if base-dir
                        (str base-dir "/.mcp-tasks/" relative-path)
                        (str ".mcp-tasks/" relative-path))]
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
              :text (json/write-str
                      {:error error-message
                       :metadata (merge {:attempted-operation operation}
                                        error-metadata)})}]
   :isError true})

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
                  {:type "text" :text (json/write-str task-data-with-files)}
                  {:type "text"
                   :text (json/write-str
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
                  {:type "text" :text (json/write-str task-data)}]
        :isError false}
       {:content [{:type "text" :text msg-text}]
        :isError false}))))
