(ns mcp-tasks.tool.reopen-task-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.test-helpers :as h]
    [mcp-tasks.tool.reopen-task :as sut]))

;; Test Mocking Strategy
;;
;; This test suite mirrors the structure of complete-task-test.clj but tests
;; the reverse operation (reopening closed tasks).
;;
;; What Gets Tested:
;;
;; 1. Reopening from tasks.ednl (closed but not archived):
;;    - Task status changes from :closed to :open
;;    - Task remains in tasks.ednl
;;
;; 2. Reopening from complete.ednl (archived):
;;    - Task status changes from :closed to :open
;;    - Task moves from complete.ednl back to tasks.ednl
;;    - Task is appended to tasks.ednl
;;
;; 3. Task identification:
;;    - By exact task-id
;;    - By exact title match
;;    - Both task-id and title (must match)
;;
;; 4. Error cases:
;;    - Task not found
;;    - Task already open
;;    - Multiple tasks with same title
;;
;; 5. Data preservation:
;;    - Meta fields preserved
;;    - Relations preserved
;;    - Parent-id preserved
;;
;; Common Patterns:
;;
;; - Use h/with-test-setup for test isolation
;; - Use h/write-ednl-test-file to set up test data
;; - Use h/read-ednl-test-file to verify results
;; - Mock git operations for unit tests

;; Test helpers

(defn make-test-task
  "Create a test task map with defaults."
  [& {:keys [id title status parent-id meta relations description design category type]
      :or {id 1
           title "test task"
           status :open
           parent-id nil
           meta {}
           relations []
           description ""
           design ""
           category "test"
           type :task}}]
  {:id id
   :parent-id parent-id
   :title title
   :description description
   :design design
   :category category
   :type type
   :status status
   :meta meta
   :relations relations})

;; Tests

(deftest reopens-closed-task-in-tasks-ednl
  (h/with-test-setup [test-dir]
    ;; Tests that reopening a closed task in tasks.ednl changes status to :open
    (testing "reopen-task"
      (testing "reopens closed task in tasks.ednl"
        ;; Create EDNL file with closed task in tasks.ednl
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [(make-test-task :id 1 :title "closed task" :description "detail" :status :closed)
           (make-test-task :id 2 :title "open task" :status :open)])
        (let [result (#'sut/reopen-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1})]
          (is (false? (:isError result)))
          ;; Verify task is now open in tasks.ednl
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")]
            (is (= 2 (count tasks)))
            (is (= "closed task" (:title (first tasks))))
            (is (= :open (:status (first tasks))))
            (is (= "open task" (:title (second tasks))))))))))

(deftest reopens-archived-task-from-complete-ednl
  (h/with-test-setup [test-dir]
    ;; Tests that reopening an archived task moves it back to tasks.ednl
    (testing "reopen-task"
      (testing "reopens archived task from complete.ednl"
        ;; Create tasks.ednl with one open task
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [(make-test-task :id 2 :title "open task" :status :open)])
        ;; Create complete.ednl with archived task
        (h/write-ednl-test-file
          test-dir
          "complete.ednl"
          [(make-test-task :id 1 :title "archived task" :description "archived" :status :closed)])
        (let [result (#'sut/reopen-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1})]
          (is (false? (:isError result)))
          ;; Verify task moved to tasks.ednl
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")]
            (is (= 2 (count tasks)))
            (is (= "open task" (:title (first tasks))))
            (is (= "archived task" (:title (second tasks))))
            (is (= :open (:status (second tasks)))))
          ;; Verify task removed from complete.ednl
          (let [complete (h/read-ednl-test-file test-dir "complete.ednl")]
            (is (= 0 (count complete)))))))))

(deftest reopens-task-by-id
  (h/with-test-setup [test-dir]
    ;; Tests that reopen-task-impl can find and reopen a task by exact ID
    (testing "reopen-task"
      (testing "reopens task by exact task-id"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [(make-test-task :id 1 :title "first task" :status :open)
           (make-test-task :id 2 :title "second task" :status :closed)])
        (let [result (#'sut/reopen-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 2})]
          (is (false? (:isError result)))
          ;; Verify task 2 is now open
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")]
            (is (= 2 (count tasks)))
            (is (= "second task" (:title (second tasks))))
            (is (= :open (:status (second tasks))))))))))

(deftest reopens-task-by-exact-title
  (h/with-test-setup [test-dir]
    ;; Tests that reopen-task-impl finds tasks by exact title match
    (testing "reopen-task"
      (testing "reopens task by exact title match"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [(make-test-task :id 1 :title "first task" :status :open)
           (make-test-task :id 2 :title "second task" :status :closed)])
        (let [result (#'sut/reopen-task-impl
                      (h/test-config test-dir)
                      nil
                      {:title "second task"})]
          (is (false? (:isError result)))
          ;; Verify second task is now open
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")]
            (is (= 2 (count tasks)))
            (is (= "second task" (:title (second tasks))))
            (is (= :open (:status (second tasks))))))))))

