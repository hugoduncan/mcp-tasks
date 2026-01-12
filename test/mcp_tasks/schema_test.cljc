(ns mcp-tasks.schema-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.schema :as schema]))

(deftest session-event-schema-validation
  ;; Test SessionEvent schema for capturing user prompts and system events
  (testing "SessionEvent schema"
    (testing "validates events with all required fields"
      (is (schema/valid-session-event? {:timestamp "2025-01-15T10:30:00Z"
                                        :event-type :user-prompt}))
      (is (schema/valid-session-event? {:timestamp "2025-01-15T10:30:00Z"
                                        :event-type :compaction}))
      (is (schema/valid-session-event? {:timestamp "2025-01-15T10:30:00Z"
                                        :event-type :session-start})))

    (testing "validates events with optional fields"
      (is (schema/valid-session-event? {:timestamp "2025-01-15T10:30:00Z"
                                        :event-type :user-prompt
                                        :content "Add tests"}))
      (is (schema/valid-session-event? {:timestamp "2025-01-15T10:30:00Z"
                                        :event-type :compaction
                                        :trigger "auto"}))
      (is (schema/valid-session-event? {:timestamp "2025-01-15T10:30:00Z"
                                        :event-type :session-start
                                        :session-id "abc-123"}))
      (is (schema/valid-session-event? {:timestamp "2025-01-15T10:30:00Z"
                                        :event-type :user-prompt
                                        :content "Fix bug"
                                        :trigger "manual"
                                        :session-id "xyz-789"})))

    (testing "rejects events with missing required fields"
      (is (not (schema/valid-session-event? {:event-type :user-prompt})))
      (is (not (schema/valid-session-event? {:timestamp "2025-01-15T10:30:00Z"}))))

    (testing "rejects events with invalid event-type"
      (is (not (schema/valid-session-event? {:timestamp "2025-01-15T10:30:00Z"
                                             :event-type :invalid-type})))
      (is (not (schema/valid-session-event? {:timestamp "2025-01-15T10:30:00Z"
                                             :event-type "user-prompt"}))))

    (testing "rejects events with invalid field types"
      (is (not (schema/valid-session-event? {:timestamp 12345
                                             :event-type :user-prompt})))
      (is (not (schema/valid-session-event? {:timestamp "2025-01-15T10:30:00Z"
                                             :event-type :user-prompt
                                             :content 123}))))

    (testing "rejects empty maps and nil"
      (is (not (schema/valid-session-event? {})))
      (is (not (schema/valid-session-event? nil))))))

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

    (testing "validates tasks with session-events field"
      (is (schema/valid-task? {:id 1
                               :parent-id nil
                               :status :open
                               :title "Story"
                               :description "Desc"
                               :design "Design"
                               :category "large"
                               :type :story
                               :meta {}
                               :relations []
                               :session-events []}))
      (is (schema/valid-task? {:id 2
                               :parent-id nil
                               :status :open
                               :title "Story with events"
                               :description "Desc"
                               :design "Design"
                               :category "large"
                               :type :story
                               :meta {}
                               :relations []
                               :session-events [{:timestamp "2025-01-15T10:30:00Z"
                                                 :event-type :user-prompt
                                                 :content "Add tests"}
                                                {:timestamp "2025-01-15T11:00:00Z"
                                                 :event-type :compaction
                                                 :trigger "auto"}]})))

    (testing "validates tasks without session-events (backward compatibility)"
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

    (testing "rejects tasks with invalid session-events types"
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
                                    :session-events "not a vector"})))
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
                                    :session-events [{:invalid "event"}]}))))

    (testing "validates tasks with code-reviewed field"
      (is (schema/valid-task? {:id 1
                               :parent-id nil
                               :status :open
                               :title "Reviewed task"
                               :description "Desc"
                               :design "Design"
                               :category "simple"
                               :type :task
                               :meta {}
                               :relations []
                               :code-reviewed "2025-01-15T10:30:00Z"}))
      (is (schema/valid-task? {:id 2
                               :parent-id nil
                               :status :open
                               :title "Unreviewed task"
                               :description "Desc"
                               :design "Design"
                               :category "simple"
                               :type :task
                               :meta {}
                               :relations []
                               :code-reviewed nil})))

    (testing "validates tasks with pr-num field"
      (is (schema/valid-task? {:id 1
                               :parent-id nil
                               :status :open
                               :title "Task with PR"
                               :description "Desc"
                               :design "Design"
                               :category "simple"
                               :type :task
                               :meta {}
                               :relations []
                               :pr-num 123}))
      (is (schema/valid-task? {:id 2
                               :parent-id nil
                               :status :open
                               :title "Task without PR"
                               :description "Desc"
                               :design "Design"
                               :category "simple"
                               :type :task
                               :meta {}
                               :relations []
                               :pr-num nil})))

    (testing "validates tasks with both code-reviewed and pr-num"
      (is (schema/valid-task? {:id 1
                               :parent-id nil
                               :status :closed
                               :title "Completed story"
                               :description "Desc"
                               :design "Design"
                               :category "large"
                               :type :story
                               :meta {}
                               :relations []
                               :code-reviewed "2025-01-15T10:30:00Z"
                               :pr-num 456})))

    (testing "validates tasks without code-reviewed or pr-num (backward compatibility)"
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

    (testing "rejects tasks with invalid code-reviewed types"
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
                                    :code-reviewed 12345})))
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
                                    :code-reviewed true}))))

    (testing "rejects tasks with invalid pr-num types"
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
                                    :pr-num "123"})))
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
                                    :pr-num 12.5}))))

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
    (testing "example-session-event is valid"
      (is (schema/valid-session-event? schema/example-session-event)))

    (testing "example-relation is valid"
      (is (schema/valid-relation? schema/example-relation)))

    (testing "example-task is valid"
      (is (schema/valid-task? schema/example-task)))))

(deftest explain-functions
  ;; Test explanation functions for invalid data
  (testing "explain-session-event"
    (testing "returns nil for valid event"
      (is (nil? (schema/explain-session-event {:timestamp "2025-01-15T10:30:00Z"
                                               :event-type :user-prompt}))))

    (testing "returns explanation for invalid event"
      (is (some? (schema/explain-session-event {:timestamp 12345
                                                :event-type :user-prompt})))
      (is (some? (schema/explain-session-event {:event-type :invalid})))))

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
