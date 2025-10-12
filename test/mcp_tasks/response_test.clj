(ns mcp-tasks.response-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.response :as response]))

(deftest error-response-test
  ;; Test standardized error response formatting with and without ex-data
  (testing "error-response"
    (testing "formats exception with message only"
      (let [e (Exception. "Test error message")
            result (response/error-response e)]
        (is (= true (:isError result)))
        (is (= 1 (count (:content result))))
        (is (= "text" (-> result :content first :type)))
        (is (= "Error: Test error message" (-> result :content first :text)))))

    (testing "formats exception with ex-data"
      (let [e (ex-info "Test error" {:file "test.md" :line 42})
            result (response/error-response e)]
        (is (= true (:isError result)))
        (is (= 1 (count (:content result))))
        (is (= "text" (-> result :content first :type)))
        (let [text (-> result :content first :text)]
          (is (str/starts-with? text "Error: Test error"))
          (is (str/includes? text "Details: "))
          (is (str/includes? text ":file"))
          (is (str/includes? text ":line")))))

    (testing "formats exception with empty ex-data"
      (let [e (ex-info "Test error" {})
            result (response/error-response e)]
        (is (= true (:isError result)))
        (let [text (-> result :content first :text)]
          (is (str/starts-with? text "Error: Test error"))
          (is (str/includes? text "Details: {}")))))))