(deftest rejects-ambiguous-title
  (h/with-test-setup [test-dir]
    ;; Tests that reopen-task-impl rejects when multiple tasks have the same title
    (testing "reopen-task"
      (testing "rejects multiple tasks with same title"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [(make-test-task :id 1 :title "duplicate" :description "first" :status :closed)
           (make-test-task :id 2 :title "duplicate" :description "second" :status :closed)])
        (let [result (#'sut/reopen-task-impl
                      (h/test-config test-dir)
                      nil
                      {:title "duplicate"})]
          (is (true? (:isError result)))
          (is (str/includes?
                (get-in result [:content 0 :text])
                "Multiple tasks found")))))))

(deftest verifies-id-and-title-match
  (h/with-test-setup [test-dir]
    ;; Tests that when both task-id and title are provided, they must refer to the same task
    (testing "reopen-task"
      (testing "verifies task-id and title refer to same task"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [(make-test-task :id 1 :title "first task" :status :closed)
           (make-test-task :id 2 :title "second task" :status :closed)])
        (let [result (#'sut/reopen-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1 :title "second task"})]
          (is (true? (:isError result)))
          (is (str/includes?
                (get-in result [:content 0 :text])
                "do not refer to the same task")))))))

(deftest rejects-task-not-found
  (h/with-test-setup [test-dir]
    ;; Tests error handling when task doesn't exist
    (testing "reopen-task"
      (testing "returns error when task not found"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [(make-test-task :id 1 :title "existing" :status :closed)])
        (let [result (#'sut/reopen-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 999})]
          (is (true? (:isError result)))
          (is (str/includes?
                (get-in result [:content 0 :text])
                "not found")))))))

(deftest rejects-already-open-task
  (h/with-test-setup [test-dir]
    ;; Tests error handling when task is already open
    (testing "reopen-task"
      (testing "returns error when task already open"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [(make-test-task :id 1 :title "open task" :status :open)])
        (let [result (#'sut/reopen-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1})]
          (is (true? (:isError result)))
          (is (str/includes?
                (get-in result [:content 0 :text])
                "already open")))))))

(deftest preserves-task-metadata
  (h/with-test-setup [test-dir]
    ;; Tests that meta fields are preserved when reopening
    (testing "reopen-task"
      (testing "preserves meta fields"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [(make-test-task :id 1
                           :title "task with meta"
                           :description "detail"
                           :status :closed
                           :meta {"key1" "value1" "key2" "value2"})])
        (let [result (#'sut/reopen-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")]
            (is (= 1 (count tasks)))
            (let [task (first tasks)]
              (is (= :open (:status task)))
              (is (= {"key1" "value1" "key2" "value2"} (:meta task))))))))))

(deftest preserves-task-relations
  (h/with-test-setup [test-dir]
    ;; Tests that relations are preserved when reopening
    (testing "reopen-task"
      (testing "preserves relations"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [(make-test-task :id 1
                           :title "task with relations"
                           :status :closed
                           :relations [{:id 1 :relates-to 2 :as-type :blocked-by}
                                       {:id 2 :relates-to 3 :as-type :related}])])
        (let [result (#'sut/reopen-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 1})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")]
            (is (= 1 (count tasks)))
            (let [task (first tasks)]
              (is (= :open (:status task)))
              (is (= 2 (count (:relations task))))
              (is (= :blocked-by (get-in task [:relations 0 :as-type])))
              (is (= :related (get-in task [:relations 1 :as-type]))))))))))

(deftest preserves-parent-id
  (h/with-test-setup [test-dir]
    ;; Tests that parent-id is preserved when reopening
    (testing "reopen-task"
      (testing "preserves parent-id"
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [(make-test-task :id 1 :title "parent story" :type :story :status :open)
           (make-test-task :id 2 :title "child task" :parent-id 1 :status :closed)])
        (let [result (#'sut/reopen-task-impl
                      (h/test-config test-dir)
                      nil
                      {:task-id 2})]
          (is (false? (:isError result)))
          (let [tasks (h/read-ednl-test-file test-dir "tasks.ednl")]
            (is (= 2 (count tasks)))
            (let [child-task (second tasks)]
              (is (= :open (:status child-task)))
              (is (= 1 (:parent-id child-task))))))))))

(deftest ^:integration creates-git-commit
  (h/with-test-setup [test-dir]
    ;; Tests that git commit is created with correct message
    (testing "reopen-task"
      (testing "creates git commit with semantic message"
        (h/init-git-repo test-dir)
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [(make-test-task :id 1 :title "test task" :status :closed)])
        ;; Initial commit
        (let [result (#'sut/reopen-task-impl
                      (h/git-test-config test-dir)
                      nil
                      {:task-id 1})]
          (is (false? (:isError result)))
          (is (h/git-commit-exists? test-dir))
          (let [commit-msg (h/git-log-last-commit test-dir)]
            (is (str/includes? commit-msg "Reopen task #1"))
            (is (str/includes? commit-msg "test task"))))))))
