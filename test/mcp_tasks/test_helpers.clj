(ns mcp-tasks.test-helpers
  "Shared test helper functions for mcp-tasks testing."
  (:require
    [babashka.fs :as fs]
    [clojure.java.shell :as sh]
    [clojure.string :as str]
    [mcp-tasks.tasks :as tasks]
    [mcp-tasks.tasks-file :as tasks-file]))

(defn setup-test-dir
  "Creates test directory structure with .mcp-tasks subdirectory."
  [test-dir]
  (fs/create-dirs (fs/file test-dir ".mcp-tasks")))

(defn write-ednl-test-file
  "Write tasks as EDNL format to test file.
  Path is relative to .mcp-tasks directory."
  [test-dir path tasks]
  (let [file-path (str test-dir "/.mcp-tasks/" path)]
    (tasks-file/write-tasks file-path tasks)))

(defn read-ednl-test-file
  "Read tasks from EDNL test file.
  Path is relative to .mcp-tasks directory."
  [test-dir path]
  (let [file-path (str test-dir "/.mcp-tasks/" path)]
    (tasks-file/read-ednl file-path)))

(defn reset-tasks-state!
  "Reset the tasks namespace global state for testing."
  []
  (reset! tasks/task-ids [])
  (reset! tasks/tasks {})
  (reset! tasks/parent-children {})
  (reset! tasks/child-parent {})
  (vreset! tasks/next-id 1))

(defn test-config
  "Returns test config with git disabled and resolved-tasks-dir set."
  [test-dir]
  {:base-dir test-dir
   :use-git? false
   :resolved-tasks-dir (str test-dir "/.mcp-tasks")})

(defn git-test-config
  "Returns test config with git enabled and resolved-tasks-dir set."
  [test-dir]
  {:base-dir test-dir
   :use-git? true
   :resolved-tasks-dir (str test-dir "/.mcp-tasks")})

(defn init-git-repo
  "Initialize a git repository in the test .mcp-tasks directory."
  [test-dir]
  (let [git-dir (str test-dir "/.mcp-tasks")]
    (sh/sh "git" "init" :dir git-dir)
    (sh/sh "git" "config" "user.email" "test@test.com" :dir git-dir)
    (sh/sh "git" "config" "user.name" "Test User" :dir git-dir)))

(defn git-log-last-commit
  "Get the last commit message from the git repo."
  [test-dir]
  (let [git-dir (str test-dir "/.mcp-tasks")
        result (sh/sh "git" "log" "-1" "--pretty=%B" :dir git-dir)]
    (str/trim (:out result))))

(defn git-commit-exists?
  "Check if there are any commits in the git repo."
  [test-dir]
  (let [git-dir (str test-dir "/.mcp-tasks")
        result (sh/sh "git" "rev-parse" "HEAD" :dir git-dir)]
    (zero? (:exit result))))

(defn derive-test-worktree-path
  "Generate expected worktree path for testing.
  Mimics the logic from mcp-tasks.tools.git/derive-worktree-path
  using the test directory structure.

  Parameters:
  - base-dir: Test base directory (from *test-dir*)
  - title: Task or story title
  - config: Optional config map (default uses :worktree-prefix :project-name)

  Returns the expected worktree path string."
  ([base-dir title]
   (derive-test-worktree-path base-dir title {:worktree-prefix :project-name}))
  ([base-dir title config]
   (let [worktree-prefix (:worktree-prefix config :project-name)
         sanitized (-> title
                       str/lower-case
                       (str/replace #"\s+" "-")
                       (str/replace #"[^a-z0-9-]" ""))
         parent-dir (fs/parent base-dir)]
     (if (= worktree-prefix :none)
       (str parent-dir "/" sanitized)
       (str parent-dir "/mcp-tasks-" sanitized)))))

(defn with-test-setup*
  [f]
  (let [dir (fs/create-temp-dir {:prefix "mcp-tasks-test-"})]
    (try
      (setup-test-dir dir)
      (reset-tasks-state!)
      (f dir)
      (finally
        (fs/delete-tree dir)))))

(defmacro with-test-setup
  "Fixture that sets up and cleans up test directory for each test.
  Binds *test-dir* to a temporary directory and resets task state."
  [[dir] & body]
  `(with-test-setup* (fn [~dir] ~@body)))
