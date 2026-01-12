(ns mcp-tasks.cli.parse
  "Argument parsing for the CLI.

  Uses babashka.cli to parse command-line arguments."
  (:require
    [babashka.cli :as cli]
    [cheshire.core :as json]
    [clojure.string :as str]))

;; Help Text

(def help-text
  "Help text for the CLI."
  "mcp-tasks - Task management from the command line

USAGE:
  clojure -M:cli <command> [options]

COMMANDS:
  list         List tasks with optional filters
  show         Display a single task by ID
  add          Create a new task
  complete     Mark a task as complete
  update       Update task fields
  delete       Delete a task
  reopen       Reopen a closed task
  why-blocked  Show why a task is blocked
  prompts      Manage prompt templates

GLOBAL OPTIONS:
  --format <format>     Output format: edn, json, human (default: edn)
  --help                Show this help message

CONFIG DISCOVERY:
  Configuration is discovered automatically - no --config-path option needed.

  The CLI searches for .mcp-tasks.edn starting from the current directory
  and traversing up the directory tree until found or reaching the filesystem
  root. This allows you to invoke the CLI from any subdirectory within your
  project.

  Example:
    # Your project structure:
    # /project/.mcp-tasks.edn
    # /project/src/
    # /project/test/

    # You can run from any directory:
    cd /project/src
    clojure -M:cli list    # Finds /project/.mcp-tasks.edn automatically

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
  --status, -s <status>         Filter by status (open, closed, in-progress, blocked, any)
                                Use 'any' to list all tasks regardless of status
  --category, -c <name>         Filter by category name
  --type, -t <type>             Filter by type (task, bug, feature, story, chore)
  --parent-id, -p <id>          Filter by parent task ID
  --task-id <id>                Filter by specific task ID
  --title-pattern, --title <pattern>  Filter by title pattern (regex or substring)
  --blocked <true|false>        Filter by dependency-blocked status (based on :blocked-by relations)
  --show-blocking               Append blocking details section showing which task IDs block each task
  --limit <n>                   Maximum tasks to return (default: 30)
  --unique                      Enforce 0 or 1 match (error if >1)
  --format <format>             Output format: edn, json, human (default: edn)

EXAMPLES:
  clojure -M:cli list --status open --format human
  clojure -M:cli list --status any --category simple
  clojure -M:cli list --parent-id 31 --status open
  clojure -M:cli list --blocked true --format human
  clojure -M:cli list --blocked false --show-blocking")

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
  --session-events <json>   Session events as JSON (object or array)
  --shared-context, -C <text>  Append entry to task's shared context
  --code-reviewed <timestamp>  ISO-8601 timestamp when code review completed
  --pr-num <integer>        GitHub pull request number
  --format <format>         Output format: edn, json, human (default: edn)

EXAMPLES:
  clojure -M:cli update --task-id 42 --status in-progress
  clojure -M:cli update --id 42 --title \"New title\" --description \"New desc\"
  clojure -M:cli update --task-id 42 --meta '{\"priority\":\"high\"}'
  clojure -M:cli update --task-id 42 -C \"Key discovery from this task\"")

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

