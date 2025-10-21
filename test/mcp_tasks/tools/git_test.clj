(ns mcp-tasks.tools.git-test
  (:require
    [clojure.java.shell :as shell]
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.tools.git :as sut]))

(deftest get-current-branch-test
  ;; Tests git rev-parse --abbrev-ref HEAD behavior
  ;; Verifies current branch detection

  (testing "get-current-branch"
    (testing "returns branch name on success"
      (with-redefs [clojure.java.shell/sh (fn [& args]
                                            (is (= ["git" "-C" "/test/dir" "rev-parse" "--abbrev-ref" "HEAD"] args))
                                            {:exit 0
                                             :out "main\n"
                                             :err ""})]
        (let [result (sut/get-current-branch "/test/dir")]
          (is (true? (:success result)))
          (is (= "main" (:branch result)))
          (is (nil? (:error result))))))

    (testing "handles git error"
      (with-redefs [clojure.java.shell/sh (fn [& _]
                                            {:exit 1
                                             :out ""
                                             :err "fatal: not a git repository\n"})]
        (let [result (sut/get-current-branch "/test/dir")]
          (is (false? (:success result)))
          (is (nil? (:branch result)))
          (is (= "fatal: not a git repository" (:error result))))))

    (testing "handles exceptions"
      (with-redefs [clojure.java.shell/sh (fn [& _]
                                            (throw (Exception. "Connection failed")))]
        (let [result (sut/get-current-branch "/test/dir")]
          (is (false? (:success result)))
          (is (nil? (:branch result)))
          (is (= "Connection failed" (:error result))))))))

(deftest get-default-branch-test
  ;; Tests default branch detection with multiple fallback strategies
  ;; Verifies origin/HEAD reading and local fallbacks

  (testing "get-default-branch"
    (testing "returns branch from origin/HEAD when remote exists"
      (with-redefs [clojure.java.shell/sh (fn [cmd & args]
                                            (cond
                                              (and (= cmd "git") (= (nth args 2) "symbolic-ref"))
                                              {:exit 0
                                               :out "origin/main\n"
                                               :err ""}
                                              :else
                                              {:exit 1 :out "" :err ""}))]
        (let [result (sut/get-default-branch "/test/dir")]
          (is (true? (:success result)))
          (is (= "main" (:branch result)))
          (is (nil? (:error result))))))

    (testing "falls back to main when no remote"
      (with-redefs [clojure.java.shell/sh (fn [cmd & args]
                                            (cond
                                              (and (= cmd "git") (= (nth args 2) "symbolic-ref"))
                                              {:exit 1 :out "" :err ""}
                                              (and (= cmd "git") (= (nth args 2) "rev-parse") (= (nth args 3) "--verify") (= (nth args 4) "main"))
                                              {:exit 0 :out "" :err ""}
                                              :else
                                              {:exit 1 :out "" :err ""}))]
        (let [result (sut/get-default-branch "/test/dir")]
          (is (true? (:success result)))
          (is (= "main" (:branch result)))
          (is (nil? (:error result))))))

    (testing "falls back to master when main doesn't exist"
      (with-redefs [clojure.java.shell/sh (fn [cmd & args]
                                            (cond
                                              (and (= cmd "git") (= (nth args 2) "symbolic-ref"))
                                              {:exit 1 :out "" :err ""}
                                              (and (= cmd "git") (= (nth args 2) "rev-parse") (= (nth args 4) "main"))
                                              {:exit 1 :out "" :err ""}
                                              (and (= cmd "git") (= (nth args 2) "rev-parse") (= (nth args 4) "master"))
                                              {:exit 0 :out "" :err ""}
                                              :else
                                              {:exit 1 :out "" :err ""}))]
        (let [result (sut/get-default-branch "/test/dir")]
          (is (true? (:success result)))
          (is (= "master" (:branch result)))
          (is (nil? (:error result))))))

    (testing "returns error when no branch found"
      (with-redefs [clojure.java.shell/sh (fn [& _] {:exit 1 :out "" :err ""})]
        (let [result (sut/get-default-branch "/test/dir")]
          (is (false? (:success result)))
          (is (nil? (:branch result)))
          (is (= "Could not determine default branch" (:error result))))))))

