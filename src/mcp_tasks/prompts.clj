(ns mcp-tasks.prompts
  "Task management prompts"
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [mcp-clj.mcp-server.prompts :as prompts]))

(defn- parse-frontmatter
  "Parse simple 'field: value' frontmatter from markdown text.

  Expects frontmatter delimited by '---' at start and end.
  Format example:
    ---
    description: Task description
    author: John Doe
    ---
    Content here...

  Returns a map with :metadata (parsed key-value pairs) and :content (remaining text).
  If no valid frontmatter is found, returns {:metadata nil :content <original-text>}."
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
                           (if-let [[_ k v] (re-matches #"([^:]+):\s*(.*)" line)]
                             (assoc acc (str/trim k) (str/trim v))
                             acc))
                         {}
                         metadata-lines)
              content (str/join "\n" content-lines)]
          {:metadata (when (seq metadata) metadata)
           :content content})))))

(defn discover-categories
  "Discover task categories by reading filenames from .mcp-tasks subdirectories.

  Takes base-dir which should be the project directory (defaults to current dir).
  Returns a sorted vector of unique category names (filenames without .md extension)
  found across the tasks, complete, and prompts subdirectories."
  ([]
   (discover-categories (System/getProperty "user.dir")))
  ([base-dir]
   (let [mcp-tasks-dir (io/file base-dir ".mcp-tasks")
         subdirs ["tasks" "complete" "prompts"]
         md-files (for [subdir subdirs
                        :let [dir (io/file mcp-tasks-dir subdir)]
                        :when (.exists dir)
                        file (.listFiles dir)
                        :when (and (.isFile file)
                                   (str/ends-with? (.getName file) ".md"))]
                    (.getName file))
         categories (into (sorted-set)
                          (map #(str/replace % #"\.md$" ""))
                          md-files)]
     (vec categories))))

(defn- read-task-prompt-text
  "Generate prompt text for reading the next task from a category.
  Config parameter included for API consistency but not currently used."
  [_config category]
  (format "- Read the file .mcp-tasks/tasks/%s.md

- Find the first incomplete task (marked with `- [ ]`) You can use the
  `next-task` tool to retrieve the next task without executing it.
- Show the task description
"
          category))

(defn- default-prompt-text
  "Generate default execution instructions for a category."
  []
  (slurp (io/resource "prompts/default-prompt-text.md")))

(defn- complete-task-prompt-text
  "Generate prompt text for completing and tracking a task.
  Conditionally includes git commit instructions based on config :use-git? value."
  [config category]
  (let [base-text (format "- Move the completed task to .mcp-tasks/complete/%s.md (append to
  end, mark as complete with `- [x]`). You can use the `complete-task` tool to
  mark a task as complete and move it to the completed archive.

- Remove the task from .mcp-tasks/tasks/%s.md (if removing the last task, leave
  the file empty rather than deleting it)
"
                          category category)
        git-text "\n- Commit the task tracking changes in the .mcp-tasks git repository\n"]
    (if (:use-git? config)
      (str base-text git-text)
      base-text)))

(defn- read-prompt-instructions
  "Read custom prompt instructions from .mcp-tasks/prompts/<category>.md if it exists.

  Returns a map with :metadata and :content keys if the file exists, or nil if it doesn't.
  The :metadata key contains parsed frontmatter (may be nil), and :content contains
  the prompt text with frontmatter stripped."
  [category]
  (let [prompt-file (io/file ".mcp-tasks" "prompts" (str category ".md"))]
    (when (.exists prompt-file)
      (parse-frontmatter (slurp prompt-file)))))

