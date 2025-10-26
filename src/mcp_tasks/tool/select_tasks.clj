(ns mcp-tasks.tool.select-tasks
  "MCP tool for querying and retrieving tasks from the task queue.

  This namespace implements the select-tasks tool, which provides flexible
  task filtering and retrieval capabilities. Tasks can be filtered by:
  - task-id: Exact ID match
  - category: Task execution category
  - parent-id: Child tasks of a specific parent
  - title-pattern: Regex or substring matching
  - type: Task type (:task, :bug, :feature, :story, :chore)
  - status: Task status (:open, :closed, :in-progress, :blocked)

  Results are returned with metadata including count and pagination info.
  Supports both single-task lookup (unique: true) and multi-task queries
  with configurable limits.

  Part of the refactored tool architecture where each tool lives in its own
  namespace under mcp-tasks.tool.*, with the main tools.clj acting as a facade."
  (:require
    [cheshire.core :as json]
    [mcp-tasks.response :as response]
    [mcp-tasks.tasks :as tasks]
    [mcp-tasks.tools.helpers :as helpers]))

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

        ;; Get all matching tasks
        ;; When parent-id is provided, we need to count completed child tasks separately
        (let [query-result (if parent-id
                             ;; Parent-id case: single query with :status :any, partition by status
                             (let [all-matching (tasks/get-tasks
                                                  :task-id task-id
                                                  :category category
                                                  :parent-id parent-id
                                                  :title-pattern title-pattern
                                                  :type type-keyword
                                                  :status :any)
                                   {closed :closed non-closed :non-closed}
                                   (group-by #(if (= :closed (:status %)) :closed :non-closed)
                                             all-matching)
                                   completed-count (count closed)]
                               {:tasks (or non-closed [])
                                :completed-task-count completed-count})
                             ;; Normal case: use status filter as before
                             {:tasks (tasks/get-tasks
                                       :task-id task-id
                                       :category category
                                       :parent-id parent-id
                                       :title-pattern title-pattern
                                       :type type-keyword
                                       :status status-keyword)
                              :completed-task-count nil})
              non-closed-tasks (:tasks query-result)
              completed-count (:completed-task-count query-result)
              total-matches (count non-closed-tasks)
              limited-tasks (vec (take effective-limit non-closed-tasks))
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
                                 :metadata {:open-task-count total-matches
                                            :returned-count result-count
                                            :total-matches total-matches}}]
              (throw (ex-info "unique? constraint violated"
                              {:response response-data}))))

          ;; Build success response
          (let [response-data {:tasks limited-tasks
                               :metadata (cond-> {:open-task-count total-matches
                                                  :returned-count result-count
                                                  :total-matches total-matches
                                                  :limited? (> total-matches result-count)}
                                           ;; Add completed-task-count only when parent-id was provided
                                           completed-count (assoc :completed-task-count completed-count))}]
            {:content [{:type "text"
                        :text (json/generate-string response-data)}]
             :isError false}))))

    (catch clojure.lang.ExceptionInfo e
      ;; Handle validation errors with structured response
      (if-let [response-data (:response (ex-data e))]
        {:content [{:type "text"
                    :text (json/generate-string response-data)}]
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
