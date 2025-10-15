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

  Finds a task by exact match (task-id or title) and moves it from
  tasks.ednl to complete.ednl with optional completion comment.

  At least one of task-id or title must be provided.
  If both are provided, they must refer to the same task.

  Returns:
  - Git mode enabled: Two text items (completion message + JSON with :modified-files)
  - Git mode disabled: Single text item (completion message only)"
  [config _context {:keys [task-id title completion-comment category]}]
  (try
    ;; Validate at least one identifier provided
    (when (and (nil? task-id) (nil? title))
      (throw (ex-info "Must provide either task-id or title"
                      {:task-id task-id :title title})))

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
            tasks-by-title (when title (tasks/find-by-title title))

            ;; Determine which task to complete
            task (cond
                   ;; Both provided - verify they match
                   (and task-id title)
                   (cond
                     (nil? task-by-id)
                     (throw (ex-info "Task ID not found"
                                     {:task-id task-id}))

                     (empty? tasks-by-title)
                     (throw (ex-info "No task found with exact title match"
                                     {:title title}))

                     (not (some #(= (:id %) task-id) tasks-by-title))
                     (throw (ex-info "Task ID and task text do not refer to the same task"
                                     {:task-id task-id
                                      :title title
                                      :task-by-id task-by-id
                                      :tasks-by-title (mapv :id tasks-by-title)}))

                     :else task-by-id)

                   ;; Only ID provided
                   task-id
                   (or task-by-id
                       (throw (ex-info "Task ID not found"
                                       {:task-id task-id})))

                   ;; Only title provided
                   title
                   (cond
                     (empty? tasks-by-title)
                     (throw (ex-info "No task found with exact title match"
                                     {:title title}))

                     (> (count tasks-by-title) 1)
                     (throw (ex-info "Multiple tasks found with same title - use task-id to disambiguate"
                                     {:title title
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

   Identifies tasks by exact match using task-id or title (title).
   At least one identifier must be provided.

   Parameters:
   - task-id: (optional) Exact task ID
   - title: (optional) Exact task title match
   - category: (optional) For backwards compatibility - verifies task category if provided
   - completion-comment: (optional) Comment appended to task description

   If both task-id and title are provided, they must refer to the same task.
   If only title is provided and multiple tasks have the same title, an error is returned.

   Returns two text items:
   1. A completion status message
   2. A JSON-encoded map with :modified-files key containing file paths
      relative to .mcp-tasks for use in git commit workflows."
    "Complete a task by moving it from .mcp-tasks/tasks.ednl to .mcp-tasks/complete.ednl.

   Identifies tasks by exact match using task-id or title (title).
   At least one identifier must be provided.

   Parameters:
   - task-id: (optional) Exact task ID
   - title: (optional) Exact task title match
   - category: (optional) For backwards compatibility - verifies task category if provided
   - completion-comment: (optional) Comment appended to task description

   If both task-id and title are provided, they must refer to the same task.
   If only title is provided and multiple tasks have the same title, an error is returned.

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
     "title"
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

(defn- select-tasks-impl
  "Implementation of select-tasks tool.

  Accepts optional filters (same as next-task):
  - category: Task category name
  - parent-id: Parent task ID for filtering children
  - title-pattern: Pattern to match task titles (regex or substring)

  Additional parameters:
  - limit: Maximum number of tasks to return (default: 5, must be > 0)
  - unique: If true, enforce that 0 or 1 task matches (error if >1)

  Returns JSON-encoded response with tasks vector and metadata."
  [config _context {:keys [category parent-id title-pattern limit unique]}]
  (try
    ;; Determine effective limit
    ;; If unique? is true, effective limit is always 1
    ;; Otherwise use provided limit or default to 5
    (let [provided-limit? (some? limit)
          default-limit 5
          requested-limit (or limit default-limit)]

      ;; Validate limit parameter if provided
      (when (and provided-limit? (<= requested-limit 0))
        (let [response-data {:error "limit must be a positive integer (> 0)"
                             :metadata {:provided-limit requested-limit}}]
          (throw (ex-info "Invalid limit parameter"
                          {:response response-data}))))

      ;; Validate limit and unique? compatibility
      ;; Only error if limit was explicitly provided AND is > 1
      (when (and unique provided-limit? (> requested-limit 1))
        (let [response-data {:error "limit must be 1 when unique is true (or omit limit)"
                             :metadata {:provided-limit requested-limit :unique true}}]
          (throw (ex-info "Incompatible parameters"
                          {:response response-data}))))

      (let [effective-limit (if unique 1 requested-limit)
            tasks-path (path-helper/task-path config ["tasks.ednl"])
            tasks-file (:absolute tasks-path)]

        ;; Load tasks from EDNL file
        (when (file-exists? tasks-file)
          (tasks/load-tasks! tasks-file))

        ;; Get all matching incomplete tasks
        (let [all-tasks (tasks/get-tasks
                          :category category
                          :parent-id parent-id
                          :title-pattern title-pattern)
              total-matches (count all-tasks)
              limited-tasks (vec (take effective-limit all-tasks))
              result-count (count limited-tasks)]

          ;; Check unique? constraint
          (when (and unique (> total-matches 1))
            (let [response-data {:error "Multiple tasks matched but :unique was specified"
                                 :metadata {:count result-count
                                            :total-matches total-matches}}]
              (throw (ex-info "unique? constraint violated"
                              {:response response-data}))))

          ;; Build success response
          (let [response-data {:tasks limited-tasks
                               :metadata {:count result-count
                                          :total-matches total-matches
                                          :limited? (> total-matches result-count)}}]
            {:content [{:type "text"
                        :text (json/write-str response-data)}]
             :isError false}))))

    (catch clojure.lang.ExceptionInfo e
      ;; Handle validation errors with structured response
      (if-let [response-data (:response (ex-data e))]
        {:content [{:type "text"
                    :text (json/write-str response-data)}]
         :isError false}
        (response/error-response e)))

    (catch Exception e
      (response/error-response e))))

(defn select-tasks-tool
  "Tool to return multiple tasks with optional filters and limits.

  Accepts optional filters:
  - category: Task category name
  - parent-id: Parent task ID for filtering children
  - title-pattern: Pattern to match task titles (regex or substring)
  - limit: Maximum number of tasks to return (default: 5, must be > 0)
  - unique?: If true, enforce that 0 or 1 task matches (error if >1)

  All filters are AND-ed together.

  Returns JSON-encoded response:
  Success: {\"tasks\": [...], \"metadata\": {...}}
  Error: {\"error\": \"...\", \"metadata\": {...}}"
  [config]
  {:name "select-tasks"
   :description "Return multiple tasks from tasks.ednl with optional filters and limits"
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
      :description "Pattern to match task titles (regex or substring)"}
     "limit"
     {:type "integer"
      :description "Maximum number of tasks to return (default: 5, must be > 0)"}
     "unique"
     {:type "boolean"
      :description "If true, enforce that 0 or 1 task matches (error if >1)"}}
    :required []}
   :implementation (partial select-tasks-impl config)})

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
  otherwise appends at the end. If parent-id is provided, the task is
  associated with that parent task.

  Returns two content items:
  1. Text message for human readability
  2. Structured data map with 'task' and 'metadata' keys"
  [config _context
   {:keys [category title description prepend type parent-id]}]
  (try
    (let [tasks-file (prepare-task-file config)]
      ;; Validate parent-id if provided (tasks are already loaded by prepare-task-file)
      (when parent-id
        (or (tasks/get-task parent-id)
            (throw (ex-info "Parent story not found"
                            {:parent-id parent-id}))))

      ;; Create task map with all required fields
      (let [task-map {:title title
                      :description (or description "")
                      :design ""
                      :category category
                      :status :open
                      :type (keyword (or type "task"))
                      :meta {}
                      :parent-id parent-id
                      :relations []}
            ;; Add task to in-memory state and get the assigned ID
            task-id (tasks/add-task task-map :prepend? (boolean prepend))
            ;; Get the complete task with ID
            created-task (tasks/get-task task-id)]
        ;; Save to EDNL file
        (tasks/save-tasks! tasks-file)
        ;; Return two content items: text message and structured data
        {:content [{:type "text"
                    :text (str "Task added to " tasks-file)}
                   {:type "text"
                    :text (json/write-str
                            {:task (select-keys
                                     created-task
                                     [:id
                                      :title
                                      :category
                                      :type
                                      :status
                                      :parent-id])
                             :metadata {:file tasks-file
                                        :operation "add-task"}})}]
         :isError false}))
    (catch Exception e
      (response/error-response e))))

(defn- add-task-description
  "Build description for add-task tool with available categories and their descriptions."
  []
  (let [category-descs (prompts/category-descriptions)
        categories (sort (keys category-descs))]
    [categories
     (if (seq categories)
       (str "Add a task to tasks.ednl\n\nAvailable categories:\n"
            (str/join "\n"
                      (for [cat categories]
                        (format "- %s: %s" cat (get category-descs cat)))))
       "Add a task to tasks.ednl")]))

(defn add-task-tool
  "Tool to add a task to a specific category.

  Returns two content items:
  1. Text message: 'Task added to <file-path>' for human readability
  2. Structured data (JSON): Map with 'task' and 'metadata' keys
     - task: {:id, :title, :category, :type, :status, :parent-id}
     - metadata: {:file, :operation}

  Accepts config parameter for future git-aware functionality."
  [config]
  (let [[categories description] (add-task-description)]
    {:name "add-task"
     :description description
     :inputSchema
     {:type "object"
      :properties
      {"category"
       {:enum (vec categories)
        :description "The task category name"}
       "title"
       {:type "string"
        :description "The task title"}
       "description"
       {:type "string"
        :description "A description of the task"}
       "type"
       {:enum ["task" "bug" "feature" "story" "chore"]
        :description "The type of task (defaults to 'task')"
        :default "task"}
       "parent-id"
       {:type "integer"
        :description "Optional task-id of parent"}
       "prepend"
       {:type "boolean"
        :description "If true, add task at the beginning instead of the end"}}
      :required ["category" "title"]}
     :implementation (partial add-task-impl config)}))

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
