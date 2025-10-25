(ns mcp-tasks.schema
  "Malli schemas for task management system.
  
  Uses lazy-loading via requiring-resolve and compiled validators with delays
  to avoid loading Malli at namespace load time.")

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
   [:status [:enum :open :closed :in-progress :blocked :deleted]]
   [:title :string]
   [:description :string]
   [:design :string]
   [:category :string]
   [:type [:enum :task :bug :feature :story :chore]]
   [:meta [:map-of :string :string]]
   [:relations [:vector Relation]]])

(def blocking-statuses
  "Set of task statuses that prevent completion or deletion of parent tasks.
  
  Tasks with these statuses are considered 'blocking' because they represent
  incomplete or problematic work. Tasks with :status :closed or :status :deleted
  are considered non-blocking as they represent completed or removed work."
  #{:open :in-progress :blocked})

;; Validation Helpers

;; Lazy-loaded Malli functions
;; Using requiring-resolve to avoid loading malli.core at namespace load time

;; Compiled validators using delays
;; Both requiring-resolve AND validator compilation happen lazily

(def relation-validator
  "Compiled validator for Relation schema."
  (delay ((requiring-resolve 'malli.core/validator) Relation)))

(def task-validator
  "Compiled validator for Task schema."
  (delay ((requiring-resolve 'malli.core/validator) Task)))

(def relation-explainer
  "Compiled explainer for Relation schema."
  (delay ((requiring-resolve 'malli.core/explainer) Relation)))

(def task-explainer
  "Compiled explainer for Task schema."
  (delay ((requiring-resolve 'malli.core/explainer) Task)))

(defn valid-relation?
  "Validate a relation map against the Relation schema."
  [relation]
  (@relation-validator relation))

(defn valid-task?
  "Validate a task map against the Task schema."
  [task]
  (@task-validator task))

(defn explain-relation
  "Explain why a relation map is invalid.
  Returns nil if valid, explanation map if invalid."
  [relation]
  (@relation-explainer relation))

(defn explain-task
  "Explain why a task map is invalid.
  Returns nil if valid, explanation map if invalid."
  [task]
  (@task-explainer task))

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
