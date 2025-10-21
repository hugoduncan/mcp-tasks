(ns mcp-tasks.tool.work-on-test
  (:require
    [clojure.data.json :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-tasks.execution-state :as execution-state]
    [mcp-tasks.test-helpers :as h]
    [mcp-tasks.tool.add-task :as add-task]
    [mcp-tasks.tool.work-on :as sut]))

(use-fixtures :each h/test-fixture)

(deftest work-on-validates-task-id-required
  ;; Test that work-on requires task-id parameter
  (testing "work-on validates task-id is required"
    (let [result (#'sut/work-on-impl (h/test-config) nil {})
          response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
      (is (false? (:isError result)))
      (is (contains? response :error))
      (is (str/includes? (:error response) "task-id parameter is required")))))

(deftest work-on-validates-task-id-type
  ;; Test that work-on validates task-id is an integer
  (testing "work-on validates task-id is an integer"
    (let [result (#'sut/work-on-impl (h/test-config) nil {:task-id "not-an-int"})
          response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
      (is (false? (:isError result)))
      (is (contains? response :error))
      (is (str/includes? (:error response) "task-id must be an integer"))
      (is (= "not-an-int" (get-in response [:metadata :provided-value])))
      (is (contains? (:metadata response) :provided-type)))))

(deftest work-on-validates-task-exists
  ;; Test that work-on validates the task exists
  (testing "work-on validates task exists"
    (testing "returns error when task does not exist"
      (let [result (#'sut/work-on-impl (h/test-config) nil {:task-id 99999})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
        (is (false? (:isError result)))
        (is (contains? response :error))
        (is (str/includes? (:error response) "No task found"))
        (is (= 99999 (get-in response [:metadata :task-id])))
        (is (contains? (:metadata response) :file))))))

(deftest work-on-returns-task-details
  ;; Test that work-on returns task details when task exists
  (testing "work-on returns task details"
    ;; Add a task
    (let [add-result (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Test Task" :type "task"})
          add-response (json/read-str (get-in add-result [:content 1 :text]) :key-fn keyword)
          task-id (get-in add-response [:task :id])

          ;; Call work-on with the task-id
          result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
          response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

      (is (false? (:isError result)))
      (is (= task-id (:task-id response)))
      (is (= "Test Task" (:title response)))
      (is (= "simple" (:category response)))
      (is (= "task" (:type response)))
      (is (= "open" (:status response)))
      (is (str/includes? (:message response) "validated successfully")))))

(deftest work-on-handles-different-task-types
  ;; Test that work-on works with different task types
  (testing "work-on handles different task types"
    (testing "works with bug type"
      (let [add-result (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Bug Fix" :type "bug"})
            add-response (json/read-str (get-in add-result [:content 1 :text]) :key-fn keyword)
            task-id (get-in add-response [:task :id])

            result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

        (is (false? (:isError result)))
        (is (= "Bug Fix" (:title response)))
        (is (= "bug" (:type response)))))

    (testing "works with feature type"
      (let [add-result (#'add-task/add-task-impl (h/test-config) nil {:category "medium" :title "New Feature" :type "feature"})
            add-response (json/read-str (get-in add-result [:content 1 :text]) :key-fn keyword)
            task-id (get-in add-response [:task :id])

            result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

        (is (false? (:isError result)))
        (is (= "New Feature" (:title response)))
        (is (= "feature" (:type response)))
        (is (= "medium" (:category response)))))

    (testing "works with story type"
      (let [add-result (#'add-task/add-task-impl (h/test-config) nil {:category "story" :title "User Story" :type "story"})
            add-response (json/read-str (get-in add-result [:content 1 :text]) :key-fn keyword)
            task-id (get-in add-response [:task :id])

            result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

        (is (false? (:isError result)))
        (is (= "User Story" (:title response)))
        (is (= "story" (:type response)))))))

(deftest work-on-writes-execution-state
  ;; Test that work-on writes execution state correctly
  (testing "work-on writes execution state"
    (testing "writes execution state for standalone task"
      (let [add-result (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Standalone Task" :type "task"})
            add-response (json/read-str (get-in add-result [:content 1 :text]) :key-fn keyword)
            task-id (get-in add-response [:task :id])

            result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)

            ;; Read execution state
            base-dir (:base-dir (h/test-config))
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
      (let [story-result (#'add-task/add-task-impl (h/test-config) nil {:category "story" :title "Test Story" :type "story"})
            story-response (json/read-str (get-in story-result [:content 1 :text]) :key-fn keyword)
            story-id (get-in story-response [:task :id])

            ;; Create a task with parent-id
            task-result (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Story Task" :type "task" :parent-id story-id})
            task-response (json/read-str (get-in task-result [:content 1 :text]) :key-fn keyword)
            task-id (get-in task-response [:task :id])

            result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)

            ;; Read execution state
            base-dir (:base-dir (h/test-config))
            state (execution-state/read-execution-state base-dir)]

        (is (false? (:isError result)))
        (is (str/includes? (:message response) "execution state written"))

        ;; Verify execution state content includes story-id
        (is (some? state))
        (is (= task-id (:task-id state)))
        (is (= story-id (:story-id state)))
        (is (string? (:started-at state)))))))

(deftest work-on-branch-management-disabled-by-default
  ;; Test that branch management is disabled when no config exists
  (testing "work-on doesn't manage branches when branch-management? is not configured"
    (let [add-result (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Test Task" :type "task"})
          add-response (json/read-str (get-in add-result [:content 1 :text]) :key-fn keyword)
          task-id (get-in add-response [:task :id])

          result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
          response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

      (is (false? (:isError result)))
      (is (= task-id (:task-id response)))
      ;; No branch info should be present
      (is (not (contains? response :branch-name)))
      (is (not (contains? response :branch-created?)))
      (is (not (contains? response :branch-switched?))))))

(deftest work-on-branch-management-for-story-tasks
  ;; Test branch management for tasks with parent-id (story tasks)
  (testing "work-on manages branches for story tasks when enabled"
    (testing "creates new branch from story title when not on correct branch"
      ;; Create .mcp-tasks.edn with branch-management enabled
      (let [base-dir (:base-dir (h/test-config))
            config-file (str base-dir "/.mcp-tasks.edn")]
        (spit config-file "{:branch-management? true}")

        ;; Create a story
        (let [story-result (#'add-task/add-task-impl (h/test-config) nil {:category "story" :title "Add New Feature" :type "story"})
              story-response (json/read-str (get-in story-result [:content 1 :text]) :key-fn keyword)
              story-id (get-in story-response [:task :id])

              ;; Create a task with parent-id
              task-result (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Implement Component" :type "task" :parent-id story-id})
              task-response (json/read-str (get-in task-result [:content 1 :text]) :key-fn keyword)
              task-id (get-in task-response [:task :id])]

          ;; Mock git operations
          (with-redefs [mcp-tasks.tools.git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                        mcp-tasks.tools.git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                        mcp-tasks.tools.git/get-default-branch (fn [_] {:success true :branch "main" :error nil})
                        mcp-tasks.tools.git/checkout-branch (fn [_ _] {:success true :error nil})
                        mcp-tasks.tools.git/pull-latest (fn [_ _] {:success true :pulled? true :error nil})
                        mcp-tasks.tools.git/branch-exists? (fn [_ _] {:success true :exists? false :error nil})
                        mcp-tasks.tools.git/create-and-checkout-branch (fn [_ branch-name]
                                                                         (is (= "add-new-feature" branch-name))
                                                                         {:success true :error nil})]

            (let [result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
                  response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

              (is (false? (:isError result)))
              (is (= "add-new-feature" (:branch-name response)))
              (is (true? (:branch-created? response)))
              (is (true? (:branch-switched? response))))))))

    (testing "checks out existing branch when it already exists"
      (let [base-dir (:base-dir (h/test-config))
            config-file (str base-dir "/.mcp-tasks.edn")]
        (spit config-file "{:branch-management? true}")

        ;; Create a story
        (let [story-result (#'add-task/add-task-impl (h/test-config) nil {:category "story" :title "Fix Bug" :type "story"})
              story-response (json/read-str (get-in story-result [:content 1 :text]) :key-fn keyword)
              story-id (get-in story-response [:task :id])

              task-result (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Fix Issue" :type "task" :parent-id story-id})
              task-response (json/read-str (get-in task-result [:content 1 :text]) :key-fn keyword)
              task-id (get-in task-response [:task :id])]

          ;; Mock git operations - branch exists
          (with-redefs [mcp-tasks.tools.git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                        mcp-tasks.tools.git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                        mcp-tasks.tools.git/get-default-branch (fn [_] {:success true :branch "main" :error nil})
                        mcp-tasks.tools.git/checkout-branch (fn [_ branch-name]
                                                              (when (= branch-name "fix-bug")
                                                                (is true "Checking out existing branch"))
                                                              {:success true :error nil})
                        mcp-tasks.tools.git/pull-latest (fn [_ _] {:success true :pulled? true :error nil})
                        mcp-tasks.tools.git/branch-exists? (fn [_ branch-name]
                                                             (is (= "fix-bug" branch-name))
                                                             {:success true :exists? true :error nil})]

            (let [result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
                  response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

              (is (false? (:isError result)))
              (is (= "fix-bug" (:branch-name response)))
              (is (false? (:branch-created? response)))
              (is (true? (:branch-switched? response))))))))

    (testing "does not switch when already on correct branch"
      (let [base-dir (:base-dir (h/test-config))
            config-file (str base-dir "/.mcp-tasks.edn")]
        (spit config-file "{:branch-management? true}")

        (let [story-result (#'add-task/add-task-impl (h/test-config) nil {:category "story" :title "Optimize Performance" :type "story"})
              story-response (json/read-str (get-in story-result [:content 1 :text]) :key-fn keyword)
              story-id (get-in story-response [:task :id])

              task-result (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Optimize Query" :type "task" :parent-id story-id})
              task-response (json/read-str (get-in task-result [:content 1 :text]) :key-fn keyword)
              task-id (get-in task-response [:task :id])]

          ;; Mock git operations - already on correct branch
          (with-redefs [mcp-tasks.tools.git/get-current-branch (fn [_] {:success true :branch "optimize-performance" :error nil})]

            (let [result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
                  response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

              (is (false? (:isError result)))
              (is (= "optimize-performance" (:branch-name response)))
              (is (false? (:branch-created? response)))
              (is (false? (:branch-switched? response))))))))))

(deftest work-on-branch-management-for-standalone-tasks
  ;; Test branch management for tasks without parent-id (standalone tasks)
  (testing "work-on manages branches for standalone tasks when enabled"
    (testing "uses task title for branch name"
      (let [base-dir (:base-dir (h/test-config))
            config-file (str base-dir "/.mcp-tasks.edn")]
        (spit config-file "{:branch-management? true}")

        (let [add-result (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Update Documentation" :type "task"})
              add-response (json/read-str (get-in add-result [:content 1 :text]) :key-fn keyword)
              task-id (get-in add-response [:task :id])]

          ;; Mock git operations
          (with-redefs [mcp-tasks.tools.git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                        mcp-tasks.tools.git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                        mcp-tasks.tools.git/get-default-branch (fn [_] {:success true :branch "main" :error nil})
                        mcp-tasks.tools.git/checkout-branch (fn [_ _] {:success true :error nil})
                        mcp-tasks.tools.git/pull-latest (fn [_ _] {:success true :pulled? true :error nil})
                        mcp-tasks.tools.git/branch-exists? (fn [_ _] {:success true :exists? false :error nil})
                        mcp-tasks.tools.git/create-and-checkout-branch (fn [_ branch-name]
                                                                         (is (= "update-documentation" branch-name))
                                                                         {:success true :error nil})]

            (let [result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
                  response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

              (is (false? (:isError result)))
              (is (= "update-documentation" (:branch-name response)))
              (is (true? (:branch-created? response)))
              (is (true? (:branch-switched? response))))))))))

(deftest work-on-branch-management-error-handling
  ;; Test error handling for branch management operations
  (testing "work-on handles branch management errors"
    (testing "returns error when uncommitted changes detected"
      (let [base-dir (:base-dir (h/test-config))
            config-file (str base-dir "/.mcp-tasks.edn")]
        (spit config-file "{:branch-management? true}")

        (let [add-result (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Test Task" :type "task"})
              add-response (json/read-str (get-in add-result [:content 1 :text]) :key-fn keyword)
              task-id (get-in add-response [:task :id])]

          ;; Mock git operations - uncommitted changes
          (with-redefs [mcp-tasks.tools.git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                        mcp-tasks.tools.git/check-uncommitted-changes (fn [_] {:success true :has-changes? true :error nil})]

            (let [result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
                  response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

              (is (false? (:isError result)))
              (is (contains? response :error))
              (is (str/includes? (:error response) "uncommitted changes"))
              (is (= "main" (get-in response [:metadata :current-branch])))
              (is (= "test-task" (get-in response [:metadata :target-branch]))))))))

    (testing "handles local-only repo gracefully"
      (let [base-dir (:base-dir (h/test-config))
            config-file (str base-dir "/.mcp-tasks.edn")]
        (spit config-file "{:branch-management? true}")

        (let [add-result (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Local Task" :type "task"})
              add-response (json/read-str (get-in add-result [:content 1 :text]) :key-fn keyword)
              task-id (get-in add-response [:task :id])]

          ;; Mock git operations - local-only repo
          (with-redefs [mcp-tasks.tools.git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                        mcp-tasks.tools.git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                        mcp-tasks.tools.git/get-default-branch (fn [_] {:success true :branch "main" :error nil})
                        mcp-tasks.tools.git/checkout-branch (fn [_ _] {:success true :error nil})
                        mcp-tasks.tools.git/pull-latest (fn [_ _] {:success true :pulled? false :error nil})
                        mcp-tasks.tools.git/branch-exists? (fn [_ _] {:success true :exists? false :error nil})
                        mcp-tasks.tools.git/create-and-checkout-branch (fn [_ _] {:success true :error nil})]

            (let [result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
                  response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

              ;; Should succeed despite failed pull
              (is (false? (:isError result)))
              (is (= "local-task" (:branch-name response)))
              (is (true? (:branch-created? response)))
              (is (true? (:branch-switched? response))))))))

    (testing "returns error when git operation fails"
      (let [base-dir (:base-dir (h/test-config))
            config-file (str base-dir "/.mcp-tasks.edn")]
        (spit config-file "{:branch-management? true}")

        (let [add-result (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Fail Task" :type "task"})
              add-response (json/read-str (get-in add-result [:content 1 :text]) :key-fn keyword)
              task-id (get-in add-response [:task :id])]

          ;; Mock git operations - checkout fails
          (with-redefs [mcp-tasks.tools.git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                        mcp-tasks.tools.git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                        mcp-tasks.tools.git/get-default-branch (fn [_] {:success true :branch "main" :error nil})
                        mcp-tasks.tools.git/checkout-branch (fn [_ _] {:success false :error "Failed to checkout branch"})
                        mcp-tasks.tools.git/pull-latest (fn [_ _] {:success true :pulled? true :error nil})]

            (let [result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
                  response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

              (is (false? (:isError result)))
              (is (contains? response :error))
              (is (str/includes? (:error response) "Failed to checkout"))
              (is (contains? (:metadata response) :operation)))))))))
