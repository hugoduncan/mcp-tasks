(ns mcp-tasks.tool.work-on-test
  (:require
    [cheshire.core :as json]
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
