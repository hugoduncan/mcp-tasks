(ns mcp-tasks.resources
  "Resource definitions for MCP server")

(defn- prompt-resource-implementation
  "Implementation function for reading a prompt resource.
  
  Returns the prompt text content for the given URI."
  [_context prompts-map uri]
  (let [prompt-name (subs uri (count "prompt://"))
        prompt (get prompts-map prompt-name)]
    (if prompt
      (let [messages (:messages prompt)
            ;; Extract text content from the first message
            text (-> messages first :content :text)]
        {:contents [{:uri uri
                     :mimeType "text/markdown"
                     :text text}]})
      {:contents [{:uri uri
                   :text (str "Prompt not found: " prompt-name)}]
       :isError true})))

(defn prompt-resources
  "Create resource definitions for all prompts.
  
  Takes a map of prompts (already merged from tp/prompts and tp/story-prompts).
  Returns a map of resource URIs to resource definitions.
  Each prompt is exposed as a resource with URI pattern: prompt://<prompt-name>"
  [prompts-map]
  (into {}
        (for [[prompt-name prompt] prompts-map]
          (let [uri (str "prompt://" prompt-name)
                description (:description prompt)]
            [uri
             {:name prompt-name
              :uri uri
              :mime-type "text/markdown"
              :description description
              :implementation (fn [context uri]
                                (prompt-resource-implementation context prompts-map uri))}]))))
