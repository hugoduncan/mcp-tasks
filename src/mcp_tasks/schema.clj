(ns mcp-tasks.schema
  "Malli schemas for task management system."
  (:require
    [malli.core :as m]))

;; Schema Definitions

(def Relation
  "Schema for task relationships.

  Defines how one task relates to another through various relationship types."
  [:map
   [:id :int]
   [:relates-to :int]
   [:as-type [:enum :blocked-by :related :discovered-during]]])

(def Task
  "Schema for a task entity.

  A task represents a unit of work with metadata, status, and relationships."
  [:map
   [:id :int]
   [:parent-id {:optional true} [:maybe :int]]
   [:status [:enum :open :closed :in-progress :blocked]]
   [:title :string]
   [:description :string]
   [:design :string]
   [:category :string]
   [:type [:enum :task :bug :feature :story :chore]]
   [:meta [:map-of :string :string]]
   [:relations [:vector Relation]]])

;; Validation Helpers

(defn valid-relation?
  "Validate a relation map against the Relation schema."
  [relation]
  (m/validate Relation relation))

(defn valid-task?
  "Validate a task map against the Task schema."
  [task]
  (m/validate Task task))

(defn explain-relation
  "Explain why a relation map is invalid.
  Returns nil if valid, explanation map if invalid."
  [relation]
  (m/explain Relation relation))

(defn explain-task
  "Explain why a task map is invalid.
  Returns nil if valid, explanation map if invalid."
  [task]
  (m/explain Task task))

;; Example Data

(def example-relation
  "Example relation for testing and documentation."
  {:id 1
   :relates-to 2
   :as-type :blocked-by})

(def example-task
  "Example task for testing and documentation."
  {:id 1
   :parent-id nil
   :status :open
   :title "Create schema namespace"
   :description "Add Malli schemas for Task and Relation"
   :design "Use malli.core for validation"
   :category "simple"
   :type :task
   :meta {"priority" "high"}
   :relations [example-relation]})
