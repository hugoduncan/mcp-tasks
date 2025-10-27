(ns mcp-tasks.tool.work-on-branch-test
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.config :as config]
    [mcp-tasks.tasks :as tasks]
    [mcp-tasks.test-helpers :as h]
    [mcp-tasks.tool.add-task :as add-task]
    [mcp-tasks.tool.work-on :as sut]
    [mcp-tasks.tools.git :as git]))

(deftest work-on-branch-management-disabled-by-default
  (h/with-test-setup [test-dir]
    ;; Test that branch management is disabled when no config exists
    (testing "work-on doesn't manage branches when branch-management? is not configured"
      (let [add-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Test Task" :type "task"})
            add-response (json/parse-string (get-in add-result [:content 1 :text]) keyword)
            task-id (get-in add-response [:task :id])

            result (#'sut/work-on-impl (h/test-config test-dir) nil {:task-id task-id})
            response (json/parse-string (get-in result [:content 0 :text]) keyword)]

        (is (false? (:isError result)))
        (is (= task-id (:task-id response)))
        ;; No branch info should be present
        (is (not (contains? response :branch-name)))
        (is (not (contains? response :branch-created?)))
        (is (not (contains? response :branch-switched?)))))))

(deftest work-on-branch-management-for-story-tasks
  (h/with-test-setup [test-dir]
    ;; Test branch management for tasks with parent-id (story tasks)
    (testing "work-on manages branches for story tasks when enabled"
      (testing "creates new branch from story title when not on correct branch"
        ;; Create .mcp-tasks.edn with branch-management enabled
        (let [base-dir (:base-dir (h/test-config test-dir))
              config-file (str base-dir "/.mcp-tasks.edn")]
          (spit config-file "{:branch-management? true}")

          ;; Create a story
          (let [story-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "story" :title "Add New Feature" :type "story"})
                story-response (json/parse-string (get-in story-result [:content 1 :text]) keyword)
                story-id (get-in story-response [:task :id])

                ;; Create a task with parent-id
                task-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Implement Component" :type "task" :parent-id story-id})
                task-response (json/parse-string (get-in task-result [:content 1 :text]) keyword)
                task-id (get-in task-response [:task :id])]

            ;; Mock git operations
            (with-redefs [git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                          git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                          git/get-default-branch (fn [_] {:success true :branch "main" :error nil})
                          git/checkout-branch (fn [_ _] {:success true :error nil})
                          git/pull-latest (fn [_ _] {:success true :pulled? true :error nil})
                          git/branch-exists? (fn [_ _] {:success true :exists? false :error nil})
                          git/create-and-checkout-branch (fn [_ branch-name]
                                                           (is (= (str story-id "-add-new-feature") branch-name))
                                                           {:success true :error nil})]

              (let [result (#'sut/work-on-impl (assoc (h/test-config test-dir) :branch-management? true) nil {:task-id task-id})
                    response (json/parse-string (get-in result [:content 0 :text]) keyword)]

                (is (false? (:isError result)))
                (is (= (str story-id "-add-new-feature") (:branch-name response)))
                (is (true? (:branch-created? response)))
                (is (true? (:branch-switched? response))))))))

      (testing "checks out existing branch when it already exists"
        (let [base-dir (:base-dir (h/test-config test-dir))
              config-file (str base-dir "/.mcp-tasks.edn")]
          (spit config-file "{:branch-management? true}")

          ;; Create a story
          (let [story-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "story" :title "Fix Bug" :type "story"})
                story-response (json/parse-string (get-in story-result [:content 1 :text]) keyword)
                story-id (get-in story-response [:task :id])

                task-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Fix Issue" :type "task" :parent-id story-id})
                task-response (json/parse-string (get-in task-result [:content 1 :text]) keyword)
                task-id (get-in task-response [:task :id])]

            ;; Mock git operations - branch exists
            (with-redefs [git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                          git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                          git/get-default-branch (fn [_] {:success true :branch "main" :error nil})
                          git/checkout-branch (fn [_ branch-name]
                                                (when (= branch-name (str story-id "-fix-bug"))
                                                  (is true "Checking out existing branch"))
                                                {:success true :error nil})
                          git/pull-latest (fn [_ _] {:success true :pulled? true :error nil})
                          git/branch-exists? (fn [_ branch-name]
                                               (is (= (str story-id "-fix-bug") branch-name))
                                               {:success true :exists? true :error nil})]

              (let [result (#'sut/work-on-impl (assoc (h/test-config test-dir) :branch-management? true) nil {:task-id task-id})
                    response (json/parse-string (get-in result [:content 0 :text]) keyword)]

                (is (false? (:isError result)))
                (is (= (str story-id "-fix-bug") (:branch-name response)))
                (is (false? (:branch-created? response)))
                (is (true? (:branch-switched? response))))))))

      (testing "does not switch when already on correct branch"
        (let [base-dir (:base-dir (h/test-config test-dir))
              config-file (str base-dir "/.mcp-tasks.edn")]
          (spit config-file "{:branch-management? true}")

          (let [story-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "story" :title "Optimize Performance" :type "story"})
                story-response (json/parse-string (get-in story-result [:content 1 :text]) keyword)
                story-id (get-in story-response [:task :id])

                task-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Optimize Query" :type "task" :parent-id story-id})
                task-response (json/parse-string (get-in task-result [:content 1 :text]) keyword)
                task-id (get-in task-response [:task :id])]

            ;; Mock git operations - already on correct branch
            (with-redefs [git/get-current-branch (fn [_] {:success true :branch (str story-id "-optimize-performance") :error nil})]

              (let [result (#'sut/work-on-impl (assoc (h/test-config test-dir) :branch-management? true) nil {:task-id task-id})
                    response (json/parse-string (get-in result [:content 0 :text]) keyword)]

                (is (false? (:isError result)))
                (is (= (str story-id "-optimize-performance") (:branch-name response)))
                (is (false? (:branch-created? response)))
                (is (false? (:branch-switched? response)))))))))))

