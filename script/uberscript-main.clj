;; Explicitly require all tool namespaces so bb uberscript includes them
(require 'mcp-tasks.tool.select-tasks)
(require 'mcp-tasks.tool.add-task)
(require 'mcp-tasks.tool.complete-task)
(require 'mcp-tasks.tool.update-task)
(require 'mcp-tasks.tool.delete-task)
(require 'mcp-tasks.cli)
(apply mcp-tasks.cli/-main *command-line-args*)
