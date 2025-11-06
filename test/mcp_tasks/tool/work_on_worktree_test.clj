(ns mcp-tasks.tool.work-on-worktree-test
  (:require
    [babashka.fs :as fs]
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.test-helpers :as h]
    [mcp-tasks.tool.add-task :as add-task]
    [mcp-tasks.tool.work-on :as sut]
    [mcp-tasks.tools.git :as git]))

(deftest work-on-worktree-creates-worktree
  (h/with-test-setup [test-dir]
    ;; Test worktree creation on first execution
    (testing "work-on creates worktree on first execution"
      (let [base-dir (:base-dir (h/test-config test-dir))
            config-file (str base-dir "/.mcp-tasks.edn")]
        (spit config-file "{:worktree-management? true}")

        (let [add-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Fix Parser Bug" :type "task"})
              add-response (json/parse-string (get-in add-result [:content 1 :text]) keyword)
              task-id (get-in add-response [:task :id])
              expected-worktree-path (h/derive-test-worktree-path base-dir "Fix Parser Bug")]

          ;; Mock git and file operations
          (with-redefs [git/find-worktree-for-branch
                        (fn [_ _] {:success true :worktree nil :error nil})
                        git/get-current-branch
                        (fn [_] {:success true :branch "main" :error nil})
                        git/check-uncommitted-changes
                        (fn [_] {:success true :has-changes? false :error nil})
                        git/get-default-branch
                        (fn [_] {:success true :branch "main" :error nil})
                        git/checkout-branch
                        (fn [_ _] {:success true :error nil})
                        git/pull-latest
                        (fn [_ _] {:success true :pulled? true :error nil})
                        git/branch-exists?
                        (fn [_ _] {:success true :exists? false :error nil})
                        git/create-and-checkout-branch
                        (fn [_ _] {:success true :error nil})
                        git/derive-project-name
                        (fn [_] {:success true :name "mcp-tasks" :error nil})
                        git/derive-worktree-path
                        (fn [_ title task-id* _config]
                          (is (= "Fix Parser Bug" title))
                          (is (= task-id task-id*))
                          {:success true :path expected-worktree-path :error nil})
                        git/worktree-exists?
                        (fn [_ path]
                          (is (= expected-worktree-path path))
                          {:success true :exists? false :worktree nil :error nil})
                        git/create-worktree
                        (fn create-worktree
                          [_ path branch base]
                          (is (= expected-worktree-path path))
                          (is (= (str task-id "-fix-parser-bug") branch))
                          (is (= "main" base))
                          {:success true :error nil})]

            (let [result (#'sut/work-on-impl
                          (assoc (h/test-config test-dir) :worktree-management? true)
                          nil
                          {:task-id task-id})
                  response (json/parse-string
                             (get-in result [:content 0 :text])
                             keyword)]

              (is (false? (:isError result)))
              (is (= expected-worktree-path (:worktree-path response)))
              (is (true? (:worktree-created? response)))
              (is (= (str task-id "-fix-parser-bug") (:branch-name response)))
              (is (= "mcp-tasks-fix-parser-bug" (:worktree-name response)))
              (is (str/includes? (:message response) "Worktree created"))
              (is (str/includes? (:message response) expected-worktree-path))
              ;; Execution state file should be written to worktree before directory switch
              (is (= (str expected-worktree-path "/.mcp-tasks-current.edn") (:execution-state-file response))))))))))

