(ns mcp-tasks.tool.update-task
  "MCP tool for modifying existing task fields.

  This namespace implements the update-task tool, which allows selective
  field updates on existing tasks. Supports updating any combination of:
  - title, description, design: Text content
  - category, type, status: Enumerated fields
  - parent-id: Task hierarchy (can be set to nil to remove parent)
  - meta: Key-value metadata map (replaces entire map)
  - relations: Task relationships vector (replaces entire vector)
  - shared-context: Story shared context vector (appends new entries with automatic prefixing)
  - session-events: Session event vector (appends new events with auto-timestamp)

  The tool validates all field values and handles type conversions from
  JSON to EDN formats. Only provided fields are updated; others remain
  unchanged.

  Part of the refactored tool architecture where each tool lives in its own
  namespace under mcp-tasks.tool.*, with the main tools.clj acting as a facade."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [mcp-tasks.execution-state :as es]
    [mcp-tasks.schema :as schema]
    [mcp-tasks.tasks :as tasks]
    [mcp-tasks.tools.git :as git]
    [mcp-tasks.tools.helpers :as helpers]
    [mcp-tasks.tools.validation :as validation])
  (:import
    (java.time
      Instant)))

(defn- validate-iso-8601-utc
  "Validate that a string is a valid ISO-8601 timestamp in UTC format (Z suffix).

  Returns nil if valid, or an error map if invalid."
  [value field-name]
  (when (and value (not (nil? value)))
    (try
      (Instant/parse value)
      ;; Instant/parse accepts any valid ISO-8601 - verify it ends with Z
      (when-not (str/ends-with? value "Z")
        {:error true
         :message (str "Invalid " field-name " format: must use UTC timezone (Z suffix)")
         :provided value
         :expected "ISO-8601 UTC format, e.g., 2025-01-15T10:30:00Z"})
      (catch Exception _
        {:error true
         :message (str "Invalid " field-name " format: not a valid ISO-8601 timestamp")
         :provided value
         :expected "ISO-8601 UTC format, e.g., 2025-01-15T10:30:00Z"}))))

(defn- convert-enum-field
  "Convert string enum value to keyword.

  Returns keyword version of the string value."
  [value]
  (keyword value))

(defn- convert-meta-field
  "Convert meta field value, ensuring string keys and values.

  The Task schema requires meta to be [:map-of :string :string].
  This function ensures both keys and values are coerced to strings,
  preventing keywordization from JSON parsing or MCP client libraries.

  Returns {} if value is nil.

  Note: When updating a task's :meta field, the entire map is replaced,
  not merged. This design decision ensures predictable behavior - users
  provide the complete desired state rather than incremental updates.
  This matches the story requirements and simplifies the mental model
  for task updates."
  [value]
  (if value
    (into {} (map (fn [[k v]] [(str k) (str v)]) value))
    {}))

