(ns mcp-tasks.path-helper
  "Helper functions for constructing .mcp-tasks paths"
  (:require
    [clojure.string :as str]))

(defn task-path
  "Construct .mcp-tasks paths with base-dir handling.

  Parameters:
  - config: Configuration map containing optional :base-dir
  - path-segments: Vector of path segments (e.g., [\"story\" \"stories\" \"foo.md\"])

  Returns map with:
  - :absolute - Full filesystem path
  - :relative - Path relative to .mcp-tasks root (for git operations)

  Examples:
    (task-path {} [\"tasks.ednl\"])
    => {:absolute \".mcp-tasks/tasks.ednl\"
        :relative \"tasks.ednl\"}

    (task-path {:base-dir \"/home/user\"} [\"complete.ednl\"])
    => {:absolute \"/home/user/.mcp-tasks/complete.ednl\"
        :relative \"complete.ednl\"}"
  [config path-segments]
  (let [base-dir (:base-dir config)
        relative-path (str/join "/" path-segments)
        absolute-path (if base-dir
                        (str base-dir "/.mcp-tasks/" relative-path)
                        (str ".mcp-tasks/" relative-path))]
    {:absolute absolute-path
     :relative relative-path}))
