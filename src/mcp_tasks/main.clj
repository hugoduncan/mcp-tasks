(ns mcp-tasks.main
  "Stdio-based MCP server main entry point for task management"
  (:gen-class)
  (:require
    [mcp-clj.log :as log]
    [mcp-clj.mcp-server.core :as mcp-server]
    [mcp-tasks.config :as config]
    [mcp-tasks.prompts :as tp]
    [mcp-tasks.resources :as resources]
    [mcp-tasks.tools :as tools]))

(defn- load-and-validate-config
  "Loads, resolves, and validates configuration.

  Searches for .mcp-tasks.edn starting from start-dir (or CWD) and traversing up.
  Returns resolved config map with :use-git? and :base-dir set.
  Throws ex-info if validation fails.

  Parameters:
  - start-dir (optional): Directory to start search from. Defaults to CWD."
  ([]
   (load-and-validate-config nil))
  ([start-dir]
   (let [{:keys [raw-config config-dir start-dir]} (if start-dir
                                                     (config/read-config start-dir)
                                                     (config/read-config))
         resolved-config (config/resolve-config config-dir raw-config start-dir)]
     (config/validate-startup config-dir resolved-config)
     resolved-config)))

(defn- block-forever
  "Blocks the current thread forever.

  Extracted for testability with with-redefs."
  []
  @(promise))

(defn create-server-config
  "Create MCP server configuration map.

  Parameters:
  - config: Resolved task configuration (from load-and-validate-config)
  - transport: Transport configuration map (e.g., {:type :stdio} or
    {:type :in-memory :shared ...})

  Returns server configuration map suitable for mcp-server/create-server"
  [config transport]
  (let [all-prompts (merge (tp/category-prompts config)
                           (tp/story-prompts config))
        ;; Use both filesystem and embedded resources
        ;; Filesystem resources override embedded (for user customizations)
        embedded-resources (tp/builtin-category-prompt-resources config)
        fs-resources (tp/category-prompt-resources config)
        category-resources-vec (concat embedded-resources fs-resources)
        base-dir (:base-dir config)
        all-resources (merge
                        (resources/prompt-resources all-prompts)
                        (resources/category-prompt-resources
                          category-resources-vec)
                        (resources/current-execution-resource base-dir)
                        (resources/available-categories-resource config))]
    {:transport transport
     :tools (tools/tools config)
     :prompts all-prompts
     :resources all-resources
     :server-info {:name "mcp-tasks"
                   :version "0.1.124"
                   :title "MCP Tasks Server"}}))

(defn- exit-process
  "Exit the process with the given code.

  Extracted for testability with with-redefs."
  [code]
  (System/exit code))

(defn start
  "Start stdio MCP server (uses stdin/stdout).

  Searches for .mcp-tasks.edn starting from current working directory,
  traversing up the directory tree until found or reaching filesystem root."
  [{:as _args}]
  (try
    (let [config        (load-and-validate-config)
          server-config (create-server-config config {:type :stdio})]
      (log/info :stdio-server {:msg "Starting MCP Tasks server"})
      (with-open [server (mcp-server/create-server server-config)]
        (log/info :stdio-server {:msg "MCP Tasks server started"})
        (.addShutdownHook
          (Runtime/getRuntime)
          (Thread. #(do
                      (log/info :shutting-down-stdio-server)
                      ((:stop server)))))
        (block-forever)))
    (catch Exception e
      (binding [*out* *err*]
        (println "Error starting server:" (.getMessage e))
        (.printStackTrace e))
      (exit-process 1))))

(defn -main
  "CLI entry point for MCP Tasks.

  Starts the MCP server. The server automatically searches for .mcp-tasks.edn
  starting from the current working directory and traversing up the directory tree."
  [& _args]
  (start {}))
