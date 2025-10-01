(ns mcp-tasks.main
  "Stdio-based MCP server main entry point for task management"
  (:gen-class)
  (:require
    [mcp-clj.log :as log]
    [mcp-clj.mcp-server.core :as mcp-server]
    [mcp-clj.mcp-server.prompts :as prompts]))

;;; Task prompts

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

(def task-prompts
  "Task management prompts"
  {"next-simple" next-simple-prompt})

(defn start
  "Start stdio MCP server (uses stdin/stdout)"
  [_]
  (try
    (log/info :stdio-server {:msg "Starting MCP Tasks server"})
    (with-open [server (mcp-server/create-server
                         {:transport {:type :stdio}
                          :prompts (merge prompts/default-prompts task-prompts)})]
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