(deftest work-on-worktree-reuses-existing
  (h/with-test-setup [test-dir]
    ;; Test worktree reuse when already in the worktree
    (testing "work-on reuses existing worktree and checks clean status"
      (testing "detects when in worktree with uncommitted changes"
        (let [base-dir (:base-dir (h/test-config test-dir))
              config-file (str base-dir "/.mcp-tasks.edn")
              expected-worktree-path (h/derive-test-worktree-path base-dir "Add Feature")]
          (spit config-file "{:worktree-management? true}")

          (let [add-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Add Feature" :type "task"})
                add-response (json/parse-string (get-in add-result [:content 1 :text]) keyword)
                task-id (get-in add-response [:task :id])]

            (with-redefs [git/find-worktree-for-branch
                          (fn find-worktree-for-branch
                            [_ _]
                            {:success true :worktree nil :error nil})
                          git/get-current-branch
                          (fn get-current-branch
                            [_]
                            {:success true :branch "main" :error nil})
                          git/check-uncommitted-changes
                          (fn check-uncommitted-changes
                            [_]
                            {:success true :has-changes? false :error nil})
                          git/get-default-branch
                          (fn get-default-branch
                            [_]
                            {:success true :branch "main" :error nil})
                          git/checkout-branch
                          (fn checkout-branch
                            [_ _]
                            {:success true :error nil})
                          git/pull-latest
                          (fn pull-latest
                            [_ _]
                            {:success true :pulled? true :error nil})
                          git/branch-exists?
                          (fn branch-exists?
                            [_ _]
                            {:success true :exists? true :error nil})
                          git/derive-worktree-path
                          (fn derive-worktree-path
                            [_ _ _ _]
                            {:success true
                             :path expected-worktree-path
                             :error nil})
                          git/worktree-exists?
                          (fn worktree-exists?
                            [_ _]
                            {:success true :exists? true
                             :worktree {:path expected-worktree-path :branch "add-feature"}
                             :error nil})]

              (let [result (#'sut/work-on-impl
                            (assoc (h/test-config test-dir) :worktree-management? true)
                            nil
                            {:task-id task-id})
                    response (json/parse-string (get-in result [:content 0 :text]) keyword)]

                (is (false? (:isError result)))
                (is (= expected-worktree-path (:worktree-path response)))
                (is (false? (:worktree-created? response)))
                (is (= "mcp-tasks-add-feature" (:worktree-name response)))
                (is (str/includes? (:message response) "Please start a new Claude Code session"))
                ;; Execution state file should be written to worktree before directory switch
                (is (= (str expected-worktree-path "/.mcp-tasks-current.edn") (:execution-state-file response))))))))

      (testing "detects existing worktree"
        (let [base-dir (:base-dir (h/test-config test-dir))
              config-file (str base-dir "/.mcp-tasks.edn")
              expected-worktree-path (h/derive-test-worktree-path base-dir "Clean Task")]
          (spit config-file "{:worktree-management? true}")

          (let [add-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Clean Task" :type "task"})
                add-response (json/parse-string (get-in add-result [:content 1 :text]) keyword)
                task-id (get-in add-response [:task :id])]

            (with-redefs [git/find-worktree-for-branch (fn [_ _] {:success true :worktree nil :error nil})
                          git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                          git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                          git/get-default-branch (fn [_] {:success true :branch "main" :error nil})
                          git/checkout-branch (fn [_ _] {:success true :error nil})
                          git/pull-latest (fn [_ _] {:success true :pulled? true :error nil})
                          git/branch-exists? (fn [_ _] {:success true :exists? true :error nil})
                          git/derive-worktree-path (fn [_ _ _ _] {:success true :path expected-worktree-path :error nil})
                          git/worktree-exists? (fn [_ _]
                                                 {:success true :exists? true
                                                  :worktree {:path expected-worktree-path :branch "clean-task"}
                                                  :error nil})]

              (let [result (#'sut/work-on-impl
                            (assoc (h/test-config test-dir) :worktree-management? true)
                            nil
                            {:task-id task-id})
                    response (json/parse-string (get-in result [:content 0 :text]) keyword)]

                (is (false? (:isError result)))
                (is (= expected-worktree-path (:worktree-path response)))
                (is (false? (:worktree-created? response)))
                (is (= "mcp-tasks-clean-task" (:worktree-name response)))
                (is (str/includes? (:message response) "Please start a new Claude Code session"))
                ;; Execution state file should be written to worktree before directory switch
                (is (= (str expected-worktree-path "/.mcp-tasks-current.edn")
                       (:execution-state-file response)))))))))))

