(ns mcp-tasks.execution-state-test
  (:require
    [babashka.fs :as fs]
    [clojure.test :refer [deftest testing is]]
    [mcp-tasks.execution-state :as exec-state]))

;; Test execution state management functions including validation,
;; reading, writing, and clearing state with proper error handling.

(def valid-state-with-story
  {:story-id 10
   :task-id 20
   :task-start-time "2025-10-20T14:30:00Z"})

(def valid-state-without-story
  {:story-id nil
   :task-id 30
   :task-start-time "2025-10-20T15:00:00Z"})

(def valid-story-level-state
  {:story-id 40
   :task-start-time "2025-10-20T16:00:00Z"})

(defn temp-dir
  "Create temporary directory for test isolation."
  []
  (let [temp-dir (System/getProperty "java.io.tmpdir")
        test-dir (str temp-dir "/exec-state-test-" (random-uuid))]
    (fs/create-dirs test-dir)
    test-dir))

(deftest execution-state-schema-validation
  (testing "ExecutionState schema"
    (testing "validates valid state with story"
      (is (exec-state/valid-execution-state? valid-state-with-story)))

    (testing "validates valid state without story"
      (is (exec-state/valid-execution-state? valid-state-without-story)))

    (testing "validates story-level state (no task-id)"
      (is (exec-state/valid-execution-state? valid-story-level-state)))

    (testing "rejects state without both story-id and task-id"
      (is (not (exec-state/valid-execution-state? {:task-start-time "2025-10-20"}))))

    (testing "rejects state with missing required fields"
      (is (not (exec-state/valid-execution-state? {:task-id 1})))
      (is (not (exec-state/valid-execution-state? {:story-id 1
                                                   :task-id 2}))))

    (testing "rejects state with invalid field types"
      (is (not (exec-state/valid-execution-state? {:story-id "not-int"
                                                   :task-id 20
                                                   :task-start-time "2025-10-20"})))
      (is (not (exec-state/valid-execution-state? {:story-id 10
                                                   :task-id "not-int"
                                                   :task-start-time "2025-10-20"})))
      (is (not (exec-state/valid-execution-state? {:story-id 10
                                                   :task-id 20
                                                   :task-start-time 123}))))

    (testing "rejects empty maps and nil"
      (is (not (exec-state/valid-execution-state? {})))
      (is (not (exec-state/valid-execution-state? nil))))))

(deftest explain-execution-state-test
  (testing "explain-execution-state"
    (testing "returns nil for valid state"
      (is (nil? (exec-state/explain-execution-state valid-state-with-story)))
      (is (nil? (exec-state/explain-execution-state valid-state-without-story)))
      (is (nil? (exec-state/explain-execution-state valid-story-level-state))))

    (testing "returns explanation for invalid state"
      (is (some? (exec-state/explain-execution-state {:task-id "bad"})))
      (is (some? (exec-state/explain-execution-state {})))
      (is (some? (exec-state/explain-execution-state {:task-start-time "2025-10-20"}))))))

(deftest read-execution-state-test
  (testing "read-execution-state"
    (testing "returns nil for missing file"
      (let [base-dir (temp-dir)]
        (is (nil? (exec-state/read-execution-state base-dir)))
        (fs/delete-tree base-dir)))

    (testing "reads valid state from file"
      (let [base-dir (temp-dir)
            state-file (str base-dir "/.mcp-tasks-current.edn")]
        (spit state-file (pr-str valid-state-with-story))
        (is (= valid-state-with-story
               (exec-state/read-execution-state base-dir)))
        (fs/delete-tree base-dir)))

    (testing "returns nil for malformed EDN"
      (let [base-dir (temp-dir)
            state-file (str base-dir "/.mcp-tasks-current.edn")]
        (spit state-file "{:invalid edn")
        (is (nil? (exec-state/read-execution-state base-dir)))
        (fs/delete-tree base-dir)))

    (testing "returns nil for invalid schema"
      (let [base-dir (temp-dir)
            state-file (str base-dir "/.mcp-tasks-current.edn")
            invalid-state {:task-id "not-int" :task-start-time "2025-10-20"}]
        (spit state-file (pr-str invalid-state))
        (is (nil? (exec-state/read-execution-state base-dir)))
        (fs/delete-tree base-dir)))))