(defn- validate-circular-dependencies
  "Validate that updating task relations won't create circular dependencies.

  Checks each :blocked-by relation in the new relations to ensure no cycles
  would be created. Returns error map if cycle detected, nil otherwise."
  [task-id new-relations tasks-file]
  (let [blocked-by-relations (filter #(= :blocked-by (:as-type %)) new-relations)]
    (when (seq blocked-by-relations)
      ;; Temporarily update the task to check for cycles
      (let [old-task (tasks/get-task task-id)
            temp-task (assoc old-task :relations new-relations)]
        ;; Temporarily update in-memory state
        (swap! tasks/tasks assoc task-id temp-task)
        (let [result (try
                       ;; Check for circular dependencies
                       (let [blocking-info (tasks/is-task-blocked? task-id)]
                         (when-let [cycle (:circular-dependency blocking-info)]
                           (helpers/build-tool-error-response
                             (str "Circular dependency detected: " (str/join " → " cycle))
                             "update-task"
                             {:task-id task-id
                              :file tasks-file
                              :cycle cycle})))
                       (finally
                         ;; Restore original task
                         (swap! tasks/tasks assoc task-id old-task)))]
          result)))))

(defn- apply-shared-context-update
  "Apply shared-context update with append semantics and size validation.

  Appends new entries to existing shared-context vector and validates that
  the total serialized EDN size doesn't exceed 50KB.

  Returns updated task map or error map if size limit exceeded."
  [task updates]
  (if (contains? updates :shared-context)
    (let [existing-context (get task :shared-context [])
          new-entries (get updates :shared-context)
          combined-context (into existing-context new-entries)
          serialized (pr-str combined-context)
          size-bytes (count (.getBytes serialized "UTF-8"))]
      (if (> size-bytes 51200) ; 50KB = 50 * 1024 = 51200 bytes
        {:error true
         :message "Shared context size limit (50KB) exceeded. Consider summarizing or removing old entries."
         :size-bytes size-bytes
         :limit-bytes 51200}
        (assoc task :shared-context combined-context)))
    task))

(defn- apply-session-events-update
  "Apply session-events update with append semantics and size validation.

  Appends new events to existing session-events vector and validates that
  the total serialized EDN size doesn't exceed 50KB.

  Returns updated task map or error map if size limit exceeded."
  [task updates]
  (if (contains? updates :session-events)
    (let [existing-events (get task :session-events [])
          new-events (get updates :session-events)
          combined-events (into existing-events new-events)
          serialized (pr-str combined-events)
          size-bytes (count (.getBytes serialized "UTF-8"))]
      (if (> size-bytes 51200) ; 50KB = 50 * 1024 = 51200 bytes
        {:error true
         :message "Session events size limit (50KB) exceeded. Consider archiving old events."
         :size-bytes size-bytes
         :limit-bytes 51200}
        (assoc task :session-events combined-events)))
    task))

(defn- convert-shared-context-field
  "Convert and prefix shared-context entries with task ID from execution state.

  Accepts either a single string or a vector of strings. If a string is provided,
  it is wrapped in a vector before processing.

  Reads the execution state file to get the current :task-id and prefixes
  each entry with 'Task NNN: '. If execution state is missing or has no
  :task-id, entries are added without prefix (manual update case).

  Returns vector of prefixed strings, or [] if value is nil."
  [base-dir value]
  (when (and value (if (string? value) (seq value) (seq value)))
    (let [entries (if (string? value) [value] value)
          exec-state (es/read-execution-state base-dir)
          task-id (:task-id exec-state)]
      (if task-id
        (mapv #(str "Task " task-id ": " %) entries)
        (vec entries)))))

(defn- current-iso-timestamp
  "Return current time as ISO-8601 string."
  []
  (str (Instant/now)))

(defn- convert-session-event
  "Convert a single session event map, adding timestamp if missing.

  Converts string keys to keywords and event-type to keyword.
  Returns the converted event map."
  [event]
  (let [;; Handle both keyword and string keys from JSON
        event-with-keywords (into {} (map (fn [[k v]] [(keyword k) v]) event))
        ;; Convert event-type string to keyword if needed
        event-type-val (:event-type event-with-keywords)
        event-type-kw (if (string? event-type-val)
                        (keyword event-type-val)
                        event-type-val)]
    (cond-> (assoc event-with-keywords :event-type event-type-kw)
      (not (:timestamp event-with-keywords))
      (assoc :timestamp (current-iso-timestamp)))))

(defn- validate-session-events
  "Validate a vector of session events against the SessionEvent schema.

  Returns nil if all valid, or error map with details if any invalid."
  [events]
  (let [invalid-events (keep-indexed
                         (fn [idx event]
                           (when-not (schema/valid-session-event? event)
                             {:index idx
                              :event (pr-str event)
                              :reason "Event does not match SessionEvent schema"}))
                         events)]
    (when (seq invalid-events)
      {:error true
       :message "Invalid session event(s)"
       :invalid-events invalid-events})))

(defn- convert-session-events-field
  "Convert session-events input to vector of validated event maps.

  Accepts either a single event map or a vector of event maps.
  Adds timestamp to events that don't have one.
  Returns {:events [...]} on success or {:error ...} on validation failure."
  [value]
  (when value
    (let [events (if (and (map? value) (not (vector? value)))
                   [value]
                   (vec value))
          converted-events (mapv convert-session-event events)
          validation-error (validate-session-events converted-events)]
      (if validation-error
        validation-error
        {:events converted-events}))))

(defn- extract-provided-updates
  "Extract and convert provided fields from arguments map.

  Returns map with only the fields that were actually provided in arguments,
  with appropriate type conversions applied.

  Type conversions:
  - :status, :type - string to keyword
  - :meta - nil becomes {}, replaces entire map (does not merge)
  - :relations - nil becomes [], replaces entire vector (does not append)
  - :shared-context - appends to existing context with automatic prefixing
  - :session-events - appends to existing events with auto-timestamp

  Note: The :meta and :relations fields use replacement semantics rather
  than merge/append. The :shared-context and :session-events fields use
  append semantics.

  For :session-events, returns {:session-events-error ...} if validation fails."
  [base-dir arguments]
  (let [conversions {:status convert-enum-field
                     :type convert-enum-field
                     :meta convert-meta-field
                     :relations helpers/convert-relations-field
                     :shared-context (partial convert-shared-context-field base-dir)}
        updatable-fields [:title :description :design :parent-id
                          :status :category :type :meta :relations :shared-context
                          :code-reviewed :pr-num]
        ;; Process standard fields
        updates (reduce (fn [updates field-key]
                          (if (contains? arguments field-key)
                            (let [value (get arguments field-key)
                                  converter (get conversions field-key identity)
                                  converted-value (converter value)]
                              ;; Skip :shared-context when converted value is nil (empty string case)
                              (if (and (= :shared-context field-key) (nil? converted-value))
                                updates
                                (assoc updates field-key converted-value)))
                            updates))
                        {}
                        updatable-fields)]
    ;; Handle session-events separately due to special validation
    (if (contains? arguments :session-events)
      (let [result (convert-session-events-field (:session-events arguments))]
        (if (:error result)
          (assoc updates :session-events-error result)
          (assoc updates :session-events (:events result))))
      updates)))

(defn- update-task-impl
  "Implementation of update-task tool.

  Updates specified fields of an existing task in tasks.ednl.
  Supports all mutable task fields with proper nil handling."
  [config _context arguments]
  ;; Perform file operations inside lock
  (let [locked-result (helpers/with-task-lock config
                                              (fn [file-context]
                                                ;; Sync with remote and load tasks
                                                (let [sync-result (helpers/sync-and-prepare-task-file config :file-context file-context)]
                                                  (if (and (map? sync-result) (false? (:success sync-result)))
                                                    ;; sync-result is an error map
                                                    (let [{:keys [error error-type]} sync-result
                                                          tasks-dir (:resolved-tasks-dir config)]
                                                      (helpers/build-tool-error-response
                                                        (case error-type
                                                          :conflict (str "Pull failed with conflicts. Resolve manually in " tasks-dir)
                                                          :network (str "Pull failed: " error)
                                                          (str "Pull failed: " error))
                                                        "update-task"
                                                        {:error-type error-type
                                                         :error-details error
                                                         :tasks-dir tasks-dir}))

                                                    ;; sync-result is the tasks-file path - proceed
                                                    (let [task-id (:task-id arguments)
                                                          tasks-file sync-result
                                                          base-dir (:base-dir config)]
                                                      (tasks/load-tasks! tasks-file :file-context file-context)
                                                      (let [updates (extract-provided-updates base-dir arguments)]
                                                        (if (empty? updates)
                                                          (helpers/build-tool-error-response
                                                            "No fields to update"
                                                            "update-task"
                                                            {:task-id task-id
                                                             :file tasks-file})
                                                          (or (validation/validate-task-exists task-id "update-task" tasks-file)
                                                              (when (and (contains? updates :parent-id) (:parent-id updates))
                                                                (validation/validate-parent-id-exists (:parent-id updates) "update-task" task-id tasks-file "Parent task not found"))
                                                              (when (contains? updates :relations)
                                                                (validate-circular-dependencies task-id (:relations updates) tasks-file))
                                                              ;; Validate code-reviewed timestamp format
                                                              (when (contains? updates :code-reviewed)
                                                                (when-let [validation-error (validate-iso-8601-utc (:code-reviewed updates) "code-reviewed")]
                                                                  (helpers/build-tool-error-response
                                                                    (:message validation-error)
                                                                    "update-task"
                                                                    {:task-id task-id
                                                                     :file tasks-file
                                                                     :provided (:provided validation-error)
                                                                     :expected (:expected validation-error)})))
                                                              ;; Check for session-events validation error
                                                              (when-let [events-error (:session-events-error updates)]
                                                                (helpers/build-tool-error-response
                                                                  (:message events-error)
                                                                  "update-task"
                                                                  {:task-id task-id
                                                                   :file tasks-file
                                                                   :invalid-events (:invalid-events events-error)}))
                                                              (let [old-task (tasks/get-task task-id)
                                                                    ;; Apply shared-context with append semantics and size validation
                                                                    context-result (apply-shared-context-update old-task updates)]
                                                                (if (:error context-result)
                                                                  ;; Size limit exceeded for shared-context
                                                                  (helpers/build-tool-error-response
                                                                    (:message context-result)
                                                                    "update-task"
                                                                    {:task-id task-id
                                                                     :file tasks-file
                                                                     :size-bytes (:size-bytes context-result)
                                                                     :limit-bytes (:limit-bytes context-result)})
                                                                  ;; Apply session-events with append semantics and size validation
                                                                  (let [events-result (apply-session-events-update context-result updates)]
                                                                    (if (:error events-result)
                                                                      ;; Size limit exceeded for session-events
                                                                      (helpers/build-tool-error-response
                                                                        (:message events-result)
                                                                        "update-task"
                                                                        {:task-id task-id
                                                                         :file tasks-file
                                                                         :size-bytes (:size-bytes events-result)
                                                                         :limit-bytes (:limit-bytes events-result)})
                                                                      ;; Continue with validation and update
                                                                      (let [task-with-appends events-result
                                                                            ;; Merge other updates (excluding already applied fields)
                                                                            other-updates (dissoc updates :shared-context :session-events :session-events-error)
                                                                            updated-task (merge task-with-appends other-updates)]
                                                                        (or (validation/validate-task-schema updated-task "update-task" task-id tasks-file)
                                                                            (do
                                                                              ;; Update with the fully merged task
                                                                              (tasks/update-task task-id (dissoc updated-task :id))
                                                                              (tasks/save-tasks! tasks-file :file-context file-context)
                                                                              (let [final-task (tasks/get-task task-id)
                                                                                    tasks-path (helpers/task-path config ["tasks.ednl"])
                                                                                    tasks-rel-path (:relative tasks-path)]
                                                                                ;; Return intermediate data for git operations
                                                                                {:final-task final-task
                                                                                 :tasks-file tasks-file
                                                                                 :tasks-rel-path tasks-rel-path
                                                                                 :task-id task-id}))))))))))))))))]
    ;; Check if locked section returned an error
    (if (:isError locked-result)
      locked-result

      ;; Perform git operations outside lock
      (let [{:keys [final-task tasks-file tasks-rel-path task-id]} locked-result
            use-git? (:use-git? config)
            git-result (when use-git?
                         (let [truncated-title (helpers/truncate-title (:title final-task))]
                           (git/commit-task-changes (:base-dir config)
                                                    [tasks-rel-path]
                                                    (str "Update task #" task-id ": " truncated-title))))
            task-data-json (json/generate-string
                             {:task (select-keys
                                      final-task
                                      [:id :title :category :type :status :parent-id :shared-context])
                              :metadata {:file tasks-file
                                         :operation "update-task"}})]
        (if use-git?
          {:content [{:type "text"
                      :text (str "Task " task-id " updated in " tasks-file)}
                     {:type "text"
                      :text task-data-json}
                     {:type "text"
                      :text (json/generate-string
                              (cond-> {:git-status (if (:success git-result)
                                                     "success"
                                                     "error")
                                       :git-commit (:commit-sha git-result)}
                                (:error git-result)
                                (assoc :git-error (:error git-result))))}]
           :isError false}
          {:content [{:type "text"
                      :text (str "Task " task-id " updated in " tasks-file)}
                     {:type "text"
                      :text task-data-json}]
           :isError false})))))

(defn update-task-tool
  "Tool to update fields of an existing task.

  Accepts config parameter for future git-aware functionality."
  [config]
  {:name "update-task"
   :description "Update fields of an existing task by ID. Only provided fields will be updated. Supports updating: title, description, design, parent-id, status, category, type, meta, relations, shared-context, session-events, code-reviewed, and pr-num. Pass nil for optional fields (parent-id, meta, relations, code-reviewed, pr-num) to clear their values. The shared-context field uses append semantics with automatic task ID prefixing. The session-events field uses append semantics with automatic timestamp generation."
   :inputSchema
   {:type "object"
    :properties
    {"task-id"
     {:type "integer"
      :description "The ID of the task to update"}
     "title"
     {:type "string"
      :description "New title for the task (optional)"}
     "description"
     {:type "string"
      :description "New description for the task (optional)"}
     "design"
     {:type "string"
      :description "New design notes for the task (optional)"}
     "parent-id"
     {:type ["integer" "null"]
      :description "New parent task ID (optional). Pass null to remove parent relationship."}
     "status"
     {:type "string"
      :enum ["open" "closed" "in-progress" "blocked" "done"]
      :description "New task status (optional)"}
     "category"
     {:type "string"
      :description "New task category (optional)"}
     "type"
     {:type "string"
      :enum ["task" "bug" "feature" "story" "chore"]
      :description "New task type (optional)"}
     "meta"
     {:type ["object" "null"]
      :description "New metadata map with string keys and values (optional). Pass null to clear. Replaces entire map, does not merge."}
     "relations"
     {:type ["array" "null"]
      :items {:type "object"
              :properties {"id" {:type "integer"}
                           "relates-to" {:type "integer"}
                           "as-type" {:type "string"
                                      :enum ["blocked-by" "related" "discovered-during"]}}
              :required ["id" "relates-to" "as-type"]}
      :description "New relations vector (optional). Pass null to clear. Replaces entire vector, does not merge."}
     "shared-context"
     {:type ["string" "array"]
      :items {:type "string"}
      :description "String or vector of strings to append to the task's shared context (optional). If a string is provided, it is wrapped in a vector. Entries are automatically prefixed with 'Task NNN: ' based on the current execution state. New entries are appended to existing context. Limited to 50KB total size."}
     "session-events"
     {:type ["object" "array"]
      :items {:type "object"
              :properties {"event-type" {:type "string"
                                         :enum ["user-prompt" "compaction" "session-start"]}
                           "timestamp" {:type "string"
                                        :description "ISO-8601 timestamp (optional, auto-generated if not provided)"}
                           "content" {:type "string"
                                      :description "Content for user-prompt events"}
                           "trigger" {:type "string"
                                      :description "Trigger type for compaction events (auto/manual)"}
                           "session-id" {:type "string"
                                         :description "Session ID for session-start events"}}
              :required ["event-type"]}
      :description "Session event or array of events to append (optional). Each event requires event-type (:user-prompt, :compaction, :session-start). Timestamp is auto-generated if not provided. New events are appended to existing events. Limited to 50KB total size."}
     "code-reviewed"
     {:type ["string" "null"]
      :description "ISO-8601 timestamp when code review was completed (optional). Pass null to clear."}
     "pr-num"
     {:type ["integer" "null"]
      :description "GitHub pull request number (optional). Pass null to clear."}}
    :required ["task-id"]}
   :implementation (partial update-task-impl config)})
