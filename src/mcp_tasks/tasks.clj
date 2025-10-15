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
  (reset! tasks {})
  (reset! parent-children {})
  (reset! child-parent {}))

(defn- update-next-id!
  "Update next-id to be greater than all existing task IDs."
  []
  (let [max-id (apply max 0 @task-ids)]
    (vreset! next-id (inc max-id))))

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
  - category: Task category must match exactly
  - parent-id: Task must be a child of this parent
  - title-pattern: Task title must match pattern (regex or substring)
  - type: Task type must match exactly (keyword: :task, :bug, :feature, :story, :chore)
  - status: Task status must match exactly (keyword: :open, :closed, :in-progress, :blocked)
            When nil (default), filters out closed tasks (same as old behavior)

  Returns vector of task maps in the order they appear in tasks.ednl.
  Returns empty vector if no matching tasks found."
  [& {:keys [category parent-id title-pattern type status]}]
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
                       :else #(str/includes? (:title % "") title-pattern))
        ;; Status filter: when nil, exclude closed; when specified, match exactly
        status-match? (if (nil? status)
                        #(not= (:status %) :closed)
                        #(= (:status %) status))]
    (->> ids
         (map #(get task-map %))
         (filter status-match?)
         (filter #(or (nil? category) (= (:category %) category)))
         (filter #(or (nil? parent-id) (= (:parent-id %) parent-id)))
         (filter #(or (nil? type) (= (:type %) type)))
         (filter title-match?)
         vec)))

(defn find-by-title
  "Find all tasks with exact title match.

  Returns vector of matching tasks (may be empty or contain multiple tasks)."
  [title]
  (let [ids @task-ids
        task-map @tasks]
    (->> ids
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

  Returns the newly assigned task ID."
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
    new-id))

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
  Returns number of tasks loaded."
  [file-path]
  (reset-state!)
  (let [task-coll (tasks-file/read-ednl file-path)
        [pc-map cp-map] (build-parent-child-maps task-coll)]
    ;; Populate state
    (reset! task-ids (mapv :id task-coll))
    (reset! tasks (into {} (map (fn [t] [(:id t) t])) task-coll))
    (reset! parent-children pc-map)
    (reset! child-parent cp-map)
    ;; Update next-id
    (update-next-id!)
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
