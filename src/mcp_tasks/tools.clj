(ns mcp-tasks.tools
  "Facade providing access to all task management tools"
  (:require
    [mcp-tasks.tool.add-task :as add-task]
    [mcp-tasks.tool.complete-task :as complete-task]
    [mcp-tasks.tool.delete-task :as delete-task]
    [mcp-tasks.tool.select-tasks :as select-tasks]
    [mcp-tasks.tool.update-task :as update-task]))

(defn tools
  "Returns map of all MCP tools for task management.
  
  Parameters:
  - config: Configuration map containing :base-dir, :use-git?, etc.
  
  Returns a map of tool-name -> tool-definition for all available tools."
  [config]
  {"complete-task" (complete-task/complete-task-tool config)
   "delete-task" (delete-task/delete-task-tool config)
   "select-tasks" (select-tasks/select-tasks-tool config)
   "add-task" (add-task/add-task-tool config)
   "update-task" (update-task/update-task-tool config)})
