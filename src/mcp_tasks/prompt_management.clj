(ns mcp-tasks.prompt-management
  "Prompt discovery and installation management"
  (:require
    [babashka.fs :as fs]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [mcp-tasks.prompts :as prompts])
  (:import
    (java.io
      File)))

(defn- parse-frontmatter
  "Parse YAML frontmatter from markdown content.

  Delegates to prompts/parse-frontmatter and converts the result to keyword map format.

  Returns: {:description \"...\" :argument-hint \"...\"} (keyword keys, or nil)"
  [content]
  (let [{:keys [metadata]} (prompts/parse-frontmatter content)]
    (when metadata
      (into {}
            (map (fn [[k v]] [(keyword k) v]) metadata)))))

(defn- discover-file-prompts
  "Discover file-based prompts from resources/prompts directory.

  Returns a sequence of maps with :name, :content, :var, and :meta keys.
  Only discovers prompts from resources - does not include namespace vars.

  Returns empty sequence if resources directory is not found or cannot be accessed."
  []
  (if-let [prompts-url (io/resource "prompts")]
    (try
      (let [prompts-dir (io/file (.toURI prompts-url))
            prompt-files (->> (file-seq prompts-dir)
                              (filter #(and (.isFile ^File %)
                                            (str/ends-with? (.getName ^File %) ".md"))))]
        (keep (fn [file]
                (try
                  (let [prompt-name (str/replace (.getName ^File file) #"\.md$" "")
                        content (slurp file)
                        frontmatter (parse-frontmatter content)
                        description (or (:description frontmatter)
                                        (str "Task execution prompt: " prompt-name))]
                    {:name prompt-name
                     :content content
                     :var (reify clojure.lang.IDeref
                            (deref [_] content))
                     :meta {:doc description}})
                  (catch Exception _e
                    ;; Skip files that cannot be read
                    nil)))
              prompt-files))
      (catch Exception _e
        ;; Cannot access resources directory
        []))
    ;; Resources directory not found
    []))

(defn- deduplicate-prompts
  "De-duplicate prompts by name, preferring file-based entries over vars.

  Takes a sequence of prompt maps. When multiple prompts have the same :name,
  keeps only the last occurrence (file-based prompts come after vars in typical usage).

  Returns a sequence of de-duplicated prompt maps."
  [prompts]
  (let [grouped (group-by :name prompts)]
    (mapcat (fn [[_name prompt-list]]
              ;; Take last to prefer file-based entries
              [(last prompt-list)])
            grouped)))

(defn get-prompt-vars
  "Get all prompt vars and files from namespaces and resources.

  Returns a sequence of maps with :name, :content, :var, and :meta keys.
  Discovers prompts from:
  - mcp-tasks.task-prompts namespace vars (category prompts)
  - mcp-tasks.story-prompts namespace vars (story prompts)
  - resources/prompts/*.md files (task execution prompts)

  File-based prompts override namespace vars when names collide."
  []
  (let [var-prompts (prompts/get-prompt-vars)
        file-prompts (discover-file-prompts)
        all-prompts (concat var-prompts (or file-prompts []))]
    (deduplicate-prompts all-prompts)))

(defn list-builtin-categories
  "List all built-in category prompt names.

  Returns a set of category names (strings) found in resources/category-prompts/.

  Delegates to prompts/list-builtin-categories."
  []
  (prompts/list-builtin-categories))

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
       :status :not-found
       :error (str "Prompt '" prompt-name "' not found. "
                   "Use 'mcp-tasks prompts list' to see available prompts.")}
      (let [builtin-categories (list-builtin-categories)
            is-category? (contains? builtin-categories prompt-name)
            target-dir (if is-category?
                         ".mcp-tasks/category-prompts"
                         ".mcp-tasks/prompt-overrides")
            relative-path (str target-dir "/" prompt-name ".md")
            ;; Resolve relative path against current working directory
            target-file (io/file (System/getProperty "user.dir") relative-path)
            prompt-type (if is-category? :category :workflow)]
        (if (fs/exists? target-file)
          {:name prompt-name
           :type prompt-type
           :status :exists
           :path relative-path}
          (try
            (when-let [parent (fs/parent target-file)]
              (fs/create-dirs parent))
            (spit target-file (:content prompt-map))
            {:name prompt-name
             :type prompt-type
             :status :installed
             :path relative-path}
            (catch Exception e
              {:name prompt-name
               :type prompt-type
               :status :error
               :error (str "Failed to install prompt to " relative-path ": " (.getMessage e))})))))))
