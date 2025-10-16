(ns mcp-tasks.tools.helpers
  "General helper functions for tool implementations"
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]))

(defn file-exists?
  "Check if a file exists"
  [file-path]
  (.exists (io/file file-path)))

(defn truncate-title
  "Truncate a title to a maximum length, adding ellipsis if needed.

  Parameters:
  - title: The title string to truncate
  - max-length: Maximum length (default 50)

  Returns truncated string with '...' suffix if longer than max-length."
  ([title]
   (truncate-title title 50))
  ([title max-length]
   (if (> (count title) max-length)
     (str (subs title 0 (- max-length 3)) "...")
     title)))

(defn build-tool-error-response
  "Build standardized two-content-item error response.

  Parameters:
  - error-message: Human-readable error message (string)
  - operation: Operation that failed (string)
  - error-metadata: Additional metadata map to include

  Returns error response map with :content and :isError keys."
  [error-message operation error-metadata]
  {:content [{:type "text"
              :text error-message}
             {:type "text"
              :text (json/write-str
                      {:error error-message
                       :metadata (merge {:attempted-operation operation}
                                        error-metadata)})}]
   :isError true})

(defn build-completion-response
  "Build standardized completion response with optional git integration.

  Parameters:
  - msg-text: Human-readable completion message (string)
  - modified-files: Vector of relative file paths that were modified
  - use-git?: Whether git integration is enabled (boolean)
  - git-result: Optional map with :success, :commit-sha, :error keys

  Returns response map with :content and :isError keys."
  [msg-text modified-files use-git? git-result]
  (if use-git?
    {:content [{:type "text"
                :text msg-text}
               {:type "text"
                :text (json/write-str {:modified-files modified-files})}
               {:type "text"
                :text (json/write-str
                        (cond-> {:git-status (if (:success git-result)
                                               "success"
                                               "error")
                                 :git-commit-sha (:commit-sha git-result)}
                          (:error git-result)
                          (assoc :git-error (:error git-result))))}]
     :isError false}
    {:content [{:type "text"
                :text msg-text}]
     :isError false}))
