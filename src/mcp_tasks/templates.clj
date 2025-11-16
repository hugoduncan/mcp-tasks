(ns mcp-tasks.templates
  "Template rendering wrapper for prompt content.

  Provides a simplified interface over Selmer for rendering templates
  with variable substitution and file includes. This namespace isolates
  the rest of the codebase from Selmer implementation details."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [selmer.parser :as selmer]
    [selmer.util :as selmer-util]))

;; Configuration

(def ^:private default-config
  "Default template rendering configuration."
  {:missing-value-formatter :leave
   :custom-resource-path nil})

(defn- configure-selmer!
  "Configure Selmer settings for template rendering."
  [config]
  (let [{:keys [missing-value-formatter]} (merge default-config config)]
    (selmer-util/set-missing-value-formatter!
      (case missing-value-formatter
        :leave (fn [tag _context-map]
                 (str "{{" (:tag-value tag) "}}"))
        :empty (constantly "")
        :error (fn [tag _context-map]
                 (throw (ex-info (str "Missing template variable: "
                                      (:tag-value tag))
                                 {:type :missing-variable
                                  :variable (:tag-value tag)})))
        missing-value-formatter))))

;; Template Rendering

(defn render
  "Render template string with variable substitution.

  Parameters:
  - template: String containing template markup (e.g., \"Hello {{name}}\")
  - context: Map of variable names to values (e.g., {:name \"World\"})
  - opts: Optional configuration map
    - :missing-value-formatter - How to handle missing variables:
      - :leave (default) - Leave {{var}} as-is
      - :empty - Replace with empty string
      - :error - Throw exception
      - Custom fn taking [tag context-map]

  Returns the rendered string.

  Example:
    (render \"Hello {{name}}!\" {:name \"World\"})
    ;=> \"Hello World!\""
  ([template context]
   (render template context {}))
  ([template context opts]
   (configure-selmer! opts)
   (selmer/render template context)))

(defn render-file
  "Render template from file path with variable substitution.

  Parameters:
  - file-path: Path to template file
  - context: Map of variable names to values
  - opts: Optional configuration (see render)

  Returns the rendered string.

  Throws if file does not exist."
  ([file-path context]
   (render-file file-path context {}))
  ([file-path context opts]
   (let [template (slurp file-path)]
     (render template context opts))))

(defn render-resource
  "Render template from classpath resource with variable substitution.

  Parameters:
  - resource-path: Classpath resource path (e.g., \"prompts/execute-task.md\")
  - context: Map of variable names to values
  - opts: Optional configuration (see render)

  Returns the rendered string, or nil if resource not found."
  ([resource-path context]
   (render-resource resource-path context {}))
  ([resource-path context opts]
   (when-let [url (io/resource resource-path)]
     (render (slurp url) context opts))))

;; Include Support

(defn create-resource-loader
  "Create a resource loader function for Selmer includes.

  The loader resolves include paths by checking multiple locations:
  1. User override directory (if provided)
  2. Builtin resources on classpath

  Parameters:
  - override-dir: Optional directory path for user overrides
  - resource-base: Base path for classpath resources

  Returns a function that Selmer can use to load included templates."
  [override-dir resource-base]
  (fn [path]
    (let [;; Try override directory first
          override-path (when override-dir
                          (let [full-path (str override-dir "/" path)]
                            (when (.exists (io/file full-path))
                              full-path)))
          ;; Fall back to classpath resource
          resource-path (when-not override-path
                          (str resource-base "/" path))]
      (cond
        override-path
        (slurp override-path)

        (io/resource resource-path)
        (slurp (io/resource resource-path))

        :else
        (throw (ex-info (str "Template include not found: " path)
                        {:type :missing-include
                         :path path
                         :searched [(when override-dir
                                      (str override-dir "/" path))
                                    resource-path]}))))))

(defn render-with-includes
  "Render template with support for file includes and variable substitution.

  Uses {% include \"path\" %} syntax for includes.

  Parameters:
  - template: String containing template markup
  - context: Map of variable names to values
  - opts: Configuration map
    - :override-dir - Directory for user template overrides (optional)
    - :resource-base - Base path for classpath resources (default: \"\")
    - :missing-value-formatter - How to handle missing variables (see render)

  Returns the rendered string.

  Example:
    (render-with-includes
      \"{% include \\\"header.md\\\" %}\\nHello {{name}}!\"
      {:name \"World\"}
      {:resource-base \"prompts/infrastructure\"})

  Include resolution order:
  1. :override-dir/path (if override-dir provided and file exists)
  2. :resource-base/path (on classpath)"
  ([template context]
   (render-with-includes template context {}))
  ([template context opts]
   (let [{:keys [override-dir resource-base]
          :or {resource-base ""}} opts
         loader (create-resource-loader override-dir resource-base)
         ;; Pre-process includes before Selmer rendering
         processed (loop [content template
                          depth 0]
                     (if (> depth 10)
                       (throw (ex-info "Include depth exceeded (possible circular include)"
                                       {:type :include-error
                                        :depth depth}))
                       (let [include-pattern #"\{%\s*include\s+\"([^\"]+)\"\s*%\}"
                             matches (re-seq include-pattern content)]
                         (if (empty? matches)
                           content
                           (recur
                             (str/replace content include-pattern
                                          (fn [[_ path]]
                                            (try
                                              (loader path)
                                              (catch Exception e
                                                (throw (ex-info (str "Failed to load include: " path)
                                                                {:type :include-error
                                                                 :path path}
                                                                e))))))
                             (inc depth))))))]
     (configure-selmer! opts)
     (selmer/render processed context))))

;; Error Handling

(defn template-error?
  "Check if an exception is a template-related error."
  [ex]
  (and (instance? clojure.lang.ExceptionInfo ex)
       (#{:missing-variable :missing-include :include-error}
        (:type (ex-data ex)))))

(defn format-error
  "Format a template error for display.

  Returns a human-readable error message."
  [ex]
  (if (template-error? ex)
    (let [{:keys [type variable path searched]} (ex-data ex)]
      (case type
        :missing-variable
        (str "Template error: Missing variable '" variable "'")

        :missing-include
        (str "Template error: Include file not found '" path "'\n"
             "Searched in: " (str/join ", " (remove nil? searched)))

        :include-error
        (str "Template error: Failed to load include '" path "'")

        (ex-message ex)))
    (ex-message ex)))
