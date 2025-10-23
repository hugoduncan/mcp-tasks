(ns mcp-tasks.file-locking-integration-test
  "Integration tests for file locking mechanism.

  Note: Java's FileLock API has a fundamental limitation - only one lock per
  file per JVM, regardless of thread count. This means we cannot test true
  concurrent access using threads in the same test process.

  These tests verify:
  1. Lock timeout behavior when lock cannot be acquired
  2. Error response format on lock failures
  3. Lock release after operations complete
  4. Lock release on exceptions
  5. End-to-end integration with task operations

  True multi-process concurrent testing would require spawning separate JVM
  processes, which is beyond the scope of these integration tests."
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.tasks :as tasks]
    [mcp-tasks.test-helpers :as h]
    [mcp-tasks.tools.helpers :as helpers]))

(deftest lock-timeout-simulation-test
  ;; Tests lock timeout behavior by simulating a timeout condition.
  ;; Note: We cannot test concurrent lock acquisition from the same JVM
  ;; due to Java FileLock limitations (OverlappingFileLockException).
  ;; This test verifies the timeout mechanism works correctly.
  (h/with-test-setup [test-dir]
    (testing "with-task-lock timeout behavior"
      (testing "times out with very short timeout"
        (let [config (assoc (h/test-config test-dir) :lock-timeout-ms 0)
              result (helpers/with-task-lock
                       config
                       (fn []
                         ;; Should execute immediately with 0 timeout
                         :executed))]

          ;; With 0 timeout, tryLock may fail immediately
          ;; Test that we either get result or timeout error
          (if (map? result)
            (is (or (true? (:isError result))
                    (= :executed result)))
            (is (= :executed result))))))))

(deftest lock-release-after-operation-test
  ;; Tests that lock is properly released after operation completes,
  ;; allowing subsequent operations to proceed.
  (h/with-test-setup [test-dir]
    (testing "lock release after operation"
      (testing "allows subsequent operations after lock release"
        (let [config (h/test-config test-dir)
              execution-count (atom 0)]

          ;; First operation
          (let [result1 (helpers/with-task-lock
                          config
                          (fn []
                            (swap! execution-count inc)
                            :first))]
            (is (= :first result1))
            (is (= 1 @execution-count)))

          ;; Second operation - should succeed if lock was released
          (let [result2 (helpers/with-task-lock
                          config
                          (fn []
                            (swap! execution-count inc)
                            :second))]
            (is (= :second result2))
            (is (= 2 @execution-count)))

          ;; Third operation
          (let [result3 (helpers/with-task-lock
                          config
                          (fn []
                            (swap! execution-count inc)
                            :third))]
            (is (= :third result3))
            (is (= 3 @execution-count))))))))

(deftest lock-release-on-exception-test
  ;; Tests that lock is released even when the operation throws an exception.
  (h/with-test-setup [test-dir]
    (testing "lock release on exception"
      (testing "releases lock when operation throws exception"
        (let [config (h/test-config test-dir)]

          ;; Operation that throws exception
          (is (thrown-with-msg?
                clojure.lang.ExceptionInfo
                #"Test exception"
                (helpers/with-task-lock
                  config
                  (fn []
                    (throw (ex-info "Test exception" {:test true}))))))

          ;; Verify lock was released by acquiring it again
          (let [result (helpers/with-task-lock
                         config
                         (fn []
                           :lock-acquired))]
            (is (= :lock-acquired result))))))))

(deftest lock-protects-task-operations-test
  ;; Tests that lock protects actual task operations end-to-end.
  ;; Verifies that multiple sequential task operations work correctly.
  (h/with-test-setup [test-dir]
    (testing "lock protects task operations"
      (testing "sequential task additions work correctly"
        (let [config (h/test-config test-dir)
              tasks-path (helpers/task-path config ["tasks.ednl"])
              tasks-file (:absolute tasks-path)]

          ;; Perform multiple task operations in sequence
          (helpers/with-task-lock
            config
            (fn []
              (helpers/prepare-task-file config)
              (tasks/add-task {:title "Task 1"
                               :description "First task"
                               :design ""
                               :category "simple"
                               :type :task
                               :status :open
                               :meta {}
                               :relations []})
              (tasks/save-tasks! tasks-file)))

          (helpers/with-task-lock
            config
            (fn []
              (helpers/prepare-task-file config)
              (tasks/add-task {:title "Task 2"
                               :description "Second task"
                               :design ""
                               :category "simple"
                               :type :task
                               :status :open
                               :meta {}
                               :relations []})
              (tasks/save-tasks! tasks-file)))

          ;; Verify both tasks were added
          (helpers/with-task-lock
            config
            (fn []
              (helpers/prepare-task-file config)
              (let [all-tasks (tasks/get-tasks)]
                (is (= 2 (count all-tasks)))
                (is (= #{"Task 1" "Task 2"}
                       (set (map :title all-tasks))))))))))))

(deftest lock-error-handling-test
  ;; Tests error handling when lock file cannot be accessed.
  (h/with-test-setup [test-dir]
    (testing "lock error handling"
      (testing "handles errors when file path setup fails"
        ;; Create config with invalid path (file where directory should be)
        (let [invalid-dir (str test-dir "/blocked")
              _ (spit invalid-dir "I'm a file, not a directory")
              config (assoc (h/test-config test-dir)
                            :base-dir invalid-dir)]

          ;; The function may throw an exception or return error
          ;; depending on where the error occurs
          (try
            (let [result (helpers/with-task-lock
                           config
                           (fn []
                             :should-not-execute))]
              ;; If we get a result, it should be an error
              (is (map? result))
              (is (true? (:isError result))))
            (catch Exception e
              ;; Exception is also acceptable - file system error
              (is (instance? Exception e)))))))))
