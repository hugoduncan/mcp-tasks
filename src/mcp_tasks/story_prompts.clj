(ns mcp-tasks.story-prompts
  "Built-in story prompt templates.

  Story prompts are used for story-based workflow management. Each prompt is
  defined as a def var with a docstring and content that includes frontmatter
  metadata."
  (:require [clojure.java.io :as io]))

(def refine-story
  "Interactively refine a story document with user feedback."
  (slurp (io/resource "story/prompts/refine-story.md")))

(def create-story-tasks
  "Break down a story into categorized, executable tasks."
  (slurp (io/resource "story/prompts/create-story-tasks.md")))

(def execute-story-task
  "Execute the next task from a story's task list."
  (slurp (io/resource "story/prompts/execute-story-task.md")))
