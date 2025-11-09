(ns mcp-tasks.schema-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.schema :as schema]))

(deftest relation-schema-validation
  ;; Test Relation schema validation with various valid and invalid inputs
  (testing "Relation schema"
    (testing "validates valid relations"
      (is (schema/valid-relation? {:id 1
                                   :relates-to 2
                                   :as-type :blocked-by}))
      (is (schema/valid-relation? {:id 10
                                   :relates-to 20
                                   :as-type :related}))
      (is (schema/valid-relation? {:id 5
                                   :relates-to 15
                                   :as-type :discovered-during})))

    (testing "rejects relations with missing fields"
      (is (not (schema/valid-relation? {:id 1
                                        :relates-to 2})))
      (is (not (schema/valid-relation? {:id 1
                                        :as-type :blocked-by})))
      (is (not (schema/valid-relation? {:relates-to 2
                                        :as-type :blocked-by}))))

    (testing "rejects relations with invalid types"
      (is (not (schema/valid-relation? {:id "not-an-int"
                                        :relates-to 2
                                        :as-type :blocked-by})))
      (is (not (schema/valid-relation? {:id 1
                                        :relates-to "not-an-int"
                                        :as-type :blocked-by})))
      (is (not (schema/valid-relation? {:id 1
                                        :relates-to 2
                                        :as-type :invalid-type}))))

    (testing "rejects empty maps and nil"
      (is (not (schema/valid-relation? {})))
      (is (not (schema/valid-relation? nil))))))

(deftest task-schema-validation
  ;; Test Task schema validation with various valid and invalid inputs
  (testing "Task schema"
    (testing "validates valid tasks"
      (is (schema/valid-task? {:id 1
                               :parent-id nil
                               :status :open
                               :title "Test task"
                               :description "Desc"
                               :design "Design"
                               :category "simple"
                               :type :task
                               :meta {}
                               :relations []}))
      (is (schema/valid-task? {:id 2
                               :parent-id 1
                               :status :in-progress
                               :title "Child task"
                               :description "Child desc"
                               :design "Child design"
                               :category "medium"
                               :type :bug
                               :meta {"priority" "high"}
                               :relations [{:id 1
                                            :relates-to 3
                                            :as-type :blocked-by}]})))

    (testing "validates tasks with shared-context field"
      (is (schema/valid-task? {:id 1
                               :parent-id nil
                               :status :open
                               :title "Test task"
                               :description "Desc"
                               :design "Design"
                               :category "simple"
                               :type :task
                               :meta {}
                               :relations []
                               :shared-context []}))
      (is (schema/valid-task? {:id 2
                               :parent-id 1
                               :status :open
                               :title "Story task"
                               :description "Story desc"
                               :design "Story design"
                               :category "large"
                               :type :story
                               :meta {}
                               :relations []
                               :shared-context ["Task 1: API endpoint is https://api.example.com"
                                                "Task 2: Implemented JWT auth"]})))

    (testing "validates tasks without shared-context field (backward compatibility)"
      (is (schema/valid-task? {:id 1
                               :parent-id nil
                               :status :open
                               :title "Legacy task"
                               :description "Desc"
                               :design "Design"
                               :category "simple"
                               :type :task
                               :meta {}
                               :relations []})))

    (testing "rejects tasks with invalid shared-context types"
      (is (not (schema/valid-task? {:id 1
                                    :parent-id nil
                                    :status :open
                                    :title "Test"
                                    :description "Desc"
                                    :design "Design"
                                    :category "simple"
                                    :type :task
                                    :meta {}
                                    :relations []
                                    :shared-context "not a vector"})))
      (is (not (schema/valid-task? {:id 1
                                    :parent-id nil
                                    :status :open
                                    :title "Test"
                                    :description "Desc"
                                    :design "Design"
                                    :category "simple"
                                    :type :task
                                    :meta {}
                                    :relations []
                                    :shared-context [123 456]}))))

    (testing "validates all status values"
      (doseq [status [:open :closed :in-progress :blocked]]
        (is (schema/valid-task? {:id 1
                                 :parent-id nil
                                 :status status
                                 :title "Test"
                                 :description "Desc"
                                 :design "Design"
                                 :category "simple"
                                 :type :task
                                 :meta {}
                                 :relations []}))))

    (testing "validates all type values"
      (doseq [type [:task :bug :feature :story :chore]]
        (is (schema/valid-task? {:id 1
                                 :parent-id nil
                                 :status :open
                                 :title "Test"
                                 :description "Desc"
                                 :design "Design"
                                 :category "simple"
                                 :type type
                                 :meta {}
                                 :relations []}))))

    (testing "rejects tasks with missing required fields"
      (is (not (schema/valid-task? {:id 1})))
      (is (not (schema/valid-task? {:id 1
                                    :status :open
                                    :title "Test"}))))

    (testing "rejects tasks with invalid field types"
      (is (not (schema/valid-task? {:id "not-int"
                                    :parent-id nil
                                    :status :open
                                    :title "Test"
                                    :description "Desc"
                                    :design "Design"
                                    :category "simple"
                                    :type :task
                                    :meta {}
                                    :relations []})))
      (is (not (schema/valid-task? {:id 1
                                    :parent-id nil
                                    :status :invalid-status
                                    :title "Test"
                                    :description "Desc"
                                    :design "Design"
                                    :category "simple"
                                    :type :task
                                    :meta {}
                                    :relations []})))
      (is (not (schema/valid-task? {:id 1
                                    :parent-id nil
                                    :status :open
                                    :title 123
                                    :description "Desc"
                                    :design "Design"
                                    :category "simple"
                                    :type :task
                                    :meta {}
                                    :relations []}))))

    (testing "rejects tasks with invalid relations"
      (is (not (schema/valid-task? {:id 1
                                    :parent-id nil
                                    :status :open
                                    :title "Test"
                                    :description "Desc"
                                    :design "Design"
                                    :category "simple"
                                    :type :task
                                    :meta {}
                                    :relations [{:id 1}]}))))

    (testing "rejects empty maps and nil"
      (is (not (schema/valid-task? {})))
      (is (not (schema/valid-task? nil))))))

(deftest example-data
  ;; Test that example data passes validation
  (testing "example data"
    (testing "example-relation is valid"
      (is (schema/valid-relation? schema/example-relation)))

    (testing "example-task is valid"
      (is (schema/valid-task? schema/example-task)))))

(deftest explain-functions
  ;; Test explanation functions for invalid data
  (testing "explain-relation"
    (testing "returns nil for valid relation"
      (is (nil? (schema/explain-relation {:id 1
                                          :relates-to 2
                                          :as-type :blocked-by}))))

    (testing "returns explanation for invalid relation"
      (is (some? (schema/explain-relation {:id "bad"
                                           :relates-to 2
                                           :as-type :blocked-by})))))

  (testing "explain-task"
    (testing "returns nil for valid task"
      (is (nil? (schema/explain-task schema/example-task))))

    (testing "returns explanation for invalid task"
      (is (some? (schema/explain-task {:id "bad"}))))))
