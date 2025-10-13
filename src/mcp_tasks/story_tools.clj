(ns mcp-tasks.story-tools
  "Story management tools"
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [mcp-tasks.path-helper :as path-helper]
    [mcp-tasks.response :as response]
    [mcp-tasks.tasks :as tasks]))

;; Helper Functions

(defn- find-story-by-name
  "Find a story task by name in loaded tasks.

  Returns the story task map or nil if not found."
  [story-name]
  (let [task-map @tasks/tasks
        task-ids @tasks/task-ids]
    (->> task-ids
         (map #(get task-map %))
         (filter #(and (= (:type %) :story)
                       (= (:title %) story-name)))
         first)))

(defn- format-task-as-text
  "Format a task map as text for display.

  Combines :title and :description fields."
  [task]
  (let [title (:title task)
        description (:description task "")]
    (if (str/blank? description)
      title
      (str title "\n" description))))

(defn- file-exists?
  "Check if a file exists"
  [file-path]
  (.exists (io/file file-path)))

(defn- get-children-in-order
  "Get children for a parent in task-ids order.

  Returns vector of child task maps."
  [parent-id]
  (let [child-ids-set (get @tasks/parent-children parent-id #{})
        task-ids @tasks/task-ids
        task-map @tasks/tasks]
    (->> task-ids
         (filter child-ids-set)
         (mapv #(get task-map %)))))

;; Tool Implementations

(defn- next-story-task-impl
  "Implementation of next-story-task tool.

  Loads tasks from .mcp-tasks/tasks.ednl and finds the first incomplete
  child task for the specified story.

  Returns a map with :task-text, :category, :task-id, and :task-index keys,
  or nil values if no incomplete task is found."
  [config _context {:keys [story-name]}]
  (try
    (let [tasks-path (path-helper/task-path config ["tasks.ednl"])
          tasks-file (:absolute tasks-path)]

      ;; Load tasks from EDNL file
      (when (file-exists? tasks-file)
        (tasks/load-tasks! tasks-file))

      ;; Find story task
      (if-let [story (find-story-by-name story-name)]
        (let [story-id (:id story)
              ;; Get children in order
              all-children (get-children-in-order story-id)
              ;; Find first incomplete
              first-incomplete (->> all-children
                                    (filter #(not= (:status %) :closed))
                                    first)]
          (if first-incomplete
            (let [task-text (format-task-as-text first-incomplete)
                  category (:category first-incomplete)
                  task-id (:id first-incomplete)
                  task-index (.indexOf (mapv :id all-children) task-id)]
              {:content [{:type "text"
                          :text (pr-str {:task-text task-text
                                         :category category
                                         :task-id task-id
                                         :task-index task-index})}]
               :isError false})
            ;; No incomplete tasks for this story
            {:content [{:type "text"
                        :text (pr-str {:task-text nil
                                       :category nil
                                       :task-id nil
                                       :task-index nil})}]
             :isError false}))
        ;; Story not found
        (throw (ex-info "Story not found"
                        {:story-name story-name}))))
    (catch Exception e
      (response/error-response e))))

(defn next-story-task-tool
  "Tool to return the next incomplete task from a story's task list.

  Takes a story-name parameter and reads from .mcp-tasks/tasks.ednl.
  Returns a map with :task-text, :category, :task-id, and :task-index, or nil values if no tasks remain."
  [config]
  {:name "next-story-task"
   :description "Return the next incomplete task from a story's task list.

  Loads tasks from `.mcp-tasks/tasks.ednl`, finds story by name,
  returns first incomplete child task with :task-text, :category, :task-id, :task-index (or nil if none)."
   :inputSchema
   {:type "object"
    :properties
    {"story-name"
     {:type "string"
      :description "The story name"}}
    :required ["story-name"]}
   :implementation (partial next-story-task-impl config)})

(defn- complete-story-task-impl
  "Implementation of complete-story-task tool.

  Marks a story task as complete by task-id. Uses tasks namespace API.

  Returns:
  - Git mode enabled: Two text items (completion message + JSON with :modified-files)
  - Git mode disabled: Single text item (completion message only)"
  [config _context {:keys [story-name task-id completion-comment]}]
  (try
    (let [use-git? (:use-git? config)
          tasks-path (path-helper/task-path config ["tasks.ednl"])
          complete-path (path-helper/task-path config ["complete.ednl"])
          tasks-file (:absolute tasks-path)
          complete-file (:absolute complete-path)
          tasks-rel-path (:relative tasks-path)
          complete-rel-path (:relative complete-path)]

      ;; Load tasks from EDNL file
      (when-not (file-exists? tasks-file)
        (throw (ex-info "Tasks file not found"
                        {:file tasks-file})))

      (tasks/load-tasks! tasks-file)

      ;; Get task by ID
      (when-not (tasks/get-task task-id)
        (throw (ex-info "Task not found"
                        {:task-id task-id})))

      ;; Verify task belongs to the story
      (if-let [story (find-story-by-name story-name)]
        (let [task (tasks/get-task task-id)
              parent-id (:parent-id task)]
          (when-not (= parent-id (:id story))
            (throw (ex-info "Task does not belong to specified story"
                            {:task-id task-id
                             :story-name story-name
                             :task-parent-id parent-id
                             :story-id (:id story)})))

          ;; Mark task as complete in memory
          (tasks/mark-complete task-id completion-comment)

          ;; Move task from tasks file to complete file
          (tasks/move-task! task-id tasks-file complete-file)

          (if use-git?
            ;; Git mode: return message + JSON with modified files
            {:content [{:type "text"
                        :text (str "Story task completed and moved to " complete-file)}
                       {:type "text"
                        :text (json/write-str {:modified-files [tasks-rel-path
                                                                complete-rel-path]})}]
             :isError false}
            ;; Non-git mode: return message only
            {:content [{:type "text"
                        :text (str "Story task completed and moved to " complete-file)}]
             :isError false}))
        (throw (ex-info "Story not found"
                        {:story-name story-name}))))
    (catch Exception e
      (response/error-response e))))

(defn- complete-story-task-description
  "Generate description for complete-story-task tool based on config."
  [config]
  (if (:use-git? config)
    "Complete a task in a story's task list by task-id.

  Marks the specified task as complete and optionally adds a completion comment.

  Returns two text items:
  1. A completion status message
  2. A JSON-encoded map with :modified-files key containing file paths
     relative to .mcp-tasks for use in git commit workflows."
    "Complete a task in a story's task list by task-id.

  Marks the specified task as complete and optionally adds a completion comment.

  Returns a completion status message."))

(defn complete-story-task-tool
  "Tool to complete a task in a story's task list.

  Accepts config parameter containing :use-git? flag. When git mode is enabled,
  returns modified file paths for git commit workflow. When disabled, returns
  only completion message."
  [config]
  {:name "complete-story-task"
   :description (complete-story-task-description config)
   :inputSchema
   {:type "object"
    :properties
    {"story-name"
     {:type "string"
      :description "The story name"}
     "task-id"
     {:type "number"
      :description "The task ID to complete"}
     "completion-comment"
     {:type "string"
      :description "Optional comment to append to the completed task"}}
    :required ["story-name" "task-id"]}
   :implementation (partial complete-story-task-impl config)})

(defn- complete-story-impl
  "Implementation of complete-story tool.

  Marks a story as complete by moving it and all child tasks to complete.ednl.

  Process:
  1. Loads tasks from tasks.ednl
  2. Finds story by name
  3. Verifies all child tasks are complete
  4. Marks story complete
  5. Moves story and all children to complete.ednl

  Returns:
  - Git mode enabled: Two text items (completion message + JSON with :modified-files)
  - Git mode disabled: Single text item (completion message only)"
  [config _context {:keys [story-name completion-comment]}]
  (try
    (let [use-git? (:use-git? config)
          tasks-path (path-helper/task-path config ["tasks.ednl"])
          complete-path (path-helper/task-path config ["complete.ednl"])
          tasks-file (:absolute tasks-path)
          complete-file (:absolute complete-path)
          tasks-rel-path (:relative tasks-path)
          complete-rel-path (:relative complete-path)]

      ;; Load tasks from EDNL file
      (when-not (file-exists? tasks-file)
        (throw (ex-info "Tasks file not found"
                        {:file tasks-file})))

      (tasks/load-tasks! tasks-file)

      ;; Find story task
      (if-let [story (find-story-by-name story-name)]
        (let [story-id (:id story)
              children (get-children-in-order story-id)
              incomplete-children (filter #(not= (:status %) :closed) children)]

          ;; Check all children are complete
          (when (seq incomplete-children)
            (throw (ex-info "Cannot complete story with incomplete tasks"
                            {:story-name story-name
                             :incomplete-count (count incomplete-children)
                             :incomplete-tasks (mapv #(select-keys % [:id :title]) incomplete-children)})))

          ;; Mark story as complete
          (tasks/mark-complete story-id completion-comment)

          ;; Move story to complete file
          (tasks/move-task! story-id tasks-file complete-file)

          ;; Move all children to complete file
          (doseq [child children]
            (tasks/move-task! (:id child) tasks-file complete-file))

          (if use-git?
            ;; Git mode: return message + JSON with modified files
            {:content [{:type "text"
                        :text (str "Story '" story-name "' marked as complete"
                                   (when (seq children)
                                     (str " (" (count children) " task(s) archived)")))}
                       {:type "text"
                        :text (json/write-str {:modified-files [tasks-rel-path
                                                                complete-rel-path]})}]
             :isError false}
            ;; Non-git mode: return message only
            {:content [{:type "text"
                        :text (str "Story '" story-name "' marked as complete"
                                   (when (seq children)
                                     (str " (" (count children) " task(s) archived)")))}]
             :isError false}))
        (throw (ex-info "Story not found"
                        {:story-name story-name}))))
    (catch Exception e
      (response/error-response e))))

(defn- complete-story-description
  "Generate description for complete-story tool based on config."
  [config]
  (if (:use-git? config)
    "Mark a story as complete and move it to the archive.

  Moves the story and all its child tasks from .mcp-tasks/tasks.ednl to
  .mcp-tasks/complete.ednl. Verifies that all child tasks are complete before
  marking the story complete.

  Optionally adds a completion comment to the story.

  Returns two text items:
  1. A completion status message
  2. A JSON-encoded map with :modified-files key containing file paths
     relative to .mcp-tasks for use in git commit workflows."
    "Mark a story as complete and move it to the archive.

  Moves the story and all its child tasks from .mcp-tasks/tasks.ednl to
  .mcp-tasks/complete.ednl. Verifies that all child tasks are complete before
  marking the story complete.

  Optionally adds a completion comment to the story.

  Returns a completion status message."))

(defn complete-story-tool
  "Tool to complete a story and move it to the archive.

  Accepts config parameter containing :use-git? flag. When git mode is enabled,
  returns modified file paths for git commit workflow. When disabled, returns
  only completion message."
  [config]
  {:name "complete-story"
   :description (complete-story-description config)
   :inputSchema
   {:type "object"
    :properties
    {"story-name"
     {:type "string"
      :description "The story name"}
     "completion-comment"
     {:type "string"
      :description "Optional comment to append to the story"}}
    :required ["story-name"]}
   :implementation (partial complete-story-impl config)})
