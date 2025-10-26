(ns mcp-tasks.cli.format-test
  "Tests for CLI output formatting."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest testing is]]
    [mcp-tasks.cli.format :as sut]))

;; Test fixtures

(def sample-task
  {:id 42
   :status :open
   :title "Fix parser bug"
   :description "The CSV parser fails on quoted fields"
   :design "Use regex to handle quotes"
   :category "simple"
   :type :bug
   :parent-id 31
   :meta {"priority" "high"}
   :relations [{:id 1 :relates-to 45 :as-type :blocked-by}
               {:id 2 :relates-to 50 :as-type :related}]})

(def minimal-task
  {:id 1
   :status :closed
   :title "Simple task"
   :description ""
   :design ""
   :category "simple"
   :type :task
   :meta {}
   :relations []})

(def task-list-response
  {:tasks [sample-task minimal-task]
   :metadata {:open-task-count 5 :returned-count 2 :total-matches 5 :limited? true}})

(def single-task-response
  {:tasks [sample-task]
   :metadata {:count 1 :total-matches 1 :limited? false}})

(def empty-task-response
  {:tasks []
   :metadata {:count 0 :total-matches 0 :limited? false}})

(def error-response
  {:error "Task not found"
   :metadata {:task-id 999
              :file "/path/to/tasks.ednl"
              :attempted-operation "select-tasks"}})

(def add-task-response
  {:task (select-keys sample-task [:id :title :category :type :status :parent-id])
   :metadata {:file "/path/to/tasks.ednl" :operation "add-task"}})

(def delete-task-response
  {:deleted sample-task
   :metadata {:count 1 :status "deleted"}})

(def git-success-response
  {:tasks [sample-task]
   :metadata {:count 1 :total-matches 1}
   :git-status "success"
   :git-commit "abc123def456"})

(def git-error-response
  {:tasks [sample-task]
   :metadata {:count 1 :total-matches 1}
   :git-status "error"
   :git-error "nothing to commit"})

;; Helper function tests

(deftest kebab->camel-test
  (testing "kebab->camel"
    (testing "converts single-word keywords"
      (is (= "status" (sut/kebab->camel :status)))
      (is (= "id" (sut/kebab->camel :id))))

    (testing "converts multi-word kebab-case"
      (is (= "taskId" (sut/kebab->camel :task-id)))
      (is (= "parentId" (sut/kebab->camel :parent-id)))
      (is (= "asType" (sut/kebab->camel :as-type)))
      (is (= "relatesTo" (sut/kebab->camel :relates-to))))))

(deftest transform-keys-test
  (testing "transform-keys"
    (testing "transforms simple map"
      (is (= {"taskId" 42 "status" :open}
             (sut/transform-keys {:task-id 42 :status :open} sut/kebab->camel))))

    (testing "transforms nested maps"
      (is (= {"task" {"taskId" 1 "parentId" 2}}
             (sut/transform-keys {:task {:task-id 1 :parent-id 2}} sut/kebab->camel))))

    (testing "transforms vectors of maps"
      (is (= [{"taskId" 1} {"taskId" 2}]
             (sut/transform-keys [{:task-id 1} {:task-id 2}] sut/kebab->camel))))

    (testing "preserves non-map values"
      (is (= {"value" 42}
             (sut/transform-keys {:value 42} sut/kebab->camel))))))

(deftest truncate-text-test
  (testing "truncate-text"
    (testing "preserves short text"
      (is (= "short" (sut/truncate-text "short" 10))))

    (testing "truncates long text with ellipsis"
      (is (= "this is..." (sut/truncate-text "this is a long text" 10))))

    (testing "handles exact length"
      (is (= "exactly10!" (sut/truncate-text "exactly10!" 10))))

    (testing "handles nil by converting to empty string"
      (is (= "" (sut/truncate-text nil 10))))))

(deftest format-relations-test
  (testing "format-relations"
    (testing "formats single relation"
      (is (= "blocked-by->#45"
             (sut/format-relations [{:as-type :blocked-by :relates-to 45}]))))

    (testing "formats multiple relations"
      (is (= "blocked-by->#45, related->#50"
             (sut/format-relations [{:as-type :blocked-by :relates-to 45}
                                    {:as-type :related :relates-to 50}]))))

    (testing "returns nil for empty relations"
      (is (nil? (sut/format-relations [])))
      (is (nil? (sut/format-relations nil))))))

(deftest format-status-test
  (testing "format-status"
    (testing "formats known statuses with indicators"
      (is (= "○ open" (sut/format-status :open)))
      (is (= "✓ closed" (sut/format-status :closed)))
      (is (= "◐ in-progress" (sut/format-status :in-progress)))
      (is (= "✗ blocked" (sut/format-status :blocked)))
      (is (= "⊗ deleted" (sut/format-status :deleted))))

    (testing "formats unknown status as name"
      (is (= "custom" (sut/format-status :custom))))

    (testing "handles nil by defaulting to open"
      (is (= "○ open" (sut/format-status nil))))))

