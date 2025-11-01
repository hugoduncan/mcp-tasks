(ns mcp-tasks.tool.work-on-test
  (:require
    [babashka.fs :as fs]
    [cheshire.core :as json]
    [clojure.java.shell :as sh]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.execution-state :as execution-state]
    [mcp-tasks.test-helpers :as h]
    [mcp-tasks.tool.add-task :as add-task]
    [mcp-tasks.tool.work-on :as sut]))

(deftest extract-worktree-name-test
  ;; Test the extract-worktree-name helper function
  (testing "extract-worktree-name"
    (testing "extracts final path component from absolute paths"
      (is (= "mcp-tasks-fix-bug"
             (#'sut/extract-worktree-name "/Users/duncan/projects/mcp-tasks-fix-bug")))
      (is (= "mcp-tasks-fix-bug"
             (#'sut/extract-worktree-name "/Users/duncan/projects/mcp-tasks-fix-bug/"))))

    (testing "handles nil input"
      (is (nil? (#'sut/extract-worktree-name nil))))))

(deftest work-on-parameter-validation
  (h/with-test-setup [test-dir]
    ;; Test that work-on validates input parameters correctly
    (testing "work-on parameter validation"
      (testing "validates task-id is required"
        (let [result (#'sut/work-on-impl (h/test-config test-dir) nil {})
              response (json/parse-string (get-in result [:content 0 :text]) keyword)]
          (is (false? (:isError result)))
          (is (contains? response :error))
          (is (str/includes? (:error response) "task-id parameter is required"))))

      (testing "validates task-id is an integer"
        (let [result (#'sut/work-on-impl (h/test-config test-dir) nil {:task-id "not-an-int"})
              response (json/parse-string (get-in result [:content 0 :text]) keyword)]
          (is (false? (:isError result)))
          (is (contains? response :error))
          (is (str/includes? (:error response) "task-id must be an integer"))
          (is (= "not-an-int" (get-in response [:metadata :provided-value])))
          (is (contains? (:metadata response) :provided-type))))

      (testing "validates task exists"
        (let [result (#'sut/work-on-impl (h/test-config test-dir) nil {:task-id 99999})
              response (json/parse-string (get-in result [:content 0 :text]) keyword)]
          (is (false? (:isError result)))
          (is (contains? response :error))
          (is (str/includes? (:error response) "No task found"))
          (is (= 99999 (get-in response [:metadata :task-id])))
          (is (contains? (:metadata response) :file)))))))

(deftest work-on-returns-task-details
  (h/with-test-setup [test-dir]
    ;; Test that work-on returns task details when task exists
    (testing "work-on returns task details"
      ;; Add a task
      (let [add-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Test Task" :type "task"})
            add-response (json/parse-string (get-in add-result [:content 1 :text]) keyword)
            task-id (get-in add-response [:task :id])

            ;; Call work-on with the task-id
            result (#'sut/work-on-impl (h/test-config test-dir) nil {:task-id task-id})
            response (json/parse-string (get-in result [:content 0 :text]) keyword)]

        (is (false? (:isError result)))
        (is (= task-id (:task-id response)))
        (is (= "Test Task" (:title response)))
        (is (= "simple" (:category response)))
        (is (= "task" (:type response)))
        (is (= "open" (:status response)))
        (is (str/includes? (:message response) "validated successfully"))
        ;; worktree-name should NOT be present when worktree management is disabled
        (is (not (contains? response :worktree-name)))))))

(deftest work-on-handles-different-task-types
  (h/with-test-setup [test-dir]
    ;; Test that work-on works with different task types
    (testing "work-on handles different task types"
      (testing "works with bug type"
        (let [add-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Bug Fix" :type "bug"})
              add-response (json/parse-string (get-in add-result [:content 1 :text]) keyword)
              task-id (get-in add-response [:task :id])

              result (#'sut/work-on-impl (h/test-config test-dir) nil {:task-id task-id})
              response (json/parse-string (get-in result [:content 0 :text]) keyword)]

          (is (false? (:isError result)))
          (is (= "Bug Fix" (:title response)))
          (is (= "bug" (:type response)))))

      (testing "works with feature type"
        (let [add-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "medium" :title "New Feature" :type "feature"})
              add-response (json/parse-string (get-in add-result [:content 1 :text]) keyword)
              task-id (get-in add-response [:task :id])

              result (#'sut/work-on-impl (h/test-config test-dir) nil {:task-id task-id})
              response (json/parse-string (get-in result [:content 0 :text]) keyword)]

          (is (false? (:isError result)))
          (is (= "New Feature" (:title response)))
          (is (= "feature" (:type response)))
          (is (= "medium" (:category response)))))

      (testing "works with story type"
        (let [add-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "story" :title "User Story" :type "story"})
              add-response (json/parse-string (get-in add-result [:content 1 :text]) keyword)
              task-id (get-in add-response [:task :id])

              result (#'sut/work-on-impl (h/test-config test-dir) nil {:task-id task-id})
              response (json/parse-string (get-in result [:content 0 :text]) keyword)]

          (is (false? (:isError result)))
          (is (= "User Story" (:title response)))
          (is (= "story" (:type response))))))))

(deftest work-on-writes-execution-state
  (h/with-test-setup [test-dir]
    ;; Test that work-on writes execution state correctly
    (testing "work-on writes execution state"
      (testing "writes execution state for standalone task"
        (let [add-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Standalone Task" :type "task"})
              add-response (json/parse-string (get-in add-result [:content 1 :text]) keyword)
              task-id (get-in add-response [:task :id])

              result (#'sut/work-on-impl (h/test-config test-dir) nil {:task-id task-id})
              response (json/parse-string (get-in result [:content 0 :text]) keyword)

              ;; Read execution state
              base-dir (:base-dir (h/test-config test-dir))
              state (execution-state/read-execution-state base-dir)]

          (is (false? (:isError result)))
          (is (str/includes? (:message response) "execution state written"))
          (is (contains? response :execution-state-file))

          ;; Verify execution state content
          (is (some? state))
          (is (= task-id (:task-id state)))
          (is (nil? (:story-id state)))
          (is (string? (:started-at state)))
          (is (not (str/blank? (:started-at state))))))

      (testing "writes execution state for story task"
        ;; Create a story
        (let [story-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "story" :title "Test Story" :type "story"})
              story-response (json/parse-string (get-in story-result [:content 1 :text]) keyword)
              story-id (get-in story-response [:task :id])

              ;; Create a task with parent-id
              task-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Story Task" :type "task" :parent-id story-id})
              task-response (json/parse-string (get-in task-result [:content 1 :text]) keyword)
              task-id (get-in task-response [:task :id])

              result (#'sut/work-on-impl (h/test-config test-dir) nil {:task-id task-id})
              response (json/parse-string (get-in result [:content 0 :text]) keyword)

              ;; Read execution state
              base-dir (:base-dir (h/test-config test-dir))
              state (execution-state/read-execution-state base-dir)]

          (is (false? (:isError result)))
          (is (str/includes? (:message response) "execution state written"))

          ;; Verify execution state content includes story-id
          (is (some? state))
          (is (= task-id (:task-id state)))
          (is (= story-id (:story-id state)))
          (is (string? (:started-at state))))))))

(deftest work-on-idempotency
  (h/with-test-setup [test-dir]
    ;; Test that calling work-on multiple times is safe and idempotent
    (testing "work-on is idempotent"
      (testing "calling multiple times updates execution state timestamp"
        (let [add-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Idempotent Task" :type "task"})
              add-response (json/parse-string (get-in add-result [:content 1 :text]) keyword)
              task-id (get-in add-response [:task :id])
              base-dir (:base-dir (h/test-config test-dir))

              ;; First call
              result1 (#'sut/work-on-impl (h/test-config test-dir) nil {:task-id task-id})
              response1 (json/parse-string (get-in result1 [:content 0 :text]) keyword)
              state1 (execution-state/read-execution-state base-dir)
              timestamp1 (:started-at state1)
              _ (is (false? (:isError result1)))
              _ (is (= task-id (:task-id response1)))

              ;; Wait a moment to ensure timestamp changes
              _ (Thread/sleep 10)

              ;; Second call
              result2 (#'sut/work-on-impl (h/test-config test-dir) nil {:task-id task-id})
              response2 (json/parse-string (get-in result2 [:content 0 :text]) keyword)
              state2 (execution-state/read-execution-state base-dir)
              timestamp2 (:started-at state2)]

          (is (false? (:isError result2)))
          (is (= task-id (:task-id response2)))

          ;; Execution state should be updated with new timestamp
          (is (= task-id (:task-id state2)))
          (is (not= timestamp1 timestamp2) "Timestamps should differ")))

      (testing "calling with different task-ids updates execution state"
        (let [add-result1 (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Task One" :type "task"})
              add-response1 (json/parse-string (get-in add-result1 [:content 1 :text]) keyword)
              task-id1 (get-in add-response1 [:task :id])

              add-result2 (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Task Two" :type "task"})
              add-response2 (json/parse-string (get-in add-result2 [:content 1 :text]) keyword)
              task-id2 (get-in add-response2 [:task :id])
              base-dir (:base-dir (h/test-config test-dir))]

          ;; Work on first task
          (#'sut/work-on-impl (h/test-config test-dir) nil {:task-id task-id1})
          (let [state1 (execution-state/read-execution-state base-dir)]
            (is (= task-id1 (:task-id state1))))

          ;; Work on second task
          (#'sut/work-on-impl (h/test-config test-dir) nil {:task-id task-id2})
          (let [state2 (execution-state/read-execution-state base-dir)]
            (is (= task-id2 (:task-id state2)))))))))

(deftest safe-to-remove-worktree-with-uncommitted-changes
  ;; Test that safety check fails when worktree has uncommitted changes
  (testing "safe-to-remove-worktree? with uncommitted changes"
    (h/with-test-setup [test-dir]
      ;; Initialize git repo in test-dir
      (sh/sh "git" "init" :dir (str test-dir))
      (sh/sh "git" "config" "user.email" "test@test.com" :dir (str test-dir))
      (sh/sh "git" "config" "user.name" "Test User" :dir (str test-dir))

      ;; Create initial commit
      (spit (str test-dir "/initial.txt") "initial")
      (sh/sh "git" "-C" (str test-dir) "add" "initial.txt")
      (sh/sh "git" "-C" (str test-dir) "commit" "-m" "Initial commit")

      ;; Create a worktree with a branch
      (let [worktree-path (str test-dir "-worktree")
            _ (sh/sh "git" "-C" (str test-dir) "worktree" "add" "-b" "test-branch" worktree-path "master")

            ;; Create an uncommitted file in the worktree
            test-file (str worktree-path "/uncommitted.txt")
            _ (spit test-file "uncommitted changes")

            ;; Call safe-to-remove-worktree?
            result (#'sut/safe-to-remove-worktree? worktree-path)]

        (is (false? (:safe? result)))
        (is (str/includes? (:reason result) "Uncommitted changes exist"))

        ;; Cleanup
        (sh/sh "git" "-C" (str test-dir) "worktree" "remove" "--force" worktree-path)))))

(deftest safe-to-remove-worktree-with-unpushed-commits
  ;; Test that safety check fails when worktree has unpushed commits
  (testing "safe-to-remove-worktree? with unpushed commits"
    (h/with-test-setup [test-dir]
      ;; Initialize git repo in test-dir
      (sh/sh "git" "init" :dir (str test-dir))
      (sh/sh "git" "config" "user.email" "test@test.com" :dir (str test-dir))
      (sh/sh "git" "config" "user.name" "Test User" :dir (str test-dir))

      ;; Create initial commit
      (spit (str test-dir "/initial.txt") "initial")
      (sh/sh "git" "-C" (str test-dir) "add" "initial.txt")
      (sh/sh "git" "-C" (str test-dir) "commit" "-m" "Initial commit")

      ;; Create a worktree with a branch
      (let [worktree-path (str test-dir "-worktree")
            _ (sh/sh "git" "-C" (str test-dir) "worktree" "add" "-b" "test-branch" worktree-path "master")

            ;; Create and commit a file in the worktree
            test-file (str worktree-path "/committed.txt")
            _ (spit test-file "committed changes")
            _ (sh/sh "git" "-C" worktree-path "add" "committed.txt")
            _ (sh/sh "git" "-C" worktree-path "commit" "-m" "Test commit")

            ;; Call safe-to-remove-worktree?
            result (#'sut/safe-to-remove-worktree? worktree-path)]

        (is (false? (:safe? result)))
        (is (str/includes? (:reason result) "remote"))

        ;; Cleanup
        (sh/sh "git" "-C" (str test-dir) "worktree" "remove" "--force" worktree-path)))))

(deftest safe-to-remove-worktree-with-no-remote
  ;; Test that safety check fails when no remote is configured
  (testing "safe-to-remove-worktree? with no remote"
    (h/with-test-setup [test-dir]
      ;; Initialize git repo in test-dir
      (sh/sh "git" "init" :dir (str test-dir))
      (sh/sh "git" "config" "user.email" "test@test.com" :dir (str test-dir))
      (sh/sh "git" "config" "user.name" "Test User" :dir (str test-dir))

      ;; Create initial commit
      (spit (str test-dir "/initial.txt") "initial")
      (sh/sh "git" "-C" (str test-dir) "add" "initial.txt")
      (sh/sh "git" "-C" (str test-dir) "commit" "-m" "Initial commit")

      ;; Create a worktree with a branch
      (let [worktree-path (str test-dir "-worktree")
            _ (sh/sh "git" "-C" (str test-dir) "worktree" "add" "-b" "test-branch" worktree-path "master")

            ;; Call safe-to-remove-worktree? (no commits, so should check remote)
            result (#'sut/safe-to-remove-worktree? worktree-path)]

        (is (false? (:safe? result)))
        (is (str/includes? (:reason result) "remote"))

        ;; Cleanup
        (sh/sh "git" "-C" (str test-dir) "worktree" "remove" "--force" worktree-path)))))

(deftest safe-to-remove-worktree-clean-and-pushed
  ;; Test that safety check passes when worktree is clean and all commits are pushed
  (testing "safe-to-remove-worktree? when clean and pushed"
    (h/with-test-setup [test-dir]
      ;; Create main repo with a remote
      (let [remote-dir (fs/create-temp-dir {:prefix "mcp-tasks-remote-"})
            ;; Initialize remote repo
            _ (sh/sh "git" "init" "--bare" :dir (str remote-dir))

            ;; Initialize local repo
            _ (sh/sh "git" "init" :dir (str test-dir))
            _ (sh/sh "git" "config" "user.email" "test@test.com" :dir (str test-dir))
            _ (sh/sh "git" "config" "user.name" "Test User" :dir (str test-dir))
            _ (sh/sh "git" "-C" (str test-dir) "remote" "add" "origin" (str remote-dir))

            ;; Create initial commit in local
            test-file (str test-dir "/initial.txt")
            _ (spit test-file "initial")
            _ (sh/sh "git" "-C" (str test-dir) "add" "initial.txt")
            _ (sh/sh "git" "-C" (str test-dir) "commit" "-m" "Initial commit")

            ;; Push to remote
            _ (sh/sh "git" "-C" (str test-dir) "push" "-u" "origin" "master")

            ;; Create a worktree with a new branch based on master (which is pushed)
            worktree-path (str test-dir "-worktree")
            _ (sh/sh "git" "-C" (str test-dir) "worktree" "add" "-b" "test-branch" worktree-path "master")

            ;; Push the new branch from the worktree
            _ (sh/sh "git" "-C" worktree-path "push" "-u" "origin" "test-branch")

            ;; Call safe-to-remove-worktree?
            result (#'sut/safe-to-remove-worktree? worktree-path)]

        (is (true? (:safe? result)))
        (is (str/includes? (:reason result) "clean"))
        (is (str/includes? (:reason result) "pushed"))

        ;; Cleanup
        (sh/sh "git" "-C" (str test-dir) "worktree" "remove" worktree-path)
        (fs/delete-tree remote-dir)))))

(deftest cleanup-worktree-succeeds
  ;; Test that cleanup succeeds when safety checks pass
  (testing "cleanup-worktree-after-completion succeeds"
    (h/with-test-setup [test-dir]
      ;; Create main repo with a remote
      (let [remote-dir (fs/create-temp-dir {:prefix "mcp-tasks-remote-"})
            ;; Initialize remote repo
            _ (sh/sh "git" "init" "--bare" :dir (str remote-dir))

            ;; Initialize local repo
            _ (sh/sh "git" "init" :dir (str test-dir))
            _ (sh/sh "git" "config" "user.email" "test@test.com" :dir (str test-dir))
            _ (sh/sh "git" "config" "user.name" "Test User" :dir (str test-dir))
            _ (sh/sh "git" "-C" (str test-dir) "remote" "add" "origin" (str remote-dir))

            ;; Create initial commit in local
            test-file (str test-dir "/initial.txt")
            _ (spit test-file "initial")
            _ (sh/sh "git" "-C" (str test-dir) "add" "initial.txt")
            _ (sh/sh "git" "-C" (str test-dir) "commit" "-m" "Initial commit")

            ;; Push to remote
            _ (sh/sh "git" "-C" (str test-dir) "push" "-u" "origin" "master")

            ;; Create a worktree with a new branch based on master (which is pushed)
            worktree-path (str test-dir "-worktree")
            _ (sh/sh "git" "-C" (str test-dir) "worktree" "add" "-b" "test-branch" worktree-path "master")

            ;; Push the new branch from the worktree
            _ (sh/sh "git" "-C" worktree-path "push" "-u" "origin" "test-branch")

            ;; Call cleanup-worktree-after-completion
            config (h/test-config test-dir)
            result (sut/cleanup-worktree-after-completion (str test-dir) worktree-path config)]

        (is (true? (:success result)))
        (is (nil? (:error result)))
        (is (str/includes? (:message result) "removed"))
        (is (str/includes? (:message result) worktree-path))

        ;; Verify worktree was actually removed
        (is (not (fs/exists? worktree-path)))

        ;; Cleanup
        (fs/delete-tree remote-dir)))))

(deftest cleanup-worktree-fails-safety-checks
  ;; Test that cleanup fails when safety checks don't pass
  (testing "cleanup-worktree-after-completion fails safety checks"
    (testing "fails with uncommitted changes"
      (h/with-test-setup [test-dir]
        ;; Initialize git repo in test-dir
        (sh/sh "git" "init" :dir (str test-dir))
        (sh/sh "git" "config" "user.email" "test@test.com" :dir (str test-dir))
        (sh/sh "git" "config" "user.name" "Test User" :dir (str test-dir))

        ;; Create initial commit
        (spit (str test-dir "/initial.txt") "initial")
        (sh/sh "git" "-C" (str test-dir) "add" "initial.txt")
        (sh/sh "git" "-C" (str test-dir) "commit" "-m" "Initial commit")

        ;; Create a worktree with a branch
        (let [worktree-path (str test-dir "-worktree")
              _ (sh/sh "git" "-C" (str test-dir) "worktree" "add" "-b" "test-branch" worktree-path "master")

              ;; Create an uncommitted file in the worktree
              test-file (str worktree-path "/uncommitted.txt")
              _ (spit test-file "uncommitted changes")

              ;; Call cleanup-worktree-after-completion
              config (h/test-config test-dir)
              result (sut/cleanup-worktree-after-completion (str test-dir) worktree-path config)]

          (is (false? (:success result)))
          (is (nil? (:message result)))
          (is (str/includes? (:error result) "Cannot remove worktree"))
          (is (str/includes? (:error result) "Uncommitted changes"))

          ;; Verify worktree still exists
          (is (fs/exists? worktree-path))

          ;; Cleanup
          (sh/sh "git" "-C" (str test-dir) "worktree" "remove" "--force" worktree-path))))

    (testing "fails with unpushed commits"
      (h/with-test-setup [test-dir]
        ;; Initialize git repo in test-dir
        (sh/sh "git" "init" :dir (str test-dir))
        (sh/sh "git" "config" "user.email" "test@test.com" :dir (str test-dir))
        (sh/sh "git" "config" "user.name" "Test User" :dir (str test-dir))

        ;; Create initial commit
        (spit (str test-dir "/initial.txt") "initial")
        (sh/sh "git" "-C" (str test-dir) "add" "initial.txt")
        (sh/sh "git" "-C" (str test-dir) "commit" "-m" "Initial commit")

        ;; Create a worktree with a branch
        (let [worktree-path (str test-dir "-worktree")
              _ (sh/sh "git" "-C" (str test-dir) "worktree" "add" "-b" "test-branch" worktree-path "master")

              ;; Create and commit a file in the worktree
              test-file (str worktree-path "/committed.txt")
              _ (spit test-file "committed changes")
              _ (sh/sh "git" "-C" worktree-path "add" "committed.txt")
              _ (sh/sh "git" "-C" worktree-path "commit" "-m" "Test commit")

              ;; Call cleanup-worktree-after-completion
              config (h/test-config test-dir)
              result (sut/cleanup-worktree-after-completion (str test-dir) worktree-path config)]

          (is (false? (:success result)))
          (is (nil? (:message result)))
          (is (str/includes? (:error result) "Cannot remove worktree"))

          ;; Verify worktree still exists
          (is (fs/exists? worktree-path))

          ;; Cleanup
          (sh/sh "git" "-C" (str test-dir) "worktree" "remove" "--force" worktree-path))))))

(deftest work-on-includes-blocked-status
  ;; Test that work-on includes blocked status in response
  (testing "work-on includes blocked status"
    (testing "returns unblocked status for task without relations"
      (h/with-test-setup [test-dir]
        (let [add-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Task A" :type "task"})
              add-response (json/parse-string (get-in add-result [:content 1 :text]) keyword)
              task-id (get-in add-response [:task :id])

              result (#'sut/work-on-impl (h/test-config test-dir) nil {:task-id task-id})
              response (json/parse-string (get-in result [:content 0 :text]) keyword)]

          (is (false? (:isError result)))
          (is (= false (:is-blocked response)))
          (is (= [] (:blocking-task-ids response))))))

    (testing "returns unblocked status when blocked-by task is completed"
      (h/with-test-setup [test-dir]
        (let [;; Create task A
              add-a (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Task A" :type "task"})
              resp-a (json/parse-string (get-in add-a [:content 1 :text]) keyword)
              task-a-id (get-in resp-a [:task :id])

              ;; Create task B
              add-b (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Task B" :type "task"})
              resp-b (json/parse-string (get-in add-b [:content 1 :text]) keyword)
              task-b-id (get-in resp-b [:task :id])

              ;; Update task B to be blocked-by task A
              update-task-ns (find-ns 'mcp-tasks.tool.update-task)
              _ (require 'mcp-tasks.tool.update-task)
              update-task-impl (ns-resolve update-task-ns 'update-task-impl)
              _ (update-task-impl (h/test-config test-dir) nil
                                  {:task-id task-b-id
                                   :relations [{"id" 1 "relates-to" task-a-id "as-type" "blocked-by"}]})

              ;; Complete task A
              complete-task-ns (find-ns 'mcp-tasks.tool.complete-task)
              _ (require 'mcp-tasks.tool.complete-task)
              complete-task-impl (ns-resolve complete-task-ns 'complete-task-impl)
              _ (complete-task-impl (h/test-config test-dir) nil {:task-id task-a-id})

              ;; Work on task B
              result (#'sut/work-on-impl (h/test-config test-dir) nil {:task-id task-b-id})
              response (json/parse-string (get-in result [:content 0 :text]) keyword)]

          (is (false? (:isError result)))
          (is (= false (:is-blocked response)))
          (is (= [] (:blocking-task-ids response))))))

    (testing "returns blocked status when blocked-by task is incomplete"
      (h/with-test-setup [test-dir]
        (let [;; Create task A
              add-a (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Task A" :type "task"})
              resp-a (json/parse-string (get-in add-a [:content 1 :text]) keyword)
              task-a-id (get-in resp-a [:task :id])

              ;; Create task B
              add-b (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Task B" :type "task"})
              resp-b (json/parse-string (get-in add-b [:content 1 :text]) keyword)
              task-b-id (get-in resp-b [:task :id])

              ;; Update task B to be blocked-by task A
              update-task-ns (find-ns 'mcp-tasks.tool.update-task)
              _ (require 'mcp-tasks.tool.update-task)
              update-task-impl (ns-resolve update-task-ns 'update-task-impl)
              _ (update-task-impl (h/test-config test-dir) nil
                                  {:task-id task-b-id
                                   :relations [{"id" 1 "relates-to" task-a-id "as-type" "blocked-by"}]})

              ;; Work on task B (task A is still incomplete)
              result (#'sut/work-on-impl (h/test-config test-dir) nil {:task-id task-b-id})
              response (json/parse-string (get-in result [:content 0 :text]) keyword)]

          (is (false? (:isError result)))
          (is (= true (:is-blocked response)))
          (is (= [task-a-id] (:blocking-task-ids response))))))))
