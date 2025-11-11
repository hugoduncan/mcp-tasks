(ns mcp-tasks.cli.prompts
  "Prompts command implementations for the CLI.

  Handles listing and installing built-in prompts."
  (:require
    [mcp-tasks.prompt-management :as pm]))

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

  Takes :prompt-names from parsed-args and installs each prompt.

  Returns structured data with:
  - :results - vector of installation result maps
  - :metadata - map with :requested-count, :installed-count, :failed-count

  Example:
  {:results [{:name \"simple\" :type :category :status :installed :path \"...\"}
             {:name \"foo\" :type nil :status :not-found}]
   :metadata {:requested-count 2 :installed-count 1 :failed-count 1}}"
  [parsed-args]
  (let [prompt-names (:prompt-names parsed-args)
        results (mapv pm/install-prompt prompt-names)
        installed-count (count (filter #(= :installed (:status %)) results))
        failed-count (count (filter #(not= :installed (:status %)) results))]
    {:results results
     :metadata {:requested-count (count prompt-names)
                :installed-count installed-count
                :failed-count failed-count}}))
