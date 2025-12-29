(ns mcp-tasks.cli.prompts
  "Prompts command implementations for the CLI.

  Handles listing and installing built-in prompts."
  (:require
    [babashka.fs :as fs]
    [cheshire.core :as json]
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
  [config _parsed-args]
  (let [prompts (vec (pm/list-available-prompts config))
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
  - Rendered prompt content with MCP tool syntax (cli=false context)

  For category prompts, the command name is prefixed with 'next-' to indicate
  execution of the next task in that category.

  Returns a result map with:
  - :name - prompt name (with next- prefix for categories)
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
            cli-context {}
            ;; Category prompts get "next-" prefix for command name
            command-name (if (= actual-type :category)
                           (str "next-" name)
                           name)]
        (try
          (let [loaded (prompts/load-prompt-content resolved-path
                                                    builtin-resource-path
                                                    cli-context)]
            (if loaded
              (let [frontmatter (build-slash-command-frontmatter (:metadata loaded))
                    file-content (str frontmatter (:content loaded))
                    target-file (io/file target-dir (str "mcp-tasks-" command-name ".md"))
                    file-existed? (fs/exists? target-file)]
                (fs/create-dirs target-dir)
                (spit target-file file-content)
                {:name command-name
                 :type actual-type
                 :status :generated
                 :path (str target-file)
                 :overwritten file-existed?})
              {:name command-name
               :type actual-type
               :status :failed
               :error (str "Prompt '" name "' not found")}))
          (catch Exception e
            {:name command-name
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

;; Hook Installation

(def ^:private hook-scripts
  "List of hook scripts to install with their event types."
  [{:event "UserPromptSubmit"
    :script "user-prompt-submit.bb"}
   {:event "PreCompact"
    :script "pre-compact.bb"}
   {:event "SessionStart"
    :script "session-start.bb"}])

(defn- copy-hook-script
  "Copy a hook script from resources to target directory.

  Returns a result map with :script, :status, :path, :overwritten."
  [target-dir {:keys [script]}]
  (let [resource-path (str "hooks/" script)
        resource (io/resource resource-path)
        target-file (io/file target-dir script)]
    (if resource
      (let [file-existed? (fs/exists? target-file)]
        (fs/create-dirs target-dir)
        (spit target-file (slurp resource))
        {:script script
         :status :installed
         :path (str target-file)
         :overwritten file-existed?})
      {:script script
       :status :failed
       :error (str "Resource not found: " resource-path)})))

(defn- install-hook-scripts
  "Copy all hook scripts to .mcp-tasks/hooks/.

  Returns vector of result maps."
  [resolved-tasks-dir]
  (let [hooks-dir (io/file resolved-tasks-dir "hooks")]
    (mapv (partial copy-hook-script hooks-dir) hook-scripts)))

(defn- build-hook-command
  "Build the command string for a hook script."
  [tasks-dir-name script]
  (str "bb " tasks-dir-name "/hooks/" script))

(defn- build-mcp-tasks-hooks
  "Build the hooks configuration for mcp-tasks event capture."
  [tasks-dir-name]
  (reduce
    (fn [acc {:keys [event script]}]
      (assoc acc event [{:hooks [{:type "command"
                                  :command (build-hook-command tasks-dir-name script)}]}]))
    {}
    hook-scripts))

(defn- get-hook-command
  "Get command from a hook, supporting both keyword and string keys."
  [hook]
  (or (:command hook) (get hook "command")))

(defn- get-entry-hooks
  "Get hooks from an entry, supporting both keyword and string keys."
  [entry]
  (or (:hooks entry) (get entry "hooks")))

(defn- merge-hooks
  "Merge new hooks into existing hooks configuration.

  For each event type, appends new hook entries to existing ones.
  Avoids duplicating hooks with identical commands.
  Handles both keyword and string keys from JSON parsing."
  [existing-hooks new-hooks]
  (reduce-kv
    (fn [acc event new-entries]
      (let [existing-entries (get acc event [])
            existing-commands (set (mapcat (fn [entry]
                                             (map get-hook-command
                                                  (get-entry-hooks entry)))
                                           existing-entries))
            ;; Filter out entries where any command already exists
            unique-entries (filter
                             (fn [entry]
                               (not-any? #(existing-commands (get-hook-command %))
                                         (get-entry-hooks entry)))
                             new-entries)]
        (if (seq unique-entries)
          (assoc acc event (vec (concat existing-entries unique-entries)))
          acc)))
    existing-hooks
    new-hooks))

(defn- install-hooks-config
  "Install hooks configuration into .claude/settings.json.

  Merges mcp-tasks hooks with existing configuration.
  Returns a result map with :status, :path, :hooks-added, :settings-existed.
  Returns :skipped status if base-dir is nil."
  [base-dir resolved-tasks-dir]
  (if (nil? base-dir)
    {:status :skipped
     :reason "No base-dir configured"}
    (let [claude-dir (io/file base-dir ".claude")
          settings-file (io/file claude-dir "settings.json")
          ;; Use relative path from base-dir to tasks-dir
          tasks-dir-rel (if (fs/absolute? resolved-tasks-dir)
                          (let [base-path (fs/path base-dir)
                                tasks-path (fs/path resolved-tasks-dir)]
                            (str (fs/relativize base-path tasks-path)))
                          resolved-tasks-dir)
          new-hooks (build-mcp-tasks-hooks tasks-dir-rel)]
      (try
        (fs/create-dirs claude-dir)
        (let [settings-existed? (fs/exists? settings-file)
              existing-settings (if settings-existed?
                                  (json/parse-string (slurp settings-file))
                                  {})
              existing-hooks (get existing-settings "hooks" {})
              merged-hooks (merge-hooks existing-hooks new-hooks)
              hooks-added (not= existing-hooks merged-hooks)
              updated-settings (assoc existing-settings "hooks" merged-hooks)]
          (spit settings-file (json/generate-string updated-settings {:pretty true}))
          {:status :installed
           :path (str settings-file)
           :hooks-added hooks-added
           :settings-existed settings-existed?})
        (catch Exception e
          {:status :failed
           :path (str settings-file)
           :error (.getMessage e)})))))

(defn prompts-install-command
  "Execute the prompts install command.

  Generates Claude Code slash command files from available prompts, installs
  hook scripts to .mcp-tasks/hooks/, and configures hooks in .claude/settings.json.

  Files are written to the target directory with names: mcp-tasks-<prompt-name>.md

  Templates are rendered without cli context (cli=false), showing MCP tool syntax
  suitable for agent execution rather than CLI command examples.

  Takes config and parsed-args. Target directory defaults to .claude/commands/.

  Returns structured data with:
  - :results - vector of generation result maps
  - :hooks - map with :scripts (vector), :settings (map)
  - :metadata - map with counts and paths

  Example:
  {:results [{:name \"simple\" :status :generated :path \"...\" :overwritten false}]
   :hooks {:scripts [{:script \"user-prompt-submit.bb\" :status :installed :path \"...\"}]
           :settings {:status :installed :path \"...\" :hooks-added true}}
   :metadata {:generated-count 1 :failed-count 0 :skipped-count 0
              :overwritten-count 0 :target-dir \".claude/commands/\"
              :hooks-installed 3 :hooks-failed 0}}"
  [config parsed-args]
  (let [target-dir (:target-dir parsed-args)
        resolved-tasks-dir (:resolved-tasks-dir config)
        base-dir (:base-dir config)
        ;; Generate slash commands
        available-prompts (pm/list-available-prompts)
        results (mapv (partial generate-slash-command config target-dir)
                      available-prompts)
        generated (filter #(= :generated (:status %)) results)
        generated-count (count generated)
        failed-count (count (filter #(= :failed (:status %)) results))
        skipped-count (count (filter #(= :skipped (:status %)) results))
        overwritten-count (count (filter :overwritten generated))
        ;; Install hook scripts
        hook-script-results (install-hook-scripts resolved-tasks-dir)
        hooks-installed (count (filter #(= :installed (:status %)) hook-script-results))
        hooks-failed (count (filter #(= :failed (:status %)) hook-script-results))
        ;; Install hooks configuration
        hooks-config-result (install-hooks-config base-dir resolved-tasks-dir)]
    {:results results
     :hooks {:scripts hook-script-results
             :settings hooks-config-result}
     :metadata {:generated-count generated-count
                :failed-count failed-count
                :skipped-count skipped-count
                :overwritten-count overwritten-count
                :target-dir target-dir
                :hooks-installed hooks-installed
                :hooks-failed hooks-failed}}))
