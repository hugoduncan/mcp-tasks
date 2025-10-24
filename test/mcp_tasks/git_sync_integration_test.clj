(ns mcp-tasks.git-sync-integration-test
  "Integration tests for git synchronization with file locking.

  Tests the interaction between file locking (Story #278) and git
  synchronization (Story #285). Verifies that:
  - File locking prevents concurrent file corruption
  - Git operations can race (last writer wins is acceptable)
  - Each operation gets latest state via pull before modifications
  - Proper error handling when pulls fail

  Note: Due to Java FileLock limitations (one lock per file per JVM),
  these tests verify sequential operations rather than true concurrency."
  (:require
    [clojure.java.shell :as sh]
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.tasks :as tasks]
    [mcp-tasks.test-helpers :as h]
    [mcp-tasks.tools.helpers :as helpers]))

(deftest ^:integ sync-with-sequential-operations-test
  ;; Verifies that sequential operations each sync with git before modification.
  ;; Each operation should pull latest changes, ensuring all operations work
  ;; with current state.
  (h/with-test-setup [test-dir]
    (testing "sync-and-prepare-task-file integration"
      (testing "pulls latest changes before each operation"
        (let [tasks-dir (str test-dir "/.mcp-tasks")
              config (h/git-test-config test-dir)]

          ;; Initialize git repo and create initial commit
          (h/init-git-repo test-dir)
          ;; Create empty tasks file before initial commit
          (h/write-ednl-test-file test-dir "tasks.ednl" [])
          (sh/sh "git" "add" "." :dir tasks-dir)
          (sh/sh "git" "commit" "-m" "Initial commit" :dir tasks-dir)

          ;; First operation: Add task and commit
          (helpers/with-task-lock
            config
            (fn []
              (let [sync-result (helpers/sync-and-prepare-task-file config)]
                ;; Should return tasks file path (no remote configured)
                (is (string? sync-result))
                (tasks/add-task {:id 1
                                 :title "Task 1"
                                 :description "First"
                                 :design ""
                                 :category "simple"
                                 :type :task
                                 :status :open
                                 :meta {}
                                 :relations []})
                (tasks/save-tasks! sync-result))))

          (sh/sh "git" "add" "." :dir tasks-dir)
          (sh/sh "git" "commit" "-m" "Add task 1" :dir tasks-dir)

          ;; Second operation: Sync pulls the commit, then add another task
          (helpers/with-task-lock
            config
            (fn []
              (let [sync-result (helpers/sync-and-prepare-task-file config)]
                ;; Should return tasks file path
                (is (string? sync-result))
                ;; Should have task from previous operation
                (is (= 1 (count (tasks/get-tasks))))
                (tasks/add-task {:id 2
                                 :title "Task 2"
                                 :description "Second"
                                 :design ""
                                 :category "simple"
                                 :type :task
                                 :status :open
                                 :meta {}
                                 :relations []})
                (tasks/save-tasks! sync-result))))

          ;; Verify both tasks exist
          (helpers/prepare-task-file config)
          (let [all-tasks (tasks/get-tasks)]
            (is (= 2 (count all-tasks)))
            (is (= #{"Task 1" "Task 2"}
                   (set (map :title all-tasks))))))))))

(deftest ^:integ sync-error-handling-with-conflicts-test
  ;; Verifies proper error handling when git pull encounters conflicts.
  ;; The operation should fail with appropriate error information.
  (h/with-test-setup [test-dir]
    (testing "sync-and-prepare-task-file error handling"
      (testing "handles pull conflicts appropriately"
        (let [tasks-dir (str test-dir "/.mcp-tasks")
              config (h/git-test-config test-dir)
              tasks-file (:absolute (helpers/task-path config ["tasks.ednl"]))]

          ;; Initialize git repo and create initial commit
          (h/init-git-repo test-dir)
          ;; Create empty tasks file before initial commit
          (h/write-ednl-test-file test-dir "tasks.ednl" [])
          (sh/sh "git" "add" "." :dir tasks-dir)
          (sh/sh "git" "commit" "-m" "Initial commit" :dir tasks-dir)

          ;; Create a "remote" by cloning to another directory
          (let [remote-dir (str test-dir "-remote")
                _ (sh/sh "git" "clone" tasks-dir remote-dir)
                ;; Set up the original as remote of the clone
                _ (sh/sh "git" "remote" "add" "origin" tasks-dir :dir remote-dir)
                ;; Also set up the clone as remote of the original
                _ (sh/sh "git" "remote" "add" "origin" remote-dir :dir tasks-dir)]

            ;; Make conflicting changes in original
            (helpers/with-task-lock
              config
              (fn []
                (helpers/prepare-task-file config)
                (tasks/add-task {:id 1
                                 :title "Task A"
                                 :description "From original"
                                 :design ""
                                 :category "simple"
                                 :type :task
                                 :status :open
                                 :meta {}
                                 :relations []})
                (tasks/save-tasks! tasks-file)))

            (sh/sh "git" "add" "." :dir tasks-dir)
            (sh/sh "git" "commit" "-m" "Add Task A" :dir tasks-dir)

            ;; Make different changes in remote and push
            (let [remote-config (assoc config :resolved-tasks-dir remote-dir)
                  remote-tasks-file (:absolute (helpers/task-path remote-config ["tasks.ednl"]))]
              (helpers/prepare-task-file remote-config)
              (tasks/add-task {:id 1
                               :title "Task B"
                               :description "From remote"
                               :design ""
                               :category "simple"
                               :type :task
                               :status :open
                               :meta {}
                               :relations []})
              (tasks/save-tasks! remote-tasks-file))

            (sh/sh "git" "add" "." :dir remote-dir)
            (sh/sh "git" "commit" "-m" "Add Task B" :dir remote-dir)

            ;; Push from remote to original (this will work)
            (sh/sh "git" "push" "origin" "master" :dir remote-dir)

            ;; Now try to sync in original - should detect we're behind
            ;; Since we have local commits that differ from remote, pull will fail
            (let [result (helpers/with-task-lock
                           config
                           (fn []
                             (helpers/sync-and-prepare-task-file config)))]
              ;; Should get an error result
              (cond
                ;; If sync returns error map directly
                (and (map? result) (false? (:success result)))
                (do
                  (is (contains? result :error))
                  (is (contains? result :error-type)))

                ;; If with-task-lock wraps it in tool error response
                (and (map? result) (:isError result))
                (is (string? (:content (first (:content result)))))

                ;; Otherwise unexpected result
                :else
                (is false "Expected error result from conflicting pull")))))))))

(deftest ^:integ sync-with-local-only-repo-test
  ;; Verifies that sync works correctly with local-only repos (no remote).
  ;; This is an acceptable configuration - should continue normally.
  (h/with-test-setup [test-dir]
    (testing "sync-and-prepare-task-file with local-only repo"
      (testing "handles no remote gracefully"
        (let [config (h/git-test-config test-dir)
              tasks-dir (str test-dir "/.mcp-tasks")]

          ;; Initialize git repo but don't add remote
          (h/init-git-repo test-dir)
          ;; Create empty tasks file before initial commit
          (h/write-ednl-test-file test-dir "tasks.ednl" [])
          ;; Create initial commit so git operations work
          (sh/sh "git" "add" "." :dir tasks-dir)
          (sh/sh "git" "commit" "-m" "Initial commit" :dir tasks-dir)

          ;; Sync should work even without remote
          (helpers/with-task-lock
            config
            (fn []
              (let [sync-result (helpers/sync-and-prepare-task-file config)]
                ;; Should return tasks file path (no remote is OK)
                (is (string? sync-result))
                (tasks/add-task {:id 1
                                 :title "Task 1"
                                 :description "Test"
                                 :design ""
                                 :category "simple"
                                 :type :task
                                 :status :open
                                 :meta {}
                                 :relations []})
                (tasks/save-tasks! sync-result))))

          ;; Verify task was added
          (helpers/prepare-task-file config)
          (is (= 1 (count (tasks/get-tasks)))))))))

(deftest ^:integ file-locking-prevents-corruption-with-sync-test
  ;; Verifies that file locking prevents corruption even when sync is involved.
  ;; Sequential operations should each complete successfully with proper locking.
  (h/with-test-setup [test-dir]
    (testing "file locking with sync"
      (testing "prevents corruption during sequential operations"
        (let [config (h/git-test-config test-dir)
              tasks-dir (str test-dir "/.mcp-tasks")]

          ;; Initialize git repo and create initial commit
          (h/init-git-repo test-dir)
          ;; Create empty tasks file before initial commit
          (h/write-ednl-test-file test-dir "tasks.ednl" [])
          (sh/sh "git" "add" "." :dir tasks-dir)
          (sh/sh "git" "commit" "-m" "Initial commit" :dir tasks-dir)

          ;; Perform multiple sequential operations, each with sync
          ;; Each should acquire lock, sync, modify, release lock
          ;; Commit changes between operations so sync can pull clean state
          (dotimes [i 3]
            (helpers/with-task-lock
              config
              (fn []
                (let [sync-result (helpers/sync-and-prepare-task-file config)]
                  (is (string? sync-result))
                  (tasks/add-task {:id (inc i)
                                   :title (str "Task " (inc i))
                                   :description (str "Test " (inc i))
                                   :design ""
                                   :category "simple"
                                   :type :task
                                   :status :open
                                   :meta {}
                                   :relations []})
                  (tasks/save-tasks! sync-result))))
            ;; Commit after each operation so next sync works
            (sh/sh "git" "add" "." :dir tasks-dir)
            (sh/sh "git" "commit" "-m" (str "Add task " (inc i)) :dir tasks-dir))

          ;; Verify all tasks were added correctly
          (helpers/prepare-task-file config)
          (let [all-tasks (tasks/get-tasks)]
            (is (= 3 (count all-tasks)))
            (is (= #{"Task 1" "Task 2" "Task 3"}
                   (set (map :title all-tasks))))))))))
