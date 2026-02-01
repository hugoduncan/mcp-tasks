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

;; Blocked indicator tests

(deftest format-blocked-indicator-test
  (testing "format-blocked-indicator"
    (testing "returns ⊠ for blocked tasks"
      (is (= "⊠" (sut/format-blocked-indicator true))))

    (testing "returns empty string for unblocked tasks"
      (is (= "" (sut/format-blocked-indicator false)))
      (is (= "" (sut/format-blocked-indicator nil))))))

(deftest format-table-with-blocked-column-test
  (testing "format-table with blocked indicator column"
    (testing "includes B column header"
      (let [tasks [{:id 1 :status :open :title "Task 1" :category "simple"
                    :type :task :meta {} :relations [] :is-blocked false}]
            output (sut/format-table tasks)]
        (is (str/includes? output "B"))
        (is (str/includes? output "ID"))))

    (testing "shows ⊠ for blocked tasks"
      (let [blocked-task {:id 1 :status :open :title "Blocked" :category "simple"
                          :type :task :meta {} :relations [] :is-blocked true
                          :blocking-task-ids [2 3]}
            unblocked-task {:id 2 :status :open :title "Free" :category "simple"
                            :type :task :meta {} :relations [] :is-blocked false
                            :blocking-task-ids []}
            output (sut/format-table [blocked-task unblocked-task])]
        (is (str/includes? output "⊠"))
        (is (str/includes? output "Blocked"))
        (is (str/includes? output "Free"))))))

(deftest format-blocking-details-test
  (testing "format-blocking-details"
    (testing "shows blocking task IDs"
      (let [tasks [{:id 1 :title "Task 1" :is-blocked true :blocking-task-ids [2 3]}
                   {:id 2 :title "Task 2" :is-blocked false :blocking-task-ids []}]
            output (sut/format-blocking-details tasks)]
        (is (str/includes? output "Task #1 blocked by: #2, #3"))
        (is (not (str/includes? output "Task #2")))))

    (testing "returns nil when no blocked tasks"
      (let [tasks [{:id 1 :title "Task 1" :is-blocked false :blocking-task-ids []}]
            output (sut/format-blocking-details tasks)]
        (is (nil? output))))))

(deftest format-table-with-show-blocking-test
  (testing "format-table with :show-blocking option"
    (testing "appends blocking details when option is true"
      (let [blocked-task {:id 1 :status :open :title "Blocked" :category "simple"
                          :type :task :meta {} :relations [] :is-blocked true
                          :blocking-task-ids [2]}
            output (sut/format-table [blocked-task] {:show-blocking true})]
        (is (str/includes? output "Blocking Details:"))
        (is (str/includes? output "Task #1 blocked by: #2"))))

    (testing "does not show blocking details when option is false"
      (let [blocked-task {:id 1 :status :open :title "Blocked" :category "simple"
                          :type :task :meta {} :relations [] :is-blocked true
                          :blocking-task-ids [2]}
            output (sut/format-table [blocked-task] {:show-blocking false})]
        (is (not (str/includes? output "Blocking Details:")))))))

(deftest format-why-blocked-test
  (testing "format-why-blocked"
    (testing "shows blocked status for blocked task"
      (let [task {:id 42 :title "Fix bug" :is-blocked true
                  :blocking-task-ids [10 11]}
            output (sut/format-why-blocked task)]
        (is (str/includes? output "Task #42: Fix bug"))
        (is (str/includes? output "Status: BLOCKED"))
        (is (str/includes? output "Blocked by tasks: #10, #11"))))

    (testing "shows not blocked for unblocked task"
      (let [task {:id 42 :title "Fix bug" :is-blocked false
                  :blocking-task-ids []}
            output (sut/format-why-blocked task)]
        (is (str/includes? output "Task #42: Fix bug"))
        (is (str/includes? output "Status: Not blocked"))
        (is (not (str/includes? output "Blocked by")))))

    (testing "shows circular dependency when present"
      (let [task {:id 42 :title "Fix bug" :is-blocked true
                  :blocking-task-ids [10]
                  :circular-dependency [42 10 42]}
            output (sut/format-why-blocked task)]
        (is (str/includes? output "Circular dependency detected"))
        (is (str/includes? output "#42 → #10 → #42"))))

    (testing "shows error when present"
      (let [task {:id 42 :title "Fix bug" :is-blocked true
                  :blocking-task-ids []
                  :error "Blocked by invalid task ID: 999"}
            output (sut/format-why-blocked task)]
        (is (str/includes? output "Error:"))
        (is (str/includes? output "invalid task ID"))))))

