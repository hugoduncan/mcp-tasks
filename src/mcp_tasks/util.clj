(ns mcp-tasks.util
  "Utility functions for mcp-tasks."
  (:require
    [clojure.string :as str]))

(defn sanitize-branch-name
  "Sanitize a title into a valid git branch name.
  
  Converts the title by:
  - Converting to lowercase
  - Replacing spaces with dashes
  - Removing all special characters (keeping only a-z, 0-9, -)
  - Replacing multiple consecutive dashes with single dash
  - Trimming leading/trailing dashes
  - Truncating to 200 characters if longer
  
  Returns task-<id> fallback if sanitized result is empty.
  
  Examples:
  (sanitize-branch-name \"Complete Remaining Work\" 45)
  => \"complete-remaining-work\"
  
  (sanitize-branch-name \"Fix bug #123\" 10)
  => \"fix-bug-123\"
  
  (sanitize-branch-name \"!!!\" 45)
  => \"task-45\"
  
  (sanitize-branch-name \"ui\" 1)
  => \"ui\""
  [title task-id]
  (let [sanitized (-> title
                      str/lower-case
                      (str/replace #"\s+" "-")
                      (str/replace #"[^a-z0-9-]" "")
                      (str/replace #"-+" "-")
                      (str/replace #"^-+" "")
                      (str/replace #"-+$" ""))]
    (cond
      (str/blank? sanitized) (str "task-" task-id)
      (> (count sanitized) 200) (subs sanitized 0 200)
      :else sanitized)))
