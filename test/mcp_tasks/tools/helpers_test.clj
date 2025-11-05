(ns mcp-tasks.tools.helpers-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.test-helpers :as h]
    [mcp-tasks.tools.git :as git]
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
                       (fn [_file-context]
                         (reset! executed? true)
                         :success))]
          (is (true? @executed?))
          (is (= :success result)))))))

(deftest with-task-lock-releases-on-exception-test
  ;; Tests that with-task-lock properly releases lock even when function
  ;; throws exception. Verifies error is returned as map, not thrown.
  (h/with-test-setup [test-dir]
    (testing "with-task-lock"
      (testing "releases lock even when function throws exception"
        (let [config (h/test-config test-dir)
              exception-thrown? (atom false)
              result (sut/with-task-lock
                       config
                       (fn [_file-context]
                         (reset! exception-thrown? true)
                         (throw (ex-info "Test error" {}))))]
          ;; Function was executed (exception was thrown)
          (is (true? @exception-thrown?))
          ;; Result should be error map, not exception
          (is (map? result))
          (is (true? (:isError result)))
          ;; Verify we can acquire the lock again (proves it was released)
          (let [result2 (sut/with-task-lock
                          config
                          (fn [_file-context] :acquired-again))]
            (is (= :acquired-again result2))))))))

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
            (fn [_file-context] :done))
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
            (fn [_file-context]
              (swap! results conj :first)))
          ;; Second lock
          (sut/with-task-lock
            config
            (fn [_file-context]
              (swap! results conj :second)))
          ;; Third lock
          (sut/with-task-lock
            config
            (fn [_file-context]
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
                       (fn [_file-context] {:status :ok :value 42}))]
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
                       (fn [_file-context] :ok))]
          ;; Just verify it works with custom timeout config
          (is (= :ok result)))))))

(deftest sync-and-prepare-task-file-test
  ;; Tests git pull synchronization and task file preparation
  ;; Verifies error type detection and appropriate handling for different scenarios

  (testing "sync-and-prepare-task-file"
    (testing "succeeds when pull works"
      (with-redefs [git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                    git/pull-latest (fn [_dir branch]
                                      (is (= "main" branch))
                                      {:success true
                                       :pulled? true
                                       :error nil
                                       :error-type nil})
                    sut/prepare-task-file (fn [config & {:keys [_file-context]}]
                                            (is (map? config))
                                            "/test/.mcp-tasks/tasks.ednl")]
        (let [config {:resolved-tasks-dir "/test/.mcp-tasks"
                      :enable-git-sync? true}
              result (sut/sync-and-prepare-task-file config)]
          (is (= "/test/.mcp-tasks/tasks.ednl" result)))))

    (testing "succeeds when no remote configured (local-only repo)"
      (with-redefs [git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                    git/pull-latest (fn [_ _]
                                      {:success true
                                       :pulled? false
                                       :error nil
                                       :error-type :no-remote})
                    sut/prepare-task-file (fn [_ & {:keys [_file-context]}]
                                            "/test/.mcp-tasks/tasks.ednl")]
        (let [config {:resolved-tasks-dir "/test/.mcp-tasks"
                      :enable-git-sync? true}
              result (sut/sync-and-prepare-task-file config)]
          (is (= "/test/.mcp-tasks/tasks.ednl" result)))))

    (testing "succeeds when directory is not a git repository"
      (with-redefs [git/get-current-branch (fn [_] {:success false :branch nil :error "fatal: not a git repository"})
                    sut/prepare-task-file (fn [_ & {:keys [_file-context]}]
                                            "/test/.mcp-tasks/tasks.ednl")]
        (let [config {:resolved-tasks-dir "/test/.mcp-tasks"
                      :enable-git-sync? true}
              result (sut/sync-and-prepare-task-file config)]
          (is (= "/test/.mcp-tasks/tasks.ednl" result)))))

    (testing "succeeds when git repository is empty (no commits)"
      (with-redefs [git/get-current-branch (fn [_] {:success false :branch nil :error "fatal: ambiguous argument 'HEAD': unknown revision or path not in the working tree."})
                    sut/prepare-task-file (fn [_ & {:keys [_file-context]}]
                                            "/test/.mcp-tasks/tasks.ednl")]
        (let [config {:resolved-tasks-dir "/test/.mcp-tasks"
                      :enable-git-sync? true}
              result (sut/sync-and-prepare-task-file config)]
          (is (= "/test/.mcp-tasks/tasks.ednl" result)))))

    (testing "returns error map on merge conflicts"
      (with-redefs [git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                    git/pull-latest (fn [_ _]
                                      {:success false
                                       :pulled? false
                                       :error "CONFLICT (content): Merge conflict in tasks.ednl"
                                       :error-type :conflict})]
        (let [config {:resolved-tasks-dir "/test/.mcp-tasks"
                      :enable-git-sync? true}
              result (sut/sync-and-prepare-task-file config)]
          (is (false? (:success result)))
          (is (= "CONFLICT (content): Merge conflict in tasks.ednl" (:error result)))
          (is (= :conflict (:error-type result))))))

    (testing "returns error map on network errors"
      (with-redefs [git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                    git/pull-latest (fn [_ _]
                                      {:success false
                                       :pulled? false
                                       :error "fatal: Could not resolve host: github.com"
                                       :error-type :network})]
        (let [config {:resolved-tasks-dir "/test/.mcp-tasks"
                      :enable-git-sync? true}
              result (sut/sync-and-prepare-task-file config)]
          (is (false? (:success result)))
          (is (= "fatal: Could not resolve host: github.com" (:error result)))
          (is (= :network (:error-type result))))))

    (testing "returns error map on other errors"
      (with-redefs [git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                    git/pull-latest (fn [_ _]
                                      {:success false
                                       :pulled? false
                                       :error "fatal: some other error"
                                       :error-type :other})]
        (let [config {:resolved-tasks-dir "/test/.mcp-tasks"
                      :enable-git-sync? true}
              result (sut/sync-and-prepare-task-file config)]
          (is (false? (:success result)))
          (is (= "fatal: some other error" (:error result)))
          (is (= :other (:error-type result))))))

    (testing "returns error map when get-current-branch fails with other git errors"
      (with-redefs [git/get-current-branch (fn [_] {:success false :branch nil :error "fatal: corrupt git repository"})]
        (let [config {:resolved-tasks-dir "/test/.mcp-tasks"
                      :enable-git-sync? true}
              result (sut/sync-and-prepare-task-file config)]
          (is (false? (:success result)))
          (is (= "fatal: corrupt git repository" (:error result)))
          (is (= :other (:error-type result))))))))
