(ns mcp-tasks.prompts
  "Task management prompts"
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [mcp-clj.mcp-server.prompts :as prompts])
  (:import
    (java.io
      File)))

(defn- parse-frontmatter
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
  [^File dir]
  (if (.exists dir)
    (->> (.listFiles dir)
         (filter #(and (.isFile ^File %)
                       (str/ends-with? (.getName ^File %) ".md")))
         (map #(str/replace (.getName ^File %) #"\.md$" ""))
         sort
         vec)
    []))

(defn discover-categories
  "Discover task categories by reading .mcp-tasks/prompts subdirectory.

  Takes base-dir which should be the project directory (defaults to
  current dir).  Returns a sorted vector of category names (filenames
  without .md extension) found in the prompts subdirectory."
  ([]
   (discover-categories (System/getProperty "user.dir")))
  ([base-dir]
   (let [prompts-dir (io/file base-dir ".mcp-tasks" "prompts")]
     (discover-prompt-files prompts-dir))))

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
  (slurp (io/resource "prompts/default-prompt-text.md")))

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
  "Read custom prompt instructions from .mcp-tasks/prompts/<category>.md.

  Takes base-dir (project directory) and category name.

  Returns a map with :metadata and :content keys if the file exists, or
  nil if it doesn't.

  The :metadata key contains parsed frontmatter (may be nil),
  and :content contains the prompt text with frontmatter stripped."
  [base-dir category]
  (let [prompt-file (io/file
                      base-dir
                      ".mcp-tasks"
                      "prompts"
                      (str category ".md"))]
    (when (.exists prompt-file)
      (parse-frontmatter (slurp prompt-file)))))

(defn create-prompts
  "Generate MCP prompts for task categories.

  Reads prompt instructions from .mcp-tasks/prompts/<category>.md files
  if they exist, otherwise uses default prompt text. Each prompt
  provides complete workflow including task lookup, execution
  instructions, and completion steps.

  Returns a vector of prompt maps suitable for registration with the MCP
  server."
  [config categories]
  (let [base-dir (:base-dir config)]
    (vec
      (for [category categories]
        (let [prompt-data (read-prompt-instructions base-dir category)
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
                                   :text prompt-text}}]}))))))

(defn category-descriptions
  "Get descriptions for all discovered categories.

  Returns a map of category name to description string. Categories
  without custom prompts or without description metadata will have a
  default description."
  ([]
   (category-descriptions (System/getProperty "user.dir")))
  ([base-dir]
   (let [categories (discover-categories base-dir)]
     (into {}
           (for [category categories]
             (let [prompt-data (read-prompt-instructions base-dir category)
                   metadata (:metadata prompt-data)
                   description (or (get metadata "description")
                                   (format "Tasks for %s category" category))]
               [category description]))))))

(defn prompts
  "Generate all task prompts by discovering categories.

  Accepts config parameter to conditionally include git instructions in prompts.
  Uses :base-dir from config to locate .mcp-tasks directory.

  Returns a map of prompt names to prompt definitions, suitable for registering
  with the MCP server."
  [config]
  (let [base-dir (or (:base-dir config) (System/getProperty "user.dir"))
        categories (discover-categories base-dir)
        prompt-list (create-prompts config categories)]
    (into {} (map (fn [p] [(:name p) p]) prompt-list))))

;; Story prompt utilities

(defn- list-builtin-story-prompts
  "List all built-in story prompts available in resources.

  Returns a sequence of prompt names (without .md extension) found in
  resources/story/prompts directory."
  []
  (when-let [prompts-url (io/resource "story/prompts")]
    (discover-prompt-files (io/file (.toURI prompts-url)))))

