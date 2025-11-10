(ns mcp-tasks.main
  "Stdio-based MCP server main entry point for task management"
  (:gen-class)
  (:require
    [babashka.fs :as fs]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [mcp-clj.log :as log]
    [mcp-clj.mcp-server.core :as mcp-server]
    [mcp-tasks.config :as config]
    [mcp-tasks.prompts :as tp]
    [mcp-tasks.resources :as resources]
    [mcp-tasks.tools :as tools])
  (:import
    (java.io
      File)))

(defn- get-prompt-vars
  "Get all prompt vars and files from namespaces and resources.

  Returns a sequence of maps with :name and :content keys.
  Discovers prompts from:
  - mcp-tasks.task-prompts namespace vars (category prompts)
  - mcp-tasks.story-prompts namespace vars (story prompts)  
  - resources/prompts/*.md files (task execution prompts)"
  []
  (require 'mcp-tasks.task-prompts)
  (require 'mcp-tasks.story-prompts)
  (let [;; Get vars from namespaces
        task-ns (find-ns 'mcp-tasks.task-prompts)
        story-ns (find-ns 'mcp-tasks.story-prompts)
        task-vars (->> (ns-publics task-ns)
                       vals
                       (filter (fn [v] (string? @v))))
        story-vars (->> (ns-publics story-ns)
                        vals
                        (filter (fn [v] (string? @v))))
        var-prompts (concat task-vars story-vars)

        ;; Discover prompts from resources/prompts directory
        prompts-url (io/resource "prompts")
        file-prompts (when prompts-url
                       (let [prompts-dir (io/file (.toURI prompts-url))
                             prompt-files (->> (file-seq prompts-dir)
                                               (filter #(and (.isFile ^File %)
                                                             (str/ends-with? (.getName ^File %) ".md"))))]
                         (for [file prompt-files]
                           (let [prompt-name (str/replace (.getName ^File file) #"\.md$" "")
                                 content (slurp file)]
                             {:name prompt-name
                              :content content
                              :var (reify clojure.lang.IDeref
                                     (deref [_] content))
                              :meta {:doc (str "Task execution prompt: " prompt-name)}}))))]

    ;; Convert vars to uniform format
    (concat
      (map (fn [v]
             {:name (name (symbol v))
              :content @v
              :var v
              :meta (meta v)})
           var-prompts)
      (or file-prompts []))))

(defn- list-prompts
  "List all available prompts with their names and descriptions.

  Outputs each prompt name with its docstring (first line only) to stdout."
  []
  (doseq [prompt-map (get-prompt-vars)]
    (let [prompt-name (:name prompt-map)
          docstring (or (:doc (:meta prompt-map)) "No description available")]
      (println (str prompt-name ": " docstring))))
  0)

(defn- list-builtin-categories
  "List all built-in category prompt names.

  Returns a set of category names (strings) found in resources/category-prompts/."
  []
  (let [resource-dir "category-prompts"]
    (->> (io/resource resource-dir)
         io/file
         file-seq
         (filter #(and (.isFile ^File %)
                       (str/ends-with? (.getName ^File %) ".md")))
         (map #(str/replace (.getName ^File %) #"\.md$" ""))
         set)))

(defn- install-prompt
  "Install a single prompt to appropriate directory based on type.

  Auto-detects whether the prompt is a category or workflow prompt:
  - Categories go to .mcp-tasks/category-prompts/<name>.md
  - Workflows go to .mcp-tasks/prompt-overrides/<name>.md

  Returns 0 if successful, 1 if the prompt was not found or installation failed."
  [prompt-name]
  (let [prompt-maps (get-prompt-vars)
        prompt-map (first (filter #(= prompt-name (:name %)) prompt-maps))
        builtin-categories (list-builtin-categories)
        is-category? (contains? builtin-categories prompt-name)
        target-dir (if is-category?
                     ".mcp-tasks/category-prompts"
                     ".mcp-tasks/prompt-overrides")
        target-file (str target-dir "/" prompt-name ".md")]
    (if-not prompt-map
      (do
        (println (str "Warning: Prompt '" prompt-name "' not found"))
        1)
      (if (fs/exists? target-file)
        (do
          (println (str "Skipping " prompt-name ": file already exists at " target-file))
          0)
        (try
          (when-let [parent (fs/parent target-file)]
            (fs/create-dirs parent))
          (spit target-file (:content prompt-map))
          (println (str "Installed " prompt-name " to " target-file))
          0
          (catch Exception e
            (println (str "Warning: Failed to install " prompt-name ": " (.getMessage e)))
            1))))))

(defn- install-prompts
  "Install prompts to appropriate directories.

  Categories are installed to .mcp-tasks/category-prompts/
  Workflow prompts are installed to .mcp-tasks/prompt-overrides/

  If prompt-names is nil or empty, installs all prompts.
  Otherwise, installs only the specified prompts.

  Returns 0 if all installations succeeded, 1 if any failed or were not found."
  [prompt-names]
  (let [names-to-install (if (empty? prompt-names)
                           (map :name (get-prompt-vars))
                           prompt-names)
        results (map install-prompt names-to-install)
        exit-code (if (every? zero? results) 0 1)]
    exit-code))

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
        category-resources-vec (tp/category-prompt-resources config)
        base-dir (:base-dir config)
        all-resources (merge
                        (resources/prompt-resources all-prompts)
                        (resources/category-prompt-resources
                          category-resources-vec)
                        (resources/current-execution-resource base-dir))]
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

(defn- index-of
  "Returns the index of the first occurrence of value in coll.
  Returns nil if not found."
  [coll value]
  (first (keep-indexed #(when (= %2 value) %1) coll)))

(defn -main
  "CLI entry point for MCP Tasks.

  Supports:
    --list-prompts: List available prompt names and descriptions

    --install-prompts [names]: Install prompts to appropriate directories.
                               Categories go to .mcp-tasks/category-prompts/
                               Workflows go to .mcp-tasks/prompt-overrides/
                               Accepts comma-separated names or installs all if omitted.

  No args: Start the MCP server

  The server automatically searches for .mcp-tasks.edn starting from the current
  working directory and traversing up the directory tree."
  [& args]
  (let [args-vec (vec args)]
    (cond
      ;; --list-prompts flag
      (some #{"--list-prompts"} args)
      (System/exit (list-prompts))

      ;; --install-prompts flag
      (some #{"--install-prompts"} args)
      (let [idx (index-of args-vec "--install-prompts")
            next-arg (when (< (inc idx) (count args-vec))
                       (nth args-vec (inc idx)))
            prompt-names (if (and next-arg
                                  (not (str/starts-with? next-arg "--")))
                           (str/split next-arg #",")
                           [])]
        (System/exit (install-prompts prompt-names)))

      ;; Default: start server
      :else
      (start {}))))
