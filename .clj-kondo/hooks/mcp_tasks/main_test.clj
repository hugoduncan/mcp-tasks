(ns hooks.mcp-tasks.main-test
  (:require
    [clj-kondo.hooks-api :as api]))

(defn with-temp-categories
  [{:keys [node]}]
  (let [[_categories _git-mode? binding-vec & body] (rest (:children node))
        bindings (:children binding-vec)
        ;; Create let bindings: [sym1 nil sym2 nil ...]
        let-bindings (api/vector-node
                       (mapcat (fn [sym] [sym (api/token-node 'nil)])
                               bindings))
        new-node (api/list-node
                   (list
                     (api/token-node 'let)
                     let-bindings
                     (api/list-node
                       (cons (api/token-node 'do) body))))]
    {:node new-node}))