(defn get-story-prompt
  "Get a story prompt by name, with file override support.

  Checks for override file at `.mcp-tasks/story/prompts/<name>.md` first.
  If not found, falls back to built-in prompt from resources/story/prompts.

  Returns a map with:
  - :name - the prompt name
  - :description - from frontmatter
  - :content - the prompt text (with frontmatter stripped)

  Returns nil if prompt is not found in either location."
  [prompt-name]
  (let [override-file (io/file
                        ".mcp-tasks"
                        "story"
                        "prompts"
                        (str prompt-name ".md"))]
    (if (.exists override-file)
      (let [file-content (slurp override-file)
            {:keys [metadata content]} (parse-frontmatter file-content)]
        {:name prompt-name
         :description (get metadata "description")
         :content content})
      (when-let [resource-path (io/resource
                                 (str "story/prompts/" prompt-name ".md"))]
        (let [file-content (slurp resource-path)
              {:keys [metadata content]} (parse-frontmatter file-content)]
          {:name prompt-name
           :description (get metadata "description")
           :content content})))))

(defn list-story-prompts
  "List all available story prompts.

  Returns a sequence of maps with :name and :description for each available
  story prompt, including both built-in prompts and file overrides."
  []
  (let [builtin-prompts (for [prompt-name (list-builtin-story-prompts)
                              :let [prompt (get-story-prompt prompt-name)]
                              :when prompt]
                          {:name (:name prompt)
                           :description (:description prompt)})
        story-dir (io/file ".mcp-tasks" "story" "prompts")
        override-prompts (when (.exists story-dir)
                           (for [^File file (.listFiles story-dir)
                                 :when (and (.isFile file)
                                            (str/ends-with?
                                              (.getName file)
                                              ".md"))]
                             (let [name (str/replace
                                          (.getName file)
                                          #"\.md$" "")
                                   {:keys [metadata]} (parse-frontmatter
                                                        (slurp file))]
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

(defn story-prompts
  "Generate MCP prompts from story prompt vars in mcp-tasks.story-prompts.

  For execute-story-task prompt, tailors content based on
  config :story-branch-management?.

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
                  ;; Tailor execute-story-task content based on config
                  tailored-content
                  (cond-> content
                    (and (= prompt-name "execute-story-task")
                         (:story-branch-management? config))
                    (str
                      "\n\n"
                      (slurp
                        (io/resource
                          "story/prompts/story-branch-management.md"))))
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

  Discovers prompt files from resources/prompts/ directory, excluding:
  - Category instruction files (simple.md, medium.md, etc.)
  - Internal files (default-prompt-text.md)

  Returns a map of prompt names to prompt definitions."
  [config]
  (when-let [prompts-url (io/resource "prompts")]
    (let [prompts-dir (io/file (.toURI prompts-url))
          all-prompts (discover-prompt-files prompts-dir)
          ;; Get category names to filter out
          base-dir (or (:base-dir config) (System/getProperty "user.dir"))
          categories (set (discover-categories base-dir))
          ;; Filter out category instruction files and internal files
          excluded-names (conj categories "default-prompt-text")
          task-prompts (remove excluded-names all-prompts)
          prompts-data (for [prompt-name task-prompts
                             :let [resource-path (io/resource
                                                   (str
                                                     "prompts/"
                                                     prompt-name
                                                     ".md"))]
                             :when resource-path]
                         (let [file-content (slurp resource-path)
                               {:keys [metadata content]} (parse-frontmatter
                                                            file-content)
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
                                                             :text content}}]}
                                (seq arguments) (assoc
                                                  :arguments
                                                  arguments)))]))]
      (into {} prompts-data))))

(defn category-prompt-resources
  "Generate MCP resources for category prompt files.

  Discovers all available categories and creates a resource for each category's
  prompt file found in .mcp-tasks/prompts/<category>.md.

  Each resource has:
  - :uri \"prompt://category-<category>\"
  - :name \"<category> category instructions\"
  - :description from frontmatter or default
  - :mimeType \"text/markdown\"
  - :text content with frontmatter preserved

  Missing files are gracefully skipped (not included in result).

  Returns a vector of resource maps."
  [config]
  (let [base-dir (:base-dir config)
        categories (discover-categories base-dir)]
    (->> categories
         (keep (fn [category]
                 (when-let [prompt-data (read-prompt-instructions
                                          base-dir
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
                      :name (str category " category instructions")
                      :description description
                      :mimeType "text/markdown"
                      :text text}))))
         vec)))