(deftest check-uncommitted-changes-test
  ;; Tests uncommitted changes detection via git status --porcelain
  ;; Verifies clean and dirty working directory states

  (testing "check-uncommitted-changes"
    (testing "returns false when working directory is clean"
      (with-redefs [clojure.java.shell/sh (fn [& args]
                                            (is (= ["git" "-C" "/test/dir" "status" "--porcelain"] args))
                                            {:exit 0
                                             :out ""
                                             :err ""})]
        (let [result (sut/check-uncommitted-changes "/test/dir")]
          (is (true? (:success result)))
          (is (false? (:has-changes? result)))
          (is (nil? (:error result))))))

    (testing "returns true when there are uncommitted changes"
      (with-redefs [clojure.java.shell/sh (fn [& _]
                                            {:exit 0
                                             :out " M file.txt\n"
                                             :err ""})]
        (let [result (sut/check-uncommitted-changes "/test/dir")]
          (is (true? (:success result)))
          (is (true? (:has-changes? result)))
          (is (nil? (:error result))))))

    (testing "handles git error"
      (with-redefs [clojure.java.shell/sh (fn [& _]
                                            {:exit 1
                                             :out ""
                                             :err "fatal: error\n"})]
        (let [result (sut/check-uncommitted-changes "/test/dir")]
          (is (false? (:success result)))
          (is (nil? (:has-changes? result)))
          (is (= "fatal: error" (:error result))))))))

(deftest branch-exists-test
  ;; Tests branch existence checking via git rev-parse --verify
  ;; Verifies detection of existing and non-existing branches

  (testing "branch-exists?"
    (testing "returns true when branch exists"
      (with-redefs [clojure.java.shell/sh (fn [& args]
                                            (is (= ["git" "-C" "/test/dir" "rev-parse" "--verify" "feature-branch"] args))
                                            {:exit 0
                                             :out "abc123\n"
                                             :err ""})]
        (let [result (sut/branch-exists? "/test/dir" "feature-branch")]
          (is (true? (:success result)))
          (is (true? (:exists? result)))
          (is (nil? (:error result))))))

    (testing "returns false when branch doesn't exist"
      (with-redefs [clojure.java.shell/sh (fn [& _]
                                            {:exit 1
                                             :out ""
                                             :err ""})]
        (let [result (sut/branch-exists? "/test/dir" "non-existent")]
          (is (true? (:success result)))
          (is (false? (:exists? result)))
          (is (nil? (:error result))))))))

(deftest checkout-branch-test
  ;; Tests branch checkout via git checkout
  ;; Verifies successful and failed checkout operations

  (testing "checkout-branch"
    (testing "succeeds when checkout works"
      (with-redefs [clojure.java.shell/sh (fn [& args]
                                            (is (= ["git" "-C" "/test/dir" "checkout" "main"] args))
                                            {:exit 0
                                             :out "Switched to branch 'main'\n"
                                             :err ""})]
        (let [result (sut/checkout-branch "/test/dir" "main")]
          (is (true? (:success result)))
          (is (nil? (:error result))))))

    (testing "fails when checkout fails"
      (with-redefs [clojure.java.shell/sh (fn [& _]
                                            {:exit 1
                                             :out ""
                                             :err "error: pathspec 'main' did not match\n"})]
        (let [result (sut/checkout-branch "/test/dir" "main")]
          (is (false? (:success result)))
          (is (= "error: pathspec 'main' did not match" (:error result))))))))

(deftest create-and-checkout-branch-test
  ;; Tests branch creation via git checkout -b
  ;; Verifies successful and failed branch creation

  (testing "create-and-checkout-branch"
    (testing "succeeds when creation works"
      (with-redefs [clojure.java.shell/sh (fn [& args]
                                            (is (= ["git" "-C" "/test/dir" "checkout" "-b" "new-feature"] args))
                                            {:exit 0
                                             :out "Switched to a new branch 'new-feature'\n"
                                             :err ""})]
        (let [result (sut/create-and-checkout-branch "/test/dir" "new-feature")]
          (is (true? (:success result)))
          (is (nil? (:error result))))))

    (testing "fails when branch already exists"
      (with-redefs [clojure.java.shell/sh (fn [& _]
                                            {:exit 1
                                             :out ""
                                             :err "fatal: A branch named 'existing' already exists.\n"})]
        (let [result (sut/create-and-checkout-branch "/test/dir" "existing")]
          (is (false? (:success result)))
          (is (= "fatal: A branch named 'existing' already exists." (:error result))))))))

