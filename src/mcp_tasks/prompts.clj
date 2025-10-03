(ns mcp-tasks.prompts
  "Task management prompts"
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [mcp-clj.mcp-server.prompts :as prompts]))

(def next-simple-prompt
  "Prompt for processing the next simple task"
  (prompts/valid-prompt?
    {:name "next-simple"
     :description "Process the next incomplete task from .mcp-tasks/tasks/simple.md"
     :messages [{:role "user"
                 :content {:type "text"
                           :text "Please complete the next simple task following these steps:

1. Read the file .mcp-tasks/tasks/simple.md
2. Find the first incomplete task (marked with `- [ ]`)
3. Show the task description
4. Analyze the task specification in the context of the project
5. Plan an implementation approach
6. Implement the solution
7. Create a git commit with the code changes in the main repository
8. Move the completed task to .mcp-tasks/complete/simple.md (append to end, mark as complete with `- [x]`)
9. Remove the task from .mcp-tasks/tasks/simple.md
10. Commit the task tracking changes in the .mcp-tasks git repository
"}}]}))

(defn discover-categories
  "Discover task categories by reading filenames from .mcp-tasks subdirectories.

  Returns a sorted vector of unique category names (filenames without .md extension)
  found across the tasks, complete, and prompts subdirectories."
  []
  (let [base-dir (io/file ".mcp-tasks")
        subdirs ["tasks" "complete" "prompts"]
        md-files (for [subdir subdirs
                       :let [dir (io/file base-dir subdir)]
                       :when (.exists dir)
                       file (.listFiles dir)
                       :when (and (.isFile file)
                                  (str/ends-with? (.getName file) ".md"))]
                   (.getName file))
        categories (into (sorted-set)
                         (map #(str/replace % #"\.md$" ""))
                         md-files)]
    (vec categories)))

(defn- read-task-prompt-text
  "Generate prompt text for reading the next task from a category."
  [category]
  (format "- Read the file .mcp-tasks/tasks/%s.md
- Find the first incomplete task (marked with `- [ ]`)
- Show the task description
"
          category))

(defn- default-prompt-text
  "Generate default execution instructions for a category."
  []
  "- Analyze the task specification in the context of the project
- Plan an implementation approach
- Implement the solution
- Create a git commit with the code changes in the main repository

Available tools:
- Use the `next-task` tool to retrieve the next task without executing it
- Use the `complete-task` tool to mark a task as complete and move it to the completed archive
- Use the `add-task` tool to add new tasks to a category
")

(defn- complete-task-prompt-text
  "Generate prompt text for completing and tracking a task."
  [category]
  (format "- Move the completed task to .mcp-tasks/complete/%s.md (append to end, mark as complete with `- [x]`)
- Remove the task from .mcp-tasks/tasks/%s.md (if removing the last task, leave the file empty rather than deleting it)
- Commit the task tracking changes in the .mcp-tasks git repository
"
          category category))

(defn- read-prompt-instructions
  "Read custom prompt instructions from .mcp-tasks/prompts/<category>.md if it exists.

  Returns the file content or nil if the file doesn't exist."
  [category]
  (let [prompt-file (io/file ".mcp-tasks" "prompts" (str category ".md"))]
    (when (.exists prompt-file)
      (slurp prompt-file))))

(defn create-prompts
  "Create MCP prompts for a sequence of categories.

  For each category, creates a prompt that:
  - Reads tasks from .mcp-tasks/tasks/<category>.md
  - Uses instructions from .mcp-tasks/prompts/<category>.md if available,
    otherwise uses default instructions based on next-simple
  - Moves completed tasks to .mcp-tasks/complete/<category>.md
  - Commits changes to the .mcp-tasks repository

  The prompt text is automatically composed from three parts:
  1. read-task-prompt-text - instructions for reading the next task
  2. custom instructions or default-prompt-text - category-specific execution logic
  3. complete-task-prompt-text - instructions for committing and tracking completion

  Returns a vector of prompt maps suitable for registration with the MCP server."
  [categories]
  (vec
    (for [category categories]
      (let [custom-instructions (read-prompt-instructions category)
            execution-instructions (or custom-instructions (default-prompt-text))
            prompt-text (str "Please complete the next " category " task following these steps:\n\n"
                             (read-task-prompt-text category)
                             execution-instructions
                             (complete-task-prompt-text category))]
        (prompts/valid-prompt?
          {:name (str "next-" category)
           :description (format "Process the next incomplete task from .mcp-tasks/tasks/%s.md" category)
           :messages [{:role "user"
                       :content {:type "text"
                                 :text prompt-text}}]})))))

(defn prompts
  "Generate all task prompts by discovering categories and creating prompts for them.
  
  Returns a map of prompt names to prompt definitions, suitable for registering
  with the MCP server."
  []
  (let [categories (discover-categories)
        prompt-list (create-prompts categories)]
    (into {} (map (fn [p] [(:name p) p]) prompt-list))))