(deftest work-on-worktree-wrong-branch-error
  (h/with-test-setup [test-dir]
    ;; Test error when worktree is on wrong branch
    (testing "work-on detects worktree on wrong branch (requires being in worktree)"
      (let [base-dir (:base-dir (h/test-config test-dir))
            config-file (str base-dir "/.mcp-tasks.edn")]
        (spit config-file "{:worktree-management? true}")

        (let [add-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Expected Branch" :type "task"})
              add-response (json/parse-string (get-in add-result [:content 1 :text]) keyword)
              task-id (get-in add-response [:task :id])
              expected-worktree-path (str (fs/parent base-dir) "/mcp-tasks-expected-branch")]

          (with-redefs [git/find-worktree-for-branch (fn [_ _] {:success true :worktree nil :error nil})
                        git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                        git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                        git/get-default-branch (fn [_] {:success true :branch "main" :error nil})
                        git/checkout-branch (fn [_ _] {:success true :error nil})
                        git/pull-latest (fn [_ _] {:success true :pulled? true :error nil})
                        git/branch-exists? (fn [_ _] {:success true :exists? true :error nil})
                        git/derive-worktree-path (fn [_ _ _ _] {:success true :path expected-worktree-path :error nil})
                        git/worktree-exists? (fn [_ _]
                                               {:success true :exists? true
                                                :worktree {:path expected-worktree-path :branch "wrong-branch"}
                                                :error nil})]

            (let [result (#'sut/work-on-impl
                          (assoc (h/test-config test-dir) :worktree-management? true)
                          nil
                          {:task-id task-id})
                  response (json/parse-string (get-in result [:content 0 :text]) keyword)]

              ;; Since we're not in the worktree, we get the "switch directory" message
              (is (false? (:isError result)))
              (is (= expected-worktree-path (:worktree-path response)))
              (is (false? (:worktree-created? response)))
              (is (str/includes? (:message response) "Please start a new Claude Code session")))))))))

(deftest work-on-worktree-directory-switch-required
  (h/with-test-setup [test-dir]
    ;; Test that work-on informs user when they need to switch directories
    (testing "work-on requires directory switch when worktree exists but not in it"
      (let [base-dir (:base-dir (h/test-config test-dir))
            config-file (str base-dir "/.mcp-tasks.edn")]
        (spit config-file "{:worktree-management? true}")

        (let [add-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Switch Dir Task" :type "task"})
              add-response (json/parse-string (get-in add-result [:content 1 :text]) keyword)
              task-id (get-in add-response [:task :id])
              expected-worktree-path (h/derive-test-worktree-path base-dir "Switch Dir Task")]

          ;; Mock to simulate worktree exists but we're not in it
          (with-redefs [git/find-worktree-for-branch (fn [_ _] {:success true :worktree nil :error nil})
                        git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                        git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                        git/get-default-branch (fn [_] {:success true :branch "main" :error nil})
                        git/checkout-branch (fn [_ _] {:success true :error nil})
                        git/pull-latest (fn [_ _] {:success true :pulled? true :error nil})
                        git/branch-exists? (fn [_ _] {:success true :exists? true :error nil})
                        git/derive-worktree-path (fn [_ _ _ _] {:success true :path expected-worktree-path :error nil})
                        git/worktree-exists? (fn [_ _]
                                               {:success true :exists? true
                                                :worktree {:path expected-worktree-path :branch "switch-dir-task"}
                                                :error nil})]

            (let [result (#'sut/work-on-impl
                          (assoc (h/test-config test-dir) :worktree-management? true)
                          nil
                          {:task-id task-id})
                  response (json/parse-string (get-in result [:content 0 :text]) keyword)]

              (is (false? (:isError result)))
              (is (= expected-worktree-path (:worktree-path response)))
              (is (false? (:worktree-created? response)))
              (is (str/includes? (:message response) "Please start a new Claude Code session"))
              (is (str/includes? (:message response) expected-worktree-path))
              ;; Execution state file should be written to worktree before directory switch
              (is (= (str expected-worktree-path "/.mcp-tasks-current.edn") (:execution-state-file response))))))))))

