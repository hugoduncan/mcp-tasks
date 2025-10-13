(ns mcp-tasks.tools
  "Task management tools"
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [mcp-tasks.path-helper :as path-helper]
    [mcp-tasks.prompts :as prompts]
    [mcp-tasks.response :as response]))

(defn- file-exists?
  "Check if a file exists"
  [file-path]
  (.exists (io/file file-path)))

(defn- read-task-file
  "Read task file and return content as string.
  Returns an empty string if file doesn't exist"
  [file-path]
  (if (file-exists? file-path)
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
          tasks-path (path-helper/task-path config ["tasks" (str category ".md")])
          complete-path (path-helper/task-path config ["complete" (str category ".md")])
          tasks-file (:absolute tasks-path)
          complete-file (:absolute complete-path)
          tasks-content (read-task-file tasks-file)
          ;; Paths relative to .mcp-tasks
          tasks-rel-path (:relative tasks-path)
          complete-rel-path (:relative complete-path)]

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
      (response/error-response e))))

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
  [config _context {:keys [category]}]
  (try
    (let [tasks-path (path-helper/task-path config ["tasks" (str category ".md")])
          tasks-file (:absolute tasks-path)
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
      (response/error-response e))))

(defn next-task-tool
  "Tool to return the next task from a specific category.

  Accepts config parameter for future git-aware functionality."
  [config]
  {:name "next-task"
   :description "Return the next task from tasks/<category>.md"
   :inputSchema
   {:type "object"
    :properties
    {"category"
     {:type "string"
      :description "The task category name"}}
    :required ["category"]}
   :implementation (partial next-task-impl config)})

(defn- prepare-story-task-file
  "Prepare story task file for adding a task.

  Validates story exists and returns [file-path content] tuple.
  Initializes file with header if empty."
  [config story-name]
  (let [story-path (path-helper/task-path config ["story" "stories" (str story-name ".md")])
        story-file (:absolute story-path)]
    (when-not (file-exists? story-file)
      (throw (ex-info "Story does not exist"
                      {:story-name story-name
                       :expected-file story-file})))
    (let [story-tasks-path (path-helper/task-path config ["story" "story-tasks" (str story-name "-tasks.md")])
          story-tasks-file (:absolute story-tasks-path)
          content (read-task-file story-tasks-file)
          content (if (str/blank? content)
                    (str "# Tasks for " story-name " Story\n")
                    content)]
      [story-tasks-file content])))

(defn- prepare-category-task-file
  "Prepare category task file for adding a task.

  Returns [file-path content] tuple."
  [config category]
  (let [tasks-path (path-helper/task-path config ["tasks" (str category ".md")])
        tasks-file (:absolute tasks-path)]
    [tasks-file (read-task-file tasks-file)]))

(defn- format-task-content
  "Format task content for adding to a task file.

  Returns the new file content with the task added."
  [task-text category tasks-content prepend story-name]
  (let [new-task (if story-name
                   (str "- [ ] " task-text "\nCATEGORY: " category)
                   (str "- [ ] " task-text))
        separator (if story-name "\n\n" "\n")
        header-only? (and story-name
                          (= tasks-content (str "# Tasks for " story-name " Story\n")))]
    (cond
      (or (str/blank? tasks-content) header-only?)
      (if header-only?
        (str tasks-content new-task)
        new-task)

      prepend
      (str new-task separator tasks-content)

      :else
      (str tasks-content separator new-task))))

(defn add-task-impl
  "Implementation of add-task tool.

  Adds a task to tasks/<category>.md as an incomplete todo item.
  If prepend is true, adds at the beginning; otherwise appends at the end.
  If story-name is provided, the task is associated with that story and
  includes CATEGORY metadata. Creates the story-tasks file if it doesn't exist."
  [config _context {:keys [category task-text prepend story-name]}]
  (try
    (let [[tasks-file tasks-content] (if story-name
                                       (prepare-story-task-file config story-name)
                                       (prepare-category-task-file config category))
          new-content (format-task-content task-text category tasks-content prepend story-name)]
      (write-task-file tasks-file new-content)
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
      (str "Add a task to tasks/<category>.md\n\nAvailable categories:\n"
           (str/join "\n"
                     (for [cat categories]
                       (format "- %s: %s" cat (get category-descs cat)))))
      "Add a task to tasks/<category>.md")))

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
     "story-name"
     {:type "string"
      :description "Optional story name to associate this task with"}
     "prepend"
     {:type "boolean"
      :description "If true, add task at the beginning instead of the end"}}
    :required ["category" "task-text"]}
   :implementation (partial add-task-impl config)})
