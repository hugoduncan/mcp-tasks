(ns mcp-tasks.resources
  "Resource definitions for MCP server"
  (:require
    [clojure.string :as str]
    [mcp-tasks.execution-state :as execution-state]))

(defn- format-argument-hint
  "Format argument hint from prompt :arguments vector.

  Converts argument maps to hint string format:
  - Required args: <arg-name>
  - Optional args: [arg-name]

  Returns nil if arguments vector is empty or nil."
  [arguments]
  (when (seq arguments)
    (str/join " "
              (for [arg arguments]
                (if (:required arg)
                  (str "<" (:name arg) ">")
                  (str "[" (:name arg) "]"))))))

(defn- build-frontmatter
  "Build YAML frontmatter string from prompt metadata.

  Includes description and argument-hint (if present).
  Returns frontmatter string with delimiters or nil if no metadata."
  [prompt]
  (let [description (:description prompt)
        argument-hint (format-argument-hint (:arguments prompt))
        lines (cond-> []
                description (conj (str "description: " description))
                argument-hint (conj (str "argument-hint: " argument-hint)))]
    (when (seq lines)
      (str "---\n"
           (str/join "\n" lines)
           "\n---\n"))))

(defn- prompt-resource-implementation
  "Implementation function for reading a prompt resource.

  Returns the prompt text content with YAML frontmatter for the given URI."
  [_context prompts-map uri]
  (let [prompt-name (subs uri (count "prompt://"))
        prompt (get prompts-map prompt-name)]
    (if prompt
      (let [messages (:messages prompt)
            ;; Extract text content from the first message
            text (-> messages first :content :text)
            frontmatter (build-frontmatter prompt)
            full-text (str frontmatter text)]
        {:contents [{:uri uri
                     :mimeType "text/markdown"
                     :text full-text}]})
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
                description (:description prompt)
                impl-fn (fn [context uri]
                          (prompt-resource-implementation
                            context
                            prompts-map
                            uri))]
            [uri
             {:name prompt-name
              :uri uri
              :mime-type "text/markdown"
              :description description
              :implementation impl-fn}]))))

(defn category-prompt-resources
  "Create resource definitions for category prompts.

  Takes a vector of category prompt resource maps from
  tp/category-prompt-resources.  Returns a map of resource URIs to
  resource definitions.  Each category prompt is exposed as a resource
  with URI pattern: prompt://category-<category>"
  [category-resources-vec]
  (into {}
        (for [resource category-resources-vec]
          (let [uri (:uri resource)
                impl-fn (fn [_context _uri]
                          {:contents [resource]})]
            [uri
             {:name (:name resource)
              :uri uri
              :mime-type (:mimeType resource)
              :description (:description resource)
              :implementation impl-fn}]))))

(defn current-execution-resource
  "Create resource definition for current execution state.

  Returns a resource definition that exposes the current story/task
  execution state from .mcp-tasks-current.edn.

  Parameters:
  - base-dir: Path to project root directory (where .mcp-tasks-current.edn lives)"
  [base-dir]
  (let [uri "resource://current-execution"
        impl-fn (fn [_context _uri]
                  (let [state (execution-state/read-execution-state base-dir)]
                    (if state
                      {:contents [{:uri uri
                                   :mimeType "application/json"
                                   :text (pr-str state)}]}
                      {:contents [{:uri uri
                                   :mimeType "application/json"
                                   :text "nil"}]})))]
    {uri
     {:name "current-execution"
      :uri uri
      :mime-type "application/json"
      :description "Current story and task execution state"
      :implementation impl-fn}}))