(deftest write-execution-state-test
  (testing "write-execution-state!"
    (testing "writes valid state to file"
      (let [base-dir (temp-dir)]
        (exec-state/write-execution-state! base-dir valid-state-with-story)
        (is (= valid-state-with-story
               (exec-state/read-execution-state base-dir)))
        (fs/delete-tree base-dir)))

    (testing "overwrites existing state"
      (let [base-dir (temp-dir)]
        (exec-state/write-execution-state! base-dir valid-state-with-story)
        (exec-state/write-execution-state! base-dir valid-state-without-story)
        (is (= valid-state-without-story
               (exec-state/read-execution-state base-dir)))
        (fs/delete-tree base-dir)))

    (testing "creates parent directories if needed"
      (let [base-dir (temp-dir)
            nested-dir (str base-dir "/nested/path")]
        (exec-state/write-execution-state! nested-dir valid-state-with-story)
        (is (= valid-state-with-story
               (exec-state/read-execution-state nested-dir)))
        (fs/delete-tree base-dir)))

    (testing "writes and reads story-level state"
      (let [base-dir (temp-dir)]
        (exec-state/write-execution-state! base-dir valid-story-level-state)
        (is (= valid-story-level-state
               (exec-state/read-execution-state base-dir)))
        (fs/delete-tree base-dir)))

    (testing "throws on invalid state schema"
      (let [base-dir (temp-dir)
            invalid-state {:task-id "bad"}]
        (is (thrown? Exception
              (exec-state/write-execution-state! base-dir invalid-state)))
        (fs/delete-tree base-dir)))))

(deftest clear-execution-state-test
  (testing "clear-execution-state!"
    (testing "removes existing state file"
      (let [base-dir (temp-dir)]
        (exec-state/write-execution-state! base-dir valid-state-with-story)
        (is (true? (exec-state/clear-execution-state! base-dir)))
        (is (nil? (exec-state/read-execution-state base-dir)))
        (fs/delete-tree base-dir)))

    (testing "returns false when file doesn't exist"
      (let [base-dir (temp-dir)]
        (is (nil? (exec-state/clear-execution-state! base-dir)))
        (fs/delete-tree base-dir)))))

(deftest state-file-isolation-test
  (testing "state file isolation"
    (testing "different base directories have separate state files"
      (let [base-dir-1 (temp-dir)
            base-dir-2 (temp-dir)]
        (exec-state/write-execution-state! base-dir-1 valid-state-with-story)
        (exec-state/write-execution-state! base-dir-2 valid-state-without-story)
        (is (= valid-state-with-story
               (exec-state/read-execution-state base-dir-1)))
        (is (= valid-state-without-story
               (exec-state/read-execution-state base-dir-2)))
        (fs/delete-tree base-dir-1)
        (fs/delete-tree base-dir-2)))))

(deftest update-execution-state-for-child-completion-test
  ;; Test updating execution state after child task completion.
  ;; Contracts: state with :story-id → story-level state (no :task-id),
  ;; state without :story-id → file cleared, defensive null handling.
  (testing "update-execution-state-for-child-completion!"
    (testing "preserves story-id and removes task-id when story-id present"
      (let [base-dir (temp-dir)]
        (exec-state/write-execution-state! base-dir valid-state-with-story)
        (let [result (exec-state/update-execution-state-for-child-completion! base-dir)]
          (is (= {:story-id 10 :task-start-time "2025-10-20T14:30:00Z"} result))
          (is (= result (exec-state/read-execution-state base-dir))))
        (fs/delete-tree base-dir)))

    (testing "clears state when no story-id present"
      (let [base-dir (temp-dir)]
        (exec-state/write-execution-state! base-dir valid-state-without-story)
        (is (nil? (exec-state/update-execution-state-for-child-completion! base-dir)))
        (is (nil? (exec-state/read-execution-state base-dir)))
        (fs/delete-tree base-dir)))

    (testing "clears state when file does not exist"
      (let [base-dir (temp-dir)]
        (is (nil? (exec-state/update-execution-state-for-child-completion! base-dir)))
        (is (nil? (exec-state/read-execution-state base-dir)))
        (fs/delete-tree base-dir)))

    (testing "handles story-level state correctly (no task-id)"
      (let [base-dir (temp-dir)]
        (exec-state/write-execution-state! base-dir valid-story-level-state)
        (let [result (exec-state/update-execution-state-for-child-completion! base-dir)]
          (is (= valid-story-level-state result))
          (is (= result (exec-state/read-execution-state base-dir))))
        (fs/delete-tree base-dir)))

    (testing "uses atomic file operations"
      (let [base-dir (temp-dir)]
        (exec-state/write-execution-state! base-dir valid-state-with-story)
        (exec-state/update-execution-state-for-child-completion! base-dir)
        (is (= {:story-id 10 :task-start-time "2025-10-20T14:30:00Z"}
               (exec-state/read-execution-state base-dir)))
        (fs/delete-tree base-dir)))))
