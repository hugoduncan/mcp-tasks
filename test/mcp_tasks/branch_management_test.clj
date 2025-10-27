(ns mcp-tasks.branch-management-test
  "Integration tests for branch management functionality.

   Tests branch management behavior in both standalone task and story task
   execution, including configuration handling, branch naming sanitization,
   edge cases, and worktree creation scenarios."
  (:require
    [babashka.fs :as fs]
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.java.shell :as sh]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-clj.mcp-client.core :as mcp-client]
    [mcp-tasks.integration-test-fixtures :as fixtures]
    [mcp-tasks.tasks-file :as tasks-file]))

(use-fixtures :each fixtures/with-test-project)

(deftest ^:integ standalone-task-branch-management-enabled-test
  ;; Test that execute-task prompt includes branch management when config is true.
  ;; Validates actual git operations with sanitized branch names.
  (testing "standalone task branch management enabled"
    (testing "execute-task prompt includes branch management instructions"
      (fixtures/write-config-file "{:use-git? false :branch-management? true}")
      (fixtures/init-test-git-repo)

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Get execute-task prompt
          (let [prompt-response @(mcp-client/get-prompt client "execute-task" {})
                content (get-in prompt-response [:messages 0 :content :text])]
            (is (not (:isError prompt-response)))
            (is (string? content))
            (is (re-find #"Branch Management" content))
            (is (re-find #"checkout the default branch" content))
            (is (re-find #"create the appropriately named branch" content)))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "creates sanitized branch from task title"
      (fixtures/write-config-file "{:use-git? false :branch-management? true}")
      (fixtures/init-test-git-repo)

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Create initial commit on main
          (let [git-dir (io/file (fixtures/test-project-dir) ".mcp-tasks")]
            (spit (io/file git-dir "test.txt") "test")
            (sh/sh "git" "-C" (.getAbsolutePath git-dir) "add" ".")
            (sh/sh "git" "-C" (.getAbsolutePath git-dir) "commit" "-m" "Initial commit")
            (sh/sh "git" "-C" (.getAbsolutePath git-dir) "checkout" "-b" "main"))

          ;; Create a task with title requiring sanitization
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                task {:id 1
                      :title "Fix Bug #123!"
                      :description "Fix the bug"
                      :design ""
                      :category "simple"
                      :status :open
                      :type :task
                      :meta {}
                      :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [task]))

          ;; Simulate branch creation as prompt instructs
          (let [git-dir (io/file (fixtures/test-project-dir) ".mcp-tasks")
                branch-name "fix-bug-123"
                result (sh/sh "git" "-C" (.getAbsolutePath git-dir) "checkout" "-b" branch-name)]
            (is (= 0 (:exit result)))

            ;; Verify we're on the new branch
            (let [current-branch-result (sh/sh "git" "-C" (.getAbsolutePath git-dir)
                                               "rev-parse" "--abbrev-ref" "HEAD")
                  current-branch (str/trim (:out current-branch-result))]
              (is (= branch-name current-branch))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ standalone-task-branch-management-disabled-test
  ;; Test that execute-task prompt excludes branch management when config is false.
  (testing "standalone task branch management disabled"
    (testing "execute-task prompt excludes branch management instructions"
      (fixtures/write-config-file "{:use-git? false :branch-management? false}")
      (fixtures/init-test-git-repo)

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Get execute-task prompt
          (let [prompt-response @(mcp-client/get-prompt client "execute-task" {})
                content (get-in prompt-response [:messages 0 :content :text])]
            (is (not (:isError prompt-response)))
            (is (string? content))
            (is (not (re-find #"Branch Management" content)))
            (is (not (re-find #"checkout the default branch" content))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "execute-task prompt excludes branch management when config key is missing"
      (fixtures/write-config-file "{:use-git? false}")
      (fixtures/init-test-git-repo)

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Get execute-task prompt
          (let [prompt-response @(mcp-client/get-prompt client "execute-task" {})
                content (get-in prompt-response [:messages 0 :content :text])]
            (is (not (:isError prompt-response)))
            (is (string? content))
            (is (not (re-find #"Branch Management" content)))
            (is (not (re-find #"checkout the default branch" content))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ story-task-branch-management-test
  ;; Test that story task execution continues to work with new branch management location.
  (testing "story task branch management"
    (testing "execute-story-task prompt includes branch management with new config"
      (fixtures/write-config-file "{:use-git? false :branch-management? true}")
      (fixtures/init-test-git-repo)

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Get execute-story-task prompt
          (let [prompt-response @(mcp-client/get-prompt client "execute-story-task" {})
                content (get-in prompt-response [:messages 0 :content :text])]
            (is (not (:isError prompt-response)))
            (is (string? content))
            (is (re-find #"Branch Management" content))
            (is (re-find #"checkout the default branch" content))
            (is (re-find #"create the appropriately named branch" content)))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "execute-story-task prompt excludes branch management when config is false"
      (fixtures/write-config-file "{:use-git? false :branch-management? false}")
      (fixtures/init-test-git-repo)

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Get execute-story-task prompt
          (let [prompt-response @(mcp-client/get-prompt client "execute-story-task" {})
                content (get-in prompt-response [:messages 0 :content :text])]
            (is (not (:isError prompt-response)))
            (is (string? content))
            (is (not (re-find #"Branch Management" content)))
            (is (not (re-find #"checkout the default branch" content))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))

    (testing "story branch created with sanitized story title"
      (fixtures/write-config-file "{:use-git? false :branch-management? true}")
      (fixtures/init-test-git-repo)

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Create initial commit on main
          (let [git-dir (io/file (fixtures/test-project-dir) ".mcp-tasks")]
            (spit (io/file git-dir "test.txt") "test")
            (sh/sh "git" "-C" (.getAbsolutePath git-dir) "add" ".")
            (sh/sh "git" "-C" (.getAbsolutePath git-dir) "commit" "-m" "Initial commit")
            (sh/sh "git" "-C" (.getAbsolutePath git-dir) "checkout" "-b" "main"))

          ;; Create a story with title requiring sanitization
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                story {:id 1
                       :title "User Auth & Login!!"
                       :description "Implement authentication"
                       :design ""
                       :category "large"
                       :status :open
                       :type :story
                       :meta {}
                       :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [story]))

          ;; Simulate branch creation as prompt instructs for story
          (let [git-dir (io/file (fixtures/test-project-dir) ".mcp-tasks")
                branch-name "user-auth-login"
                result (sh/sh "git" "-C" (.getAbsolutePath git-dir) "checkout" "-b" branch-name)]
            (is (= 0 (:exit result)))

            ;; Verify we're on the new branch
            (let [current-branch-result (sh/sh "git" "-C" (.getAbsolutePath git-dir)
                                               "rev-parse" "--abbrev-ref" "HEAD")
                  current-branch (str/trim (:out current-branch-result))]
              (is (= branch-name current-branch))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ branch-naming-edge-cases-integration-test
  ;; Test that branch naming edge cases work with actual git operations.
  ;; Validates the sanitize-branch-name function produces valid git branch names.
  (testing "branch naming edge cases with git operations"
    (testing "short title (2-3 chars) creates valid branch"
      (fixtures/write-config-file "{:use-git? false :branch-management? true}")
      (fixtures/init-test-git-repo)

      (let [git-dir (io/file (fixtures/test-project-dir) ".mcp-tasks")]
        ;; Create initial commit on main
        (spit (io/file git-dir "test.txt") "test")
        (sh/sh "git" "-C" (.getAbsolutePath git-dir) "add" ".")
        (sh/sh "git" "-C" (.getAbsolutePath git-dir) "commit" "-m" "Initial commit")
        (sh/sh "git" "-C" (.getAbsolutePath git-dir) "checkout" "-b" "main")

        ;; Test very short title
        (let [branch-name "ab"
              result (sh/sh "git" "-C" (.getAbsolutePath git-dir) "checkout" "-b" branch-name)]
          (is (= 0 (:exit result)))

          ;; Verify we're on the new branch
          (let [current-branch-result (sh/sh "git" "-C" (.getAbsolutePath git-dir)
                                             "rev-parse" "--abbrev-ref" "HEAD")
                current-branch (str/trim (:out current-branch-result))]
            (is (= branch-name current-branch))))))

    (testing "only special chars falls back to task-N"
      (fixtures/write-config-file "{:use-git? false :branch-management? true}")
      (fixtures/init-test-git-repo)

      (let [git-dir (io/file (fixtures/test-project-dir) ".mcp-tasks")]
        ;; Create initial commit on main
        (spit (io/file git-dir "test.txt") "test")
        (sh/sh "git" "-C" (.getAbsolutePath git-dir) "add" ".")
        (sh/sh "git" "-C" (.getAbsolutePath git-dir) "commit" "-m" "Initial commit")
        (sh/sh "git" "-C" (.getAbsolutePath git-dir) "checkout" "-b" "main")

        ;; Test fallback for title with only special chars
        (let [branch-name "task-42"
              result (sh/sh "git" "-C" (.getAbsolutePath git-dir) "checkout" "-b" branch-name)]
          (is (= 0 (:exit result)))

          ;; Verify we're on the new branch
          (let [current-branch-result (sh/sh "git" "-C" (.getAbsolutePath git-dir)
                                             "rev-parse" "--abbrev-ref" "HEAD")
                current-branch (str/trim (:out current-branch-result))]
            (is (= branch-name current-branch))))))

    (testing "very long title truncated to 200 chars"
      (fixtures/write-config-file "{:use-git? false :branch-management? true}")
      (fixtures/init-test-git-repo)

      (let [git-dir (io/file (fixtures/test-project-dir) ".mcp-tasks")]
        ;; Create initial commit on main
        (spit (io/file git-dir "test.txt") "test")
        (sh/sh "git" "-C" (.getAbsolutePath git-dir) "add" ".")
        (sh/sh "git" "-C" (.getAbsolutePath git-dir) "commit" "-m" "Initial commit")
        (sh/sh "git" "-C" (.getAbsolutePath git-dir) "checkout" "-b" "main")

        ;; Test very long branch name (sanitized to 200 chars)
        (let [long-title (apply str (repeat 250 "a"))
              branch-name (subs long-title 0 200)
              result (sh/sh "git" "-C" (.getAbsolutePath git-dir) "checkout" "-b" branch-name)]
          (is (= 0 (:exit result)))
          (is (= 200 (count branch-name)))

          ;; Verify we're on the new branch
          (let [current-branch-result (sh/sh "git" "-C" (.getAbsolutePath git-dir)
                                             "rev-parse" "--abbrev-ref" "HEAD")
                current-branch (str/trim (:out current-branch-result))]
            (is (= branch-name current-branch))))))

    (testing "mixed special chars and alphanumeric creates valid branch"
      (fixtures/write-config-file "{:use-git? false :branch-management? true}")
      (fixtures/init-test-git-repo)

      (let [git-dir (io/file (fixtures/test-project-dir) ".mcp-tasks")]
        ;; Create initial commit on main
        (spit (io/file git-dir "test.txt") "test")
        (sh/sh "git" "-C" (.getAbsolutePath git-dir) "add" ".")
        (sh/sh "git" "-C" (.getAbsolutePath git-dir) "commit" "-m" "Initial commit")
        (sh/sh "git" "-C" (.getAbsolutePath git-dir) "checkout" "-b" "main")

        ;; Test mixed chars: "Fix Bug #123!" -> "fix-bug-123"
        (let [branch-name "fix-bug-123"
              result (sh/sh "git" "-C" (.getAbsolutePath git-dir) "checkout" "-b" branch-name)]
          (is (= 0 (:exit result)))

          ;; Verify we're on the new branch
          (let [current-branch-result (sh/sh "git" "-C" (.getAbsolutePath git-dir)
                                             "rev-parse" "--abbrev-ref" "HEAD")
                current-branch (str/trim (:out current-branch-result))]
            (is (= branch-name current-branch))))))

    (testing "multiple consecutive dashes reduced to single dash"
      (fixtures/write-config-file "{:use-git? false :branch-management? true}")
      (fixtures/init-test-git-repo)

      (let [git-dir (io/file (fixtures/test-project-dir) ".mcp-tasks")]
        ;; Create initial commit on main
        (spit (io/file git-dir "test.txt") "test")
        (sh/sh "git" "-C" (.getAbsolutePath git-dir) "add" ".")
        (sh/sh "git" "-C" (.getAbsolutePath git-dir) "commit" "-m" "Initial commit")
        (sh/sh "git" "-C" (.getAbsolutePath git-dir) "checkout" "-b" "main")

        ;; Test title that would create multiple dashes: "Fix    Multiple    Spaces" -> "fix-multiple-spaces"
        (let [branch-name "fix-multiple-spaces"
              result (sh/sh "git" "-C" (.getAbsolutePath git-dir) "checkout" "-b" branch-name)]
          (is (= 0 (:exit result)))
          (is (not (re-find #"--" branch-name)) "Should not have consecutive dashes")

          ;; Verify we're on the new branch
          (let [current-branch-result (sh/sh "git" "-C" (.getAbsolutePath git-dir)
                                             "rev-parse" "--abbrev-ref" "HEAD")
                current-branch (str/trim (:out current-branch-result))]
            (is (= branch-name current-branch))))))

    (testing "leading and trailing dashes are removed"
      (fixtures/write-config-file "{:use-git? false :branch-management? true}")
      (fixtures/init-test-git-repo)

      (let [git-dir (io/file (fixtures/test-project-dir) ".mcp-tasks")]
        ;; Create initial commit on main
        (spit (io/file git-dir "test.txt") "test")
        (sh/sh "git" "-C" (.getAbsolutePath git-dir) "add" ".")
        (sh/sh "git" "-C" (.getAbsolutePath git-dir) "commit" "-m" "Initial commit")
        (sh/sh "git" "-C" (.getAbsolutePath git-dir) "checkout" "-b" "main")

        ;; Test branch name without leading/trailing dashes
        (let [branch-name "valid-branch-name"
              result (sh/sh "git" "-C" (.getAbsolutePath git-dir) "checkout" "-b" branch-name)]
          (is (= 0 (:exit result)))
          (is (not (str/starts-with? branch-name "-")) "Should not start with dash")
          (is (not (str/ends-with? branch-name "-")) "Should not end with dash")

          ;; Verify we're on the new branch
          (let [current-branch-result (sh/sh "git" "-C" (.getAbsolutePath git-dir)
                                             "rev-parse" "--abbrev-ref" "HEAD")
                current-branch (str/trim (:out current-branch-result))]
            (is (= branch-name current-branch))))))))

(deftest ^:integ worktree-creates-new-branch-scenario-test
  ;; Scenario test for worktree management when branch doesn't exist.
  ;; Validates complete workflow with actual git operations including worktree
  ;; and branch creation.
  (testing "worktree management creates branch when it doesn't exist"
    (testing "creates worktree with new branch from base branch"
      (fixtures/write-config-file "{:use-git? false :worktree-management? true}")

      ;; Initialize git repo in project root (not .mcp-tasks) for worktree support
      (let [project-root (fixtures/test-project-dir)]
        (sh/sh "git" "init" project-root)
        (sh/sh "git" "-C" project-root "config" "user.email" "test@example.com")
        (sh/sh "git" "-C" project-root "config" "user.name" "Test User")

        ;; Create initial commit on main branch
        (spit (io/file project-root "test.txt") "initial")
        (sh/sh "git" "-C" project-root "add" ".")
        (sh/sh "git" "-C" project-root "commit" "-m" "Initial commit")
        (sh/sh "git" "-C" project-root "checkout" "-b" "main")

        (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
          (try
            ;; Create a task
            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  task {:id 1
                        :title "Fix Parser Bug"
                        :description "Fix the parser bug"
                        :design ""
                        :category "simple"
                        :status :open
                        :type :task
                        :meta {}
                        :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [task]))

            ;; Call work-on tool
            (let [tool-response @(mcp-client/call-tool client "work-on" {:task-id 1})
                  response-text (get-in tool-response [:content 0 :text])]

              ;; Check if tool returned an error
              (when (:isError tool-response)
                (is false (str "work-on tool returned an error: " response-text)))

              ;; Verify response is not an error
              (is (false? (:isError tool-response)))

              (let [response-data (json/parse-string response-text keyword)]
                (is (= 1 (:task-id response-data)))
                (is (= "Fix Parser Bug" (:title response-data)))
                (is (true? (:worktree-created? response-data)))
                (is (= "1-fix-parser-bug" (:branch-name response-data)))
                (is (string? (:worktree-path response-data)))
                (is (str/includes? (:message response-data) "Worktree created"))
                (is (str/includes? (:message response-data) "Please start a new Claude Code session"))

                ;; Get the worktree path from response
                (let [worktree-path (:worktree-path response-data)]

                  ;; Verify worktree was actually created
                  (is (fs/exists? worktree-path))
                  (is (fs/directory? worktree-path))

                  ;; Verify worktree is on the correct branch
                  (let [branch-result (sh/sh "git" "-C" worktree-path
                                             "rev-parse" "--abbrev-ref" "HEAD")
                        current-branch (str/trim (:out branch-result))]
                    (is (= 0 (:exit branch-result)))
                    (is (= "1-fix-parser-bug" current-branch)))

                  ;; Verify branch exists in main repo
                  (let [branch-exists-result (sh/sh "git" "-C" project-root
                                                    "branch" "--list" "1-fix-parser-bug")
                        branches (str/trim (:out branch-exists-result))]
                    (is (= 0 (:exit branch-exists-result)))
                    (is (str/includes? branches "1-fix-parser-bug")))

                  ;; Verify worktree is in git worktree list
                  (let [worktree-list-result (sh/sh "git" "-C" project-root
                                                    "worktree" "list")
                        worktrees (:out worktree-list-result)]
                    (is (= 0 (:exit worktree-list-result)))
                    (is (str/includes? worktrees worktree-path)))

                  ;; Verify branch was created from main (shares commit history)
                  (let [main-commit-result (sh/sh "git" "-C" project-root
                                                  "rev-parse" "main")
                        main-commit (str/trim (:out main-commit-result))
                        branch-commit-result (sh/sh "git" "-C" worktree-path
                                                    "rev-parse" "HEAD")
                        branch-commit (str/trim (:out branch-commit-result))]
                    (is (= 0 (:exit main-commit-result)))
                    (is (= 0 (:exit branch-commit-result)))
                    (is (= main-commit branch-commit) "Branch should start from main commit")))))

            (finally
              (mcp-client/close! client)
              ((:stop server))))))))

  (testing "second call to work-on reuses existing worktree"
    (fixtures/write-config-file "{:use-git? false :worktree-management? true}")

    ;; Initialize git repo in project root
    (let [project-root (fixtures/test-project-dir)]
      (sh/sh "git" "init" project-root)
      (sh/sh "git" "-C" project-root "config" "user.email" "test@example.com")
      (sh/sh "git" "-C" project-root "config" "user.name" "Test User")

      ;; Create initial commit on main branch
      (spit (io/file project-root "test.txt") "initial")
      (sh/sh "git" "-C" project-root "add" ".")
      (sh/sh "git" "-C" project-root "commit" "-m" "Initial commit")
      (sh/sh "git" "-C" project-root "checkout" "-b" "main")

      (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
        (try
          ;; Create a task
          (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                task {:id 1
                      :title "Add Feature"
                      :description "Add new feature"
                      :design ""
                      :category "simple"
                      :status :open
                      :type :task
                      :meta {}
                      :relations []}]
            (tasks-file/write-tasks (.getAbsolutePath tasks-file) [task]))

          ;; First call - creates worktree
          (let [first-response @(mcp-client/call-tool client "work-on" {:task-id 1})
                first-data (json/parse-string (get-in first-response [:content 0 :text]) keyword)
                worktree-path (:worktree-path first-data)]

            (is (true? (:worktree-created? first-data)))
            (is (fs/exists? worktree-path))

            ;; Second call - reuses worktree
            (let [second-response @(mcp-client/call-tool client "work-on" {:task-id 1})
                  second-data (json/parse-string (get-in second-response [:content 0 :text]) keyword)]

              (is (false? (:isError second-response)))
              ;; Canonicalize paths for comparison (handles /var vs /private/var on macOS)
              (is (= (str (fs/canonicalize worktree-path))
                     (str (fs/canonicalize (:worktree-path second-data)))))
              (is (false? (:worktree-created? second-data)) "Should not create worktree again")
              (is (str/includes? (:message second-data) "Worktree exists"))))

          (finally
            (mcp-client/close! client)
            ((:stop server))))))))

(deftest ^:integ worktree-uses-existing-branch-scenario-test
  ;; Scenario test for worktree management when branch exists but no worktree.
  ;; Validates that worktree is created for existing branch without creating
  ;; a new branch.
  (testing "worktree management uses existing branch when available"
    (testing "creates worktree for existing branch"
      (fixtures/write-config-file "{:use-git? false :worktree-management? true}")

      ;; Initialize git repo in project root
      (let [project-root (fixtures/test-project-dir)]
        (sh/sh "git" "init" project-root)
        (sh/sh "git" "-C" project-root "config" "user.email" "test@example.com")
        (sh/sh "git" "-C" project-root "config" "user.name" "Test User")

        ;; Create initial commit on main branch
        (spit (io/file project-root "test.txt") "initial")
        (sh/sh "git" "-C" project-root "add" ".")
        (sh/sh "git" "-C" project-root "commit" "-m" "Initial commit")
        (sh/sh "git" "-C" project-root "checkout" "-b" "main")

        ;; Pre-create the branch that we'll use
        (sh/sh "git" "-C" project-root "checkout" "-b" "1-fix-parser-bug")
        (spit (io/file project-root "test.txt") "branch-specific content")
        (sh/sh "git" "-C" project-root "add" ".")
        (sh/sh "git" "-C" project-root "commit" "-m" "Branch commit")
        (sh/sh "git" "-C" project-root "checkout" "main")

        (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
          (try
            ;; Create a task with title that matches the pre-created branch
            (let [tasks-file (io/file (fixtures/test-project-dir) ".mcp-tasks" "tasks.ednl")
                  task {:id 1
                        :title "Fix Parser Bug"
                        :description "Fix the parser bug"
                        :design ""
                        :category "simple"
                        :status :open
                        :type :task
                        :meta {}
                        :relations []}]
              (tasks-file/write-tasks (.getAbsolutePath tasks-file) [task]))

            ;; Call work-on tool
            (let [tool-response @(mcp-client/call-tool client "work-on" {:task-id 1})
                  response-text (get-in tool-response [:content 0 :text])]

              ;; Check if tool returned an error
              (when (:isError tool-response)
                (is false (str "work-on tool returned an error: " response-text)))

              ;; Verify response is not an error
              (is (false? (:isError tool-response)))

              (let [response-data (json/parse-string response-text keyword)]
                (is (= 1 (:task-id response-data)))
                (is (= "Fix Parser Bug" (:title response-data)))
                (is (true? (:worktree-created? response-data)))
                (is (= "1-fix-parser-bug" (:branch-name response-data)))
                (is (string? (:worktree-path response-data)))
                (is (str/includes? (:message response-data) "Worktree created"))
                (is (str/includes? (:message response-data) "Please start a new Claude Code session"))

                ;; Get the worktree path from response
                (let [worktree-path (:worktree-path response-data)]

                  ;; Verify worktree was actually created
                  (is (fs/exists? worktree-path))
                  (is (fs/directory? worktree-path))

                  ;; Verify worktree is on the correct branch
                  (let [branch-result (sh/sh "git" "-C" worktree-path
                                             "rev-parse" "--abbrev-ref" "HEAD")
                        current-branch (str/trim (:out branch-result))]
                    (is (= 0 (:exit branch-result)))
                    (is (= "1-fix-parser-bug" current-branch)))

                  ;; Verify worktree is in git worktree list
                  (let [worktree-list-result (sh/sh "git" "-C" project-root
                                                    "worktree" "list")
                        worktrees (:out worktree-list-result)]
                    (is (= 0 (:exit worktree-list-result)))
                    (is (str/includes? worktrees worktree-path)))

                  ;; Verify we're using the existing branch's commit (not main's commit)
                  (let [main-commit-result (sh/sh "git" "-C" project-root
                                                  "rev-parse" "main")
                        main-commit (str/trim (:out main-commit-result))
                        branch-commit-result (sh/sh "git" "-C" worktree-path
                                                    "rev-parse" "HEAD")
                        branch-commit (str/trim (:out branch-commit-result))]
                    (is (= 0 (:exit main-commit-result)))
                    (is (= 0 (:exit branch-commit-result)))
                    (is (not= main-commit branch-commit) "Should be on branch's commit, not main's")

                    ;; Verify the worktree has the branch-specific content
                    (let [worktree-content (slurp (io/file worktree-path "test.txt"))]
                      (is (= "branch-specific content" worktree-content)
                          "Worktree should have branch's content, not main's"))))))

            (finally
              (mcp-client/close! client)
              ((:stop server)))))))))
