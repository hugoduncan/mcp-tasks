(ns mcp-tasks.cli.prompts
  "Prompts command implementations for the CLI.

  Handles listing and installing built-in prompts."
  (:require
    [mcp-tasks.prompt-management :as pm]
    [mcp-tasks.prompts :as prompts]))

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

(defn prompts-install-command
  "Execute the prompts install command.

  Takes config and parsed-args. Installs each prompt from :prompt-names.

  Returns structured data with:
  - :results - vector of installation result maps
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
            builtin-categories (prompts/list-builtin-categories)
            builtin-workflows (set (prompts/list-builtin-workflows))
            is-category? (contains? builtin-categories prompt-name)
            is-workflow? (contains? builtin-workflows prompt-name)]
        (cond
          is-category?
          (let [resolved-path (prompts/resolve-category-prompt-path
                                resolved-tasks-dir
                                prompt-name)
                builtin-resource-path (str prompts/builtin-category-prompts-dir
                                           "/"
                                           prompt-name
                                           ".md")
                loaded (prompts/load-prompt-content resolved-path builtin-resource-path)]
            (if loaded
              (assoc loaded
                     :name prompt-name
                     :type :category)
              {:error (str "Prompt '" prompt-name "' not found")
               :metadata {:prompt-name prompt-name}}))

          is-workflow?
          (let [resolved-path (prompts/resolve-workflow-prompt-path
                                resolved-tasks-dir
                                prompt-name)
                builtin-resource-path (str prompts/builtin-prompts-dir "/" prompt-name ".md")
                loaded (prompts/load-prompt-content resolved-path builtin-resource-path)]
            (if loaded
              (assoc loaded
                     :name prompt-name
                     :type :workflow)
              {:error (str "Prompt '" prompt-name "' not found")
               :metadata {:prompt-name prompt-name}}))

          :else
          {:error (str "Prompt '" prompt-name "' not found. "
                       "Use 'mcp-tasks prompts list' to see available prompts.")
           :metadata {:prompt-name prompt-name}})))))
