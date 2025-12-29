#!/usr/bin/env bb
;; Common utilities for mcp-tasks hook scripts.
;;
;; This file provides shared functions used by the session event capture hooks:
;; - user-prompt-submit.bb
;; - pre-compact.bb
;; - session-start.bb
;;
;; Load this file from a hook script using:
;;   (def script-dir (-> *file* io/file .getParent))
;;   (load-file (str script-dir "/common.bb"))

(require '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io])

(defn read-stdin
  "Read all input from stdin."
  []
  (slurp *in*))

(defn parse-json
  "Parse JSON string, returning nil on error."
  [s]
  (try
    (json/parse-string s keyword)
    (catch Exception _
      nil)))

(defn read-execution-state
  "Read .mcp-tasks-current.edn from the given directory.
  Returns nil if file doesn't exist or can't be parsed."
  [dir]
  (let [f (io/file dir ".mcp-tasks-current.edn")]
    (when (.exists f)
      (try
        (edn/read-string (slurp f))
        (catch Exception _
          nil)))))

(defn call-mcp-tasks-update
  "Call mcp-tasks update to append a session event.
  Returns true on success, false on failure."
  [dir story-id event-json]
  (try
    (let [result (p/shell {:dir dir
                           :out :string
                           :err :string
                           :continue true}
                          "mcp-tasks" "update"
                          "--task-id" (str story-id)
                          "--session-events" event-json)]
      (zero? (:exit result)))
    (catch Exception _
      false)))

(defn truncate-content
  "Truncate content to max-chars if needed, appending ... if truncated."
  [s max-chars]
  (if (> (count s) max-chars)
    (str (subs s 0 (- max-chars 3)) "...")
    s))

(defn run-hook
  "Run a hook with standard error handling.

  Takes a main-fn that processes the hook input and returns nil.
  Ensures the hook always exits 0 (non-blocking) even on errors."
  [main-fn]
  (try
    (main-fn)
    (catch Exception _
      nil))
  (System/exit 0))
