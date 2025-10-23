(ns mcp-tasks.tasks-test
  (:require
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

(deftest get-tasks-test
  ;; Test get-tasks returns multiple matching tasks in order
  (testing "get-tasks"
    (testing "returns empty vector when no tasks exist"
      (is (= [] (tasks/get-tasks))))

    (testing "returns all incomplete tasks in order"
      (reset! tasks/task-ids [1 2 4])
      (reset! tasks/tasks {1 test-task-1
                           2 test-task-2
                           4 test-task-4})
      (let [result (tasks/get-tasks)]
        (is (= 3 (count result)))
        (is (= [1 2 4] (map :id result)))))

    (testing "filters by category and returns multiple matches"
      (reset! tasks/task-ids [1 2 4])
      (reset! tasks/tasks {1 test-task-1
                           2 test-task-2
                           4 test-task-4})
      (let [result (tasks/get-tasks :category "simple")]
        (is (= 2 (count result)))
        (is (= [1 4] (map :id result)))))

    (testing "filters by parent-id and returns multiple children"
      (let [child-1 (assoc test-task-3 :id 5 :status :open :parent-id 1)
            child-2 (assoc test-task-3 :id 6 :status :open :parent-id 1)]
        (reset! tasks/task-ids [1 5 6])
        (reset! tasks/tasks {1 test-task-1
                             5 child-1
                             6 child-2})
        (let [result (tasks/get-tasks :parent-id 1)]
          (is (= 2 (count result)))
          (is (= [5 6] (map :id result))))))

    (testing "filters by title-pattern with substring match"
      (reset! tasks/task-ids [1 2 4])
      (reset! tasks/tasks {1 test-task-1
                           2 test-task-2
                           4 test-task-4})
      (let [result (tasks/get-tasks :title-pattern "Task")]
        (is (= 3 (count result)))
        (is (= [1 2 4] (map :id result)))))

    (testing "filters by title-pattern with regex"
      (reset! tasks/task-ids [1 2 4])
      (reset! tasks/tasks {1 test-task-1
                           2 test-task-2
                           4 test-task-4})
      (let [result (tasks/get-tasks :title-pattern "Task [12]")]
        (is (= 2 (count result)))
        (is (= [1 2] (map :id result)))))

    (testing "combines multiple filters with AND"
      (let [task-5 (assoc test-task-1 :id 5 :category "simple" :title "Simple A")
            task-6 (assoc test-task-2 :id 6 :category "simple" :title "Simple B")]
        (reset! tasks/task-ids [1 5 6])
        (reset! tasks/tasks {1 test-task-1
                             5 task-5
                             6 task-6})
        (let [result (tasks/get-tasks :category "simple" :title-pattern "Simple")]
          (is (= 2 (count result)))
          (is (= [5 6] (map :id result))))))

    (testing "skips closed tasks"
      (reset! tasks/task-ids [3 1 2])
      (reset! tasks/tasks {3 test-task-3
                           1 test-task-1
                           2 test-task-2})
      (let [result (tasks/get-tasks)]
        (is (= 2 (count result)))
        (is (= [1 2] (map :id result)))))

    (testing "returns empty vector when no tasks match filters"
      (reset! tasks/task-ids [1])
      (reset! tasks/tasks {1 test-task-1})
      (is (= [] (tasks/get-tasks :category "nonexistent")))
      (is (= [] (tasks/get-tasks :parent-id 999)))
      (is (= [] (tasks/get-tasks :title-pattern "nonexistent"))))

    (testing "preserves task order from tasks.ednl"
      (reset! tasks/task-ids [4 1 2])
      (reset! tasks/tasks {4 test-task-4
                           1 test-task-1
                           2 test-task-2})
      (let [result (tasks/get-tasks)]
        (is (= [4 1 2] (map :id result)))))))

(deftest get-tasks-with-status-filter-ordering-test
  ;; Test that get-tasks preserves file order when status filter is applied
  (testing "get-tasks with status filter"
    (testing "preserves file order when filtering by :open status"
      (let [task-a (assoc test-task-1 :id 5 :status :open)
            task-b (assoc test-task-2 :id 3 :status :open)
            task-c (assoc test-task-4 :id 7 :status :open)]
        ;; Set up task-ids in specific order: 5, 3, 7
        (reset! tasks/task-ids [5 3 7])
        (reset! tasks/tasks {5 task-a
                             3 task-b
                             7 task-c})
        ;; Query with explicit :open status should preserve order
        (let [result (tasks/get-tasks :status :open)]
          (is (= 3 (count result)))
          (is (= [5 3 7] (map :id result))
              "Tasks should be returned in file order (5, 3, 7), not sorted by ID"))))

    (testing "preserves file order when filtering :open with parent-id"
      (let [task-a (assoc test-task-1 :id 170 :status :open :parent-id 165)
            task-b (assoc test-task-2 :id 169 :status :open :parent-id 165)]
        ;; File order: 169 before 170
        (reset! tasks/task-ids [169 170])
        (reset! tasks/tasks {169 task-b
                             170 task-a})
        ;; Query with both parent-id and status filters
        (let [result (tasks/get-tasks :parent-id 165 :status :open)]
          (is (= 2 (count result)))
          (is (= [169 170] (map :id result))
              "Should return task 169 first (as it appears first in file), not 170"))))

    (testing "preserves order when searching both active and completed tasks"
      (let [active-1 (assoc test-task-1 :id 10 :status :open)
            active-2 (assoc test-task-2 :id 5 :status :open)
            complete-1 (assoc test-task-3 :id 15 :status :closed)
            complete-2 (assoc test-task-4 :id 3 :status :closed)]
        ;; Active tasks order: 10, 5
        ;; Completed tasks order: 15, 3
        (reset! tasks/task-ids [10 5])
        (reset! tasks/complete-task-ids [15 3])
        (reset! tasks/tasks {10 active-1
                             5 active-2
                             15 complete-1
                             3 complete-2})
        ;; Query for closed tasks should use complete-task-ids order
        (let [result (tasks/get-tasks :status :closed)]
          (is (= 2 (count result)))
          (is (= [15 3] (map :id result))
              "Completed tasks should be in complete.ednl file order"))))

    (testing "returns all tasks when status is :any"
      (let [active-1 (assoc test-task-1 :id 10 :status :open)
            active-2 (assoc test-task-2 :id 5 :status :in-progress)
            complete-1 (assoc test-task-3 :id 15 :status :closed)
            complete-2 (assoc test-task-4 :id 3 :status :closed)]
        ;; Active tasks order: 10, 5
        ;; Completed tasks order: 15, 3
        (reset! tasks/task-ids [10 5])
        (reset! tasks/complete-task-ids [15 3])
        (reset! tasks/tasks {10 active-1
                             5 active-2
                             15 complete-1
                             3 complete-2})
        ;; Query with :status :any should return all tasks
        (let [result (tasks/get-tasks :status :any)]
          (is (= 4 (count result)))
          (is (= [10 5 15 3] (map :id result))
              "Should return all tasks in file order (active then complete)"))))

    (testing ":status :any respects other filters"
      (let [active-1 (assoc test-task-1 :id 10 :status :open :parent-id 1)
            active-2 (assoc test-task-2 :id 5 :status :open :parent-id 2)
            complete-1 (assoc test-task-3 :id 15 :status :closed :parent-id 1)
            complete-2 (assoc test-task-4 :id 3 :status :closed :parent-id 2)]
        (reset! tasks/task-ids [10 5])
        (reset! tasks/complete-task-ids [15 3])
        (reset! tasks/tasks {10 active-1
                             5 active-2
                             15 complete-1
                             3 complete-2})
        ;; Query with :status :any and :parent-id filter
        (let [result (tasks/get-tasks :parent-id 1 :status :any)]
          (is (= 2 (count result)))
          (is (= [10 15] (map :id result))
              "Should return only parent-id 1 tasks (both open and closed)"))))))

;; Mutation API Tests

(deftest add-task-test
  (testing "add-task"
    (testing "adds task with generated ID"
      (reset-state!)
      (let [task (dissoc test-task-1 :id)
            created-task (tasks/add-task task)]
        (is (= 1 (:id created-task)))
        (is (= [1] @tasks/task-ids))
        (is (= task (dissoc created-task :id)))))

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

    (testing "removes task from memory after move"
      (let [from-file (temp-file)
            to-file (temp-file)]
        (tasks-file/append-task from-file test-task-1)
        (tasks/load-tasks! from-file)
        (tasks/move-task! 1 from-file to-file)
        (is (nil? (tasks/get-task 1)))))

    (testing "throws on non-existent task"
      (let [from-file (temp-file)
            to-file (temp-file)]
        (is (thrown-with-msg? Exception #"Task not found"
              (tasks/move-task! 999 from-file to-file)))))))

(deftest load-tasks-with-complete-file-test
  ;; Test load-tasks! considers both active and completed tasks for ID generation
  (testing "load-tasks! with complete-file"
    (testing "sets next-id from active tasks when no complete-file"
      (let [file (temp-file)]
        (tasks-file/append-task file test-task-1)
        (tasks-file/append-task file test-task-2)
        (tasks/load-tasks! file)
        (is (= 3 @tasks/next-id))))

    (testing "sets next-id from complete-file when it has higher IDs"
      (let [tasks-file-path (temp-file)
            complete-file-path (temp-file)
            high-id-task (assoc test-task-1 :id 100)]
        (tasks-file/append-task tasks-file-path test-task-1)
        (tasks-file/append-task tasks-file-path test-task-2)
        (tasks-file/append-task complete-file-path high-id-task)
        (tasks/load-tasks! tasks-file-path :complete-file complete-file-path)
        (is (= 101 @tasks/next-id))))

    (testing "uses max ID across both files"
      (let [tasks-file-path (temp-file)
            complete-file-path (temp-file)
            high-id-active (assoc test-task-1 :id 50)
            high-id-complete (assoc test-task-2 :id 100)]
        (tasks-file/append-task tasks-file-path high-id-active)
        (tasks-file/append-task complete-file-path high-id-complete)
        (tasks/load-tasks! tasks-file-path :complete-file complete-file-path)
        (is (= 101 @tasks/next-id))))

    (testing "handles empty complete-file"
      (let [tasks-file-path (temp-file)
            complete-file-path (temp-file)]
        (tasks-file/append-task tasks-file-path test-task-1)
        (tasks/load-tasks! tasks-file-path :complete-file complete-file-path)
        (is (= 2 @tasks/next-id))))

    (testing "handles missing complete-file"
      (let [tasks-file-path (temp-file)]
        (tasks-file/append-task tasks-file-path test-task-1)
        (tasks/load-tasks! tasks-file-path :complete-file "/nonexistent.ednl")
        (is (= 2 @tasks/next-id))))

    (testing "handles empty active tasks with completed tasks"
      (let [tasks-file-path (temp-file)
            complete-file-path (temp-file)]
        (tasks-file/append-task complete-file-path test-task-1)
        (tasks-file/append-task complete-file-path test-task-2)
        (tasks/load-tasks! tasks-file-path :complete-file complete-file-path)
        (is (= 3 @tasks/next-id))
        (is (= [] @tasks/task-ids))))))

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

(deftest id-monotonicity-integration-test
  ;; Test that task IDs remain monotonic after completing tasks
  (testing "task IDs are always monotonically increasing"
    (let [tasks-file-path (temp-file)
          complete-file-path (temp-file)]
      ;; Add initial tasks
      (tasks-file/append-task tasks-file-path test-task-1)
      (tasks-file/append-task tasks-file-path test-task-2)

      ;; Load tasks
      (tasks/load-tasks! tasks-file-path :complete-file complete-file-path)
      (is (= 3 @tasks/next-id))

      ;; Complete task 1 (simulate move to complete file)
      (tasks/move-task! 1 tasks-file-path complete-file-path)

      ;; Reload tasks (simulating a new session)
      (tasks/load-tasks! tasks-file-path :complete-file complete-file-path)

      ;; Verify next-id considers the completed task
      (is (= 3 @tasks/next-id))

      ;; Add a new task
      (let [new-task (tasks/add-task (dissoc test-task-3 :id))]
        ;; New task ID should be 3, not reusing ID 1
        (is (= 3 (:id new-task)))
        (is (= 4 @tasks/next-id)))))

  (testing "no ID collision after multiple completions"
    (let [tasks-file-path (temp-file)
          complete-file-path (temp-file)]
      ;; Create tasks with IDs 1-5
      (doseq [id (range 1 6)]
        (tasks-file/append-task tasks-file-path (assoc test-task-1 :id id)))

      ;; Load tasks
      (tasks/load-tasks! tasks-file-path :complete-file complete-file-path)
      (is (= 6 @tasks/next-id))

      ;; Complete tasks 1-3
      (doseq [id (range 1 4)]
        (tasks/move-task! id tasks-file-path complete-file-path))

      ;; Reload
      (tasks/load-tasks! tasks-file-path :complete-file complete-file-path)

      ;; Verify next-id still considers all completed tasks
      (is (= 6 @tasks/next-id))

      ;; Add new tasks
      (let [task-6 (tasks/add-task (dissoc test-task-1 :id))
            task-7 (tasks/add-task (dissoc test-task-1 :id))]
        ;; New tasks should have IDs 6 and 7, not 1 and 2
        (is (= 6 (:id task-6)))
        (is (= 7 (:id task-7)))
        (is (= 8 @tasks/next-id))))))
