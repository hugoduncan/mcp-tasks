(ns mcp-tasks.main-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [mcp-tasks.main :as sut]
   [mcp-tasks.prompts :as prompts]
   [mcp-tasks.tools :as tools])
  (:import
   (java.io
    File)))

(deftest get-prompt-vars-test
  ;; Test that get-prompt-vars finds all prompt vars from task-prompts namespace
  ;; and filters to only those containing strings.
  (testing "get-prompt-vars"
    (testing "returns sequence of vars"
      (let [vars (#'sut/get-prompt-vars)]
        (is (seq vars))
        (is (every? var? vars))))

    (testing "returns only vars with string values"
      (let [vars (#'sut/get-prompt-vars)]
        (is (every? #(string? @%) vars))))

    (testing "finds all defined prompts"
      (let [vars (#'sut/get-prompt-vars)
            var-names (set (map #(name (symbol %)) vars))]
        (is (contains? var-names "clarify-task"))
        (is (contains? var-names "simple"))
        (is (>= (count var-names) 2))))))

(deftest list-prompts-test
  ;; Test that list-prompts outputs prompt names and descriptions to stdout.
  (testing "list-prompts"
    (testing "outputs all prompt names with descriptions"
      (let [output (with-out-str (#'sut/list-prompts))]
        (is (string? output))
        (is (str/includes? output "clarify-task:"))
        (is (str/includes? output "simple:"))
        (is (str/includes? output "Transform informal task instructions"))
        (is (str/includes? output "Execute simple tasks"))))

    (testing "returns exit code 0"
      (is (= 0 (#'sut/list-prompts))))))

(deftest install-prompt-test
  ;; Test that install-prompt handles various edge cases.
  (testing "install-prompt"
    (testing "warns on nonexistent prompt"
      (let [output (with-out-str (#'sut/install-prompt "nonexistent"))
            exit-code (#'sut/install-prompt "nonexistent")]
        (is (str/includes? output "Warning"))
        (is (str/includes? output "not found"))
        (is (= 1 exit-code))))))

(deftest install-prompts-test
  ;; Test that install-prompts handles multiple prompts and returns
  ;; appropriate exit codes.
  (testing "install-prompts"
    (testing "returns exit code 1 when any prompt not found"
      (let [exit-code (#'sut/install-prompts ["nonexistent"])]
        (is (= 1 exit-code))))

    (testing "returns exit code 1 when some prompts are not found"
      (let [output (with-out-str (#'sut/install-prompts ["simple" "nonexistent"]))
            exit-code (#'sut/install-prompts ["simple" "nonexistent"])]
        (is (= 1 exit-code))
        (is (str/includes? output "Warning: Prompt 'nonexistent' not found"))))))

(deftest load-and-validate-config-test
  ;; Test that load-and-validate-config correctly loads, resolves, and validates config.
  (testing "load-and-validate-config"
    (testing "loads and resolves config with no config file"
      (let [temp-dir (File/createTempFile "mcp-tasks-test" "")
            _ (.delete temp-dir)
            _ (.mkdirs temp-dir)]
        (try
          (let [config (#'sut/load-and-validate-config (.getPath temp-dir))]
            (is (map? config))
            (is (contains? config :use-git?))
            (is (boolean? (:use-git? config))))
          (finally
            (.delete temp-dir)))))

    (testing "loads and resolves config with valid config file"
      (let [temp-dir (File/createTempFile "mcp-tasks-test" "")
            _ (.delete temp-dir)
            _ (.mkdirs temp-dir)
            config-file (io/file temp-dir ".mcp-tasks.edn")]
        (try
          (spit config-file "{:use-git? false}")
          (let [config (#'sut/load-and-validate-config (.getPath temp-dir))]
            (is (map? config))
            (is (false? (:use-git? config))))
          (finally
            (.delete config-file)
            (.delete temp-dir)))))

    (testing "throws on invalid config"
      (let [temp-dir (File/createTempFile "mcp-tasks-test" "")
            _ (.delete temp-dir)
            _ (.mkdirs temp-dir)
            config-file (io/file temp-dir ".mcp-tasks.edn")]
        (try
          (spit config-file "{:use-git? \"not-a-boolean\"}")
          (is (thrown? Exception
                       (#'sut/load-and-validate-config (.getPath temp-dir))))
          (finally
            (.delete config-file)
            (.delete temp-dir)))))

    (testing "throws on malformed EDN"
      (let [temp-dir (File/createTempFile "mcp-tasks-test" "")
            _ (.delete temp-dir)
            _ (.mkdirs temp-dir)
            config-file (io/file temp-dir ".mcp-tasks.edn")]
        (try
          (spit config-file "{:invalid")
          (is (thrown? Exception
                       (#'sut/load-and-validate-config (.getPath temp-dir))))
          (finally
            (.delete config-file)
            (.delete temp-dir)))))

    (testing "validates git repo when use-git is true"
      (let [temp-dir (File/createTempFile "mcp-tasks-test" "")
            _ (.delete temp-dir)
            _ (.mkdirs temp-dir)
            mcp-tasks-dir (io/file temp-dir ".mcp-tasks")
            git-dir (io/file mcp-tasks-dir ".git")
            _ (.mkdirs git-dir)
            config-file (io/file temp-dir ".mcp-tasks.edn")]
        (try
          (spit config-file "{:use-git? true}")
          (let [config (#'sut/load-and-validate-config (.getPath temp-dir))]
            (is (map? config))
            (is (true? (:use-git? config))))
          (finally
            (.delete config-file)
            (.delete git-dir)
            (.delete mcp-tasks-dir)
            (.delete temp-dir)))))))

(deftest start-test
  ;; Testing start function is complex due to stdio transport blocking on stdin.
  ;; The core functionality (config loading and validation) is tested in
  ;; load-and-validate-config-test.
  (testing "start"
    (testing "config parameter is accepted"
      (is (fn? sut/start))
      (is (= 1 (count (:arglists (meta #'sut/start))))))))

(deftest config-threading-integration-test
  ;; Test that config is correctly threaded from loading through to tools and prompts.
  ;; This integration test verifies the complete flow without starting the full server.
  (testing "config threading integration"
    (testing "tools receive config with git mode enabled"
      (let [config {:use-git? true}
            complete-tool (tools/complete-task-tool config)
            next-tool (tools/next-task-tool config)
            add-tool (tools/add-task-tool config)]
        (is (map? complete-tool))
        (is (= "complete-task" (:name complete-tool)))
        (is (fn? (:implementation complete-tool)))
        (is (map? next-tool))
        (is (= "next-task" (:name next-tool)))
        (is (map? add-tool))
        (is (= "add-task" (:name add-tool)))))

    (testing "tools receive config with git mode disabled"
      (let [config {:use-git? false}
            complete-tool (tools/complete-task-tool config)
            next-tool (tools/next-task-tool config)
            add-tool (tools/add-task-tool config)]
        (is (map? complete-tool))
        (is (= "complete-task" (:name complete-tool)))
        (is (fn? (:implementation complete-tool)))
        (is (map? next-tool))
        (is (= "next-task" (:name next-tool)))
        (is (map? add-tool))
        (is (= "add-task" (:name add-tool)))))

    (testing "prompts receive config and generate correctly with git mode enabled"
      (let [temp-dir (File/createTempFile "mcp-tasks-test" "")
            _ (.delete temp-dir)
            _ (.mkdirs temp-dir)
            mcp-tasks-dir (io/file temp-dir ".mcp-tasks")
            tasks-dir (io/file mcp-tasks-dir "tasks")
            _ (.mkdirs tasks-dir)
            _ (spit (io/file tasks-dir "simple.md") "- [ ] test task\n")]
        (try
          (let [config {:use-git? true :base-dir (.getPath temp-dir)}
                prompt-map (prompts/prompts config)
                simple-prompt (get prompt-map "next-simple")]
            (is (map? prompt-map))
            (is (map? simple-prompt))
            (is (= "next-simple" (:name simple-prompt)))
            (let [message-text (get-in simple-prompt [:messages 0 :content :text])]
              (is (string? message-text))
              (is (re-find #"Commit the task tracking changes" message-text))))
          (finally
            (.delete (io/file tasks-dir "simple.md"))
            (.delete tasks-dir)
            (.delete mcp-tasks-dir)
            (.delete temp-dir)))))

    (testing "prompts receive config and generate correctly with git mode disabled"
      (let [temp-dir (File/createTempFile "mcp-tasks-test" "")
            _ (.delete temp-dir)
            _ (.mkdirs temp-dir)
            mcp-tasks-dir (io/file temp-dir ".mcp-tasks")
            tasks-dir (io/file mcp-tasks-dir "tasks")
            _ (.mkdirs tasks-dir)
            _ (spit (io/file tasks-dir "simple.md") "- [ ] test task\n")]
        (try
          (let [config {:use-git? false :base-dir (.getPath temp-dir)}
                prompt-map (prompts/prompts config)
                simple-prompt (get prompt-map "next-simple")]
            (is (map? prompt-map))
            (is (map? simple-prompt))
            (is (= "next-simple" (:name simple-prompt)))
            (let [message-text (get-in simple-prompt [:messages 0 :content :text])]
              (is (string? message-text))
              (is (not
                   (re-find #"Commit the task tracking changes" message-text)))))
          (finally
            (.delete (io/file tasks-dir "simple.md"))
            (.delete tasks-dir)
            (.delete mcp-tasks-dir)
            (.delete temp-dir)))))

    (testing "config flows from load-and-validate-config through to components"
      (let [temp-dir (File/createTempFile "mcp-tasks-test" "")
            _ (.delete temp-dir)
            _ (.mkdirs temp-dir)
            mcp-tasks-dir (io/file temp-dir ".mcp-tasks")
            tasks-dir (io/file mcp-tasks-dir "tasks")
            _ (.mkdirs tasks-dir)
            _ (spit (io/file tasks-dir "simple.md") "- [ ] test task\n")
            config-file (io/file temp-dir ".mcp-tasks.edn")]
        (try
          (spit config-file "{:use-git? false}")
          (let [config (#'sut/load-and-validate-config
                        (.getPath temp-dir))
                complete-tool (tools/complete-task-tool config)
                prompt-map (prompts/prompts config)
                simple-prompt (get prompt-map "next-simple")]
            (is (false? (:use-git? config)))
            (is (= (.getPath temp-dir) (:base-dir config)))
            (is (map? complete-tool))
            (is (map? simple-prompt))
            (let [message-text (get-in
                                simple-prompt
                                [:messages 0 :content :text])]
              (is (not
                   (re-find
                    #"Commit the task tracking changes"
                    message-text)))))
          (finally
            (.delete config-file)
            (.delete (io/file tasks-dir "simple.md"))
            (.delete tasks-dir)
            (.delete mcp-tasks-dir)
            (.delete temp-dir)))))))

(deftest create-server-config-test
  ;; Test that create-server-config properly includes all tools and prompts,
  ;; including story prompts.
  (testing "create-server-config"
    (testing "includes all required tools"
      (let [config {:use-git? false}
            transport {:type :in-memory}
            server-config (sut/create-server-config config transport)]
        (is (map? server-config))
        (is (= transport (:transport server-config)))
        (is (map? (:tools server-config)))
        (is (contains? (:tools server-config) "complete-task"))
        (is (contains? (:tools server-config) "next-task"))
        (is (contains? (:tools server-config) "add-task"))
        (is (contains? (:tools server-config) "next-story-task"))
        (is (contains? (:tools server-config) "complete-story-task"))))

    (testing "includes story prompts"
      (let [temp-dir (File/createTempFile "mcp-tasks-test" "")
            _ (.delete temp-dir)
            _ (.mkdirs temp-dir)
            mcp-tasks-dir (io/file temp-dir ".mcp-tasks")
            tasks-dir (io/file mcp-tasks-dir "tasks")
            _ (.mkdirs tasks-dir)
            _ (spit (io/file tasks-dir "simple.md") "- [ ] test task\n")
            config {:use-git? false :base-dir (.getPath temp-dir)}
            transport {:type :in-memory}
            server-config (sut/create-server-config config transport)]
        (try
          (is (map? (:prompts server-config)))
          (is (contains? (:prompts server-config) "refine-story"))
          (is (contains? (:prompts server-config) "create-story-tasks"))
          (is (contains? (:prompts server-config) "execute-story-task"))
          (is (contains? (:prompts server-config) "review-story-implementation"))
          (is (map? (get (:prompts server-config) "refine-story")))
          (is (= "refine-story" (:name (get (:prompts server-config) "refine-story"))))
          (finally
            (.delete (io/file tasks-dir "simple.md"))
            (.delete tasks-dir)
            (.delete mcp-tasks-dir)
            (.delete temp-dir)))))

    (testing "merges task prompts and story prompts"
      (let [temp-dir (File/createTempFile "mcp-tasks-test" "")
            _ (.delete temp-dir)
            _ (.mkdirs temp-dir)
            mcp-tasks-dir (io/file temp-dir ".mcp-tasks")
            tasks-dir (io/file mcp-tasks-dir "tasks")
            _ (.mkdirs tasks-dir)
            _ (spit (io/file tasks-dir "simple.md") "- [ ] test task\n")
            config {:use-git? false :base-dir (.getPath temp-dir)}
            transport {:type :in-memory}
            server-config (sut/create-server-config config transport)]
        (try
          (is (map? (:prompts server-config)))
          (is (contains? (:prompts server-config) "next-simple"))
          (is (contains? (:prompts server-config) "refine-story"))
          (finally
            (.delete (io/file tasks-dir "simple.md"))
            (.delete tasks-dir)
            (.delete mcp-tasks-dir)
            (.delete temp-dir)))))

    (testing "review-story-implementation prompt has correct arguments"
      (let [temp-dir (File/createTempFile "mcp-tasks-test" "")
            _ (.delete temp-dir)
            _ (.mkdirs temp-dir)
            mcp-tasks-dir (io/file temp-dir ".mcp-tasks")
            tasks-dir (io/file mcp-tasks-dir "tasks")
            _ (.mkdirs tasks-dir)
            _ (spit (io/file tasks-dir "simple.md") "- [ ] test task\n")
            config {:use-git? false :base-dir (.getPath temp-dir)}
            transport {:type :in-memory}
            server-config (sut/create-server-config config transport)]
        (try
          (let [prompt (get (:prompts server-config) "review-story-implementation")]
            (is (map? prompt))
            (is (= "review-story-implementation" (:name prompt)))
            (is (vector? (:arguments prompt)))
            (is (= 2 (count (:arguments prompt))))
            (let [[story-name-arg context-arg] (:arguments prompt)]
              (is (= "story-name" (:name story-name-arg)))
              (is (true? (:required story-name-arg)))
              (is (= "additional-context" (:name context-arg)))
              (is (false? (:required context-arg)))))
          (finally
            (.delete (io/file tasks-dir "simple.md"))
            (.delete tasks-dir)
            (.delete mcp-tasks-dir)
            (.delete temp-dir)))))))

(deftest complete-story-integration-test
  ;; Test that complete-story tool works end-to-end through the MCP server config
  (testing "complete-story integration"
    (testing "tool is registered in server config"
      (let [config {:use-git? false}
            transport {:type :in-memory}
            server-config (sut/create-server-config config transport)]
        (is (map? (:tools server-config)))
        (is (contains? (:tools server-config) "complete-story"))
        (is (map? (get (:tools server-config) "complete-story")))
        (is (= "complete-story" (:name (get (:tools server-config) "complete-story"))))))

    (testing "successfully completes story through tool interface"
      (let [temp-dir (File/createTempFile "mcp-tasks-test" "")
            _ (.delete temp-dir)
            _ (.mkdirs temp-dir)
            mcp-tasks-dir (io/file temp-dir ".mcp-tasks")
            stories-dir (io/file mcp-tasks-dir "story" "stories")
            tasks-dir (io/file mcp-tasks-dir "story" "story-tasks")
            _ (.mkdirs stories-dir)
            _ (.mkdirs tasks-dir)
            story-file (io/file stories-dir "test-story.md")
            tasks-file (io/file tasks-dir "test-story-tasks.md")
            _ (spit story-file "# Test Story\n\nStory content here")
            _ (spit tasks-file "- [x] Task 1\n\nCATEGORY: simple\n")
            config {:use-git? false :base-dir (.getPath temp-dir)}
            transport {:type :in-memory}
            server-config (sut/create-server-config config transport)
            complete-story-tool (get (:tools server-config) "complete-story")
            result ((:implementation complete-story-tool)
                    nil
                    {:story-name "test-story"})]
        (try
          (is (false? (:isError result)))
          (is (= 1 (count (:content result))))
          (is (re-find #"marked as complete" (get-in result [:content 0 :text])))
          (is (not (.exists story-file)))
          (is (not (.exists tasks-file)))
          (is (.exists (io/file mcp-tasks-dir "story" "complete" "test-story.md")))
          (is (.exists (io/file mcp-tasks-dir "story" "story-tasks-complete" "test-story-tasks.md")))
          (finally
            (doseq [file (reverse (file-seq mcp-tasks-dir))]
              (.delete file))
            (.delete temp-dir)))))

    (testing "returns modified files in git mode"
      (let [temp-dir (File/createTempFile "mcp-tasks-test" "")
            _ (.delete temp-dir)
            _ (.mkdirs temp-dir)
            mcp-tasks-dir (io/file temp-dir ".mcp-tasks")
            stories-dir (io/file mcp-tasks-dir "story" "stories")
            tasks-dir (io/file mcp-tasks-dir "story" "story-tasks")
            _ (.mkdirs stories-dir)
            _ (.mkdirs tasks-dir)
            story-file (io/file stories-dir "test-story.md")
            tasks-file (io/file tasks-dir "test-story-tasks.md")
            _ (spit story-file "# Test Story")
            _ (spit tasks-file "- [x] Task\n\nCATEGORY: simple\n")
            config {:use-git? true :base-dir (.getPath temp-dir)}
            transport {:type :in-memory}
            server-config (sut/create-server-config config transport)
            complete-story-tool (get (:tools server-config) "complete-story")
            result ((:implementation complete-story-tool)
                    nil
                    {:story-name "test-story"})]
        (try
          (is (false? (:isError result)))
          (is (= 2 (count (:content result))))
          (is (= "text" (:type (second (:content result)))))
          (let [json-text (:text (second (:content result)))]
            (is (string? json-text))
            (is (re-find #"modified-files" json-text)))
          (finally
            (doseq [file (reverse (file-seq mcp-tasks-dir))]
              (.delete file))
            (.delete temp-dir)))))))
