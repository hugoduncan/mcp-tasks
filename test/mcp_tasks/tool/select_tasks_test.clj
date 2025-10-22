(ns mcp-tasks.tool.select-tasks-test
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.test-helpers :as h]
    [mcp-tasks.tool.add-task :as add-task]
    [mcp-tasks.tool.complete-task :as complete-task]
    [mcp-tasks.tool.select-tasks :as sut]))

(defn- write-tasks-ednl
  "Helper to write tasks to tasks.ednl."
  [test-dir tasks]
  (h/write-ednl-test-file test-dir "tasks.ednl" tasks))

;; Tests from tools_test.clj

(deftest select-tasks-returns-multiple-tasks
  (h/with-test-setup [test-dir]
    ;; Test select-tasks returns all matching tasks
    (testing "select-tasks returns multiple matching tasks"
      ;; Add three tasks
      (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "test" :title "Task One"})
      (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "test" :title "Task Two"})
      (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "test" :title "Task Three"})

      (let [result   (#'sut/select-tasks-impl (h/test-config test-dir) nil {:category "test"})
            response (json/parse-string (get-in result [:content 0 :text]) keyword)]
        (is (false? (:isError result)))
        (is (= 3 (count (:tasks response))))
        (is (= 3 (get-in response [:metadata :count])))
        (is (= 3 (get-in response [:metadata :total-matches])))
        (is (false? (get-in response [:metadata :limited?])))
        (is (= ["Task One" "Task Two" "Task Three"]
               (map :title (:tasks response))))))))

(deftest select-tasks-limit-parameter
  (h/with-test-setup [test-dir]
    ;; Test :limit parameter correctly limits results
    (testing "select-tasks :limit parameter"
      ;; Add five tasks
      (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "test" :title "Task 1"})
      (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "test" :title "Task 2"})
      (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "test" :title "Task 3"})
      (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "test" :title "Task 4"})
      (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "test" :title "Task 5"})

      (testing "returns up to limit tasks"
        (let [result   (#'sut/select-tasks-impl (h/test-config test-dir) nil {:category "test" :limit 3})
              response (json/parse-string (get-in result [:content 0 :text]) keyword)]
          (is (false? (:isError result)))
          (is (= 3 (count (:tasks response))))
          (is (= 3 (get-in response [:metadata :count])))
          (is (= 5 (get-in response [:metadata :total-matches])))
          (is (true? (get-in response [:metadata :limited?])))
          (is (= ["Task 1" "Task 2" "Task 3"]
                 (map :title (:tasks response))))))

      (testing "uses default limit of 5"
        (let [result   (#'sut/select-tasks-impl (h/test-config test-dir) nil {:category "test"})
              response (json/parse-string (get-in result [:content 0 :text]) keyword)]
          (is (false? (:isError result)))
          (is (= 5 (count (:tasks response))))
          (is (= 5 (get-in response [:metadata :count])))
          (is (= 5 (get-in response [:metadata :total-matches])))
          (is (false? (get-in response [:metadata :limited?]))))))))

(deftest select-tasks-unique-constraint
  (h/with-test-setup [test-dir]
    ;; Test :unique enforces 0 or 1 task
    (testing "select-tasks :unique constraint"
      (testing "returns task when exactly one matches"
        (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "test" :title "Unique Task"})

        (let [result   (#'sut/select-tasks-impl (h/test-config test-dir) nil {:category "test" :unique true})
              response (json/parse-string (get-in result [:content 0 :text]) keyword)]
          (is (false? (:isError result)))
          (is (= 1 (count (:tasks response))))
          (is (= 1 (get-in response [:metadata :count])))
          (is (= 1 (get-in response [:metadata :total-matches])))
          (is (= "Unique Task" (get-in response [:tasks 0 :title])))))

      (testing "returns empty when no matches"
        (let [result   (#'sut/select-tasks-impl (h/test-config test-dir) nil {:category "nonexistent" :unique true})
              response (json/parse-string (get-in result [:content 0 :text]) keyword)]
          (is (false? (:isError result)))
          (is (= 0 (count (:tasks response))))
          (is (= 0 (get-in response [:metadata :count])))
          (is (= 0 (get-in response [:metadata :total-matches])))))

      (testing "returns error when multiple tasks match"
        (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "test" :title "Task One"})
        (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "test" :title "Task Two"})

        (let [result   (#'sut/select-tasks-impl (h/test-config test-dir) nil {:category "test" :unique true})
              response (json/parse-string (get-in result [:content 0 :text]) keyword)]
          (is (false? (:isError result)))
          (is (contains? response :error))
          (is (str/includes? (:error response) "Multiple tasks matched"))
          (is (= 3 (get-in response [:metadata :total-matches]))))))))

