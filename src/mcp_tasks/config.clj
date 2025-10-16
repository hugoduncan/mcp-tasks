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
  (when-let [story-branch-mgmt (:story-branch-management? config)]
    (when-not (boolean? story-branch-mgmt)
      (throw (ex-info (str "Expected boolean for :story-branch-management?, got " (type story-branch-mgmt))
                      {:type :invalid-config-type
                       :key :story-branch-management?
                       :value story-branch-mgmt
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

;; Git auto-detection

(defn git-repo-exists?
  "Checks if .mcp-tasks/.git directory exists in the project directory.
  Returns true if the git repository exists, false otherwise."
  [project-dir]
  (let [git-dir (io/file project-dir ".mcp-tasks" ".git")]
    (.exists git-dir)))

(defn determine-git-mode
  "Determines whether to use git mode based on config and auto-detection.
  Returns boolean indicating if git mode should be enabled.

  Precedence:
  1. Explicit config value (:use-git?) if present
  2. Auto-detected presence of .mcp-tasks/.git directory"
  [project-dir config]
  (if (contains? config :use-git?)
    (:use-git? config)
    (git-repo-exists? project-dir)))

(defn resolve-config
  "Returns final config map with :use-git? and :base-dir resolved.
  Uses explicit config value if present, otherwise auto-detects from git
  repo presence.  Base directory defaults to current working directory
  if project-dir not provided."
  [project-dir config]
  (let [base-dir (or project-dir (System/getProperty "user.dir"))]
    (assoc config
           :use-git? (determine-git-mode project-dir config)
           :base-dir base-dir)))

;; Startup validation

(defn validate-git-repo
  "Validates that git repository exists when git mode is enabled.
  Returns nil on success.
  Throws ex-info with clear message if validation fails."
  [project-dir config]
  (when (:use-git? config)
    (when-not (git-repo-exists? project-dir)
      (throw (ex-info "Git mode enabled but .mcp-tasks/.git not found"
                      {:type :git-repo-missing
                       :project-dir project-dir
                       :git-dir (str project-dir "/.mcp-tasks/.git")}))))
  nil)

(defn validate-startup
  "Performs all startup validation (config + git repo).
  Returns nil on success.
  Throws ex-info with clear message if any validation fails."
  [project-dir config]
  (validate-git-repo project-dir config)
  nil)
