(ns mcp-tasks.cli.parse-test
  "Tests for CLI argument parsing."
  (:require
    [clojure.test :refer [deftest testing is]]
    [mcp-tasks.cli.parse :as sut]))

(deftest coerce-json-map-test
  (testing "coerce-json-map"
    (testing "parses valid JSON object"
      (is (= {:foo "bar" :baz 42}
             (sut/coerce-json-map "{\"foo\":\"bar\",\"baz\":42}"))))

    (testing "returns error for invalid JSON"
      (let [result (sut/coerce-json-map "{invalid}")]
        (is (contains? result :error))
        (is (= "{invalid}" (:provided result)))))

    (testing "returns error for non-object JSON"
      (let [result (sut/coerce-json-map "[1,2,3]")]
        (is (= "Expected JSON object for --meta" (:error result)))
        (is (= "[1,2,3]" (:provided result)))))))

(deftest coerce-json-array-test
  (testing "coerce-json-array"
    (testing "parses valid JSON array"
      (is (= [{:id 1 :relates-to 2 :as-type "blocked-by"}]
             (sut/coerce-json-array "[{\"id\":1,\"relates-to\":2,\"as-type\":\"blocked-by\"}]"))))

    (testing "returns error for invalid JSON"
      (let [result (sut/coerce-json-array "[invalid]")]
        (is (contains? result :error))
        (is (= "[invalid]" (:provided result)))))

    (testing "returns error for non-array JSON"
      (let [result (sut/coerce-json-array "{\"foo\":\"bar\"}")]
        (is (= "Expected JSON array for --relations" (:error result)))
        (is (= "{\"foo\":\"bar\"}" (:provided result)))))))

(deftest validate-at-least-one-test
  (testing "validate-at-least-one"
    (testing "returns valid when at least one key present"
      (is (= {:valid? true}
             (sut/validate-at-least-one {:task-id 42} [:task-id :title] ["--task-id" "--title"])))
      (is (= {:valid? true}
             (sut/validate-at-least-one {:title "foo"} [:task-id :title] ["--task-id" "--title"])))
      (is (= {:valid? true}
             (sut/validate-at-least-one {:task-id 42 :title "foo"} [:task-id :title] ["--task-id" "--title"]))))

    (testing "returns error when no keys present"
      (let [result (sut/validate-at-least-one {:other "data"} [:task-id :title] ["--task-id" "--title"])]
        (is (false? (:valid? result)))
        (is (= "At least one of --task-id, --title must be provided" (:error result)))
        (is (= {:required-one-of [:task-id :title]} (:metadata result)))))))

