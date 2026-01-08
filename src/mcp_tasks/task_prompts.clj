(ns mcp-tasks.task-prompts
  "Task prompt templates for category and workflow prompts.

  Category prompts define execution workflows for different task complexities.
  Workflow prompts define specific task operations (execute, refine, etc.)."
  (:require
    [clojure.java.io :as io]))

(def simple
  "Execute simple tasks with basic workflow"
  (slurp (io/resource "category-prompts/simple.md")))

(def clarify-task
  "Transform informal task instructions into clear, explicit specifications"
  (slurp (io/resource "category-prompts/clarify-task.md")))

(def medium
  "Execute medium complexity tasks with analysis, design, and user interaction"
  (slurp (io/resource "category-prompts/medium.md")))

(def large
  "Execute large tasks with detailed analysis, design, and user interaction"
  (slurp (io/resource "category-prompts/large.md")))

(def bugfix
  "Fix bugs through systematic debugging workflow"
  (slurp (io/resource "category-prompts/bugfix.md")))
