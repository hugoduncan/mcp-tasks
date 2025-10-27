(ns mcp-tasks.tools.git-test
  (:require
    [clojure.java.io :as io]
    [clojure.java.shell :as shell]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.tools.git :as sut]))

(deftest in-worktree-test
  ;; Tests worktree detection by checking .git file vs directory
  ;; Verifies correct identification of worktree environments

  (testing "in-worktree?"
    (testing "detects current directory type correctly"
      ;; Test works whether running in worktree or regular repo
      ;; Just verify the function returns a boolean based on .git structure
      (let [current-dir (System/getProperty "user.dir")
            git-file (io/file current-dir ".git")
            is-worktree (.isFile git-file)
            is-directory (.isDirectory git-file)]

        ;; Should return true if .git is a file, false if .git is a directory
        (if is-worktree
          (is (true? (sut/in-worktree? current-dir))
              "Should detect worktree when .git is a file")
          (when is-directory
            (is (false? (sut/in-worktree? current-dir))
                "Should detect non-worktree when .git is a directory")))))

    (testing "returns false when .git does not exist"
      ;; Test with a directory that has no .git
      (let [temp-dir (System/getProperty "java.io.tmpdir")]
        (is (false? (sut/in-worktree? temp-dir)))))))

(deftest find-main-repo-test
  ;; Tests extraction of main repository path from worktree .git file
  ;; Verifies correct parsing and path resolution

  (testing "find-main-repo"
    (testing "extracts main repo path from worktree"
      (let [current-dir (System/getProperty "user.dir")]
        ;; Only run this test if we're in a worktree
        (when (sut/in-worktree? current-dir)
          (let [main-repo (sut/find-main-repo current-dir)]
            (is (string? main-repo))
            (is (not (sut/in-worktree? main-repo)))
            ;; Verify the main repo has a .git directory
            (is (.isDirectory (io/file main-repo ".git")))))))

    (testing "throws when called on non-worktree"
      (let [current-dir (System/getProperty "user.dir")
            main-repo (sut/get-main-repo-dir current-dir)]
        ;; Should throw because precondition fails
        (is (thrown? AssertionError
              (sut/find-main-repo main-repo)))))))

(deftest get-main-repo-dir-test
  ;; Tests unified main repo directory resolution
  ;; Verifies correct handling of both worktree and main repo paths

  (testing "get-main-repo-dir"
    (testing "returns main repo path when given worktree"
      (let [current-dir (System/getProperty "user.dir")]
        (when (sut/in-worktree? current-dir)
          (let [main-repo (sut/get-main-repo-dir current-dir)]
            (is (string? main-repo))
            (is (not (sut/in-worktree? main-repo)))))))

    (testing "returns same path when given main repo"
      (let [current-dir (System/getProperty "user.dir")
            main-repo (sut/get-main-repo-dir current-dir)
            result (sut/get-main-repo-dir main-repo)]
        (is (= main-repo result))))

    (testing "normalizes path to canonical form"
      (let [current-dir (System/getProperty "user.dir")
            main-repo (sut/get-main-repo-dir current-dir)]
        ;; Result should be absolute path
        (is (.isAbsolute (io/file main-repo)))))))

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