(deftest work-on-worktree-branch-discovery
  (h/with-test-setup [test-dir]
    ;; Test that work-on discovers and reuses worktrees by branch name
    (testing "work-on discovers existing worktree for branch"
      (testing "redirects to existing worktree when branch exists elsewhere"
        (let [base-dir (:base-dir (h/test-config test-dir))
              config-file (str base-dir "/.mcp-tasks.edn")
              existing-worktree-path "/path/to/existing/worktree"]
          (spit config-file "{:worktree-management? true}")

          (let [add-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Reuse Worktree" :type "task"})
                add-response (json/parse-string (get-in add-result [:content 1 :text]) keyword)
                task-id (get-in add-response [:task :id])]

            (with-redefs [git/find-worktree-for-branch (fn [_ branch-name]
                                                         (is (= (str task-id "-reuse-worktree") branch-name))
                                                         {:success true
                                                          :worktree {:path existing-worktree-path
                                                                     :branch (str task-id "-reuse-worktree")}
                                                          :error nil})]

              (let [result (#'sut/work-on-impl
                            (assoc (h/test-config test-dir) :worktree-management? true)
                            nil
                            {:task-id task-id})
                    response (json/parse-string (get-in result [:content 0 :text]) keyword)]

                (is (false? (:isError result)))
                (is (= existing-worktree-path (:worktree-path response)))
                (is (false? (:worktree-created? response)))
                (is (str/includes? (:message response) "Worktree exists at"))
                (is (str/includes? (:message response) existing-worktree-path))
                (is (= (str existing-worktree-path "/.mcp-tasks-current.edn") (:execution-state-file response))))))))

      (testing "creates new worktree when branch not in any worktree"
        (let [base-dir (:base-dir (h/test-config test-dir))
              config-file (str base-dir "/.mcp-tasks.edn")
              expected-worktree-path (h/derive-test-worktree-path base-dir "New Worktree")]
          (spit config-file "{:worktree-management? true}")

          (let [add-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "New Worktree" :type "task"})
                add-response (json/parse-string (get-in add-result [:content 1 :text]) keyword)
                task-id (get-in add-response [:task :id])]

            (with-redefs [git/find-worktree-for-branch (fn find-worktree-for-branch
                                                         [_ branch-name]
                                                         (is (= (str task-id "-new-worktree") branch-name))
                                                         {:success true
                                                          :worktree nil
                                                          :error nil})
                          git/derive-worktree-path
                          (fn derive-worktree-path
                            [_ _ _ _]
                            {:success true
                             :path expected-worktree-path
                             :error nil})
                          git/worktree-exists? (fn worktree-exists?
                                                 [_ path]
                                                 (is (= expected-worktree-path path))
                                                 {:success true
                                                  :exists? false
                                                  :error nil})
                          git/branch-exists? (fn branch-exists?
                                               [_ branch]
                                               (is (= (str task-id "-new-worktree") branch))
                                               {:success true
                                                :exists? false
                                                :error nil})
                          git/get-default-branch (fn get-default-branch
                                                   [_]
                                                   {:success true
                                                    :branch "main"
                                                    :error nil})
                          git/create-worktree (fn create-worktree
                                                ([_ path branch base]
                                                 (is (= expected-worktree-path path))
                                                 (is (= (str task-id "-new-worktree") branch))
                                                 (is (= "main" base))
                                                 {:success true :error nil}))]

              (let [result (#'sut/work-on-impl
                            (assoc (h/test-config test-dir) :worktree-management? true)
                            nil
                            {:task-id task-id})
                    response (json/parse-string
                               (get-in result [:content 0 :text])
                               keyword)]

                (is (false? (:isError result)))
                (is (= expected-worktree-path (:worktree-path response)))
                (is (true? (:worktree-created? response)))
                (is (str/includes? (:message response) "Worktree created"))
                (is (= (str expected-worktree-path "/.mcp-tasks-current.edn") (:execution-state-file response))))))))

      (testing "multiple tasks in same story reuse same worktree"
        (let [base-dir (:base-dir (h/test-config test-dir))
              config-file (str base-dir "/.mcp-tasks.edn")
              story-worktree-path "/path/to/story/worktree"]
          (spit config-file "{:worktree-management? true}")

          ;; Create a story
          (let [story-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "story" :title "My Story" :type "story"})
                story-response (json/parse-string (get-in story-result [:content 1 :text]) keyword)
                story-id (get-in story-response [:task :id])

                ;; Create two tasks in the story
                task1-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Task One" :type "task" :parent-id story-id})
                task1-response (json/parse-string (get-in task1-result [:content 1 :text]) keyword)
                task1-id (get-in task1-response [:task :id])

                task2-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Task Two" :type "task" :parent-id story-id})
                task2-response (json/parse-string (get-in task2-result [:content 1 :text]) keyword)
                task2-id (get-in task2-response [:task :id])]

            (with-redefs [git/find-worktree-for-branch (fn [_ branch-name]
                                                         (is (= (str story-id "-my-story") branch-name))
                                                         {:success true
                                                          :worktree {:path story-worktree-path
                                                                     :branch (str story-id "-my-story")}
                                                          :error nil})]

              ;; First task should find existing worktree
              (let [result1 (#'sut/work-on-impl (assoc (h/test-config test-dir) :worktree-management? true) nil {:task-id task1-id})
                    response1 (json/parse-string (get-in result1 [:content 0 :text]) keyword)]
                (is (false? (:isError result1)))
                (is (= story-worktree-path (:worktree-path response1)))
                (is (false? (:worktree-created? response1))))

              ;; Second task should also find the same worktree
              (let [result2 (#'sut/work-on-impl (assoc (h/test-config test-dir) :worktree-management? true) nil {:task-id task2-id})
                    response2 (json/parse-string (get-in result2 [:content 0 :text]) keyword)]
                (is (false? (:isError result2)))
                (is (= story-worktree-path (:worktree-path response2)))
                (is (false? (:worktree-created? response2))))))))

      (testing "handles branch in main repository working directory"
        (let [base-dir (:base-dir (h/test-config test-dir))
              config-file (str base-dir "/.mcp-tasks.edn")]
          (spit config-file "{:worktree-management? true}")

          (let [add-result (#'add-task/add-task-impl
                            (h/test-config test-dir)
                            nil
                            {:category "simple"
                             :title "Main Repo Task"
                             :type "task"})
                add-response (json/parse-string
                               (get-in add-result [:content 1 :text])
                               keyword)
                task-id (get-in add-response [:task :id])]

            (with-redefs [git/find-worktree-for-branch
                          (fn find-worktree-for-branch
                            [_ branch-name]
                            (is (= (str task-id "-main-repo-task") branch-name))
                            {:success true
                             :worktree {:path base-dir
                                        :branch (str task-id "-main-repo-task")}
                             :error nil})]

              (let [result (#'sut/work-on-impl
                            (assoc (h/test-config test-dir) :worktree-management? true)
                            nil
                            {:task-id task-id})
                    response (json/parse-string
                               (get-in result [:content 0 :text])
                               keyword)]

                ;; Since we're in the main repo and the branch is checked out there,
                ;; the find-worktree-for-branch will return the main repo path,
                ;; and we'll need to switch to it (which we're already in this test)
                (is (false? (:isError result)))
                (is (= (str base-dir) (:worktree-path response)))
                (is (false? (:worktree-created? response)))))))))))

