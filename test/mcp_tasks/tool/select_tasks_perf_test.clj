(ns mcp-tasks.tool.select-tasks-perf-test
  "Performance tests for batch vs single-task blocked status enrichment."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.tasks :as tasks]
    [mcp-tasks.test-helpers :as h]
    [mcp-tasks.tool.add-task :as add-task]
    [mcp-tasks.tool.update-task :as update-task]))

(defn- create-task-with-blocking
  "Create a task and optionally add blocked-by relations."
  [test-dir category title blocked-by-ids]
  (let [add-result (#'add-task/add-task-impl
                    (h/test-config test-dir)
                    nil
                    {:category category :title title})
        data-content (second (:content add-result))
        data (json/parse-string (:text data-content) keyword)
        task-id (get-in data [:task :id])]
    (when (seq blocked-by-ids)
      (let [relations (mapv (fn [id]
                              {:id (inc (rand-int 10000))
                               :relates-to id
                               :as-type :blocked-by})
                            blocked-by-ids)]
        (#'update-task/update-task-impl
         (h/test-config test-dir)
         nil
         {:task-id task-id :relations relations})))
    task-id))

(defn- create-test-tasks
  "Create N tasks with various blocking relationships.

  Returns vector of task IDs in creation order."
  [test-dir n]
  (let [task-ids (atom [])]
    ;; Create first 20 tasks with no blocking
    (dotimes [i (min 20 n)]
      (swap! task-ids conj
             (create-task-with-blocking test-dir "test" (str "Task " (inc i)) [])))

    ;; Create remaining tasks with blocking relationships
    (dotimes [i (- n 20)]
      (let [blocking-id (rand-nth @task-ids)]
        (swap! task-ids conj
               (create-task-with-blocking test-dir "test"
                                          (str "Blocked Task " (inc i))
                                          [blocking-id]))))
    @task-ids))

(deftest batch-enrichment-produces-same-results-as-single
  ;; Test batch enrichment produces identical results to single-task enrichment
  (h/with-test-setup [test-dir]
    (testing "Batch enrichment matches single-task enrichment"
      (let [task-ids (create-test-tasks test-dir 50)
            ;; Get tasks using batch enrichment
            batch-results (tasks/is-tasks-blocked? task-ids)
            ;; Get tasks using single-task enrichment
            single-results (into {}
                                 (map (fn [tid]
                                        [tid (tasks/is-task-blocked? tid)])
                                      task-ids))]

        ;; Verify all tasks have identical results
        (doseq [tid task-ids]
          (let [batch-info (get batch-results tid)
                single-info (get single-results tid)]
            (is (= (:blocked? batch-info) (:blocked? single-info))
                (str "Task " tid " blocked? mismatch"))
            (is (= (set (:blocking-ids batch-info))
                   (set (:blocking-ids single-info)))
                (str "Task " tid " blocking-ids mismatch"))
            (is (= (:error batch-info) (:error single-info))
                (str "Task " tid " error mismatch"))
            (is (= (:circular-dependency batch-info)
                   (:circular-dependency single-info))
                (str "Task " tid " circular-dependency mismatch"))))))))

(deftest batch-enrichment-performance
  ;; Test batch enrichment is faster than single-task enrichment for large sets
  (h/with-test-setup [test-dir]
    (testing "Batch enrichment performance with 100+ tasks"
      (let [task-ids (create-test-tasks test-dir 120)
            ;; Warm up
            _ (tasks/is-tasks-blocked? (take 10 task-ids))
            _ (doseq [tid (take 10 task-ids)]
                (tasks/is-task-blocked? tid))

            ;; Benchmark batch enrichment
            batch-start (System/nanoTime)
            batch-results (tasks/is-tasks-blocked? task-ids)
            batch-duration (/ (- (System/nanoTime) batch-start) 1e6)

            ;; Benchmark single-task enrichment
            single-start (System/nanoTime)
            _ (doseq [tid task-ids]
                (tasks/is-task-blocked? tid))
            single-duration (/ (- (System/nanoTime) single-start) 1e6)]

        ;; Verify batch version completed
        (is (= (count batch-results) (count task-ids))
            "Batch enrichment returned all tasks")

        ;; Log performance results
        (println (format "\n  Batch: %.2fms" batch-duration))
        (println (format "  Single: %.2fms" single-duration))
        (println (format "  Speedup: %.2fx" (/ single-duration batch-duration)))

        ;; Batch should be at least as fast as single (allow 10% margin for noise)
        (is (<= batch-duration (* 1.1 single-duration))
            (format "Batch (%.2fms) should be faster than single (%.2fms)"
                    batch-duration single-duration))))))

(deftest batch-enrichment-empty-collection
  ;; Test batch enrichment handles empty collections
  (h/with-test-setup [_test-dir]
    (testing "Batch enrichment with empty collection"
      (let [result (tasks/is-tasks-blocked? [])]
        (is (map? result))
        (is (empty? result))))))
