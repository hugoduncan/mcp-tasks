(ns mcp-tasks.tools
  "Task management tools"
  (:require
    [clojure.data.json :as json]
    [clojure.java.shell :as sh]
    [clojure.string :as str]
    [mcp-tasks.prompts :as prompts]
    [mcp-tasks.response :as response]
    [mcp-tasks.schema :as schema]
    [mcp-tasks.tasks :as tasks]
    [mcp-tasks.tools.git :as git]
    [mcp-tasks.tools.helpers :as helpers]
    [mcp-tasks.tools.validation :as validation]))

(defn- setup-completion-context
  "Prepares common context for task completion operations.
  
  Returns either:
  - Error response map (with :isError true) if setup fails
  - Context map with :use-git?, :tasks-file, :complete-file, :tasks-rel-path, :complete-rel-path"
  [config]
  (let [use-git? (:use-git? config)
        tasks-path (helpers/task-path config ["tasks.ednl"])
        complete-path (helpers/task-path config ["complete.ednl"])
        tasks-file (:absolute tasks-path)
        complete-file (:absolute complete-path)
        tasks-rel-path (:relative tasks-path)
        complete-rel-path (:relative complete-path)]

    (if-not (helpers/file-exists? tasks-file)
      (helpers/build-tool-error-response
        "Tasks file not found"
        "complete-task"
        {:file tasks-file})

      (do
        (tasks/load-tasks! tasks-file :complete-file complete-file)
        {:use-git? use-git?
         :tasks-file tasks-file
         :complete-file complete-file
         :tasks-rel-path tasks-rel-path
         :complete-rel-path complete-rel-path}))))

(defn- complete-regular-task-
  "Completes a regular task by marking it :status :closed and moving to complete.ednl.
  
  Parameters:
  - config: Configuration map
  - context: Context map from setup-completion-context
  - task: Task map to complete
  - completion-comment: Optional comment to append to task description
  
  Returns completion response map."
  [config context task completion-comment]
  (let [{:keys [use-git? tasks-file complete-file tasks-rel-path complete-rel-path]} context]
    (tasks/mark-complete (:id task) completion-comment)
    ;; Get the updated task after marking complete
    (let [updated-task (tasks/get-task (:id task))]
      (tasks/move-task! (:id task) tasks-file complete-file)

      (let [msg-text (str "Task " (:id task) " completed and moved to " complete-file)
            modified-files [tasks-rel-path complete-rel-path]
            git-result (when use-git?
                         (git/commit-task-changes (:base-dir config)
                                                  (:id task)
                                                  (:title task)
                                                  modified-files
                                                  "Complete"))
            task-data {:task (select-keys updated-task [:id :title :description :category :type :status :parent-id])
                       :metadata {:file complete-file
                                  :operation "complete-task"}}]
        (helpers/build-completion-response msg-text modified-files use-git? git-result task-data)))))

(defn- complete-child-task-
  "Completes a story child task by marking it :status :closed but keeping it in tasks.ednl.
  
  Parameters:
  - config: Configuration map
  - context: Context map from setup-completion-context
  - task: Task map to complete (must have :parent-id)
  - completion-comment: Optional comment to append to task description
  
  Returns either:
  - Error response if parent validation fails
  - Completion response map"
  [config context task completion-comment]
  (let [{:keys [use-git? tasks-file tasks-rel-path]} context
        parent (tasks/get-task (:parent-id task))]
    (cond
      (not parent)
      (helpers/build-tool-error-response
        "Parent task not found"
        "complete-task"
        {:task-id (:id task)
         :parent-id (:parent-id task)
         :file tasks-file})

      (not= (:type parent) :story)
      (helpers/build-tool-error-response
        "Parent task is not a story"
        "complete-task"
        {:task-id (:id task)
         :parent-id (:parent-id task)
         :parent-type (:type parent)
         :file tasks-file})

      :else
      (do
        (tasks/mark-complete (:id task) completion-comment)
        ;; Get the updated task after marking complete
        (let [updated-task (tasks/get-task (:id task))]
          (tasks/save-tasks! tasks-file)

          (let [msg-text (str "Task " (:id task) " completed")
                modified-files [tasks-rel-path]
                git-result (when use-git?
                             (git/commit-task-changes (:base-dir config)
                                                      (:id task)
                                                      (:title task)
                                                      modified-files
                                                      "Complete"))
                task-data {:task (select-keys updated-task [:id :title :description :category :type :status :parent-id])
                           :metadata {:file tasks-file
                                      :operation "complete-task"}}]
            (helpers/build-completion-response msg-text modified-files use-git? git-result task-data)))))))

