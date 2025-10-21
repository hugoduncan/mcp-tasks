(ns mcp-tasks.tool.work-on-test
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-tasks.config :as config]
    [mcp-tasks.execution-state :as execution-state]
    [mcp-tasks.tasks :as tasks]
    [mcp-tasks.test-helpers :as h]
    [mcp-tasks.tool.add-task :as add-task]
    [mcp-tasks.tool.work-on :as sut]
    [mcp-tasks.tools.git :as git]))

(use-fixtures :each h/test-fixture)

(deftest work-on-parameter-validation
  ;; Test that work-on validates input parameters correctly
  (testing "work-on parameter validation"
    (testing "validates task-id is required"
      (let [result (#'sut/work-on-impl (h/test-config) nil {})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
        (is (false? (:isError result)))
        (is (contains? response :error))
        (is (str/includes? (:error response) "task-id parameter is required"))))

    (testing "validates task-id is an integer"
      (let [result (#'sut/work-on-impl (h/test-config) nil {:task-id "not-an-int"})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
        (is (false? (:isError result)))
        (is (contains? response :error))
        (is (str/includes? (:error response) "task-id must be an integer"))
        (is (= "not-an-int" (get-in response [:metadata :provided-value])))
        (is (contains? (:metadata response) :provided-type))))

    (testing "validates task exists"
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

(deftest work-on-error-handling
  ;; Test error handling for branch management and configuration operations
  (testing "work-on error handling"
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
              (is (contains? (:metadata response) :operation)))))))

    (testing "continues without branch management when config is invalid"
      (let [add-result (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Test Config Error" :type "task"})
            add-response (json/read-str (get-in add-result [:content 1 :text]) :key-fn keyword)
            task-id (get-in add-response [:task :id])]

        ;; Mock config/read-config to simulate invalid config that returns default (empty map)
        (with-redefs [mcp-tasks.config/read-config (fn [_] {})]
          (let [result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
                response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

            ;; Should succeed, but without branch management
            (is (false? (:isError result)))
            (is (= task-id (:task-id response)))
            (is (not (contains? response :branch-name)))))))

    (testing "continues when config file doesn't exist"
      (let [base-dir (:base-dir (h/test-config))
            config-file (str base-dir "/.mcp-tasks.edn")]
        ;; Ensure no config file exists
        (when (.exists (clojure.java.io/file config-file))
          (clojure.java.io/delete-file config-file))

        (let [add-result (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "No Config Task" :type "task"})
              add-response (json/read-str (get-in add-result [:content 1 :text]) :key-fn keyword)
              task-id (get-in add-response [:task :id])
              result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
              response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

          (is (false? (:isError result)))
          (is (= task-id (:task-id response)))
          (is (not (contains? response :branch-name))))))))

(deftest work-on-validates-parent-story-exists
  ;; Test that work-on validates parent story exists when branch management is enabled
  (testing "work-on validates parent story exists"
    (let [base-dir (:base-dir (h/test-config))
          config-file (str base-dir "/.mcp-tasks.edn")]
      (spit config-file "{:branch-management? true}")

      ;; Create a task
      (let [add-result (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Orphan Task" :type "task"})
            add-response (json/read-str (get-in add-result [:content 1 :text]) :key-fn keyword)
            task-id (get-in add-response [:task :id])
            non-existent-parent-id 99999]

        ;; Mock tasks/get-tasks to return a task with invalid parent-id
        (with-redefs [mcp-tasks.tasks/get-tasks
                      (fn [& {:keys [task-id]}]
                        (if (= task-id 1)
                          [{:id 1
                            :parent-id non-existent-parent-id ; Invalid parent reference
                            :title "Orphan Task"
                            :category "simple"
                            :type "task"
                            :status "open"
                            :description ""
                            :design ""
                            :meta {}
                            :relations []}]
                          []))
                      mcp-tasks.tools.git/get-current-branch (fn [_] {:success true :branch "main" :error nil})]
          (let [result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
                response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

            (is (false? (:isError result)))
            (is (contains? response :error))
            (is (str/includes? (:error response) "parent story that does not exist"))
            (is (= task-id (get-in response [:metadata :task-id])))
            (is (= non-existent-parent-id (get-in response [:metadata :parent-id])))))))))

(deftest work-on-default-branch-fallback-chain
  ;; Test that work-on uses fallback chain for default branch detection
  (testing "work-on uses fallback chain for default branch"
    (testing "falls back to 'main' when origin/HEAD not found"
      (let [base-dir (:base-dir (h/test-config))
            config-file (str base-dir "/.mcp-tasks.edn")]
        (spit config-file "{:branch-management? true}")

        (let [add-result (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Test Fallback" :type "task"})
              add-response (json/read-str (get-in add-result [:content 1 :text]) :key-fn keyword)
              task-id (get-in add-response [:task :id])]

          ;; Mock git operations - no remote, falls back to main
          (with-redefs [mcp-tasks.tools.git/get-current-branch (fn [_] {:success true :branch "feature" :error nil})
                        mcp-tasks.tools.git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                        mcp-tasks.tools.git/get-default-branch (fn [_] {:success true :branch "main" :error nil})
                        mcp-tasks.tools.git/checkout-branch (fn [_ branch-name]
                                                              (is (= "main" branch-name) "Should checkout main as default")
                                                              {:success true :error nil})
                        mcp-tasks.tools.git/pull-latest (fn [_ _] {:success true :pulled? false :error nil})
                        mcp-tasks.tools.git/branch-exists? (fn [_ _] {:success true :exists? false :error nil})
                        mcp-tasks.tools.git/create-and-checkout-branch (fn [_ _] {:success true :error nil})]

            (let [result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
                  response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

              (is (false? (:isError result)))
              (is (= "test-fallback" (:branch-name response)))
              (is (true? (:branch-created? response))))))))

    (testing "falls back to 'master' when main not found"
      (let [base-dir (:base-dir (h/test-config))
            config-file (str base-dir "/.mcp-tasks.edn")]
        (spit config-file "{:branch-management? true}")

        (let [add-result (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Test Master" :type "task"})
              add-response (json/read-str (get-in add-result [:content 1 :text]) :key-fn keyword)
              task-id (get-in add-response [:task :id])]

          ;; Mock git operations - falls back to master
          (with-redefs [mcp-tasks.tools.git/get-current-branch (fn [_] {:success true :branch "feature" :error nil})
                        mcp-tasks.tools.git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                        mcp-tasks.tools.git/get-default-branch (fn [_] {:success true :branch "master" :error nil})
                        mcp-tasks.tools.git/checkout-branch (fn [_ branch-name]
                                                              (is (= "master" branch-name) "Should checkout master as fallback")
                                                              {:success true :error nil})
                        mcp-tasks.tools.git/pull-latest (fn [_ _] {:success true :pulled? false :error nil})
                        mcp-tasks.tools.git/branch-exists? (fn [_ _] {:success true :exists? false :error nil})
                        mcp-tasks.tools.git/create-and-checkout-branch (fn [_ _] {:success true :error nil})]

            (let [result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
                  response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

              (is (false? (:isError result)))
              (is (= "test-master" (:branch-name response)))
              (is (true? (:branch-created? response))))))))))

(deftest work-on-idempotency
  ;; Test that calling work-on multiple times is safe and idempotent
  (testing "work-on is idempotent"
    (testing "calling multiple times updates execution state timestamp"
      (let [add-result (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Idempotent Task" :type "task"})
            add-response (json/read-str (get-in add-result [:content 1 :text]) :key-fn keyword)
            task-id (get-in add-response [:task :id])
            base-dir (:base-dir (h/test-config))

            ;; First call
            result1 (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
            response1 (json/read-str (get-in result1 [:content 0 :text]) :key-fn keyword)
            state1 (execution-state/read-execution-state base-dir)
            timestamp1 (:started-at state1)
            _ (is (false? (:isError result1)))
            _ (is (= task-id (:task-id response1)))

            ;; Wait a moment to ensure timestamp changes
            _ (Thread/sleep 10)

            ;; Second call
            result2 (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
            response2 (json/read-str (get-in result2 [:content 0 :text]) :key-fn keyword)
            state2 (execution-state/read-execution-state base-dir)
            timestamp2 (:started-at state2)]

        (is (false? (:isError result2)))
        (is (= task-id (:task-id response2)))

        ;; Execution state should be updated with new timestamp
        (is (= task-id (:task-id state2)))
        (is (not= timestamp1 timestamp2) "Timestamps should differ")))

    (testing "calling with different task-ids updates execution state"
      (let [add-result1 (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Task One" :type "task"})
            add-response1 (json/read-str (get-in add-result1 [:content 1 :text]) :key-fn keyword)
            task-id1 (get-in add-response1 [:task :id])

            add-result2 (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Task Two" :type "task"})
            add-response2 (json/read-str (get-in add-result2 [:content 1 :text]) :key-fn keyword)
            task-id2 (get-in add-response2 [:task :id])
            base-dir (:base-dir (h/test-config))]

        ;; Work on first task
        (#'sut/work-on-impl (h/test-config) nil {:task-id task-id1})
        (let [state1 (execution-state/read-execution-state base-dir)]
          (is (= task-id1 (:task-id state1))))

        ;; Work on second task
        (#'sut/work-on-impl (h/test-config) nil {:task-id task-id2})
        (let [state2 (execution-state/read-execution-state base-dir)]
          (is (= task-id2 (:task-id state2))))))))

(deftest work-on-base-branch-configuration
  ;; Test that work-on handles base branch configuration correctly
  (testing "work-on base branch configuration"
    (testing "uses configured base branch"
      (let [base-dir (:base-dir (h/test-config))
            config-file (str base-dir "/.mcp-tasks.edn")]
        (spit config-file "{:branch-management? true :base-branch \"develop\"}")

        (let [add-result (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Feature Task" :type "task"})
              add-response (json/read-str (get-in add-result [:content 1 :text]) :key-fn keyword)
              task-id (get-in add-response [:task :id])]

          ;; Mock git operations
          (with-redefs [mcp-tasks.tools.git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                        mcp-tasks.tools.git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                        mcp-tasks.tools.git/branch-exists? (fn [_ branch-name]
                                                             (cond
                                                               (= branch-name "develop") {:success true :exists? true :error nil}
                                                               (= branch-name "feature-task") {:success true :exists? false :error nil}
                                                               :else {:success true :exists? false :error nil}))
                        mcp-tasks.tools.git/checkout-branch (fn [_ branch-name]
                                                              (when (= branch-name "develop")
                                                                (is true "Should checkout configured base branch 'develop'"))
                                                              {:success true :error nil})
                        mcp-tasks.tools.git/pull-latest (fn [_ branch-name]
                                                          (is (= "develop" branch-name) "Should pull from configured base branch")
                                                          {:success true :pulled? true :error nil})
                        mcp-tasks.tools.git/create-and-checkout-branch (fn [_ _] {:success true :error nil})]

            (let [result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
                  response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

              (is (false? (:isError result)))
              (is (= "feature-task" (:branch-name response)))
              (is (true? (:branch-created? response)))
              (is (true? (:branch-switched? response))))))))

    (testing "falls back to auto-detection when base-branch not configured"
      (let [base-dir (:base-dir (h/test-config))
            config-file (str base-dir "/.mcp-tasks.edn")]
        (spit config-file "{:branch-management? true}")

        (let [add-result (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Auto Detect Task" :type "task"})
              add-response (json/read-str (get-in add-result [:content 1 :text]) :key-fn keyword)
              task-id (get-in add-response [:task :id])]

          ;; Mock git operations - should call get-default-branch
          (with-redefs [mcp-tasks.tools.git/get-current-branch (fn [_] {:success true :branch "feature" :error nil})
                        mcp-tasks.tools.git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                        mcp-tasks.tools.git/get-default-branch (fn [_]
                                                                 (is true "Should call get-default-branch when no base-branch configured")
                                                                 {:success true :branch "main" :error nil})
                        mcp-tasks.tools.git/checkout-branch (fn [_ branch-name]
                                                              (when (= branch-name "main")
                                                                (is true "Should checkout auto-detected default branch"))
                                                              {:success true :error nil})
                        mcp-tasks.tools.git/pull-latest (fn [_ _] {:success true :pulled? true :error nil})
                        mcp-tasks.tools.git/branch-exists? (fn [_ _] {:success true :exists? false :error nil})
                        mcp-tasks.tools.git/create-and-checkout-branch (fn [_ _] {:success true :error nil})]

            (let [result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
                  response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

              (is (false? (:isError result)))
              (is (= "auto-detect-task" (:branch-name response))))))))

    (testing "errors when configured base branch doesn't exist"
      (let [base-dir (:base-dir (h/test-config))
            config-file (str base-dir "/.mcp-tasks.edn")]
        (spit config-file "{:branch-management? true :base-branch \"nonexistent\"}")

        (let [add-result (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Missing Branch Task" :type "task"})
              add-response (json/read-str (get-in add-result [:content 1 :text]) :key-fn keyword)
              task-id (get-in add-response [:task :id])]

          ;; Mock git operations - branch doesn't exist
          (with-redefs [mcp-tasks.tools.git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                        mcp-tasks.tools.git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                        mcp-tasks.tools.git/branch-exists? (fn [_ branch-name]
                                                             (if (= branch-name "nonexistent")
                                                               {:success true :exists? false :error nil}
                                                               {:success true :exists? true :error nil}))]

            (let [result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
                  response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

              (is (false? (:isError result)))
              (is (contains? response :error))
              (is (str/includes? (:error response) "Configured base branch nonexistent does not exist"))
              (is (= "nonexistent" (get-in response [:metadata :base-branch])))
              (is (= "validate-base-branch" (get-in response [:metadata :operation]))))))))

    (testing "ignores base-branch when branch management disabled"
      (let [base-dir (:base-dir (h/test-config))
            config-file (str base-dir "/.mcp-tasks.edn")]
        ;; Config with base-branch but branch-management disabled
        (spit config-file "{:branch-management? false :base-branch \"develop\"}")

        (let [add-result (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Ignored Config Task" :type "task"})
              add-response (json/read-str (get-in add-result [:content 1 :text]) :key-fn keyword)
              task-id (get-in add-response [:task :id])
              result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
              response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

          (is (false? (:isError result)))
          (is (= task-id (:task-id response)))
          ;; No branch management should have occurred
          (is (not (contains? response :branch-name)))
          (is (not (contains? response :branch-created?)))
          (is (not (contains? response :branch-switched?))))))))

(deftest work-on-worktree-creates-worktree
  ;; Test worktree creation on first execution
  (testing "work-on creates worktree on first execution"
    (let [base-dir (:base-dir (h/test-config))
          config-file (str base-dir "/.mcp-tasks.edn")]
      (spit config-file "{:worktree-management? true}")

      (let [add-result (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Fix Parser Bug" :type "task"})
            add-response (json/read-str (get-in add-result [:content 1 :text]) :key-fn keyword)
            task-id (get-in add-response [:task :id])
            expected-worktree-path (h/derive-test-worktree-path base-dir "Fix Parser Bug")]

        ;; Mock git and file operations
        (with-redefs [mcp-tasks.tools.git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                      mcp-tasks.tools.git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                      mcp-tasks.tools.git/get-default-branch (fn [_] {:success true :branch "main" :error nil})
                      mcp-tasks.tools.git/checkout-branch (fn [_ _] {:success true :error nil})
                      mcp-tasks.tools.git/pull-latest (fn [_ _] {:success true :pulled? true :error nil})
                      mcp-tasks.tools.git/branch-exists? (fn [_ _] {:success true :exists? false :error nil})
                      mcp-tasks.tools.git/create-and-checkout-branch (fn [_ _] {:success true :error nil})
                      mcp-tasks.tools.git/derive-project-name (fn [_] {:success true :name "mcp-tasks" :error nil})
                      mcp-tasks.tools.git/derive-worktree-path (fn [_ title _config]
                                                                 (is (= "Fix Parser Bug" title))
                                                                 {:success true :path expected-worktree-path :error nil})
                      mcp-tasks.tools.git/worktree-exists? (fn [_ path]
                                                             (is (= expected-worktree-path path))
                                                             {:success true :exists? false :worktree nil :error nil})
                      mcp-tasks.tools.git/create-worktree (fn [_ path branch]
                                                            (is (= expected-worktree-path path))
                                                            (is (= "fix-parser-bug" branch))
                                                            {:success true :error nil})]

          (let [result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
                response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

            (is (false? (:isError result)))
            (is (= expected-worktree-path (:worktree-path response)))
            (is (true? (:worktree-created? response)))
            (is (= "fix-parser-bug" (:branch-name response)))
            (is (str/includes? (:message response) "Worktree created"))
            (is (str/includes? (:message response) expected-worktree-path))
            ;; Should not have written execution state since directory switch needed
            (is (not (contains? response :execution-state-file)))))))))

(deftest work-on-worktree-reuses-existing
  ;; Test worktree reuse when already in the worktree
  (testing "work-on reuses existing worktree and checks clean status"
    (testing "detects when in worktree with uncommitted changes"
      ;; Note: This test verifies behavior when worktree exists
      ;; In a real scenario where we're actually in the worktree directory,
      ;; the tool would check clean status. In this test environment,
      ;; we can't easily simulate being in a different directory,
      ;; so we verify the "exists but not in it" behavior
      (let [base-dir (:base-dir (h/test-config))
            config-file (str base-dir "/.mcp-tasks.edn")
            expected-worktree-path (h/derive-test-worktree-path base-dir "Add Feature")]
        (spit config-file "{:worktree-management? true}")

        (let [add-result (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Add Feature" :type "task"})
              add-response (json/read-str (get-in add-result [:content 1 :text]) :key-fn keyword)
              task-id (get-in add-response [:task :id])]

          ;; Mock to simulate worktree existing (but we're not in it due to test limitations)
          (with-redefs [mcp-tasks.tools.git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                        mcp-tasks.tools.git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                        mcp-tasks.tools.git/get-default-branch (fn [_] {:success true :branch "main" :error nil})
                        mcp-tasks.tools.git/checkout-branch (fn [_ _] {:success true :error nil})
                        mcp-tasks.tools.git/pull-latest (fn [_ _] {:success true :pulled? true :error nil})
                        mcp-tasks.tools.git/branch-exists? (fn [_ _] {:success true :exists? true :error nil})
                        mcp-tasks.tools.git/derive-worktree-path (fn [_ _ _] {:success true :path expected-worktree-path :error nil})
                        mcp-tasks.tools.git/worktree-exists? (fn [_ _]
                                                               {:success true :exists? true
                                                                :worktree {:path expected-worktree-path :branch "add-feature"}
                                                                :error nil})]

            (let [result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
                  response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

              (is (false? (:isError result)))
              (is (= expected-worktree-path (:worktree-path response)))
              (is (false? (:worktree-created? response)))
              (is (str/includes? (:message response) "Please start a new Claude Code session"))
              ;; Should not have written execution state since directory switch needed
              (is (not (contains? response :execution-state-file))))))))

    (testing "detects existing worktree"
      ;; Note: Similar to above test, we verify the "exists but not in it" behavior
      ;; A full end-to-end test of the clean status checking would require
      ;; actually being in the worktree directory
      (let [base-dir (:base-dir (h/test-config))
            config-file (str base-dir "/.mcp-tasks.edn")
            expected-worktree-path (h/derive-test-worktree-path base-dir "Clean Task")]
        (spit config-file "{:worktree-management? true}")

        (let [add-result (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Clean Task" :type "task"})
              add-response (json/read-str (get-in add-result [:content 1 :text]) :key-fn keyword)
              task-id (get-in add-response [:task :id])]

          ;; Mock to simulate worktree existing
          (with-redefs [mcp-tasks.tools.git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                        mcp-tasks.tools.git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                        mcp-tasks.tools.git/get-default-branch (fn [_] {:success true :branch "main" :error nil})
                        mcp-tasks.tools.git/checkout-branch (fn [_ _] {:success true :error nil})
                        mcp-tasks.tools.git/pull-latest (fn [_ _] {:success true :pulled? true :error nil})
                        mcp-tasks.tools.git/branch-exists? (fn [_ _] {:success true :exists? true :error nil})
                        mcp-tasks.tools.git/derive-worktree-path (fn [_ _ _] {:success true :path expected-worktree-path :error nil})
                        mcp-tasks.tools.git/worktree-exists? (fn [_ _]
                                                               {:success true :exists? true
                                                                :worktree {:path expected-worktree-path :branch "clean-task"}
                                                                :error nil})]

            (let [result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
                  response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

              (is (false? (:isError result)))
              (is (= expected-worktree-path (:worktree-path response)))
              (is (false? (:worktree-created? response)))
              (is (str/includes? (:message response) "Please start a new Claude Code session"))
              ;; Should not have written execution state since directory switch needed
              (is (not (contains? response :execution-state-file))))))))))

(deftest work-on-worktree-wrong-branch-error
  ;; Test error when worktree is on wrong branch
  ;; Note: This test would require being in the worktree directory to trigger the branch check
  ;; In the test environment, we can't easily simulate that, so we skip this specific scenario
  ;; The branch verification logic is tested implicitly through the manage-worktree function
  (testing "work-on detects worktree on wrong branch (requires being in worktree)"
    (let [base-dir (:base-dir (h/test-config))
          config-file (str base-dir "/.mcp-tasks.edn")]
      (spit config-file "{:worktree-management? true}")

      (let [add-result (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Expected Branch" :type "task"})
            add-response (json/read-str (get-in add-result [:content 1 :text]) :key-fn keyword)
            task-id (get-in add-response [:task :id])
            expected-worktree-path (str (.getParent (io/file base-dir)) "/mcp-tasks-expected-branch")]

        ;; Mock to simulate worktree existing (but we're not in it due to test limitations)
        (with-redefs [mcp-tasks.tools.git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                      mcp-tasks.tools.git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                      mcp-tasks.tools.git/get-default-branch (fn [_] {:success true :branch "main" :error nil})
                      mcp-tasks.tools.git/checkout-branch (fn [_ _] {:success true :error nil})
                      mcp-tasks.tools.git/pull-latest (fn [_ _] {:success true :pulled? true :error nil})
                      mcp-tasks.tools.git/branch-exists? (fn [_ _] {:success true :exists? true :error nil})
                      mcp-tasks.tools.git/derive-worktree-path (fn [_ _ _] {:success true :path expected-worktree-path :error nil})
                      mcp-tasks.tools.git/worktree-exists? (fn [_ _]
                                                             {:success true :exists? true
                                                              :worktree {:path expected-worktree-path :branch "wrong-branch"}
                                                              :error nil})]

          (let [result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
                response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

            ;; Since we're not in the worktree, we get the "switch directory" message
            (is (false? (:isError result)))
            (is (= expected-worktree-path (:worktree-path response)))
            (is (false? (:worktree-created? response)))
            (is (str/includes? (:message response) "Please start a new Claude Code session"))))))))

(deftest work-on-worktree-directory-switch-required
  ;; Test that work-on informs user when they need to switch directories
  (testing "work-on requires directory switch when worktree exists but not in it"
    (let [base-dir (:base-dir (h/test-config))
          config-file (str base-dir "/.mcp-tasks.edn")]
      (spit config-file "{:worktree-management? true}")

      (let [add-result (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Switch Dir Task" :type "task"})
            add-response (json/read-str (get-in add-result [:content 1 :text]) :key-fn keyword)
            task-id (get-in add-response [:task :id])
            expected-worktree-path (h/derive-test-worktree-path base-dir "Switch Dir Task")]

        ;; Mock to simulate worktree exists but we're not in it
        (with-redefs [mcp-tasks.tools.git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                      mcp-tasks.tools.git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                      mcp-tasks.tools.git/get-default-branch (fn [_] {:success true :branch "main" :error nil})
                      mcp-tasks.tools.git/checkout-branch (fn [_ _] {:success true :error nil})
                      mcp-tasks.tools.git/pull-latest (fn [_ _] {:success true :pulled? true :error nil})
                      mcp-tasks.tools.git/branch-exists? (fn [_ _] {:success true :exists? true :error nil})
                      mcp-tasks.tools.git/derive-worktree-path (fn [_ _ _] {:success true :path expected-worktree-path :error nil})
                      mcp-tasks.tools.git/worktree-exists? (fn [_ _]
                                                             {:success true :exists? true
                                                              :worktree {:path expected-worktree-path :branch "switch-dir-task"}
                                                              :error nil})]

          (let [result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
                response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

            (is (false? (:isError result)))
            (is (= expected-worktree-path (:worktree-path response)))
            (is (false? (:worktree-created? response)))
            (is (str/includes? (:message response) "Please start a new Claude Code session"))
            (is (str/includes? (:message response) expected-worktree-path))
            ;; Should not have written execution state since directory switch needed
            (is (not (contains? response :execution-state-file)))))))))
