(ns mcp-tasks.tools
  "Task management tools"
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [mcp-tasks.path-helper :as path-helper]
    [mcp-tasks.prompts :as prompts]
    [mcp-tasks.response :as response]
    [mcp-tasks.tasks :as tasks]))

(defn- file-exists?
  "Check if a file exists"
  [file-path]
  (.exists (io/file file-path)))

(defn- complete-task-impl
  "Implementation of complete-task tool.

  Finds a task by exact match (task-id or task-text) and moves it from 
  tasks.ednl to complete.ednl with optional completion comment.
  
  At least one of task-id or task-text must be provided.
  If both are provided, they must refer to the same task.

  Returns:
  - Git mode enabled: Two text items (completion message + JSON with :modified-files)
  - Git mode disabled: Single text item (completion message only)"
  [config _context {:keys [task-id task-text completion-comment category]}]
  (try
    ;; Validate at least one identifier provided
    (when (and (nil? task-id) (nil? task-text))
      (throw (ex-info "Must provide either task-id or task-text"
                      {:task-id task-id :task-text task-text})))

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

      ;; Find task by ID or exact title match
      (let [task-by-id (when task-id (tasks/get-task task-id))
            tasks-by-title (when task-text (tasks/find-by-title task-text))

            ;; Determine which task to complete
            task (cond
                   ;; Both provided - verify they match
                   (and task-id task-text)
                   (cond
                     (nil? task-by-id)
                     (throw (ex-info "Task ID not found"
                                     {:task-id task-id}))

                     (empty? tasks-by-title)
                     (throw (ex-info "No task found with exact title match"
                                     {:task-text task-text}))

                     (not (some #(= (:id %) task-id) tasks-by-title))
                     (throw (ex-info "Task ID and task text do not refer to the same task"
                                     {:task-id task-id
                                      :task-text task-text
                                      :task-by-id task-by-id
                                      :tasks-by-title (mapv :id tasks-by-title)}))

                     :else task-by-id)

                   ;; Only ID provided
                   task-id
                   (or task-by-id
                       (throw (ex-info "Task ID not found"
                                       {:task-id task-id})))

                   ;; Only title provided
                   task-text
                   (cond
                     (empty? tasks-by-title)
                     (throw (ex-info "No task found with exact title match"
                                     {:task-text task-text}))

                     (> (count tasks-by-title) 1)
                     (throw (ex-info "Multiple tasks found with same title - use task-id to disambiguate"
                                     {:task-text task-text
                                      :matching-task-ids (mapv :id tasks-by-title)
                                      :matching-tasks tasks-by-title}))

                     :else (first tasks-by-title)))]

        ;; Verify category if provided (for backwards compatibility)
        (when (and category (not= (:category task) category))
          (throw (ex-info "Task category does not match"
                          {:expected-category category
                           :actual-category (:category task)
                           :task-id (:id task)})))

        ;; Verify task is not already closed
        (when (= (:status task) :closed)
          (throw (ex-info "Task is already closed"
                          {:task-id (:id task)
                           :title (:title task)})))

        ;; Mark task as complete in memory
        (tasks/mark-complete (:id task) completion-comment)

        ;; Move task from tasks file to complete file
        (tasks/move-task! (:id task) tasks-file complete-file)

        (if use-git?
          ;; Git mode: return message + JSON with modified files
          {:content [{:type "text"
                      :text (str "Task " (:id task) " completed and moved to " complete-file)}
                     {:type "text"
                      :text (json/write-str {:modified-files [tasks-rel-path
                                                              complete-rel-path]})}]
           :isError false}
          ;; Non-git mode: return message only
          {:content [{:type "text"
                      :text (str "Task " (:id task) " completed and moved to " complete-file)}]
           :isError false})))
    (catch Exception e
      (response/error-response e))))

(defn- description
  "Generate description for complete-task tool based on config."
  [config]
  (if (:use-git? config)
    "Complete a task by moving it from .mcp-tasks/tasks.ednl to .mcp-tasks/complete.ednl.

   Identifies tasks by exact match using task-id or task-text (title).
   At least one identifier must be provided.

   Parameters:
   - task-id: (optional) Exact task ID
   - task-text: (optional) Exact task title match
   - category: (optional) For backwards compatibility - verifies task category if provided
   - completion-comment: (optional) Comment appended to task description

   If both task-id and task-text are provided, they must refer to the same task.
   If only task-text is provided and multiple tasks have the same title, an error is returned.

   Returns two text items:
   1. A completion status message
   2. A JSON-encoded map with :modified-files key containing file paths
      relative to .mcp-tasks for use in git commit workflows."
    "Complete a task by moving it from .mcp-tasks/tasks.ednl to .mcp-tasks/complete.ednl.

   Identifies tasks by exact match using task-id or task-text (title).
   At least one identifier must be provided.

   Parameters:
   - task-id: (optional) Exact task ID
   - task-text: (optional) Exact task title match
   - category: (optional) For backwards compatibility - verifies task category if provided
   - completion-comment: (optional) Comment appended to task description

   If both task-id and task-text are provided, they must refer to the same task.
   If only task-text is provided and multiple tasks have the same title, an error is returned.

   Returns a completion status message."))

(defn complete-task-tool
  "Tool to complete a task and move it from tasks to complete directory.
  
  Accepts config parameter containing :use-git? flag. When git mode is enabled,
  returns modified file paths for git commit workflow. When disabled, returns
  only completion message."
  [config]
  {:name "complete-task"
   :description (description config)
   :inputSchema
   {:type "object"
    :properties
    {"task-id"
     {:type "integer"
      :description "Exact task ID to complete"}
     "task-text"
     {:type "string"
      :description "Exact task title to match"}
     "category"
     {:type "string"
      :description "(Optional) Task category for backwards compatibility - verifies category matches if provided"}
     "completion-comment"
     {:type "string"
      :description "Optional comment to append to the completed task"}}
    :required []}
   :implementation (partial complete-task-impl config)})

(defn next-task-impl
  "Implementation of next-task tool.

  Accepts optional filters:
  - category: Task category name
  - parent-id: Parent task ID for filtering children
  - title-pattern: Pattern to match task titles (regex or substring)

  Returns the complete task map with all fields from the Task schema,
  or a map with :status key if there are no matching tasks."
  [config _context {:keys [category parent-id title-pattern]}]
  (try
    (let [tasks-path (path-helper/task-path config ["tasks.ednl"])
          tasks-file (:absolute tasks-path)]

      ;; Load tasks from EDNL file
      (when (file-exists? tasks-file)
        (tasks/load-tasks! tasks-file))

      ;; Get next incomplete task with filters
      (if-let [task (tasks/get-next-incomplete
                      :category category
                      :parent-id parent-id
                      :title-pattern title-pattern)]
        {:content [{:type "text"
                    :text (pr-str task)}]
         :isError false}
        {:content [{:type "text"
                    :text (pr-str {:status "No matching tasks found"})}]
         :isError false}))
    (catch Exception e
      (response/error-response e))))

(defn next-task-tool
  "Tool to return the next task with optional filters.

  Accepts optional filters:
  - category: Task category name
  - parent-id: Parent task ID for filtering children
  - title-pattern: Pattern to match task titles (regex or substring)

  All filters are AND-ed together."
  [config]
  {:name "next-task"
   :description "Return the next task from tasks.ednl"
   :inputSchema
   {:type "object"
    :properties
    {"category"
     {:type "string"
      :description "The task category name"}
     "parent-id"
     {:type "integer"
      :description "Parent task ID for filtering children"}
     "title-pattern"
     {:type "string"
      :description "Pattern to match task titles (regex or substring)"}}
    :required []}
   :implementation (partial next-task-impl config)})

(defn- find-story-by-name
  "Find a story task by name in loaded tasks.

  Returns the story task ID or nil if not found."
  [story-name]
  (let [task-map @tasks/tasks
        task-ids @tasks/task-ids]
    (->> task-ids
         (map #(get task-map %))
         (filter #(and (= (:type %) :story)
                       (= (:title %) story-name)))
         first
         :id)))

(defn- prepare-task-file
  "Prepare task file for adding a task.

  Loads tasks from tasks.ednl into memory.
  Returns the absolute file path."
  [config]
  (let [tasks-path (path-helper/task-path config ["tasks.ednl"])
        tasks-file (:absolute tasks-path)]
    ;; Load existing tasks into memory if file exists
    (when (file-exists? tasks-file)
      (tasks/load-tasks! tasks-file))
    tasks-file))

(defn add-task-impl
  "Implementation of add-task tool.

  Adds a task to tasks.ednl. If prepend is true, adds at the beginning;
  otherwise appends at the end. If story-name is provided, the task is
  associated with that story via :parent-id."
  [config _context {:keys [category task-text prepend story-name type]}]
  (try
    (let [tasks-file (prepare-task-file config)
          ;; Parse task-text into title and description
          lines (str/split-lines task-text)
          title (first lines)
          description (if (> (count lines) 1)
                        (str/join "\n" (rest lines))
                        "")
          ;; If story-name provided, find the story task
          parent-id (when story-name
                      (or (find-story-by-name story-name)
                          (throw (ex-info "Story not found"
                                          {:story-name story-name}))))
          ;; Create task map with all required fields
          task-map {:title title
                    :description description
                    :design ""
                    :category category
                    :status :open
                    :type (keyword (or type "task"))
                    :meta {}
                    :relations []}
          ;; Add parent-id if this is a story task
          task-map (if parent-id
                     (assoc task-map :parent-id parent-id)
                     task-map)]
      ;; Add task to in-memory state
      (tasks/add-task task-map :prepend? (boolean prepend))
      ;; Save to EDNL file
      (tasks/save-tasks! tasks-file)
      {:content [{:type "text"
                  :text (str "Task added to " tasks-file)}]
       :isError false})
    (catch Exception e
      (response/error-response e))))

(defn- add-task-description
  "Build description for add-task tool with available categories and their descriptions."
  []
  (let [category-descs (prompts/category-descriptions)
        categories (sort (keys category-descs))]
    (if (seq categories)
      (str "Add a task to tasks.ednl\n\nAvailable categories:\n"
           (str/join "\n"
                     (for [cat categories]
                       (format "- %s: %s" cat (get category-descs cat)))))
      "Add a task to tasks.ednl")))

(defn add-task-tool
  "Tool to add a task to a specific category.

  Accepts config parameter for future git-aware functionality."
  [config]
  {:name "add-task"
   :description (add-task-description)
   :inputSchema
   {:type "object"
    :properties
    {"category"
     {:type "string"
      :description "The task category name"}
     "task-text"
     {:type "string"
      :description "The task text to add"}
     "type"
     {:type "string"
      :enum ["task" "bug" "feature" "story" "chore"]
      :description "The type of task (defaults to 'task')"}
     "story-name"
     {:type "string"
      :description "Optional story name to associate this task with"}
     "prepend"
     {:type "boolean"
      :description "If true, add task at the beginning instead of the end"}}
    :required ["category" "task-text"]}
   :implementation (partial add-task-impl config)})

(defn- update-task-impl
  "Implementation of update-task tool.

  Updates specified fields of an existing task in tasks.ednl."
  [config _context {:keys [task-id title description design]}]
  (try
    (let [tasks-file (prepare-task-file config)]
      ;; Load tasks
      (tasks/load-tasks! tasks-file)
      ;; Build updates map from provided fields
      (let [updates (cond-> {}
                      title (assoc :title title)
                      description (assoc :description description)
                      design (assoc :design design))]
        (when (empty? updates)
          (throw (ex-info "No fields to update" {:task-id task-id})))
        ;; Update task in memory
        (tasks/update-task task-id updates)
        ;; Save to EDNL file
        (tasks/save-tasks! tasks-file)
        {:content [{:type "text"
                    :text (str "Task " task-id " updated in " tasks-file)}]
         :isError false}))
    (catch Exception e
      (response/error-response e))))

(defn update-task-tool
  "Tool to update fields of an existing task.

  Accepts config parameter for future git-aware functionality."
  [config]
  {:name "update-task"
   :description "Update fields of an existing task by ID. Only provided fields will be updated."
   :inputSchema
   {:type "object"
    :properties
    {"task-id"
     {:type "integer"
      :description "The ID of the task to update"}
     "title"
     {:type "string"
      :description "New title for the task (optional)"}
     "description"
     {:type "string"
      :description "New description for the task (optional)"}
     "design"
     {:type "string"
      :description "New design notes for the task (optional)"}}
    :required ["task-id"]}
   :implementation (partial update-task-impl config)})