(deftest pull-latest-test
  ;; Tests pulling latest changes from remote
  ;; Verifies handling of both remote-connected and local-only repos

  (testing "pull-latest"
    (testing "succeeds when pull works"
      (with-redefs [clojure.java.shell/sh (fn [& args]
                                            (is (= ["git" "-C" "/test/dir" "pull" "origin" "main"] args))
                                            {:exit 0
                                             :out "Already up to date.\n"
                                             :err ""})]
        (let [result (sut/pull-latest "/test/dir" "main")]
          (is (true? (:success result)))
          (is (true? (:pulled? result)))
          (is (nil? (:error result))))))

    (testing "handles local-only repo gracefully"
      (with-redefs [clojure.java.shell/sh (fn [& _]
                                            {:exit 1
                                             :out ""
                                             :err "fatal: 'origin' does not appear to be a git repository\n"})]
        (let [result (sut/pull-latest "/test/dir" "main")]
          (is (true? (:success result)))
          (is (false? (:pulled? result)))
          (is (nil? (:error result))))))

    (testing "handles exceptions gracefully"
      (with-redefs [clojure.java.shell/sh (fn [& _]
                                            (throw (Exception. "Network error")))]
        (let [result (sut/pull-latest "/test/dir" "main")]
          (is (true? (:success result)))
          (is (false? (:pulled? result)))
          (is (nil? (:error result))))))))

(deftest derive-project-name-test
  ;; Tests project name extraction from directory paths
  ;; Verifies correct extraction and error handling

  (testing "derive-project-name"
    (testing "extracts project name from path"
      (let [result (sut/derive-project-name "/Users/test/projects/mcp-tasks")]
        (is (true? (:success result)))
        (is (= "mcp-tasks" (:name result)))
        (is (nil? (:error result)))))

    (testing "handles relative paths"
      (let [result (sut/derive-project-name "mcp-tasks")]
        (is (true? (:success result)))
        (is (= "mcp-tasks" (:name result)))
        (is (nil? (:error result)))))

    (testing "handles paths with trailing slash"
      (let [result (sut/derive-project-name "/Users/test/projects/mcp-tasks/")]
        (is (true? (:success result)))
        (is (= "mcp-tasks" (:name result)))
        (is (nil? (:error result)))))))

(deftest derive-worktree-path-test
  ;; Tests worktree path generation from titles
  ;; Verifies sanitization and path construction

  (testing "derive-worktree-path"
    (testing "generates path from simple title"
      (let [result (sut/derive-worktree-path "/Users/test/mcp-tasks" "fix parser bug")]
        (is (true? (:success result)))
        (is (= "/Users/test/mcp-tasks-fix-parser-bug" (:path result)))
        (is (nil? (:error result)))))

    (testing "sanitizes title with special characters"
      (let [result (sut/derive-worktree-path "/Users/test/mcp-tasks" "Add Git Worktree Management (Option)!")]
        (is (true? (:success result)))
        (is (= "/Users/test/mcp-tasks-add-git-worktree-management-option" (:path result)))
        (is (nil? (:error result)))))

    (testing "handles multiple spaces"
      (let [result (sut/derive-worktree-path "/Users/test/mcp-tasks" "fix    multiple   spaces")]
        (is (true? (:success result)))
        (is (= "/Users/test/mcp-tasks-fix-multiple-spaces" (:path result)))
        (is (nil? (:error result)))))

    (testing "fails on empty title after sanitization"
      (let [result (sut/derive-worktree-path "/Users/test/mcp-tasks" "!!!")]
        (is (false? (:success result)))
        (is (nil? (:path result)))
        (is (= "Title produced empty path after sanitization" (:error result)))))))

