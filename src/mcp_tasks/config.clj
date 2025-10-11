(ns mcp-tasks.config
  "Configuration management for mcp-tasks"
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]))

(defn validate-config
  "Validates config map structure.
  Returns config if valid, throws ex-info with descriptive error otherwise."
  [config]
  (when-not (map? config)
    (throw (ex-info "Config must be a map"
                    {:type :invalid-config
                     :config config})))
  (when-let [use-git (:use-git? config)]
    (when-not (boolean? use-git)
      (throw (ex-info (str "Expected boolean for :use-git?, got " (type use-git))
                      {:type :invalid-config-type
                       :key :use-git?
                       :value use-git
                       :expected 'boolean?}))))
  config)

(defn read-config
  "Reads and validates .mcp-tasks.edn from project directory.
  Returns nil if file doesn't exist.
  Returns validated config map if file exists and is valid.
  Throws ex-info with clear message for malformed EDN or invalid schema."
  [project-dir]
  (let [config-file (io/file project-dir ".mcp-tasks.edn")]
    (if (.exists config-file)
      (try
        (let [config (edn/read-string (slurp config-file))]
          (validate-config config))
        (catch clojure.lang.ExceptionInfo e
          ;; Re-throw validation errors as-is
          (throw e))
        (catch Exception e
          ;; Wrap EDN parsing errors with context
          (throw (ex-info (str "Failed to parse .mcp-tasks.edn: " (.getMessage e))
                          {:type :malformed-edn
                           :file (.getPath config-file)
                           :cause e}
                          e))))
      nil)))