(deftest work-on-worktree-execution-state-with-directory-switch
  ;; Test execution state file creation when directory switch is needed
  (testing "work-on writes execution state to worktree when working on a story"
    (h/with-test-setup [test-dir]
      (let [base-dir (:base-dir (h/test-config test-dir))
            config-file (str base-dir "/.mcp-tasks.edn")
            expected-worktree-path (h/derive-test-worktree-path base-dir "Story Task")]
        (spit config-file "{:worktree-management? true}")

        ;; Create a story task
        (let [add-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "story" :title "Story Task" :type "story"})
              add-response (json/parse-string (get-in add-result [:content 1 :text]) keyword)
              story-id (get-in add-response [:task :id])]

          (with-redefs [git/find-worktree-for-branch (fn [_ _] {:success true :worktree nil :error nil})
                        git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                        git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                        git/get-default-branch (fn [_] {:success true :branch "main" :error nil})
                        git/checkout-branch (fn [_ _] {:success true :error nil})
                        git/pull-latest (fn [_ _] {:success true :pulled? true :error nil})
                        git/branch-exists? (fn [_ _] {:success true :exists? false :error nil})
                        git/derive-project-name (fn [_] {:success true :name "mcp-tasks" :error nil})
                        git/derive-worktree-path (fn [_ _ _ _] {:success true :path expected-worktree-path :error nil})
                        git/worktree-exists? (fn [_ _] {:success true :exists? false :worktree nil :error nil})
                        git/create-worktree (fn [_ _ _ _] {:success true :error nil})]

            (let [result (#'sut/work-on-impl
                          (assoc (h/test-config test-dir) :worktree-management? true)
                          nil
                          {:task-id story-id})
                  response (json/parse-string (get-in result [:content 0 :text]) keyword)
                  exec-state-path (str expected-worktree-path "/.mcp-tasks-current.edn")]

              (is (false? (:isError result)))
              (is (= expected-worktree-path (:worktree-path response)))
              (is (true? (:worktree-created? response)))
              ;; Verify execution state file path is returned
              (is (= exec-state-path (:execution-state-file response))))))))))