(deftest list-worktrees-test
  ;; Tests worktree listing via git worktree list --porcelain
  ;; Verifies parsing of porcelain format output

  (testing "list-worktrees"
    (testing "parses single worktree"
      (with-redefs [clojure.java.shell/sh (fn [& args]
                                            (is (= ["git" "-C" "/test/dir" "worktree" "list" "--porcelain"] args))
                                            {:exit 0
                                             :out "worktree /test/dir\nHEAD abc123\nbranch refs/heads/main\n\n"
                                             :err ""})]
        (let [result (sut/list-worktrees "/test/dir")]
          (is (true? (:success result)))
          (is (= 1 (count (:worktrees result))))
          (is (= "/test/dir" (-> result :worktrees first :path)))
          (is (= "main" (-> result :worktrees first :branch)))
          (is (= "abc123" (-> result :worktrees first :head)))
          (is (nil? (:error result))))))

    (testing "parses multiple worktrees"
      (with-redefs [clojure.java.shell/sh (fn [& _]
                                            {:exit 0
                                             :out "worktree /test/dir\nHEAD abc123\nbranch refs/heads/main\n\nworktree /test/feature\nHEAD def456\nbranch refs/heads/feature-x\n\n"
                                             :err ""})]
        (let [result (sut/list-worktrees "/test/dir")]
          (is (true? (:success result)))
          (is (= 2 (count (:worktrees result))))
          (is (= "main" (-> result :worktrees first :branch)))
          (is (= "feature-x" (-> result :worktrees second :branch)))
          (is (nil? (:error result))))))

    (testing "parses detached HEAD"
      (with-redefs [clojure.java.shell/sh (fn [& _]
                                            {:exit 0
                                             :out "worktree /test/dir\nHEAD abc123\ndetached\n\n"
                                             :err ""})]
        (let [result (sut/list-worktrees "/test/dir")]
          (is (true? (:success result)))
          (is (= 1 (count (:worktrees result))))
          (is (true? (-> result :worktrees first :detached)))
          (is (nil? (-> result :worktrees first :branch)))
          (is (nil? (:error result))))))

    (testing "handles empty worktree list"
      (with-redefs [clojure.java.shell/sh (fn [& _]
                                            {:exit 0
                                             :out ""
                                             :err ""})]
        (let [result (sut/list-worktrees "/test/dir")]
          (is (true? (:success result)))
          (is (= [] (:worktrees result)))
          (is (nil? (:error result))))))

    (testing "fails on git error"
      (with-redefs [clojure.java.shell/sh (fn [& _]
                                            {:exit 1
                                             :out ""
                                             :err "fatal: not a git repository\n"})]
        (let [result (sut/list-worktrees "/test/dir")]
          (is (false? (:success result)))
          (is (nil? (:worktrees result)))
          (is (= "fatal: not a git repository" (:error result))))))))

(deftest worktree-exists-test
  ;; Tests worktree existence checking
  ;; Verifies detection based on list-worktrees output

  (testing "worktree-exists?"
    (testing "returns true when worktree exists"
      (with-redefs [sut/list-worktrees (fn [_]
                                         {:success true
                                          :worktrees [{:path "/test/feature" :branch "feature-x" :head "abc123"}]
                                          :error nil})]
        (let [result (sut/worktree-exists? "/test/dir" "/test/feature")]
          (is (true? (:success result)))
          (is (true? (:exists? result)))
          (is (= "feature-x" (-> result :worktree :branch)))
          (is (nil? (:error result))))))

    (testing "returns false when worktree doesn't exist"
      (with-redefs [sut/list-worktrees (fn [_]
                                         {:success true
                                          :worktrees [{:path "/test/other" :branch "main" :head "def456"}]
                                          :error nil})]
        (let [result (sut/worktree-exists? "/test/dir" "/test/missing")]
          (is (true? (:success result)))
          (is (false? (:exists? result)))
          (is (nil? (:worktree result)))
          (is (nil? (:error result))))))

    (testing "propagates list-worktrees error"
      (with-redefs [sut/list-worktrees (fn [_]
                                         {:success false
                                          :worktrees nil
                                          :error "git error"})]
        (let [result (sut/worktree-exists? "/test/dir" "/test/feature")]
          (is (false? (:success result)))
          (is (nil? (:exists? result)))
          (is (nil? (:worktree result))))))))

