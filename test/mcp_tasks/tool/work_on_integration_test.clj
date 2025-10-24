(ns mcp-tasks.tool.work-on-integration-test
  "Integration tests for work-on tool with real git worktrees.

  These tests create actual git repositories and worktrees to verify
  end-to-end behavior without mocks."
  (:require
    [babashka.fs :as fs]
    [clojure.data.json :as json]
    [clojure.java.shell :as sh]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.test-helpers :as h]
    [mcp-tasks.tool.add-task :as add-task]
    [mcp-tasks.tool.work-on :as sut]))

(defn init-main-git-repo
  "Initialize a git repository in the base directory (not .mcp-tasks).
  This creates a main repository that can have worktrees."
  [base-dir]
  (sh/sh "git" "init" :dir base-dir)
  (sh/sh "git" "config" "user.email" "test@test.com" :dir base-dir)
  (sh/sh "git" "config" "user.name" "Test User" :dir base-dir)
  ;; Create initial commit so we have a default branch
  (spit (str base-dir "/README.md") "# Test Project\n")
  (sh/sh "git" "add" "README.md" :dir base-dir)
  (sh/sh "git" "commit" "-m" "Initial commit" :dir base-dir))

(defn create-worktree
  "Create a git worktree at the specified path.
  Creates a new branch with the given name."
  [base-dir worktree-path branch-name]
  (let [result (sh/sh "git" "worktree" "add" "-b" branch-name worktree-path "HEAD" :dir base-dir)]
    (when-not (zero? (:exit result))
      (throw (ex-info "Failed to create worktree"
                      {:exit (:exit result)
                       :out (:out result)
                       :err (:err result)})))))

(defn worktree-exists?
  "Check if a worktree exists at the given path."
  [worktree-path]
  (and (fs/exists? worktree-path)
       (fs/exists? (fs/file worktree-path ".git"))
       (not (fs/directory? (fs/file worktree-path ".git")))))

(defn list-worktrees
  "List all worktrees for a git repository."
  [base-dir]
  (let [result (sh/sh "git" "worktree" "list" "--porcelain" :dir base-dir)]
    (when (zero? (:exit result))
      (:out result))))

(deftest work-on-creates-real-worktree
  (h/with-test-setup [test-dir]
    ;; Test that work-on actually creates a real git worktree
    (testing "creates a real git worktree"
      (let [base-dir (str test-dir)
            config-file (str base-dir "/.mcp-tasks.edn")]

        ;; Initialize main git repo
        (init-main-git-repo base-dir)

        ;; Initialize .mcp-tasks with git
        (h/init-git-repo base-dir)

        ;; Enable worktree management
        (spit config-file "{:worktree-management? true}")

        ;; Add a task
        (let [add-result (#'add-task/add-task-impl
                          (h/test-config base-dir)
                          nil
                          {:category "simple"
                           :title "Test Worktree Task"
                           :type "task"})
              add-response (json/read-str
                             (get-in add-result [:content 1 :text])
                             :key-fn keyword)
              task-id (get-in add-response [:task :id])]

          ;; Call work-on tool
          (let [result (#'sut/work-on-impl
                        (assoc (h/git-test-config base-dir)
                               :worktree-management? true
                               :main-repo-dir base-dir)
                        nil
                        {:task-id task-id})
                response (json/read-str
                           (get-in result [:content 0 :text])
                           :key-fn keyword)
                actual-worktree-path (:worktree-path response)]

            ;; Verify response indicates worktree creation
            (is (false? (:isError result))
                "work-on should succeed")
            (is (true? (:worktree-created? response))
                "Response should indicate worktree was created")
            (is (some? actual-worktree-path)
                "Response should include worktree path")

            ;; Verify worktree actually exists on filesystem
            (is (worktree-exists? actual-worktree-path)
                "Worktree directory should exist with .git file")

            ;; Verify worktree is listed in git worktree list
            (let [worktree-list (list-worktrees base-dir)]
              (is (str/includes? worktree-list actual-worktree-path)
                  "Worktree should appear in git worktree list"))))))))

