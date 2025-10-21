(ns mcp-tasks.tools
  "Facade providing access to all task management tools.

  ## Architecture

  This namespace acts as a facade over individual tool implementations,
  each located in mcp-tasks.tool.<tool-name> namespaces. The facade pattern
  was introduced to improve maintainability as the original tools.clj grew
  to over 1,100 lines.

  ## Organization

  - Tool implementations: mcp-tasks.tool.* (e.g., mcp-tasks.tool.complete-task)
  - Shared helpers: mcp-tasks.tools.helpers
  - Shared validation: mcp-tasks.tools.validation
  - Tests: test/mcp_tasks/tool/*_test.clj (one per tool)

  ## Purpose

  The facade centralizes tool registration, making it easy to:
  - Add new tools by creating a new mcp-tasks.tool.* namespace
  - See all available tools in one place
  - Maintain consistent tool configuration patterns
  - Keep individual tool implementations focused and testable

  ## For Future Maintainers

  When adding a new tool:
  1. Create src/mcp_tasks/tool/<tool_name>.clj with <tool-name>-tool function
  2. Add require and entry to the tools map in this file
  3. Create test/mcp_tasks/tool/<tool_name>_test.clj
  4. Use helpers from mcp-tasks.tools.helpers for common operations"
  (:require
    [mcp-tasks.tool.add-task :as add-task]
    [mcp-tasks.tool.complete-task :as complete-task]
    [mcp-tasks.tool.delete-task :as delete-task]
    [mcp-tasks.tool.execution-state :as execution-state]
    [mcp-tasks.tool.select-tasks :as select-tasks]
    [mcp-tasks.tool.update-task :as update-task]
    [mcp-tasks.tool.work-on :as work-on]))

(defn tools
  "Returns map of all MCP tools for task management.
  
  Parameters:
  - config: Configuration map containing :base-dir, :use-git?, etc.
  
  Returns a map of tool-name -> tool-definition for all available tools."
  [config]
  {"complete-task" (complete-task/complete-task-tool config)
   "delete-task" (delete-task/delete-task-tool config)
   "execution-state" (execution-state/execution-state-tool config)
   "select-tasks" (select-tasks/select-tasks-tool config)
   "add-task" (add-task/add-task-tool config)
   "update-task" (update-task/update-task-tool config)
   "work-on" (work-on/work-on-tool config)})
