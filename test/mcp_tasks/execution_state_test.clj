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
   :started-at "2025-10-20T14:30:00Z"})

(def valid-state-without-story
  {:story-id nil
   :task-id 30
   :started-at "2025-10-20T15:00:00Z"})

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

    (testing "rejects state with missing required fields"
      (is (not (exec-state/valid-execution-state? {:task-id 1})))
      (is (not (exec-state/valid-execution-state? {:story-id 1
                                                   :started-at "2025-10-20"})))
      (is (not (exec-state/valid-execution-state? {:story-id 1
                                                   :task-id 2}))))

    (testing "rejects state with invalid field types"
      (is (not (exec-state/valid-execution-state? {:story-id "not-int"
                                                   :task-id 20
                                                   :started-at "2025-10-20"})))
      (is (not (exec-state/valid-execution-state? {:story-id 10
                                                   :task-id "not-int"
                                                   :started-at "2025-10-20"})))
      (is (not (exec-state/valid-execution-state? {:story-id 10
                                                   :task-id 20
                                                   :started-at 123}))))

    (testing "rejects empty maps and nil"
      (is (not (exec-state/valid-execution-state? {})))
      (is (not (exec-state/valid-execution-state? nil))))))

(deftest explain-execution-state-test
  (testing "explain-execution-state"
    (testing "returns nil for valid state"
      (is (nil? (exec-state/explain-execution-state valid-state-with-story)))
      (is (nil? (exec-state/explain-execution-state valid-state-without-story))))

    (testing "returns explanation for invalid state"
      (is (some? (exec-state/explain-execution-state {:task-id "bad"})))
      (is (some? (exec-state/explain-execution-state {}))))))

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
            invalid-state {:task-id "not-int" :started-at "2025-10-20"}]
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