(deftest worktree-branch-test
  ;; Tests branch name retrieval for worktrees
  ;; Verifies detection of normal and detached HEAD states

  (testing "worktree-branch"
    (testing "returns branch name"
      (with-redefs [clojure.java.shell/sh (fn [& args]
                                            (is (= ["git" "-C" "/test/feature" "rev-parse" "--abbrev-ref" "HEAD"] args))
                                            {:exit 0
                                             :out "feature-x\n"
                                             :err ""})]
        (let [result (sut/worktree-branch "/test/feature")]
          (is (true? (:success result)))
          (is (= "feature-x" (:branch result)))
          (is (false? (:detached? result)))
          (is (nil? (:error result))))))

    (testing "detects detached HEAD"
      (with-redefs [clojure.java.shell/sh (fn [& _]
                                            {:exit 0
                                             :out "HEAD\n"
                                             :err ""})]
        (let [result (sut/worktree-branch "/test/feature")]
          (is (true? (:success result)))
          (is (nil? (:branch result)))
          (is (true? (:detached? result)))
          (is (nil? (:error result))))))

    (testing "fails on git error"
      (with-redefs [clojure.java.shell/sh (fn [& _]
                                            {:exit 1
                                             :out ""
                                             :err "fatal: not a git repository\n"})]
        (let [result (sut/worktree-branch "/test/feature")]
          (is (false? (:success result)))
          (is (nil? (:branch result)))
          (is (nil? (:detached? result)))
          (is (= "fatal: not a git repository" (:error result))))))))

(deftest create-worktree-test
  ;; Tests worktree creation via git worktree add
  ;; Verifies successful and failed creation scenarios

  (testing "create-worktree"
    (testing "succeeds when creation works"
      (with-redefs [clojure.java.shell/sh (fn [& args]
                                            (is (= ["git" "-C" "/test/dir" "worktree" "add" "/test/feature" "feature-x"] args))
                                            {:exit 0
                                             :out "Preparing worktree\n"
                                             :err ""})]
        (let [result (sut/create-worktree "/test/dir" "/test/feature" "feature-x")]
          (is (true? (:success result)))
          (is (nil? (:error result))))))

    (testing "fails when path already exists"
      (with-redefs [clojure.java.shell/sh (fn [& _]
                                            {:exit 1
                                             :out ""
                                             :err "fatal: '/test/feature' already exists\n"})]
        (let [result (sut/create-worktree "/test/dir" "/test/feature" "feature-x")]
          (is (false? (:success result)))
          (is (= "fatal: '/test/feature' already exists" (:error result))))))

    (testing "fails when branch doesn't exist"
      (with-redefs [clojure.java.shell/sh (fn [& _]
                                            {:exit 1
                                             :out ""
                                             :err "fatal: invalid reference: missing-branch\n"})]
        (let [result (sut/create-worktree "/test/dir" "/test/feature" "missing-branch")]
          (is (false? (:success result)))
          (is (= "fatal: invalid reference: missing-branch" (:error result))))))))

(deftest remove-worktree-test
  ;; Tests worktree removal via git worktree remove
  ;; Verifies successful and failed removal scenarios

  (testing "remove-worktree"
    (testing "succeeds when removal works"
      (with-redefs [clojure.java.shell/sh (fn [& args]
                                            (is (= ["git" "-C" "/test/dir" "worktree" "remove" "/test/feature"] args))
                                            {:exit 0
                                             :out ""
                                             :err ""})]
        (let [result (sut/remove-worktree "/test/dir" "/test/feature")]
          (is (true? (:success result)))
          (is (nil? (:error result))))))

    (testing "fails when worktree has changes"
      (with-redefs [clojure.java.shell/sh (fn [& _]
                                            {:exit 1
                                             :out ""
                                             :err "fatal: '/test/feature' contains modified or untracked files\n"})]
        (let [result (sut/remove-worktree "/test/dir" "/test/feature")]
          (is (false? (:success result)))
          (is (= "fatal: '/test/feature' contains modified or untracked files" (:error result))))))

    (testing "fails when worktree doesn't exist"
      (with-redefs [clojure.java.shell/sh (fn [& _]
                                            {:exit 1
                                             :out ""
                                             :err "fatal: '/test/missing' is not a working tree\n"})]
        (let [result (sut/remove-worktree "/test/dir" "/test/missing")]
          (is (false? (:success result)))
          (is (= "fatal: '/test/missing' is not a working tree" (:error result))))))))
