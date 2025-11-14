(ns mcp-tasks.cli.prompts-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.cli.prompts :as sut]))

(deftest prompts-list-command-returns-all-prompts
  ;; Test prompts-list-command returns all available prompts with metadata
  (testing "prompts-list-command"
    (testing "returns all prompts with counts"
      (let [result (sut/prompts-list-command {})]
        (is (contains? result :prompts))
        (is (contains? result :metadata))
        (is (vector? (:prompts result)))
        (is (pos? (count (:prompts result))))

        (testing "metadata contains correct counts"
          (let [metadata (:metadata result)
                prompts (:prompts result)]
            (is (= (:total-count metadata) (count prompts)))
            (is (= (:category-count metadata)
                   (count (filter #(= :category (:type %)) prompts))))
            (is (= (:workflow-count metadata)
                   (count (filter #(= :workflow (:type %)) prompts))))))

        (testing "each prompt has required fields"
          (doseq [prompt (:prompts result)]
            (is (contains? prompt :name))
            (is (contains? prompt :type))
            (is (contains? prompt :description))
            (is (string? (:name prompt)))
            (is (keyword? (:type prompt)))
            (is (#{:category :workflow} (:type prompt)))
            (is (string? (:description prompt)))))))))

(deftest prompts-install-command-installs-prompts
  ;; Test prompts-install-command installs prompts and returns results
  (testing "prompts-install-command"
    (let [test-config {:resolved-tasks-dir (str (System/getProperty "java.io.tmpdir") "/.mcp-tasks")}]
      (testing "with valid prompt names"
        (let [result (sut/prompts-install-command test-config {:prompt-names ["simple" "medium"]})]
          (is (contains? result :results))
          (is (contains? result :metadata))
          (is (= 2 (count (:results result))))
          (is (= 2 (get-in result [:metadata :requested-count])))

          (testing "each result has required fields"
            (doseq [res (:results result)]
              (is (contains? res :name))
              (is (contains? res :type))
              (is (contains? res :status))
              (is (keyword? (:status res)))))))

      (testing "with non-existent prompt"
        (let [result (sut/prompts-install-command test-config {:prompt-names ["nonexistent"]})]
          (is (= 1 (count (:results result))))
          (is (= :not-found (get-in result [:results 0 :status])))
          (is (= 0 (get-in result [:metadata :installed-count])))
          (is (= 1 (get-in result [:metadata :failed-count])))))

      (testing "with mix of valid and invalid prompts"
        (let [result (sut/prompts-install-command test-config {:prompt-names ["simple" "invalid" "medium"]})]
          (is (= 3 (count (:results result))))
          (is (= 3 (get-in result [:metadata :requested-count])))
          (is (<= (get-in result [:metadata :installed-count]) 2))
          (is (>= (get-in result [:metadata :failed-count]) 1)))))))