(deftest work-on-branch-management-for-standalone-tasks
  (h/with-test-setup [test-dir]
    ;; Test branch management for tasks without parent-id (standalone tasks)
    (testing "work-on manages branches for standalone tasks when enabled"
      (testing "uses task title for branch name"
        (let [base-dir (:base-dir (h/test-config test-dir))
              config-file (str base-dir "/.mcp-tasks.edn")]
          (spit config-file "{:branch-management? true}")

          (let [add-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Update Documentation" :type "task"})
                add-response (json/parse-string (get-in add-result [:content 1 :text]) keyword)
                task-id (get-in add-response [:task :id])]

            ;; Mock git operations
            (with-redefs [git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                          git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                          git/get-default-branch (fn [_] {:success true :branch "main" :error nil})
                          git/checkout-branch (fn [_ _] {:success true :error nil})
                          git/pull-latest (fn [_ _] {:success true :pulled? true :error nil})
                          git/branch-exists? (fn [_ _] {:success true :exists? false :error nil})
                          git/create-and-checkout-branch (fn [_ branch-name]
                                                           (is (= (str task-id "-update-documentation") branch-name))
                                                           {:success true :error nil})]

              (let [result (#'sut/work-on-impl (assoc (h/test-config test-dir) :branch-management? true) nil {:task-id task-id})
                    response (json/parse-string (get-in result [:content 0 :text]) keyword)]

                (is (false? (:isError result)))
                (is (= (str task-id "-update-documentation") (:branch-name response)))
                (is (true? (:branch-created? response)))
                (is (true? (:branch-switched? response)))))))))))

(deftest work-on-error-handling
  (h/with-test-setup [test-dir]
    ;; Test error handling for branch management and configuration operations
    (testing "work-on error handling"
      (testing "returns error when uncommitted changes detected"
        (let [base-dir (:base-dir (h/test-config test-dir))
              config-file (str base-dir "/.mcp-tasks.edn")]
          (spit config-file "{:branch-management? true}")

          (let [add-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Test Task" :type "task"})
                add-response (json/parse-string (get-in add-result [:content 1 :text]) keyword)
                task-id (get-in add-response [:task :id])]

            ;; Mock git operations - uncommitted changes
            (with-redefs [git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                          git/check-uncommitted-changes (fn [_] {:success true :has-changes? true :error nil})]

              (let [result (#'sut/work-on-impl (assoc (h/test-config test-dir) :branch-management? true) nil {:task-id task-id})
                    response (json/parse-string (get-in result [:content 0 :text]) keyword)]

                (is (false? (:isError result)))
                (is (contains? response :error))
                (is (str/includes? (:error response) "uncommitted changes"))
                (is (= "main" (get-in response [:metadata :current-branch])))
                (is (= (str task-id "-test-task") (get-in response [:metadata :target-branch]))))))))

      (testing "handles local-only repo gracefully"
        (let [base-dir (:base-dir (h/test-config test-dir))
              config-file (str base-dir "/.mcp-tasks.edn")]
          (spit config-file "{:branch-management? true}")

          (let [add-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Local Task" :type "task"})
                add-response (json/parse-string (get-in add-result [:content 1 :text]) keyword)
                task-id (get-in add-response [:task :id])]

            ;; Mock git operations - local-only repo
            (with-redefs [git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                          git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                          git/get-default-branch (fn [_] {:success true :branch "main" :error nil})
                          git/checkout-branch (fn [_ _] {:success true :error nil})
                          git/pull-latest (fn [_ _] {:success true :pulled? false :error nil})
                          git/branch-exists? (fn [_ _] {:success true :exists? false :error nil})
                          git/create-and-checkout-branch (fn [_ _] {:success true :error nil})]

              (let [result (#'sut/work-on-impl (assoc (h/test-config test-dir) :branch-management? true) nil {:task-id task-id})
                    response (json/parse-string (get-in result [:content 0 :text]) keyword)]

                ;; Should succeed despite failed pull
                (is (false? (:isError result)))
                (is (= (str task-id "-local-task") (:branch-name response)))
                (is (true? (:branch-created? response)))
                (is (true? (:branch-switched? response))))))))

      (testing "returns error when git operation fails"
        (let [base-dir (:base-dir (h/test-config test-dir))
              config-file (str base-dir "/.mcp-tasks.edn")]
          (spit config-file "{:branch-management? true}")

          (let [add-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Fail Task" :type "task"})
                add-response (json/parse-string (get-in add-result [:content 1 :text]) keyword)
                task-id (get-in add-response [:task :id])]

            ;; Mock git operations - checkout fails
            (with-redefs [git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                          git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                          git/get-default-branch (fn [_] {:success true :branch "main" :error nil})
                          git/checkout-branch (fn [_ _] {:success false :error "Failed to checkout branch"})
                          git/pull-latest (fn [_ _] {:success true :pulled? true :error nil})]

              (let [result (#'sut/work-on-impl (assoc (h/test-config test-dir) :branch-management? true) nil {:task-id task-id})
                    response (json/parse-string (get-in result [:content 0 :text]) keyword)]

                (is (false? (:isError result)))
                (is (contains? response :error))
                (is (str/includes? (:error response) "Failed to checkout"))
                (is (contains? (:metadata response) :operation)))))))

      (testing "continues without branch management when config is invalid"
        (let [add-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Test Config Error" :type "task"})
              add-response (json/parse-string (get-in add-result [:content 1 :text]) keyword)
              task-id (get-in add-response [:task :id])]

          ;; Mock config/read-config to simulate missing config - returns empty raw-config
          (with-redefs [mcp-tasks.config/read-config (fn [_] {:raw-config {} :config-dir test-dir})]
            (let [result (#'sut/work-on-impl (h/test-config test-dir) nil {:task-id task-id})
                  response (json/parse-string (get-in result [:content 0 :text]) keyword)]

              ;; Should succeed, but without branch management
              (is (false? (:isError result)))
              (is (= task-id (:task-id response)))
              (is (not (contains? response :branch-name)))))))

      (testing "continues when config file doesn't exist"
        (let [base-dir (:base-dir (h/test-config test-dir))
              config-file (str base-dir "/.mcp-tasks.edn")]
          ;; Ensure no config file exists
          (when (.exists (clojure.java.io/file config-file))
            (clojure.java.io/delete-file config-file))

          (let [add-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "No Config Task" :type "task"})
                add-response (json/parse-string (get-in add-result [:content 1 :text]) keyword)
                task-id (get-in add-response [:task :id])
                result (#'sut/work-on-impl (h/test-config test-dir) nil {:task-id task-id})
                response (json/parse-string (get-in result [:content 0 :text]) keyword)]

            (is (false? (:isError result)))
            (is (= task-id (:task-id response)))
            (is (not (contains? response :branch-name)))))))))

(deftest work-on-validates-parent-story-exists
  (h/with-test-setup [test-dir]
    ;; Test that work-on validates parent story exists when branch management is enabled
    (testing "work-on validates parent story exists"
      (let [base-dir (:base-dir (h/test-config test-dir))
            config-file (str base-dir "/.mcp-tasks.edn")]
        (spit config-file "{:branch-management? true}")

        ;; Create a task
        (let [add-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Orphan Task" :type "task"})
              add-response (json/parse-string (get-in add-result [:content 1 :text]) keyword)
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
                        git/get-current-branch (fn [_] {:success true :branch "main" :error nil})]
            (let [result (#'sut/work-on-impl (assoc (h/test-config test-dir) :branch-management? true) nil {:task-id task-id})
                  response (json/parse-string (get-in result [:content 0 :text]) keyword)]

              (is (false? (:isError result)))
              (is (contains? response :error))
              (is (str/includes? (:error response) "parent story that does not exist"))
              (is (= task-id (get-in response [:metadata :task-id])))
              (is (= non-existent-parent-id (get-in response [:metadata :parent-id]))))))))))

(deftest work-on-default-branch-fallback-chain
  (h/with-test-setup [test-dir]
    ;; Test that work-on uses fallback chain for default branch detection
    (testing "work-on uses fallback chain for default branch"
      (testing "falls back to 'main' when origin/HEAD not found"
        (let [base-dir (:base-dir (h/test-config test-dir))
              config-file (str base-dir "/.mcp-tasks.edn")]
          (spit config-file "{:branch-management? true}")

          (let [add-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Test Fallback" :type "task"})
                add-response (json/parse-string (get-in add-result [:content 1 :text]) keyword)
                task-id (get-in add-response [:task :id])]

            ;; Mock git operations - no remote, falls back to main
            (with-redefs [git/get-current-branch (fn [_] {:success true :branch "feature" :error nil})
                          git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                          git/get-default-branch (fn [_] {:success true :branch "main" :error nil})
                          git/checkout-branch (fn [_ branch-name]
                                                (is (= "main" branch-name) "Should checkout main as default")
                                                {:success true :error nil})
                          git/pull-latest (fn [_ _] {:success true :pulled? false :error nil})
                          git/branch-exists? (fn [_ _] {:success true :exists? false :error nil})
                          git/create-and-checkout-branch (fn [_ _] {:success true :error nil})]

              (let [result (#'sut/work-on-impl (assoc (h/test-config test-dir) :branch-management? true) nil {:task-id task-id})
                    response (json/parse-string (get-in result [:content 0 :text]) keyword)]

                (is (false? (:isError result)))
                (is (= (str task-id "-test-fallback") (:branch-name response)))
                (is (true? (:branch-created? response))))))))

      (testing "falls back to 'master' when main not found"
        (let [base-dir (:base-dir (h/test-config test-dir))
              config-file (str base-dir "/.mcp-tasks.edn")]
          (spit config-file "{:branch-management? true}")

          (let [add-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Test Master" :type "task"})
                add-response (json/parse-string (get-in add-result [:content 1 :text]) keyword)
                task-id (get-in add-response [:task :id])]

            ;; Mock git operations - falls back to master
            (with-redefs [git/get-current-branch (fn [_] {:success true :branch "feature" :error nil})
                          git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                          git/get-default-branch (fn [_] {:success true :branch "master" :error nil})
                          git/checkout-branch (fn [_ branch-name]
                                                (is (= "master" branch-name) "Should checkout master as fallback")
                                                {:success true :error nil})
                          git/pull-latest (fn [_ _] {:success true :pulled? false :error nil})
                          git/branch-exists? (fn [_ _] {:success true :exists? false :error nil})
                          git/create-and-checkout-branch (fn [_ _] {:success true :error nil})]

              (let [result (#'sut/work-on-impl (assoc (h/test-config test-dir) :branch-management? true) nil {:task-id task-id})
                    response (json/parse-string (get-in result [:content 0 :text]) keyword)]

                (is (false? (:isError result)))
                (is (= (str task-id "-test-master") (:branch-name response)))
                (is (true? (:branch-created? response)))))))))))

(deftest work-on-base-branch-configuration
  (h/with-test-setup [test-dir]
    ;; Test that work-on handles base branch configuration correctly
    (testing "work-on base branch configuration"
      (testing "uses configured base branch"
        (let [base-dir (:base-dir (h/test-config test-dir))
              config-file (str base-dir "/.mcp-tasks.edn")]
          (spit config-file "{:branch-management? true :base-branch \"develop\"}")

          (let [add-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Feature Task" :type "task"})
                add-response (json/parse-string (get-in add-result [:content 1 :text]) keyword)
                task-id (get-in add-response [:task :id])]

            ;; Mock git operations
            (with-redefs [git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                          git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                          git/branch-exists? (fn [_ branch-name]
                                               (cond
                                                 (= branch-name "develop") {:success true :exists? true :error nil}
                                                 (= branch-name (str task-id "-feature-task")) {:success true :exists? false :error nil}
                                                 :else {:success true :exists? false :error nil}))
                          git/checkout-branch (fn [_ branch-name]
                                                (when (= branch-name "develop")
                                                  (is true "Should checkout configured base branch 'develop'"))
                                                {:success true :error nil})
                          git/pull-latest (fn [_ branch-name]
                                            (is (= "develop" branch-name) "Should pull from configured base branch")
                                            {:success true :pulled? true :error nil})
                          git/create-and-checkout-branch (fn [_ _] {:success true :error nil})]

              (let [result (#'sut/work-on-impl (assoc (h/test-config test-dir) :branch-management? true :base-branch "develop") nil {:task-id task-id})
                    response (json/parse-string (get-in result [:content 0 :text]) keyword)]

                (is (false? (:isError result)))
                (is (= (str task-id "-feature-task") (:branch-name response)))
                (is (true? (:branch-created? response)))
                (is (true? (:branch-switched? response))))))))

      (testing "falls back to auto-detection when base-branch not configured"
        (let [base-dir (:base-dir (h/test-config test-dir))
              config-file (str base-dir "/.mcp-tasks.edn")]
          (spit config-file "{:branch-management? true}")

          (let [add-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Auto Detect Task" :type "task"})
                add-response (json/parse-string (get-in add-result [:content 1 :text]) keyword)
                task-id (get-in add-response [:task :id])]

            ;; Mock git operations - should call get-default-branch
            (with-redefs [git/get-current-branch (fn [_] {:success true :branch "feature" :error nil})
                          git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                          git/get-default-branch (fn [_]
                                                   (is true "Should call get-default-branch when no base-branch configured")
                                                   {:success true :branch "main" :error nil})
                          git/checkout-branch (fn [_ branch-name]
                                                (when (= branch-name "main")
                                                  (is true "Should checkout auto-detected default branch"))
                                                {:success true :error nil})
                          git/pull-latest (fn [_ _] {:success true :pulled? true :error nil})
                          git/branch-exists? (fn [_ _] {:success true :exists? false :error nil})
                          git/create-and-checkout-branch (fn [_ _] {:success true :error nil})]

              (let [result (#'sut/work-on-impl (assoc (h/test-config test-dir) :branch-management? true) nil {:task-id task-id})
                    response (json/parse-string (get-in result [:content 0 :text]) keyword)]

                (is (false? (:isError result)))
                (is (= (str task-id "-auto-detect-task") (:branch-name response))))))))

      (testing "errors when configured base branch doesn't exist"
        (let [base-dir (:base-dir (h/test-config test-dir))
              config-file (str base-dir "/.mcp-tasks.edn")]
          (spit config-file "{:branch-management? true :base-branch \"nonexistent\"}")

          (let [add-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Missing Branch Task" :type "task"})
                add-response (json/parse-string (get-in add-result [:content 1 :text]) keyword)
                task-id (get-in add-response [:task :id])]

            ;; Mock git operations - branch doesn't exist
            (with-redefs [git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                          git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                          git/branch-exists? (fn [_ branch-name]
                                               (if (= branch-name "nonexistent")
                                                 {:success true :exists? false :error nil}
                                                 {:success true :exists? true :error nil}))]

              (let [result (#'sut/work-on-impl (assoc (h/test-config test-dir) :branch-management? true :base-branch "nonexistent") nil {:task-id task-id})
                    response (json/parse-string (get-in result [:content 0 :text]) keyword)]

                (is (false? (:isError result)))
                (is (contains? response :error))
                (is (str/includes? (:error response) "Configured base branch nonexistent does not exist"))
                (is (= "nonexistent" (get-in response [:metadata :base-branch])))
                (is (= "validate-base-branch" (get-in response [:metadata :operation]))))))))

      (testing "ignores base-branch when branch management disabled"
        (let [base-dir (:base-dir (h/test-config test-dir))
              config-file (str base-dir "/.mcp-tasks.edn")]
          ;; Config with base-branch but branch-management disabled
          (spit config-file "{:branch-management? false :base-branch \"develop\"}")

          (let [add-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Ignored Config Task" :type "task"})
                add-response (json/parse-string (get-in add-result [:content 1 :text]) keyword)
                task-id (get-in add-response [:task :id])
                result (#'sut/work-on-impl (h/test-config test-dir) nil {:task-id task-id})
                response (json/parse-string (get-in result [:content 0 :text]) keyword)]

            (is (false? (:isError result)))
            (is (= task-id (:task-id response)))
            ;; No branch management should have occurred
            (is (not (contains? response :branch-name)))
            (is (not (contains? response :branch-created?)))
            (is (not (contains? response :branch-switched?)))))))))
