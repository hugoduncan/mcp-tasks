(ns mcp-tasks.cli-test
  "Tests for the CLI main entry point."
  (:require
    [clojure.test :refer [deftest testing is]]
    [mcp-tasks.cli.parse :as parse]))

(deftest help-text-is-defined
  (testing "help-text"
    (testing "contains main usage"
      (is (string? parse/help-text))
      (is (re-find #"mcp-tasks - Task management" parse/help-text))
      (is (re-find #"USAGE:" parse/help-text))
      (is (re-find #"list.*List tasks" parse/help-text)))

    (testing "contains all commands"
      (is (re-find #"list" parse/help-text))
      (is (re-find #"show" parse/help-text))
      (is (re-find #"add" parse/help-text))
      (is (re-find #"complete" parse/help-text))
      (is (re-find #"update" parse/help-text))
      (is (re-find #"delete" parse/help-text)))

    (testing "contains examples"
      (is (re-find #"EXAMPLES:" parse/help-text)))))

(deftest command-specific-help-defined
  (testing "list-help"
    (is (string? parse/list-help))
    (is (re-find #"List tasks" parse/list-help))
    (is (re-find #"--status" parse/list-help)))

  (testing "show-help"
    (is (string? parse/show-help))
    (is (re-find #"Display a single task" parse/show-help))
    (is (re-find #"--task-id" parse/show-help)))

  (testing "add-help"
    (is (string? parse/add-help))
    (is (re-find #"Create a new task" parse/add-help))
    (is (re-find #"--category" parse/add-help)))

  (testing "complete-help"
    (is (string? parse/complete-help))
    (is (re-find #"Mark a task as complete" parse/complete-help))
    (is (re-find #"--completion-comment" parse/complete-help)))

  (testing "update-help"
    (is (string? parse/update-help))
    (is (re-find #"Update task fields" parse/update-help))
    (is (re-find #"--design" parse/update-help)))

  (testing "delete-help"
    (is (string? parse/delete-help))
    (is (re-find #"Delete a task" parse/delete-help))
    (is (re-find #"--title-pattern" parse/delete-help))))
