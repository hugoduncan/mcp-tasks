(ns mcp-tasks.tool.work-on-test
  (:require
    [clojure.data.json :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-tasks.test-helpers :as h]
    [mcp-tasks.tool.add-task :as add-task]
    [mcp-tasks.tool.work-on :as sut]))

(use-fixtures :each h/test-fixture)

(deftest work-on-validates-task-id-required
  ;; Test that work-on requires task-id parameter
  (testing "work-on validates task-id is required"
    (let [result (#'sut/work-on-impl (h/test-config) nil {})
          response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
      (is (false? (:isError result)))
      (is (contains? response :error))
      (is (str/includes? (:error response) "task-id parameter is required")))))

(deftest work-on-validates-task-id-type
  ;; Test that work-on validates task-id is an integer
  (testing "work-on validates task-id is an integer"
    (let [result (#'sut/work-on-impl (h/test-config) nil {:task-id "not-an-int"})
          response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
      (is (false? (:isError result)))
      (is (contains? response :error))
      (is (str/includes? (:error response) "task-id must be an integer"))
      (is (= "not-an-int" (get-in response [:metadata :provided-value])))
      (is (contains? (:metadata response) :provided-type)))))

(deftest work-on-validates-task-exists
  ;; Test that work-on validates the task exists
  (testing "work-on validates task exists"
    (testing "returns error when task does not exist"
      (let [result (#'sut/work-on-impl (h/test-config) nil {:task-id 99999})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]
        (is (false? (:isError result)))
        (is (contains? response :error))
        (is (str/includes? (:error response) "No task found"))
        (is (= 99999 (get-in response [:metadata :task-id])))
        (is (contains? (:metadata response) :file))))))

(deftest work-on-returns-task-details
  ;; Test that work-on returns task details when task exists
  (testing "work-on returns task details"
    ;; Add a task
    (let [add-result (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Test Task" :type "task"})
          add-response (json/read-str (get-in add-result [:content 1 :text]) :key-fn keyword)
          task-id (get-in add-response [:task :id])

          ;; Call work-on with the task-id
          result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
          response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

      (is (false? (:isError result)))
      (is (= task-id (:task-id response)))
      (is (= "Test Task" (:title response)))
      (is (= "simple" (:category response)))
      (is (= "task" (:type response)))
      (is (= "open" (:status response)))
      (is (str/includes? (:message response) "validated successfully")))))

(deftest work-on-handles-different-task-types
  ;; Test that work-on works with different task types
  (testing "work-on handles different task types"
    (testing "works with bug type"
      (let [add-result (#'add-task/add-task-impl (h/test-config) nil {:category "simple" :title "Bug Fix" :type "bug"})
            add-response (json/read-str (get-in add-result [:content 1 :text]) :key-fn keyword)
            task-id (get-in add-response [:task :id])

            result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

        (is (false? (:isError result)))
        (is (= "Bug Fix" (:title response)))
        (is (= "bug" (:type response)))))

    (testing "works with feature type"
      (let [add-result (#'add-task/add-task-impl (h/test-config) nil {:category "medium" :title "New Feature" :type "feature"})
            add-response (json/read-str (get-in add-result [:content 1 :text]) :key-fn keyword)
            task-id (get-in add-response [:task :id])

            result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

        (is (false? (:isError result)))
        (is (= "New Feature" (:title response)))
        (is (= "feature" (:type response)))
        (is (= "medium" (:category response)))))

    (testing "works with story type"
      (let [add-result (#'add-task/add-task-impl (h/test-config) nil {:category "story" :title "User Story" :type "story"})
            add-response (json/read-str (get-in add-result [:content 1 :text]) :key-fn keyword)
            task-id (get-in add-response [:task :id])

            result (#'sut/work-on-impl (h/test-config) nil {:task-id task-id})
            response (json/read-str (get-in result [:content 0 :text]) :key-fn keyword)]

        (is (false? (:isError result)))
        (is (= "User Story" (:title response)))
        (is (= "story" (:type response)))))))
