(ns mcp-tasks.util
  "Utility functions for mcp-tasks."
  (:require
    [clojure.string :as str]))

(defn sanitize-branch-name
  "Sanitize a title into a valid git branch name with task ID prefix.
  
  The title is processed by:
  1. Handling empty/blank titles (returns \"task-<id>\")
  2. Splitting into words and taking first `word-limit` words (all if nil)
  3. Joining words with spaces
  4. Converting to lowercase
  5. Replacing spaces with dashes
  6. Removing all special characters (keeping only a-z, 0-9, -)
  7. Replacing multiple consecutive dashes with single dash
  8. Trimming leading/trailing dashes
  9. Prepending task ID: \"<id>-<slug>\"
  10. Truncating to 200 characters if longer
  
  Returns \"task-<id>\" if title is empty or becomes empty after slugification.
  
  Parameters:
  - title: The title string to sanitize
  - task-id: The task ID number (used as prefix)
  - word-limit: Maximum number of words to use from title (nil = unlimited)
  
  Examples:
  (sanitize-branch-name \"Implement user authentication with OAuth support\" 123 4)
  => \"123-implement-user-authentication-with\"
  
  (sanitize-branch-name \"Implement user authentication\" 123 2)
  => \"123-implement-user\"
  
  (sanitize-branch-name \"Fix bug #456\" 10 nil)
  => \"10-fix-bug-456\"
  
  (sanitize-branch-name \"!!!\" 45 4)
  => \"task-45\"
  
  (sanitize-branch-name \"ui\" 5 4)
  => \"5-ui\""
  [title task-id word-limit]
  ;; Handle empty/blank title edge case
  (if (str/blank? title)
    (str "task-" task-id)
    (let [;; Split into words
          words (str/split (str/trim title) #"\s+")
          ;; Take first N words (or all if limit is nil or exceeds available)
          limited-words (if (or (nil? word-limit)
                                (>= word-limit (count words)))
                          words
                          (take word-limit words))
          ;; Join with spaces
          limited-title (str/join " " limited-words)
          ;; Slugify: lowercase, spaces to dashes, remove special chars
          slug (-> limited-title
                   str/lower-case
                   (str/replace #"\s+" "-")
                   (str/replace #"[^a-z0-9-]" "")
                   (str/replace #"-+" "-")
                   (str/replace #"^-+" "")
                   (str/replace #"-+$" ""))
          ;; Prepend ID
          with-id (if (str/blank? slug)
                    (str "task-" task-id)
                    (str task-id "-" slug))]
      ;; Truncate if needed
      (if (> (count with-id) 200)
        (subs with-id 0 200)
        with-id))))
