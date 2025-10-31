(ns mcp-tasks.tool.reopen-task
  "MCP tool for reopening closed tasks."
  (:require
    [mcp-tasks.tasks :as tasks]
    [mcp-tasks.tasks-file :as tasks-file]
    [mcp-tasks.tools.git :as git]
    [mcp-tasks.tools.helpers :as helpers]
    [mcp-tasks.tools.validation :as validation]))

(defn- reopen-from-tasks-ednl
  "Reopens a closed task that's still in tasks.ednl (not archived)."
  [config context task]
  (let [{:keys [tasks-file tasks-rel-path]} context]
    (tasks/mark-open (:id task))
    (tasks/save-tasks! tasks-file)
    {:use-git? (:use-git? config)
     :base-dir (:base-dir config)
     :commit-msg (str "Reopen task #" (:id task) ": " (:title task))
     :modified-files [tasks-rel-path]
     :msg-text (str "Task " (:id task) " reopened in " tasks-file)
     :updated-task (tasks/get-task (:id task))
     :tasks-file tasks-file}))

(defn- reopen-from-complete-ednl
  "Reopens an archived task from complete.ednl by moving it back to tasks.ednl."
  [config context task]
  (let [{:keys [tasks-file complete-file tasks-rel-path complete-rel-path]} context
        task-id (:id task)]
    (tasks/mark-open task-id)
    (let [reopened-task (tasks/get-task task-id)]
      (tasks-file/delete-task complete-file task-id)
      (tasks-file/append-task tasks-file reopened-task)
      (tasks/move-task-to-active task-id)
      {:use-git? (:use-git? config)
       :base-dir (:base-dir config)
       :commit-msg (str "Reopen task #" task-id ": " (:title task))
       :modified-files [tasks-rel-path complete-rel-path]
       :msg-text (str "Task " task-id " reopened and moved from " complete-file " to " tasks-file)
       :updated-task reopened-task
       :tasks-file tasks-file})))

(defn- reopen-task-impl
  "Implementation of reopen-task tool."
  [config _context {:keys [task-id title]}]
  (let [locked-result (helpers/with-task-lock config
                                              (fn []
                                                (let [sync-result (helpers/sync-and-prepare-task-file config)]
                                                  (if (and (map? sync-result) (false? (:success sync-result)))
                                                    (let [{:keys [error error-type]} sync-result
                                                          tasks-dir (:resolved-tasks-dir config)]
                                                      (helpers/build-tool-error-response
                                                        (case error-type
                                                          :conflict (str "Pull failed with conflicts. Resolve manually in " tasks-dir)
                                                          :network (str "Pull failed: " error)
                                                          (str "Pull failed: " error))
                                                        "reopen-task"
                                                        {:error-type error-type
                                                         :error-details error
                                                         :tasks-dir tasks-dir}))
                                                    (let [context (helpers/setup-completion-context config "reopen-task")]
                                                      (if (:isError context)
                                                        context
                                                        (let [{:keys [tasks-file]} context
                                                              task-result (validation/find-task-by-identifiers task-id title "reopen-task" tasks-file)]
                                                          (if (:isError task-result)
                                                            task-result
                                                            (let [task task-result
                                                                  archived? (contains? (set @tasks/complete-task-ids) (:id task))]
                                                              (cond
                                                                (not= (:status task) :closed)
                                                                (helpers/build-tool-error-response
                                                                  "Task is already open"
                                                                  "reopen-task"
                                                                  {:task-id (:id task)
                                                                   :title (:title task)
                                                                   :status (:status task)
                                                                   :file tasks-file})
                                                                archived?
                                                                (reopen-from-complete-ednl config context task)
                                                                :else
                                                                (reopen-from-tasks-ednl config context task)))))))))))]
    (if (:isError locked-result)
      locked-result
      (let [{:keys [updated-task tasks-file modified-files use-git? base-dir commit-msg msg-text]} locked-result
            git-result (when use-git?
                         (git/commit-task-changes base-dir modified-files commit-msg))
            task-data {:task (select-keys updated-task [:id :title :description :category :type :status :parent-id])
                       :metadata {:file tasks-file
                                  :operation "reopen-task"}}]
        (helpers/build-completion-response msg-text modified-files use-git? git-result task-data)))))

(defn- description
  "Generate description for reopen-task tool based on config."
  [config]
  (str
    "Reopen a closed task by changing :status to :open.\n"
    (when (:use-git? config)
      "Automatically commits the task changes.\n")
    "\nIdentifies tasks by exact match using task-id or title.
   At least one identifier must be provided.

   Parameters:
   - task-id: (optional) Exact task ID
   - title: (optional) Exact task title match

   If both task-id and title are provided, they must refer to the same task.
   If only title is provided and multiple tasks have the same title, an error is returned.

   Behavior:
   - Tasks in tasks.ednl (closed but not archived): Status changed to :open
   - Tasks in complete.ednl (archived): Moved back to tasks.ednl (appended at end) with :status :open"))

(defn reopen-task-tool
  "Tool to reopen a closed task."
  [config]
  {:name "reopen-task"
   :description (description config)
   :inputSchema
   {:type "object"
    :properties
    {"task-id"
     {:type "integer"
      :description "Exact task ID to reopen"}
     "title"
     {:type "string"
      :description "Exact task title to match"}}
    :required []}
   :implementation (partial reopen-task-impl config)})
