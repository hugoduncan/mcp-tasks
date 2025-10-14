(ns mcp-tasks.story-tools
  "Story management tools"
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [mcp-tasks.path-helper :as path-helper]
    [mcp-tasks.response :as response]
    [mcp-tasks.tasks :as tasks]))

;; Helper Functions

(defn- find-story-by-name
  "Find a story task by name in loaded tasks.

  Returns the story task map or nil if not found."
  [story-name]
  (let [task-map @tasks/tasks
        task-ids @tasks/task-ids]
    (->> task-ids
         (map #(get task-map %))
         (filter #(and (= (:type %) :story)
                       (= (:title %) story-name)))
         first)))

(defn- format-task-as-text
  "Format a task map as text for display.

  Combines :title and :description fields."
  [task]
  (let [title (:title task)
        description (:description task "")]
    (if (str/blank? description)
      title
      (str title "\n" description))))

(defn- file-exists?
  "Check if a file exists"
  [file-path]
  (.exists (io/file file-path)))

(defn- get-children-in-order
  "Get children for a parent in task-ids order.

  Returns vector of child task maps."
  [parent-id]
  (let [child-ids-set (get @tasks/parent-children parent-id #{})
        task-ids @tasks/task-ids
        task-map @tasks/tasks]
    (->> task-ids
         (filter child-ids-set)
         (mapv #(get task-map %)))))

;; Tool Implementations

(defn- next-story-task-impl
  "Implementation of next-story-task tool.

  Loads tasks from .mcp-tasks/tasks.ednl and finds the first incomplete
  child task for the specified story.

  Returns a map with :task-text, :category, :task-id, and :task-index keys,
  or nil values if no incomplete task is found."
  [config _context {:keys [story-name]}]
  (try
    (let [tasks-path (path-helper/task-path config ["tasks.ednl"])
          tasks-file (:absolute tasks-path)]

      ;; Load tasks from EDNL file
      (when (file-exists? tasks-file)
        (tasks/load-tasks! tasks-file))

      ;; Find story task
      (if-let [story (find-story-by-name story-name)]
        (let [story-id (:id story)
              ;; Get children in order
              all-children (get-children-in-order story-id)
              ;; Find first incomplete
              first-incomplete (->> all-children
                                    (filter #(not= (:status %) :closed))
                                    first)]
          (if first-incomplete
            (let [task-text (format-task-as-text first-incomplete)
                  category (:category first-incomplete)
                  task-id (:id first-incomplete)
                  task-index (.indexOf (mapv :id all-children) task-id)]
              {:content [{:type "text"
                          :text (pr-str {:task-text task-text
                                         :category category
                                         :task-id task-id
                                         :task-index task-index})}]
               :isError false})
            ;; No incomplete tasks for this story
            {:content [{:type "text"
                        :text (pr-str {:task-text nil
                                       :category nil
                                       :task-id nil
                                       :task-index nil})}]
             :isError false}))
        ;; Story not found
        (throw (ex-info "Story not found"
                        {:story-name story-name}))))
    (catch Exception e
      (response/error-response e))))

(defn next-story-task-tool
  "Tool to return the next incomplete task from a story's task list.

  Takes a story-name parameter and reads from .mcp-tasks/tasks.ednl.
  Returns a map with :task-text, :category, :task-id, and :task-index, or nil values if no tasks remain."
  [config]
  {:name "next-story-task"
   :description "Return the next incomplete task from a story's task list.

  Loads tasks from `.mcp-tasks/tasks.ednl`, finds story by name,
  returns first incomplete child task with :task-text, :category, :task-id, :task-index (or nil if none)."
   :inputSchema
   {:type "object"
    :properties
    {"story-name"
     {:type "string"
      :description "The story name"}}
    :required ["story-name"]}
   :implementation (partial next-story-task-impl config)})