(deftest format-meta-test
  (testing "format-meta"
    (testing "formats non-empty meta map"
      (is (= "{\"refined\" \"true\"}" (sut/format-meta {"refined" "true"})))
      (is (= "{\"priority\" \"high\"}" (sut/format-meta {"priority" "high"}))))

    (testing "formats meta with multiple keys"
      (is (= "{\"priority\" \"high\", \"refined\" \"true\"}"
             (sut/format-meta {"priority" "high" "refined" "true"}))))

    (testing "returns dash for empty meta"
      (is (= "-" (sut/format-meta {})))
      (is (= "-" (sut/format-meta nil))))))

;; EDN format tests

(deftest edn-format-test
  (testing "render :edn"
    (testing "formats task list response"
      (let [output (sut/render :edn task-list-response)
            parsed (read-string output)]
        (is (string? output))
        (is (= task-list-response parsed))))

    (testing "formats error response"
      (let [output (sut/render :edn error-response)
            parsed (read-string output)]
        (is (= error-response parsed))))

    (testing "preserves all data types"
      (let [data {:keywords :work :numbers 42 :strings "text" :vectors [1 2 3]}
            output (sut/render :edn data)
            parsed (read-string output)]
        (is (= data parsed))))))

;; JSON format tests

(deftest json-format-test
  (testing "render :json"
    (testing "formats task list with camelCase keys"
      (let [output (sut/render :json task-list-response)
            parsed (json/parse-string output)]
        (is (string? output))
        (is (contains? parsed "tasks"))
        (is (contains? parsed "metadata"))
        (is (contains? (get parsed "metadata") "totalMatches"))
        (is (contains? (first (get parsed "tasks")) "parentId"))))

    (testing "formats error response with camelCase"
      (let [output (sut/render :json error-response)
            parsed (json/parse-string output)]
        (is (contains? parsed "error"))
        (is (contains? parsed "metadata"))
        (is (contains? (get parsed "metadata") "taskId"))
        (is (contains? (get parsed "metadata") "attemptedOperation"))))

    (testing "transforms nested structures"
      (let [data {:task-id 1 :nested {:parent-id 2 :sub-field "value"}}
            output (sut/render :json data)
            parsed (json/parse-string output)]
        (is (contains? parsed "taskId"))
        (is (contains? (get parsed "nested") "parentId"))
        (is (contains? (get parsed "nested") "subField"))))

    (testing "handles vectors of maps"
      (let [data {:relations [{:as-type :blocked-by :relates-to 5}]}
            output (sut/render :json data)
            parsed (json/parse-string output)]
        (is (contains? (first (get parsed "relations")) "asType"))
        (is (contains? (first (get parsed "relations")) "relatesTo"))))))

;; Human format tests

(deftest human-format-error-test
  (testing "render :human for errors"
    (testing "formats error response"
      (let [output (sut/render :human error-response)]
        (is (string? output))
        (is (str/includes? output "Error: Task not found"))
        (is (str/includes? output "task-id: 999"))
        (is (str/includes? output "file: /path/to/tasks.ednl"))))))

(deftest human-format-table-test
  (testing "render :human for task lists"
    (testing "formats multiple tasks as table"
      (let [output (sut/render :human task-list-response)]
        (is (str/includes? output "ID"))
        (is (str/includes? output "Status"))
        (is (str/includes? output "Category"))
        (is (str/includes? output "Meta"))
        (is (str/includes? output "Title"))
        (is (str/includes? output "42"))
        (is (str/includes? output "Fix parser bug"))
        (is (str/includes? output "○ open"))
        (is (str/includes? output "✓ closed"))
        (is (str/includes? output "{\"priority\" \"high\"}"))
        (is (str/includes? output "Total: 5"))
        (is (str/includes? output "showing 2"))))

    (testing "formats empty task list"
      (let [output (sut/render :human empty-task-response)]
        (is (= "No tasks found" output))))

    (testing "truncates long titles in table"
      (let [long-title-task {:id 1
                             :status :open
                             :title (apply str (repeat 100 "x"))
                             :category "test"
                             :type :task
                             :meta {}
                             :relations []}
            short-task {:id 2
                        :status :open
                        :title "Short"
                        :category "test"
                        :type :task
                        :meta {}
                        :relations []}
            response {:tasks [long-title-task short-task] :metadata {:count 2 :total-matches 2}}
            output (sut/render :human response)
            lines (str/split-lines output)
            task-line (nth lines 2)]
        (is (str/includes? task-line "..."))))

    (testing "handles tasks with nil fields in table"
      (let [nil-task {:id nil
                      :status nil
                      :title nil
                      :category nil
                      :type :task
                      :meta {}
                      :relations []}
            good-task {:id 1
                       :status :open
                       :title "Good task"
                       :category "simple"
                       :type :task
                       :meta {}
                       :relations []}
            response {:tasks [nil-task good-task] :metadata {:count 2 :total-matches 2}}
            output (sut/render :human response)]
        (is (string? output))
        (is (str/includes? output "ID"))
        (is (str/includes? output "○ open"))))))

