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

  Moves first task from tasks.ednl to complete.ednl,
  verifying it matches the provided task-text and optionally adding a
  completion comment.

  Returns:
  - Git mode enabled: Two text items (completion message + JSON with :modified-files)
  - Git mode disabled: Single text item (completion message only)"
  [config _context {:keys [category task-text completion-comment]}]
  (try
    (let [use-git? (:use-git? config)
          tasks-path (path-helper/task-path config ["tasks.ednl"])
          complete-path (path-helper/task-path config ["complete.ednl"])
          tasks-file (:absolute tasks-path)
          complete-file (:absolute complete-path)
          ;; Paths relative to .mcp-tasks
          tasks-rel-path (:relative tasks-path)
          complete-rel-path (:relative complete-path)]

      ;; Load tasks from EDNL file
      (when-not (file-exists? tasks-file)
        (throw (ex-info "Tasks file not found"
                        {:file tasks-file})))

      (tasks/load-tasks! tasks-file)

      ;; Get next incomplete task
      (if-let [task (tasks/get-next-incomplete-by-category category)]
        (let [task-id (:id task)
              title (:title task)
              description (:description task "")
              full-text (if (str/blank? description)
                          title
                          (str title "\n" description))]

          ;; Verify task text matches
          (when-not (or (str/starts-with? full-text task-text)
                        (str/starts-with? title task-text))
            (throw (ex-info "First task does not match provided text"
                            {:category category
                             :expected task-text
                             :actual full-text})))

          ;; Mark task as complete in memory
          (tasks/mark-complete task-id completion-comment)

          ;; Move task from tasks file to complete file
          (tasks/move-task! task-id tasks-file complete-file)

          (if use-git?
            ;; Git mode: return message + JSON with modified files
            {:content [{:type "text"
                        :text (str "Task completed and moved to " complete-file)}
                       {:type "text"
                        :text (json/write-str {:modified-files [tasks-rel-path
                                                                complete-rel-path]})}]
             :isError false}
            ;; Non-git mode: return message only
            {:content [{:type "text"
                        :text (str "Task completed and moved to " complete-file)}]
             :isError false}))
        (throw (ex-info "No tasks found in category"
                        {:category category
                         :file tasks-file}))))
    (catch Exception e
      (response/error-response e))))

(defn- description
  "Generate description for complete-task tool based on config."
  [config]
  (if (:use-git? config)
    "Complete a task by moving it from
   .mcp-tasks/tasks.ednl to .mcp-tasks/complete.ednl.

   Verifies the first task matches the provided text, marks it complete, and
   optionally adds a completion comment.

   Returns two text items:
   1. A completion status message
   2. A JSON-encoded map with :modified-files key containing file paths
      relative to .mcp-tasks for use in git commit workflows."
    "Complete a task by moving it from
   .mcp-tasks/tasks.ednl to .mcp-tasks/complete.ednl.

   Verifies the first task matches the provided text, marks it complete, and
   optionally adds a completion comment.

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
    {"category"
     {:type "string"
      :description "The task category name"}
     "task-text"
     {:type "string"
      :description "Partial text from the beginning of the task to verify"}
     "completion-comment"
     {:type "string"
      :description "Optional comment to append to the completed task"}}
    :required ["category" "task-text"]}
   :implementation (partial complete-task-impl config)})

(defn next-task-impl
  "Implementation of next-task tool.

  Accepts optional filters:
  - category: Task category name
  - parent-id: Parent task ID for filtering children
  - title-pattern: Pattern to match task titles (regex or substring)

  Returns the first matching task from tasks.ednl in a map with :category and :task keys,
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
        (let [title (:title task)
              description (:description task "")
              task-text (if (str/blank? description)
                          title
                          (str title "\n" description))
              task-category (:category task)
              task-id (:id task)]
          {:content [{:type "text"
                      :text (pr-str {:category task-category
                                     :task task-text
                                     :task-id task-id})}]
           :isError false})
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
