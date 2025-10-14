(ns mcp-tasks.tasks-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest testing is use-fixtures]]
    [mcp-tasks.tasks :as tasks]
    [mcp-tasks.tasks-file :as tasks-file]))

;; Test that the tasks namespace correctly manages in-memory state
;; for query operations, mutations, and persistence with proper
;; state management and invariant maintenance.

(def test-task-1
  {:id 1
   :parent-id nil
   :status :open
   :title "Task 1"
   :description "First test task"
   :design "Design 1"
   :category "simple"
   :type :task
   :meta {}
   :relations []})

(def test-task-2
  {:id 2
   :parent-id nil
   :status :open
   :title "Task 2"
   :description "Second test"
   :design "Design 2"
   :category "medium"
   :type :task
   :meta {}
   :relations []})

(def test-task-3
  {:id 3
   :parent-id 1
   :status :closed
   :title "Task 3"
   :description "Child task"
   :design "Design 3"
   :category "simple"
   :type :task
   :meta {"priority" "high"}
   :relations []})

(def test-task-4
  {:id 4
   :parent-id nil
   :status :in-progress
   :title "Task 4"
   :description "In-progress task"
   :design "Design 4"
   :category "simple"
   :type :task
   :meta {}
   :relations []})

(defn temp-file
  []
  (let [file (java.io.File/createTempFile "test-tasks-" ".ednl")]
    (.deleteOnExit file)
    (.getAbsolutePath file)))

(defn reset-state!
  "Reset all task state atoms to empty values."
  []
  (reset! tasks/task-ids [])
  (reset! tasks/tasks {})
  (reset! tasks/parent-children {})
  (reset! tasks/child-parent {})
  (vreset! tasks/next-id 1))

(defn reset-state-fixture
  [f]
  (reset-state!)
  (f))

(use-fixtures :each reset-state-fixture)

;; Query API Tests

(deftest get-task-test
  (testing "get-task"
    (testing "returns nil for non-existent task"
      (is (nil? (tasks/get-task 999))))

    (testing "returns task when it exists"
      (reset! tasks/tasks {1 test-task-1})
      (is (= test-task-1 (tasks/get-task 1))))))

