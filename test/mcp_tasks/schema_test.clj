(ns mcp-tasks.schema-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [mcp-tasks.schema :as schema]))

;; Tests that verify the schema definitions are correct and that
;; blocking-statuses contains the expected values for dependency logic.

(deftest blocking-statuses-test
  (testing "blocking-statuses"
    (testing "includes statuses representing incomplete work"
      (is (contains? schema/blocking-statuses :open))
      (is (contains? schema/blocking-statuses :in-progress))
      (is (contains? schema/blocking-statuses :blocked)))
    (testing "excludes statuses representing completed work"
      (is (not (contains? schema/blocking-statuses :closed)))
      (is (not (contains? schema/blocking-statuses :deleted))))
    (testing "excludes :done status (tasks awaiting merge do not block)"
      (is (not (contains? schema/blocking-statuses :done))))))

(deftest task-status-validation-test
  (testing "valid-task?"
    (let [base-task {:id 1
                     :parent-id nil
                     :status :open
                     :title "Test task"
                     :description "A test"
                     :design ""
                     :category "simple"
                     :type :task
                     :meta {}
                     :relations []}]
      (testing "accepts all valid status values"
        (is (schema/valid-task? (assoc base-task :status :open)))
        (is (schema/valid-task? (assoc base-task :status :closed)))
        (is (schema/valid-task? (assoc base-task :status :in-progress)))
        (is (schema/valid-task? (assoc base-task :status :blocked)))
        (is (schema/valid-task? (assoc base-task :status :deleted)))
        (is (schema/valid-task? (assoc base-task :status :done))))
      (testing "rejects invalid status values"
        (is (not (schema/valid-task? (assoc base-task :status :invalid))))
        (is (not (schema/valid-task? (assoc base-task :status :completed))))
        (is (not (schema/valid-task? (assoc base-task :status :pending))))))))