(deftest work-on-from-within-worktree
  (h/with-test-setup [test-dir]
    ;; Test that work-on works correctly when called from within a worktree
    (testing "works when called from within a worktree"
      (let [base-dir (str test-dir)
            config-file (str base-dir "/.mcp-tasks.edn")]

        ;; Initialize main git repo
        (init-main-git-repo base-dir)

        ;; Initialize .mcp-tasks with git
        (h/init-git-repo base-dir)

        ;; Enable worktree management
        (spit config-file "{:worktree-management? true}")

        ;; Add two tasks
        (let [add-result1 (#'add-task/add-task-impl
                           (h/test-config base-dir)
                           nil
                           {:category "simple"
                            :title "First Task"
                            :type "task"})
              add-response1 (json/read-str
                              (get-in add-result1 [:content 1 :text])
                              :key-fn keyword)
              task1-id (get-in add-response1 [:task :id])

              add-result2 (#'add-task/add-task-impl
                           (h/test-config base-dir)
                           nil
                           {:category "simple"
                            :title "Second Task"
                            :type "task"})
              add-response2 (json/read-str
                              (get-in add-result2 [:content 1 :text])
                              :key-fn keyword)
              task2-id (get-in add-response2 [:task :id])]

          ;; Create first worktree using work-on
          (let [result1 (#'sut/work-on-impl
                         (assoc (h/git-test-config base-dir)
                                :worktree-management? true
                                :main-repo-dir base-dir)
                         nil
                         {:task-id task1-id})
                response1 (json/read-str
                            (get-in result1 [:content 0 :text])
                            :key-fn keyword)
                worktree1-path (:worktree-path response1)]

            ;; Verify first worktree was created
            (is (true? (:worktree-created? response1))
                "First worktree should be created")
            (is (worktree-exists? worktree1-path)
                "First worktree should exist")

            ;; Copy config to first worktree so it can be used as base-dir
            (fs/copy config-file (str worktree1-path "/.mcp-tasks.edn"))

            ;; Call work-on from within first worktree for second task
            ;; This simulates the scenario where the MCP server is running
            ;; from within a worktree directory
            (let [result2 (#'sut/work-on-impl
                           (assoc (h/git-test-config worktree1-path)
                                  :worktree-management? true
                                  :base-dir worktree1-path
                                  :main-repo-dir base-dir)
                           nil
                           {:task-id task2-id})
                  response2 (json/read-str
                              (get-in result2 [:content 0 :text])
                              :key-fn keyword)
                  worktree2-path (:worktree-path response2)]

              ;; Verify the tool works correctly from within a worktree
              (is (false? (:isError result2))
                  "work-on should succeed when called from worktree")
              (is (true? (:worktree-created? response2))
                  "Should create second worktree")
              (is (some? worktree2-path)
                  "Should return worktree path")

              ;; Verify second worktree actually exists
              (is (worktree-exists? worktree2-path)
                  "Second worktree should exist on filesystem"))))))))

(deftest work-on-detects-worktree-environment
  (h/with-test-setup [test-dir]
    ;; Test that work-on correctly detects when it's running in a worktree
    (testing "detects worktree environment correctly"
      (let [base-dir (str test-dir)
            config-file (str base-dir "/.mcp-tasks.edn")]

        ;; Initialize main git repo
        (init-main-git-repo base-dir)

        ;; Initialize .mcp-tasks with git
        (h/init-git-repo base-dir)

        ;; Enable worktree management
        (spit config-file "{:worktree-management? true}")

        ;; Add a task
        (let [add-result (#'add-task/add-task-impl
                          (h/test-config base-dir)
                          nil
                          {:category "simple"
                           :title "Detect Test"
                           :type "task"})
              add-response (json/read-str
                             (get-in add-result [:content 1 :text])
                             :key-fn keyword)
              task-id (get-in add-response [:task :id])]

          ;; Create worktree using work-on from main repo
          (let [result1 (#'sut/work-on-impl
                         (assoc (h/git-test-config base-dir)
                                :worktree-management? true
                                :main-repo-dir base-dir)
                         nil
                         {:task-id task-id})
                response1 (json/read-str
                            (get-in result1 [:content 0 :text])
                            :key-fn keyword)
                worktree-path (:worktree-path response1)]

            ;; Verify worktree was created
            (is (true? (:worktree-created? response1))
                "Worktree should be created")
            (is (worktree-exists? worktree-path)
                "Worktree should exist")

            ;; Copy config to worktree
            (fs/copy config-file (str worktree-path "/.mcp-tasks.edn"))

            ;; Call work-on from within the worktree for the same task
            ;; (simulating continuing work in the same worktree)
            (let [result2 (#'sut/work-on-impl
                           (assoc (h/git-test-config worktree-path)
                                  :worktree-management? true
                                  :base-dir worktree-path
                                  :main-repo-dir base-dir)
                           nil
                           {:task-id task-id})
                  response2 (json/read-str
                              (get-in result2 [:content 0 :text])
                              :key-fn keyword)
                  response-worktree-path (:worktree-path response2)]

              ;; Should recognize we're already in the correct worktree
              ;; Note: paths may differ in canonical form (/var vs /private/var on macOS)
              ;; so we canonicalize both for comparison
              (is (false? (:isError result2))
                  "work-on should succeed")
              (is (false? (:worktree-created? response2))
                  "Should not create new worktree")
              (is (= (str (fs/canonicalize worktree-path))
                     (str (fs/canonicalize response-worktree-path)))
                  "Should recognize current worktree"))))))))
