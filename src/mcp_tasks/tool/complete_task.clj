(ns mcp-tasks.tool.complete-task
  "MCP tool for marking tasks as complete and archiving them.

  This namespace implements the complete-task tool, which handles the full
  lifecycle of task completion including:
  - Finding tasks by ID or title match
  - Appending optional completion comments
  - Moving tasks from tasks.ednl to complete.ednl with :status :closed
  - Handling special cases for story tasks and child tasks
  - Committing changes to git

  The tool supports three completion modes:
  - Regular tasks: Simple completion and archive
  - Child tasks: Completion with parent relationship preservation
  - Story tasks: Completion with archival of all child tasks

  Part of the refactored tool architecture where each tool lives in its own
  namespace under mcp-tasks.tool.*, with the main tools.clj acting as a facade."
  (:require
    [mcp-tasks.execution-state :as exec-state]
    [mcp-tasks.tasks :as tasks]
    [mcp-tasks.tools.git :as git]
    [mcp-tasks.tools.helpers :as helpers]
    [mcp-tasks.tools.validation :as validation]))

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
      ;; Clear execution state after successful completion
      (exec-state/clear-execution-state! (:base-dir config))

      (let [msg-text (str "Task " (:id task) " completed and moved to " complete-file)
            modified-files [tasks-rel-path complete-rel-path]
            git-result (when use-git?
                         (git/commit-task-changes (:base-dir config)
                                                  modified-files
                                                  (str "Complete task #" (:id task) ": " (:title task))))
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
          ;; Clear execution state after successful completion
          (exec-state/clear-execution-state! (:base-dir config))

          (let [msg-text (str "Task " (:id task) " completed")
                modified-files [tasks-rel-path]
                git-result (when use-git?
                             (git/commit-task-changes (:base-dir config)
                                                      modified-files
                                                      (str "Complete task #" (:id task) ": " (:title task))))
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
          ;; Clear execution state after successful completion
          (exec-state/clear-execution-state! (:base-dir config))

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
                             (git/commit-task-changes (:base-dir config)
                                                      modified-files
                                                      commit-msg))]
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
  (let [context (helpers/setup-completion-context config "complete-task")]
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
