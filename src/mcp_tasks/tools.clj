(ns mcp-tasks.tools
  "Task management tools"
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.java.shell :as sh]
    [clojure.string :as str]
    [mcp-tasks.path-helper :as path-helper]
    [mcp-tasks.prompts :as prompts]
    [mcp-tasks.response :as response]
    [mcp-tasks.schema :as schema]
    [mcp-tasks.tasks :as tasks]))

(defn- file-exists?
  "Check if a file exists"
  [file-path]
  (.exists (io/file file-path)))

(defn- commit-task-changes
  "Commits task file changes to .mcp-tasks git repository.

  Returns a map with:
  - :success - boolean indicating if commit succeeded
  - :commit-sha - commit SHA string (or nil if failed)
  - :error - error message string (or nil if successful)

  Never throws - all errors are caught and returned in the map."
  [base-dir task-id task-title]
  (try
    (let [git-dir (str base-dir "/.mcp-tasks")
          commit-msg (str "Complete task #" task-id ": " task-title)]

      ;; Stage modified files
      (sh/sh "git" "-C" git-dir "add" "tasks.ednl" "complete.ednl")

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
       :error (.getMessage e)})))

;; Error response helpers

(defn- build-tool-error-response
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

;; Validation helpers

(defn- format-path-element
  "Format a single element from a Malli :in path.

  Handles keywords, symbols, strings, and numeric indices."
  [element]
  (cond
    (keyword? element) (name element)
    (symbol? element) (name element)
    (string? element) element
    (number? element) (str "[" element "]")
    :else (str element)))

(defn- format-field-path
  "Format a Malli :in path into a human-readable field name.

  Returns a string like 'relations[0].as-type' for nested paths."
  [in]
  (if (seq in)
    (str/join "." (map format-path-element in))
    "field"))

(defn- format-malli-error
  "Format a Malli validation error into a human-readable message.

  Extracts field path and error details from Malli's explain result.
  Returns a string describing what field failed and why."
  [error]
  (let [in (:in error)
        schema (:schema error)
        value (:value error)
        type (:type error)
        field-name (format-field-path in)]
    (cond
      ;; Enum validation failure
      (and (vector? schema) (= :enum (first schema)))
      (let [allowed-values (rest schema)]
        (format "%s has invalid value %s (expected one of: %s)"
                field-name
                (pr-str value)
                (str/join ", " (map pr-str allowed-values))))

      ;; Type mismatch
      (= type :malli.core/invalid-type)
      (format "%s has invalid type (got: %s, expected: %s)"
              field-name
              (pr-str value)
              (pr-str schema))

      ;; Missing required field
      (= type :malli.core/missing-key)
      (format "missing required field: %s" field-name)

      ;; Generic fallback
      :else
      (format "%s failed validation: %s" field-name (pr-str error)))))

(defn- format-validation-errors
  "Format Malli validation result into human-readable error messages.

  Takes the result from schema/explain-task and returns a formatted
  string with specific field errors."
  [validation-result]
  (if-let [errors (:errors validation-result)]
    (str/join "; " (map format-malli-error errors))
    (pr-str validation-result)))

(defn- validate-task-exists
  "Validate that a task exists.

  Returns error response map if validation fails, nil if successful."
  [task-id operation tasks-file & {:keys [additional-metadata]}]
  (when-not (tasks/get-task task-id)
    (build-tool-error-response
      "Task not found"
      operation
      (merge {:task-id task-id :file tasks-file}
             additional-metadata))))

(defn- validate-parent-id-exists
  "Validate that a parent task exists if parent-id is provided and non-nil.

  Returns error response map if validation fails, nil if successful."
  [parent-id operation task-id tasks-file error-message & {:keys [additional-metadata]}]
  (when (and parent-id (not (tasks/get-task parent-id)))
    (build-tool-error-response
      error-message
      operation
      (merge {:task-id task-id :parent-id parent-id :file tasks-file}
             additional-metadata))))

(defn- validate-task-schema
  "Validate that a task conforms to the schema.

  Returns error response map if validation fails, nil if successful."
  [task operation task-id tasks-file]
  (when-let [validation-result (schema/explain-task task)]
    (let [formatted-errors (format-validation-errors validation-result)
          error-message (str "Invalid task field values: " formatted-errors)]
      (build-tool-error-response
        error-message
        operation
        {:task-id task-id
         :validation-errors (pr-str validation-result)
         :file tasks-file}))))

(defn- complete-task-impl
  "Implementation of complete-task tool.

  Finds a task by exact match (task-id or title) and moves it from
  tasks.ednl to complete.ednl with optional completion comment.

  At least one of task-id or title must be provided.
  If both are provided, they must refer to the same task.

  Returns:
  - Git mode enabled: Three text items (completion message + JSON with :modified-files + JSON with git status)
  - Git mode disabled: Single text item (completion message only)"
  [config _context {:keys [task-id title completion-comment category]}]
  ;; Validate at least one identifier provided
  (if (and (nil? task-id) (nil? title))
    (build-tool-error-response
      "Must provide either task-id or title"
      "complete-task"
      {:task-id task-id
       :title title})

    (let [use-git? (:use-git? config)
          tasks-path (path-helper/task-path config ["tasks.ednl"])
          complete-path (path-helper/task-path config ["complete.ednl"])
          tasks-file (:absolute tasks-path)
          complete-file (:absolute complete-path)
          tasks-rel-path (:relative tasks-path)
          complete-rel-path (:relative complete-path)]

      ;; Validate tasks file exists
      (if-not (file-exists? tasks-file)
        (build-tool-error-response
          "Tasks file not found"
          "complete-task"
          {:file tasks-file})

        (do
          (tasks/load-tasks! tasks-file :complete-file complete-file)

          ;; Find task by ID or exact title match
          (let [task-by-id (when task-id (tasks/get-task task-id))
                tasks-by-title (when title (tasks/find-by-title title))

                ;; Determine which task to complete
                task-result (cond
                              ;; Both provided - verify they match
                              (and task-id title)
                              (cond
                                (nil? task-by-id)
                                (build-tool-error-response
                                  "Task ID not found"
                                  "complete-task"
                                  {:task-id task-id
                                   :file tasks-file})

                                (empty? tasks-by-title)
                                (build-tool-error-response
                                  "No task found with exact title match"
                                  "complete-task"
                                  {:title title
                                   :file tasks-file})

                                (not (some #(= (:id %) task-id) tasks-by-title))
                                (build-tool-error-response
                                  "Task ID and task text do not refer to the same task"
                                  "complete-task"
                                  {:task-id task-id
                                   :title title
                                   :task-by-id task-by-id
                                   :tasks-by-title (mapv :id tasks-by-title)
                                   :file tasks-file})

                                :else task-by-id)

                              ;; Only ID provided
                              task-id
                              (or task-by-id
                                  (build-tool-error-response
                                    "Task ID not found"
                                    "complete-task"
                                    {:task-id task-id
                                     :file tasks-file}))

                              ;; Only title provided
                              title
                              (cond
                                (empty? tasks-by-title)
                                (build-tool-error-response
                                  "No task found with exact title match"
                                  "complete-task"
                                  {:title title
                                   :file tasks-file})

                                (> (count tasks-by-title) 1)
                                (build-tool-error-response
                                  "Multiple tasks found with same title - use task-id to disambiguate"
                                  "complete-task"
                                  {:title title
                                   :matching-task-ids (mapv :id tasks-by-title)
                                   :matching-tasks tasks-by-title
                                   :file tasks-file})

                                :else (first tasks-by-title)))]

            ;; Check if task-result is an error response
            (if (:isError task-result)
              task-result

              ;; task-result is the actual task - proceed with validations
              (let [task task-result]
                ;; Verify category if provided (for backwards compatibility)
                (if (and category (not= (:category task) category))
                  (build-tool-error-response
                    "Task category does not match"
                    "complete-task"
                    {:expected-category category
                     :actual-category (:category task)
                     :task-id (:id task)
                     :file tasks-file})

                  ;; Verify task is not already closed
                  (if (= (:status task) :closed)
                    (build-tool-error-response
                      "Task is already closed"
                      "complete-task"
                      {:task-id (:id task)
                       :title (:title task)
                       :file tasks-file})

                    ;; All validations passed - complete the task
                    (do
                      ;; Mark task as complete in memory
                      (tasks/mark-complete (:id task) completion-comment)

                      ;; Move task from tasks file to complete file
                      (tasks/move-task! (:id task) tasks-file complete-file)

                      ;; Commit changes if git mode is enabled
                      (let [git-result (when use-git?
                                         (commit-task-changes (:base-dir config)
                                                              (:id task)
                                                              (:title task)))]
                        (if use-git?
                          ;; Git mode: return message + JSON with modified files + git status
                          {:content [{:type "text"
                                      :text (str "Task " (:id task) " completed and moved to " complete-file)}
                                     {:type "text"
                                      :text (json/write-str {:modified-files [tasks-rel-path
                                                                              complete-rel-path]})}
                                     {:type "text"
                                      :text (json/write-str
                                              (cond-> {:git-status (if (:success git-result)
                                                                     "success"
                                                                     "error")
                                                       :git-commit-sha (:commit-sha git-result)}
                                                (:error git-result)
                                                (assoc :git-error (:error git-result))))}]
                           :isError false}
                          ;; Non-git mode: return message only
                          {:content [{:type "text"
                                      :text (str "Task " (:id task) " completed and moved to " complete-file)}]
                           :isError false})))))))))))))

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
            tasks-path (path-helper/task-path config ["tasks.ednl"])
            tasks-file (:absolute tasks-path)
            complete-path (path-helper/task-path config ["complete.ednl"])
            complete-file (:absolute complete-path)
            ;; Convert type string to keyword if provided
            type-keyword (when type (keyword type))
            ;; Convert status string to keyword if provided
            status-keyword (when status (keyword status))]

        ;; Load tasks from EDNL file
        (when (file-exists? tasks-file)
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
  (let [tasks-path (path-helper/task-path config ["tasks.ednl"])
        tasks-file (:absolute tasks-path)
        complete-path (path-helper/task-path config ["complete.ednl"])
        complete-file (:absolute complete-path)]
    ;; Load existing tasks into memory if file exists
    (when (file-exists? tasks-file)
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

  Returns two content items:
  1. Text message for human readability
  2. Structured data map with 'task' and 'metadata' keys"
  [config _context
   {:keys [category title description prepend type parent-id]}]
  (let [tasks-file (prepare-task-file config)]
    ;; Validate parent-id exists if provided
    (or (when parent-id
          (validate-parent-id-exists parent-id "add-task" nil tasks-file "Parent story not found"
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
              created-task (tasks/add-task task-map :prepend? (boolean prepend))]
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
           :isError false}))))

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

;; Field conversion helpers

(defn- convert-enum-field
  "Convert string enum value to keyword.

  Returns keyword version of the string value."
  [value]
  (keyword value))

(defn- convert-meta-field
  "Convert meta field value, treating nil as empty map.

  Returns the value or {} if nil.

  Note: When updating a task's :meta field, the entire map is replaced,
  not merged. This design decision ensures predictable behavior - users
  provide the complete desired state rather than incremental updates.
  This matches the story requirements and simplifies the mental model
  for task updates."
  [value]
  (or value {}))

(defn- convert-relations-field
  "Convert relations from JSON structure to Clojure keyword-based structure.

  Transforms string keys to keywords for :as-type field.
  Returns [] if relations is nil.

  Note: When updating a task's :relations field, the entire vector is
  replaced, not appended or merged. This design decision ensures
  predictable behavior - users provide the complete desired state rather
  than incremental updates. This matches the story requirements and
  simplifies the mental model for task updates."
  [relations]
  (if relations
    (mapv (fn [rel]
            {:id (get rel "id")
             :relates-to (get rel "relates-to")
             :as-type (keyword (get rel "as-type"))})
          relations)
    []))

(defn- extract-provided-updates
  "Extract and convert provided fields from arguments map.

  Returns map with only the fields that were actually provided in arguments,
  with appropriate type conversions applied.

  Type conversions:
  - :status, :type - string to keyword
  - :meta - nil becomes {}, replaces entire map (does not merge)
  - :relations - nil becomes [], replaces entire vector (does not append)

  Note: The :meta and :relations fields use replacement semantics rather
  than merge/append. This design ensures predictable behavior where users
  specify the complete desired state in a single update operation."
  [arguments]
  (let [;; Define conversion functions for each field type
        conversions {:status convert-enum-field
                     :type convert-enum-field
                     :meta convert-meta-field
                     :relations convert-relations-field}

        ;; List of all updatable fields
        updatable-fields [:title :description :design :parent-id
                          :status :category :type :meta :relations]]

    ;; Build updates map by checking each field for presence
    (reduce (fn [updates field-key]
              (if (contains? arguments field-key)
                (let [value (get arguments field-key)
                      converter (get conversions field-key identity)
                      converted-value (converter value)]
                  (assoc updates field-key converted-value))
                updates))
            {}
            updatable-fields)))

(defn- update-task-impl
  "Implementation of update-task tool.

  Updates specified fields of an existing task in tasks.ednl.
  Supports all mutable task fields with proper nil handling."
  [config _context arguments]
  (let [task-id (:task-id arguments)
        tasks-file (prepare-task-file config)]

    ;; Load tasks
    (tasks/load-tasks! tasks-file)

    (let [;; Extract provided fields with conversions applied
          updates (extract-provided-updates arguments)]

      ;; Validate at least one field provided
      (if (empty? updates)
        (build-tool-error-response
          "No fields to update"
          "update-task"
          {:task-id task-id
           :file tasks-file})

        ;; Validate task exists
        (or (validate-task-exists task-id "update-task" tasks-file)

            ;; Validate parent-id exists if provided and non-nil
            (when (and (contains? updates :parent-id) (:parent-id updates))
              (validate-parent-id-exists (:parent-id updates) "update-task" task-id tasks-file "Parent task not found"))

            ;; Validate schema after merging updates
            (let [old-task (tasks/get-task task-id)
                  updated-task (merge old-task updates)]
              (validate-task-schema updated-task "update-task" task-id tasks-file))

            ;; All validations passed - apply update
            (do
              (tasks/update-task task-id updates)
              (tasks/save-tasks! tasks-file)
              (let [final-task (tasks/get-task task-id)]
                {:content [{:type "text"
                            :text (str "Task " task-id " updated in " tasks-file)}
                           {:type "text"
                            :text (json/write-str
                                    {:task (select-keys
                                             final-task
                                             [:id :title :category :type :status :parent-id])
                                     :metadata {:file tasks-file
                                                :operation "update-task"}})}]
                 :isError false})))))))

(defn update-task-tool
  "Tool to update fields of an existing task.

  Accepts config parameter for future git-aware functionality."
  [config]
  {:name "update-task"
   :description "Update fields of an existing task by ID. Only provided fields will be updated. Supports updating: title, description, design, parent-id, status, category, type, meta, and relations. Pass nil for optional fields (parent-id, meta, relations) to clear their values."
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
      :description "New design notes for the task (optional)"}
     "parent-id"
     {:type ["integer" "null"]
      :description "New parent task ID (optional). Pass null to remove parent relationship."}
     "status"
     {:type "string"
      :enum ["open" "closed" "in-progress" "blocked"]
      :description "New task status (optional)"}
     "category"
     {:type "string"
      :description "New task category (optional)"}
     "type"
     {:type "string"
      :enum ["task" "bug" "feature" "story" "chore"]
      :description "New task type (optional)"}
     "meta"
     {:type ["object" "null"]
      :description "New metadata map with string keys and values (optional). Pass null to clear. Replaces entire map, does not merge."}
     "relations"
     {:type ["array" "null"]
      :items {:type "object"
              :properties {"id" {:type "integer"}
                           "relates-to" {:type "integer"}
                           "as-type" {:type "string"
                                      :enum ["blocked-by" "related" "discovered-during"]}}
              :required ["id" "relates-to" "as-type"]}
      :description "New relations vector (optional). Pass null to clear. Replaces entire vector, does not merge."}}
    :required ["task-id"]}
   :implementation (partial update-task-impl config)})
