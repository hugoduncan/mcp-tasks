(ns mcp-tasks.cli.parse
  "Argument parsing for the CLI.
  
  Uses babashka.cli to parse command-line arguments."
  (:require
    [babashka.cli :as cli]
    [clojure.data.json :as json]
    [clojure.string :as str]))

;; Help Text

(def help-text
  "Help text for the CLI."
  "mcp-tasks - Task management from the command line

USAGE:
  clojure -M:cli <command> [options]

COMMANDS:
  list      List tasks with optional filters
  show      Display a single task by ID
  add       Create a new task
  complete  Mark a task as complete
  update    Update task fields
  delete    Delete a task

GLOBAL OPTIONS:
  --config-path <path>  Path to directory containing .mcp-tasks.edn (default: .)
  --format <format>     Output format: edn, json, human (default: edn)
  --help                Show this help message

Run 'clojure -M:cli <command> --help' for command-specific options.

EXAMPLES:
  clojure -M:cli list --status open --format human
  clojure -M:cli show --task-id 42
  clojure -M:cli add --category simple --title \"Fix bug\"
  clojure -M:cli complete --task-id 42 --comment \"Fixed\"
  clojure -M:cli update --task-id 42 --status in-progress
  clojure -M:cli delete --task-id 42")

(def list-help
  "Help text for the list command."
  "List tasks with optional filters

USAGE:
  clojure -M:cli list [options]

OPTIONS:
  --status, -s <status>         Filter by status (open, closed, in-progress, blocked)
  --category, -c <name>         Filter by category name
  --type, -t <type>             Filter by type (task, bug, feature, story, chore)
  --parent-id, -p <id>          Filter by parent task ID
  --task-id <id>                Filter by specific task ID
  --title-pattern, --title <pattern>  Filter by title pattern (regex or substring)
  --limit <n>                   Maximum tasks to return (default: 5)
  --unique                      Enforce 0 or 1 match (error if >1)
  --format <format>             Output format: edn, json, human (default: edn)

EXAMPLES:
  clojure -M:cli list --status open --format human
  clojure -M:cli list --category simple --limit 10
  clojure -M:cli list --parent-id 31 --status open")

(def show-help
  "Help text for the show command."
  "Display a single task by ID

USAGE:
  clojure -M:cli show --task-id <id> [options]

OPTIONS:
  --task-id, --id <id>  Task ID to display (required)
  --format <format>     Output format: edn, json, human (default: edn)

EXAMPLES:
  clojure -M:cli show --task-id 42
  clojure -M:cli show --id 42 --format human")

(def add-help
  "Help text for the add command."
  "Create a new task

USAGE:
  clojure -M:cli add --category <name> --title <title> [options]

OPTIONS:
  --category, -c <name>     Task category (required, e.g., simple, medium, large)
  --title, -t <title>       Task title (required)
  --description, -d <text>  Task description
  --type <type>             Task type (default: task)
                            Options: task, bug, feature, story, chore
  --parent-id, -p <id>      Parent task ID (for child tasks)
  --prepend                 Add task at beginning instead of end
  --format <format>         Output format: edn, json, human (default: edn)

EXAMPLES:
  clojure -M:cli add --category simple --title \"Fix parser bug\"
  clojure -M:cli add -c medium -t \"Add auth\" -d \"Implement JWT auth\"
  clojure -M:cli add --category simple --title \"Subtask\" --parent-id 31")

(def complete-help
  "Help text for the complete command."
  "Mark a task as complete

USAGE:
  clojure -M:cli complete (--task-id <id> | --title <pattern>) [options]

OPTIONS:
  --task-id, --id <id>          Task ID to complete
  --title, -t <pattern>         Task title pattern (alternative to task-id)
  --category, -c <name>         Task category (for verification)
  --completion-comment, --comment <text>  Optional completion comment
  --format <format>             Output format: edn, json, human (default: edn)

NOTE: At least one of --task-id or --title must be provided.

EXAMPLES:
  clojure -M:cli complete --task-id 42
  clojure -M:cli complete --title \"Fix bug\" --comment \"Fixed via PR #123\"
  clojure -M:cli complete --id 42 --category simple")

(def update-help
  "Help text for the update command."
  "Update task fields

USAGE:
  clojure -M:cli update --task-id <id> [options]

OPTIONS:
  --task-id, --id <id>      Task ID to update (required)
  --title, -t <title>       New task title
  --description, -d <text>  New task description
  --design <text>           New task design notes
  --status, -s <status>     New status (open, closed, in-progress, blocked)
  --category, -c <name>     New task category
  --type <type>             New task type (task, bug, feature, story, chore)
  --parent-id, -p <id>      New parent task ID (pass empty string to remove)
  --meta <json>             New metadata as JSON object
  --relations <json>        New relations as JSON array
  --format <format>         Output format: edn, json, human (default: edn)

EXAMPLES:
  clojure -M:cli update --task-id 42 --status in-progress
  clojure -M:cli update --id 42 --title \"New title\" --description \"New desc\"
  clojure -M:cli update --task-id 42 --meta '{\"priority\":\"high\"}'")

(def delete-help
  "Help text for the delete command."
  "Delete a task

USAGE:
  clojure -M:cli delete (--task-id <id> | --title-pattern <pattern>) [options]

OPTIONS:
  --task-id, --id <id>          Task ID to delete
  --title-pattern, --title <pattern>  Title pattern to match (alternative to task-id)
  --format <format>             Output format: edn, json, human (default: edn)

NOTE: At least one of --task-id or --title-pattern must be provided.

EXAMPLES:
  clojure -M:cli delete --task-id 42
  clojure -M:cli delete --title-pattern \"old-task\"
  clojure -M:cli delete --id 42 --format human")

;; Type Coercion Functions

(defn coerce-json-map
  "Parse JSON string to Clojure map for :meta field.
  
  Returns the parsed map or an error map."
  [s]
  (try
    (let [parsed (json/read-str s :key-fn keyword)]
      (if (map? parsed)
        parsed
        {:error "Expected JSON object for --meta"
         :provided s}))
    (catch Exception e
      {:error (str "Invalid JSON for --meta: " (.getMessage e))
       :provided s})))

(defn coerce-json-array
  "Parse JSON string to Clojure vector for :relations field.
  
  Returns the parsed vector or an error map."
  [s]
  (try
    (let [parsed (json/read-str s :key-fn keyword)]
      (if (vector? parsed)
        parsed
        {:error "Expected JSON array for --relations"
         :provided s}))
    (catch Exception e
      {:error (str "Invalid JSON for --relations: " (.getMessage e))
       :provided s})))

;; Validation Functions

(defn validate-at-least-one
  "Validate that at least one of the specified keys is present in the parsed map.
  
  Returns {:valid? true} or {:valid? false :error \"...\" :details {...}}"
  [parsed-map required-keys field-names]
  (let [present-keys (filter #(contains? parsed-map %) required-keys)]
    (if (seq present-keys)
      {:valid? true}
      {:valid? false
       :error (str "At least one of " (str/join ", " field-names) " must be provided")
       :details {:required-one-of required-keys}})))

;; Command Spec Maps

(def list-spec
  "Spec for the list command."
  {:status {:coerce :keyword
            :alias :s
            :desc "Filter by status (open, closed, in-progress, blocked)"}
   :category {:alias :c
              :desc "Filter by category name"}
   :type {:coerce :keyword
          :alias :t
          :desc "Filter by type (task, bug, feature, story, chore)"}
   :parent-id {:coerce :long
               :alias :p
               :desc "Filter by parent task ID"}
   :task-id {:coerce :long
             :desc "Filter by specific task ID"}
   :title-pattern {:alias :title
                   :desc "Filter by title pattern (regex or substring)"}
   :limit {:coerce :long
           :default 5
           :desc "Maximum number of tasks to return"}
   :unique {:coerce :boolean
            :desc "Enforce that 0 or 1 task matches (error if >1)"}
   :format {:coerce :keyword
            :desc "Output format (edn, json, human)"}})

(def show-spec
  "Spec for the show command."
  {:task-id {:coerce :long
             :alias :id
             :desc "Task ID to display"}
   :format {:coerce :keyword
            :desc "Output format (edn, json, human)"}})

(def add-spec
  "Spec for the add command."
  {:category {:alias :c
              :desc "Task category (e.g., simple, medium, large)"}
   :title {:alias :t
           :desc "Task title"}
   :description {:alias :d
                 :desc "Task description"}
   :type {:coerce :keyword
          :default :task
          :desc "Task type (task, bug, feature, story, chore)"}
   :parent-id {:coerce :long
               :alias :p
               :desc "Parent task ID (for child tasks)"}
   :prepend {:coerce :boolean
             :desc "Add task at beginning instead of end"}
   :format {:coerce :keyword
            :desc "Output format (edn, json, human)"}})

(def complete-spec
  "Spec for the complete command."
  {:task-id {:coerce :long
             :alias :id
             :desc "Task ID to complete"}
   :title {:alias :t
           :desc "Task title (alternative to task-id)"}
   :category {:alias :c
              :desc "Task category (for verification)"}
   :completion-comment {:alias :comment
                        :desc "Optional completion comment"}
   :format {:coerce :keyword
            :desc "Output format (edn, json, human)"}})

(def update-spec
  "Spec for the update command."
  {:task-id {:coerce :long
             :alias :id
             :desc "Task ID to update"}
   :title {:alias :t
           :desc "New task title"}
   :description {:alias :d
                 :desc "New task description"}
   :design {:desc "New task design notes"}
   :status {:coerce :keyword
            :alias :s
            :desc "New status (open, closed, in-progress, blocked)"}
   :category {:alias :c
              :desc "New task category"}
   :type {:coerce :keyword
          :desc "New task type (task, bug, feature, story, chore)"}
   :parent-id {:coerce :long
               :alias :p
               :desc "New parent task ID (or nil to remove)"}
   :meta {:desc "New metadata as JSON object"}
   :relations {:desc "New relations as JSON array"}
   :format {:coerce :keyword
            :desc "Output format (edn, json, human)"}})

(def delete-spec
  "Spec for the delete command."
  {:task-id {:coerce :long
             :alias :id
             :desc "Task ID to delete"}
   :title-pattern {:alias :title
                   :desc "Title pattern to match (alternative to task-id)"}
   :format {:coerce :keyword
            :desc "Output format (edn, json, human)"}})

;; Parse Functions

(defn parse-list
  "Parse arguments for the list command.
  
  Returns parsed options map or error map with :error key."
  [args]
  (try
    (let [raw-parsed (cli/parse-opts args {:spec list-spec})
          parsed (-> raw-parsed
                     (dissoc :s :c :t :p :title)
                     (cond-> (:s raw-parsed) (assoc :status (:s raw-parsed)))
                     (cond-> (:c raw-parsed) (assoc :category (:c raw-parsed)))
                     (cond-> (:t raw-parsed) (assoc :type (:t raw-parsed)))
                     (cond-> (contains? raw-parsed :p) (assoc :parent-id (:p raw-parsed)))
                     (cond-> (:title raw-parsed) (assoc :title-pattern (:title raw-parsed))))]
      parsed)
    (catch Exception e
      {:error (str "Failed to parse list arguments: " (.getMessage e))
       :details {:args args}})))

(defn parse-show
  "Parse arguments for the show command.
  
  Returns parsed options map or error map with :error key."
  [args]
  (try
    (let [parsed (cli/parse-opts args {:spec show-spec})
          task-id (or (:task-id parsed) (:id parsed))]
      (if-not task-id
        {:error "Required option: --task-id (or --id)"
         :details {:args args}}
        (-> parsed
            (dissoc :id)
            (assoc :task-id task-id))))
    (catch Exception e
      {:error (str "Failed to parse show arguments: " (.getMessage e))
       :details {:args args}})))

(defn parse-add
  "Parse arguments for the add command.
  
  Returns parsed options map or error map with :error key."
  [args]
  (try
    (let [raw-parsed (cli/parse-opts args {:spec add-spec})
          category (or (:category raw-parsed) (:c raw-parsed))
          title (or (:title raw-parsed) (:t raw-parsed))
          parsed (-> raw-parsed
                     (dissoc :c :t :d :p)
                     (cond-> category (assoc :category category))
                     (cond-> title (assoc :title title))
                     (cond-> (:d raw-parsed) (assoc :description (:d raw-parsed)))
                     (cond-> (contains? raw-parsed :p) (assoc :parent-id (:p raw-parsed))))]
      (cond
        (not category)
        {:error "Required option: --category (or -c)"
         :details {:args args}}

        (not title)
        {:error "Required option: --title (or -t)"
         :details {:args args}}

        :else
        parsed))
    (catch Exception e
      {:error (str "Failed to parse add arguments: " (.getMessage e))
       :details {:args args}})))

(defn parse-complete
  "Parse arguments for the complete command.
  
  Validates that at least one of task-id or title is provided.
  Returns parsed options map or error map with :error key."
  [args]
  (try
    (let [raw-parsed (cli/parse-opts args {:spec complete-spec})
          task-id (or (:task-id raw-parsed) (:id raw-parsed))
          parsed (-> raw-parsed
                     (dissoc :id :t :c :comment)
                     (cond-> task-id (assoc :task-id task-id))
                     (cond-> (:t raw-parsed) (assoc :title (:t raw-parsed)))
                     (cond-> (:c raw-parsed) (assoc :category (:c raw-parsed)))
                     (cond-> (:comment raw-parsed) (assoc :completion-comment (:comment raw-parsed))))
          validation (validate-at-least-one parsed [:task-id :title] ["--task-id" "--title"])]
      (if (:valid? validation)
        parsed
        (dissoc validation :valid?)))
    (catch Exception e
      {:error (str "Failed to parse complete arguments: " (.getMessage e))
       :details {:args args}})))

(defn parse-update
  "Parse arguments for the update command.
  
  Handles JSON parsing for :meta and :relations fields.
  Returns parsed options map or error map with :error key."
  [args]
  (try
    (let [raw-parsed (cli/parse-opts args {:spec update-spec})
          task-id (or (:task-id raw-parsed) (:id raw-parsed))
          parsed (-> raw-parsed
                     (dissoc :id :t :d :s :c :p)
                     (cond-> task-id (assoc :task-id task-id))
                     (cond-> (:t raw-parsed) (assoc :title (:t raw-parsed)))
                     (cond-> (:d raw-parsed) (assoc :description (:d raw-parsed)))
                     (cond-> (:s raw-parsed) (assoc :status (:s raw-parsed)))
                     (cond-> (:c raw-parsed) (assoc :category (:c raw-parsed)))
                     (cond-> (contains? raw-parsed :p) (assoc :parent-id (:p raw-parsed))))]
      (if-not task-id
        {:error "Required option: --task-id (or --id)"
         :details {:args args}}
        ;; Parse :meta if provided
        (if-let [meta-str (:meta parsed)]
          (let [meta-result (coerce-json-map meta-str)]
            (if (:error meta-result)
              meta-result
              (let [parsed-with-meta (assoc parsed :meta meta-result)]
                ;; Parse :relations if provided
                (if-let [relations-str (:relations parsed-with-meta)]
                  (let [relations-result (coerce-json-array relations-str)]
                    (if (:error relations-result)
                      relations-result
                      (assoc parsed-with-meta :relations relations-result)))
                  parsed-with-meta))))
          ;; No :meta, check :relations
          (if-let [relations-str (:relations parsed)]
            (let [relations-result (coerce-json-array relations-str)]
              (if (:error relations-result)
                relations-result
                (assoc parsed :relations relations-result)))
            parsed))))
    (catch Exception e
      {:error (str "Failed to parse update arguments: " (.getMessage e))
       :details {:args args}})))

(defn parse-delete
  "Parse arguments for the delete command.
  
  Validates that at least one of task-id or title-pattern is provided.
  Returns parsed options map or error map with :error key."
  [args]
  (try
    (let [raw-parsed (cli/parse-opts args {:spec delete-spec})
          task-id (or (:task-id raw-parsed) (:id raw-parsed))
          parsed (-> raw-parsed
                     (dissoc :id :title)
                     (cond-> task-id (assoc :task-id task-id))
                     (cond-> (:title raw-parsed) (assoc :title-pattern (:title raw-parsed))))
          validation (validate-at-least-one parsed [:task-id :title-pattern] ["--task-id" "--title-pattern"])]
      (if (:valid? validation)
        parsed
        (dissoc validation :valid?)))
    (catch Exception e
      {:error (str "Failed to parse delete arguments: " (.getMessage e))
       :details {:args args}})))
