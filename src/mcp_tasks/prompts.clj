(ns mcp-tasks.prompts
  "Task management prompts"
  (:require
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
7. Move the completed task to .mcp-tasks/complete/simple.md (append to end, mark as complete with `- [x]`)
8. Remove the task from .mcp-tasks/tasks/simple.md
9. Create a git commit with the changes"}}]}))
