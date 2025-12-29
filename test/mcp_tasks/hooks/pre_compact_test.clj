(ns mcp-tasks.hooks.pre-compact-test
  "Tests for the PreCompact hook script.

  Tests the hook's behavior in various scenarios:
  - No story executing (no .mcp-tasks-current.edn)
  - Story executing (calls mcp-tasks update)
  - Error handling (graceful exit 0)"
  (:require
    [babashka.fs :as fs]
    [babashka.process :as p]
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing]])
  (:import
    (java.nio.file
      Files)
    (java.nio.file.attribute
      FileAttribute)))

(def hook-script
  "Path to the hook script."
  "resources/hooks/pre-compact.bb")

(defn create-temp-dir
  "Create a temporary directory for testing."
  []
  (str (Files/createTempDirectory "hook-test" (into-array FileAttribute []))))

(defn delete-dir
  "Recursively delete a directory."
  [dir]
  (fs/delete-tree dir))

(defn run-hook
  "Run the hook script with the given input JSON.

  Returns a map with :exit, :out, and :err."
  [input-json]
  (let [result (p/shell {:in input-json
                         :out :string
                         :err :string
                         :continue true}
                        "bb" hook-script)]
    {:exit (:exit result)
     :out (:out result)
     :err (:err result)}))

(deftest hook-exits-zero-test
  ;; Tests that the hook always exits 0 (non-blocking) in all scenarios.
  (testing "PreCompact hook"
    (testing "when no execution state file exists"
      (testing "exits 0"
        (let [tmp-dir (create-temp-dir)
              input (json/generate-string {:session_id "test"
                                           :trigger "manual"
                                           :cwd tmp-dir})]
          (try
            (let [result (run-hook input)]
              (is (= 0 (:exit result))))
            (finally
              (delete-dir tmp-dir))))))

    (testing "when execution state has no story-id"
      (testing "exits 0"
        (let [tmp-dir (create-temp-dir)
              _ (spit (str tmp-dir "/.mcp-tasks-current.edn")
                      (pr-str {:task-id 123}))
              input (json/generate-string {:session_id "test"
                                           :trigger "auto"
                                           :cwd tmp-dir})]
          (try
            (let [result (run-hook input)]
              (is (= 0 (:exit result))))
            (finally
              (delete-dir tmp-dir))))))

    (testing "when input is invalid JSON"
      (testing "exits 0"
        (let [result (run-hook "not valid json")]
          (is (= 0 (:exit result))))))

    (testing "when input is empty"
      (testing "exits 0"
        (let [result (run-hook "")]
          (is (= 0 (:exit result))))))

    (testing "when cwd is missing from input"
      (testing "exits 0"
        (let [input (json/generate-string {:session_id "test"
                                           :trigger "manual"})
              result (run-hook input)]
          (is (= 0 (:exit result))))))))

(deftest hook-with-story-test
  ;; Tests hook behavior when a story is executing.
  ;; NOTE: This test may fail if mcp-tasks CLI is not available or doesn't
  ;; support --session-events. The hook should still exit 0.
  (testing "PreCompact hook"
    (testing "when story is executing"
      (testing "exits 0 even if mcp-tasks command fails"
        (let [tmp-dir (create-temp-dir)
              ;; Story ID 99999 doesn't exist, so update will fail
              _ (spit (str tmp-dir "/.mcp-tasks-current.edn")
                      (pr-str {:task-id 100 :story-id 99999}))
              input (json/generate-string {:session_id "test"
                                           :trigger "manual"
                                           :cwd tmp-dir})]
          (try
            (let [result (run-hook input)]
              ;; Hook should still exit 0 even if mcp-tasks fails
              (is (= 0 (:exit result))))
            (finally
              (delete-dir tmp-dir))))))))
