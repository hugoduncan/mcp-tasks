(ns mcp-tasks.main
  "Stdio-based MCP server main entry point for task management"
  (:gen-class)
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [mcp-clj.log :as log]
    [mcp-clj.mcp-server.core :as mcp-server]
    [mcp-tasks.config :as config]
    [mcp-tasks.prompts :as tp]
    [mcp-tasks.resources :as resources]
    [mcp-tasks.tools :as tools]))

(defn- get-prompt-vars
  "Get all prompt vars from the task-prompts and story-prompts namespaces.

  Returns a sequence of var objects representing prompt definitions."
  []
  (require 'mcp-tasks.task-prompts)
  (require 'mcp-tasks.story-prompts)
  (let [task-ns (find-ns 'mcp-tasks.task-prompts)
        story-ns (find-ns 'mcp-tasks.story-prompts)
        task-vars (->> (ns-publics task-ns)
                       vals
                       (filter (fn [v] (string? @v))))
        story-vars (->> (ns-publics story-ns)
                        vals
                        (filter (fn [v] (string? @v))))]
    (concat task-vars story-vars)))

(defn- list-prompts
  "List all available prompts with their names and descriptions.

  Outputs each prompt name with its docstring (first line only) to stdout."
  []
  (doseq [v (get-prompt-vars)]
    (let [prompt-name (name (symbol v))
          docstring (or (:doc (meta v)) "No description available")]
      (println (str prompt-name ": " docstring))))
  0)

(defn- install-prompt
  "Install a single prompt to .mcp-tasks/prompt/<name>.md.

  Returns 0 if successful, 1 if the prompt was not found or installation failed."
  [prompt-name]
  (let [prompt-vars (get-prompt-vars)
        prompt-var (first (filter #(= prompt-name (name (symbol %))) prompt-vars))]
    (if-not prompt-var
      (do
        (println (str "Warning: Prompt '" prompt-name "' not found"))
        1)
      (let [target-file (io/file ".mcp-tasks" "prompts" (str prompt-name ".md"))]
        (if (.exists target-file)
          (do
            (println (str "Skipping " prompt-name ": file already exists"))
            0)
          (try
            (.mkdirs (.getParentFile target-file))
            (spit target-file @prompt-var)
            (println (str "Installed " prompt-name))
            0
            (catch Exception e
              (println (str "Warning: Failed to install " prompt-name ": " (.getMessage e)))
              1)))))))

(defn- install-prompts
  "Install prompts to .mcp-tasks/prompt/ directory.

  If prompt-names is nil or empty, installs all prompts.
  Otherwise, installs only the specified prompts.

  Returns 0 if all installations succeeded, 1 if any failed or were not found."
  [prompt-names]
  (let [names-to-install (if (empty? prompt-names)
                           (map #(name (symbol %)) (get-prompt-vars))
                           prompt-names)
        results (map install-prompt names-to-install)
        exit-code (if (every? zero? results) 0 1)]
    exit-code))

(defn- load-and-validate-config
  "Loads, resolves, and validates configuration.

  Returns resolved config map with :use-git? set.
  Throws ex-info if validation fails."
  [config-path]
  (let [path (str config-path)
        raw-config (config/read-config path)
        resolved-config (config/resolve-config path (or raw-config {}))]
    (config/validate-startup path resolved-config)
    resolved-config))

(defn- block-forever
  "Blocks the current thread forever.

  Extracted for testability with with-redefs."
  []
  @(promise))

(defn create-server-config
  "Create MCP server configuration map.

  Parameters:
  - config: Resolved task configuration (from load-and-validate-config)
  - transport: Transport configuration map (e.g., {:type :stdio} or {:type :in-memory :shared ...})

  Returns server configuration map suitable for mcp-server/create-server"
  [config transport]
  (let [all-prompts (merge (tp/prompts config)
                           (tp/story-prompts config))]
    {:transport transport
     :tools {"complete-task" (tools/complete-task-tool config)
             "next-task" (tools/next-task-tool config)
             "add-task" (tools/add-task-tool config)
             "update-task" (tools/update-task-tool config)}
     :prompts all-prompts
     :resources (resources/prompt-resources all-prompts)}))

(defn- exit-process
  "Exit the process with the given code.

  Extracted for testability with with-redefs."
  [code]
  (System/exit code))

(defn start
  "Start stdio MCP server (uses stdin/stdout).

  Options:
  - :config-path - Path to directory containing .mcp-tasks.edn (default: '.')
                   Can be a string or symbol (for clj -X compatibility)"
  [{:keys [config-path] :or {config-path "."}}]
  (try
    (let [config (load-and-validate-config config-path)
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
        (println "Error starting server:" (.getMessage e)))
      (exit-process 1))))

(defn- index-of
  "Returns the index of the first occurrence of value in coll.
  Returns nil if not found."
  [coll value]
  (first (keep-indexed #(when (= %2 value) %1) coll)))

(defn -main
  "CLI entry point for MCP Tasks.

  Supports:
  - --list-prompts: List available prompt names and descriptions
  - --install-prompts [names]: Install prompts (comma-separated or all if omitted)
  - --config-path <path>: Path to directory containing .mcp-tasks.edn (default: '.')
  - No args: Start the MCP server"
  [& args]
  (let [args-vec (vec args)
        config-path-idx (index-of args-vec "--config-path")
        config-path (when (>= config-path-idx 0)
                      (when (< (inc config-path-idx) (count args-vec))
                        (nth args-vec (inc config-path-idx))))]
    (cond
      ;; --list-prompts flag
      (some #{"--list-prompts"} args)
      (System/exit (list-prompts))

      ;; --install-prompts flag
      (some #{"--install-prompts"} args)
      (let [idx (index-of args-vec "--install-prompts")
            next-arg (when (< (inc idx) (count args-vec))
                       (nth args-vec (inc idx)))
            prompt-names (if (and next-arg (not (str/starts-with? next-arg "--")))
                           (str/split next-arg #",")
                           [])]
        (System/exit (install-prompts prompt-names)))

      ;; Default: start server
      :else
      (start (if config-path
               {:config-path config-path}
               {})))))