(deftest check-all-pushed-test
  ;; Tests verification that all commits are pushed to remote
  ;; Verifies various scenarios: all pushed, unpushed commits, no remote, errors

  (testing "check-all-pushed?"
    (testing "returns true when all commits are pushed"
      (with-redefs [clojure.java.shell/sh
                    (fn [& args]
                      (cond
                        ;; Check for tracking branch
                        (= ["git" "-C" "/test/dir" "rev-parse" "--abbrev-ref" "@{u}"] args)
                        {:exit 0
                         :out "origin/main\n"
                         :err ""}
                        ;; Check for unpushed commits
                        (= ["git" "-C" "/test/dir" "rev-list" "@{u}..HEAD"] args)
                        {:exit 0
                         :out ""
                         :err ""}))]
        (let [result (sut/check-all-pushed? "/test/dir")]
          (is (true? (:success result)))
          (is (true? (:all-pushed? result)))
          (is (= "All commits are pushed to remote" (:reason result))))))

    (testing "returns false when unpushed commits exist"
      (with-redefs [clojure.java.shell/sh
                    (fn [& args]
                      (cond
                        (= ["git" "-C" "/test/dir" "rev-parse" "--abbrev-ref" "@{u}"] args)
                        {:exit 0
                         :out "origin/main\n"
                         :err ""}
                        (= ["git" "-C" "/test/dir" "rev-list" "@{u}..HEAD"] args)
                        {:exit 0
                         :out "abc123\ndef456\n"
                         :err ""}))]
        (let [result (sut/check-all-pushed? "/test/dir")]
          (is (true? (:success result)))
          (is (false? (:all-pushed? result)))
          (is (= "Unpushed commits exist" (:reason result))))))

    (testing "returns false when no remote tracking branch configured"
      (with-redefs [clojure.java.shell/sh
                    (fn [& args]
                      (when (= ["git" "-C" "/test/dir" "rev-parse" "--abbrev-ref" "@{u}"] args)
                        {:exit 128
                         :out ""
                         :err "fatal: no upstream configured for branch 'main'\n"}))]
        (let [result (sut/check-all-pushed? "/test/dir")]
          (is (true? (:success result)))
          (is (false? (:all-pushed? result)))
          (is (= "No remote tracking branch configured" (:reason result))))))

    (testing "handles git error checking tracking branch"
      (with-redefs [clojure.java.shell/sh
                    (fn [& args]
                      (when (= ["git" "-C" "/test/dir" "rev-parse" "--abbrev-ref" "@{u}"] args)
                        {:exit 128
                         :out ""
                         :err "fatal: not a git repository\n"}))]
        (let [result (sut/check-all-pushed? "/test/dir")]
          (is (false? (:success result)))
          (is (nil? (:all-pushed? result)))
          (is (str/includes? (:reason result) "Failed to check tracking branch")))))

    (testing "handles git error checking unpushed commits"
      (with-redefs [clojure.java.shell/sh
                    (fn [& args]
                      (cond
                        (= ["git" "-C" "/test/dir" "rev-parse" "--abbrev-ref" "@{u}"] args)
                        {:exit 0
                         :out "origin/main\n"
                         :err ""}
                        (= ["git" "-C" "/test/dir" "rev-list" "@{u}..HEAD"] args)
                        {:exit 1
                         :out ""
                         :err "fatal: bad revision\n"}))]
        (let [result (sut/check-all-pushed? "/test/dir")]
          (is (false? (:success result)))
          (is (nil? (:all-pushed? result)))
          (is (str/includes? (:reason result) "Failed to check commits")))))

    (testing "handles exceptions"
      (with-redefs [clojure.java.shell/sh
                    (fn [& _]
                      (throw (Exception. "Network error")))]
        (let [result (sut/check-all-pushed? "/test/dir")]
          (is (false? (:success result)))
          (is (nil? (:all-pushed? result)))
          (is (str/includes? (:reason result) "Exception checking pushed status")))))))

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
  ;; Verifies error type detection for different failure scenarios

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
          (is (nil? (:error result)))
          (is (nil? (:error-type result))))))

    (testing "no-remote error detection"
      (testing "handles local-only repo gracefully (no remote configured)"
        (with-redefs [clojure.java.shell/sh (fn [& _]
                                              {:exit 1
                                               :out ""
                                               :err "fatal: 'origin' does not appear to be a git repository\n"})]
          (let [result (sut/pull-latest "/test/dir" "main")]
            (is (true? (:success result)))
            (is (false? (:pulled? result)))
            (is (nil? (:error result)))
            (is (= :no-remote (:error-type result))))))

      (testing "handles local-only repo with 'No configured push destination' message"
        (with-redefs [clojure.java.shell/sh (fn [& _]
                                              {:exit 1
                                               :out ""
                                               :err "fatal: No configured push destination\n"})]
          (let [result (sut/pull-latest "/test/dir" "main")]
            (is (true? (:success result)))
            (is (false? (:pulled? result)))
            (is (nil? (:error result)))
            (is (= :no-remote (:error-type result))))))

      (testing "detects 'No remote repository specified' with exit 128"
        (with-redefs [clojure.java.shell/sh (fn [& _]
                                              {:exit 128
                                               :out ""
                                               :err "fatal: No remote repository specified\n"})]
          (let [result (sut/pull-latest "/test/dir" "main")]
            (is (true? (:success result)))
            (is (false? (:pulled? result)))
            (is (nil? (:error result)))
            (is (= :no-remote (:error-type result))))))

      (testing "detects repository not found pattern"
        (with-redefs [clojure.java.shell/sh (fn [& _]
                                              {:exit 128
                                               :out ""
                                               :err "fatal: repository 'https://github.com/user/repo.git' not found\n"})]
          (let [result (sut/pull-latest "/test/dir" "main")]
            (is (true? (:success result)))
            (is (false? (:pulled? result)))
            (is (nil? (:error result)))
            (is (= :no-remote (:error-type result)))))))

    (testing "conflict detection"
      (testing "detects merge conflicts with CONFLICT pattern"
        (with-redefs [clojure.java.shell/sh (fn [& _]
                                              {:exit 1
                                               :out ""
                                               :err "CONFLICT (content): Merge conflict in file.txt\n"})]
          (let [result (sut/pull-latest "/test/dir" "main")]
            (is (false? (:success result)))
            (is (false? (:pulled? result)))
            (is (= "CONFLICT (content): Merge conflict in file.txt" (:error result)))
            (is (= :conflict (:error-type result))))))

      (testing "detects conflicts with 'Automatic merge failed' pattern"
        (with-redefs [clojure.java.shell/sh (fn [& _]
                                              {:exit 1
                                               :out ""
                                               :err "Automatic merge failed; fix conflicts and then commit the result.\n"})]
          (let [result (sut/pull-latest "/test/dir" "main")]
            (is (false? (:success result)))
            (is (false? (:pulled? result)))
            (is (= "Automatic merge failed; fix conflicts and then commit the result." (:error result)))
            (is (= :conflict (:error-type result))))))

      (testing "detects conflicts with 'fix conflicts' pattern"
        (with-redefs [clojure.java.shell/sh (fn [& _]
                                              {:exit 1
                                               :out ""
                                               :err "error: Pulling is not possible because you have unmerged files.\nhint: Fix them up in the work tree, and then use 'git add/rm <file>'\nhint: as appropriate to mark resolution and make a commit.\nfatal: Exiting because of an unresolved conflict.\n"})]
          (let [result (sut/pull-latest "/test/dir" "main")]
            (is (false? (:success result)))
            (is (false? (:pulled? result)))
            (is (= :conflict (:error-type result)))))))

    (testing "network error detection"
      (testing "detects network errors - could not resolve host with exit 128"
        (with-redefs [clojure.java.shell/sh (fn [& _]
                                              {:exit 128
                                               :out ""
                                               :err "fatal: Could not resolve host: github.com\n"})]
          (let [result (sut/pull-latest "/test/dir" "main")]
            (is (false? (:success result)))
            (is (false? (:pulled? result)))
            (is (= "fatal: Could not resolve host: github.com" (:error result)))
            (is (= :network (:error-type result))))))

      (testing "detects network errors - connection refused with exit 128"
        (with-redefs [clojure.java.shell/sh (fn [& _]
                                              {:exit 128
                                               :out ""
                                               :err "fatal: Connection refused\n"})]
          (let [result (sut/pull-latest "/test/dir" "main")]
            (is (false? (:success result)))
            (is (false? (:pulled? result)))
            (is (= "fatal: Connection refused" (:error result)))
            (is (= :network (:error-type result))))))

      (testing "detects 'Failed to connect' pattern"
        (with-redefs [clojure.java.shell/sh (fn [& _]
                                              {:exit 128
                                               :out ""
                                               :err "fatal: Failed to connect to github.com\n"})]
          (let [result (sut/pull-latest "/test/dir" "main")]
            (is (false? (:success result)))
            (is (false? (:pulled? result)))
            (is (= :network (:error-type result))))))

      (testing "detects 'timed out' pattern"
        (with-redefs [clojure.java.shell/sh (fn [& _]
                                              {:exit 128
                                               :out ""
                                               :err "fatal: unable to access 'https://github.com/user/repo.git/': Operation timed out\n"})]
          (let [result (sut/pull-latest "/test/dir" "main")]
            (is (false? (:success result)))
            (is (false? (:pulled? result)))
            (is (= :network (:error-type result))))))

      (testing "detects 'Network is unreachable' pattern"
        (with-redefs [clojure.java.shell/sh (fn [& _]
                                              {:exit 128
                                               :out ""
                                               :err "fatal: Network is unreachable\n"})]
          (let [result (sut/pull-latest "/test/dir" "main")]
            (is (false? (:success result)))
            (is (false? (:pulled? result)))
            (is (= :network (:error-type result))))))

      (testing "detects 'unable to access' pattern"
        (with-redefs [clojure.java.shell/sh (fn [& _]
                                              {:exit 128
                                               :out ""
                                               :err "fatal: unable to access 'https://github.com/user/repo.git/': Could not resolve host: github.com\n"})]
          (let [result (sut/pull-latest "/test/dir" "main")]
            (is (false? (:success result)))
            (is (false? (:pulled? result)))
            (is (= :network (:error-type result))))))

      (testing "detects 'The requested URL returned error' pattern"
        (with-redefs [clojure.java.shell/sh (fn [& _]
                                              {:exit 128
                                               :out ""
                                               :err "error: The requested URL returned error: 503\n"})]
          (let [result (sut/pull-latest "/test/dir" "main")]
            (is (false? (:success result)))
            (is (false? (:pulled? result)))
            (is (= :network (:error-type result)))))))

    (testing "other error types"
      (testing "detects other errors with exit 1"
        (with-redefs [clojure.java.shell/sh (fn [& _]
                                              {:exit 1
                                               :out ""
                                               :err "fatal: some other error\n"})]
          (let [result (sut/pull-latest "/test/dir" "main")]
            (is (false? (:success result)))
            (is (false? (:pulled? result)))
            (is (= "fatal: some other error" (:error result)))
            (is (= :other (:error-type result))))))

      (testing "detects other errors with exit 128"
        (with-redefs [clojure.java.shell/sh (fn [& _]
                                              {:exit 128
                                               :out ""
                                               :err "fatal: unexpected error\n"})]
          (let [result (sut/pull-latest "/test/dir" "main")]
            (is (false? (:success result)))
            (is (false? (:pulled? result)))
            (is (= "fatal: unexpected error" (:error result)))
            (is (= :other (:error-type result))))))

      (testing "handles exceptions"
        (with-redefs [clojure.java.shell/sh (fn [& _]
                                              (throw (Exception. "Network error")))]
          (let [result (sut/pull-latest "/test/dir" "main")]
            (is (false? (:success result)))
            (is (false? (:pulled? result)))
            (is (= "Network error" (:error result)))
            (is (= :other (:error-type result)))))))

    (testing "fallback pattern matching for unknown exit codes"
      (testing "detects conflict with unknown exit code"
        (with-redefs [clojure.java.shell/sh (fn [& _]
                                              {:exit 99
                                               :out ""
                                               :err "CONFLICT (content): Merge conflict\n"})]
          (let [result (sut/pull-latest "/test/dir" "main")]
            (is (false? (:success result)))
            (is (false? (:pulled? result)))
            (is (= :conflict (:error-type result))))))

      (testing "detects no-remote with unknown exit code"
        (with-redefs [clojure.java.shell/sh (fn [& _]
                                              {:exit 99
                                               :out ""
                                               :err "fatal: No configured push destination\n"})]
          (let [result (sut/pull-latest "/test/dir" "main")]
            (is (true? (:success result)))
            (is (false? (:pulled? result)))
            (is (nil? (:error result)))
            (is (= :no-remote (:error-type result))))))

      (testing "detects network error with unknown exit code"
        (with-redefs [clojure.java.shell/sh (fn [& _]
                                              {:exit 99
                                               :out ""
                                               :err "fatal: Connection refused\n"})]
          (let [result (sut/pull-latest "/test/dir" "main")]
            (is (false? (:success result)))
            (is (false? (:pulled? result)))
            (is (= :network (:error-type result)))))))))

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
  ;; Tests worktree path generation with task ID prefix and word limiting
  ;; Verifies sanitization and path construction with different prefix modes

  (testing "derive-worktree-path"
    (testing "with :project-name prefix (default)"
      (testing "generates path with ID prefix from simple title"
        (let [result (sut/derive-worktree-path "/Users/test/mcp-tasks" "fix parser bug" 123
                                               {:worktree-prefix :project-name :branch-title-words 4})]
          (is (true? (:success result)))
          (is (= "/Users/test/mcp-tasks-123-fix-parser-bug" (:path result)))
          (is (nil? (:error result)))))

      (testing "sanitizes title with special characters and includes ID"
        (let [result (sut/derive-worktree-path "/Users/test/mcp-tasks" "Add Git Worktree Management (Option)!" 456
                                               {:worktree-prefix :project-name :branch-title-words 4})]
          (is (true? (:success result)))
          (is (= "/Users/test/mcp-tasks-456-add-git-worktree-management" (:path result)))
          (is (nil? (:error result)))))

      (testing "respects word limit configuration"
        (let [result (sut/derive-worktree-path "/Users/test/mcp-tasks" "fix parser bug in production" 789
                                               {:worktree-prefix :project-name :branch-title-words 2})]
          (is (true? (:success result)))
          (is (= "/Users/test/mcp-tasks-789-fix-parser" (:path result)))
          (is (nil? (:error result)))))

      (testing "uses default word limit of 4 when not configured"
        (let [result (sut/derive-worktree-path "/Users/test/mcp-tasks" "one two three four five" 100
                                               {:worktree-prefix :project-name})]
          (is (true? (:success result)))
          (is (= "/Users/test/mcp-tasks-100-one-two-three-four" (:path result)))
          (is (nil? (:error result)))))

      (testing "handles multiple spaces"
        (let [result (sut/derive-worktree-path "/Users/test/mcp-tasks" "fix    multiple   spaces" 999
                                               {:worktree-prefix :project-name :branch-title-words 4})]
          (is (true? (:success result)))
          (is (= "/Users/test/mcp-tasks-999-fix-multiple-spaces" (:path result)))
          (is (nil? (:error result))))))

    (testing "with :none prefix"
      (testing "generates path without project name"
        (let [result (sut/derive-worktree-path "/Users/test/mcp-tasks" "fix parser bug" 123
                                               {:worktree-prefix :none :branch-title-words 4})]
          (is (true? (:success result)))
          (is (= "/Users/test/123-fix-parser-bug" (:path result)))
          (is (nil? (:error result)))))

      (testing "sanitizes title with special characters"
        (let [result (sut/derive-worktree-path "/Users/test/mcp-tasks" "Add Git Worktree Management (Option)!" 456
                                               {:worktree-prefix :none :branch-title-words 4})]
          (is (true? (:success result)))
          (is (= "/Users/test/456-add-git-worktree-management" (:path result)))
          (is (nil? (:error result)))))

      (testing "respects word limit configuration"
        (let [result (sut/derive-worktree-path "/Users/test/mcp-tasks" "fix parser bug in production" 789
                                               {:worktree-prefix :none :branch-title-words 2})]
          (is (true? (:success result)))
          (is (= "/Users/test/789-fix-parser" (:path result)))
          (is (nil? (:error result)))))

      (testing "handles multiple spaces"
        (let [result (sut/derive-worktree-path "/Users/test/mcp-tasks" "fix    multiple   spaces" 999
                                               {:worktree-prefix :none :branch-title-words 4})]
          (is (true? (:success result)))
          (is (= "/Users/test/999-fix-multiple-spaces" (:path result)))
          (is (nil? (:error result))))))

    (testing "with relative paths (demonstrates why config must canonicalize)"
      (testing "relative path produces incorrect path rooted at filesystem root"
        (let [result (sut/derive-worktree-path "." "fix parser bug" 5
                                               {:worktree-prefix :none :branch-title-words 4})]
          ;; Function succeeds but produces bad path at root (/) instead of proper location
          ;; This is why resolve-config must canonicalize base-dir before passing to git functions
          (is (true? (:success result)))
          (is (= "/5-fix-parser-bug" (:path result)))
          (is (nil? (:error result))))))

    (testing "uses default :project-name when config missing key"
      (let [result (sut/derive-worktree-path "/Users/test/mcp-tasks" "fix parser bug" 42 {})]
        (is (true? (:success result)))
        (is (= "/Users/test/mcp-tasks-42-fix-parser-bug" (:path result)))
        (is (nil? (:error result)))))

    (testing "handles empty title after sanitization"
      (let [result (sut/derive-worktree-path "/Users/test/mcp-tasks" "!!!" 99
                                             {:worktree-prefix :project-name})]
        (is (true? (:success result)))
        ;; sanitize-branch-name returns "task-99" as fallback for empty titles
        (is (= "/Users/test/mcp-tasks-task-99" (:path result)))
        (is (nil? (:error result)))))))

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

