(ns mcp-tasks.prompt-management
  "Prompt discovery and installation management"
  (:require
    [babashka.fs :as fs]
    [clojure.java.io :as io]
    [clojure.string :as str])
  (:import
    (java.io
      File)))

(defn get-prompt-vars
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

(defn list-builtin-categories
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

(defn list-available-prompts
  "List all available built-in prompts with metadata.

  Returns a sequence of maps with:
  - :name - prompt name (string)
  - :type - :category or :workflow
  - :description - prompt description (string)

  Example:
  [{:name \"simple\"
    :type :category
    :description \"Execute simple tasks with basic workflow\"}
   {:name \"execute-task\"
    :type :workflow
    :description \"Execute a task following category-specific workflow\"}]"
  []
  (let [prompt-maps (get-prompt-vars)
        builtin-categories (list-builtin-categories)]
    (map (fn [prompt-map]
           (let [prompt-name (:name prompt-map)
                 is-category? (contains? builtin-categories prompt-name)
                 docstring (or (:doc (:meta prompt-map)) "No description available")]
             {:name prompt-name
              :type (if is-category? :category :workflow)
              :description docstring}))
         prompt-maps)))

(defn install-prompt
  "Install a single prompt to appropriate directory.

  Parameters:
  - prompt-name: Name of the prompt to install (string)

  Auto-detects type:
  - Categories -> .mcp-tasks/category-prompts/<name>.md
  - Workflows -> .mcp-tasks/prompt-overrides/<name>.md

  Returns a map with:
  - :name - prompt name
  - :type - :category or :workflow (nil if not found)
  - :status - :installed | :exists | :not-found | :error
  - :path - installation path (when status is :installed or :exists)
  - :error - error message (when status is :error)

  Example success:
  {:name \"simple\" :type :category :status :installed
   :path \".mcp-tasks/category-prompts/simple.md\"}

  Example not found:
  {:name \"foo\" :type nil :status :not-found}"
  [prompt-name]
  (let [prompt-maps (get-prompt-vars)
        prompt-map (first (filter #(= prompt-name (:name %)) prompt-maps))]
    (if-not prompt-map
      {:name prompt-name
       :type nil
       :status :not-found}
      (let [builtin-categories (list-builtin-categories)
            is-category? (contains? builtin-categories prompt-name)
            target-dir (if is-category?
                         ".mcp-tasks/category-prompts"
                         ".mcp-tasks/prompt-overrides")
            target-file (str target-dir "/" prompt-name ".md")
            prompt-type (if is-category? :category :workflow)]
        (if (fs/exists? target-file)
          {:name prompt-name
           :type prompt-type
           :status :exists
           :path target-file}
          (try
            (when-let [parent (fs/parent target-file)]
              (fs/create-dirs parent))
            (spit target-file (:content prompt-map))
            {:name prompt-name
             :type prompt-type
             :status :installed
             :path target-file}
            (catch Exception e
              {:name prompt-name
               :type prompt-type
               :status :error
               :error (.getMessage e)})))))))
