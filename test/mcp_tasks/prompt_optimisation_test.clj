(ns mcp-tasks.prompt-optimisation-test
  (:require
    [babashka.fs :as fs]
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
      (let [base-dir (temp-dir)]
        (is (nil? (opt/read-state base-dir)))
        (fs/delete-tree base-dir)))

    (testing "reads valid state from file"
      (let [base-dir (temp-dir)
            state-file (opt/state-file-path base-dir)]
        (fs/create-dirs (fs/parent state-file))
        (spit state-file (pr-str valid-state-with-data))
        (is (= valid-state-with-data (opt/read-state base-dir)))
        (fs/delete-tree base-dir)))

    (testing "reads empty state from file"
      (let [base-dir (temp-dir)
            state-file (opt/state-file-path base-dir)]
        (fs/create-dirs (fs/parent state-file))
        (spit state-file (pr-str valid-state-empty))
        (is (= valid-state-empty (opt/read-state base-dir)))
        (fs/delete-tree base-dir)))

    (testing "returns nil for malformed EDN"
      (let [base-dir (temp-dir)
            state-file (opt/state-file-path base-dir)]
        (fs/create-dirs (fs/parent state-file))
        (spit state-file "{:invalid edn")
        (is (nil? (opt/read-state base-dir)))
        (fs/delete-tree base-dir)))

    (testing "returns nil for invalid schema"
      (let [base-dir (temp-dir)
            state-file (opt/state-file-path base-dir)
            invalid-state {:bad "data"}]
        (fs/create-dirs (fs/parent state-file))
        (spit state-file (pr-str invalid-state))
        (is (nil? (opt/read-state base-dir)))
        (fs/delete-tree base-dir)))))

(deftest write-state-test
  (testing "write-state!"
    (testing "writes valid state to file"
      (let [base-dir (temp-dir)]
        (opt/write-state! base-dir valid-state-with-data)
        (is (= valid-state-with-data (opt/read-state base-dir)))
        (fs/delete-tree base-dir)))

    (testing "writes empty state to file"
      (let [base-dir (temp-dir)]
        (opt/write-state! base-dir valid-state-empty)
        (is (= valid-state-empty (opt/read-state base-dir)))
        (fs/delete-tree base-dir)))

    (testing "overwrites existing state"
      (let [base-dir (temp-dir)]
        (opt/write-state! base-dir valid-state-empty)
        (opt/write-state! base-dir valid-state-with-data)
        (is (= valid-state-with-data (opt/read-state base-dir)))
        (fs/delete-tree base-dir)))

    (testing "creates parent directories if needed"
      (let [base-dir (temp-dir)
            nested-dir (str base-dir "/nested/path")]
        (opt/write-state! nested-dir valid-state-with-data)
        (is (= valid-state-with-data (opt/read-state nested-dir)))
        (fs/delete-tree base-dir)))

    (testing "throws on invalid state schema"
      (let [base-dir (temp-dir)
            invalid-state {:bad "data"}]
        (is (thrown? Exception (opt/write-state! base-dir invalid-state)))
        (fs/delete-tree base-dir)))))

(deftest init-state-test
  (testing "init-state!"
    (testing "creates new state file when missing"
      (let [base-dir (temp-dir)]
        (is (= opt/initial-state (opt/init-state! base-dir)))
        (is (= opt/initial-state (opt/read-state base-dir)))
        (fs/delete-tree base-dir)))

    (testing "returns existing state when file exists"
      (let [base-dir (temp-dir)]
        (opt/write-state! base-dir valid-state-with-data)
        (is (= valid-state-with-data (opt/init-state! base-dir)))
        (is (= valid-state-with-data (opt/read-state base-dir)))
        (fs/delete-tree base-dir)))

    (testing "does not overwrite existing valid state"
      (let [base-dir (temp-dir)]
        (opt/write-state! base-dir valid-state-with-data)
        (opt/init-state! base-dir)
        (is (= valid-state-with-data (opt/read-state base-dir)))
        (fs/delete-tree base-dir)))))