(defn- complete-story-task-
  "Completes a story by validating all children are :status :closed, then atomically
  archiving the story and all its children to complete.ednl.
  
  Parameters:
  - config: Configuration map
  - context: Context map from setup-completion-context
  - task: Story task map to complete (must have :type :story)
  - completion-comment: Optional comment to append to task description
  
  Returns either:
  - Error response if children are not all closed
  - Completion response map"
  [config context task completion-comment]
  (let [{:keys [use-git? tasks-file complete-file tasks-rel-path complete-rel-path]} context
        children (tasks/get-children (:id task))
        unclosed-children (filterv #(not= :closed (:status %)) children)]
    (if (seq unclosed-children)
      ;; Error: unclosed children exist
      (helpers/build-tool-error-response
        (str "Cannot complete story: " (count unclosed-children)
             " child task" (when (> (count unclosed-children) 1) "s")
             " still " (if (= 1 (count unclosed-children)) "is" "are")
             " not closed")
        "complete-task"
        {:task-id (:id task)
         :title (:title task)
         :unclosed-children (mapv #(select-keys % [:id :title :status]) unclosed-children)
         :file tasks-file})

      ;; All children closed - proceed with atomic archival
      (do
        ;; Mark story as complete in memory
        (tasks/mark-complete (:id task) completion-comment)

        ;; Get updated story BEFORE moving (since move-tasks! removes from memory)
        (let [updated-story (tasks/get-task (:id task))
              all-ids (cons (:id task) (mapv :id children))
              child-count (count children)]
          ;; Move story and all children to complete.ednl atomically
          (tasks/move-tasks! all-ids tasks-file complete-file)

          ;; Prepare response
          (let [msg-text (str "Story " (:id task) " completed and archived"
                              (when (pos? child-count)
                                (str " with " child-count " child task"
                                     (when (> child-count 1) "s"))))
                modified-files [tasks-rel-path complete-rel-path]
                ;; Commit changes if git mode is enabled
                commit-msg (str "Complete story #" (:id task) ": " (:title task)
                                (when (pos? child-count)
                                  (str " (with " child-count " task"
                                       (when (> child-count 1) "s") ")")))
                git-result (when use-git?
                             ;; Use custom commit message for stories
                             (try
                               (let [git-dir (str (:base-dir config) "/.mcp-tasks")]
                                 ;; Stage modified files
                                 (apply sh/sh "git" "-C" git-dir "add" modified-files)
                                 ;; Commit changes
                                 (let [commit-result (sh/sh "git" "-C" git-dir "commit" "-m" commit-msg)]
                                   (if (zero? (:exit commit-result))
                                     ;; Success - get commit SHA
                                     (let [sha-result (sh/sh "git" "-C" git-dir "rev-parse" "HEAD")
                                           sha (str/trim (:out sha-result))]
                                       {:success true
                                        :commit-sha sha
                                        :error nil})
                                     ;; Commit failed
                                     {:success false
                                      :commit-sha nil
                                      :error (str/trim (:err commit-result))})))
                               (catch Exception e
                                 {:success false
                                  :commit-sha nil
                                  :error (.getMessage e)})))]
            (helpers/build-completion-response
              msg-text
              modified-files
              use-git?
              git-result
              {:task (select-keys updated-story [:id :title :description :category :type :status :parent-id])
               :metadata {:file complete-file
                          :operation "complete-task"
                          :archived-children child-count}})))))))

(defn- complete-task-impl
  "Implementation of complete-task tool.

  Finds a task by exact match (task-id or title) and completes it with optional
  completion comment. Behavior depends on task type:

  - Regular tasks (no parent-id): Marked :status :closed and moved to complete.ednl
  - Story children (has parent-id): Marked :status :closed but stay in tasks.ednl
  - Stories (type :story): Validates all children :status :closed, then atomically
    archives story and all children to complete.ednl

  At least one of task-id or title must be provided.
  If both are provided, they must refer to the same task.

  Returns:
  - Git mode enabled: Three text items (completion message + JSON with :modified-files + JSON with git status)
  - Git mode disabled: Single text item (completion message only)"
  [config _context {:keys [task-id title completion-comment category]}]
  ;; Setup common context and load tasks
  (let [context (setup-completion-context config)]
    (if (:isError context)
      context

      (let [{:keys [tasks-file]} context
            ;; Find task using shared helper
            task-result (validation/find-task-by-identifiers task-id title "complete-task" tasks-file)]

        ;; Check if task-result is an error response
        (if (:isError task-result)
          task-result

          ;; task-result is the actual task - proceed with validations
          (let [task task-result]
            ;; Verify category if provided (for backwards compatibility)
            (cond
              (and category (not= (:category task) category))
              (helpers/build-tool-error-response
                "Task category does not match"
                "complete-task"
                {:expected-category category
                 :actual-category (:category task)
                 :task-id (:id task)
                 :file tasks-file})

              ;; Verify task is not already closed
              (= (:status task) :closed)
              (helpers/build-tool-error-response
                "Task is already closed"
                "complete-task"
                {:task-id (:id task)
                 :title (:title task)
                 :file tasks-file})

              ;; All validations passed - dispatch to appropriate completion function
              (= (:type task) :story)
              (complete-story-task- config context task completion-comment)

              (some? (:parent-id task))
              (complete-child-task- config context task completion-comment)

              :else
              (complete-regular-task- config context task completion-comment))))))))

(defn- description
  "Generate description for complete-task tool based on config."
  [config]
  (str
    "Complete a task by changing :status to :closed.\n"
    (when (:use-git? config)
      "Automatically commits the tasj changes.\n")
    "\nIdentifies tasks by exact match using task-id or title (title).
   At least one identifier must be provided.

   Parameters:
   - task-id: (optional) Exact task ID
   - title: (optional) Exact task title match
   - category: (optional) For backwards compatibility - verifies task category if provided
   - completion-comment: (optional) Comment appended to task description

   If both task-id and title are provided, they must refer to the same task.
   If only title is provided and multiple tasks have the same title, an error is returned."))

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

(defn- delete-task-impl
  "Implementation of delete-task tool.

  Finds a task by exact match (task-id or title-pattern) and marks it as deleted
  with :status :deleted, then moves it to complete.ednl.

  Prevents deletion of parent tasks that have non-closed children.

  At least one of task-id or title-pattern must be provided.
  If both are provided, they must refer to the same task.

  Returns:
  - Git mode enabled: Three text items (deletion message + JSON with deleted task data + JSON with git status)
  - Git mode disabled: Two text items (deletion message + JSON with deleted task data)"
  [config _context {:keys [task-id title-pattern]}]
  ;; Setup common context and load tasks
  (let [context (setup-completion-context config)]
    (if (:isError context)
      context

      (let [{:keys [tasks-file complete-file]} context
            ;; Find task using shared helper (title-pattern is used for exact match)
            task-result (validation/find-task-by-identifiers task-id title-pattern "delete-task" tasks-file)]

        ;; Check if task-result is an error response
        (if (:isError task-result)
          task-result

          ;; task-result is the actual task - proceed with validations
          (let [task task-result]
            (cond
              ;; Verify task is not already deleted
              (= (:status task) :deleted)
              (helpers/build-tool-error-response
                "Task is already deleted"
                "delete-task"
                {:task-id (:id task)
                 :title (:title task)
                 :file tasks-file})

              ;; Check for non-closed children
              :else
              (let [children (tasks/get-children (:id task))
                    non-closed-children (filterv #(not= :closed (:status %)) children)]
                (if (seq non-closed-children)
                  ;; Error: non-closed children exist
                  (helpers/build-tool-error-response
                    "Cannot delete task with children. Delete or complete all child tasks first."
                    "delete-task"
                    {:task-id (:id task)
                     :title (:title task)
                     :child-count (count non-closed-children)
                     :non-closed-children (mapv #(select-keys % [:id :title :status]) non-closed-children)
                     :file tasks-file})

                  ;; All validations passed - delete task
                  (let [{:keys [use-git? tasks-rel-path complete-rel-path]} context
                        ;; Update task status to :deleted
                        updated-task (assoc task :status :deleted)
                        _ (tasks/update-task (:id task) {:status :deleted})
                        ;; Move to complete.ednl
                        _ (tasks/move-task! (:id task) tasks-file complete-file)
                        msg-text (str "Task " (:id task) " deleted successfully")
                        modified-files [tasks-rel-path complete-rel-path]
                        git-result (when use-git?
                                     (git/commit-task-changes (:base-dir config)
                                                              (:id task)
                                                              (:title task)
                                                              modified-files
                                                              "Delete"))]
                    ;; Build response with deleted task data
                    (if use-git?
                      {:content [{:type "text"
                                  :text msg-text}
                                 {:type "text"
                                  :text (json/write-str {:deleted updated-task
                                                         :metadata {:count 1
                                                                    :status "deleted"}})}
                                 {:type "text"
                                  :text (json/write-str
                                          (cond-> {:git-status (if (:success git-result)
                                                                 "success"
                                                                 "error")
                                                   :git-commit (:commit-sha git-result)}
                                            (:error git-result)
                                            (assoc :git-error (:error git-result))))}]
                       :isError false}
                      {:content [{:type "text"
                                  :text msg-text}
                                 {:type "text"
                                  :text (json/write-str {:deleted updated-task
                                                         :metadata {:count 1
                                                                    :status "deleted"}})}]
                       :isError false})))))))))))

(defn delete-task-tool
  "Tool to delete a task by marking it :status :deleted and moving to complete.ednl.

  Prevents deletion of parent tasks that have non-closed children.

  Accepts config parameter for future git-aware functionality."
  [config]
  {:name "delete-task"
   :description "Delete a task from tasks.ednl by marking it :status :deleted and moving to complete.ednl. Cannot delete tasks with non-closed children. At least one of task-id or title-pattern must be provided."
   :inputSchema
   {:type "object"
    :properties
    {"task-id"
     {:type "integer"
      :description "Exact task ID to delete"}
     "title-pattern"
     {:type "string"
      :description "Pattern for fuzzy title matching"}}
    :required []}
   :implementation (partial delete-task-impl config)})

(defn- select-tasks-impl
  "Implementation of select-tasks tool.

  Accepts optional filters (same as next-task):
  - task-id: Task ID to filter by
  - category: Task category name
  - parent-id: Parent task ID for filtering children
  - title-pattern: Pattern to match task titles (regex or substring)
  - type: Task type (keyword: :task, :bug, :feature, :story, :chore)
  - status: Task status (keyword: :open, :closed, :in-progress, :blocked)

  Additional parameters:
  - limit: Maximum number of tasks to return (default: 5, must be > 0)
  - unique: If true, enforce that 0 or 1 task matches (error if >1)

  Returns JSON-encoded response with tasks vector and metadata."
  [config _context {:keys [task-id category parent-id title-pattern type status limit unique]}]
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
            tasks-path (helpers/task-path config ["tasks.ednl"])
            tasks-file (:absolute tasks-path)
            complete-path (helpers/task-path config ["complete.ednl"])
            complete-file (:absolute complete-path)
            ;; Convert type string to keyword if provided
            type-keyword (when type (keyword type))
            ;; Convert status string to keyword if provided
            status-keyword (when status (keyword status))]

        ;; Load tasks from EDNL file
        (when (helpers/file-exists? tasks-file)
          (tasks/load-tasks! tasks-file :complete-file complete-file))

        ;; Get all matching incomplete tasks
        (let [all-tasks (tasks/get-tasks
                          :task-id task-id
                          :category category
                          :parent-id parent-id
                          :title-pattern title-pattern
                          :type type-keyword
                          :status status-keyword)
              total-matches (count all-tasks)
              limited-tasks (vec (take effective-limit all-tasks))
              result-count (count limited-tasks)]

          ;; Check unique? constraint
          ;; When unique is true and a specific task-id was requested, 0 matches is an error
          (when (and unique (zero? total-matches) task-id)
            (let [response-data {:error "No task found with the specified task-id"
                                 :metadata {:task-id task-id
                                            :file tasks-file}}]
              (throw (ex-info "Task not found"
                              {:response response-data}))))

          ;; Multiple matches with unique is also an error
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
  - task-id: Task ID to filter by (returns at most one task)
  - category: Task category name
  - parent-id: Parent task ID for filtering children
  - title-pattern: Pattern to match task titles (regex or substring)
  - type: Task type (task, bug, feature, story, chore)
  - status: Task status (open, closed, in-progress, blocked)
  - limit: Maximum number of tasks to return (default: 5, must be > 0)
  - unique: If true, enforce that 0 or 1 task matches (error if >1)

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
    {"task-id"
     {:type "integer"
      :description "Task ID to filter by (returns at most one task)"}
     "category"
     {:type "string"
      :description "The task category name"}
     "parent-id"
     {:type "integer"
      :description "Parent task ID for filtering children"}
     "title-pattern"
     {:type "string"
      :description "Pattern to match task titles (regex or substring)"}
     "type"
     {:type "string"
      :enum ["task" "bug" "feature" "story" "chore"]
      :description "Task type to filter by"}
     "status"
     {:type "string"
      :enum ["open" "closed" "in-progress" "blocked"]
      :description "Task status to filter by"}
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
  (let [tasks-path (helpers/task-path config ["tasks.ednl"])
        tasks-file (:absolute tasks-path)
        complete-path (helpers/task-path config ["complete.ednl"])
        complete-file (:absolute complete-path)]
    (when (helpers/file-exists? tasks-file)
      (tasks/load-tasks! tasks-file :complete-file complete-file))
    tasks-file))

(defn add-task-impl
  "Implementation of add-task tool.

  Adds a task to tasks.ednl. If prepend is true, adds at the beginning;
  otherwise appends at the end. If parent-id is provided, the task is
  associated with that parent task.

  Error Handling:
  - Tool-level validation errors (e.g., parent not found) are returned directly
    in format: {:error \"...\" :metadata {...}} with :isError true
  - Unexpected errors (e.g., file I/O) are allowed to throw and are caught
    by the MCP server layer, which converts them to MCP error format

  Returns:
  - Git disabled: Two content items (text message + task data JSON)
  - Git enabled: Three content items (text message + task data JSON + git-status JSON)"
  [config _context
   {:keys [category title description prepend type parent-id]}]
  (let [tasks-file (prepare-task-file config)]
    ;; Validate parent-id exists if provided
    (or (when parent-id
          (validation/validate-parent-id-exists parent-id "add-task" nil tasks-file "Parent story not found"
                                                :additional-metadata {:title title :category category}))

        ;; All validations passed - create task
        (let [task-map (cond-> {:title title
                                :description (or description "")
                                :design ""
                                :category category
                                :status :open
                                :type (keyword (or type "task"))
                                :meta {}
                                :relations []}
                         parent-id (assoc :parent-id parent-id))
              ;; Add task to in-memory state and get the complete task with ID
              created-task (tasks/add-task task-map :prepend? (boolean prepend))
              ;; Get path info for git operations
              tasks-path (helpers/task-path config ["tasks.ednl"])
              tasks-rel-path (:relative tasks-path)]

          ;; Save to EDNL file
          (tasks/save-tasks! tasks-file)

          ;; Commit to git if enabled
          (let [use-git? (:use-git? config)
                git-result (when use-git?
                             (let [truncated-title (helpers/truncate-title title)
                                   git-dir (str (:base-dir config) "/.mcp-tasks")
                                   commit-msg (str "Add task #" (:id created-task) ": " truncated-title)]
                               (git/perform-git-commit git-dir [tasks-rel-path] commit-msg)))
                task-data-json (json/write-str
                                 {:task (select-keys
                                          created-task
                                          [:id
                                           :title
                                           :category
                                           :type
                                           :status
                                           :parent-id])
                                  :metadata {:file tasks-file
                                             :operation "add-task"}})]

            ;; Build response based on git mode
            (if use-git?
              ;; Git enabled: 3 content items
              {:content [{:type "text"
                          :text (str "Task added to " tasks-file)}
                         {:type "text"
                          :text task-data-json}
                         {:type "text"
                          :text (json/write-str
                                  (cond-> {:git-status (if (:success git-result)
                                                         "success"
                                                         "error")
                                           :git-commit (:commit-sha git-result)}
                                    (:error git-result)
                                    (assoc :git-error (:error git-result))))}]
               :isError false}

              ;; Git disabled: 2 content items (existing behavior)
              {:content [{:type "text"
                          :text (str "Task added to " tasks-file)}
                         {:type "text"
                          :text task-data-json}]
               :isError false}))))))

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

  Success response structure:
  {
    \"task\": {
      \"id\": 42,
      \"title\": \"Example task\",
      \"category\": \"simple\",
      \"type\": \"task\",
      \"status\": \"open\",
      \"parent-id\": null
    },
    \"metadata\": {
      \"file\": \"./.mcp-tasks/tasks.ednl\",
      \"operation\": \"add-task\"
    }
  }

  Error response structure (e.g., parent not found):
  {
    \"error\": \"Parent story not found\",
    \"metadata\": {
      \"attempted-operation\": \"add-task\",
      \"parent-id\": 99,
      \"file\": \"./.mcp-tasks/tasks.ednl\"
    }
  }

  Agent usage: On successful task creation, display the task-id and title to
  the user to confirm the task was added.

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
