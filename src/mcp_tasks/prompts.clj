(ns mcp-tasks.prompts
  "Task management prompts"
  (:require
    [babashka.fs :as fs]
    [clojure.java.io :as io]
    [clojure.set :as set]
    [clojure.string :as str]
    [mcp-clj.log :as log]
    [mcp-clj.mcp-server.prompts :as prompts]
    [mcp-tasks.templates :as templates]))

;; Path constants for prompt resources and user overrides
(def builtin-category-prompts-dir "category-prompts")
(def builtin-prompts-dir "prompts")
(def ^:private builtin-infrastructure-dir "prompts/infrastructure")

(def ^:private user-category-prompts-dir "category-prompts")
(def ^:private user-prompt-overrides-dir "prompt-overrides")

;; Deprecated directory paths for backward compatibility
(def ^:private deprecated-user-category-prompts-dir "prompts")
(def ^:private deprecated-user-workflow-prompts-dir "story/prompts")

(defn- resolve-paths
  "Pure path resolution function without side effects.

  Checks which of the given paths exist and returns resolution metadata.

  Parameters:
  - new-path: Path to check in new location (absolute)
  - deprecated-path: Path to check in deprecated location (absolute)

  Returns:
  - Map with :path (absolute path), :location (:new, :deprecated, or :none),
    and :both-exist? boolean
  - Location is :none if neither path exists"
  [new-path deprecated-path]
  (let [new-exists? (fs/exists? new-path)
        deprecated-exists? (fs/exists? deprecated-path)]
    (cond
      (and new-exists? deprecated-exists?)
      {:path new-path
       :location :new
       :both-exist? true}

      new-exists?
      {:path new-path
       :location :new
       :both-exist? false}

      deprecated-exists?
      {:path deprecated-path
       :location :deprecated
       :both-exist? false}

      :else
      {:path nil
       :location :none
       :both-exist? false})))

(defn- log-path-resolution
  "Log warnings/info based on path resolution result.

  Parameters:
  - resolution: Map returned from resolve-paths
  - new-path: Path that was checked in new location
  - deprecated-path: Path that was checked in deprecated location
  - identifier: Name for logging (e.g., category name or prompt name)
  - log-event-prefix: Keyword prefix for log events
  - migration-message: Message explaining how to migrate

  Side effects:
  - Logs warning if deprecated location is used
  - Logs info if file exists in both locations"
  [resolution new-path deprecated-path identifier log-event-prefix migration-message]
  (case (:location resolution)
    :new
    (when (:both-exist? resolution)
      (log/info (keyword (str (name log-event-prefix) "-both-locations"))
                {:identifier identifier
                 :new-path new-path
                 :deprecated-path deprecated-path
                 :message (str (name log-event-prefix) " found in both new and deprecated locations. Using new location.")}))

    :deprecated
    (log/warn (keyword (str (name log-event-prefix) "-deprecated-location"))
              {:identifier identifier
               :deprecated-path deprecated-path
               :new-path new-path
               :message migration-message})

    :none
    nil))

(defn- resolve-prompt-path-with-fallback
  "Generic path resolution with fallback to deprecated location.

  Checks for file in new location first, then deprecated location with warning.

  Parameters:
  - new-path: Path to check in new location (absolute)
  - deprecated-path: Path to check in deprecated location (absolute)
  - identifier: Name for logging (e.g., category name or prompt name)
  - log-event-prefix: Keyword prefix for log events (e.g., :category-prompt or :workflow-prompt)
  - migration-message: Message explaining how to migrate

  Returns:
  - Map with :path (absolute path) and :location (:new or :deprecated)
  - nil if file not found in either location

  Side effects:
  - Logs warning if deprecated location is used
  - Logs info if file exists in both locations (new takes precedence)"
  [new-path deprecated-path identifier log-event-prefix migration-message]
  (let [resolution (resolve-paths new-path deprecated-path)]
    (log-path-resolution resolution new-path deprecated-path identifier log-event-prefix migration-message)
    (when-not (= :none (:location resolution))
      {:path (:path resolution)
       :location (:location resolution)})))

