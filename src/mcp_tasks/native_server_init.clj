(ns mcp-tasks.native-server-init
  "Entry point for GraalVM native-image server builds.

  Explicitly requires all tool namespaces so native-image includes them.
  This is necessary because native-image cannot dynamically load namespaces
  at runtime via requiring-resolve.

  Similar approach to native-init.clj for native CLI builds."
  (:gen-class))

;; Explicitly require Malli and all tool namespaces so native-image includes them
(require 'malli.core)
(require 'mcp-tasks.tool.select-tasks)
(require 'mcp-tasks.tool.add-task)
(require 'mcp-tasks.tool.complete-task)
(require 'mcp-tasks.tool.update-task)
(require 'mcp-tasks.tool.delete-task)
(require 'mcp-tasks.tool.reopen-task)
(require 'mcp-tasks.task-prompts)
(require 'mcp-tasks.story-prompts)
(require 'mcp-tasks.main)

(defn -main
  "Main entry point for native server binary.
  Delegates to mcp-tasks.main/-main after ensuring all tool namespaces are loaded."
  [& args]
  (apply mcp-tasks.main/-main args))
