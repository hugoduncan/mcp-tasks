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
    (task-path {} [\"tasks\" \"simple.md\"])
    => {:absolute \".mcp-tasks/tasks/simple.md\"
        :relative \"tasks/simple.md\"}

    (task-path {:base-dir \"/home/user\"} [\"story\" \"stories\" \"foo.md\"])
    => {:absolute \"/home/user/.mcp-tasks/story/stories/foo.md\"
        :relative \"story/stories/foo.md\"}"
  [config path-segments]
  (let [base-dir (:base-dir config)
        relative-path (str/join "/" path-segments)
        absolute-path (if base-dir
                        (str base-dir "/.mcp-tasks/" relative-path)
                        (str ".mcp-tasks/" relative-path))]
    {:absolute absolute-path
     :relative relative-path}))
