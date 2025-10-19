(ns mcp-tasks.tool.add-task
  "MCP tool for adding new tasks to the task queue.

  This namespace implements the add-task tool, which creates new task records
  and appends them to the tasks.ednl file. Tasks can be added to the end of
  the queue (default) or prepended to the beginning for higher priority.

  The tool integrates with the category system, validating that the specified
  category exists and has corresponding prompt resources. It also supports
  task hierarchies through optional parent-id references.

  Part of the refactored tool architecture where each tool lives in its own
  namespace under mcp-tasks.tool.*, with the main tools.clj acting as a facade."
  (:require
    [clojure.data.json :as json]
    [clojure.string :as str]
    [mcp-tasks.prompts :as prompts]
    [mcp-tasks.tasks :as tasks]
    [mcp-tasks.tools.git :as git]
    [mcp-tasks.tools.helpers :as helpers]
    [mcp-tasks.tools.validation :as validation]))

(defn- add-task-impl
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
  (let [tasks-file (helpers/prepare-task-file config)]
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
