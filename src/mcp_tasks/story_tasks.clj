(ns mcp-tasks.story-tasks
  "Utilities for parsing and manipulating story task files"
  (:require
    [clojure.string :as str]))

(defn- parse-task-block
  "Parse a single task block into a map.

  A task block consists of:
  - A checkbox line: - [ ] or - [x]
  - Zero or more continuation lines (indented)
  - A CATEGORY: line

  Returns {:text \"...\" :category \"...\" :complete? bool :index int}
  Throws if CATEGORY line is missing."
  [lines index]
  (let [first-line (first lines)
        checkbox-pattern #"^- \[([ x])\] (.*)"]
    (when-not (re-matches checkbox-pattern first-line)
      (throw (ex-info "Invalid task format: missing checkbox"
                      {:line first-line :index index})))

    (let [[_ checkbox-char _] (re-find checkbox-pattern first-line)
          complete? (= checkbox-char "x")]
      ;; Find the CATEGORY line
      (loop [remaining (rest lines)
             task-lines [first-line]]
        (if (empty? remaining)
          (throw (ex-info "Invalid task format: missing CATEGORY line"
                          {:index index :lines lines}))
          (let [line (first remaining)]
            (if (str/starts-with? line "CATEGORY:")
              ;; Found CATEGORY line - remove trailing blank lines from task-lines
              (let [category (-> line
                                 (str/replace #"^CATEGORY:\s*" "")
                                 str/trim)
                    ;; Remove trailing blank lines
                    trimmed-task-lines (loop [lines (reverse task-lines)
                                              result []]
                                         (if (empty? lines)
                                           result
                                           (let [line (first lines)]
                                             (if (and (str/blank? line) (empty? result))
                                               (recur (rest lines) result)
                                               (recur (rest lines) (cons line result))))))]
                (when (str/blank? category)
                  (throw (ex-info "Invalid task format: empty CATEGORY"
                                  {:index index :line line})))
                {:text (str/join "\n" trimmed-task-lines)
                 :category category
                 :complete? complete?
                 :index index})
              ;; Continuation line
              (recur (rest remaining) (conj task-lines line)))))))))

(defn parse-story-tasks
  "Parse a story task file into a sequence of task maps.

  Each task map contains:
  - :text - Full task text (multi-line, without CATEGORY line)
  - :category - Category name extracted from CATEGORY line
  - :complete? - Boolean indicating if task is complete
  - :index - Zero-based index of task in file

  Throws ex-info if:
  - Task missing checkbox format
  - Task missing CATEGORY line
  - CATEGORY value is empty

  Example input:
    - [ ] STORY: name - Do something
      With more details

    CATEGORY: medium

    - [x] STORY: name - Done task

    CATEGORY: simple

  Returns:
    [{:text \"- [ ] STORY: name - Do something\\n  With more details\"
      :category \"medium\"
      :complete? false
      :index 0}
     {:text \"- [x] STORY: name - Done task\"
      :category \"simple\"
      :complete? true
      :index 1}]"
  [content]
  (let [lines (str/split-lines content)
        checkbox-pattern #"^- \[([ x])\] (.*)"]
    (loop [remaining lines
           current-block []
           tasks []
           task-index 0]
      (if (empty? remaining)
        ;; End of file - process final block if exists
        (if (seq current-block)
          (conj tasks (parse-task-block current-block task-index))
          tasks)
        (let [line (first remaining)
              rest-lines (rest remaining)]
          (cond
            ;; Start of new task
            (re-matches checkbox-pattern line)
            (if (seq current-block)
              ;; Save previous task and start new one
              (recur rest-lines
                     [line]
                     (conj tasks (parse-task-block current-block task-index))
                     (inc task-index))
              ;; First task
              (recur rest-lines [line] tasks task-index))

            ;; CATEGORY line marks end of current task's content
            (str/starts-with? line "CATEGORY:")
            (if (seq current-block)
              (recur rest-lines
                     []
                     (conj tasks (parse-task-block (conj current-block line) task-index))
                     (inc task-index))
              ;; CATEGORY without task - skip
              (recur rest-lines [] tasks task-index))

            ;; Continuation line or blank line
            :else
            ;; Check for invalid task format (starts with "- " but no valid checkbox)
            (if (str/starts-with? line "- ")
              ;; This looks like a task but doesn't have valid checkbox format
              (throw (ex-info "Invalid task format: missing checkbox"
                              {:line line :index task-index}))
              ;; Continuation line or blank line
              (if (seq current-block)
                (recur rest-lines (conj current-block line) tasks task-index)
                ;; Skip lines before first task
                (recur rest-lines [] tasks task-index)))))))))

(defn find-first-incomplete-task
  "Find the first incomplete task in a sequence of task maps.

  Returns the first task map where :complete? is false, or nil if none found.

  Example:
    (find-first-incomplete-task
      [{:complete? true :text \"done\"}
       {:complete? false :text \"todo\"}])
    => {:complete? false :text \"todo\"}"
  [tasks]
  (first (filter (complement :complete?) tasks)))

(defn mark-task-complete
  "Mark a task as complete in the file content.

  Takes:
  - content: Original file content string
  - task-index: Zero-based index of task to complete
  - completion-comment: Optional comment to append after task (on new lines)

  Returns updated file content with task marked complete.

  The function is idempotent - marking an already-complete task has no effect.

  Throws ex-info if task-index is out of bounds."
  ([content task-index]
   (mark-task-complete content task-index nil))
  ([content task-index completion-comment]
   (let [tasks (parse-story-tasks content)
         task-count (count tasks)]
     (when (or (< task-index 0) (>= task-index task-count))
       (throw (ex-info "Task index out of bounds"
                       {:index task-index :count task-count})))

     (let [lines (str/split-lines content)
           checkbox-pattern #"^- \[([ x])\] (.*)"]
       ;; Find the line number for the task at task-index
       (loop [remaining lines
              line-num 0
              current-task-idx 0
              result-lines []]
         (if (empty? remaining)
           (str/join "\n" result-lines)
           (let [line (first remaining)
                 rest-lines (rest remaining)]
             (cond
               ;; Found the task to complete
               (and (re-matches checkbox-pattern line)
                    (= current-task-idx task-index))
               (let [completed-line (str/replace line #"^- \[ \]" "- [x]")
                     ;; Collect continuation lines and CATEGORY line
                     continuation-result
                     (loop [cont-remaining rest-lines
                            cont-lines []]
                       (if (empty? cont-remaining)
                         {:lines cont-lines :remaining []}
                         (let [cont-line (first cont-remaining)]
                           (cond
                             ;; Found CATEGORY line - this is the end
                             (str/starts-with? cont-line "CATEGORY:")
                             {:lines (conj cont-lines cont-line)
                              :remaining (rest cont-remaining)}

                             ;; Next task starts
                             (re-matches checkbox-pattern cont-line)
                             {:lines cont-lines
                              :remaining cont-remaining}

                             ;; Continuation or blank line
                             :else
                             (recur (rest cont-remaining)
                                    (conj cont-lines cont-line))))))

                     new-lines (if (and completion-comment
                                        (not (str/blank? completion-comment)))
                                 (concat [completed-line]
                                         (:lines continuation-result)
                                         ["" (str/trim completion-comment)])
                                 (cons completed-line (:lines continuation-result)))]
                 (recur (:remaining continuation-result)
                        (+ line-num (count new-lines))
                        (inc current-task-idx)
                        (into result-lines new-lines)))

               ;; Start of a different task
               (re-matches checkbox-pattern line)
               (recur rest-lines
                      (inc line-num)
                      (inc current-task-idx)
                      (conj result-lines line))

               ;; Regular line
               :else
               (recur rest-lines
                      (inc line-num)
                      current-task-idx
                      (conj result-lines line))))))))))