(deftest state-isolation-test
  (testing "state isolation"
    (testing "different directories have separate state files"
      (let [base-dir-1 (temp-dir)
            base-dir-2 (temp-dir)]
        (opt/write-state! base-dir-1 valid-state-empty)
        (opt/write-state! base-dir-2 valid-state-with-data)
        (is (= valid-state-empty (opt/read-state base-dir-1)))
        (is (= valid-state-with-data (opt/read-state base-dir-2)))
        (fs/delete-tree base-dir-1)
        (fs/delete-tree base-dir-2)))))

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
      (let [base-dir (temp-dir)
            state valid-state-empty]
        (is (= [] (opt/collect-unprocessed-stories base-dir state)))
        (fs/delete-tree base-dir)))

    (testing "returns empty vector when file exists but has no stories"
      (let [base-dir (temp-dir)
            complete-path (opt/complete-file-path base-dir)
            state valid-state-empty]
        (fs/create-dirs (fs/parent complete-path))
        (tasks-file/write-tasks complete-path [(make-task 1) (make-task 2)])
        (is (= [] (opt/collect-unprocessed-stories base-dir state)))
        (fs/delete-tree base-dir)))

    (testing "returns stories with session-events"
      (let [base-dir (temp-dir)
            complete-path (opt/complete-file-path base-dir)
            story-with-events (make-story 10 :session-events [sample-session-event])
            state valid-state-empty]
        (fs/create-dirs (fs/parent complete-path))
        (tasks-file/write-tasks complete-path [story-with-events])
        (let [result (opt/collect-unprocessed-stories base-dir state)]
          (is (= 1 (count result)))
          (is (= 10 (:id (first result)))))
        (fs/delete-tree base-dir)))

    (testing "excludes stories without session-events"
      (let [base-dir (temp-dir)
            complete-path (opt/complete-file-path base-dir)
            story-no-events (make-story 10)
            story-with-events (make-story 11 :session-events [sample-session-event])
            state valid-state-empty]
        (fs/create-dirs (fs/parent complete-path))
        (tasks-file/write-tasks complete-path [story-no-events story-with-events])
        (let [result (opt/collect-unprocessed-stories base-dir state)]
          (is (= 1 (count result)))
          (is (= 11 (:id (first result)))))
        (fs/delete-tree base-dir)))

    (testing "excludes stories with empty session-events"
      (let [base-dir (temp-dir)
            complete-path (opt/complete-file-path base-dir)
            story-empty-events (make-story 10 :session-events [])
            story-with-events (make-story 11 :session-events [sample-session-event])
            state valid-state-empty]
        (fs/create-dirs (fs/parent complete-path))
        (tasks-file/write-tasks complete-path [story-empty-events story-with-events])
        (let [result (opt/collect-unprocessed-stories base-dir state)]
          (is (= 1 (count result)))
          (is (= 11 (:id (first result)))))
        (fs/delete-tree base-dir)))

    (testing "excludes already processed stories"
      (let [base-dir (temp-dir)
            complete-path (opt/complete-file-path base-dir)
            story-1 (make-story 10 :session-events [sample-session-event])
            story-2 (make-story 11 :session-events [sample-session-event])
            state {:last-run nil :processed-story-ids #{10} :modifications []}]
        (fs/create-dirs (fs/parent complete-path))
        (tasks-file/write-tasks complete-path [story-1 story-2])
        (let [result (opt/collect-unprocessed-stories base-dir state)]
          (is (= 1 (count result)))
          (is (= 11 (:id (first result)))))
        (fs/delete-tree base-dir)))

    (testing "excludes regular tasks even with session-events"
      (let [base-dir (temp-dir)
            complete-path (opt/complete-file-path base-dir)
            ;; Regular tasks shouldn't have session-events, but test the filter
            task-with-events (assoc (make-task 10)
                                    :session-events [sample-session-event])
            story-with-events (make-story 11 :session-events [sample-session-event])
            state valid-state-empty]
        (fs/create-dirs (fs/parent complete-path))
        (tasks-file/write-tasks complete-path [task-with-events story-with-events])
        (let [result (opt/collect-unprocessed-stories base-dir state)]
          (is (= 1 (count result)))
          (is (= 11 (:id (first result)))))
        (fs/delete-tree base-dir)))

    (testing "returns multiple unprocessed stories"
      (let [base-dir (temp-dir)
            complete-path (opt/complete-file-path base-dir)
            story-1 (make-story 10 :session-events [sample-session-event])
            story-2 (make-story 11 :session-events [sample-session-event])
            story-3 (make-story 12 :session-events [sample-session-event])
            state valid-state-empty]
        (fs/create-dirs (fs/parent complete-path))
        (tasks-file/write-tasks complete-path [story-1 story-2 story-3])
        (let [result (opt/collect-unprocessed-stories base-dir state)]
          (is (= 3 (count result)))
          (is (= #{10 11 12} (set (map :id result)))))
        (fs/delete-tree base-dir)))

    (testing "mixed scenario with all filter types"
      (let [base-dir (temp-dir)
            complete-path (opt/complete-file-path base-dir)
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
          (is (= 10 (:id (first result)))))
        (fs/delete-tree base-dir)))))
