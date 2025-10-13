(ns mcp-tasks.story-tools
  "Story management tools"
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [mcp-tasks.path-helper :as path-helper]
    [mcp-tasks.response :as response]
    [mcp-tasks.story-tasks :as story-tasks]))

(defn- next-story-task-impl
  "Implementation of next-story-task tool.

  Reads story task file from .mcp-tasks/story-tasks/<story-name>-tasks.md,
  parses it, and returns the first incomplete task with its metadata.

  Returns a map with :task-text, :category, and :task-index keys,
  or nil values if no incomplete task is found."
  [config _context {:keys [story-name]}]
  (try
    (let [story-tasks-path (path-helper/task-path config ["story" "story-tasks" (str story-name "-tasks.md")])
          story-tasks-file (:absolute story-tasks-path)]

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
      (response/error-response e))))

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
          story-tasks-path (path-helper/task-path config ["story" "story-tasks" (str story-name "-tasks.md")])
          story-tasks-file (:absolute story-tasks-path)
          story-tasks-rel-path (:relative story-tasks-path)]

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
      (response/error-response e))))

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

(defn- complete-story-impl
  "Implementation of complete-story tool.

  Marks a story as complete by moving it to the archive along with its task list.

  Process:
  1. Reads story file from .mcp-tasks/story/stories/<story-name>.md
  2. Optionally appends completion comment
  3. Moves story file to .mcp-tasks/story/complete/<story-name>.md
  4. Moves tasks file from .mcp-tasks/story/story-tasks/<story-name>-tasks.md
     to .mcp-tasks/story/story-tasks-complete/<story-name>-tasks.md

  Returns:
  - Git mode enabled: Two text items (completion message + JSON with :modified-files)
  - Git mode disabled: Single text item (completion message only)"
  [config _context {:keys [story-name completion-comment]}]
  (try
    (let [use-git? (:use-git? config)
          ;; Story file paths
          story-path-map (path-helper/task-path config ["story" "stories" (str story-name ".md")])
          complete-story-path-map (path-helper/task-path config ["story" "complete" (str story-name ".md")])
          story-file (:absolute story-path-map)
          complete-story-file (:absolute complete-story-path-map)
          story-rel-path (:relative story-path-map)
          complete-story-rel-path (:relative complete-story-path-map)

          ;; Task file paths
          tasks-path-map (path-helper/task-path config ["story" "story-tasks" (str story-name "-tasks.md")])
          complete-tasks-path-map (path-helper/task-path config ["story" "story-tasks-complete" (str story-name "-tasks.md")])
          tasks-file (:absolute tasks-path-map)
          complete-tasks-file (:absolute complete-tasks-path-map)
          tasks-rel-path (:relative tasks-path-map)
          complete-tasks-rel-path (:relative complete-tasks-path-map)

          ;; Directory paths for mkdirs
          complete-dir (:absolute (path-helper/task-path config ["story" "complete"]))
          story-tasks-complete-dir (:absolute (path-helper/task-path config ["story" "story-tasks-complete"]))]

      ;; Check if story file exists
      (when-not (.exists (io/file story-file))
        (throw (ex-info "Story file not found"
                        {:story-name story-name
                         :file story-file})))

      ;; Check if already completed
      (when (.exists (io/file complete-story-file))
        (throw (ex-info "Story is already completed"
                        {:story-name story-name
                         :file complete-story-file})))

      ;; Read and optionally update story content
      (let [story-content (slurp story-file)
            updated-content (if (and completion-comment
                                     (not (str/blank? completion-comment)))
                              (str story-content "\n\n---\n\n"
                                   (str/trim completion-comment))
                              story-content)]

        ;; Ensure complete directory exists
        (.mkdirs (io/file complete-dir))

        ;; Move story file to complete
        (spit complete-story-file updated-content)
        (.delete (io/file story-file))

        ;; Move tasks file if it exists
        (let [tasks-file-exists? (.exists (io/file tasks-file))
              modified-files (if tasks-file-exists?
                               (do
                                 (.mkdirs (io/file story-tasks-complete-dir))
                                 (let [tasks-content (slurp tasks-file)]
                                   (spit complete-tasks-file tasks-content)
                                   (.delete (io/file tasks-file)))
                                 [story-rel-path complete-story-rel-path
                                  tasks-rel-path complete-tasks-rel-path])
                               [story-rel-path complete-story-rel-path])]

          (if use-git?
            ;; Git mode: return message + JSON with modified files
            {:content [{:type "text"
                        :text (str "Story '" story-name "' marked as complete"
                                   (when-not tasks-file-exists?
                                     "\n(Note: No tasks file found to archive)"))}
                       {:type "text"
                        :text (json/write-str {:modified-files modified-files})}]
             :isError false}
            ;; Non-git mode: return message only
            {:content [{:type "text"
                        :text (str "Story '" story-name "' marked as complete"
                                   (when-not tasks-file-exists?
                                     "\n(Note: No tasks file found to archive)"))}]
             :isError false}))))
    (catch Exception e
      (response/error-response e))))

(defn- complete-story-description
  "Generate description for complete-story tool based on config."
  [config]
  (if (:use-git? config)
    "Mark a story as complete and move it to the archive.

  Moves the story file from .mcp-tasks/story/stories/<story-name>.md to
  .mcp-tasks/story/complete/<story-name>.md and the tasks file from
  .mcp-tasks/story/story-tasks/<story-name>-tasks.md to
  .mcp-tasks/story/story-tasks-complete/<story-name>-tasks.md.

  Optionally adds a completion comment to the story.

  Returns two text items:
  1. A completion status message
  2. A JSON-encoded map with :modified-files key containing file paths
     relative to .mcp-tasks for use in git commit workflows."
    "Mark a story as complete and move it to the archive.

  Moves the story file from .mcp-tasks/story/stories/<story-name>.md to
  .mcp-tasks/story/complete/<story-name>.md and the tasks file from
  .mcp-tasks/story/story-tasks/<story-name>-tasks.md to
  .mcp-tasks/story/story-tasks-complete/<story-name>-tasks.md.

  Optionally adds a completion comment to the story.

  Returns a completion status message."))

(defn complete-story-tool
  "Tool to complete a story and move it to the archive.

  Accepts config parameter containing :use-git? flag. When git mode is enabled,
  returns modified file paths for git commit workflow. When disabled, returns
  only completion message."
  [config]
  {:name "complete-story"
   :description (complete-story-description config)
   :inputSchema
   {:type "object"
    :properties
    {"story-name"
     {:type "string"
      :description "The story name (without .md extension)"}
     "completion-comment"
     {:type "string"
      :description "Optional comment to append to the story"}}
    :required ["story-name"]}
   :implementation (partial complete-story-impl config)})
