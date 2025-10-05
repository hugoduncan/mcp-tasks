(ns mcp-tasks.main
  "Stdio-based MCP server main entry point for task management"
  (:gen-class)
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [mcp-clj.log :as log]
   [mcp-clj.mcp-server.core :as mcp-server]
   [mcp-tasks.prompts :as tp]
   [mcp-tasks.tools :as tools]))

(defn- get-prompt-vars
  "Get all prompt vars from the task-prompts namespace.

  Returns a sequence of var objects representing prompt definitions."
  []
  (require 'mcp-tasks.task-prompts)
  (let [ns (find-ns 'mcp-tasks.task-prompts)]
    (->> (ns-publics ns)
         vals
         (filter (fn [v] (string? @v))))))

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
  "CLI entry point for MCP Tasks.

  Supports:
  - --list-prompts: List available prompt names and descriptions
  - --install-prompts [names]: Install prompts (comma-separated or all if omitted)
  - No args: Start the MCP server"
  [& args]
  (cond
    ;; --list-prompts flag
    (some #{"--list-prompts"} args)
    (System/exit (list-prompts))

    ;; --install-prompts flag
    (some #{"--install-prompts"} args)
    (let [idx (.indexOf (vec args) "--install-prompts")
          next-arg (when (< (inc idx) (count args))
                     (nth args (inc idx)))
          prompt-names (if (and next-arg (not (str/starts-with? next-arg "--")))
                         (str/split next-arg #",")
                         [])]
      (System/exit (install-prompts prompt-names)))

    ;; Default: start server
    :else
    (start {})))
