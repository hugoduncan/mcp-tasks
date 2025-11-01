(ns mcp-tasks.tasks
  "High-level task management with in-memory state.

  Provides query and mutation operations over an in-memory task store,
  backed by EDNL file storage."
  (:require
    [clojure.string :as str]
    [mcp-tasks.schema :as schema]
    [mcp-tasks.tasks-file :as tasks-file]))

;; State Management

(defonce ^{:doc "Ordered vector of task IDs (matches disk order)."}
  task-ids
  (atom []))

(defonce ^{:doc "Map of task-id → task-map."}
  tasks
  (atom {}))

(defonce ^{:doc "Ordered vector of completed task IDs (matches disk order in complete.ednl)."}
  complete-task-ids
  (atom []))

(defonce ^{:doc "Map of parent-id → set of child-ids."}
  parent-children
  (atom {}))

(defonce ^{:doc "Map of child-id → parent-id."}
  child-parent
  (atom {}))

(defonce ^{:doc "Monotonically increasing ID generator."}
  next-id
  (volatile! 1))

;; State Initialization Helpers

(defn- reset-state!
  "Reset all state atoms to empty values."
  []
  (reset! task-ids [])
  (reset! complete-task-ids [])
  (reset! tasks {})
  (reset! parent-children {})
  (reset! child-parent {}))

(defn- update-next-id!
  "Update next-id to be greater than all existing task IDs.

  Optionally accepts :additional-ids (e.g., IDs from complete.ednl) to ensure
  monotonicity across both active and completed tasks."
  [& {:keys [additional-ids]}]
  (let [active-max (apply max 0 @task-ids)
        complete-max (if additional-ids
                       (apply max 0 additional-ids)
                       0)
        max-id (max active-max complete-max)]
    (vreset! next-id (inc max-id))))

;; Atom Manipulation Helpers