(defn resolve-category-prompt-path
  "Public API for CLI and tooling to resolve category prompt paths.
  
  Resolves category prompt path with fallback to deprecated location.

  Checks for category prompt file in this order:
  1. .mcp-tasks/category-prompts/<category>.md (new location)
  2. .mcp-tasks/prompts/<category>.md (deprecated, with warning)

  Parameters:
  - resolved-tasks-dir: Base directory containing .mcp-tasks
  - category: Category name (without .md extension)

  Returns:
  - Map with :path (absolute path) and :location (:new or :deprecated)
  - nil if file not found in either location

  Side effects:
  - Logs warning if deprecated location is used
  - Logs info if file exists in both locations (new takes precedence)"
  [resolved-tasks-dir category]
  (let [new-path (str resolved-tasks-dir "/" user-category-prompts-dir "/" category ".md")
        deprecated-path (str resolved-tasks-dir "/" deprecated-user-category-prompts-dir "/" category ".md")]
    (resolve-prompt-path-with-fallback
      new-path
      deprecated-path
      category
      :category-prompt
      (str "Category prompt found in deprecated location. "
           "Please move from .mcp-tasks/prompts/ to .mcp-tasks/category-prompts/"))))

(defn resolve-workflow-prompt-path
  "Public API for CLI and tooling to resolve workflow prompt paths.
  
  Resolves workflow prompt path with fallback to deprecated location.

  Checks for workflow prompt file in this order:
  1. .mcp-tasks/prompt-overrides/<name>.md (new location)
  2. .mcp-tasks/story/prompts/<name>.md (deprecated, with warning)

  Parameters:
  - resolved-tasks-dir: Base directory containing .mcp-tasks
  - prompt-name: Prompt name (without .md extension)

  Returns:
  - Map with :path (absolute path) and :location (:new or :deprecated)
  - nil if file not found in either location

  Side effects:
  - Logs warning if deprecated location is used
  - Logs info if file exists in both locations (new takes precedence)"
  [resolved-tasks-dir prompt-name]
  (let [new-path (str resolved-tasks-dir "/" user-prompt-overrides-dir "/" prompt-name ".md")
        deprecated-path (str resolved-tasks-dir "/" deprecated-user-workflow-prompts-dir "/" prompt-name ".md")]
    (resolve-prompt-path-with-fallback
      new-path
      deprecated-path
      prompt-name
      :workflow-prompt
      (str "Workflow prompt found in deprecated location. "
           "Please move from .mcp-tasks/story/prompts/ to .mcp-tasks/prompt-overrides/"))))

(defn parse-frontmatter
  "Parse simple 'field: value' frontmatter from markdown text.

  Expects frontmatter delimited by '---' at start and end.
  Format example:
    ---
    description: Task description
    author: John Doe
    ---
    Content here...

  Returns a map with :metadata (parsed key-value pairs)
  and :content (remaining text).  If no valid frontmatter is found,
  returns {:metadata nil :content <original-text>}."
  [text]
  (if-not (str/starts-with? text "---\n")
    {:metadata nil :content text}
    (let [lines (str/split-lines text)
          ;; Skip first "---" line
          after-start (rest lines)
          ;; Find closing "---"
          closing-idx (first (keep-indexed
                               (fn [idx line]
                                 (when (= "---" (str/trim line)) idx))
                               after-start))]
      (if-not closing-idx
        ;; No closing delimiter, treat as no frontmatter
        {:metadata nil :content text}
        (let [metadata-lines (take closing-idx after-start)
              content-lines (drop (inc closing-idx) after-start)
              ;; Parse "key: value" pairs
              metadata (reduce
                         (fn [acc line]
                           (if-let [[_ k v] (re-matches
                                              #"([^:]+):\s*(.*)"
                                              line)]
                             (assoc acc (str/trim k) (str/trim v))
                             acc))
                         {}
                         metadata-lines)
              content (str/join "\n" content-lines)]
          {:metadata (when (seq metadata) metadata)
           :content content})))))

(defn- discover-prompt-files
  "Discover .md prompt files in a directory.

  Takes a File object and returns a sorted vector of filenames without
  .md extension.  Returns empty vector if directory doesn't exist."
  [dir]
  (if (fs/exists? dir)
    (->> (fs/list-dir dir)
         (filter #(and (fs/regular-file? %)
                       (str/ends-with? (str (fs/file-name %)) ".md")))
         (map #(str/replace (str (fs/file-name %)) #"\.md$" ""))
         sort
         vec)
    []))

