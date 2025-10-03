(ns mcp-tasks.tools
  "Task management tools"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

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
  (let [lines        (str/split-lines content)
        task-pattern #"^- \[([ x])\] (.*)"]
    (loop [lines        lines
           current-task []
           tasks        []]
      (if (empty? lines)
        (if (seq current-task)
          (conj tasks (str/join "\n" current-task))
          tasks)
        (let [line       (first lines)
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

(defn complete-task-impl
  "Implementation of complete-task tool.

  Moves first task from tasks/<category>.md to complete/<category>.md,
  verifying it matches the provided task-text and optionally adding a
  completion comment."
  [{:keys [category task-text completion-comment]}]
  (try
    (let [tasks-dir     ".mcp-tasks/tasks"
          complete-dir  ".mcp-tasks/complete"
          tasks-file    (str tasks-dir "/" category ".md")
          complete-file (str complete-dir "/" category ".md")
          tasks-content (read-task-file tasks-file)]

      (when (str/blank? tasks-content)
        (throw (ex-info "No tasks found in category"
                        {:category category
                         :file     tasks-file})))

      (let [tasks (parse-tasks tasks-content)]
        (when (empty? tasks)
          (throw (ex-info "No tasks found in category"
                          {:category category
                           :file     tasks-file})))

        (let [first-task (first tasks)]
          (when-not (task-matches? first-task task-text)
            (throw (ex-info "First task does not match provided text"
                            {:category category
                             :expected task-text
                             :actual   first-task})))

          ;; Mark task as complete and append to complete file
          (let [completed-task       (mark-complete
                                      first-task
                                      completion-comment)
                complete-content     (read-task-file complete-file)
                new-complete-content (if (str/blank? complete-content)
                                       completed-task
                                       (str complete-content
                                            "\n"
                                            completed-task))]
            (write-task-file complete-file new-complete-content))

          ;; Remove first task from tasks file
          (let [remaining-tasks   (rest tasks)
                new-tasks-content (str/join "\n" remaining-tasks)]
            (write-task-file tasks-file new-tasks-content))

          {:content [{:type "text"
                      :text (str "Task completed and moved to " complete-file)}]
           :isError false})))
    (catch Exception e
      {:content [{:type "text"
                  :text (str "Error: " (.getMessage e)
                             (when-let [data (ex-data e)]
                               (str "\nDetails: " (pr-str data))))}]
       :isError true})))

(def ^:private description
  "Complete a task by moving it from
   .mcp-tasks/tasks/<category>.md to .mcp-tasks/complete/<category>.md.

   Verifies the first task matches the provided text, marks it complete, and
   optionally adds a completion comment.")

(def complete-task-tool
  "Tool to complete a task and move it from tasks to complete directory"
  {:name           "complete-task"
   :description    description
   :inputSchema
   {:type     "object"
    :properties
    {"category"
     {:type        "string"
      :description "The task category name"}
     "task-text"
     {:type        "string"
      :description "Partial text from the beginning of the task to verify"}
     "completion-comment"
     {:type        "string"
      :description "Optional comment to append to the completed task"}}
    :required ["category" "task-text"]}
   :implementation complete-task-impl})
