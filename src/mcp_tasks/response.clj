(ns mcp-tasks.response
  "Response formatting utilities for MCP tools.")

(defn error-response
  "Create a standardized error response for MCP tools.

  Takes an exception and returns a map with :content and :isError keys.
  The error message includes the exception message and ex-data details if present.

  Parameters:
  - e: Exception to format

  Returns:
  {:content [{:type \"text\" :text \"Error: ...\"}]
   :isError true}"
  [e]
  {:content [{:type "text"
              :text (str "Error: " (.getMessage e)
                         (when-let [data (ex-data e)]
                           (str "\nDetails: " (pr-str data))))}]
   :isError true})
