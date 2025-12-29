#!/usr/bin/env bb
;; PreCompact hook for capturing compaction events during story execution.
;;
;; This hook is triggered before Claude Code compacts the conversation context.
;; It checks if a story is currently being executed (via .mcp-tasks-current.edn),
;; and if so, appends a session event to the story task.
;;
;; Input: JSON via stdin with structure:
;;   {"session_id": "...", "trigger": "manual"|"auto", "cwd": "...", ...}
;;
;; Behavior:
;; - Reads .mcp-tasks-current.edn from cwd to check for :story-id
;; - If story is executing, calls `mcp-tasks update` to append session event
;; - Always exits 0 (non-blocking) even on errors
;;
;; Exit codes:
;; - 0: Success or graceful failure (non-blocking)

(require '[clojure.java.io :as io]
         '[cheshire.core :as json])

;; Load common utilities from the same directory
(def script-dir (-> *file* io/file .getParent))
(load-file (str script-dir "/common.bb"))

(defn main []
  (let [input-str (read-stdin)
        input (parse-json input-str)]
    (when input
      (let [cwd (:cwd input)
            trigger (:trigger input)
            exec-state (when cwd (read-execution-state cwd))]
        ;; Only proceed if a story is being executed
        (when-let [story-id (:story-id exec-state)]
          ;; Build session event with trigger type
          (let [event {:event-type "compaction"
                       :trigger (or trigger "unknown")}
                event-json (json/generate-string event)]
            ;; Call mcp-tasks to update the story
            (call-mcp-tasks-update cwd story-id event-json)))))))

(run-hook main)
