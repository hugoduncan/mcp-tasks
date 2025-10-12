(ns mcp-tasks.tools
  "Task management tools"
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [mcp-tasks.prompts :as prompts]
    [mcp-tasks.story-tasks :as story-tasks]))

(defn- read-task-file
  "Read task file and return content as string.
  Returns an empty string if file doesn't exist"
  [file-path]
  (if (.exists (io/file file-path))
    (slurp file-path)
    ""))

(defn- write-task-file
  "Write content to task file atomically"
  [file-path content]
  (let [file (io/file file-path)
        parent (.getParentFile file)]
    (when parent
      (.mkdirs parent))
    (let [temp-file (io/file (str file-path ".tmp"))]
      (spit temp-file content)
      (.renameTo temp-file file))))

(defn- parse-tasks
  "Parse markdown task list into individual task strings.
  Returns vector of task strings, each starting with '- [ ]' or '- [x]'"
  [content]
  (let [lines (str/split-lines content)
        task-pattern #"^- \[([ x])\] (.*)"]
    (loop [lines lines
           current-task []
           tasks []]
      (if (empty? lines)
        (if (seq current-task)
          (conj tasks (str/join "\n" current-task))
          tasks)
        (let [line (first lines)
              rest-lines (rest lines)]
          (if (re-matches task-pattern line)
            ;; Start of new task
            (if (seq current-task)
              (recur rest-lines [line]
                     (conj tasks (str/join "\n" current-task)))
              (recur rest-lines [line] tasks))
            ;; Continuation of current task or empty line
            (if (seq current-task)
              (recur rest-lines (conj current-task line) tasks)
              (recur rest-lines [] tasks))))))))

(defn- task-matches?
  "Check if task text starts with the given partial text.
   The test is case-insensitive and ignores whitespace."
  [task-text partial-text]
  (let [normalize #(-> % str/lower-case (str/replace #"\s+" " ") str/trim)
        task-content (-> task-text
                         (str/replace #"^- \[([ x])\] " "")
                         normalize)
        search-text (normalize partial-text)]
    (str/starts-with? task-content search-text)))

(defn- mark-complete
  "Mark task as complete and optionally append completion comment"
  [task-text completion-comment]
  (let [completed-task (str/replace task-text #"^- \[ \]" "- [x]")]
    (if (and completion-comment (not (str/blank? completion-comment)))
      (str completed-task "\n\n" (str/trim completion-comment))
      completed-task)))

(defn- complete-task-impl
  "Implementation of complete-task tool.

  Moves first task from tasks/<category>.md to complete/<category>.md,
  verifying it matches the provided task-text and optionally adding a
  completion comment.
  
  Returns:
  - Git mode enabled: Two text items (completion message + JSON with :modified-files)
  - Git mode disabled: Single text item (completion message only)"
  [config _context {:keys [category task-text completion-comment]}]
  (try
    (let [use-git? (:use-git? config)
          tasks-dir ".mcp-tasks/tasks"
          complete-dir ".mcp-tasks/complete"
          tasks-file (str tasks-dir "/" category ".md")
          complete-file (str complete-dir "/" category ".md")
          tasks-content (read-task-file tasks-file)
          ;; Paths relative to .mcp-tasks
          tasks-rel-path (str "tasks/" category ".md")
          complete-rel-path (str "complete/" category ".md")]

      (when (str/blank? tasks-content)
        (throw (ex-info "No tasks found in category"
                        {:category category
                         :file tasks-file})))

      (let [tasks (parse-tasks tasks-content)]
        (when (empty? tasks)
          (throw (ex-info "No tasks found in category"
                          {:category category
                           :file tasks-file})))

        (let [first-task (first tasks)]
          (when-not (task-matches? first-task task-text)
            (throw (ex-info "First task does not match provided text"
                            {:category category
                             :expected task-text
                             :actual first-task})))

          ;; Mark task as complete and append to complete file
          (let [completed-task (mark-complete
                                 first-task
                                 completion-comment)
                complete-content (read-task-file complete-file)
                new-complete-content (if (str/blank? complete-content)
                                       completed-task
                                       (str complete-content
                                            "\n"
                                            completed-task))]
            (write-task-file complete-file new-complete-content))

          ;; Remove first task from tasks file
          (let [remaining-tasks (rest tasks)
                new-tasks-content (str/join "\n" remaining-tasks)]
            (write-task-file tasks-file new-tasks-content))

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
             :isError false}))))
    (catch Exception e
      {:content [{:type "text"
                  :text (str "Error: " (.getMessage e)
                             (when-let [data (ex-data e)]
                               (str "\nDetails: " (pr-str data))))}]
       :isError true})))

(defn- description
  "Generate description for complete-task tool based on config."
  [config]
  (if (:use-git? config)
    "Complete a task by moving it from
   .mcp-tasks/tasks/<category>.md to .mcp-tasks/complete/<category>.md.

   Verifies the first task matches the provided text, marks it complete, and
   optionally adds a completion comment.
   
   Returns two text items:
   1. A completion status message
   2. A JSON-encoded map with :modified-files key containing file paths
      relative to .mcp-tasks for use in git commit workflows."
    "Complete a task by moving it from
   .mcp-tasks/tasks/<category>.md to .mcp-tasks/complete/<category>.md.

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

  Returns the first task from tasks/<category>.md in a map with :category and :task keys,
  or a map with :category and :status keys if there are no tasks."
  [_context {:keys [category]}]
  (try
    (let [tasks-dir ".mcp-tasks/tasks"
          tasks-file (str tasks-dir "/" category ".md")
          tasks-content (read-task-file tasks-file)]

      (if (str/blank? tasks-content)
        {:content [{:type "text"
                    :text (pr-str {:category category
                                   :status "No more tasks in this category"})}]
         :isError false}
        (let [tasks (parse-tasks tasks-content)]
          (if (empty? tasks)
            {:content [{:type "text"
                        :text (pr-str {:category category
                                       :status "No more tasks in this category"})}]
             :isError false}
            (let [first-task (first tasks)
                  task-text (str/replace first-task #"^- \[([ x])\] " "")]
              {:content [{:type "text"
                          :text (pr-str {:category category
                                         :task task-text})}]
               :isError false})))))
    (catch Exception e
      {:content [{:type "text"
                  :text (str "Error: " (.getMessage e)
                             (when-let [data (ex-data e)]
                               (str "\nDetails: " (pr-str data))))}]
       :isError true})))

(defn next-task-tool
  "Tool to return the next task from a specific category.
  
  Accepts config parameter for future git-aware functionality."
  [_config]
  {:name "next-task"
   :description "Return the next task from tasks/<category>.md"
   :inputSchema
   {:type "object"
    :properties
    {"category"
     {:type "string"
      :description "The task category name"}}
    :required ["category"]}
   :implementation next-task-impl})

(defn add-task-impl
  "Implementation of add-task tool.

  Adds a task to tasks/<category>.md as an incomplete todo item.
  If prepend is true, adds at the beginning; otherwise appends at the end."
  [_context {:keys [category task-text prepend]}]
  (try
    (let [tasks-dir ".mcp-tasks/tasks"
          tasks-file (str tasks-dir "/" category ".md")
          tasks-content (read-task-file tasks-file)
          new-task (str "- [ ] " task-text)
          new-content (cond
                        (str/blank? tasks-content)
                        new-task

                        prepend
                        (str new-task "\n" tasks-content)

                        :else
                        (str tasks-content "\n" new-task))]
      (write-task-file tasks-file new-content)
      {:content [{:type "text"
                  :text (str "Task added to " tasks-file)}]
       :isError false})
    (catch Exception e
      {:content [{:type "text"
                  :text (str "Error: " (.getMessage e)
                             (when-let [data (ex-data e)]
                               (str "\nDetails: " (pr-str data))))}]
       :isError true})))

(defn- add-task-description
  "Build description for add-task tool with available categories and their descriptions."
  []
  (let [category-descs (prompts/category-descriptions)
        categories (sort (keys category-descs))]
    (if (seq categories)
      (str "Add a task to tasks/<category>.md\n\nAvailable categories:\n"
           (str/join "\n"
                     (for [cat categories]
                       (format "- %s: %s" cat (get category-descs cat)))))
      "Add a task to tasks/<category>.md")))

(defn add-task-tool
  "Tool to add a task to a specific category.

  Accepts config parameter for future git-aware functionality."
  [_config]
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
     "prepend"
     {:type "boolean"
      :description "If true, add task at the beginning instead of the end"}}
    :required ["category" "task-text"]}
   :implementation add-task-impl})

;; Story task management

(defn- next-story-task-impl
  "Implementation of next-story-task tool.

  Reads story task file from .mcp-tasks/story-tasks/<story-name>-tasks.md,
  parses it, and returns the first incomplete task with its metadata.

  Returns a map with :task-text, :category, and :task-index keys,
  or nil values if no incomplete task is found."
  [config _context {:keys [story-name]}]
  (try
    (let [base-dir (:base-dir config ".")
          story-tasks-dir (str base-dir "/.mcp-tasks/story-tasks")
          story-tasks-file (str story-tasks-dir "/" story-name "-tasks.md")]

      (when-not (.exists (io/file story-tasks-file))
        (throw (ex-info "Story tasks file not found"
                        {:story-name story-name
                         :file story-tasks-file})))

      (let [content (slurp story-tasks-file)
            tasks (story-tasks/parse-story-tasks content)
            first-incomplete (story-tasks/find-first-incomplete-task tasks)]

        (if first-incomplete
          {:content [{:type "text"
                      :text (pr-str {:task-text (:text first-incomplete)
                                     :category (:category first-incomplete)
                                     :task-index (:index first-incomplete)})}]
           :isError false}
          {:content [{:type "text"
                      :text (pr-str {:task-text nil
                                     :category nil
                                     :task-index nil})}]
           :isError false})))
    (catch Exception e
      {:content [{:type "text"
                  :text (str "Error: " (.getMessage e)
                             (when-let [data (ex-data e)]
                               (str "\nDetails: " (pr-str data))))}]
       :isError true})))

(defn next-story-task-tool
  "Tool to return the next incomplete task from a story's task list.

  Takes a story-name parameter and reads from .mcp-tasks/story-tasks/<story-name>-tasks.md.
  Returns a map with :task-text, :category, and :task-index, or nil values if no tasks remain."
  [config]
  {:name "next-story-task"
   :description "Return the next incomplete task from a story's task list.

  Reads story task file from `.mcp-tasks/story-tasks/<story-name>-tasks.md`,
  uses story-tasks parsing utilities to find first incomplete task,
  returns map with :task-text, :category, :task-index (or nil if none)."
   :inputSchema
   {:type "object"
    :properties
    {"story-name"
     {:type "string"
      :description "The story name (without -tasks.md suffix)"}}
    :required ["story-name"]}
   :implementation (partial next-story-task-impl config)})

(defn- complete-story-task-impl
  "Implementation of complete-story-task tool.

  Marks the first incomplete task in a story's task list as complete.
  Verifies task-text matches before completing.

  Returns:
  - Git mode enabled: Two text items (completion message + JSON with :modified-files)
  - Git mode disabled: Single text item (completion message only)"
  [config _context {:keys [story-name task-text completion-comment]}]
  (try
    (let [use-git? (:use-git? config)
          base-dir (:base-dir config ".")
          story-tasks-dir (str base-dir "/.mcp-tasks/story-tasks")
          story-tasks-file (str story-tasks-dir "/" story-name "-tasks.md")
          ;; Path relative to .mcp-tasks
          story-tasks-rel-path (str "story-tasks/" story-name "-tasks.md")]

      (when-not (.exists (io/file story-tasks-file))
        (throw (ex-info "Story tasks file not found"
                        {:story-name story-name
                         :file story-tasks-file})))

      (let [content (slurp story-tasks-file)
            tasks (story-tasks/parse-story-tasks content)
            first-incomplete (story-tasks/find-first-incomplete-task tasks)]

        (when-not first-incomplete
          (throw (ex-info "No incomplete tasks found in story"
                          {:story-name story-name
                           :file story-tasks-file})))

        ;; Verify task-text matches (case-insensitive, whitespace-normalized)
        ;; Strip checkbox prefix before comparing, like task-matches? does
        (let [normalize #(-> % str/lower-case (str/replace #"\s+" " ") str/trim)
              task-content (-> (:text first-incomplete)
                               (str/replace #"^- \[([ x])\] " "")
                               normalize)
              search-text (normalize task-text)]
          (when-not (str/starts-with? task-content search-text)
            (throw (ex-info "First incomplete task does not match provided text"
                            {:story-name story-name
                             :expected task-text
                             :actual (str/replace (:text first-incomplete) #"^- \[([ x])\] " "")}))))

        ;; Mark task as complete
        (let [updated-content (story-tasks/mark-task-complete
                                content
                                (:index first-incomplete)
                                completion-comment)]
          (spit story-tasks-file updated-content))

        (if use-git?
          ;; Git mode: return message + JSON with modified files
          {:content [{:type "text"
                      :text (str "Story task completed in " story-tasks-file)}
                     {:type "text"
                      :text (json/write-str {:modified-files [story-tasks-rel-path]})}]
           :isError false}
          ;; Non-git mode: return message only
          {:content [{:type "text"
                      :text (str "Story task completed in " story-tasks-file)}]
           :isError false})))
    (catch Exception e
      {:content [{:type "text"
                  :text (str "Error: " (.getMessage e)
                             (when-let [data (ex-data e)]
                               (str "\nDetails: " (pr-str data))))}]
       :isError true})))

(defn- complete-story-task-description
  "Generate description for complete-story-task tool based on config."
  [config]
  (if (:use-git? config)
    "Complete a task in a story's task list.

  Verifies the first incomplete task matches the provided text, marks it complete,
  and optionally adds a completion comment.
  
  Returns two text items:
  1. A completion status message
  2. A JSON-encoded map with :modified-files key containing file paths
     relative to .mcp-tasks for use in git commit workflows."
    "Complete a task in a story's task list.

  Verifies the first incomplete task matches the provided text, marks it complete,
  and optionally adds a completion comment.
  
  Returns a completion status message."))

(defn complete-story-task-tool
  "Tool to complete a task in a story's task list.
  
  Accepts config parameter containing :use-git? flag. When git mode is enabled,
  returns modified file paths for git commit workflow. When disabled, returns
  only completion message."
  [config]
  {:name "complete-story-task"
   :description (complete-story-task-description config)
   :inputSchema
   {:type "object"
    :properties
    {"story-name"
     {:type "string"
      :description "The story name (without -tasks.md suffix)"}
     "task-text"
     {:type "string"
      :description "Partial text from the beginning of the task to verify"}
     "completion-comment"
     {:type "string"
      :description "Optional comment to append to the completed task"}}
    :required ["story-name" "task-text"]}
   :implementation (partial complete-story-task-impl config)})
