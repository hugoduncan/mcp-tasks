(ns mcp-tasks.tools.helpers-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.test-helpers :as h]
    [mcp-tasks.tools.helpers :as sut]))

(deftest with-task-lock-successful-execution-test
  ;; Tests that with-task-lock successfully acquires lock, executes function,
  ;; and releases lock. Verifies basic lock acquisition and cleanup flow.
  (h/with-test-setup [test-dir]
    (testing "with-task-lock"
      (testing "successfully acquires lock and executes function"
        (let [executed? (atom false)
              result (sut/with-task-lock
                       (h/test-config test-dir)
                       (fn []
                         (reset! executed? true)
                         :success))]
          (is (true? @executed?))
          (is (= :success result)))))))

(deftest with-task-lock-releases-on-exception-test
  ;; Tests that with-task-lock properly releases lock even when function
  ;; throws exception. Verifies exception propagates while cleanup occurs.
  (h/with-test-setup [test-dir]
    (testing "with-task-lock"
      (testing "releases lock even when function throws exception"
        (let [config (h/test-config test-dir)
              exception-thrown? (atom false)]
          (try
            (sut/with-task-lock
              config
              (fn []
                (reset! exception-thrown? true)
                (throw (ex-info "Test error" {}))))
            (catch Exception _e
              ;; Expected
              nil))
          (is (true? @exception-thrown?))
          ;; Verify we can acquire the lock again (proves it was released)
          (let [result (sut/with-task-lock
                         config
                         (fn [] :acquired-again))]
            (is (= :acquired-again result))))))))

(deftest with-task-lock-creates-file-if-missing-test
  ;; Tests that with-task-lock creates tasks.ednl if it doesn't exist.
  ;; Verifies file creation and directory setup.
  (h/with-test-setup [test-dir]
    (testing "with-task-lock"
      (testing "creates tasks file if it doesn't exist"
        (let [config (h/test-config test-dir)
              tasks-file (str test-dir "/.mcp-tasks/tasks.ednl")]
          ;; Delete the file if it exists
          (when (sut/file-exists? tasks-file)
            (io/delete-file tasks-file))
          ;; Execute with-task-lock
          (sut/with-task-lock
            config
            (fn [] :done))
          ;; Verify file was created
          (is (sut/file-exists? tasks-file)))))))

(deftest with-task-lock-sequential-access-test
  ;; Tests that with-task-lock allows sequential access.
  ;; Verifies lock can be acquired multiple times sequentially.
  (h/with-test-setup [test-dir]
    (testing "with-task-lock"
      (testing "allows sequential lock acquisition"
        (let [config (h/test-config test-dir)
              results (atom [])]
          ;; First lock
          (sut/with-task-lock
            config
            (fn []
              (swap! results conj :first)))
          ;; Second lock
          (sut/with-task-lock
            config
            (fn []
              (swap! results conj :second)))
          ;; Third lock
          (sut/with-task-lock
            config
            (fn []
              (swap! results conj :third)))
          ;; Verify all executed in order
          (is (= [:first :second :third] @results)))))))

(deftest with-task-lock-returns-function-result-test
  ;; Tests that with-task-lock returns the result from the function.
  (h/with-test-setup [test-dir]
    (testing "with-task-lock"
      (testing "returns the result from the executed function"
        (let [config (h/test-config test-dir)
              result (sut/with-task-lock
                       config
                       (fn [] {:status :ok :value 42}))]
          (is (= {:status :ok :value 42} result)))))))

(deftest with-task-lock-custom-timeout-config-test
  ;; Tests that with-task-lock accepts custom timeout in config.
  ;; Just verifies the config is accepted, not the actual timeout behavior.
  (h/with-test-setup [test-dir]
    (testing "with-task-lock"
      (testing "accepts custom timeout from config"
        (let [config (assoc (h/test-config test-dir) :lock-timeout-ms 5000)
              result (sut/with-task-lock
                       config
                       (fn [] :ok))]
          ;; Just verify it works with custom timeout config
          (is (= :ok result)))))))
