(ns mcp-tasks.tool.update-task
  "MCP tool for modifying existing task fields.

  This namespace implements the update-task tool, which allows selective
  field updates on existing tasks. Supports updating any combination of:
  - title, description, design: Text content
  - category, type, status: Enumerated fields
  - parent-id: Task hierarchy (can be set to nil to remove parent)
  - meta: Key-value metadata map (replaces entire map)
  - relations: Task relationships vector (replaces entire vector)

  The tool validates all field values and handles type conversions from
  JSON to EDN formats. Only provided fields are updated; others remain
  unchanged.

  Part of the refactored tool architecture where each tool lives in its own
  namespace under mcp-tasks.tool.*, with the main tools.clj acting as a facade."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [mcp-tasks.tasks :as tasks]
    [mcp-tasks.tools.git :as git]
    [mcp-tasks.tools.helpers :as helpers]
    [mcp-tasks.tools.validation :as validation]))

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

(defn- convert-relations-field
  "Convert relations from JSON structure to Clojure keyword-based structure.

  Handles both keyword keys (from MCP JSON parsing) and string keys (from
  direct function calls). Transforms string keys to keywords for :as-type field.
  Returns [] if relations is nil.

  Note: When updating a task's :relations field, the entire vector is
  replaced, not appended or merged. This design decision ensures
  predictable behavior - users provide the complete desired state rather
  than incremental updates. This matches the story requirements and
  simplifies the mental model for task updates."
  [relations]
  (if relations
    (mapv (fn [rel]
            (let [id (or (get rel :id) (get rel "id"))
                  relates-to (or (get rel :relates-to) (get rel "relates-to"))
                  as-type-val (or (get rel :as-type) (get rel "as-type"))
                  as-type (if (keyword? as-type-val) as-type-val (keyword as-type-val))]
              {:id id
               :relates-to relates-to
               :as-type as-type}))
          relations)
    []))

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

(defn- extract-provided-updates
  "Extract and convert provided fields from arguments map.

  Returns map with only the fields that were actually provided in arguments,
  with appropriate type conversions applied.

  Type conversions:
  - :status, :type - string to keyword
  - :meta - nil becomes {}, replaces entire map (does not merge)
  - :relations - nil becomes [], replaces entire vector (does not append)

  Note: The :meta and :relations fields use replacement semantics rather
  than merge/append. This design ensures predictable behavior where users
  specify the complete desired state in a single update operation."
  [arguments]
  (let [conversions {:status convert-enum-field
                     :type convert-enum-field
                     :meta convert-meta-field
                     :relations convert-relations-field}
        updatable-fields [:title :description :design :parent-id
                          :status :category :type :meta :relations]]
    (reduce (fn [updates field-key]
              (if (contains? arguments field-key)
                (let [value (get arguments field-key)
                      converter (get conversions field-key identity)
                      converted-value (converter value)]
                  (assoc updates field-key converted-value))
                updates))
            {}
            updatable-fields)))

(defn- update-task-impl
  "Implementation of update-task tool.

  Updates specified fields of an existing task in tasks.ednl.
  Supports all mutable task fields with proper nil handling."
  [config _context arguments]
  ;; Perform file operations inside lock
  (let [locked-result (helpers/with-task-lock config
                                              (fn []
                                                ;; Sync with remote and load tasks
                                                (let [sync-result (helpers/sync-and-prepare-task-file config)]
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
                                                          tasks-file sync-result]
                                                      (tasks/load-tasks! tasks-file)
                                                      (let [updates (extract-provided-updates arguments)]
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
                                                              (let [old-task (tasks/get-task task-id)
                                                                    updated-task (merge old-task updates)]
                                                                (validation/validate-task-schema updated-task "update-task" task-id tasks-file))
                                                              (do
                                                                (tasks/update-task task-id updates)
                                                                (tasks/save-tasks! tasks-file)
                                                                (let [final-task (tasks/get-task task-id)
                                                                      tasks-path (helpers/task-path config ["tasks.ednl"])
                                                                      tasks-rel-path (:relative tasks-path)]
                                                                  ;; Return intermediate data for git operations
                                                                  {:final-task final-task
                                                                   :tasks-file tasks-file
                                                                   :tasks-rel-path tasks-rel-path
                                                                   :task-id task-id}))))))))))]
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
                                      [:id :title :category :type :status :parent-id])
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
   :description "Update fields of an existing task by ID. Only provided fields will be updated. Supports updating: title, description, design, parent-id, status, category, type, meta, and relations. Pass nil for optional fields (parent-id, meta, relations) to clear their values."
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
      :enum ["open" "closed" "in-progress" "blocked"]
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
      :description "New relations vector (optional). Pass null to clear. Replaces entire vector, does not merge."}}
    :required ["task-id"]}
   :implementation (partial update-task-impl config)})
