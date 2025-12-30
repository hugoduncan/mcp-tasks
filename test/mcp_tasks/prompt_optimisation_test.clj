(ns mcp-tasks.prompt-optimisation-test
  (:require
    [babashka.fs :as fs]
    [clojure.string :as str]
    [clojure.test :refer [deftest testing is]]
    [mcp-tasks.prompt-optimisation :as opt]
    [mcp-tasks.tasks-file :as tasks-file]))

;; Test prompt optimisation state management functions including validation,
;; reading, writing, and initialization with proper error handling.

(def valid-modification
  {:timestamp "2025-01-15T10:30:00Z"
   :prompt-path "category-prompts/simple.md"
   :change-summary "Reduced verbosity"})

(def valid-state-empty
  {:last-run nil
   :processed-story-ids #{}
   :modifications []})

(def valid-state-with-data
  {:last-run "2025-01-15T10:30:00Z"
   :processed-story-ids #{100 101 102}
   :modifications [valid-modification]})

(defn temp-dir
  "Create temporary directory for test isolation."
  []
  (let [temp-dir (System/getProperty "java.io.tmpdir")
        test-dir (str temp-dir "/opt-state-test-" (random-uuid))]
    (fs/create-dirs test-dir)
    test-dir))

(defmacro with-temp-dir
  "Execute body with a temp directory bound to sym, cleaning up afterward."
  [[sym] & body]
  `(let [~sym (temp-dir)]
     (try
       ~@body
       (finally
         (fs/delete-tree ~sym)))))

(deftest modification-schema-validation
  (testing "Modification schema"
    (testing "validates valid modification"
      (is (opt/valid-modification? valid-modification)))

    (testing "rejects modification with missing fields"
      (is (not (opt/valid-modification? {:timestamp "2025-01-15"})))
      (is (not (opt/valid-modification? {:prompt-path "foo.md"})))
      (is (not (opt/valid-modification? {:change-summary "fix"}))))

    (testing "rejects modification with invalid types"
      (is (not (opt/valid-modification? {:timestamp 12345
                                         :prompt-path "foo.md"
                                         :change-summary "fix"})))
      (is (not (opt/valid-modification? {:timestamp "2025-01-15"
                                         :prompt-path 123
                                         :change-summary "fix"}))))

    (testing "rejects empty maps and nil"
      (is (not (opt/valid-modification? {})))
      (is (not (opt/valid-modification? nil))))))

(deftest optimisation-state-schema-validation
  (testing "OptimisationState schema"
    (testing "validates state with nil last-run"
      (is (opt/valid-optimisation-state? valid-state-empty)))

    (testing "validates state with data"
      (is (opt/valid-optimisation-state? valid-state-with-data)))

    (testing "validates state with multiple modifications"
      (is (opt/valid-optimisation-state?
            {:last-run "2025-01-15T10:30:00Z"
             :processed-story-ids #{1 2 3}
             :modifications [valid-modification valid-modification]})))

    (testing "rejects state with missing fields"
      (is (not (opt/valid-optimisation-state? {:last-run nil})))
      (is (not (opt/valid-optimisation-state? {:processed-story-ids #{}})))
      (is (not (opt/valid-optimisation-state? {:modifications []}))))

    (testing "rejects state with invalid types"
      (is (not (opt/valid-optimisation-state?
                 {:last-run 12345
                  :processed-story-ids #{}
                  :modifications []})))
      (is (not (opt/valid-optimisation-state?
                 {:last-run nil
                  :processed-story-ids [1 2 3]
                  :modifications []})))
      (is (not (opt/valid-optimisation-state?
                 {:last-run nil
                  :processed-story-ids #{}
                  :modifications {}}))))

    (testing "rejects state with invalid modifications"
      (is (not (opt/valid-optimisation-state?
                 {:last-run nil
                  :processed-story-ids #{}
                  :modifications [{:bad "data"}]}))))

    (testing "rejects empty maps and nil"
      (is (not (opt/valid-optimisation-state? {})))
      (is (not (opt/valid-optimisation-state? nil))))))

(deftest explain-modification-test
  (testing "explain-modification"
    (testing "returns nil for valid modification"
      (is (nil? (opt/explain-modification valid-modification))))

    (testing "returns explanation for invalid modification"
      (is (some? (opt/explain-modification {:bad "data"})))
      (is (some? (opt/explain-modification {}))))))

(deftest explain-optimisation-state-test
  (testing "explain-optimisation-state"
    (testing "returns nil for valid state"
      (is (nil? (opt/explain-optimisation-state valid-state-empty)))
      (is (nil? (opt/explain-optimisation-state valid-state-with-data))))

    (testing "returns explanation for invalid state"
      (is (some? (opt/explain-optimisation-state {:bad "data"})))
      (is (some? (opt/explain-optimisation-state {}))))))

(deftest state-file-path-test
  (testing "state-file-path"
    (testing "returns correct path"
      (is (= "/project/.mcp-tasks/prompt-optimisation.edn"
             (opt/state-file-path "/project"))))))

(deftest initial-state-test
  (testing "initial-state"
    (testing "has expected structure"
      (is (= {:last-run nil
              :processed-story-ids #{}
              :modifications []}
             opt/initial-state)))

    (testing "is valid according to schema"
      (is (opt/valid-optimisation-state? opt/initial-state)))))

(deftest read-state-test
  (testing "read-state"
    (testing "returns nil for missing file"
      (with-temp-dir [base-dir]
        (is (nil? (opt/read-state base-dir)))))

    (testing "reads valid state from file"
      (with-temp-dir [base-dir]
        (let [state-file (opt/state-file-path base-dir)]
          (fs/create-dirs (fs/parent state-file))
          (spit state-file (pr-str valid-state-with-data))
          (is (= valid-state-with-data (opt/read-state base-dir))))))

    (testing "reads empty state from file"
      (with-temp-dir [base-dir]
        (let [state-file (opt/state-file-path base-dir)]
          (fs/create-dirs (fs/parent state-file))
          (spit state-file (pr-str valid-state-empty))
          (is (= valid-state-empty (opt/read-state base-dir))))))

    (testing "returns nil for malformed EDN"
      (with-temp-dir [base-dir]
        (let [state-file (opt/state-file-path base-dir)]
          (fs/create-dirs (fs/parent state-file))
          (spit state-file "{:invalid edn")
          (is (nil? (opt/read-state base-dir))))))

    (testing "returns nil for invalid schema"
      (with-temp-dir [base-dir]
        (let [state-file (opt/state-file-path base-dir)
              invalid-state {:bad "data"}]
          (fs/create-dirs (fs/parent state-file))
          (spit state-file (pr-str invalid-state))
          (is (nil? (opt/read-state base-dir))))))))

(deftest write-state-test
  (testing "write-state!"
    (testing "writes valid state to file"
      (with-temp-dir [base-dir]
        (opt/write-state! base-dir valid-state-with-data)
        (is (= valid-state-with-data (opt/read-state base-dir)))))

    (testing "writes empty state to file"
      (with-temp-dir [base-dir]
        (opt/write-state! base-dir valid-state-empty)
        (is (= valid-state-empty (opt/read-state base-dir)))))

    (testing "overwrites existing state"
      (with-temp-dir [base-dir]
        (opt/write-state! base-dir valid-state-empty)
        (opt/write-state! base-dir valid-state-with-data)
        (is (= valid-state-with-data (opt/read-state base-dir)))))

    (testing "creates parent directories if needed"
      (with-temp-dir [base-dir]
        (let [nested-dir (str base-dir "/nested/path")]
          (opt/write-state! nested-dir valid-state-with-data)
          (is (= valid-state-with-data (opt/read-state nested-dir))))))

    (testing "throws on invalid state schema"
      (with-temp-dir [base-dir]
        (let [invalid-state {:bad "data"}]
          (is (thrown? Exception (opt/write-state! base-dir invalid-state))))))))

(deftest init-state-test
  (testing "init-state!"
    (testing "creates new state file when missing"
      (with-temp-dir [base-dir]
        (is (= opt/initial-state (opt/init-state! base-dir)))
        (is (= opt/initial-state (opt/read-state base-dir)))))

    (testing "returns existing state when file exists"
      (with-temp-dir [base-dir]
        (opt/write-state! base-dir valid-state-with-data)
        (is (= valid-state-with-data (opt/init-state! base-dir)))
        (is (= valid-state-with-data (opt/read-state base-dir)))))

    (testing "does not overwrite existing valid state"
      (with-temp-dir [base-dir]
        (opt/write-state! base-dir valid-state-with-data)
        (opt/init-state! base-dir)
        (is (= valid-state-with-data (opt/read-state base-dir)))))))

(deftest state-isolation-test
  (testing "state isolation"
    (testing "different directories have separate state files"
      (with-temp-dir [base-dir-1]
        (with-temp-dir [base-dir-2]
          (opt/write-state! base-dir-1 valid-state-empty)
          (opt/write-state! base-dir-2 valid-state-with-data)
          (is (= valid-state-empty (opt/read-state base-dir-1)))
          (is (= valid-state-with-data (opt/read-state base-dir-2))))))))

(deftest example-data-test
  (testing "example data"
    (testing "example-modification is valid"
      (is (opt/valid-modification? opt/example-modification)))

    (testing "example-state is valid"
      (is (opt/valid-optimisation-state? opt/example-state)))))

;; Story Collection Tests

(defn make-story
  "Create a minimal valid story task for testing."
  [id & {:keys [session-events] :or {session-events []}}]
  {:id id
   :status :closed
   :type :story
   :title (str "Story " id)
   :description "Test story"
   :design ""
   :category "large"
   :meta {}
   :relations []
   :session-events session-events})

(defn make-task
  "Create a minimal valid task for testing."
  [id]
  {:id id
   :status :closed
   :type :task
   :title (str "Task " id)
   :description "Test task"
   :design ""
   :category "simple"
   :meta {}
   :relations []})

(def sample-session-event
  {:timestamp "2025-01-15T10:00:00Z"
   :event-type :user-prompt
   :content "Fix the test"})

(deftest complete-file-path-test
  (testing "complete-file-path"
    (testing "returns correct path"
      (is (= "/project/.mcp-tasks/complete.ednl"
             (opt/complete-file-path "/project"))))))

(deftest collect-unprocessed-stories-test
  ;; Tests collect-unprocessed-stories function which filters completed stories
  ;; for session-event analysis. Verifies filtering by type, session-events
  ;; presence, and processed-story-ids exclusion.
  (testing "collect-unprocessed-stories"
    (testing "returns empty vector for missing file"
      (with-temp-dir [base-dir]
        (is (= [] (opt/collect-unprocessed-stories base-dir valid-state-empty)))))

    (testing "returns empty vector when file exists but has no stories"
      (with-temp-dir [base-dir]
        (let [complete-path (opt/complete-file-path base-dir)]
          (fs/create-dirs (fs/parent complete-path))
          (tasks-file/write-tasks complete-path [(make-task 1) (make-task 2)])
          (is (= [] (opt/collect-unprocessed-stories base-dir valid-state-empty))))))

    (testing "returns stories with session-events"
      (with-temp-dir [base-dir]
        (let [complete-path (opt/complete-file-path base-dir)
              story-with-events (make-story 10 :session-events [sample-session-event])]
          (fs/create-dirs (fs/parent complete-path))
          (tasks-file/write-tasks complete-path [story-with-events])
          (let [result (opt/collect-unprocessed-stories base-dir valid-state-empty)]
            (is (= 1 (count result)))
            (is (= 10 (:id (first result))))))))

    (testing "excludes stories without session-events"
      (with-temp-dir [base-dir]
        (let [complete-path (opt/complete-file-path base-dir)
              story-no-events (make-story 10)
              story-with-events (make-story 11 :session-events [sample-session-event])]
          (fs/create-dirs (fs/parent complete-path))
          (tasks-file/write-tasks complete-path [story-no-events story-with-events])
          (let [result (opt/collect-unprocessed-stories base-dir valid-state-empty)]
            (is (= 1 (count result)))
            (is (= 11 (:id (first result))))))))

    (testing "excludes stories with empty session-events"
      (with-temp-dir [base-dir]
        (let [complete-path (opt/complete-file-path base-dir)
              story-empty-events (make-story 10 :session-events [])
              story-with-events (make-story 11 :session-events [sample-session-event])]
          (fs/create-dirs (fs/parent complete-path))
          (tasks-file/write-tasks complete-path [story-empty-events story-with-events])
          (let [result (opt/collect-unprocessed-stories base-dir valid-state-empty)]
            (is (= 1 (count result)))
            (is (= 11 (:id (first result))))))))

    (testing "excludes already processed stories"
      (with-temp-dir [base-dir]
        (let [complete-path (opt/complete-file-path base-dir)
              story-1 (make-story 10 :session-events [sample-session-event])
              story-2 (make-story 11 :session-events [sample-session-event])
              state {:last-run nil :processed-story-ids #{10} :modifications []}]
          (fs/create-dirs (fs/parent complete-path))
          (tasks-file/write-tasks complete-path [story-1 story-2])
          (let [result (opt/collect-unprocessed-stories base-dir state)]
            (is (= 1 (count result)))
            (is (= 11 (:id (first result))))))))

    (testing "excludes regular tasks even with session-events"
      (with-temp-dir [base-dir]
        (let [complete-path (opt/complete-file-path base-dir)
              ;; Regular tasks shouldn't have session-events, but test the filter
              task-with-events (assoc (make-task 10)
                                      :session-events [sample-session-event])
              story-with-events (make-story 11 :session-events [sample-session-event])]
          (fs/create-dirs (fs/parent complete-path))
          (tasks-file/write-tasks complete-path [task-with-events story-with-events])
          (let [result (opt/collect-unprocessed-stories base-dir valid-state-empty)]
            (is (= 1 (count result)))
            (is (= 11 (:id (first result))))))))

    (testing "returns multiple unprocessed stories"
      (with-temp-dir [base-dir]
        (let [complete-path (opt/complete-file-path base-dir)
              story-1 (make-story 10 :session-events [sample-session-event])
              story-2 (make-story 11 :session-events [sample-session-event])
              story-3 (make-story 12 :session-events [sample-session-event])]
          (fs/create-dirs (fs/parent complete-path))
          (tasks-file/write-tasks complete-path [story-1 story-2 story-3])
          (let [result (opt/collect-unprocessed-stories base-dir valid-state-empty)]
            (is (= 3 (count result)))
            (is (= #{10 11 12} (set (map :id result))))))))

    (testing "mixed scenario with all filter types"
      (with-temp-dir [base-dir]
        (let [complete-path (opt/complete-file-path base-dir)
              ;; Story with events, not processed - should be returned
              story-unprocessed (make-story 10 :session-events [sample-session-event])
              ;; Story with events, already processed - should be excluded
              story-processed (make-story 11 :session-events [sample-session-event])
              ;; Story without events - should be excluded
              story-no-events (make-story 12)
              ;; Regular task - should be excluded
              task (make-task 13)
              state {:last-run nil :processed-story-ids #{11} :modifications []}]
          (fs/create-dirs (fs/parent complete-path))
          (tasks-file/write-tasks complete-path
                                  [story-unprocessed story-processed
                                   story-no-events task])
          (let [result (opt/collect-unprocessed-stories base-dir state)]
            (is (= 1 (count result)))
            (is (= 10 (:id (first result))))))))))

;; Session Event Analysis Tests

(def compaction-event
  {:timestamp "2025-01-15T10:00:00Z"
   :event-type :compaction
   :trigger "auto"})

(def manual-compaction-event
  {:timestamp "2025-01-15T10:05:00Z"
   :event-type :compaction
   :trigger "manual"})

(def user-prompt-event
  {:timestamp "2025-01-15T10:01:00Z"
   :event-type :user-prompt
   :content "Add the tests"})

(def correction-event
  {:timestamp "2025-01-15T10:02:00Z"
   :event-type :user-prompt
   :content "No, I meant the unit tests"})

(def session-start-event
  {:timestamp "2025-01-15T09:00:00Z"
   :event-type :session-start
   :trigger "startup"
   :session-id "sess-001"})

(def session-restart-event
  {:timestamp "2025-01-15T11:00:00Z"
   :event-type :session-start
   :trigger "resume"
   :session-id "sess-002"})

(defn make-story-with-events
  "Create a story with specific session events for testing."
  [id events]
  {:id id
   :status :closed
   :type :story
   :title (str "Test Story " id)
   :description "Test story desc"
   :design ""
   :category "medium"
   :meta {}
   :relations []
   :session-events events})

(deftest analyze-story-events-test
  ;; Tests analyze-story-events function which extracts and categorizes
  ;; issues from a single story's session events.
  (testing "analyze-story-events"
    (testing "returns nil for story with no issues"
      (let [story (make-story-with-events 1 [user-prompt-event])]
        (is (nil? (opt/analyze-story-events story)))))

    (testing "returns nil for story with empty events"
      (let [story (make-story-with-events 1 [])]
        (is (nil? (opt/analyze-story-events story)))))

    (testing "returns nil for story with no events key"
      (let [story (dissoc (make-story-with-events 1 []) :session-events)]
        (is (nil? (opt/analyze-story-events story)))))

    (testing "extracts compaction events"
      (let [story (make-story-with-events 1 [compaction-event
                                             manual-compaction-event])
            result (opt/analyze-story-events story)]
        (is (some? result))
        (is (= 1 (:story-id result)))
        (is (= "Test Story 1" (:story-title result)))
        (is (= "medium" (:category result)))
        (is (= 2 (count (:compactions result))))
        (is (= "auto" (:trigger (first (:compactions result)))))
        (is (= "manual" (:trigger (second (:compactions result)))))))

    (testing "extracts correction events"
      (let [story (make-story-with-events 1 [correction-event])
            result (opt/analyze-story-events story)]
        (is (some? result))
        (is (= 1 (count (:corrections result))))
        (is (= "No, I meant the unit tests"
               (:content (first (:corrections result)))))
        (is (seq (:matched-patterns (first (:corrections result)))))))

    (testing "extracts restart events (skipping first session-start)"
      (let [story (make-story-with-events 1 [session-start-event
                                             user-prompt-event
                                             session-restart-event])
            result (opt/analyze-story-events story)]
        (is (some? result))
        (is (= 1 (count (:restarts result))))
        (is (= "resume" (:trigger (first (:restarts result)))))
        (is (= "sess-002" (:session-id (first (:restarts result)))))))

    (testing "only first session-start is ignored"
      (let [story (make-story-with-events 1 [session-start-event])
            result (opt/analyze-story-events story)]
        (is (nil? result))))

    (testing "handles story with all issue types"
      (let [story (make-story-with-events 1 [session-start-event
                                             compaction-event
                                             correction-event
                                             session-restart-event])
            result (opt/analyze-story-events story)]
        (is (some? result))
        (is (= 1 (count (:compactions result))))
        (is (= 1 (count (:corrections result))))
        (is (= 1 (count (:restarts result))))))))

(deftest correction-pattern-detection-test
  ;; Tests correction pattern matching for various phrases users might
  ;; use when correcting or clarifying instructions.
  (testing "correction pattern detection"
    (testing "detects 'no' at start of prompt"
      (let [story (make-story-with-events 1
                                          [{:timestamp "t" :event-type :user-prompt
                                            :content "No, not that file"}])
            result (opt/analyze-story-events story)]
        (is (= 1 (count (:corrections result))))))

    (testing "detects 'wait' at start of prompt"
      (let [story (make-story-with-events 1
                                          [{:timestamp "t" :event-type :user-prompt
                                            :content "Wait, let me explain"}])
            result (opt/analyze-story-events story)]
        (is (= 1 (count (:corrections result))))))

    (testing "detects 'actually' at start of prompt"
      (let [story (make-story-with-events 1
                                          [{:timestamp "t" :event-type :user-prompt
                                            :content "Actually, use the other approach"}])
            result (opt/analyze-story-events story)]
        (is (= 1 (count (:corrections result))))))

    (testing "detects 'that's wrong'"
      (let [story (make-story-with-events 1
                                          [{:timestamp "t" :event-type :user-prompt
                                            :content "That's wrong, fix it"}])
            result (opt/analyze-story-events story)]
        (is (= 1 (count (:corrections result))))))

    (testing "detects 'I meant'"
      (let [story (make-story-with-events 1
                                          [{:timestamp "t" :event-type :user-prompt
                                            :content "I meant the other function"}])
            result (opt/analyze-story-events story)]
        (is (= 1 (count (:corrections result))))))

    (testing "detects 'fix this'"
      (let [story (make-story-with-events 1
                                          [{:timestamp "t" :event-type :user-prompt
                                            :content "Please fix this"}])
            result (opt/analyze-story-events story)]
        (is (= 1 (count (:corrections result))))))

    (testing "detects 'wrong file'"
      (let [story (make-story-with-events 1
                                          [{:timestamp "t" :event-type :user-prompt
                                            :content "That's the wrong file"}])
            result (opt/analyze-story-events story)]
        (is (= 1 (count (:corrections result))))))

    (testing "detects 'should be'"
      (let [story (make-story-with-events 1
                                          [{:timestamp "t" :event-type :user-prompt
                                            :content "It should be in src/"}])
            result (opt/analyze-story-events story)]
        (is (= 1 (count (:corrections result))))))

    (testing "does not match normal prompts"
      (let [story (make-story-with-events 1
                                          [{:timestamp "t" :event-type :user-prompt
                                            :content "Add the authentication module"}])
            result (opt/analyze-story-events story)]
        (is (nil? result))))

    (testing "does not match 'fix' in other contexts"
      (let [story (make-story-with-events 1
                                          [{:timestamp "t" :event-type :user-prompt
                                            :content "Add a prefix to the output"}])
            result (opt/analyze-story-events story)]
        (is (nil? result))))))

(deftest analyze-session-events-test
  ;; Tests analyze-session-events function which aggregates findings
  ;; from multiple stories into categorized results.
  (testing "analyze-session-events"
    (testing "returns empty results for empty input"
      (let [result (opt/analyze-session-events [])]
        (is (= {:compactions []
                :corrections []
                :restarts []
                :story-count 0
                :event-count 0}
               result))))

    (testing "returns empty findings for stories with no issues"
      (let [stories [(make-story-with-events 1 [user-prompt-event])
                     (make-story-with-events 2 [session-start-event])]
            result (opt/analyze-session-events stories)]
        (is (= [] (:compactions result)))
        (is (= [] (:corrections result)))
        (is (= [] (:restarts result)))
        (is (= 2 (:story-count result)))
        (is (= 2 (:event-count result)))))

    (testing "categorizes compaction findings"
      (let [stories [(make-story-with-events 1 [compaction-event])]
            result (opt/analyze-session-events stories)]
        (is (= 1 (count (:compactions result))))
        (is (= :compaction (:finding-type (first (:compactions result)))))
        (is (= 1 (:story-id (first (:compactions result)))))
        (is (= "medium" (:category (first (:compactions result)))))))

    (testing "categorizes correction findings"
      (let [stories [(make-story-with-events 1 [correction-event])]
            result (opt/analyze-session-events stories)]
        (is (= 1 (count (:corrections result))))
        (is (= :correction (:finding-type (first (:corrections result)))))))

    (testing "categorizes restart findings"
      (let [stories [(make-story-with-events 1 [session-start-event
                                                session-restart-event])]
            result (opt/analyze-session-events stories)]
        (is (= 1 (count (:restarts result))))
        (is (= :restart (:finding-type (first (:restarts result)))))))

    (testing "aggregates findings from multiple stories"
      (let [stories [(make-story-with-events 1 [compaction-event])
                     (make-story-with-events 2 [correction-event])
                     (make-story-with-events 3 [session-start-event
                                                session-restart-event])]
            result (opt/analyze-session-events stories)]
        (is (= 1 (count (:compactions result))))
        (is (= 1 (count (:corrections result))))
        (is (= 1 (count (:restarts result))))
        (is (= 3 (:story-count result)))
        (is (= 4 (:event-count result)))))

    (testing "multiple findings per category from different stories"
      (let [stories [(make-story-with-events 1 [compaction-event])
                     (make-story-with-events 2 [manual-compaction-event])]
            result (opt/analyze-session-events stories)]
        (is (= 2 (count (:compactions result))))
        (is (= #{1 2} (set (map :story-id (:compactions result)))))))

    (testing "includes story metadata in findings"
      (let [story (assoc (make-story-with-events 1 [compaction-event])
                         :title "Auth Feature"
                         :category "large")
            result (opt/analyze-session-events [story])
            finding (first (:compactions result))]
        (is (= "Auth Feature" (:story-title finding)))
        (is (= "large" (:category finding)))))))

;; Findings Presentation Tests

(def sample-compaction-finding
  {:finding-type :compaction
   :story-id 123
   :story-title "Auth Feature"
   :category "large"
   :events [{:timestamp "2025-01-15T10:30:00Z" :trigger "auto"}
            {:timestamp "2025-01-15T11:00:00Z" :trigger "auto"}]})

(def sample-correction-finding
  {:finding-type :correction
   :story-id 456
   :story-title "User Profile"
   :category "medium"
   :events [{:timestamp "2025-01-15T10:00:00Z"
             :content "No, I meant the unit tests"
             :matched-patterns ["(?i)^no[,\\s]"]}]})

(def sample-restart-finding
  {:finding-type :restart
   :story-id 789
   :story-title "API Refactor"
   :category "large"
   :events [{:timestamp "2025-01-15T12:00:00Z"
             :trigger "resume"
             :session-id "sess-002"}]})

(deftest format-compaction-finding-test
  ;; Tests format-compaction-finding which formats a single compaction
  ;; finding as markdown with diagnosis and proposed fix.
  (testing "format-compaction-finding"
    (testing "formats finding with story info and events"
      (let [result (opt/format-compaction-finding sample-compaction-finding 1)]
        (is (string? result))
        (is (str/includes? result "**1."))
        (is (str/includes? result "Story #123"))
        (is (str/includes? result "Auth Feature"))
        (is (str/includes? result "category: large"))))

    (testing "includes event count and timestamps"
      (let [result (opt/format-compaction-finding sample-compaction-finding 1)]
        (is (str/includes? result "2 compaction(s)"))
        (is (str/includes? result "10:30"))
        (is (str/includes? result "11:00"))))

    (testing "includes diagnosis for multiple auto-compactions"
      (let [result (opt/format-compaction-finding sample-compaction-finding 1)]
        (is (str/includes? result "Diagnosis:"))
        (is (str/includes? result "Multiple auto-compactions"))))

    (testing "includes proposed fix referencing category"
      (let [result (opt/format-compaction-finding sample-compaction-finding 1)]
        (is (str/includes? result "Proposed fix:"))
        (is (str/includes? result "category-prompts/large.md"))))

    (testing "uses correct index number"
      (let [result (opt/format-compaction-finding sample-compaction-finding 5)]
        (is (str/includes? result "**5."))))))

(deftest format-correction-finding-test
  ;; Tests format-correction-finding which formats a single correction
  ;; finding as markdown with sample content and proposed fix.
  (testing "format-correction-finding"
    (testing "formats finding with story info"
      (let [result (opt/format-correction-finding sample-correction-finding 1)]
        (is (string? result))
        (is (str/includes? result "Story #456"))
        (is (str/includes? result "User Profile"))
        (is (str/includes? result "category: medium"))))

    (testing "includes sample content"
      (let [result (opt/format-correction-finding sample-correction-finding 1)]
        (is (str/includes? result "Sample:"))
        (is (str/includes? result "unit tests"))))

    (testing "truncates long content"
      (let [long-finding (assoc-in sample-correction-finding
                                   [:events 0 :content]
                                   (apply str (repeat 100 "x")))
            result (opt/format-correction-finding long-finding 1)]
        (is (str/includes? result "..."))
        (is (< (count (re-find #"Sample: \"[^\"]+\"" result)) 80))))

    (testing "includes diagnosis and proposed fix"
      (let [result (opt/format-correction-finding sample-correction-finding 1)]
        (is (str/includes? result "Diagnosis:"))
        (is (str/includes? result "Proposed fix:"))
        (is (str/includes? result "category-prompts/medium.md"))))))

(deftest format-restart-finding-test
  ;; Tests format-restart-finding which formats a single restart
  ;; finding as markdown with restart count and diagnosis.
  (testing "format-restart-finding"
    (testing "formats finding with story info"
      (let [result (opt/format-restart-finding sample-restart-finding 1)]
        (is (string? result))
        (is (str/includes? result "Story #789"))
        (is (str/includes? result "API Refactor"))
        (is (str/includes? result "category: large"))))

    (testing "includes restart count and triggers"
      (let [result (opt/format-restart-finding sample-restart-finding 1)]
        (is (str/includes? result "Restarts: 1"))
        (is (str/includes? result "resume"))))

    (testing "includes diagnosis and proposed fix"
      (let [result (opt/format-restart-finding sample-restart-finding 1)]
        (is (str/includes? result "Diagnosis:"))
        (is (str/includes? result "Proposed fix:"))))))

(deftest format-findings-test
  ;; Tests format-findings which formats all findings grouped by type
  ;; as markdown for user presentation.
  (testing "format-findings"
    (testing "returns no-findings message for empty results"
      (let [empty-result {:compactions []
                          :corrections []
                          :restarts []
                          :story-count 5
                          :event-count 42}
            result (opt/format-findings empty-result)]
        (is (str/includes? result "No issues found"))
        (is (str/includes? result "5 stories"))
        (is (str/includes? result "42 events"))))

    (testing "includes summary with counts"
      (let [analysis {:compactions [sample-compaction-finding]
                      :corrections [sample-correction-finding]
                      :restarts [sample-restart-finding]
                      :story-count 3
                      :event-count 10}
            result (opt/format-findings analysis)]
        (is (str/includes? result "## Prompt Optimization Findings"))
        (is (str/includes? result "3 stories"))
        (is (str/includes? result "10 events"))
        (is (str/includes? result "1 compaction(s)"))
        (is (str/includes? result "1 correction(s)"))
        (is (str/includes? result "1 restart(s)"))))

    (testing "groups findings by type with section headers"
      (let [analysis {:compactions [sample-compaction-finding]
                      :corrections [sample-correction-finding]
                      :restarts [sample-restart-finding]
                      :story-count 3
                      :event-count 10}
            result (opt/format-findings analysis)]
        (is (str/includes? result "### Compactions (1)"))
        (is (str/includes? result "### Corrections (1)"))
        (is (str/includes? result "### Restarts (1)"))))

    (testing "numbers findings sequentially across sections"
      (let [analysis {:compactions [sample-compaction-finding]
                      :corrections [sample-correction-finding]
                      :restarts [sample-restart-finding]
                      :story-count 3
                      :event-count 10}
            result (opt/format-findings analysis)]
        (is (str/includes? result "**1. Story #123"))
        (is (str/includes? result "**2. Story #456"))
        (is (str/includes? result "**3. Story #789"))))

    (testing "omits empty sections"
      (let [analysis {:compactions [sample-compaction-finding]
                      :corrections []
                      :restarts []
                      :story-count 1
                      :event-count 2}
            result (opt/format-findings analysis)]
        (is (str/includes? result "### Compactions"))
        (is (not (str/includes? result "### Corrections")))
        (is (not (str/includes? result "### Restarts")))))

    (testing "handles multiple findings per category"
      (let [compaction2 (assoc sample-compaction-finding
                               :story-id 124
                               :story-title "Other Feature")
            analysis {:compactions [sample-compaction-finding compaction2]
                      :corrections []
                      :restarts []
                      :story-count 2
                      :event-count 4}
            result (opt/format-findings analysis)]
        (is (str/includes? result "### Compactions (2)"))
        (is (str/includes? result "**1. Story #123"))
        (is (str/includes? result "**2. Story #124"))))))

;; State Update Tests

(deftest record-optimization-run-test
  ;; Tests record-optimization-run! which atomically updates the state file
  ;; with processed story IDs, modifications, and last-run timestamp.
  (testing "record-optimization-run!"
    (testing "creates state file if missing and records run"
      (with-temp-dir [base-dir]
        (let [timestamp "2025-01-20T15:00:00Z"
              story-ids [10 11]
              modifications [valid-modification]
              result (opt/record-optimization-run!
                       base-dir timestamp story-ids modifications)]
          (is (= timestamp (:last-run result)))
          (is (= #{10 11} (:processed-story-ids result)))
          (is (= [valid-modification] (:modifications result))))))

    (testing "merges story IDs with existing processed IDs"
      (with-temp-dir [base-dir]
        (let [initial {:last-run "2025-01-19T10:00:00Z"
                       :processed-story-ids #{1 2 3}
                       :modifications []}]
          (opt/write-state! base-dir initial)
          (let [result (opt/record-optimization-run!
                         base-dir "2025-01-20T15:00:00Z" [4 5] [])]
            (is (= #{1 2 3 4 5} (:processed-story-ids result)))))))

    (testing "appends modifications to existing modifications"
      (with-temp-dir [base-dir]
        (let [existing-mod {:timestamp "2025-01-19T10:00:00Z"
                            :prompt-path "a.md"
                            :change-summary "First"}
              new-mod {:timestamp "2025-01-20T15:00:00Z"
                       :prompt-path "b.md"
                       :change-summary "Second"}
              initial {:last-run "2025-01-19T10:00:00Z"
                       :processed-story-ids #{}
                       :modifications [existing-mod]}]
          (opt/write-state! base-dir initial)
          (let [result (opt/record-optimization-run!
                         base-dir "2025-01-20T15:00:00Z" [] [new-mod])]
            (is (= 2 (count (:modifications result))))
            (is (= "First" (:change-summary (first (:modifications result)))))
            (is (= "Second" (:change-summary (second (:modifications result)))))))))

    (testing "updates last-run timestamp"
      (with-temp-dir [base-dir]
        (let [initial {:last-run "2025-01-19T10:00:00Z"
                       :processed-story-ids #{}
                       :modifications []}]
          (opt/write-state! base-dir initial)
          (let [result (opt/record-optimization-run!
                         base-dir "2025-01-20T15:00:00Z" [] [])]
            (is (= "2025-01-20T15:00:00Z" (:last-run result)))))))

    (testing "persists changes to file"
      (with-temp-dir [base-dir]
        (let [timestamp "2025-01-20T15:00:00Z"
              story-ids [10]
              modifications [valid-modification]]
          (opt/record-optimization-run!
            base-dir timestamp story-ids modifications)
          (let [persisted (opt/read-state base-dir)]
            (is (= timestamp (:last-run persisted)))
            (is (= #{10} (:processed-story-ids persisted)))
            (is (= [valid-modification] (:modifications persisted)))))))

    (testing "handles empty collections"
      (with-temp-dir [base-dir]
        (let [result (opt/record-optimization-run!
                       base-dir "2025-01-20T15:00:00Z" [] [])]
          (is (= "2025-01-20T15:00:00Z" (:last-run result)))
          (is (= #{} (:processed-story-ids result)))
          (is (= [] (:modifications result))))))

    (testing "throws on invalid modification in input with index info"
      (with-temp-dir [base-dir]
        (let [invalid-mod {:bad "data"}]
          (try
            (opt/record-optimization-run!
              base-dir "2025-01-20T15:00:00Z" [] [invalid-mod])
            (is false "Expected exception not thrown")
            (catch Exception e
              (is (= "Invalid modification at index 0" (.getMessage e)))
              (is (= 0 (:index (ex-data e))))
              (is (= invalid-mod (:modification (ex-data e))))
              (is (some? (:explanation (ex-data e)))))))))

    (testing "throws on second invalid modification with correct index"
      (with-temp-dir [base-dir]
        (let [valid-mod {:timestamp "2025-01-20T15:00:00Z"
                         :prompt-path "a.md"
                         :change-summary "Valid"}
              invalid-mod {:missing "required-keys"}]
          (try
            (opt/record-optimization-run!
              base-dir "2025-01-20T15:00:00Z" [] [valid-mod invalid-mod])
            (is false "Expected exception not thrown")
            (catch Exception e
              (is (= "Invalid modification at index 1" (.getMessage e)))
              (is (= 1 (:index (ex-data e))))
              (is (= invalid-mod (:modification (ex-data e)))))))))

    (testing "returns updated state"
      (with-temp-dir [base-dir]
        (let [result (opt/record-optimization-run!
                       base-dir "2025-01-20T15:00:00Z" [10 11] [valid-modification])]
          (is (opt/valid-optimisation-state? result)))))))
