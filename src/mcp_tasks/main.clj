(ns mcp-tasks.main
  "Stdio-based MCP server main entry point for task management"
  (:gen-class)
  (:require
    [mcp-clj.log :as log]
    [mcp-clj.mcp-server.core :as mcp-server]
    [mcp-tasks.prompts :as tp]
    [mcp-tasks.tools :as tools]))

(defn start
  "Start stdio MCP server (uses stdin/stdout)"
  [_]
  (try
    (log/info :stdio-server {:msg "Starting MCP Tasks server"})
    (with-open [server (mcp-server/create-server
                         {:transport {:type :stdio}
                          :tools {"complete-task" tools/complete-task-tool
                                  "next-task" tools/next-task-tool
                                  "add-task" tools/add-task-tool}
                          :prompts (tp/prompts)})]
      (log/info :stdio-server {:msg "MCP Tasks server started"})
      (.addShutdownHook
        (Runtime/getRuntime)
        (Thread. #(do
                    (log/info :shutting-down-stdio-server)
                    ((:stop server)))))
      ;; Keep the main thread alive
      @(promise))
    (catch Exception e
      (log/error :stdio-server {:error (.getMessage e)})
      (System/exit 1))))

(defn -main
  "Start stdio MCP server (uses stdin/stdout)"
  [& _args]
  (start {}))
