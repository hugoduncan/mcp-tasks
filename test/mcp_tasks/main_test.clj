(ns mcp-tasks.main-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.main :as sut]))

(deftest get-prompt-vars-test
  ;; Test that get-prompt-vars finds all prompt vars from task-prompts namespace
  ;; and filters to only those containing strings.
  (testing "get-prompt-vars"
    (testing "returns sequence of vars"
      (let [vars (#'sut/get-prompt-vars)]
        (is (seq vars))
        (is (every? var? vars))))

    (testing "returns only vars with string values"
      (let [vars (#'sut/get-prompt-vars)]
        (is (every? #(string? @%) vars))))

    (testing "finds clarify-task prompt"
      (let [vars (#'sut/get-prompt-vars)
            var-names (map #(name (symbol %)) vars)]
        (is (some #(= "clarify-task" %) var-names))))))

(deftest list-prompts-test
  ;; Test that list-prompts outputs prompt names and descriptions to stdout.
  (testing "list-prompts"
    (testing "outputs prompt names with descriptions"
      (let [output (with-out-str (#'sut/list-prompts))]
        (is (string? output))
        (is (str/includes? output "clarify-task:"))
        (is (str/includes? output "Transform informal task instructions"))))

    (testing "returns exit code 0"
      (let [exit-code (with-out-str (#'sut/list-prompts))]
        (is (= 0 (#'sut/list-prompts)))))))

(deftest install-prompt-test
  ;; Test that install-prompt handles various edge cases.
  (testing "install-prompt"
    (testing "warns on nonexistent prompt"
      (let [output (with-out-str (#'sut/install-prompt "nonexistent"))
            exit-code (#'sut/install-prompt "nonexistent")]
        (is (str/includes? output "Warning"))
        (is (str/includes? output "not found"))
        (is (= 1 exit-code))))))

(deftest install-prompts-test
  ;; Test that install-prompts handles multiple prompts and returns
  ;; appropriate exit codes.
  (testing "install-prompts"
    (testing "returns exit code 1 when any prompt not found"
      (let [exit-code (#'sut/install-prompts ["nonexistent"])]
        (is (= 1 exit-code))))))
