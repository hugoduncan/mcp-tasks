(ns mcp-tasks.native-init
  "Entry point for GraalVM native-image CLI builds.

  Explicitly requires all tool namespaces so native-image includes them.
  This is necessary because native-image cannot dynamically load namespaces
  at runtime via requiring-resolve.

  Similar approach to script/uberscript-main.clj for Babashka uberscript builds."
  (:gen-class))

;; Explicitly require Malli and all tool namespaces so native-image includes them
(require 'malli.core)
(require 'mcp-tasks.tool.select-tasks)
(require 'mcp-tasks.tool.add-task)
(require 'mcp-tasks.tool.complete-task)
(require 'mcp-tasks.tool.update-task)
(require 'mcp-tasks.tool.delete-task)
(require 'mcp-tasks.tool.reopen-task)
(require 'mcp-tasks.cli)

(defn -main
  "Main entry point for native CLI binary.
  Delegates to mcp-tasks.cli/-main after ensuring all tool namespaces are loaded."
  [& args]
  (apply mcp-tasks.cli/-main args))
