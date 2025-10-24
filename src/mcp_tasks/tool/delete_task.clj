(ns mcp-tasks.tool.delete-task
  "MCP tool for deleting tasks from the task queue.

  This namespace implements the delete-task tool, which removes tasks by
  marking them with :status :deleted and moving them to complete.ednl.
  This provides a soft-delete mechanism that maintains an audit trail.

  The tool enforces referential integrity by preventing deletion of tasks
  that have non-closed child tasks. Tasks can be identified either by exact
  task-id or by fuzzy title-pattern matching.

  Part of the refactored tool architecture where each tool lives in its own
  namespace under mcp-tasks.tool.*, with the main tools.clj acting as a facade."
  (:require
    [cheshire.core :as json]
    [mcp-tasks.tasks :as tasks]
    [mcp-tasks.tools.git :as git]
    [mcp-tasks.tools.helpers :as helpers]
    [mcp-tasks.tools.validation :as validation]))

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
  ;; Perform file operations inside lock
  (let [locked-result (helpers/with-task-lock config
                                              (fn []
                                                ;; Sync with remote and load tasks
                                                (let [sync-result (helpers/sync-and-prepare-task-file config)]
                                                  (if (and (map? sync-result) (false? (:success sync-result)))
                                                    ;; sync-result is an error map
                                                    (let [{:keys [error error-type]} sync-result
                                                          tasks-dir (:resolved-tasks-dir config)]
                                                      (helpers/build-tool-error-response
                                                        (case error-type
                                                          :conflict (str "Pull failed with conflicts. Resolve manually in " tasks-dir)
                                                          :network (str "Pull failed: " error)
                                                          (str "Pull failed: " error))
                                                        "delete-task"
                                                        {:error-type error-type
                                                         :error-details error
                                                         :tasks-dir tasks-dir}))

                                                    ;; sync-result is the tasks-file path - proceed
                                                    ;; Setup common context and load tasks
                                                    (let [context (helpers/setup-completion-context config "delete-task")]
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
                                                                    (let [{:keys [use-git? tasks-rel-path complete-rel-path base-dir]} context
                                                                          ;; Update task status to :deleted
                                                                          updated-task (assoc task :status :deleted)
                                                                          _ (tasks/update-task (:id task) {:status :deleted})
                                                                          ;; Move to complete.ednl
                                                                          _ (tasks/move-task! (:id task) tasks-file complete-file)]
                                                                      ;; Return intermediate data for git operations
                                                                      {:updated-task updated-task
                                                                       :tasks-rel-path tasks-rel-path
                                                                       :complete-rel-path complete-rel-path
                                                                       :base-dir base-dir
                                                                       :use-git? use-git?})))))))))))))]
    ;; Check if locked section returned an error
    (if (:isError locked-result)
      locked-result

      ;; Perform git operations outside lock
      (let [{:keys [updated-task tasks-rel-path complete-rel-path base-dir use-git?]} locked-result
            msg-text (str "Task " (:id updated-task) " deleted successfully")
            modified-files [tasks-rel-path complete-rel-path]
            git-result (when use-git?
                         (git/commit-task-changes base-dir
                                                  modified-files
                                                  (str "Delete task #" (:id updated-task) ": " (:title updated-task))))]
        ;; Build response with deleted task data
        (if use-git?
          {:content [{:type "text"
                      :text msg-text}
                     {:type "text"
                      :text (json/generate-string {:deleted updated-task
                                                   :metadata {:count 1
                                                              :status "deleted"}})}
                     {:type "text"
                      :text (json/generate-string
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
                      :text (json/generate-string {:deleted updated-task
                                                   :metadata {:count 1
                                                              :status "deleted"}})}]
           :isError false})))))

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