(defn create-prompts
  "Create MCP prompts for a sequence of categories.

  For each category, creates a prompt that:
  - Reads tasks from .mcp-tasks/tasks/<category>.md
  - Uses instructions from .mcp-tasks/prompts/<category>.md if available,
    otherwise uses default instructions based on next-simple
  - Moves completed tasks to .mcp-tasks/complete/<category>.md
  - Conditionally includes git commit instructions based on config :use-git? value
  - Includes an :arguments key with category argument specification

  The prompt text is automatically composed from three parts:
  1. read-task-prompt-text - instructions for reading the next task
  2. custom instructions or default-prompt-text - category-specific execution logic
  3. complete-task-prompt-text - instructions for committing and tracking completion

  If custom prompt files contain frontmatter with a 'description' field, that will be
  used for the prompt's :description. Otherwise, a default description is generated.

  Returns a vector of prompt maps suitable for registration with the MCP server."
  [config categories]
  (vec
    (for [category categories]
      (let [prompt-data            (read-prompt-instructions category)
            metadata               (:metadata prompt-data)
            custom-content         (:content prompt-data)
            execution-instructions (or custom-content (default-prompt-text))
            prompt-text            (str "Please complete the next " category " task following these steps:\n\n"
                                        (read-task-prompt-text config category)
                                        execution-instructions
                                        (complete-task-prompt-text config category))
            description            (or (get metadata "description")
                                       (format "Process the next incomplete task from .mcp-tasks/tasks/%s.md" category))]
        (prompts/valid-prompt?
          {:name        (str "next-" category)
           :description description
           :arguments   [{:name        "category"
                          :description (format "The task category to execute (always '%s' for this prompt)" category)
                          :required    true}]
           :messages    [{:role    "user"
                          :content {:type "text"
                                    :text prompt-text}}]})))))

(defn category-descriptions
  "Get descriptions for all discovered categories.

  Returns a map of category name to description string. Categories without
  custom prompts or without description metadata will have a default description."
  ([]
   (category-descriptions (System/getProperty "user.dir")))
  ([base-dir]
   (let [categories (discover-categories base-dir)]
     (into {}
           (for [category categories]
             (let [prompt-data (read-prompt-instructions category)
                   metadata (:metadata prompt-data)
                   description (or (get metadata "description")
                                   (format "Tasks for %s category" category))]
               [category description]))))))

(defn prompts
  "Generate all task prompts by discovering categories and creating prompts for them.

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
    (let [prompts-file (io/file (.toURI prompts-url))]
      (when (.exists prompts-file)
        (->> (.listFiles prompts-file)
             (filter #(and (.isFile %) (str/ends-with? (.getName %) ".md")))
             (map #(str/replace (.getName %) #"\.md$" "")))))))

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
  (let [override-file (io/file ".mcp-tasks" "story" "prompts" (str prompt-name ".md"))]
    (if (.exists override-file)
      (let [file-content (slurp override-file)
            {:keys [metadata content]} (parse-frontmatter file-content)]
        {:name prompt-name
         :description (get metadata "description")
         :content content})
      (when-let [resource-path (io/resource (str "story/prompts/" prompt-name ".md"))]
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
                           (for [file (.listFiles story-dir)
                                 :when (and (.isFile file)
                                            (str/ends-with? (.getName file) ".md"))]
                             (let [name (str/replace (.getName file) #"\.md$" "")
                                   {:keys [metadata]} (parse-frontmatter (slurp file))]
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

  Returns a vector of argument maps with :name, :description, and :required keys."
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
                                is-variadic (format "Optional additional %s (variadic)" clean-name)
                                is-required (format "The %s (required)" (str/replace clean-name "-" " "))
                                :else (format "Optional %s" (str/replace clean-name "-" " ")))]]
        {:name        clean-name
         :description description
         :required    is-required}))))

(defn story-prompts
  "Generate MCP prompts from story prompt vars in mcp-tasks.story-prompts namespace.

  Returns a map of prompt names to prompt definitions, suitable for registering
  with the MCP server."
  []
  (require 'mcp-tasks.story-prompts)
  (let [ns          (find-ns 'mcp-tasks.story-prompts)
        prompt-vars (->> (ns-publics ns)
                         vals
                         (filter (fn [v] (string? @v))))]
    (into {}
          (for [v prompt-vars]
            (let [prompt-name                (name (symbol v))
                  prompt-content             @v
                  {:keys [metadata content]} (parse-frontmatter prompt-content)
                  description                (or (get metadata "description")
                                                 (:doc (meta v))
                                                 (format "Story prompt: %s" prompt-name))
                  arguments                  (parse-argument-hint metadata)]
              [prompt-name
               (prompts/valid-prompt?
                 (cond-> {:name        prompt-name
                          :description description
                          :messages    [{:role    "user"
                                         :content {:type "text"
                                                   :text content}}]}
                   (seq arguments) (assoc :arguments arguments)))])))))