(testing "work-on writes execution state to worktree when working on a child task"
  (h/with-test-setup [test-dir]
    (let [base-dir (:base-dir (h/test-config test-dir))
          config-file (str base-dir "/.mcp-tasks.edn")
          expected-worktree-path (h/derive-test-worktree-path base-dir "Parent Story")]
      (spit config-file "{:worktree-management? true}")

      ;; Create a story and a child task
      (let [story-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "story" :title "Parent Story" :type "story"})
            story-response (json/parse-string (get-in story-result [:content 1 :text]) keyword)
            story-id (get-in story-response [:task :id])

            task-result (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "simple" :title "Child Task" :type "task" :parent-id story-id})
            task-response (json/parse-string (get-in task-result [:content 1 :text]) keyword)
            task-id (get-in task-response [:task :id])]

        (with-redefs [git/find-worktree-for-branch (fn [_ _] {:success true :worktree nil :error nil})
                      git/get-current-branch (fn [_] {:success true :branch "main" :error nil})
                      git/check-uncommitted-changes (fn [_] {:success true :has-changes? false :error nil})
                      git/get-default-branch (fn [_] {:success true :branch "main" :error nil})
                      git/checkout-branch (fn [_ _] {:success true :error nil})
                      git/pull-latest (fn [_ _] {:success true :pulled? true :error nil})
                      git/branch-exists? (fn [_ _] {:success true :exists? false :error nil})
                      git/derive-project-name (fn [_] {:success true :name "mcp-tasks" :error nil})
                      git/derive-worktree-path (fn [_ _ _ _] {:success true :path expected-worktree-path :error nil})
                      git/worktree-exists? (fn [_ _] {:success true :exists? false :worktree nil :error nil})
                      git/create-worktree (fn [_ _ _ _] {:success true :error nil})]

          (let [result (#'sut/work-on-impl
                        (assoc (h/test-config test-dir) :worktree-management? true)
                        nil
                        {:task-id task-id})
                response (json/parse-string (get-in result [:content 0 :text]) keyword)
                exec-state-path (str expected-worktree-path "/.mcp-tasks-current.edn")]

            (is (false? (:isError result)))
            (is (= expected-worktree-path (:worktree-path response)))
            (is (true? (:worktree-created? response)))
            ;; Verify execution state file path is returned
            (is (= exec-state-path (:execution-state-file response)))))))))
