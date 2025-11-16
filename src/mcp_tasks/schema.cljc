(ns mcp-tasks.schema
  "Malli schemas for task management system.

  Uses lazy-loading via dynaload and compiled validators to avoid loading
  Malli at namespace load time. When AOT-compiled with
  -Dborkdude.dynaload.aot=true, dynaload enables direct linking for reduced
  binary size."
  (:require
    [borkdude.dynaload :refer [dynaload]]))

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
   [:relations [:vector Relation]]
   [:shared-context {:optional true} [:vector :string]]])

(def blocking-statuses
  "Set of task statuses that prevent completion or deletion of parent tasks.

  Tasks with these statuses are considered 'blocking' because they represent
  incomplete or problematic work. Tasks with :status :closed or :status :deleted
  are considered non-blocking as they represent completed or removed work."
  #{:open :in-progress :blocked})

;; Validation Helpers

;; Lazy-loaded Malli functions via dynaload
;; dynaload defers namespace loading until first call, with fallbacks for
;; environments without Malli (e.g., standalone uberscript).

(def ^:private malli-validator
  "Lazy reference to malli.core/validator.
  Falls back to a function returning always-true validator when Malli unavailable."
  (dynaload 'malli.core/validator {:default (constantly (fn [_] true))}))

(def ^:private malli-explainer
  "Lazy reference to malli.core/explainer.
  Falls back to a function returning always-nil explainer when Malli unavailable."
  (dynaload 'malli.core/explainer {:default (constantly (fn [_] nil))}))

;; Compiled validators
;; dynaload handles lazy loading, so no delays needed

(def relation-validator
  "Compiled validator for Relation schema."
  (malli-validator Relation))

(def task-validator
  "Compiled validator for Task schema."
  (malli-validator Task))

(def relation-explainer
  "Compiled explainer for Relation schema."
  (malli-explainer Relation))

(def task-explainer
  "Compiled explainer for Task schema."
  (malli-explainer Task))

(defn valid-relation?
  "Validate a relation map against the Relation schema."
  [relation]
  (relation-validator relation))

(defn valid-task?
  "Validate a task map against the Task schema."
  [task]
  (task-validator task))

(defn explain-relation
  "Explain why a relation map is invalid.
  Returns nil if valid, explanation map if invalid."
  [relation]
  (relation-explainer relation))

(defn explain-task
  "Explain why a task map is invalid.
  Returns nil if valid, explanation map if invalid."
  [task]
  (task-explainer task))

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