(deftest select-tasks-validation-errors
  (h/with-test-setup [test-dir]
    ;; Test parameter validation errors
    (testing "select-tasks validation errors"
      (testing "non-positive limit returns error"
        (let [result   (#'sut/select-tasks-impl (h/test-config test-dir) nil {:limit 0})
              response (json/parse-string (get-in result [:content 0 :text]) keyword)]
          (is (false? (:isError result)))
          (is (contains? response :error))
          (is (str/includes? (:error response) "positive integer"))
          (is (= 0 (get-in response [:metadata :provided-limit])))))

      (testing "negative limit returns error"
        (let [result   (#'sut/select-tasks-impl (h/test-config test-dir) nil {:limit -5})
              response (json/parse-string (get-in result [:content 0 :text]) keyword)]
          (is (false? (:isError result)))
          (is (contains? response :error))
          (is (str/includes? (:error response) "positive integer"))))

      (testing "limit > 1 with unique true returns error"
        (let [result   (#'sut/select-tasks-impl (h/test-config test-dir) nil {:limit 5 :unique true})
              response (json/parse-string (get-in result [:content 0 :text]) keyword)]
          (is (false? (:isError result)))
          (is (contains? response :error))
          (is (str/includes? (:error response) "limit must be 1 when unique"))
          (is (= 5 (get-in response [:metadata :provided-limit])))
          (is (true? (get-in response [:metadata :unique]))))))))

(deftest select-tasks-empty-results
  (h/with-test-setup [test-dir]
    ;; Test empty results return empty tasks vector
    (testing "select-tasks empty results"
      (let [result   (#'sut/select-tasks-impl (h/test-config test-dir) nil {:category "nonexistent"})
            response (json/parse-string (get-in result [:content 0 :text]) keyword)]
        (is (false? (:isError result)))
        (is (= [] (:tasks response)))
        (is (= 0 (get-in response [:metadata :count])))
        (is (= 0 (get-in response [:metadata :total-matches])))
        (is (false? (get-in response [:metadata :limited?])))))))

(deftest select-tasks-metadata-accuracy
  (h/with-test-setup [test-dir]
    ;; Test metadata contains accurate information
    (testing "select-tasks metadata accuracy"
      ;; Add tasks
      (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "test" :title "Task A"})
      (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "test" :title "Task B"})
      (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "other" :title "Task C"})

      (testing "metadata reflects filtering and limiting"
        (let [result   (#'sut/select-tasks-impl (h/test-config test-dir) nil {:category "test" :limit 1})
              response (json/parse-string (get-in result [:content 0 :text]) keyword)]
          (is (= 1 (get-in response [:metadata :count])))
          (is (= 2 (get-in response [:metadata :total-matches])))
          (is (true? (get-in response [:metadata :limited?])))))

      (testing "metadata when not limited"
        (let [result   (#'sut/select-tasks-impl (h/test-config test-dir) nil {:category "test" :limit 10})
              response (json/parse-string (get-in result [:content 0 :text]) keyword)]
          (is (= 2 (get-in response [:metadata :count])))
          (is (= 2 (get-in response [:metadata :total-matches])))
          (is (false? (get-in response [:metadata :limited?]))))))))

(deftest select-tasks-type-filter
  (h/with-test-setup [test-dir]
    ;; Test :type parameter filters tasks by type
    (testing "select-tasks :type filter"
      ;; Add tasks with different types
      (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "test" :title "Regular task" :type "task"})
      (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "test" :title "Bug fix" :type "bug"})
      (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "test" :title "New feature" :type "feature"})
      (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "test" :title "Another task" :type "task"})

      (testing "filters by type task"
        (let [result   (#'sut/select-tasks-impl (h/test-config test-dir) nil {:type "task"})
              response (json/parse-string (get-in result [:content 0 :text]) keyword)]
          (is (false? (:isError result)))
          (is (= 2 (count (:tasks response))))
          (is (= ["Regular task" "Another task"]
                 (map :title (:tasks response))))))

      (testing "filters by type bug"
        (let [result   (#'sut/select-tasks-impl (h/test-config test-dir) nil {:type "bug"})
              response (json/parse-string (get-in result [:content 0 :text]) keyword)]
          (is (false? (:isError result)))
          (is (= 1 (count (:tasks response))))
          (is (= "Bug fix" (get-in response [:tasks 0 :title])))))

      (testing "filters by type feature"
        (let [result   (#'sut/select-tasks-impl (h/test-config test-dir) nil {:type "feature"})
              response (json/parse-string (get-in result [:content 0 :text]) keyword)]
          (is (false? (:isError result)))
          (is (= 1 (count (:tasks response))))
          (is (= "New feature" (get-in response [:tasks 0 :title])))))

      (testing "combines type filter with category filter"
        (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "other" :title "Other bug" :type "bug"})

        (let [result   (#'sut/select-tasks-impl (h/test-config test-dir) nil {:category "test" :type "bug"})
              response (json/parse-string (get-in result [:content 0 :text]) keyword)]
          (is (false? (:isError result)))
          (is (= 1 (count (:tasks response))))
          (is (= "Bug fix" (get-in response [:tasks 0 :title]))))))))

(deftest select-tasks-status-filter
  (h/with-test-setup [test-dir]
    ;; Test :status parameter filters tasks by status
    (testing "select-tasks :status filter"
      (testing "filters by status open (default behavior)"
        ;; Add tasks and complete one
        (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "test" :title "Open task 1"})
        (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "test" :title "Open task 2"})
        (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "test" :title "To be closed"})
        ;; Complete one task
        (#'complete-task/complete-task-impl (h/test-config test-dir) nil {:title "To be closed"})

        ;; Without status filter, should only return open tasks
        (let [result   (#'sut/select-tasks-impl (h/test-config test-dir) nil {:category "test"})
              response (json/parse-string (get-in result [:content 0 :text]) keyword)]
          (is (false? (:isError result)))
          (is (= 2 (count (:tasks response))))
          (is (= ["Open task 1" "Open task 2"]
                 (map :title (:tasks response)))))))))

(deftest select-tasks-status-open-explicit
  (h/with-test-setup [test-dir]
    ;; Test explicitly filtering by status open
    (testing "select-tasks :status filter"
      (testing "explicitly filters by status open"
        (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "test" :title "Open task"})
        (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "test" :title "To close"})
        (#'complete-task/complete-task-impl (h/test-config test-dir) nil {:title "To close"})

        ;; Explicitly filter by open status
        (let [result   (#'sut/select-tasks-impl (h/test-config test-dir) nil {:status "open"})
              response (json/parse-string (get-in result [:content 0 :text]) keyword)]
          (is (false? (:isError result)))
          (is (= 1 (count (:tasks response))))
          (is (= "Open task" (get-in response [:tasks 0 :title])))
          (is (= "open" (name (get-in response [:tasks 0 :status])))))))))

(deftest select-tasks-status-in-progress
  (h/with-test-setup [test-dir]
    ;; Test filtering by status in-progress
    (testing "select-tasks :status filter"
      (testing "filters by status in-progress"
        ;; Write tasks with different statuses directly to file
        (h/write-ednl-test-file
          test-dir
          "tasks.ednl"
          [{:id 1 :parent-id nil :title "Open task" :description "" :design "" :category "test" :type :task :status :open :meta {} :relations []}
           {:id 2 :parent-id nil :title "In progress task 1" :description "" :design "" :category "test" :type :task :status :in-progress :meta {} :relations []}
           {:id 3 :parent-id nil :title "In progress task 2" :description "" :design "" :category "test" :type :task :status :in-progress :meta {} :relations []}])

        ;; Filter by in-progress status
        (let [result   (#'sut/select-tasks-impl (h/test-config test-dir) nil {:status "in-progress"})
              response (json/parse-string (get-in result [:content 0 :text]) keyword)]
          (is (false? (:isError result)))
          (is (= 2 (count (:tasks response))))
          (is (= #{"In progress task 1" "In progress task 2"}
                 (set (map :title (:tasks response)))))
          (is (every? #(= "in-progress" (name (:status %))) (:tasks response))))))))

(deftest select-tasks-status-with-category
  (h/with-test-setup [test-dir]
    ;; Test combining status filter with category filter
    (testing "select-tasks :status filter"
      (testing "combines status filter with category filter"
        (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "test" :title "Test open"})
        (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "other" :title "Other open"})
        (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "test" :title "Test to close"})
        (#'complete-task/complete-task-impl (h/test-config test-dir) nil {:title "Test to close"})

        ;; Filter by category test and status open
        (let [result   (#'sut/select-tasks-impl (h/test-config test-dir) nil {:category "test" :status "open"})
              response (json/parse-string (get-in result [:content 0 :text]) keyword)]
          (is (false? (:isError result)))
          (is (= 1 (count (:tasks response))))
          (is (= "Test open" (get-in response [:tasks 0 :title]))))))))

(deftest select-tasks-task-id-filter
  (h/with-test-setup [test-dir]
    ;; Test :task-id parameter filters tasks by ID
    (testing "select-tasks :task-id filter"
      ;; Add tasks
      (let [task1      (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "test" :title "Task One"})
            task1-data (json/parse-string (get-in task1 [:content 1 :text]) keyword)
            task1-id   (get-in task1-data [:task :id])

            task2      (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "test" :title "Task Two"})
            task2-data (json/parse-string (get-in task2 [:content 1 :text]) keyword)
            task2-id   (get-in task2-data [:task :id])

            task3      (#'add-task/add-task-impl (h/test-config test-dir) nil {:category "other" :title "Task Three"})
            task3-data (json/parse-string (get-in task3 [:content 1 :text]) keyword)
            task3-id   (get-in task3-data [:task :id])]

        (testing "filters by task-id to return single task"
          (let [result   (#'sut/select-tasks-impl (h/test-config test-dir) nil {:task-id task1-id})
                response (json/parse-string (get-in result [:content 0 :text]) keyword)]
            (is (false? (:isError result)))
            (is (= 1 (count (:tasks response))))
            (is (= task1-id (get-in response [:tasks 0 :id])))
            (is (= "Task One" (get-in response [:tasks 0 :title])))))

        (testing "returns empty when task-id does not exist"
          (let [result   (#'sut/select-tasks-impl (h/test-config test-dir) nil {:task-id 99999})
                response (json/parse-string (get-in result [:content 0 :text]) keyword)]
            (is (false? (:isError result)))
            (is (= 0 (count (:tasks response))))
            (is (= 0 (get-in response [:metadata :count])))
            (is (= 0 (get-in response [:metadata :total-matches])))))

        (testing "combines task-id filter with category filter"
          ;; Task 2 has category "test", task 3 has category "other"
          ;; Filtering by task3-id and category "test" should return empty
          (let [result   (#'sut/select-tasks-impl (h/test-config test-dir) nil {:task-id task3-id :category "test"})
                response (json/parse-string (get-in result [:content 0 :text]) keyword)]
            (is (false? (:isError result)))
            (is (= 0 (count (:tasks response)))))

          ;; Filtering by task3-id and category "other" should return task 3
          (let [result   (#'sut/select-tasks-impl (h/test-config test-dir) nil {:task-id task3-id :category "other"})
                response (json/parse-string (get-in result [:content 0 :text]) keyword)]
            (is (false? (:isError result)))
            (is (= 1 (count (:tasks response))))
            (is (= task3-id (get-in response [:tasks 0 :id])))
            (is (= "Task Three" (get-in response [:tasks 0 :title])))))

        (testing "task-id filter works with unique constraint"
          (let [result   (#'sut/select-tasks-impl (h/test-config test-dir) nil {:task-id task2-id :unique true})
                response (json/parse-string (get-in result [:content 0 :text]) keyword)]
            (is (false? (:isError result)))
            (is (= 1 (count (:tasks response))))
            (is (= task2-id (get-in response [:tasks 0 :id])))
            (is (= "Task Two" (get-in response [:tasks 0 :title])))))))))

(deftest select-tasks-returns-first-incomplete-child-by-parent-id
  (h/with-test-setup [test-dir]
    ;; Test that select-tasks with parent-id filter returns the first incomplete child task
    (testing "select-tasks with parent-id filter"
      (testing "returns first incomplete child task"
        (let [story             {:id          1
                                 :type        :story
                                 :title       "test-story"
                                 :description ""
                                 :design      ""
                                 :category    "story"
                                 :status      :open
                                 :meta        {}
                                 :relations   []}
              completed-task    {:id          2
                                 :parent-id   1
                                 :type        :task
                                 :title       "Already done"
                                 :description ""
                                 :design      ""
                                 :category    "simple"
                                 :status      :closed
                                 :meta        {}
                                 :relations   []}
              first-incomplete  {:id          3
                                 :parent-id   1
                                 :type        :task
                                 :title       "First incomplete"
                                 :description "With details"
                                 :design      ""
                                 :category    "medium"
                                 :status      :open
                                 :meta        {}
                                 :relations   []}
              second-incomplete {:id          4
                                 :parent-id   1
                                 :type        :task
                                 :title       "Second incomplete"
                                 :description ""
                                 :design      ""
                                 :category    "simple"
                                 :status      :open
                                 :meta        {}
                                 :relations   []}]
          (write-tasks-ednl
            test-dir
            [story completed-task first-incomplete second-incomplete])
          (let [config {:base-dir test-dir :use-git? false}
                result (#'sut/select-tasks-impl
                        config
                        nil
                        {:parent-id 1 :limit 1})]
            (is (false? (:isError result)))
            (let [response (json/parse-string (get-in result [:content 0 :text]) keyword)
                  task     (first (:tasks response))]
              (is (= "First incomplete" (:title task)))
              (is (= "With details" (:description task)))
              (is (= "medium" (:category task)))
              (is (= 3 (:id task))))))))))

(deftest select-tasks-returns-no-match-when-no-incomplete-children
  (h/with-test-setup [test-dir]
    ;; Test that select-tasks with parent-id filter returns empty tasks when no
    ;; incomplete children
    (testing "select-tasks with parent-id filter"
      (testing "returns empty tasks when no incomplete children"
        (let [story          {:id          1
                              :type        :story
                              :title       "test-story"
                              :description ""
                              :design      ""
                              :category    "story"
                              :status      :open
                              :meta        {}
                              :relations   []}
              completed-task {:id          2
                              :parent-id   1
                              :type        :task
                              :title       "Already done"
                              :description ""
                              :design      ""
                              :category    "simple"
                              :status      :closed
                              :meta        {}
                              :relations   []}]
          (write-tasks-ednl test-dir [story completed-task])
          (let [config {:base-dir test-dir :use-git? false}
                result (#'sut/select-tasks-impl
                        config
                        nil
                        {:parent-id 1})]
            (is (false? (:isError result)))
            (let [response (json/parse-string (get-in result [:content 0 :text]) keyword)]
              (is (empty? (:tasks response))))))))))

(deftest select-tasks-by-title-pattern-finds-story
  (h/with-test-setup [test-dir]
    ;; Test that select-tasks with title-pattern can find story tasks
    (testing "select-tasks with title-pattern filter"
      (testing "finds story by title pattern"
        (let [story      {:id          1
                          :type        :story
                          :title       "test-story"
                          :description ""
                          :design      ""
                          :category    "story"
                          :status      :open
                          :meta        {}
                          :relations   []}
              other-task {:id          2
                          :type        :task
                          :title       "Some other task"
                          :description ""
                          :design      ""
                          :category    "simple"
                          :status      :open
                          :meta        {}
                          :relations   []}]
          (write-tasks-ednl test-dir [story other-task])
          (let [config {:base-dir test-dir :use-git? false}
                result (#'sut/select-tasks-impl
                        config
                        nil
                        {:title-pattern "test-story" :limit 1})]
            (is (false? (:isError result)))
            (let [response (json/parse-string (get-in result [:content 0 :text]) keyword)
                  task     (first (:tasks response))]
              (is (= "test-story" (:title task)))
              (is (= "story" (:category task)))
              (is (= 1 (:id task))))))))))

(deftest select-tasks-combines-multiple-filters
  (h/with-test-setup [test-dir]
    ;; Test that select-tasks can combine parent-id and category filters
    (testing "select-tasks with multiple filters"
      (testing "combines parent-id and category filters"
        (let [story       {:id          1
                           :type        :story
                           :title       "test-story"
                           :description ""
                           :design      ""
                           :category    "story"
                           :status      :open
                           :meta        {}
                           :relations   []}
              simple-task {:id          2
                           :parent-id   1
                           :type        :task
                           :title       "Simple task"
                           :description ""
                           :design      ""
                           :category    "simple"
                           :status      :open
                           :meta        {}
                           :relations   []}
              medium-task {:id          3
                           :parent-id   1
                           :type        :task
                           :title       "Medium task"
                           :description ""
                           :design      ""
                           :category    "medium"
                           :status      :open
                           :meta        {}
                           :relations   []}]
          (write-tasks-ednl test-dir [story simple-task medium-task])
          (let [config {:base-dir test-dir :use-git? false}
                result (#'sut/select-tasks-impl
                        config
                        nil
                        {:parent-id 1 :category "medium" :limit 1})]
            (is (false? (:isError result)))
            (let [response (json/parse-string (get-in result [:content 0 :text]) keyword)
                  task     (first (:tasks response))]
              (is (= "Medium task" (:title task)))
              (is (= "medium" (:category task)))
              (is (= 3 (:id task))))))))))
