(ns mcp-tasks.cli.prompts
  "Prompts command implementations for the CLI.

  Handles listing and installing built-in prompts."
  (:require
    [babashka.fs :as fs]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [mcp-tasks.prompt-management :as pm]
    [mcp-tasks.prompts :as prompts]))

(defn- build-slash-command-frontmatter
  "Build YAML frontmatter string for Claude Code slash command.

  Extracts relevant fields from prompt metadata:
  - description: Brief description shown in /help
  - argument-hint: Expected arguments for auto-completion

  Metadata may have string or keyword keys (from YAML parsing).

  Returns frontmatter string with delimiters, or empty string if no relevant fields."
  [metadata]
  (let [;; Support both string and keyword keys
        description (or (get metadata "description") (get metadata :description))
        argument-hint (or (get metadata "argument-hint") (get metadata :argument-hint))
        fields (cond-> []
                 description
                 (conj (str "description: " description))

                 argument-hint
                 (conj (str "argument-hint: " argument-hint)))]
    (if (seq fields)
      (str "---\n" (str/join "\n" fields) "\n---\n\n")
      "")))

(defn prompts-list-command
  "Execute the prompts list command.

  Returns structured data with:
  - :prompts - vector of prompt maps with :name, :type, :description
  - :metadata - map with :total-count, :category-count, :workflow-count

  Example:
  {:prompts [{:name \"simple\" :type :category :description \"...\"}
             {:name \"execute-task\" :type :workflow :description \"...\"}]
   :metadata {:total-count 11 :category-count 4 :workflow-count 7}}"
  [_parsed-args]
  (let [prompts (vec (pm/list-available-prompts))
        category-count (count (filter #(= :category (:type %)) prompts))
        workflow-count (count (filter #(= :workflow (:type %)) prompts))]
    {:prompts prompts
     :metadata {:total-count (count prompts)
                :category-count category-count
                :workflow-count workflow-count}}))

(defn prompts-customize-command
  "Execute the prompts customize command.

  Takes config and parsed-args. Copies each prompt from :prompt-names to local directories.

  Returns structured data with:
  - :results - vector of copy result maps
  - :metadata - map with :requested-count, :installed-count, :failed-count

  Example:
  {:results [{:name \"simple\" :type :category :status :installed :path \"...\"}
             {:name \"foo\" :type nil :status :not-found}]
   :metadata {:requested-count 2 :installed-count 1 :failed-count 1}}"
  [config parsed-args]
  (let [prompt-names (:prompt-names parsed-args)
        results (mapv (partial pm/install-prompt config) prompt-names)
        installed-count (count (filter #(= :installed (:status %)) results))
        failed-count (count (filter #(not= :installed (:status %)) results))]
    {:results results
     :metadata {:requested-count (count prompt-names)
                :installed-count installed-count
                :failed-count failed-count}}))

(defn- load-prompt-by-type
  "Load a prompt by type, handling path resolution and content loading.

  Parameters:
  - prompt-type: :category or :workflow
  - resolver-fn: Function to resolve override path (takes resolved-tasks-dir and prompt-name)
  - builtin-dir: Built-in resource directory string
  - resolved-tasks-dir: Base directory for overrides
  - prompt-name: Name of the prompt

  Returns structured data with :name, :type, :content, :source, :path
  or error map with :error and :metadata."
  [prompt-type resolver-fn builtin-dir resolved-tasks-dir prompt-name]
  (let [resolved-path (resolver-fn resolved-tasks-dir prompt-name)
        builtin-resource-path (str builtin-dir "/" prompt-name ".md")
        loaded (prompts/load-prompt-content resolved-path builtin-resource-path)]
    (if loaded
      (assoc loaded
             :name prompt-name
             :type prompt-type)
      {:error (str "Prompt '" prompt-name "' not found. "
                   "Use 'mcp-tasks prompts list' to see available prompts.")
       :metadata {:prompt-name prompt-name}})))

(defn- generate-slash-command
  "Generate a single Claude Code slash command file from a prompt.

  Parameters:
  - config: Configuration map with :resolved-tasks-dir
  - target-dir: Target directory for generated files
  - prompt-info: Map with :name and :type from list-available-prompts

  The generated file includes:
  - YAML frontmatter with description and argument-hint (if present in source)
  - Rendered prompt content with {:cli true} context applied

  Returns a result map with:
  - :name - prompt name
  - :type - :category or :workflow
  - :status - :generated | :failed | :skipped
  - :path - path to generated file (when status is :generated)
  - :overwritten - true if file existed and was overwritten (when status is :generated)
  - :error - error message (when status is :failed)
  - :reason - skip reason (when status is :skipped)"
  [config target-dir prompt-info]
  (let [{:keys [name]} prompt-info
        resolved-tasks-dir (:resolved-tasks-dir config)
        actual-type (prompts/detect-prompt-type name)]
    (if (nil? actual-type)
      {:name name
       :type nil
       :status :skipped
       :reason "Infrastructure file, not a prompt"}
      (let [[resolver-fn builtin-dir] (case actual-type
                                        :category [prompts/resolve-category-prompt-path
                                                   prompts/builtin-category-prompts-dir]
                                        :workflow [prompts/resolve-workflow-prompt-path
                                                   prompts/builtin-prompts-dir])
            resolved-path (resolver-fn resolved-tasks-dir name)
            builtin-resource-path (str builtin-dir "/" name ".md")
            cli-context {:cli true}]
        (try
          (let [loaded (prompts/load-prompt-content resolved-path
                                                    builtin-resource-path
                                                    cli-context)]
            (if loaded
              (let [frontmatter (build-slash-command-frontmatter (:metadata loaded))
                    file-content (str frontmatter (:content loaded))
                    target-file (io/file target-dir (str "mcp-tasks-" name ".md"))
                    file-existed? (fs/exists? target-file)]
                (fs/create-dirs target-dir)
                (spit target-file file-content)
                {:name name
                 :type actual-type
                 :status :generated
                 :path (str target-file)
                 :overwritten file-existed?})
              {:name name
               :type actual-type
               :status :failed
               :error (str "Prompt '" name "' not found")}))
          (catch Exception e
            {:name name
             :type actual-type
             :status :failed
             :error (.getMessage e)}))))))

(defn prompts-show-command
  "Execute the prompts show command.

  Takes config and parsed-args. Shows the effective content of a specific prompt.

  Returns structured data with:
  - :name - prompt name
  - :type - :category or :workflow
  - :content - resolved prompt content
  - :source - :override or :builtin
  - :path - path to source file

  Returns error map if prompt not found:
  - :error - error message
  - :metadata - map with :prompt-name

  Example success:
  {:name \"simple\"
   :type :category
   :content \"...\"
   :source :builtin
   :path \"jar:file:/...!/category-prompts/simple.md\"}

  Example error:
  {:error \"Prompt 'foo' not found\"
   :metadata {:prompt-name \"foo\"}}"
  [config parsed-args]
  (let [prompt-name (:prompt-name parsed-args)]
    (if (nil? prompt-name)
      {:error "Prompt name is required"
       :metadata {:prompt-name nil}}
      (let [resolved-tasks-dir (:resolved-tasks-dir config)
            prompt-type (prompts/detect-prompt-type prompt-name)]
        (case prompt-type
          :category
          (load-prompt-by-type :category
                               prompts/resolve-category-prompt-path
                               prompts/builtin-category-prompts-dir
                               resolved-tasks-dir
                               prompt-name)

          :workflow
          (load-prompt-by-type :workflow
                               prompts/resolve-workflow-prompt-path
                               prompts/builtin-prompts-dir
                               resolved-tasks-dir
                               prompt-name)

          {:error (str "Prompt '" prompt-name "' not found. "
                       "Use 'mcp-tasks prompts list' to see available prompts.")
           :metadata {:prompt-name prompt-name}})))))

(defn prompts-install-command
  "Execute the prompts install command.

  Generates Claude Code slash command files from available prompts.
  Files are written to the target directory with names: mcp-tasks-<prompt-name>.md

  Templates are rendered with {:cli true} context, enabling {% if cli %} conditionals
  to provide CLI-specific alternatives for MCP tool references.

  Takes config and parsed-args. Target directory defaults to .claude/commands/.

  Returns structured data with:
  - :results - vector of generation result maps
  - :metadata - map with :generated-count, :failed-count, :skipped-count, :overwritten-count, :target-dir

  Example:
  {:results [{:name \"simple\" :status :generated :path \"...\" :overwritten false}]
   :metadata {:generated-count 1 :failed-count 0 :skipped-count 0 :overwritten-count 0 :target-dir \".claude/commands/\"}}"
  [config parsed-args]
  (let [target-dir (:target-dir parsed-args)
        available-prompts (pm/list-available-prompts)
        results (mapv (partial generate-slash-command config target-dir)
                      available-prompts)
        generated (filter #(= :generated (:status %)) results)
        generated-count (count generated)
        failed-count (count (filter #(= :failed (:status %)) results))
        skipped-count (count (filter #(= :skipped (:status %)) results))
        overwritten-count (count (filter :overwritten generated))]
    {:results results
     :metadata {:generated-count generated-count
                :failed-count failed-count
                :skipped-count skipped-count
                :overwritten-count overwritten-count
                :target-dir target-dir}}))