(deftest render-why-blocked-test
  (testing "render :human for why-blocked response"
    (let [task {:id 42 :title "Fix bug" :is-blocked true
                :blocking-task-ids [10 11]}
          response {:why-blocked task}
          output (sut/render :human response)]
      (is (str/includes? output "Task #42"))
      (is (str/includes? output "BLOCKED"))
      (is (str/includes? output "#10, #11")))))

;; Default method test

(deftest unknown-format-test
  (testing "render with unknown format"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Unknown format type"
          (sut/render :xml task-list-response)))))

(deftest format-prompts-show-test
  ;; Test formatting prompts show response for human output
  ;; Should display metadata header followed by content
  (testing "format-prompts-show"
    (testing "formats prompts show with all metadata"
      (let [data {:name "simple"
                  :type :category
                  :source :builtin
                  :path "jar:file:/path/to/resources.jar!/category-prompts/simple.md"
                  :content "- Step 1\n- Step 2"
                  :metadata {"description" "Execute simple tasks"}}
            result (sut/format-prompts-show data)]
        (is (str/includes? result "Prompt: simple"))
        (is (str/includes? result "Type: category"))
        (is (str/includes? result "Source: builtin"))
        (is (str/includes? result "Description: Execute simple tasks"))
        (is (str/includes? result "Path: jar:file:/path/to/resources.jar!/category-prompts/simple.md"))
        (is (str/includes? result "---"))
        (is (str/includes? result "- Step 1\n- Step 2"))))

    (testing "formats prompts show without description"
      (let [data {:name "refine-task"
                  :type :workflow
                  :source :override
                  :path "/path/to/.mcp-tasks/prompt-overrides/refine-task.md"
                  :content "Refine the task"
                  :metadata {}}
            result (sut/format-prompts-show data)]
        (is (str/includes? result "Prompt: refine-task"))
        (is (str/includes? result "Type: workflow"))
        (is (str/includes? result "Source: override"))
        (is (not (str/includes? result "Description:")))
        (is (str/includes? result "Path: /path/to/.mcp-tasks/prompt-overrides/refine-task.md"))
        (is (str/includes? result "Refine the task"))))))

(deftest render-prompts-show-test
  ;; Test that render :human dispatches correctly for prompts show responses
  ;; Should recognize prompts show data by presence of :name and :content
  (testing "render :human for prompts show"
    (testing "recognizes and formats prompts show response"
      (let [data {:name "medium"
                  :type :category
                  :source :builtin
                  :path "resources/category-prompts/medium.md"
                  :content "Execute medium tasks"
                  :metadata {"description" "Medium workflow"}}
            result (sut/render :human data)]
        (is (str/includes? result "Prompt: medium"))
        (is (str/includes? result "Type: category"))
        (is (str/includes? result "Execute medium tasks"))))

    (testing "renders prompts show in edn format"
      (let [data {:name "simple"
                  :type :category
                  :source :builtin
                  :path "resources/category-prompts/simple.md"
                  :content "Simple workflow"
                  :metadata {}}
            result (sut/render :edn data)
            parsed (read-string result)]
        (is (= "simple" (:name parsed)))
        (is (= :category (:type parsed)))
        (is (= "Simple workflow" (:content parsed)))))

    (testing "renders prompts show in json format"
      (let [data {:name "large"
                  :type :workflow
                  :source :override
                  :path "/path/to/override.md"
                  :content "Large workflow"
                  :metadata {"description" "Execute large tasks"}}
            result (sut/render :json data)
            parsed (json/parse-string result true)]
        (is (= "large" (:name parsed)))
        (is (= "workflow" (:type parsed)))
        (is (= "Large workflow" (:content parsed)))
        (is (= "Execute large tasks" (get-in parsed [:metadata :description])))))))

