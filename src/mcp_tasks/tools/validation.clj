(ns mcp-tasks.tools.validation
  "Validation error formatting utilities"
  (:require
    [clojure.string :as str]))

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
