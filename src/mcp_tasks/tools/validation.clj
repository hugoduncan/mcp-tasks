(ns mcp-tasks.tools.validation
  "Validation error formatting and task validation utilities"
  (:require
    [clojure.string :as str]
    [mcp-tasks.schema :as schema]
    [mcp-tasks.tasks :as tasks]
    [mcp-tasks.tools.helpers :as helpers]))

(defn format-path-element
  "Format a single element from a Malli :in path.

  Handles keywords, symbols, strings, and numeric indices."
  [element]
  (cond
    (keyword? element) (name element)
    (symbol? element) (name element)
    (string? element) element
    (number? element) (str "[" element "]")
    :else (str element)))

(defn format-field-path
  "Format a Malli :in path into a human-readable field name.

  Returns a string like 'relations[0].as-type' for nested paths."
  [in]
  (if (seq in)
    (str/join "." (map format-path-element in))
    "field"))

(defn format-malli-error
  "Format a Malli validation error into a human-readable message.

  Extracts field path and error details from Malli's explain result.
  Returns a string describing what field failed and why."
  [error]
  (let [in (:in error)
        schema (:schema error)
        value (:value error)
        type (:type error)
        field-name (format-field-path in)]
    (cond
      ;; Enum validation failure
      (and (vector? schema) (= :enum (first schema)))
      (let [allowed-values (rest schema)]
        (format "%s has invalid value %s (expected one of: %s)"
                field-name
                (pr-str value)
                (str/join ", " (map pr-str allowed-values))))

      ;; Type mismatch
      (= type :malli.core/invalid-type)
      (format "%s has invalid type (got: %s, expected: %s)"
              field-name
              (pr-str value)
              (pr-str schema))

      ;; Missing required field
      (= type :malli.core/missing-key)
      (format "missing required field: %s" field-name)

      ;; Generic fallback
      :else
      (format "%s failed validation: %s" field-name (pr-str error)))))

(defn format-validation-errors
  "Format Malli validation result into human-readable error messages.

  Takes the result from schema/explain-task and returns a formatted
  string with specific field errors."
  [validation-result]
  (if-let [errors (:errors validation-result)]
    (str/join "; " (map format-malli-error errors))
    (pr-str validation-result)))

;; Task validation functions

(defn validate-task-exists
  "Validate that a task exists.

  Returns error response map if validation fails, nil if successful."
  [task-id operation tasks-file & {:keys [additional-metadata]}]
  (when-not (tasks/get-task task-id)
    (helpers/build-tool-error-response
      "Task not found"
      operation
      (merge {:task-id task-id :file tasks-file}
             additional-metadata))))

(defn validate-parent-id-exists
  "Validate that a parent task exists if parent-id is provided and non-nil.

  Returns error response map if validation fails, nil if successful."
  [parent-id operation task-id tasks-file error-message & {:keys [additional-metadata]}]
  (when (and parent-id (not (tasks/get-task parent-id)))
    (helpers/build-tool-error-response
      error-message
      operation
      (merge {:task-id task-id :parent-id parent-id :file tasks-file}
             additional-metadata))))

(defn validate-task-schema
  "Validate that a task conforms to the schema.

  Returns error response map if validation fails, nil if successful."
  [task operation task-id tasks-file]
  (when-let [validation-result (schema/explain-task task)]
    (let [formatted-errors (format-validation-errors validation-result)
          error-message (str "Invalid task field values: " formatted-errors)]
      (helpers/build-tool-error-response
        error-message
        operation
        {:task-id task-id
         :validation-errors (pr-str validation-result)
         :file tasks-file}))))

(defn find-task-by-identifiers
  "Find a task by task-id and/or title using exact match.

  At least one of task-id or title must be provided.
  If both are provided, they must refer to the same task.

  Parameters:
  - task-id: Optional integer task ID
  - title: Optional string for exact title match
  - operation: String operation name for error messages (e.g., 'complete-task', 'delete-task')
  - tasks-file: Path to tasks file for error metadata

  Returns:
  - Task map if found, OR
  - Error response map with :isError true"
  [task-id title operation tasks-file]
  ;; Validate at least one identifier provided
  (if (and (nil? task-id) (nil? title))
    (helpers/build-tool-error-response
      "Must provide either task-id or title"
      operation
      {:task-id task-id
       :title title})

    ;; Find task by ID or exact title match
    (let [task-by-id (when task-id (tasks/get-task task-id))
          tasks-by-title (when title (tasks/find-by-title title))]

      (cond
        ;; Both provided - verify they match
        (and task-id title)
        (cond
          (nil? task-by-id)
          (helpers/build-tool-error-response
            "Task ID not found"
            operation
            {:task-id task-id
             :file tasks-file})

          (empty? tasks-by-title)
          (helpers/build-tool-error-response
            "No task found with exact title match"
            operation
            {:title title
             :file tasks-file})

          (not (some #(= (:id %) task-id) tasks-by-title))
          (helpers/build-tool-error-response
            "Task ID and title do not refer to the same task"
            operation
            {:task-id task-id
             :title title
             :task-by-id task-by-id
             :tasks-by-title (mapv :id tasks-by-title)
             :file tasks-file})

          :else task-by-id)

        ;; Only ID provided
        task-id
        (or task-by-id
            (helpers/build-tool-error-response
              "Task ID not found"
              operation
              {:task-id task-id
               :file tasks-file}))

        ;; Only title provided
        title
        (cond
          (empty? tasks-by-title)
          (helpers/build-tool-error-response
            "No task found with exact title match"
            operation
            {:title title
             :file tasks-file})

          (> (count tasks-by-title) 1)
          (helpers/build-tool-error-response
            "Multiple tasks found with same title - use task-id to disambiguate"
            operation
            {:title title
             :matching-task-ids (mapv :id tasks-by-title)
             :matching-tasks tasks-by-title
             :file tasks-file})

          :else (first tasks-by-title))))))