(deftest format-prompts-install-test
  ;; Test format-prompts-install displays generation results with overwrite warnings.
  ;; Contracts being tested:
  ;; - Shows generated files with checkmark and path
  ;; - Indicates overwritten files with (overwritten) suffix
  ;; - Shows warning when files are overwritten
  ;; - Displays summary with counts

  (testing "format-prompts-install"
    (testing "shows generated files without overwrites"
      (let [results [{:name "simple"
                      :type :category
                      :status :generated
                      :path "/path/simple.md"
                      :overwritten false}]
            metadata {:generated-count 1
                      :skipped-count 0
                      :failed-count 0
                      :overwritten-count 0
                      :target-dir ".claude/commands/"}
            output (sut/format-prompts-install {:results results :metadata metadata})]
        (is (str/includes? output "✓ simple (category)"))
        (is (str/includes? output "/path/simple.md"))
        (is (not (str/includes? output "(overwritten)")))
        (is (not (str/includes? output "Warning:")))
        (is (str/includes? output "Summary: 1 generated"))))

    (testing "shows overwritten files with indicator"
      (let [results [{:name "medium"
                      :type :workflow
                      :status :generated
                      :path "/path/medium.md"
                      :overwritten true}]
            metadata {:generated-count 1
                      :skipped-count 0
                      :failed-count 0
                      :overwritten-count 1
                      :target-dir ".claude/commands/"}
            output (sut/format-prompts-install {:results results :metadata metadata})]
        (is (str/includes? output "✓ medium (workflow)"))
        (is (str/includes? output "(overwritten)"))
        (is (str/includes? output "Warning: 1 file overwritten"))))

    (testing "shows warning for multiple overwrites"
      (let [results [{:name "a" :type :category :status :generated
                      :path "/a.md" :overwritten true}
                     {:name "b" :type :workflow :status :generated
                      :path "/b.md" :overwritten true}]
            metadata {:generated-count 2
                      :skipped-count 0
                      :failed-count 0
                      :overwritten-count 2
                      :target-dir ".claude/commands/"}
            output (sut/format-prompts-install {:results results :metadata metadata})]
        (is (str/includes? output "Warning: 2 files overwritten"))))

    (testing "hides skipped files from output but shows count in summary"
      (let [results [{:name "infrastructure"
                      :type nil
                      :status :skipped
                      :reason "Infrastructure file"}]
            metadata {:generated-count 0
                      :skipped-count 1
                      :failed-count 0
                      :overwritten-count 0
                      :target-dir ".claude/commands/"}
            output (sut/format-prompts-install {:results results :metadata metadata})]
        (is (not (str/includes? output "infrastructure")))
        (is (str/includes? output "Summary: 0 generated, 0 failed, 1 skipped"))))

    (testing "shows failed files with error"
      (let [results [{:name "broken"
                      :type :category
                      :status :failed
                      :error "File not found"}]
            metadata {:generated-count 0
                      :skipped-count 0
                      :failed-count 1
                      :overwritten-count 0
                      :target-dir ".claude/commands/"}
            output (sut/format-prompts-install {:results results :metadata metadata})]
        (is (str/includes? output "✗ broken (category)"))
        (is (str/includes? output "Error: File not found"))
        (is (str/includes? output "1 failed"))))))

;; Tests for code-reviewed and pr-num display in format-single-task.
;; These fields should appear in detail view when present and be hidden when nil.
;; Table output should remain unchanged (not show these fields).

(deftest format-single-task-code-reviewed-test
  (testing "format-single-task"
    (testing "displays code-reviewed when set"
      (let [task {:id 42
                  :status :open
                  :title "Reviewed task"
                  :category "simple"
                  :type :task
                  :meta {}
                  :relations []
                  :code-reviewed "2025-01-10T14:30:00Z"}
            output (sut/format-single-task task)]
        (is (str/includes? output "Code Reviewed: 2025-01-10T14:30:00Z"))))

    (testing "hides code-reviewed when nil"
      (let [task {:id 42
                  :status :open
                  :title "Unreviewed task"
                  :category "simple"
                  :type :task
                  :meta {}
                  :relations []
                  :code-reviewed nil}
            output (sut/format-single-task task)]
        (is (not (str/includes? output "Code Reviewed:")))))

    (testing "hides code-reviewed when not present"
      (let [task {:id 42
                  :status :open
                  :title "Unreviewed task"
                  :category "simple"
                  :type :task
                  :meta {}
                  :relations []}
            output (sut/format-single-task task)]
        (is (not (str/includes? output "Code Reviewed:")))))))

(deftest format-single-task-pr-num-test
  (testing "format-single-task"
    (testing "displays pr-num with # prefix when set"
      (let [task {:id 42
                  :status :open
                  :title "Task with PR"
                  :category "simple"
                  :type :task
                  :meta {}
                  :relations []
                  :pr-num 123}
            output (sut/format-single-task task)]
        (is (str/includes? output "PR Number: #123"))))

    (testing "hides pr-num when nil"
      (let [task {:id 42
                  :status :open
                  :title "Task without PR"
                  :category "simple"
                  :type :task
                  :meta {}
                  :relations []
                  :pr-num nil}
            output (sut/format-single-task task)]
        (is (not (str/includes? output "PR Number:")))))

    (testing "hides pr-num when not present"
      (let [task {:id 42
                  :status :open
                  :title "Task without PR"
                  :category "simple"
                  :type :task
                  :meta {}
                  :relations []}
            output (sut/format-single-task task)]
        (is (not (str/includes? output "PR Number:")))))))

