(ns mcp-tasks.story-prompts
  "Built-in story prompt templates.

  Story prompts are used for story-based workflow management. Each prompt is
  defined as a def var with a docstring and content that includes frontmatter
  metadata."
  (:require
    [clojure.java.io :as io]
    [mcp-tasks.templates :as templates]))

(defn- load-and-render-prompt
  "Load prompt from classpath resource and render template includes.
  
  Resolves {% include \"path\" %} directives using prompts/ as the resource base."
  [resource-path]
  (when-let [url (io/resource resource-path)]
    (let [template (slurp url)]
      (templates/render-with-includes template {} {:resource-base "prompts"}))))

(def create-story-tasks
  "Break down a story into categorized, executable tasks."
  (load-and-render-prompt "prompts/create-story-tasks.md"))

(def execute-story-child
  "Execute the next task from a story's task list."
  (load-and-render-prompt "prompts/execute-story-child.md"))

(def review-story-implementation
  "Review the implementation of a story."
  (load-and-render-prompt "prompts/review-story-implementation.md"))

(def review-task-implementation
  "Review the implementation of a task before completion."
  (load-and-render-prompt "prompts/review-task-implementation.md"))

(def complete-story
  "Mark a story as complete and archive it."
  (load-and-render-prompt "prompts/complete-story.md"))

(def create-story-pr
  "Create a pull request for a completed story."
  (slurp (io/resource "prompts/create-story-pr.md")))

(def execute-task
  "Execute a task following category-specific workflow instructions."
  (slurp (io/resource "prompts/execute-task.md")))

(def refine-task
  "Refine a task to improve clarity and completeness."
  (slurp (io/resource "prompts/refine-task.md")))

(def optimize-prompts
  "Optimize prompts based on session event analysis."
  (load-and-render-prompt "prompts/optimize-prompts.md"))