(defn- discover-builtin-categories
  "Discover built-in category prompts from resources/category-prompts/.

  Returns a set of category names (strings without .md extension) found in
  the resources directory. Returns empty set if resource directory not found."
  []
  (if-let [resource-url (io/resource builtin-category-prompts-dir)]
    (try
      (let [resource-dir (io/file (.toURI resource-url))]
        (->> (file-seq resource-dir)
             (filter #(and (.isFile %)
                           (str/ends-with? (.getName %) ".md")))
             (map #(str/replace (.getName %) #"\.md$" ""))
             set))
      (catch Exception _e
        #{}))
    #{}))

(defn list-builtin-categories
  "List all built-in category prompt names.

  Returns a set of category names (strings) found in resources/category-prompts/."
  []
  (discover-builtin-categories))

(defn discover-categories
  "Discover task categories by reading category-prompts subdirectory from resolved tasks dir.

  Takes config containing :resolved-tasks-dir. Returns a sorted vector of
  category names (filenames without .md extension) found in the category-prompts
  subdirectory.

  Includes built-in categories from resources/category-prompts/ and user overrides.
  User overrides take precedence over built-in categories.

  Supports backward compatibility by also checking deprecated prompts/ directory.
  Categories found in both locations are deduplicated (new location takes precedence)."
  [config]
  (let [resolved-tasks-dir (:resolved-tasks-dir config)
        new-dir (str resolved-tasks-dir "/" user-category-prompts-dir)
        deprecated-dir (str resolved-tasks-dir "/" deprecated-user-category-prompts-dir)
        builtin-categories (discover-builtin-categories)
        new-categories (set (discover-prompt-files new-dir))
        deprecated-categories (set (discover-prompt-files deprecated-dir))
        ;; Find categories that only exist in deprecated location
        deprecated-only (set/difference deprecated-categories new-categories)]
    ;; Log warning for each deprecated-only category
    (doseq [category deprecated-only]
      (log/warn :category-prompt-deprecated-location
                {:category category
                 :deprecated-path (str deprecated-dir "/" category ".md")
                 :new-path (str new-dir "/" category ".md")
                 :message (str "Category prompt found in deprecated location. "
                               "Please move from .mcp-tasks/prompts/ to .mcp-tasks/category-prompts/")}))
    ;; Return combined sorted vector (built-in + user overrides + deprecated)
    (vec (sort (clojure.set/union builtin-categories new-categories deprecated-categories)))))

(defn- read-task-prompt-text
  "Generate prompt text for reading the next task from a category.
  Config parameter included for API consistency but not currently used."
  [_config _category]
  "- Read the file .mcp-tasks/tasks.ednl

- Find the first incomplete task (marked with `- [ ]`) You can use the
  `select-tasks` tool with `:limit 1` to retrieve the next task without
  executing it.

- Show the task description
")

(defn- default-prompt-text
  "Generate default execution instructions for a category."
  []
  (slurp (io/resource (str builtin-infrastructure-dir "/default-prompt-text.md"))))

(defn- complete-task-prompt-text
  "Generate prompt text for completing and tracking a task.

  Conditionally includes git commit instructions based on
  config :use-git? value."
  [config _category]
  (let [base-text
        "- Mark the completed task as complete using the `complete-task` tool.
  This will update the task's status to :closed and move it from
  .mcp-tasks/tasks.ednl to .mcp-tasks/complete.ednl.

- Summarise any deviations in the execution of the task, compared to the task
  spec.
"
        git-text "\n- Commit the task tracking changes in the .mcp-tasks git repository\n"]
    (if (:use-git? config)
      (str base-text git-text)
      base-text)))

(defn- read-prompt-instructions
  "Read custom prompt instructions from category-prompts subdirectory in resolved tasks dir.

  Takes config containing :resolved-tasks-dir and category name.

  Returns a map with :metadata and :content keys if the file exists, or
  nil if it doesn't.

  The :metadata key contains parsed frontmatter (may be nil),
  and :content contains the prompt text with frontmatter stripped.

  Falls back to built-in resources/category-prompts/<category>.md if no user
  override exists. Supports backward compatibility by checking deprecated
  prompts/ directory with deprecation warning."
  [config category]
  (let [resolved-tasks-dir (:resolved-tasks-dir config)]
    ;; Try user override first, then fall back to built-in resource
    (if-let [resolved (resolve-category-prompt-path resolved-tasks-dir category)]
      (parse-frontmatter (slurp (:path resolved)))
      ;; No user override, try built-in resource
      (when-let [resource-url (io/resource (str builtin-category-prompts-dir "/" category ".md"))]
        (parse-frontmatter (slurp resource-url))))))

(defn create-prompts
  "Generate MCP prompts for task categories.

  Reads prompt instructions from prompts/<category>.md files in resolved tasks
  directory if they exist, otherwise uses default prompt text. Each prompt
  provides complete workflow including task lookup, execution
  instructions, and completion steps.

  Returns a vector of prompt maps suitable for registration with the MCP
  server."
  [config categories]
  (vec
    (for [category categories]
      (let [prompt-data (read-prompt-instructions config category)
            metadata (:metadata prompt-data)
            custom-content (:content prompt-data)
            execution-instructions (or custom-content (default-prompt-text))
            prompt-text (str "Please complete the next "
                             category
                             " task following these steps:\n\n"
                             (read-task-prompt-text config category)
                             execution-instructions
                             (complete-task-prompt-text config category))
            description (or (get metadata "description")
                            (format
                              "Execute the next %s task from .mcp-tasks/tasks.ednl"
                              category))]
        (prompts/valid-prompt?
          {:name (str "next-" category)
           :description description
           :messages [{:role "user"
                       :content {:type "text"
                                 :text prompt-text}}]})))))

(defn category-descriptions
  "Get descriptions for all discovered categories.

  Returns a map of category name to description string. Categories
  without custom prompts or without description metadata will have a
  default description."
  [config]
  (let [categories (discover-categories config)]
    (into {}
          (for [category categories]
            (let [prompt-data (read-prompt-instructions config category)
                  metadata (:metadata prompt-data)
                  description (or (get metadata "description")
                                  (format "Tasks for %s category" category))]
              [category description])))))

(defn prompts
  "Generate all task prompts by discovering categories.

  Accepts config parameter to conditionally include git instructions in prompts.
  Uses :resolved-tasks-dir from config to locate prompts directory.

  Returns a map of prompt names to prompt definitions, suitable for registering
  with the MCP server."
  [config]
  (let [categories (discover-categories config)
        prompt-list (create-prompts config categories)]
    (into {} (map (fn [p] [(:name p) p]) prompt-list))))

(defn category-prompts
  "Generate MCP prompts from category prompt vars in mcp-tasks.task-prompts.

  Discovers category prompt definitions from the vars in mcp-tasks.task-prompts
  namespace and generates MCP prompt definitions.

  This function is used for native binaries where filesystem discovery isn't
  available.

  Returns a map of prompt names to prompt definitions, suitable for registering
  with the MCP server."
  [config]
  (require 'mcp-tasks.task-prompts)
  (let [ns (find-ns 'mcp-tasks.task-prompts)
        category-vars (->> (ns-publics ns)
                           vals
                           (filter (fn [v] (string? @v))))
        categories (map (fn [v] (name (symbol v))) category-vars)]
    (into {}
          (for [category categories]
            (let [prompt-var (ns-resolve ns (symbol category))
                  prompt-content @prompt-var
                  {:keys [metadata content]} (parse-frontmatter prompt-content)
                  execution-instructions content
                  prompt-text (str "Please complete the next "
                                   category
                                   " task following these steps:\n\n"
                                   (read-task-prompt-text config category)
                                   execution-instructions
                                   (complete-task-prompt-text config category))
                  description (or (get metadata "description")
                                  (format
                                    "Execute the next %s task from .mcp-tasks/tasks.ednl"
                                    category))]
              [(str "next-" category)
               (prompts/valid-prompt?
                 {:name (str "next-" category)
                  :description description
                  :messages [{:role "user"
                              :content {:type "text"
                                        :text prompt-text}}]})])))))

;; Story prompt utilities

(defn get-prompt-vars
  "Get all prompt vars from namespaces.

  Returns a sequence of maps with :name, :content, :var, and :meta keys.
  Discovers prompts from:
  - mcp-tasks.task-prompts namespace vars (category prompts)
  - mcp-tasks.story-prompts namespace vars (story prompts)

  Does NOT include file-based prompts - only namespace vars."
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
                        (filter (fn [v] (string? @v))))
        all-vars (concat task-vars story-vars)]
    (map (fn [v]
           {:name (name (symbol v))
            :content @v
            :var v
            :meta (meta v)})
         all-vars)))

(defn load-prompt-content
  "Load prompt content from override file or builtin resource.

  Parameters:
  - resolved-override: Map from resolve-*-prompt-path with :path and :location, or nil
  - builtin-resource-path: Path to builtin resource (e.g., \"category-prompts/simple.md\")
  - context: Optional map of template context variables (default: {})

  Returns a map with:
  - :content - The prompt text (with frontmatter stripped and templates rendered)
  - :metadata - Parsed frontmatter metadata map (may be nil)
  - :source - :override or :builtin
  - :path - Absolute path to override file or resource URL string

  Returns nil if neither override nor builtin exists.
  
  Template includes are resolved relative to prompts/ on the classpath.
  Template conditionals (e.g., {% if cli %}...{% endif %}) are evaluated
  using the provided context."
  ([resolved-override builtin-resource-path]
   (load-prompt-content resolved-override builtin-resource-path {}))
  ([resolved-override builtin-resource-path context]
   (if resolved-override
     ;; Load from override file
     (let [file-content (slurp (:path resolved-override))
           {:keys [metadata content]} (parse-frontmatter file-content)
           rendered-content (templates/render-with-includes
                              content context {:resource-base "prompts"})]
       {:content rendered-content
        :metadata metadata
        :source :override
        :path (:path resolved-override)})
     ;; Try builtin resource
     (when-let [resource-url (io/resource builtin-resource-path)]
       (let [file-content (slurp resource-url)
             {:keys [metadata content]} (parse-frontmatter file-content)
             rendered-content (templates/render-with-includes
                                content context {:resource-base "prompts"})]
         {:content rendered-content
          :metadata metadata
          :source :builtin
          :path (str resource-url)})))))

(defn list-builtin-workflows
  "List all built-in workflow prompts available in resources.

  Reads from generated source file (src/mcp_tasks/generated_workflows.clj) which is
  created at build time. This approach works reliably in both JAR and GraalVM native
  images by embedding the workflow list directly in compiled code.

  Falls back to empty vector if generated file not found (shouldn't happen in production)."
  []
  (try
    (require 'mcp-tasks.generated-workflows)
    (if-let [workflows (resolve 'mcp-tasks.generated-workflows/builtin-workflows)]
      @workflows
      (do
        (log/warn :generated-workflows-not-resolved {})
        []))
    (catch Exception e
      (log/error :failed-to-load-generated-workflows {:error (.getMessage e)})
      [])))

(defn detect-prompt-type
  "Detect whether a prompt name is a category or workflow prompt.

  Checks against built-in prompt lists to determine type.

  Parameters:
  - prompt-name: Name of the prompt (string)

  Returns:
  - :category if prompt is a built-in category
  - :workflow if prompt is a built-in workflow
  - nil if prompt is not found in either list"
  [prompt-name]
  (let [builtin-categories (list-builtin-categories)
        builtin-workflows (set (list-builtin-workflows))]
    (cond
      (contains? builtin-categories prompt-name) :category
      (contains? builtin-workflows prompt-name) :workflow
      :else nil)))

(defn get-story-prompt
  "Get a story prompt by name, with file override support.

  Checks for override file at `.mcp-tasks/prompt-overrides/<name>.md` first.
  If not found, falls back to deprecated `.mcp-tasks/story/prompts/<name>.md`.
  If not found in either location, falls back to built-in prompt from resources/prompts.

  Returns a map with:
  - :name - the prompt name
  - :description - from frontmatter
  - :content - the prompt text (with frontmatter stripped)

  Returns nil if prompt is not found in any location.

  Supports backward compatibility by checking deprecated story/prompts/ directory
  with deprecation warning."
  [prompt-name]
  (let [resolved (resolve-workflow-prompt-path ".mcp-tasks" prompt-name)
        builtin-path (str builtin-prompts-dir "/" prompt-name ".md")
        loaded (load-prompt-content resolved builtin-path)]
    (when loaded
      {:name prompt-name
       :description (get (:metadata loaded) "description")
       :content (:content loaded)})))

(defn list-story-prompts
  "List all available story prompts.

  Returns a sequence of maps with :name and :description for each available
  story prompt, including both built-in prompts and file overrides."
  []
  (let [builtin-prompts (for [prompt-name (list-builtin-workflows)
                              :let [prompt (get-story-prompt prompt-name)]
                              :when prompt]
                          {:name (:name prompt)
                           :description (:description prompt)})
        story-dir (str ".mcp-tasks/" user-prompt-overrides-dir)
        override-prompts (when (fs/exists? story-dir)
                           (for [file (fs/list-dir story-dir)
                                 :when (and (fs/regular-file? file)
                                            (str/ends-with?
                                              (str (fs/file-name file))
                                              ".md"))]
                             (let [name (str/replace
                                          (str (fs/file-name file))
                                          #"\.md$" "")
                                   {:keys [metadata]} (parse-frontmatter
                                                        (slurp (str file)))]
                               {:name name
                                :description (get metadata "description")})))
        all-prompts (concat override-prompts builtin-prompts)
        seen (atom #{})]
    (for [prompt all-prompts
          :when (not (contains? @seen (:name prompt)))]
      (do
        (swap! seen conj (:name prompt))
        prompt))))

(defn- parse-argument-hint
  "Parse argument-hint from frontmatter metadata.

  The argument-hint format uses angle brackets for required arguments and
  square brackets for optional arguments:
  - <arg-name> - required argument
  - [arg-name] - optional argument
  - [...] or [name...] - variadic/multiple values

  Example: '<story-name> [additional-context...]'

  Returns a vector of argument maps with :name, :description,
  and :required keys."
  [metadata]
  (when-let [hint (get metadata "argument-hint")]
    (vec
      (for [token (re-seq #"<([^>]+)>|\[([^\]]+)\]" hint)
            :let [[_ required optional] token
                  arg-name (or required optional)
                  is-required (some? required)
                  is-variadic (str/ends-with? arg-name "...")
                  clean-name (if is-variadic
                               (str/replace arg-name #"\.\.\.$" "")
                               arg-name)
                  description (cond
                                is-variadic (format
                                              "Optional additional %s (variadic)"
                                              clean-name)
                                is-required (format
                                              "The %s (required)"
                                              (str/replace clean-name "-" " "))
                                :else (format
                                        "Optional %s"
                                        (str/replace clean-name "-" " ")))]]
        {:name clean-name
         :description description
         :required is-required}))))

(defn- append-management-instructions
  "Append branch and worktree management instructions to prompt content.

  Conditionally appends management instruction files based on config flags.
  Only appends to prompts matching target-prompt-name.

  Parameters:
  - content: Base prompt content string
  - prompt-name: Name of the current prompt being processed
  - target-prompt-name: Prompt that should receive the instructions
  - config: Config map with :branch-management? and :worktree-management? flags

  Returns the content with instructions appended if conditions match."
  [content prompt-name target-prompt-name config]
  (cond-> content
    (and (= prompt-name target-prompt-name)
         (:branch-management? config))
    (str "\n\n" (slurp (io/resource (str builtin-infrastructure-dir "/branch-management.md"))))
    (and (= prompt-name target-prompt-name)
         (:worktree-management? config))
    (str "\n\n" (slurp (io/resource (str builtin-infrastructure-dir "/worktree-management.md"))))))

(defn story-prompts
  "Generate MCP prompts from story prompt vars in mcp-tasks.story-prompts.

  For execute-story-child prompt, tailors content based on
  config :branch-management?.

  Returns a map of prompt names to prompt definitions, suitable for registering
  with the MCP server."
  [config]
  (require 'mcp-tasks.story-prompts)
  (let [ns (find-ns 'mcp-tasks.story-prompts)
        prompt-vars (->> (ns-publics ns)
                         vals
                         (filter (fn [v] (string? @v))))]
    (into {}
          (for [v prompt-vars]
            (let [prompt-name (name (symbol v))
                  prompt-content @v
                  {:keys [metadata content]} (parse-frontmatter prompt-content)
                  ;; Tailor content based on config for prompts that need it
                  tailored-content
                  (cond-> content
                    (= prompt-name "execute-story-child")
                    (append-management-instructions "execute-story-child" "execute-story-child" config)

                    (= prompt-name "execute-task")
                    (append-management-instructions "execute-task" "execute-task" config))
                  description (or (get metadata "description")
                                  (:doc (meta v))
                                  (format "Story prompt: %s" prompt-name))
                  arguments (parse-argument-hint metadata)]

              [prompt-name
               (prompts/valid-prompt?
                 (cond-> {:name prompt-name
                          :description description
                          :messages [{:role "user"
                                      :content {:type "text"
                                                :text tailored-content}}]}
                   (seq arguments) (assoc :arguments arguments)))])))))

(defn task-execution-prompts
  "Generate MCP prompts for general task execution workflows.

  Discovers prompt files from resources/prompts/ directory.

  Returns a map of prompt names to prompt definitions."
  [config]
  (when-let [prompts-url (io/resource builtin-prompts-dir)]
    (let [prompts-dir (io/file (.toURI prompts-url))
          all-prompts (discover-prompt-files prompts-dir)
          prompts-data (for [prompt-name all-prompts
                             :let [resource-path (io/resource
                                                   (str
                                                     builtin-prompts-dir
                                                     "/"
                                                     prompt-name
                                                     ".md"))]
                             :when resource-path]
                         (let [file-content (slurp resource-path)
                               {:keys [metadata content]} (parse-frontmatter
                                                            file-content)
                               ;; Tailor execute-task and execute-story-child content based on config
                               tailored-content
                               (cond-> content
                                 (= prompt-name "execute-task")
                                 (append-management-instructions "execute-task" "execute-task" config)

                                 (= prompt-name "execute-story-child")
                                 (append-management-instructions "execute-story-child" "execute-story-child" config))
                               description (or (get metadata "description")
                                               (format
                                                 "Task execution prompt: %s"
                                                 prompt-name))
                               arguments (parse-argument-hint metadata)]
                           [prompt-name
                            (prompts/valid-prompt?
                              (cond-> {:name prompt-name
                                       :description description
                                       :messages [{:role "user"
                                                   :content {:type "text"
                                                             :text tailored-content}}]}
                                (seq arguments) (assoc
                                                  :arguments
                                                  arguments)))]))]
      (into {} prompts-data))))

(defn category-prompt-resources
  "Generate MCP resources for category prompt files.

  Discovers all available categories and creates a resource for each category's
  prompt file found in prompts/<category>.md within resolved tasks directory.

  Each resource has:
  - :uri \"prompt://category-<category>\"
  - :name \"<category> category instructions\"
  - :description from frontmatter or default
  - :mimeType \"text/markdown\"
  - :text content with frontmatter preserved

  Missing files are gracefully skipped (not included in result).

  Returns a vector of resource maps."
  [config]
  (let [categories (discover-categories config)]
    (->> categories
         (keep (fn [category]
                 (when-let [prompt-data (read-prompt-instructions
                                          config
                                          category)]
                   (let [metadata (:metadata prompt-data)
                         content (:content prompt-data)
                         description (or (get metadata "description")
                                         (format
                                           "Execution instructions for %s category"
                                           category))
                         ;; Reconstruct frontmatter if metadata exists
                         frontmatter (when metadata
                                       (let [lines (keep (fn [[k v]]
                                                           (when v
                                                             (str k ": " v)))
                                                         metadata)]
                                         (when (seq lines)
                                           (str "---\n"
                                                (str/join "\n" lines)
                                                "\n---\n"))))
                         ;; Include frontmatter in text if it exists
                         text (str frontmatter content)]
                     {:uri (str "prompt://category-" category)
                      :name (str "next-" category)
                      :description description
                      :mimeType "text/markdown"
                      :text text}))))
         vec)))

(defn builtin-category-prompt-resources
  "Generate MCP resources from builtin category prompts in mcp-tasks.task-prompts.

  Reads category prompt definitions from the vars in mcp-tasks.task-prompts
  namespace and generates MCP resource definitions.

  This function is used for native binaries where filesystem discovery isn't
  available.

  Returns a vector of resource maps."
  [_config]
  (require 'mcp-tasks.task-prompts)
  (let [ns (find-ns 'mcp-tasks.task-prompts)
        category-vars (->> (ns-publics ns)
                           vals
                           (filter (fn [v] (string? @v))))]
    (vec
      (for [v category-vars]
        (let [category (name (symbol v))
              prompt-content @v
              {:keys [metadata content]} (parse-frontmatter prompt-content)
              description (or (get metadata "description")
                              (format "Execution instructions for %s category" category))
              ;; Reconstruct frontmatter if metadata exists
              frontmatter (when metadata
                            (let [lines (keep (fn [[k v]]
                                                (when v
                                                  (str k ": " v)))
                                              metadata)]
                              (when (seq lines)
                                (str "---\n"
                                     (str/join "\n" lines)
                                     "\n---\n"))))
              ;; Include frontmatter in text if it exists
              text (str frontmatter content)]
          {:uri (str "prompt://category-" category)
           :name (str "next-" category)
           :description description
           :mimeType "text/markdown"
           :text text})))))