(deftest format-single-task-both-fields-test
  (testing "format-single-task"
    (testing "displays both fields when both are set"
      (let [task {:id 42
                  :status :closed
                  :title "Completed story"
                  :category "large"
                  :type :story
                  :meta {}
                  :relations []
                  :code-reviewed "2025-01-10T14:30:00Z"
                  :pr-num 456}
            output (sut/format-single-task task)]
        (is (str/includes? output "Code Reviewed: 2025-01-10T14:30:00Z"))
        (is (str/includes? output "PR Number: #456"))))))

(deftest table-output-unchanged-test
  (testing "format-table"
    (testing "does not include code-reviewed in table output"
      (let [task {:id 42
                  :status :open
                  :title "Task"
                  :category "simple"
                  :type :task
                  :meta {}
                  :relations []
                  :is-blocked false
                  :code-reviewed "2025-01-10T14:30:00Z"
                  :pr-num 123}
            output (sut/format-table [task])]
        (is (not (str/includes? output "Code Reviewed")))
        (is (not (str/includes? output "PR Number")))))))

;; Work-on formatting tests

(deftest format-work-on-test
  ;; Tests format-work-on function for human-readable output.
  ;; Contracts:
  ;; - Shows "Working on task N: title" as first line
  ;; - Shows message on second line
  ;; - Shows "Switch to worktree: cd path" when worktree-path present
  (testing "format-work-on"
    (testing "shows task ID, title, and message"
      (let [data {:task-id 42
                  :title "Fix bug"
                  :message "Task validated successfully"}
            output (sut/format-work-on data)]
        (is (str/includes? output "Working on task 42: Fix bug"))
        (is (str/includes? output "Task validated successfully"))))

    (testing "shows worktree switch hint when worktree-path present"
      (let [data {:task-id 42
                  :title "Fix bug"
                  :message "Worktree created"
                  :worktree-path "/path/to/worktree"}
            output (sut/format-work-on data)]
        (is (str/includes? output "Working on task 42: Fix bug"))
        (is (str/includes? output "Worktree created"))
        (is (str/includes? output "Switch to worktree: cd /path/to/worktree"))))

    (testing "omits worktree hint when worktree-path not present"
      (let [data {:task-id 42
                  :title "Fix bug"
                  :message "Task validated successfully"
                  :worktree-path nil}
            output (sut/format-work-on data)]
        (is (str/includes? output "Working on task 42: Fix bug"))
        (is (not (str/includes? output "Switch to worktree")))))))

(deftest render-work-on-test
  ;; Tests that render :human dispatches correctly for work-on responses.
  ;; Recognizes work-on data by presence of :task-id and :message without :tasks.
  (testing "render for work-on response"
    (testing "renders human format for work-on"
      (let [data {:task-id 42
                  :title "Fix bug"
                  :category "simple"
                  :message "Task validated successfully"}
            output (sut/render :human data)]
        (is (str/includes? output "Working on task 42: Fix bug"))
        (is (str/includes? output "Task validated successfully"))))

    (testing "renders human format with worktree hint"
      (let [data {:task-id 42
                  :title "Fix bug"
                  :category "simple"
                  :message "Worktree created"
                  :worktree-path "/path/to/worktree"}
            output (sut/render :human data)]
        (is (str/includes? output "Switch to worktree: cd /path/to/worktree"))))

    (testing "renders JSON format with camelCase keys"
      (let [data {:task-id 42
                  :title "Fix bug"
                  :worktree-path "/path/to/worktree"
                  :message "Task validated"}
            output (sut/render :json data)
            parsed (json/parse-string output)]
        (is (contains? parsed "taskId"))
        (is (contains? parsed "worktreePath"))
        (is (= 42 (get parsed "taskId")))))

    (testing "renders EDN format unchanged"
      (let [data {:task-id 42
                  :title "Fix bug"
                  :message "Task validated"}
            output (sut/render :edn data)
            parsed (read-string output)]
        (is (= 42 (:task-id parsed)))
        (is (= "Fix bug" (:title parsed)))))

    (testing "renders error response for work-on error"
      (let [data {:error "Task not found"
                  :metadata {:task-id 999}}
            output (sut/render :human data)]
        (is (str/includes? output "Error: Task not found"))
        (is (str/includes? output "task-id: 999"))))))