(deftest human-format-table-meta-column-test
  (testing "render :human table with meta column"
    (testing "displays refined status in meta column"
      (let [refined-task {:id 1
                          :status :open
                          :title "Refined task"
                          :category "medium"
                          :type :task
                          :meta {"refined" "true"}
                          :relations []}
            unrefined-task {:id 2
                            :status :open
                            :title "Unrefined task"
                            :category "simple"
                            :type :task
                            :meta {}
                            :relations []}
            response {:tasks [refined-task unrefined-task]
                      :metadata {:count 2 :total-matches 2}}
            output (sut/render :human response)]
        (is (str/includes? output "Meta"))
        (is (str/includes? output "{\"refined\" \"true\"}"))
        (is (str/includes? output "-"))))

    (testing "truncates long meta values"
      (let [long-meta-task {:id 1
                            :status :open
                            :title "Task 1"
                            :category "test"
                            :type :task
                            :meta {"key1" "val1" "key2" "val2" "key3" "val3"}
                            :relations []}
            short-task {:id 2
                        :status :open
                        :title "Task 2"
                        :category "test"
                        :type :task
                        :meta {}
                        :relations []}
            response {:tasks [long-meta-task short-task]
                      :metadata {:count 2 :total-matches 2}}
            output (sut/render :human response)
            lines (str/split-lines output)
            task-line (nth lines 2)]
        (is (str/includes? task-line "..."))))))

(deftest human-format-list-with-single-task-test
  (testing "render :human for list with single task"
    (testing "formats single task as table"
      (let [output (sut/render :human single-task-response)]
        ;; Should show table headers
        (is (str/includes? output "ID"))
        (is (str/includes? output "Status"))
        (is (str/includes? output "Category"))
        (is (str/includes? output "Meta"))
        (is (str/includes? output "Title"))
        ;; Should show task data in table format
        (is (str/includes? output "42"))
        (is (str/includes? output "Fix parser bug"))
        (is (str/includes? output "○ open"))
        (is (str/includes? output "simple"))
        ;; Should show parent ID in table
        (is (str/includes? output "31"))
        ;; Meta should be displayed
        (is (str/includes? output "{\"priority\" \"high\"}"))))

    (testing "formats minimal task in table"
      (let [response {:tasks [minimal-task] :metadata {:count 1 :total-matches 1}}
            output (sut/render :human response)]
        ;; Should show table headers
        (is (str/includes? output "ID"))
        (is (str/includes? output "Title"))
        ;; Should show task data
        (is (str/includes? output "1"))
        (is (str/includes? output "Simple task"))
        (is (str/includes? output "✓ closed"))
        ;; Empty meta should show as dash
        (is (str/includes? output "-"))))

    (testing "handles task with nil fields in table"
      (let [nil-task {:id nil
                      :status nil
                      :title nil
                      :category nil
                      :type nil
                      :meta {}
                      :relations []}
            response {:tasks [nil-task] :metadata {:count 1 :total-matches 1}}
            output (sut/render :human response)]
        ;; Should show table headers
        (is (str/includes? output "ID"))
        (is (str/includes? output "Status"))
        ;; Should handle nil gracefully
        (is (str/includes? output "○ open"))))))

(deftest human-format-add-task-test
  (testing "render :human for add-task response"
    (let [output (sut/render :human add-task-response)]
      (is (str/includes? output "Task #42"))
      (is (str/includes? output "Fix parser bug"))
      (is (str/includes? output "File: /path/to/tasks.ednl")))))

(deftest human-format-delete-task-test
  (testing "render :human for delete-task response"
    (let [output (sut/render :human delete-task-response)]
      (is (str/includes? output "Deleted Task #42: Fix parser bug")))))

(deftest human-format-git-test
  (testing "render :human with git metadata"
    (testing "formats successful git commit"
      (let [output (sut/render :human git-success-response)]
        (is (str/includes? output "Git Status: success"))
        (is (str/includes? output "Commit: abc123def456"))))

    (testing "formats git error"
      (let [output (sut/render :human git-error-response)]
        (is (str/includes? output "Git Status: error"))
        (is (str/includes? output "Git Error: nothing to commit"))))))

;; Default method test

(deftest unknown-format-test
  (testing "render with unknown format"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Unknown format type"
          (sut/render :xml task-list-response)))))