(def reopen-help
  "Help text for the reopen command."
  "Reopen a closed task

USAGE:
  clojure -M:cli reopen (--task-id <id> | --title-pattern <pattern>) [options]

OPTIONS:
  --task-id, --id <id>          Task ID to reopen
  --title-pattern, --title <pattern>  Title pattern to match (alternative to task-id)
  --format <format>             Output format: edn, json, human (default: edn)

NOTE: At least one of --task-id or --title-pattern must be provided.

EXAMPLES:
  clojure -M:cli reopen --task-id 42
  clojure -M:cli reopen --title \"Fix bug\"
  clojure -M:cli reopen --id 42 --format human")

(def why-blocked-help
  "Help text for the why-blocked command."
  "Show why a task is blocked

USAGE:
  clojure -M:cli why-blocked --task-id <id> [options]

OPTIONS:
  --task-id, --id <id>  Task ID to check (required)
  --format <format>     Output format: edn, json, human (default: edn)

EXAMPLES:
  clojure -M:cli why-blocked --task-id 42
  clojure -M:cli why-blocked --id 42 --format human")

(def prompts-help
  "Help text for the prompts command."
  "mcp-tasks prompts - Manage prompt templates

USAGE:
  mcp-tasks prompts <subcommand> [options]

SUBCOMMANDS:
  list       List all available built-in prompts
  customize  Copy prompts to local directories for customization
  show       Display resolved content of a specific prompt
  install    Generate Claude Code slash commands from prompts

Run 'mcp-tasks prompts <subcommand> --help' for subcommand-specific options.")

(def prompts-list-help
  "Help text for the prompts list subcommand."
  "mcp-tasks prompts list - List all available built-in prompts

USAGE:
  mcp-tasks prompts list [options]

Displays all built-in prompts with their names, types, and descriptions.
Category prompts define execution workflows for tasks.
Workflow prompts define operations like refining tasks or creating stories.

OPTIONS:
  --format, -f <format>  Output format: human, json, edn (default: human)
  --help, -h             Show this help message

EXAMPLES:
  mcp-tasks prompts list
  mcp-tasks prompts list --format json")

(def prompts-customize-help
  "Help text for the prompts customize subcommand."
  "mcp-tasks prompts customize - Copy prompts to local directories for customization

USAGE:
  mcp-tasks prompts customize <prompt1> [prompt2] [prompt3]... [options]

Copy one or more built-in prompts to local override directories for customization.
Category prompts are copied to .mcp-tasks/category-prompts/
Workflow prompts are copied to .mcp-tasks/prompt-overrides/

OPTIONS:
  --format, -f <format>  Output format: human, json, edn (default: human)
  --help, -h             Show this help message

EXAMPLES:
  mcp-tasks prompts customize simple
  mcp-tasks prompts customize simple medium execute-task
  mcp-tasks prompts customize simple --format json")

(def prompts-show-help
  "Help text for the prompts show subcommand."
  "mcp-tasks prompts show - Display effective content of a specific prompt

USAGE:
  mcp-tasks prompts show <prompt-name> [options]

Displays the effective (resolved) content of a prompt, following override precedence:
- Category prompts: .mcp-tasks/category-prompts/<name>.md → built-in
- Workflow prompts: .mcp-tasks/prompt-overrides/<name>.md → built-in

OPTIONS:
  --format, -f <format>  Output format: human, json, edn (default: human)
  --help, -h             Show this help message

EXAMPLES:
  mcp-tasks prompts show simple
  mcp-tasks prompts show execute-task
  mcp-tasks prompts show simple --format json")

(def prompts-install-help
  "Help text for the prompts install subcommand."
  "mcp-tasks prompts install - Generate Claude Code slash commands from prompts

USAGE:
  mcp-tasks prompts install [target-directory] [options]

Generate Claude Code slash command files (.md) from available prompts.
Files are written to the target directory with names: mcp-tasks-<prompt-name>.md

TARGET DIRECTORY:
  Defaults to .claude/commands/ if not specified.

OPTIONS:
  --format, -f <format>  Output format: human, json, edn (default: human)
  --help, -h             Show this help message

EXAMPLES:
  mcp-tasks prompts install
  mcp-tasks prompts install .claude/commands/
  mcp-tasks prompts install my-commands/ --format json")

;; Type Coercion Functions

(defn coerce-json-map
  "Parse JSON string to Clojure map for :meta field.

  Returns the parsed map or an error map."
  [s]
  (try
    (let [parsed (json/parse-string s keyword)]
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
    (let [parsed (json/parse-string s keyword)]
      (if (sequential? parsed)
        (vec parsed)
        {:error "Expected JSON array for --relations"
         :provided s}))
    (catch Exception e
      {:error (str "Invalid JSON for --relations: " (.getMessage e))
       :provided s})))

(defn coerce-session-events
  "Parse JSON string to session events (map or vector) for :session-events field.

  Accepts either a single event object or an array of events.
  Returns the parsed data or an error map."
  [s]
  (try
    (let [parsed (json/parse-string s keyword)]
      (cond
        (map? parsed) parsed
        (sequential? parsed) (vec parsed)
        :else {:error "Expected JSON object or array for --session-events"
               :provided s}))
    (catch Exception e
      {:error (str "Invalid JSON for --session-events: " (.getMessage e))
       :provided s})))

(defn coerce-parent-id
  "Coerce parent-id value, handling 'null' string as nil.

  Accepts:
  - String \"null\" -> nil
  - Numeric string -> parsed long
  - Number -> long

  Returns the coerced value or throws exception on invalid input."
  [v]
  (cond
    (nil? v) nil
    (= "null" v) nil
    (string? v) (Long/parseLong v)
    (number? v) (long v)
    :else (throw (ex-info "Invalid parent-id value" {:value v}))))

(defn resolve-alias
  "Resolve an aliased key from parsed map.

  Returns the value of primary-key if present, otherwise tries alias-key.
  If both are absent, returns nil."
  [parsed-map primary-key alias-key]
  (or (get parsed-map primary-key)
      (get parsed-map alias-key)))

;; Error Handling Functions

(defn format-unknown-option-error
  "Format babashka.cli unknown option error into user-friendly message.

  Converts ':option-name' to '--option-name' and adds help suggestion."
  [cli-error-message]
  (if-let [option-match (re-find #"Unknown option: :(\S+)" cli-error-message)]
    (str "Unknown option: --" (second option-match) ". Use --help to see valid options.")
    cli-error-message))

(defn get-allowed-keys
  "Extract all allowed keys from a spec, including aliases.

  Returns a set of keywords representing all valid option keys."
  [spec]
  (reduce
    (fn [acc [k v]]
      (if-let [alias (:alias v)]
        (conj acc k alias)
        (conj acc k)))
    #{}
    spec))

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
       :metadata {:required-one-of required-keys}})))

(defn validate-format
  "Validate that the format is one of the allowed values.

  Returns {:valid? true} or {:valid? false :error \"...\" :details {...}}"
  [parsed-map]
  (if-let [fmt (:format parsed-map)]
    (if (#{:edn :json :human} fmt)
      {:valid? true}
      {:valid? false
       :error (str "Invalid format: " (name fmt) ". Must be one of: edn, json, human")
       :metadata {:provided fmt
                  :allowed #{:edn :json :human}}})
    {:valid? true}))

(defn validate-status
  "Validate that status is one of the allowed values.

  Returns {:valid? true} or {:valid? false :error \"...\" :details {...}}"
  [parsed-map]
  (if-let [status (:status parsed-map)]
    (if (#{:open :closed :in-progress :blocked :any} status)
      {:valid? true}
      {:valid? false
       :error (str "Invalid status value '" (name status) "'. Must be one of: open, closed, in-progress, blocked, any")
       :metadata {:provided status
                  :allowed #{:open :closed :in-progress :blocked :any}}})
    {:valid? true}))

;; Command Spec Maps

(def list-spec
  "Spec for the list command.

  Validates and coerces arguments for querying tasks with filters.

  Coercion rules:
  - :status -> keyword (open, closed, in-progress, blocked, any)
  - :type -> keyword (task, bug, feature, story, chore)
  - :parent-id -> long integer
  - :task-id -> long integer
  - :blocked -> boolean
  - :show-blocking -> boolean
  - :limit -> long integer (default: 30)
  - :unique -> boolean
  - :format -> keyword (edn, json, human)

  Validation:
  - Post-parse validation checks format is valid (edn, json, human)"
  {:status {:coerce :keyword
            :alias :s
            :desc "Filter by status (open, closed, in-progress, blocked, any)"}
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
   :blocked {:coerce :boolean
             :desc "Filter by blocked status (true for blocked, false for unblocked)"}
   :show-blocking {:coerce :boolean
                   :desc "Show which tasks are blocking each listed task"}
   :limit {:coerce :long
           :default 30
           :desc "Maximum number of tasks to return"}
   :unique {:coerce :boolean
            :desc "Enforce that 0 or 1 task matches (error if >1)"}
   :format {:coerce :keyword
            :desc "Output format (edn, json, human)"}})

(def show-spec
  "Spec for the show command.

  Validates and coerces arguments for displaying a single task.

  Coercion rules:
  - :task-id -> long integer
  - :format -> keyword (edn, json, human)

  Validation:
  - Post-parse validation checks format is valid (edn, json, human)
  - Requires :task-id to be present"
  {:task-id {:coerce :long
             :alias :id
             :desc "Task ID to display"}
   :format {:coerce :keyword
            :desc "Output format (edn, json, human)"}})

(def add-spec
  "Spec for the add command.

  Validates and coerces arguments for creating new tasks.

  Coercion rules:
  - :type -> keyword (task, bug, feature, story, chore), default: :task
  - :parent-id -> long integer
  - :prepend -> boolean
  - :format -> keyword (edn, json, human)

  Validation:
  - Post-parse validation checks format is valid (edn, json, human)
  - Requires both :category and :title to be present"
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
  "Spec for the complete command.

  Validates and coerces arguments for marking tasks complete.

  Coercion rules:
  - :task-id -> long integer
  - :format -> keyword (edn, json, human)

  Validation:
  - Post-parse validation checks format is valid (edn, json, human)
  - Requires at least one of :task-id or :title
  - Resolves :id alias to :task-id
  - Resolves :t alias to :title
  - Resolves :c alias to :category
  - Resolves :comment alias to :completion-comment"
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
  "Spec for the update command.

  Validates and coerces arguments for updating task fields.

  Coercion rules:
  - :task-id -> long integer
  - :status -> keyword (open, closed, in-progress, blocked)
  - :type -> keyword (task, bug, feature, story, chore)
  - :parent-id -> long integer or nil (via coerce-parent-id)
  - :meta -> parsed from JSON string to Clojure map
  - :relations -> parsed from JSON array to Clojure vector
  - :shared-context -> string (passed directly to update-task tool)
  - :code-reviewed -> string (ISO-8601 timestamp, passed directly)
  - :pr-num -> long integer (GitHub PR number, passed directly)
  - :format -> keyword (edn, json, human)

  Validation:
  - Post-parse validation checks format is valid (edn, json, human)
  - Requires :task-id to be present"
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
   :parent-id {:coerce coerce-parent-id
               :alias :p
               :desc "New parent task ID (or 'null' to remove)"}
   :meta {:desc "New metadata as JSON object"}
   :relations {:desc "New relations as JSON array"}
   :session-events {:desc "Session events as JSON (object or array)"}
   :shared-context {:alias :C
                    :desc "Append entry to task's shared context"}
   :code-reviewed {:desc "ISO-8601 timestamp when code review completed"}
   :pr-num {:coerce :long
            :desc "GitHub pull request number"}
   :format {:coerce :keyword
            :desc "Output format (edn, json, human)"}})

(def delete-spec
  "Spec for the delete command.

  Validates and coerces arguments for deleting tasks.

  Coercion rules:
  - :task-id -> long integer
  - :format -> keyword (edn, json, human)

  Validation:
  - Post-parse validation checks format is valid (edn, json, human)
  - Requires at least one of :task-id or :title-pattern"
  {:task-id {:coerce :long
             :alias :id
             :desc "Task ID to delete"}
   :title-pattern {:alias :title
                   :desc "Title pattern to match (alternative to task-id)"}
   :format {:coerce :keyword
            :desc "Output format (edn, json, human)"}})

(def reopen-spec
  "Spec for the reopen command.

  Validates and coerces arguments for reopening closed tasks.

  Coercion rules:
  - :task-id -> long integer
  - :format -> keyword (edn, json, human)

  Validation:
  - Post-parse validation checks format is valid (edn, json, human)
  - Requires at least one of :task-id or :title
  - Resolves :id alias to :task-id
  - Resolves :t alias to :title"
  {:task-id {:coerce :long
             :alias :id
             :desc "Task ID to reopen"}
   :title {:alias :t
           :desc "Task title (alternative to task-id)"}
   :format {:coerce :keyword
            :desc "Output format (edn, json, human)"}})

(def why-blocked-spec
  "Spec for the why-blocked command.

  Validates and coerces arguments for checking task blocking status.

  Coercion rules:
  - :task-id -> long integer
  - :format -> keyword (edn, json, human)

  Validation:
  - Post-parse validation checks format is valid (edn, json, human)
  - Requires :task-id to be present"
  {:task-id {:coerce :long
             :alias :id
             :desc "Task ID to check"}
   :format {:coerce :keyword
            :desc "Output format (edn, json, human)"}})

(def prompts-list-spec
  "Spec for the prompts list subcommand.

  Validates and coerces arguments for listing available prompts.

  Coercion rules:
  - :format -> keyword (edn, json, human)

  Validation:
  - Post-parse validation checks format is valid (edn, json, human)"
  {:format {:coerce :keyword
            :alias :f
            :desc "Output format (edn, json, human)"}})

(def prompts-customize-spec
  "Spec for the prompts customize subcommand.

  Validates and coerces arguments for copying prompts.

  Coercion rules:
  - :format -> keyword (edn, json, human)

  Validation:
  - Post-parse validation checks format is valid (edn, json, human)
  - Requires at least one prompt name in args"
  {:format {:coerce :keyword
            :alias :f
            :desc "Output format (edn, json, human)"}})

(def prompts-show-spec
  "Spec for the prompts show subcommand.

  Validates and coerces arguments for showing a specific prompt.

  Coercion rules:
  - :format -> keyword (edn, json, human)

  Validation:
  - Post-parse validation checks format is valid (edn, json, human)
  - Requires exactly one prompt name in args"
  {:format {:coerce :keyword
            :alias :f
            :desc "Output format (edn, json, human)"}})

(def prompts-install-spec
  "Spec for the prompts install subcommand.

  Validates and coerces arguments for generating slash commands.

  Coercion rules:
  - :format -> keyword (edn, json, human)

  Validation:
  - Post-parse validation checks format is valid (edn, json, human)
  - Optional target directory in args (defaults to .claude/commands/)"
  {:format {:coerce :keyword
            :alias :f
            :desc "Output format (edn, json, human)"}})

(def prompts-subcommands
  "Configuration map for prompts subcommands.

  Keys are subcommand strings, values are maps containing:
  - :spec - babashka.cli spec for parsing
  - :help - help text string
  - :args-mode - how to handle positional args:
    - :none - no positional args allowed
    - :one-or-more - at least one arg required
    - :exactly-one - exactly one arg required
    - :zero-or-one - optional single arg with default
  - :default-arg - default value when :args-mode is :zero-or-one
  - :result-key - key to use for positional arg(s) in result"
  {"list" {:spec prompts-list-spec
           :help prompts-list-help
           :args-mode :none}
   "customize" {:spec prompts-customize-spec
                :help prompts-customize-help
                :args-mode :one-or-more
                :result-key :prompt-names}
   "show" {:spec prompts-show-spec
           :help prompts-show-help
           :args-mode :exactly-one
           :result-key :prompt-name}
   "install" {:spec prompts-install-spec
              :help prompts-install-help
              :args-mode :zero-or-one
              :default-arg ".claude/commands/"
              :result-key :target-dir}})

(defn parse-prompts-subcommand
  "Parse arguments for a prompts subcommand using its configuration.

  Takes subcommand keyword, config map, and args vector.
  Returns parsed options with :subcommand key, or error map with :error key."
  [subcommand-kw {:keys [spec args-mode default-arg result-key]} args original-args]
  (try
    (let [raw-parsed (if (= args-mode :none)
                       {:opts (cli/parse-opts args {:spec spec
                                                    :restrict (get-allowed-keys spec)})
                        :args []}
                       (cli/parse-args args {:spec spec}))
          parsed-opts (-> (:opts raw-parsed)
                          (dissoc :f)
                          (cond-> (get-in raw-parsed [:opts :f])
                            (assoc :format (get-in raw-parsed [:opts :f]))))
          positional-args (:args raw-parsed)]
      (case args-mode
        :none
        (let [format-validation (validate-format parsed-opts)]
          (if (:valid? format-validation)
            (assoc parsed-opts :subcommand subcommand-kw)
            (dissoc format-validation :valid?)))

        :one-or-more
        (if (empty? positional-args)
          {:error "At least one prompt name is required"
           :metadata {:args original-args}}
          (let [format-validation (validate-format parsed-opts)]
            (if (:valid? format-validation)
              (assoc parsed-opts
                     :subcommand subcommand-kw
                     result-key (vec positional-args))
              (dissoc format-validation :valid?))))

        :exactly-one
        (cond
          (empty? positional-args)
          {:error "Prompt name is required"
           :metadata {:args original-args}}

          (> (count positional-args) 1)
          {:error "Only one prompt name is allowed"
           :metadata {:args original-args
                      :provided-names positional-args}}

          :else
          (let [format-validation (validate-format parsed-opts)]
            (if (:valid? format-validation)
              (assoc parsed-opts
                     :subcommand subcommand-kw
                     result-key (first positional-args))
              (dissoc format-validation :valid?))))

        :zero-or-one
        (cond
          (> (count positional-args) 1)
          {:error "Only one target directory is allowed"
           :metadata {:args original-args
                      :provided-args positional-args}}

          :else
          (let [format-validation (validate-format parsed-opts)]
            (if (:valid? format-validation)
              (assoc parsed-opts
                     :subcommand subcommand-kw
                     result-key (or (first positional-args) default-arg))
              (dissoc format-validation :valid?))))))
    (catch Exception e
      {:error (format-unknown-option-error (.getMessage e))
       :metadata {:args original-args}})))

;; Parse Functions

(defn parse-list
  "Parse arguments for the list command.

  Returns parsed options map or error map with :error key."
  [args]
  (try
    (let [raw-parsed (cli/parse-opts args {:spec list-spec :restrict (get-allowed-keys list-spec)})
          parsed (-> raw-parsed
                     (dissoc :s :c :t :p :title)
                     (cond-> (:s raw-parsed) (assoc :status (:s raw-parsed)))
                     (cond-> (:c raw-parsed) (assoc :category (:c raw-parsed)))
                     (cond-> (:t raw-parsed) (assoc :type (:t raw-parsed)))
                     (cond-> (contains? raw-parsed :p) (assoc :parent-id (:p raw-parsed)))
                     (cond-> (:title raw-parsed) (assoc :title-pattern (:title raw-parsed))))
          format-validation (validate-format parsed)
          status-validation (validate-status parsed)]
      (cond
        (not (:valid? format-validation))
        (dissoc format-validation :valid?)

        (not (:valid? status-validation))
        (dissoc status-validation :valid?)

        :else
        parsed))
    (catch Exception e
      {:error (format-unknown-option-error (.getMessage e))
       :metadata {:args args}})))

(defn parse-show
  "Parse arguments for the show command.

  Returns parsed options map or error map with :error key."
  [args]
  (try
    (let [parsed (cli/parse-opts args {:spec show-spec :restrict (get-allowed-keys show-spec)})
          task-id (resolve-alias parsed :task-id :id)]
      (cond
        (not task-id)
        {:error "Required option: --task-id (or --id)"
         :metadata {:args args}}

        :else
        (let [result (-> parsed
                         (dissoc :id)
                         (assoc :task-id task-id))
              format-validation (validate-format result)]
          (if (:valid? format-validation)
            result
            (dissoc format-validation :valid?)))))
    (catch Exception e
      {:error (format-unknown-option-error (.getMessage e))
       :metadata {:args args}})))

(defn parse-add
  "Parse arguments for the add command.

  Returns parsed options map or error map with :error key."
  [args]
  (try
    (let [raw-parsed (cli/parse-opts args {:spec add-spec :restrict (get-allowed-keys add-spec)})
          category (resolve-alias raw-parsed :category :c)
          title (resolve-alias raw-parsed :title :t)
          parsed (-> raw-parsed
                     (dissoc :c :t :d :p)
                     (cond-> category (assoc :category category))
                     (cond-> title (assoc :title title))
                     (cond-> (:d raw-parsed) (assoc :description (:d raw-parsed)))
                     (cond-> (contains? raw-parsed :p) (assoc :parent-id (:p raw-parsed))))]
      (cond
        (not category)
        {:error "Required option: --category (or -c)"
         :metadata {:args args}}

        (not title)
        {:error "Required option: --title (or -t)"
         :metadata {:args args}}

        :else
        (let [format-validation (validate-format parsed)]
          (if (:valid? format-validation)
            parsed
            (dissoc format-validation :valid?)))))
    (catch Exception e
      {:error (format-unknown-option-error (.getMessage e))
       :metadata {:args args}})))

(defn parse-complete
  "Parse arguments for the complete command.

  Validates that at least one of task-id or title is provided.
  Returns parsed options map or error map with :error key."
  [args]
  (try
    (let [raw-parsed (cli/parse-opts args {:spec complete-spec :restrict (get-allowed-keys complete-spec)})
          task-id (or (:task-id raw-parsed) (:id raw-parsed))
          parsed (-> raw-parsed
                     (dissoc :id :t :c :comment)
                     (cond-> task-id (assoc :task-id task-id))
                     (cond-> (:t raw-parsed) (assoc :title (:t raw-parsed)))
                     (cond-> (:c raw-parsed) (assoc :category (:c raw-parsed)))
                     (cond-> (:comment raw-parsed) (assoc :completion-comment (:comment raw-parsed))))
          at-least-one-validation (validate-at-least-one parsed [:task-id :title] ["--task-id" "--title"])]
      (if-not (:valid? at-least-one-validation)
        (dissoc at-least-one-validation :valid?)
        (let [format-validation (validate-format parsed)]
          (if (:valid? format-validation)
            parsed
            (dissoc format-validation :valid?)))))
    (catch Exception e
      {:error (format-unknown-option-error (.getMessage e))
       :metadata {:args args}})))

(defn- parse-json-fields
  "Parse JSON string fields in a map, returning error on first failure.

  Takes a map of field-key to coercion-fn pairs. For each field present
  in parsed-map, applies the coercion function. Returns the updated map
  or the first error encountered."
  [parsed-map field-coercions]
  (reduce
    (fn [acc [field-key coerce-fn]]
      (if (:error acc)
        (reduced acc)
        (if-let [json-str (get acc field-key)]
          (let [result (coerce-fn json-str)]
            (if (:error result)
              (reduced result)
              (assoc acc field-key result)))
          acc)))
    parsed-map
    field-coercions))

(defn parse-update
  "Parse arguments for the update command.

  Handles JSON parsing for :meta, :relations, and :session-events fields.
  Returns parsed options map or error map with :error key."
  [args]
  (try
    (let [raw-parsed (cli/parse-opts args {:spec update-spec :restrict (get-allowed-keys update-spec)})
          task-id (or (:task-id raw-parsed) (:id raw-parsed))
          parsed (-> raw-parsed
                     (dissoc :id :t :d :s :c :p :C)
                     (cond-> task-id (assoc :task-id task-id))
                     (cond-> (:t raw-parsed) (assoc :title (:t raw-parsed)))
                     (cond-> (:d raw-parsed) (assoc :description (:d raw-parsed)))
                     (cond-> (:s raw-parsed) (assoc :status (:s raw-parsed)))
                     (cond-> (:c raw-parsed) (assoc :category (:c raw-parsed)))
                     (cond-> (contains? raw-parsed :p) (assoc :parent-id (:p raw-parsed)))
                     (cond-> (:C raw-parsed) (assoc :shared-context (:C raw-parsed))))]
      (if-not task-id
        {:error "Required option: --task-id (or --id)"
         :metadata {:args args}}
        ;; Parse JSON fields
        (let [json-parsed (parse-json-fields parsed
                                             [[:meta coerce-json-map]
                                              [:relations coerce-json-array]
                                              [:session-events coerce-session-events]])]
          (if (:error json-parsed)
            json-parsed
            (let [format-validation (validate-format json-parsed)]
              (if (:valid? format-validation)
                json-parsed
                (dissoc format-validation :valid?)))))))
    (catch Exception e
      {:error (format-unknown-option-error (.getMessage e))
       :metadata {:args args}})))

(defn parse-delete
  "Parse arguments for the delete command.

  Validates that at least one of task-id or title-pattern is provided.
  Returns parsed options map or error map with :error key."
  [args]
  (try
    (let [raw-parsed (cli/parse-opts args {:spec delete-spec :restrict (get-allowed-keys delete-spec)})
          task-id (or (:task-id raw-parsed) (:id raw-parsed))
          parsed (-> raw-parsed
                     (dissoc :id :title)
                     (cond-> task-id (assoc :task-id task-id))
                     (cond-> (:title raw-parsed) (assoc :title-pattern (:title raw-parsed))))
          at-least-one-validation (validate-at-least-one parsed [:task-id :title-pattern] ["--task-id" "--title-pattern"])]
      (if-not (:valid? at-least-one-validation)
        (dissoc at-least-one-validation :valid?)
        (let [format-validation (validate-format parsed)]
          (if (:valid? format-validation)
            parsed
            (dissoc format-validation :valid?)))))
    (catch Exception e
      {:error (format-unknown-option-error (.getMessage e))
       :metadata {:args args}})))

(defn parse-reopen
  "Parse arguments for the reopen command.

  Validates that at least one of task-id or title is provided.
  Returns parsed options map or error map with :error key."
  [args]
  (try
    (let [raw-parsed (cli/parse-opts args {:spec reopen-spec :restrict (get-allowed-keys reopen-spec)})
          task-id (or (:task-id raw-parsed) (:id raw-parsed))
          parsed (-> raw-parsed
                     (dissoc :id :t)
                     (cond-> task-id (assoc :task-id task-id))
                     (cond-> (:t raw-parsed) (assoc :title (:t raw-parsed))))
          at-least-one-validation (validate-at-least-one parsed [:task-id :title] ["--task-id" "--title"])]
      (if-not (:valid? at-least-one-validation)
        (dissoc at-least-one-validation :valid?)
        (let [format-validation (validate-format parsed)]
          (if (:valid? format-validation)
            parsed
            (dissoc format-validation :valid?)))))
    (catch Exception e
      {:error (format-unknown-option-error (.getMessage e))
       :metadata {:args args}})))

(defn parse-why-blocked
  "Parse arguments for the why-blocked command.

  Returns parsed options map or error map with :error key."
  [args]
  (try
    (let [parsed (cli/parse-opts args {:spec why-blocked-spec :restrict (get-allowed-keys why-blocked-spec)})
          task-id (resolve-alias parsed :task-id :id)]
      (cond
        (not task-id)
        {:error "Required option: --task-id (or --id)"
         :metadata {:args args}}

        :else
        (let [result (-> parsed
                         (dissoc :id)
                         (assoc :task-id task-id))
              format-validation (validate-format result)]
          (if (:valid? format-validation)
            result
            (dissoc format-validation :valid?)))))
    (catch Exception e
      {:error (format-unknown-option-error (.getMessage e))
       :metadata {:args args}})))

(defn parse-prompts
  "Parse arguments for the prompts command.

  Handles subcommands: list, customize, show, install
  Returns parsed options map with :subcommand key, error map with :error key,
  or help map with :help key."
  [args]
  (let [valid-subcommands (str/join ", " (sort (keys prompts-subcommands)))]
    (if (empty? args)
      {:error (str "Subcommand required: " valid-subcommands)
       :metadata {:args args}}
      (let [subcommand (first args)
            subcommand-args (rest args)
            config (get prompts-subcommands subcommand)]
        (cond
          ;; Unknown subcommand
          (nil? config)
          {:error (str "Unknown subcommand: " subcommand
                       ". Valid subcommands: " valid-subcommands)
           :metadata {:args args
                      :provided-subcommand subcommand}}

          ;; Help flag requested
          (and (seq subcommand-args)
               (or (= "--help" (first subcommand-args))
                   (= "-h" (first subcommand-args))))
          {:help (:help config)}

          ;; Normal parsing
          :else
          (parse-prompts-subcommand (keyword subcommand)
                                    config
                                    subcommand-args
                                    args))))))