(deftest get-children-test
  (testing "get-children"
    (testing "returns empty vector when parent has no children"
      (is (= [] (tasks/get-children 1))))

    (testing "returns all child tasks for a parent"
      (reset! tasks/tasks {1 test-task-1
                           3 test-task-3})
      (reset! tasks/parent-children {1 #{3}})
      (is (= [test-task-3] (tasks/get-children 1))))

    (testing "returns multiple children"
      (let [child-1 (assoc test-task-3 :id 5 :parent-id 1)
            child-2 (assoc test-task-3 :id 6 :parent-id 1)]
        (reset! tasks/tasks {1 test-task-1
                             5 child-1
                             6 child-2})
        (reset! tasks/parent-children {1 #{5 6}})
        (let [children (tasks/get-children 1)]
          (is (= 2 (count children)))
          (is (contains? (set children) child-1))
          (is (contains? (set children) child-2)))))))

(deftest get-next-incomplete-by-category-test
  (testing "get-next-incomplete-by-category"
    (testing "returns nil when no tasks exist"
      (is (nil? (tasks/get-next-incomplete-by-category "simple"))))

    (testing "returns nil when no tasks match category"
      (reset! tasks/task-ids [1])
      (reset! tasks/tasks {1 test-task-1})
      (is (nil? (tasks/get-next-incomplete-by-category "large"))))

    (testing "returns first incomplete task in category"
      (reset! tasks/task-ids [1 4])
      (reset! tasks/tasks {1 test-task-1
                           4 test-task-4})
      (is (= test-task-1 (tasks/get-next-incomplete-by-category "simple"))))

    (testing "skips closed tasks"
      (reset! tasks/task-ids [3 1])
      (reset! tasks/tasks {3 test-task-3
                           1 test-task-1})
      (is (= test-task-1 (tasks/get-next-incomplete-by-category "simple"))))

    (testing "returns nil when all tasks are closed"
      (reset! tasks/task-ids [3])
      (reset! tasks/tasks {3 test-task-3})
      (is (nil? (tasks/get-next-incomplete-by-category "simple"))))))

(deftest get-next-incomplete-by-parent-test
  (testing "get-next-incomplete-by-parent"
    (testing "returns nil when parent has no children"
      (is (nil? (tasks/get-next-incomplete-by-parent 1))))

    (testing "returns first incomplete child"
      (let [child-open (assoc test-task-3 :id 5 :status :open)
            child-closed test-task-3]
        (reset! tasks/tasks {1 test-task-1
                             3 child-closed
                             5 child-open})
        (reset! tasks/parent-children {1 #{3 5}})
        (is (= child-open (tasks/get-next-incomplete-by-parent 1)))))

    (testing "returns nil when all children are closed"
      (reset! tasks/tasks {1 test-task-1
                           3 test-task-3})
      (reset! tasks/parent-children {1 #{3}})
      (is (nil? (tasks/get-next-incomplete-by-parent 1))))))

(deftest verify-task-text-test
  (testing "verify-task-text"
    (testing "returns false for non-existent task"
      (is (false? (tasks/verify-task-text 999 "Task"))))

    (testing "matches title prefix"
      (reset! tasks/tasks {1 test-task-1})
      (is (true? (tasks/verify-task-text 1 "Task 1")))
      (is (true? (tasks/verify-task-text 1 "Task"))))

    (testing "matches description prefix"
      (reset! tasks/tasks {1 test-task-1})
      (is (true? (tasks/verify-task-text 1 "First test"))))

    (testing "returns false for non-matching text"
      (reset! tasks/tasks {1 test-task-1})
      (is (false? (tasks/verify-task-text 1 "No match"))))))

(deftest get-next-incomplete-test
  ;; Test get-next-incomplete with various filter combinations
  (testing "get-next-incomplete"
    (testing "returns nil when no tasks exist"
      (is (nil? (tasks/get-next-incomplete))))

    (testing "filters by category"
      (reset! tasks/task-ids [1 2])
      (reset! tasks/tasks {1 test-task-1
                           2 test-task-2})
      (is (= test-task-1 (tasks/get-next-incomplete :category "simple")))
      (is (= test-task-2 (tasks/get-next-incomplete :category "medium"))))

    (testing "filters by parent-id"
      (let [child-task (assoc test-task-3 :id 5 :status :open :parent-id 1)]
        (reset! tasks/task-ids [1 5])
        (reset! tasks/tasks {1 test-task-1
                             5 child-task})
        (is (= child-task (tasks/get-next-incomplete :parent-id 1)))))

    (testing "filters by title-pattern with regex"
      (reset! tasks/task-ids [1 2])
      (reset! tasks/tasks {1 test-task-1
                           2 test-task-2})
      (is (= test-task-1 (tasks/get-next-incomplete :title-pattern "Task 1")))
      (is (= test-task-2 (tasks/get-next-incomplete :title-pattern "Task 2"))))

    (testing "filters by title-pattern with substring match"
      (reset! tasks/task-ids [1 2])
      (reset! tasks/tasks {1 test-task-1
                           2 test-task-2})
      (is (= test-task-1 (tasks/get-next-incomplete :title-pattern "Task"))))

    (testing "combines multiple filters with AND"
      (let [child-1 (assoc test-task-3 :id 5 :status :open :parent-id 1 :category "simple" :title "Child A")
            child-2 (assoc test-task-3 :id 6 :status :open :parent-id 1 :category "medium" :title "Child B")]
        (reset! tasks/task-ids [1 5 6])
        (reset! tasks/tasks {1 test-task-1
                             5 child-1
                             6 child-2})
        (is (= child-1 (tasks/get-next-incomplete :parent-id 1 :category "simple")))
        (is (= child-2 (tasks/get-next-incomplete :parent-id 1 :category "medium")))
        (is (= child-1 (tasks/get-next-incomplete :parent-id 1 :title-pattern "Child A")))))

    (testing "skips closed tasks"
      (reset! tasks/task-ids [3 1])
      (reset! tasks/tasks {3 test-task-3
                           1 test-task-1})
      (is (= test-task-1 (tasks/get-next-incomplete))))

    (testing "returns nil when no tasks match filters"
      (reset! tasks/task-ids [1])
      (reset! tasks/tasks {1 test-task-1})
      (is (nil? (tasks/get-next-incomplete :category "nonexistent")))
      (is (nil? (tasks/get-next-incomplete :parent-id 999)))
      (is (nil? (tasks/get-next-incomplete :title-pattern "nonexistent"))))))

;; Mutation API Tests

(deftest add-task-test
  (testing "add-task"
    (testing "adds task with generated ID"
      (reset-state!)
      (let [task (dissoc test-task-1 :id)
            new-id (tasks/add-task task)]
        (is (= 1 new-id))
        (is (= [1] @tasks/task-ids))
        (is (= task (dissoc (tasks/get-task 1) :id)))))

    (testing "increments next-id"
      (reset-state!)
      (tasks/add-task (dissoc test-task-1 :id))
      (tasks/add-task (dissoc test-task-2 :id))
      (is (= 3 @tasks/next-id)))

    (testing "appends by default"
      (reset-state!)
      (tasks/add-task (dissoc test-task-1 :id))
      (tasks/add-task (dissoc test-task-2 :id))
      (is (= [1 2] @tasks/task-ids)))

    (testing "prepends when specified"
      (reset-state!)
      (tasks/add-task (dissoc test-task-1 :id))
      (tasks/add-task (dissoc test-task-2 :id) :prepend? true)
      (is (= [2 1] @tasks/task-ids)))

    (testing "updates parent-child maps for child task"
      (reset-state!)
      (tasks/add-task (dissoc test-task-1 :id))
      (tasks/add-task (assoc (dissoc test-task-3 :id) :parent-id 1))
      (is (= {1 #{2}} @tasks/parent-children))
      (is (= {2 1} @tasks/child-parent)))

    (testing "throws on invalid task schema"
      (reset-state!)
      (is (thrown? Exception
            (tasks/add-task {:title "Missing required fields"}))))))

(deftest update-task-test
  (testing "update-task"
    (testing "updates task fields"
      (reset-state!)
      (tasks/add-task (dissoc test-task-1 :id))
      (tasks/update-task 1 {:title "Updated Title"})
      (is (= "Updated Title" (:title (tasks/get-task 1)))))

    (testing "maintains parent-child maps when parent unchanged"
      (reset-state!)
      (tasks/add-task (dissoc test-task-1 :id))
      (tasks/add-task (assoc (dissoc test-task-3 :id) :parent-id 1))
      (tasks/update-task 2 {:status :open})
      (is (= {1 #{2}} @tasks/parent-children))
      (is (= {2 1} @tasks/child-parent)))

    (testing "updates parent-child maps when parent changes"
      (reset-state!)
      (tasks/add-task (dissoc test-task-1 :id))
      (tasks/add-task (dissoc test-task-2 :id))
      (tasks/add-task (assoc (dissoc test-task-3 :id) :parent-id 1))
      (tasks/update-task 3 {:parent-id 2})
      (is (= {2 #{3}} @tasks/parent-children))
      (is (= {3 2} @tasks/child-parent)))

    (testing "removes from parent-child maps when parent becomes nil"
      (reset-state!)
      (tasks/add-task (dissoc test-task-1 :id))
      (tasks/add-task (assoc (dissoc test-task-3 :id) :parent-id 1))
      (tasks/update-task 2 {:parent-id nil})
      (is (= {} @tasks/parent-children))
      (is (= {} @tasks/child-parent)))

    (testing "throws on non-existent task"
      (reset-state!)
      (is (thrown-with-msg? Exception #"Task not found"
            (tasks/update-task 999 {:title "New"}))))

    (testing "throws on invalid schema after update"
      (reset-state!)
      (tasks/add-task (dissoc test-task-1 :id))
      (is (thrown? Exception
            (tasks/update-task 1 {:status :invalid-status}))))))

(deftest mark-complete-test
  (testing "mark-complete"
    (testing "marks task as closed"
      (tasks/add-task (dissoc test-task-1 :id))
      (tasks/mark-complete 1 nil)
      (is (= :closed (:status (tasks/get-task 1)))))

    (testing "adds completion comment when provided"
      (tasks/add-task (dissoc test-task-1 :id))
      (tasks/mark-complete 1 "Fixed the bug")
      (let [description (:description (tasks/get-task 1))]
        (is (re-find #"Completed: Fixed the bug" description))))

    (testing "does not modify description when comment is nil"
      (tasks/add-task (dissoc test-task-1 :id))
      (let [original-desc (:description (tasks/get-task 1))]
        (tasks/mark-complete 1 nil)
        (is (= original-desc (:description (tasks/get-task 1))))))

    (testing "throws on non-existent task"
      (is (thrown-with-msg? Exception #"Task not found"
            (tasks/mark-complete 999 nil))))))

(deftest delete-task-test
  (testing "delete-task"
    (testing "removes task from all state atoms"
      (reset-state!)
      (tasks/add-task (dissoc test-task-1 :id))
      (tasks/delete-task 1)
      (is (= [] @tasks/task-ids))
      (is (= {} @tasks/tasks))
      (is (nil? (tasks/get-task 1))))

    (testing "removes from parent-child maps"
      (reset-state!)
      (tasks/add-task (dissoc test-task-1 :id))
      (tasks/add-task (dissoc test-task-3 :id :parent-id) :parent-id 1)
      (tasks/delete-task 2)
      (is (= {} @tasks/parent-children))
      (is (= {} @tasks/child-parent)))

    (testing "preserves order after deletion"
      (reset-state!)
      (tasks/add-task (dissoc test-task-1 :id))
      (tasks/add-task (dissoc test-task-2 :id))
      (tasks/add-task (dissoc test-task-4 :id))
      (tasks/delete-task 2)
      (is (= [1 3] @tasks/task-ids)))

    (testing "throws on non-existent task"
      (reset-state!)
      (is (thrown-with-msg? Exception #"Task not found"
            (tasks/delete-task 999))))))

;; Persistence API Tests

(deftest load-tasks-test
  (testing "load-tasks!"
    (testing "loads tasks from file into memory"
      (let [file (temp-file)]
        (tasks-file/append-task file test-task-1)
        (tasks-file/append-task file test-task-2)
        (let [count (tasks/load-tasks! file)]
          (is (= 2 count))
          (is (= [1 2] @tasks/task-ids))
          (is (= test-task-1 (tasks/get-task 1)))
          (is (= test-task-2 (tasks/get-task 2))))))

    (testing "builds parent-child maps from file"
      (let [file (temp-file)]
        (tasks-file/append-task file test-task-1)
        (tasks-file/append-task file test-task-3)
        (tasks/load-tasks! file)
        (is (= {1 #{3}} @tasks/parent-children))
        (is (= {3 1} @tasks/child-parent))))

    (testing "updates next-id from loaded tasks"
      (let [file (temp-file)]
        (tasks-file/append-task file test-task-1)
        (tasks-file/append-task file test-task-2)
        (tasks/load-tasks! file)
        (is (= 3 @tasks/next-id))))

    (testing "resets state before loading"
      (tasks/add-task (dissoc test-task-1 :id))
      (let [file (temp-file)]
        (tasks-file/append-task file test-task-2)
        (tasks/load-tasks! file)
        (is (= [2] @tasks/task-ids))
        (is (nil? (tasks/get-task 1)))))

    (testing "returns 0 for missing file"
      (let [count (tasks/load-tasks! "/nonexistent.ednl")]
        (is (= 0 count))
        (is (= [] @tasks/task-ids))))))

(deftest save-tasks-test
  (testing "save-tasks!"
    (testing "saves tasks in task-ids order"
      (reset-state!)
      (let [file (temp-file)]
        (tasks/add-task (dissoc test-task-2 :id))
        (tasks/add-task (dissoc test-task-1 :id))
        (tasks/save-tasks! file)
        (let [loaded (tasks-file/read-ednl file)]
          (is (= 2 (count loaded)))
          (is (= 1 (:id (first loaded))))
          (is (= 2 (:id (second loaded)))))))

    (testing "returns count of tasks saved"
      (reset-state!)
      (let [file (temp-file)]
        (tasks/add-task (dissoc test-task-1 :id))
        (tasks/add-task (dissoc test-task-2 :id))
        (is (= 2 (tasks/save-tasks! file)))))

    (testing "overwrites existing file"
      (reset-state!)
      (let [file (temp-file)]
        (tasks-file/append-task file test-task-3)
        (tasks/add-task (dissoc test-task-1 :id))
        (tasks/save-tasks! file)
        (let [loaded (tasks-file/read-ednl file)]
          (is (= 1 (count loaded)))
          (is (= 1 (:id (first loaded)))))))))

(deftest move-task-test
  (testing "move-task!"
    (testing "moves task between files"
      (let [from-file (temp-file)
            to-file (temp-file)]
        (tasks-file/append-task from-file test-task-1)
        (tasks-file/append-task from-file test-task-2)
        (tasks/load-tasks! from-file)
        (tasks/move-task! 1 from-file to-file)
        (is (= [test-task-2] (tasks-file/read-ednl from-file)))
        (is (= [test-task-1] (tasks-file/read-ednl to-file)))))

    (testing "keeps task in memory after move"
      (let [from-file (temp-file)
            to-file (temp-file)]
        (tasks-file/append-task from-file test-task-1)
        (tasks/load-tasks! from-file)
        (tasks/move-task! 1 from-file to-file)
        (is (= test-task-1 (tasks/get-task 1)))))

    (testing "throws on non-existent task"
      (let [from-file (temp-file)
            to-file (temp-file)]
        (is (thrown-with-msg? Exception #"Task not found"
              (tasks/move-task! 999 from-file to-file)))))))

;; Integration Tests

(deftest workflow-integration-test
  (testing "complete workflow: add, query, update, persist"
    (let [file (temp-file)]
      ;; Add tasks
      (tasks/add-task (dissoc test-task-1 :id))
      (tasks/add-task (dissoc test-task-2 :id))
      (tasks/add-task (assoc (dissoc test-task-3 :id) :parent-id 1))

      ;; Query
      (is (= test-task-1 (tasks/get-next-incomplete-by-category "simple")))
      (is (= 1 (count (tasks/get-children 1))))

      ;; Update
      (tasks/mark-complete 1 "Done")
      (is (= :closed (:status (tasks/get-task 1))))

      ;; Persist
      (tasks/save-tasks! file)
      (let [loaded (tasks-file/read-ednl file)]
        (is (= 3 (count loaded)))
        (is (= :closed (:status (first loaded))))))))
