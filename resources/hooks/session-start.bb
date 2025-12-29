#!/usr/bin/env bb
;; SessionStart hook for capturing session start events during story execution.
;;
;; This hook is triggered when Claude Code starts a new session or resumes.
;; It checks if a story is currently being executed (via .mcp-tasks-current.edn),
;; and if so, appends a session event to the story task.
;;
;; Input: JSON via stdin with structure:
;;   {"session_id": "...", "source": "startup"|"resume"|"clear"|"compact", "cwd": "...", ...}
;;
;; Behavior:
;; - Reads .mcp-tasks-current.edn from cwd to check for :story-id
;; - If story is executing, calls `mcp-tasks update` to append session event
;; - Always exits 0 (non-blocking) even on errors
;;
;; Exit codes:
;; - 0: Success or graceful failure (non-blocking)

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

(defn main []
  ;; Read and parse hook input
  (let [input-str (read-stdin)
        input (parse-json input-str)]
    (when input
      (let [cwd (:cwd input)
            session-id (:session_id input)
            source (:source input)
            exec-state (when cwd (read-execution-state cwd))]
        ;; Only proceed if a story is being executed
        (when-let [story-id (:story-id exec-state)]
          ;; Build session event with session-id
          (let [event (cond-> {:event-type "session-start"}
                        session-id (assoc :session-id session-id)
                        source (assoc :trigger source))
                event-json (json/generate-string event)]
            ;; Call mcp-tasks to update the story
            (call-mcp-tasks-update cwd story-id event-json)))))))

;; Run main and always exit 0
(try
  (main)
  (catch Exception _
    nil))

(System/exit 0)