(deftest find-worktree-for-branch-test
  ;; Tests finding a worktree by branch name
  ;; Verifies correct handling of matches, no matches, and errors

  (testing "find-worktree-for-branch"
    (testing "finds branch in a worktree"
      (with-redefs [mcp-tasks.tools.git/list-worktrees
                    (fn [project-dir]
                      (is (= "/test/dir" project-dir))
                      {:success true
                       :worktrees [{:path "/test/dir"
                                    :head "abc123"
                                    :branch "main"}
                                   {:path "/test/feature"
                                    :head "def456"
                                    :branch "feature-x"}]
                       :error nil})]
        (let [result (sut/find-worktree-for-branch "/test/dir" "feature-x")]
          (is (true? (:success result)))
          (is (= {:path "/test/feature"
                  :head "def456"
                  :branch "feature-x"}
                 (:worktree result)))
          (is (nil? (:error result))))))

    (testing "returns nil when branch not in any worktree"
      (with-redefs [mcp-tasks.tools.git/list-worktrees
                    (fn [_]
                      {:success true
                       :worktrees [{:path "/test/dir"
                                    :head "abc123"
                                    :branch "main"}]
                       :error nil})]
        (let [result (sut/find-worktree-for-branch "/test/dir" "nonexistent")]
          (is (true? (:success result)))
          (is (nil? (:worktree result)))
          (is (nil? (:error result))))))

    (testing "handles multiple worktrees with only one matching"
      (with-redefs [mcp-tasks.tools.git/list-worktrees
                    (fn [_]
                      {:success true
                       :worktrees [{:path "/test/dir"
                                    :head "abc123"
                                    :branch "main"}
                                   {:path "/test/feature-a"
                                    :head "def456"
                                    :branch "feature-a"}
                                   {:path "/test/feature-b"
                                    :head "ghi789"
                                    :branch "feature-b"}]
                       :error nil})]
        (let [result (sut/find-worktree-for-branch "/test/dir" "feature-a")]
          (is (true? (:success result)))
          (is (= "feature-a" (-> result :worktree :branch)))
          (is (= "/test/feature-a" (-> result :worktree :path)))
          (is (nil? (:error result))))))

    (testing "propagates error from list-worktrees"
      (with-redefs [mcp-tasks.tools.git/list-worktrees
                    (fn [_]
                      {:success false
                       :worktrees nil
                       :error "not a git repo"})]
        (let [result (sut/find-worktree-for-branch "/test/dir" "main")]
          (is (false? (:success result)))
          (is (nil? (:worktree result)))
          (is (= "not a git repo" (:error result))))))))

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