(deftest validate-format-test
  (testing "validate-format"
    (testing "returns valid for allowed formats"
      (is (= {:valid? true}
             (sut/validate-format {:format :edn})))
      (is (= {:valid? true}
             (sut/validate-format {:format :json})))
      (is (= {:valid? true}
             (sut/validate-format {:format :human}))))

    (testing "returns valid when format is not present"
      (is (= {:valid? true}
             (sut/validate-format {:other "data"}))))

    (testing "returns error for invalid formats"
      (let [result (sut/validate-format {:format :xyz})]
        (is (false? (:valid? result)))
        (is (= "Invalid format: xyz. Must be one of: edn, json, human" (:error result)))
        (is (= {:provided :xyz :allowed #{:edn :json :human}} (:metadata result))))

      (let [result (sut/validate-format {:format :csv})]
        (is (false? (:valid? result)))
        (is (= "Invalid format: csv. Must be one of: edn, json, human" (:error result)))))))

(deftest validate-status-test
  (testing "validate-status"
    (testing "returns valid for allowed status values"
      (is (= {:valid? true}
             (sut/validate-status {:status :open})))
      (is (= {:valid? true}
             (sut/validate-status {:status :closed})))
      (is (= {:valid? true}
             (sut/validate-status {:status :in-progress})))
      (is (= {:valid? true}
             (sut/validate-status {:status :blocked})))
      (is (= {:valid? true}
             (sut/validate-status {:status :any}))))

    (testing "returns valid when status is not present"
      (is (= {:valid? true}
             (sut/validate-status {:other "data"}))))

    (testing "returns error for invalid status values"
      (let [result (sut/validate-status {:status :invalid})]
        (is (false? (:valid? result)))
        (is (= "Invalid status value 'invalid'. Must be one of: open, closed, in-progress, blocked, any" (:error result)))
        (is (= {:provided :invalid :allowed #{:open :closed :in-progress :blocked :any}} (:metadata result))))

      (let [result (sut/validate-status {:status :foo})]
        (is (false? (:valid? result)))
        (is (= "Invalid status value 'foo'. Must be one of: open, closed, in-progress, blocked, any" (:error result)))))))

(deftest parse-list-test
  (testing "parse-list"
    (testing "parses basic arguments"
      (is (= {:limit 30}
             (sut/parse-list []))))

    (testing "parses all filter options"
      (is (= {:status :open
              :category "simple"
              :type :task
              :parent-id 10
              :task-id 42
              :title-pattern "foo"
              :limit 10
              :unique true
              :format :json}
             (sut/parse-list ["--status" "open"
                              "--category" "simple"
                              "--type" "task"
                              "--parent-id" "10"
                              "--task-id" "42"
                              "--title-pattern" "foo"
                              "--limit" "10"
                              "--unique"
                              "--format" "json"]))))

    (testing "uses aliases"
      (is (= {:status :closed :category "large" :type :bug :parent-id 5 :limit 30}
             (sut/parse-list ["-s" "closed" "-c" "large" "-t" "bug" "-p" "5"]))))

    (testing "uses defaults"
      (is (= {:limit 30}
             (sut/parse-list []))))

    (testing "coerces types correctly"
      (let [result (sut/parse-list ["--status" "in-progress" "--limit" "20" "--unique"])]
        (is (keyword? (:status result)))
        (is (= :in-progress (:status result)))
        (is (number? (:limit result)))
        (is (= 20 (:limit result)))
        (is (true? (:unique result)))))

    (testing "parses status any"
      (is (= {:status :any :limit 30}
             (sut/parse-list ["--status" "any"]))))

    (testing "returns error for invalid format"
      (let [result (sut/parse-list ["--format" "xyz"])]
        (is (contains? result :error))
        (is (= "Invalid format: xyz. Must be one of: edn, json, human" (:error result)))))

    (testing "returns error for invalid status"
      (let [result (sut/parse-list ["--status" "foo"])]
        (is (contains? result :error))
        (is (= "Invalid status value 'foo'. Must be one of: open, closed, in-progress, blocked, any" (:error result)))))))

(deftest parse-show-test
  (testing "parse-show"
    (testing "parses required task-id"
      (is (= {:task-id 42}
             (sut/parse-show ["--task-id" "42"]))))

    (testing "uses alias for task-id"
      (is (= {:task-id 99}
             (sut/parse-show ["--id" "99"]))))

    (testing "supports format option"
      (is (= {:task-id 42 :format :human}
             (sut/parse-show ["--task-id" "42" "--format" "human"]))))

    (testing "returns error for invalid format"
      (let [result (sut/parse-show ["--task-id" "42" "--format" "csv"])]
        (is (contains? result :error))
        (is (= "Invalid format: csv. Must be one of: edn, json, human" (:error result)))))))

(deftest parse-add-test
  (testing "parse-add"
    (testing "parses required category and title"
      (is (= {:category "simple"
              :title "Test task"
              :type :task}
             (sut/parse-add ["--category" "simple" "--title" "Test task"]))))

    (testing "parses all optional fields"
      (is (= {:category "medium"
              :title "Complex task"
              :description "Long desc"
              :type :feature
              :parent-id 10
              :prepend true
              :format :json}
             (sut/parse-add ["--category" "medium"
                             "--title" "Complex task"
                             "--description" "Long desc"
                             "--type" "feature"
                             "--parent-id" "10"
                             "--prepend"
                             "--format" "json"]))))

    (testing "uses aliases"
      (is (= {:category "simple" :title "Task" :description "Desc" :parent-id 5 :type :task}
             (sut/parse-add ["-c" "simple" "-t" "Task" "-d" "Desc" "-p" "5"]))))

    (testing "uses default type"
      (let [result (sut/parse-add ["--category" "simple" "--title" "Task"])]
        (is (= :task (:type result)))))

    (testing "returns error for invalid format"
      (let [result (sut/parse-add ["--category" "simple" "--title" "Task" "--format" "xml"])]
        (is (contains? result :error))
        (is (= "Invalid format: xml. Must be one of: edn, json, human" (:error result)))))))

(deftest parse-complete-test
  (testing "parse-complete"
    (testing "accepts task-id"
      (is (= {:task-id 42}
             (sut/parse-complete ["--task-id" "42"]))))

    (testing "accepts title"
      (is (= {:title "My task"}
             (sut/parse-complete ["--title" "My task"]))))

    (testing "accepts both task-id and title"
      (is (= {:task-id 42 :title "My task"}
             (sut/parse-complete ["--task-id" "42" "--title" "My task"]))))

    (testing "accepts category and completion-comment"
      (is (= {:task-id 42 :category "simple" :completion-comment "Done!"}
             (sut/parse-complete ["--task-id" "42" "--category" "simple" "--completion-comment" "Done!"]))))

    (testing "uses aliases"
      (is (= {:task-id 42 :category "simple" :completion-comment "Done"}
             (sut/parse-complete ["--id" "42" "-c" "simple" "--comment" "Done"]))))

    (testing "requires at least one of task-id or title"
      (let [result (sut/parse-complete ["--category" "simple"])]
        (is (contains? result :error))
        (is (= "At least one of --task-id, --title must be provided" (:error result)))))))

(deftest parse-update-test
  (testing "parse-update"
    (testing "parses required task-id"
      (is (= {:task-id 42}
             (sut/parse-update ["--task-id" "42"]))))

    (testing "parses all optional string fields"
      (is (= {:task-id 42
              :title "New title"
              :description "New desc"
              :design "New design"
              :category "medium"}
             (sut/parse-update ["--task-id" "42"
                                "--title" "New title"
                                "--description" "New desc"
                                "--design" "New design"
                                "--category" "medium"]))))

    (testing "parses enum fields"
      (is (= {:task-id 42 :status :closed :type :bug}
             (sut/parse-update ["--task-id" "42" "--status" "closed" "--type" "bug"]))))

    (testing "parses parent-id"
      (is (= {:task-id 42 :parent-id 10}
             (sut/parse-update ["--task-id" "42" "--parent-id" "10"]))))

    (testing "parses meta as JSON"
      (let [result (sut/parse-update ["--task-id" "42" "--meta" "{\"key\":\"value\",\"num\":123}"])]
        (is (= 42 (:task-id result)))
        (is (map? (:meta result)))
        (is (= "value" (:key (:meta result))))
        (is (= 123 (:num (:meta result))))))

    (testing "parses relations as JSON"
      (let [result (sut/parse-update ["--task-id" "42" "--relations" "[{\"id\":1,\"relates-to\":2,\"as-type\":\"blocked-by\"}]"])]
        (is (= 42 (:task-id result)))
        (is (vector? (:relations result)))
        (is (= 1 (count (:relations result))))
        (is (= {:id 1 :relates-to 2 :as-type "blocked-by"} (first (:relations result))))))

    (testing "parses both meta and relations"
      (let [result (sut/parse-update ["--task-id" "42"
                                      "--meta" "{\"foo\":\"bar\"}"
                                      "--relations" "[{\"id\":1,\"relates-to\":2,\"as-type\":\"related\"}]"])]
        (is (= 42 (:task-id result)))
        (is (= {:foo "bar"} (:meta result)))
        (is (= [{:id 1 :relates-to 2 :as-type "related"}] (:relations result)))))

    (testing "returns error for invalid meta JSON"
      (let [result (sut/parse-update ["--task-id" "42" "--meta" "{invalid}"])]
        (is (contains? result :error))
        (is (:error result))))

    (testing "returns error for invalid relations JSON"
      (let [result (sut/parse-update ["--task-id" "42" "--relations" "[invalid]"])]
        (is (contains? result :error))
        (is (:error result))))

    (testing "returns error for non-object meta"
      (let [result (sut/parse-update ["--task-id" "42" "--meta" "[1,2,3]"])]
        (is (= "Expected JSON object for --meta" (:error result)))))

    (testing "returns error for non-array relations"
      (let [result (sut/parse-update ["--task-id" "42" "--relations" "{\"foo\":\"bar\"}"])]
        (is (= "Expected JSON array for --relations" (:error result)))))

    (testing "uses aliases"
      (is (= {:task-id 99 :title "New" :description "Desc" :status :open :category "simple" :parent-id 5}
             (sut/parse-update ["--id" "99" "-t" "New" "-d" "Desc" "-s" "open" "-c" "simple" "-p" "5"]))))

    (testing "parses session-events as JSON object"
      (let [result (sut/parse-update ["--task-id" "42" "--session-events" "{\"event-type\":\"user-prompt\",\"content\":\"test\"}"])]
        (is (= 42 (:task-id result)))
        (is (map? (:session-events result)))
        (is (= "user-prompt" (:event-type (:session-events result))))
        (is (= "test" (:content (:session-events result))))))

    (testing "parses session-events as JSON array"
      (let [result (sut/parse-update ["--task-id" "42" "--session-events" "[{\"event-type\":\"user-prompt\",\"content\":\"test\"}]"])]
        (is (= 42 (:task-id result)))
        (is (vector? (:session-events result)))
        (is (= 1 (count (:session-events result))))
        (is (= {:event-type "user-prompt" :content "test"} (first (:session-events result))))))

    (testing "returns error for invalid session-events JSON"
      (let [result (sut/parse-update ["--task-id" "42" "--session-events" "{invalid}"])]
        (is (contains? result :error))
        (is (string? (:error result)))))

    (testing "returns error for non-object/array session-events"
      (let [result (sut/parse-update ["--task-id" "42" "--session-events" "\"just a string\""])]
        (is (= "Expected JSON object or array for --session-events" (:error result)))))

    (testing "parses --shared-context option"
      (is (= {:task-id 42 :shared-context "Key discovery"}
             (sut/parse-update ["--task-id" "42" "--shared-context" "Key discovery"]))))

    (testing "parses -C alias for --shared-context"
      (is (= {:task-id 42 :shared-context "Important note"}
             (sut/parse-update ["--task-id" "42" "-C" "Important note"]))))

    (testing "combines --shared-context with other update options"
      (is (= {:task-id 42
              :title "New title"
              :status :in-progress
              :shared-context "Progress note"}
             (sut/parse-update ["--task-id" "42"
                                "--title" "New title"
                                "--status" "in-progress"
                                "--shared-context" "Progress note"]))))

    (testing "parses --code-reviewed option"
      (is (= {:task-id 42 :code-reviewed "2025-01-11T10:30:00Z"}
             (sut/parse-update ["--task-id" "42" "--code-reviewed" "2025-01-11T10:30:00Z"]))))

    (testing "parses --pr-num option"
      (is (= {:task-id 42 :pr-num 123}
             (sut/parse-update ["--task-id" "42" "--pr-num" "123"]))))

    (testing "combines --code-reviewed and --pr-num with other options"
      (is (= {:task-id 42
              :status :closed
              :code-reviewed "2025-01-11T12:00:00Z"
              :pr-num 456}
             (sut/parse-update ["--task-id" "42"
                                "--status" "closed"
                                "--code-reviewed" "2025-01-11T12:00:00Z"
                                "--pr-num" "456"]))))))

(deftest parse-delete-test
  (testing "parse-delete"
    (testing "accepts task-id"
      (is (= {:task-id 42}
             (sut/parse-delete ["--task-id" "42"]))))

    (testing "accepts title-pattern"
      (is (= {:title-pattern "my.*task"}
             (sut/parse-delete ["--title-pattern" "my.*task"]))))

    (testing "accepts both task-id and title-pattern"
      (is (= {:task-id 42 :title-pattern "task"}
             (sut/parse-delete ["--task-id" "42" "--title-pattern" "task"]))))

    (testing "uses aliases"
      (is (= {:task-id 99 :title-pattern "foo"}
             (sut/parse-delete ["--id" "99" "--title" "foo"]))))

    (testing "requires at least one of task-id or title-pattern"
      (let [result (sut/parse-delete ["--format" "json"])]
        (is (contains? result :error))
        (is (= "At least one of --task-id, --title-pattern must be provided" (:error result)))))))

(deftest parse-reopen-test
  (testing "parse-reopen"
    (testing "accepts task-id"
      (is (= {:task-id 42}
             (sut/parse-reopen ["--task-id" "42"]))))

    (testing "accepts title"
      (is (= {:title "my task"}
             (sut/parse-reopen ["--title" "my task"]))))

    (testing "accepts both task-id and title"
      (is (= {:task-id 42 :title "task"}
             (sut/parse-reopen ["--task-id" "42" "--title" "task"]))))

    (testing "uses aliases"
      (is (= {:task-id 99 :title "foo"}
             (sut/parse-reopen ["--id" "99" "--t" "foo"]))))

    (testing "requires at least one of task-id or title"
      (let [result (sut/parse-reopen ["--format" "json"])]
        (is (contains? result :error))
        (is (= "At least one of --task-id, --title must be provided" (:error result)))))))

(deftest parse-list-with-blocked-filter-test
  (testing "parse-list with blocked filter"
    (testing "parses --blocked true"
      (is (= {:blocked true :limit 30}
             (sut/parse-list ["--blocked" "true"]))))

    (testing "parses --blocked false"
      (is (= {:blocked false :limit 30}
             (sut/parse-list ["--blocked" "false"]))))

    (testing "combines with other filters"
      (is (= {:blocked true :status :open :limit 30}
             (sut/parse-list ["--blocked" "true" "--status" "open"]))))))

(deftest parse-list-with-show-blocking-test
  (testing "parse-list with show-blocking flag"
    (testing "parses --show-blocking"
      (is (= {:show-blocking true :limit 30}
             (sut/parse-list ["--show-blocking" "true"]))))

    (testing "defaults to false when not provided"
      (is (= {:limit 30}
             (sut/parse-list []))))))

(deftest parse-why-blocked-test
  (testing "parse-why-blocked"
    (testing "parses task-id successfully"
      (is (= {:task-id 42}
             (sut/parse-why-blocked ["--task-id" "42"]))))

    (testing "uses id alias"
      (is (= {:task-id 99}
             (sut/parse-why-blocked ["--id" "99"]))))

    (testing "parses with format option"
      (is (= {:task-id 42 :format :human}
             (sut/parse-why-blocked ["--task-id" "42" "--format" "human"]))))

    (testing "requires task-id"
      (let [result (sut/parse-why-blocked ["--format" "json"])]
        (is (contains? result :error))
        (is (= "Required option: --task-id (or --id)" (:error result)))))))

(deftest parse-prompts-test
  ;; Tests for parse-prompts with list, customize, show, and install subcommands
  (testing "parse-prompts"
    (testing "returns error when no subcommand provided"
      (let [result (sut/parse-prompts [])]
        (is (= "Subcommand required: customize, install, list, show" (:error result)))
        (is (= {:args []} (:metadata result)))))

    (testing "handles list subcommand"
      (testing "with no args"
        (let [result (sut/parse-prompts ["list"])]
          (is (= :list (:subcommand result)))
          (is (nil? (:format result)))))

      (testing "with format option"
        (let [result (sut/parse-prompts ["list" "--format" "json"])]
          (is (= :list (:subcommand result)))
          (is (= :json (:format result)))))

      (testing "with help flag"
        (let [result (sut/parse-prompts ["list" "--help"])]
          (is (string? (:help result)))
          (is (re-find #"mcp-tasks prompts list" (:help result))))))

    (testing "handles customize subcommand"
      (testing "with single prompt"
        (let [result (sut/parse-prompts ["customize" "simple"])]
          (is (= :customize (:subcommand result)))
          (is (= ["simple"] (:prompt-names result)))))

      (testing "with multiple prompts"
        (let [result (sut/parse-prompts ["customize" "simple" "medium" "large"])]
          (is (= :customize (:subcommand result)))
          (is (= ["simple" "medium" "large"] (:prompt-names result)))))

      (testing "with format option"
        (let [result (sut/parse-prompts ["customize" "simple" "--format" "edn"])]
          (is (= :customize (:subcommand result)))
          (is (= ["simple"] (:prompt-names result)))
          (is (= :edn (:format result)))))

      (testing "returns error when no prompt names provided"
        (let [result (sut/parse-prompts ["customize"])]
          (is (= "At least one prompt name is required" (:error result)))))

      (testing "with help flag"
        (let [result (sut/parse-prompts ["customize" "--help"])]
          (is (string? (:help result)))
          (is (re-find #"mcp-tasks prompts customize" (:help result))))))

    (testing "handles show subcommand"
      (testing "with single prompt name"
        (let [result (sut/parse-prompts ["show" "simple"])]
          (is (= :show (:subcommand result)))
          (is (= "simple" (:prompt-name result)))))

      (testing "with format option"
        (let [result (sut/parse-prompts ["show" "execute-task" "--format" "json"])]
          (is (= :show (:subcommand result)))
          (is (= "execute-task" (:prompt-name result)))
          (is (= :json (:format result)))))

      (testing "returns error when no prompt name provided"
        (let [result (sut/parse-prompts ["show"])]
          (is (= "Prompt name is required" (:error result)))))

      (testing "returns error when multiple prompt names provided"
        (let [result (sut/parse-prompts ["show" "simple" "medium"])]
          (is (= "Only one prompt name is allowed" (:error result)))
          (is (= ["simple" "medium"] (get-in result [:metadata :provided-names])))))

      (testing "with help flag"
        (let [result (sut/parse-prompts ["show" "--help"])]
          (is (string? (:help result)))
          (is (re-find #"mcp-tasks prompts show" (:help result))))))

    (testing "handles install subcommand"
      (testing "with no args uses default directory"
        (let [result (sut/parse-prompts ["install"])]
          (is (= :install (:subcommand result)))
          (is (= ".claude/commands/" (:target-dir result)))))

      (testing "with target directory"
        (let [result (sut/parse-prompts ["install" "my-commands/"])]
          (is (= :install (:subcommand result)))
          (is (= "my-commands/" (:target-dir result)))))

      (testing "with format option"
        (let [result (sut/parse-prompts ["install" "--format" "json"])]
          (is (= :install (:subcommand result)))
          (is (= ".claude/commands/" (:target-dir result)))
          (is (= :json (:format result)))))

      (testing "with target directory and format option"
        (let [result (sut/parse-prompts ["install" "custom/" "--format" "edn"])]
          (is (= :install (:subcommand result)))
          (is (= "custom/" (:target-dir result)))
          (is (= :edn (:format result)))))

      (testing "returns error when multiple directories provided"
        (let [result (sut/parse-prompts ["install" "dir1/" "dir2/"])]
          (is (= "Only one target directory is allowed" (:error result)))
          (is (= ["dir1/" "dir2/"] (get-in result [:metadata :provided-args])))))

      (testing "with help flag"
        (let [result (sut/parse-prompts ["install" "--help"])]
          (is (string? (:help result)))
          (is (re-find #"mcp-tasks prompts install" (:help result))))))

    (testing "handles unknown subcommand"
      (let [result (sut/parse-prompts ["unknown"])]
        (is (re-find #"Unknown subcommand: unknown" (:error result)))
        (is (re-find #"customize, install, list, show" (:error result)))
        (is (= "unknown" (get-in result [:metadata :provided-subcommand])))))))
