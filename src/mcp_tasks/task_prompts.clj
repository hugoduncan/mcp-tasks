(ns mcp-tasks.task-prompts
  "Default task prompt templates for installation to .mcp-tasks/prompt/"
  (:require
    [clojure.java.io :as io]))

(def simple
  "Execute simple tasks with basic workflow"
  (slurp (io/resource "prompts/simple.md")))

(def clarify-task
  "Transform informal task instructions into clear, explicit specifications"
  (slurp (io/resource "prompts/clarify-task.md")))

(def medium
  "Execute medium complexity tasks with analysis, design, and user interaction"
  (slurp (io/resource "prompts/medium.md")))

(def large
  "Execute large tasks with detailed analysis, design, and user interaction"
  (slurp (io/resource "prompts/large.md")))