(defn move-task-to-active
  "Move a task from complete-task-ids to task-ids.

  Assumes task exists. No validation performed."
  [task-id]
  (swap! task-ids conj task-id)
  (swap! complete-task-ids #(filterv (fn [id] (not= id task-id)) %)))

(defn move-task-to-complete
  "Move a task from task-ids to complete-task-ids.

  Assumes task exists. No validation performed."
  [task-id]
  (swap! complete-task-ids conj task-id)
  (swap! task-ids #(filterv (fn [id] (not= id task-id)) %)))

(defn- build-parent-child-maps
  "Build parent-children and child-parent maps from task collection.

  Returns [parent-children-map child-parent-map]."
  [task-coll]
  (reduce
    (fn [[pc cp] task]
      (if-let [parent-id (:parent-id task)]
        [(update pc parent-id (fnil conj #{}) (:id task))
         (assoc cp (:id task) parent-id)]
        [pc cp]))
    [{} {}]
    task-coll))

;; Query API

(defn get-task
  "Get task by ID.

  Returns task map or nil if not found."
  [id]
  (get @tasks id))

;; Blocking Logic

(defn- detect-circular-dependency
  "Detect circular dependencies in :blocked-by relations.

  Returns [:cycle path] if a cycle is found, nil otherwise.
  Path shows the cycle: [id1 id2 id3 id1]."
  [task-id visited]
  (cond
    (visited task-id)
    [:cycle [task-id]]

    :else
    (let [task (get-task task-id)]
      (if-not task
        nil
        (let [blocking-ids (->> (:relations task [])
                                (filter #(= :blocked-by (:as-type %)))
                                (map :relates-to))]
          (reduce
            (fn [_ blocking-id]
              (let [result (detect-circular-dependency
                             blocking-id
                             (conj visited task-id))]
                (when (= :cycle (first result))
                  (reduced (update result 1 #(conj % task-id))))))
            nil
            blocking-ids))))))

(defn get-blocking-tasks
  "Get all tasks that are blocking the specified task.

  Returns vector of task maps that have incomplete status and are referenced
  in :blocked-by relations. Returns empty vector if task is not blocked."
  [task-id]
  (let [task (get-task task-id)]
    (if-not task
      []
      (let [blocking-ids (->> (:relations task [])
                              (filter #(= :blocked-by (:as-type %)))
                              (map :relates-to))
            blocking-tasks (keep get-task blocking-ids)]
        (filterv #(schema/blocking-statuses (:status %))
                 blocking-tasks)))))

(defn is-task-blocked?
  "Check if a task is blocked by incomplete tasks.

  Returns map with:
  - :blocked? - true if any :blocked-by relation references an incomplete task
  - :blocking-ids - vector of task IDs that are blocking this task (empty if unblocked)
  - :error - error message if invalid task ID or other issue
  - :circular-dependency - vector showing cycle path if detected

  A task is blocked if ANY :blocked-by relation points to a task with status
  in #{:open :in-progress :blocked}. A task is unblocked if ALL :blocked-by
  relations point to completed tasks (:closed or :deleted) or has no :blocked-by
  relations."
  [task-id]
  (let [task (get-task task-id)]
    (if-not task
      {:blocked? false
       :blocking-ids []
       :error (str "Task " task-id " not found")}
      (let [blocking-relations (->> (:relations task [])
                                    (filter #(= :blocked-by (:as-type %))))
            blocking-ids (map :relates-to blocking-relations)
            ;; Check for circular dependencies
            cycle-result (detect-circular-dependency task-id #{})
            ;; Check each blocking task
            blocking-results (for [blocking-id blocking-ids]
                               (let [blocking-task (get-task blocking-id)]
                                 (cond
                                   (nil? blocking-task)
                                   {:invalid-id blocking-id
                                    :error (str "Blocked by invalid task ID: " blocking-id)}

                                   (schema/blocking-statuses (:status blocking-task))
                                   {:blocking-id blocking-id
                                    :is-blocking true}

                                   :else
                                   {:blocking-id blocking-id
                                    :is-blocking false})))
            invalid-ids (keep :invalid-id blocking-results)
            blocking-task-ids (keep #(when (:is-blocking %) (:blocking-id %))
                                    blocking-results)
            is-blocked (or (seq blocking-task-ids) (seq invalid-ids))]
        (cond-> {:blocked? (boolean is-blocked)
                 :blocking-ids (vec blocking-task-ids)}
          (seq invalid-ids)
          (assoc :error (str/join ", " (map :error (filter :error blocking-results))))

          cycle-result
          (assoc :circular-dependency (second cycle-result)))))))

(defn is-tasks-blocked?
  "Batch version of is-task-blocked? for optimized processing of multiple tasks.

  Accepts a collection of task IDs and returns a map of task-id -> blocking-info.
  This is more efficient than calling is-task-blocked? repeatedly because:
  - Builds task lookup map once
  - Caches blocking task status lookups
  - Detects circular dependencies once per task

  Returns map of task-id -> {:blocked? bool, :blocking-ids [...], :error ..., :circular-dependency ...}"
  [task-ids]
  (let [;; Build task map once for all lookups
        task-map @tasks
        ;; Build a cache for circular dependency checks (memoized during this batch)
        cycle-cache (atom {})
        ;; Helper to check circular deps with caching
        check-cycle (fn check-cycle
                      [tid visited]
                      (if-let [cached (@cycle-cache tid)]
                        cached
                        (let [result (detect-circular-dependency tid visited)]
                          (swap! cycle-cache assoc tid result)
                          result)))]

    ;; Process each task ID
    (into {}
          (map (fn [task-id]
                 (let [task (get task-map task-id)]
                   [task-id
                    (if-not task
                      {:blocked? false
                       :blocking-ids []
                       :error (str "Task " task-id " not found")}
                      (let [blocking-relations (->> (:relations task [])
                                                    (filter #(= :blocked-by (:as-type %))))
                            blocking-ids (map :relates-to blocking-relations)
                            ;; Check for circular dependencies (cached)
                            cycle-result (check-cycle task-id #{})
                            ;; Check each blocking task
                            blocking-results (for [blocking-id blocking-ids]
                                               (let [blocking-task (get task-map blocking-id)]
                                                 (cond
                                                   (nil? blocking-task)
                                                   {:invalid-id blocking-id
                                                    :error (str "Blocked by invalid task ID: " blocking-id)}

                                                   (schema/blocking-statuses (:status blocking-task))
                                                   {:blocking-id blocking-id
                                                    :is-blocking true}

                                                   :else
                                                   {:blocking-id blocking-id
                                                    :is-blocking false})))
                            invalid-ids (keep :invalid-id blocking-results)
                            blocking-task-ids (keep #(when (:is-blocking %) (:blocking-id %))
                                                    blocking-results)
                            is-blocked (or (seq blocking-task-ids) (seq invalid-ids))]
                        (cond-> {:blocked? (boolean is-blocked)
                                 :blocking-ids (vec blocking-task-ids)}
                          (seq invalid-ids)
                          (assoc :error (str/join ", " (map :error (filter :error blocking-results))))

                          cycle-result
                          (assoc :circular-dependency (second cycle-result)))))]))
               task-ids))))

(defn get-children
  "Get all child tasks for a parent ID.

  Returns vector of child task maps (empty if no children)."
  [parent-id]
  (let [child-ids (get @parent-children parent-id #{})]
    (mapv #(get @tasks %) child-ids)))

(defn get-next-incomplete-by-category
  "Find first incomplete task in the specified category.

  Returns task map or nil if no incomplete tasks found."
  [category]
  (let [ids @task-ids
        task-map @tasks]
    (->> ids
         (map #(get task-map %))
         (filter #(and (= (:category %) category)
                       (not= (:status %) :closed)))
         first)))

(defn get-next-incomplete-by-parent
  "Find first incomplete child task for a parent ID.

  Returns task map or nil if no incomplete children found."
  [parent-id]
  (let [children (get-children parent-id)]
    (->> children
         (filter #(not= (:status %) :closed))
         first)))

(defn get-next-incomplete
  "Find first incomplete task matching optional filters.

  Filters are AND-ed together:
  - category: Task category must match exactly
  - parent-id: Task must be a child of this parent
  - title-pattern: Task title must match pattern (regex or substring)

  Returns task map or nil if no matching incomplete tasks found."
  [& {:keys [category parent-id title-pattern]}]
  (let [ids @task-ids
        task-map @tasks
        ;; Build regex if possible, otherwise use substring match
        title-matcher (when title-pattern
                        (try
                          (re-pattern title-pattern)
                          (catch Exception _
                            ;; Fall back to substring match
                            nil)))
        title-match? (cond
                       (nil? title-pattern) (constantly true)
                       title-matcher #(re-find title-matcher (:title % ""))
                       :else #(str/includes? (:title % "") title-pattern))]
    (->> ids
         (map #(get task-map %))
         (filter #(not= (:status %) :closed))
         (filter #(or (nil? category) (= (:category %) category)))
         (filter #(or (nil? parent-id) (= (:parent-id %) parent-id)))
         (filter title-match?)
         first)))

(defn get-tasks
  "Find all tasks matching optional filters.

  Filters are AND-ed together:
  - task-id: Task ID must match exactly
  - category: Task category must match exactly
  - parent-id: Task must be a child of this parent
  - title-pattern: Task title must match pattern (regex or substring)
  - type: Task type must match exactly (keyword: :task, :bug, :feature, :story, :chore)
  - status: Task status must match exactly (keyword: :open, :closed, :in-progress, :blocked)
            When nil (default), returns only non-closed tasks
            When :any, returns tasks regardless of status

  Returns vector of task maps in the order they appear in tasks.ednl.
  Returns empty vector if no matching tasks found."
  [& {:keys [task-id category parent-id title-pattern type status]}]
  (let [task-map @tasks
        ;; When status is specified (including :closed) or :any, search both active and completed tasks
        ;; When status is nil, only search active tasks (exclude closed)
        ;; Always preserve file order by iterating through ordered ID vectors
        task-seq (if (or (some? status) (= status :any))
                   (keep #(get task-map %) (concat @task-ids @complete-task-ids))
                   (keep #(get task-map %) @task-ids))
        ;; Build regex if possible, otherwise use substring match
        title-matcher (when title-pattern
                        (try
                          (re-pattern title-pattern)
                          (catch Exception _
                            ;; Fall back to substring match
                            nil)))
        title-match? (cond
                       (nil? title-pattern) (constantly true)
                       title-matcher #(re-find title-matcher (:title % ""))
                       :else #(str/includes? (:title % "") title-pattern))
        ;; Status filter: when nil, exclude closed; when :any, include all; when specified, match exactly
        status-match? (cond
                        (nil? status) #(not= (:status %) :closed)
                        (= status :any) (constantly true)
                        :else #(= (:status %) status))]
    (->> task-seq
         (filter status-match?)
         (filter #(or (nil? task-id) (= (:id %) task-id)))
         (filter #(or (nil? category) (= (:category %) category)))
         (filter #(or (nil? parent-id) (= (:parent-id %) parent-id)))
         (filter #(or (nil? type) (= (:type %) type)))
         (filter title-match?)
         vec)))

(defn find-by-title
  "Find all tasks with exact title match.

  Searches both active and completed tasks.
  Returns vector of matching tasks (may be empty or contain multiple tasks)."
  [title]
  (let [all-ids (concat @task-ids @complete-task-ids)
        task-map @tasks]
    (->> all-ids
         (map #(get task-map %))
         (filter #(= (:title %) title))
         vec)))

(defn verify-task-text
  "Check if task with given ID has text that starts with partial-text.

  Checks both :title and :description fields.
  Returns true if match found, false otherwise."
  [id partial-text]
  (if-let [task (get-task id)]
    (let [title (:title task "")
          description (:description task "")]
      (or (str/starts-with? title partial-text)
          (str/starts-with? description partial-text)))
    false))

;; Mutation API

(defn add-task
  "Add a new task to in-memory state.

  Generates a new task ID and adds the task to all relevant state atoms.
  Options:
  - :prepend? - If true, add at beginning; otherwise add at end (default false)

  Returns the complete task map with the newly assigned ID."
  [task & {:keys [prepend?] :or {prepend? false}}]
  (let [new-id @next-id
        task-with-id (assoc task :id new-id)]
    ;; Validate before adding
    (when-not (schema/valid-task? task-with-id)
      (throw (ex-info "Invalid task schema"
                      {:task task-with-id
                       :explanation (schema/explain-task task-with-id)})))
    ;; Update task-ids
    (if prepend?
      (swap! task-ids #(into [new-id] %))
      (swap! task-ids conj new-id))
    ;; Update tasks map
    (swap! tasks assoc new-id task-with-id)
    ;; Update parent-child maps if needed
    (when-let [parent-id (:parent-id task-with-id)]
      (swap! parent-children update parent-id (fnil conj #{}) new-id)
      (swap! child-parent assoc new-id parent-id))
    ;; Increment next-id
    (vswap! next-id inc)
    task-with-id))

(defn update-task
  "Update an existing task in memory.

  Applies the updates map to the task with given ID.
  Maintains parent-child relationship invariants.
  Throws ex-info if task not found."
  [id updates]
  (when-not (get-task id)
    (throw (ex-info "Task not found" {:id id})))
  (let [old-task (get-task id)
        new-task (merge old-task updates)
        old-parent (:parent-id old-task)
        new-parent (:parent-id new-task)]
    ;; Validate updated task
    (when-not (schema/valid-task? new-task)
      (throw (ex-info "Invalid task schema after update"
                      {:task new-task
                       :explanation (schema/explain-task new-task)})))
    ;; Update tasks map
    (swap! tasks assoc id new-task)
    ;; Update parent-child maps if parent changed
    (when (not= old-parent new-parent)
      ;; Remove from old parent
      (when old-parent
        (swap! parent-children
               (fn [pc]
                 (let [updated (update pc old-parent disj id)]
                   (if (empty? (get updated old-parent))
                     (dissoc updated old-parent)
                     updated))))
        (swap! child-parent dissoc id))
      ;; Add to new parent
      (when new-parent
        (swap! parent-children update new-parent (fnil conj #{}) id)
        (swap! child-parent assoc id new-parent)))
    new-task))

(defn mark-complete
  "Mark a task as complete with optional completion comment.

  Updates task status to :closed and optionally adds comment to description.
  Throws ex-info if task not found."
  [id comment]
  (when-not (get-task id)
    (throw (ex-info "Task not found" {:id id})))
  (let [task (get-task id)
        description (:description task "")
        new-description (if (and comment (not (str/blank? comment)))
                          (str description "\n\nCompleted: " comment)
                          description)]
    (update-task id {:status :closed
                     :description new-description})))

(defn mark-open
  "Mark a task as open by changing status to :open.

  This is the inverse of mark-complete.
  Throws ex-info if task not found."
  [id]
  (when-not (get-task id)
    (throw (ex-info "Task not found" {:id id})))
  (update-task id {:status :open}))

(defn delete-task
  "Remove task from in-memory state.

  Does not modify disk - use move-task! for persistence.
  Throws ex-info if task not found."
  [id]
  (when-not (get-task id)
    (throw (ex-info "Task not found" {:id id})))
  (let [task (get-task id)]
    ;; Remove from task-ids
    (swap! task-ids #(filterv (fn [tid] (not= tid id)) %))
    ;; Remove from tasks map
    (swap! tasks dissoc id)
    ;; Remove from parent-child maps
    (when-let [parent-id (:parent-id task)]
      (swap! parent-children
             (fn [pc]
               (let [updated (update pc parent-id disj id)]
                 (if (empty? (get updated parent-id))
                   (dissoc updated parent-id)
                   updated))))
      (swap! child-parent dissoc id))
    task))

;; Persistence API

(defn load-tasks!
  "Load tasks from EDNL file into memory.

  Resets current state and populates from file.

  Options:
  - :complete-file - Path to complete.ednl to also load completed tasks

  Returns number of tasks loaded from primary file."
  [file-path & {:keys [complete-file]}]
  (reset-state!)
  (let [task-coll (tasks-file/read-ednl file-path)
        ;; Also load completed tasks if complete-file is provided
        complete-coll (when complete-file
                        (tasks-file/read-ednl complete-file))
        ;; Combine both collections for parent-child map building
        all-tasks (concat task-coll complete-coll)
        [pc-map cp-map] (build-parent-child-maps all-tasks)
        ;; Extract IDs from complete tasks
        complete-ids (when complete-coll
                       (mapv :id complete-coll))]
    ;; Populate state with active tasks only in task-ids
    (reset! task-ids (mapv :id task-coll))
    ;; Track completed task IDs in order
    (reset! complete-task-ids (or complete-ids []))
    ;; But include both active and completed tasks in the tasks map
    (reset! tasks (into {}
                        (concat
                          (map (fn [t] [(:id t) t]) task-coll)
                          (map (fn [t] [(:id t) t]) complete-coll))))
    (reset! parent-children pc-map)
    (reset! child-parent cp-map)
    ;; Update next-id considering both active and completed tasks
    (update-next-id! :additional-ids complete-ids)
    (count task-coll)))

(defn save-tasks!
  "Save in-memory tasks to EDNL file.

  Writes tasks in task-ids order to maintain disk ordering.
  Returns number of tasks saved."
  [file-path]
  (let [ids @task-ids
        task-map @tasks
        task-coll (mapv #(get task-map %) ids)]
    ;; Write all tasks atomically
    (tasks-file/write-tasks file-path task-coll)
    (count task-coll)))

(defn move-task!
  "Move task from one file to another.

  Atomically removes task from source file and appends to destination file.
  Updates in-memory state by removing the task (since in-memory state represents
  only tasks.ednl, not complete.ednl).
  Throws ex-info if task not found."
  [id from-file to-file]
  (when-not (get-task id)
    (throw (ex-info "Task not found" {:id id})))
  (let [task (get-task id)]
    ;; Delete from source file
    (tasks-file/delete-task from-file id)
    ;; Append to destination file
    (tasks-file/append-task to-file task)
    ;; Remove from in-memory state since it's no longer in tasks.ednl
    (delete-task id)
    task))

(defn move-tasks!
  "Move multiple tasks from one file to another atomically.

  Removes all tasks from source file and appends to destination file.
  Updates in-memory state by removing all tasks (since in-memory state represents
  only tasks.ednl, not complete.ednl).
  Throws ex-info if any task not found."
  [ids from-file to-file]
  ;; Validate all tasks exist first
  (doseq [id ids]
    (when-not (get-task id)
      (throw (ex-info "Task not found" {:id id}))))

  ;; Get all tasks
  (let [tasks-to-move (mapv get-task ids)]
    ;; Delete all from source file atomically
    (let [remaining-tasks (remove #(contains? (set ids) (:id %))
                                  (tasks-file/read-ednl from-file))]
      (tasks-file/write-tasks from-file remaining-tasks))

    ;; Append all to destination file atomically
    (let [existing-tasks (tasks-file/read-ednl to-file)
          all-tasks (into existing-tasks tasks-to-move)]
      (tasks-file/write-tasks to-file all-tasks))

    ;; Remove all from in-memory state
    (doseq [id ids]
      (delete-task id))

    tasks-to-move))
