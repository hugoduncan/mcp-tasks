(ns mcp-tasks.main-test
  (:require
    [babashka.fs :as fs]
    [cheshire.core]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.config :as config]
    [mcp-tasks.main :as sut]
    [mcp-tasks.prompts :as prompts]
    [mcp-tasks.tools :as tools])
  (:import
    (java.io
      File)))

;; Test Fixtures

(defmacro with-temp-categories
  "Create temporary config directory with category-prompts.

  Usage: (with-temp-categories categories git-mode? [temp-dir config] ...body...)

  categories - map of category-name -> content string
  git-mode? - boolean for :use-git? config (default false)
  binding-vec - vector [temp-dir-sym config-sym] for bindings
  body - test forms to execute"
  [categories git-mode? [temp-dir-sym config-sym] & body]
  (let [mcp-tasks-dir (gensym "mcp-tasks-dir")
        prompts-dir (gensym "prompts-dir")
        config-dir (gensym "config-dir")
        raw-config (gensym "raw-config")
        cat-name (gensym "cat-name")
        content (gensym "content")]
    `(let [~temp-dir-sym (File/createTempFile "mcp-tasks-test" "")
           ~'_ (fs/delete ~temp-dir-sym)
           ~'_ (.mkdirs ~temp-dir-sym)
           ~mcp-tasks-dir (io/file ~temp-dir-sym ".mcp-tasks")
           ~prompts-dir (io/file ~mcp-tasks-dir "category-prompts")
           ~'_ (.mkdirs ~prompts-dir)
           ;; Create category files
           ~'_ (doseq [[~cat-name ~content] ~categories]
                 (spit (io/file ~prompts-dir (str ~cat-name ".md")) ~content))
           ~config-dir (.getPath ~temp-dir-sym)
           ~raw-config {:use-git? ~git-mode?}
           ~config-sym (config/resolve-config ~config-dir ~raw-config)]
       (try
         ~@body
         (finally
           ;; Clean up category files
           (doseq [[~cat-name ~'_] ~categories]
             (fs/delete (io/file ~prompts-dir (str ~cat-name ".md"))))
           (fs/delete ~prompts-dir)
           (fs/delete ~mcp-tasks-dir)
           (fs/delete ~temp-dir-sym))))))

(deftest get-prompt-vars-test
  ;; Test that get-prompt-vars finds all prompt definitions from task-prompts 
  ;; namespace vars and resources/prompts files.
  (testing "get-prompt-vars"
    (testing "returns sequence of prompt maps"
      (let [prompts (#'sut/get-prompt-vars)]
        (is (seq prompts))
        (is (every? map? prompts))
        (is (every? #(contains? % :name) prompts))
        (is (every? #(contains? % :content) prompts))))

    (testing "returns only prompts with string content"
      (let [prompts (#'sut/get-prompt-vars)]
        (is (every? #(string? (:content %)) prompts))))

    (testing "finds all defined prompts including task execution prompts"
      (let [prompts (#'sut/get-prompt-vars)
            prompt-names (set (map :name prompts))]
        ;; Category prompts from task-prompts namespace
        (is (contains? prompt-names "clarify-task"))
        (is (contains? prompt-names "simple"))
        ;; Task execution prompts from resources/prompts
        (is (contains? prompt-names "execute-task"))
        (is (contains? prompt-names "refine-task"))
        ;; Story prompts from story-prompts namespace
        (is (contains? prompt-names "create-story-tasks"))
        (is (>= (count prompt-names) 7))))))

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

(deftest list-builtin-categories-test
  ;; Test that list-builtin-categories returns the correct set of categories.
  (testing "list-builtin-categories"
    (testing "returns set of built-in category names"
      (let [categories (#'sut/list-builtin-categories)]
        (is (set? categories))
        (is (contains? categories "simple"))
        (is (contains? categories "medium"))
        (is (contains? categories "large"))
        (is (contains? categories "clarify-task"))))

    (testing "does not contain workflow prompts"
      (let [categories (#'sut/list-builtin-categories)]
        (is (not (contains? categories "execute-task")))
        (is (not (contains? categories "refine-task")))
        (is (not (contains? categories "complete-story")))))))

(deftest install-prompt-test
  ;; Test that install-prompt handles various edge cases and directory routing.
  (testing "install-prompt"
    (testing "warns on nonexistent prompt"
      (let [output (with-out-str (#'sut/install-prompt "nonexistent"))
            exit-code (#'sut/install-prompt "nonexistent")]
        (is (str/includes? output "Warning"))
        (is (str/includes? output "not found"))
        (is (= 1 exit-code))))

    (testing "installs category prompt to category-prompts directory"
      (let [target-file-atom (atom nil)]
        (with-redefs [fs/exists? (constantly false)
                      fs/create-dirs (fn [_] nil)
                      spit (fn [file _content]
                             (reset! target-file-atom file))]
          (binding [*out* (java.io.StringWriter.)]
            (#'sut/install-prompt "simple"))
          (is (some? @target-file-atom))
          (is (str/includes? @target-file-atom ".mcp-tasks/category-prompts/simple.md")))))

    (testing "installs workflow prompt to prompt-overrides directory"
      (let [target-file-atom (atom nil)]
        (with-redefs [fs/exists? (constantly false)
                      fs/create-dirs (fn [_] nil)
                      spit (fn [file _content]
                             (reset! target-file-atom file))]
          (binding [*out* (java.io.StringWriter.)]
            (#'sut/install-prompt "execute-task"))
          (is (some? @target-file-atom))
          (is (str/includes? @target-file-atom ".mcp-tasks/prompt-overrides/execute-task.md")))))

    (testing "skips existing files with message"
      (let [temp-dir (fs/create-temp-dir)
            test-file (fs/path temp-dir ".mcp-tasks" "category-prompts" "simple.md")]
        (try
          (fs/create-dirs (fs/parent test-file))
          (spit (str test-file) "existing content")
          (with-redefs [fs/exists? (constantly true)]
            (let [output (with-out-str (#'sut/install-prompt "simple"))]
              (is (str/includes? output "Skipping simple"))
              (is (str/includes? output "already exists"))))
          (finally
            (fs/delete-tree temp-dir)))))))

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
            _ (fs/delete temp-dir)
            _ (.mkdirs temp-dir)]
        (try
          (let [config (#'sut/load-and-validate-config (.getPath temp-dir))]
            (is (map? config))
            (is (contains? config :use-git?))
            (is (boolean? (:use-git? config))))
          (finally
            (fs/delete temp-dir)))))

    (testing "loads and resolves config with valid config file"
      (let [temp-dir (File/createTempFile "mcp-tasks-test" "")
            _ (fs/delete temp-dir)
            _ (.mkdirs temp-dir)
            config-file (io/file temp-dir ".mcp-tasks.edn")]
        (try
          (spit config-file "{:use-git? false}")
          (let [config (#'sut/load-and-validate-config (.getPath temp-dir))]
            (is (map? config))
            (is (false? (:use-git? config))))
          (finally
            (fs/delete config-file)
            (fs/delete temp-dir)))))

    (testing "finds config in parent directory"
      (let [temp-dir (File/createTempFile "mcp-tasks-test" "")
            _ (fs/delete temp-dir)
            _ (.mkdirs temp-dir)
            sub-dir (io/file temp-dir "subdir")
            _ (.mkdirs sub-dir)
            config-file (io/file temp-dir ".mcp-tasks.edn")]
        (try
          (spit config-file "{:use-git? false}")
          ;; Load config starting from subdirectory
          (let [config (#'sut/load-and-validate-config (.getPath sub-dir))]
            (is (map? config))
            (is (false? (:use-git? config)))
            ;; base-dir should be start-dir (subdirectory where we started), not config-dir
            (is (= (str (fs/canonicalize sub-dir)) (:base-dir config))))
          (finally
            (fs/delete config-file)
            (fs/delete sub-dir)
            (fs/delete temp-dir)))))

    (testing "throws on invalid config"
      (let [temp-dir (File/createTempFile "mcp-tasks-test" "")
            _ (fs/delete temp-dir)
            _ (.mkdirs temp-dir)
            config-file (io/file temp-dir ".mcp-tasks.edn")]
        (try
          (spit config-file "{:use-git? \"not-a-boolean\"}")
          (is (thrown? Exception
                (#'sut/load-and-validate-config (.getPath temp-dir))))
          (finally
            (fs/delete config-file)
            (fs/delete temp-dir)))))

    (testing "throws on malformed EDN"
      (let [temp-dir (File/createTempFile "mcp-tasks-test" "")
            _ (fs/delete temp-dir)
            _ (.mkdirs temp-dir)
            config-file (io/file temp-dir ".mcp-tasks.edn")]
        (try
          (spit config-file "{:invalid")
          (is (thrown? Exception
                (#'sut/load-and-validate-config (.getPath temp-dir))))
          (finally
            (fs/delete config-file)
            (fs/delete temp-dir)))))

    (testing "validates git repo when use-git is true"
      (let [temp-dir (File/createTempFile "mcp-tasks-test" "")
            _ (fs/delete temp-dir)
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
            (fs/delete config-file)
            (fs/delete git-dir)
            (fs/delete mcp-tasks-dir)
            (fs/delete temp-dir)))))))

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
            tools-map (tools/tools config)
            complete-tool (get tools-map "complete-task")
            next-tool (get tools-map "select-tasks")
            add-tool (get tools-map "add-task")]
        (is (map? complete-tool))
        (is (= "complete-task" (:name complete-tool)))
        (is (fn? (:implementation complete-tool)))
        (is (map? next-tool))
        (is (= "select-tasks" (:name next-tool)))
        (is (map? add-tool))
        (is (= "add-task" (:name add-tool)))))

    (testing "tools receive config with git mode disabled"
      (let [config {:use-git? false}
            tools-map (tools/tools config)
            complete-tool (get tools-map "complete-task")
            next-tool (get tools-map "select-tasks")
            add-tool (get tools-map "add-task")]
        (is (map? complete-tool))
        (is (= "complete-task" (:name complete-tool)))
        (is (fn? (:implementation complete-tool)))
        (is (map? next-tool))
        (is (= "select-tasks" (:name next-tool)))
        (is (map? add-tool))
        (is (= "add-task" (:name add-tool)))))

    (testing "prompts receive config and generate correctly with git mode enabled"
      (let [temp-dir (File/createTempFile "mcp-tasks-test" "")
            _ (fs/delete temp-dir)
            _ (.mkdirs temp-dir)
            mcp-tasks-dir (io/file temp-dir ".mcp-tasks")
            prompts-dir (io/file mcp-tasks-dir "category-prompts")
            _ (.mkdirs prompts-dir)
            _ (spit (io/file prompts-dir "simple.md") "Test instructions\n")
            config-dir (.getPath temp-dir)
            raw-config {:use-git? true}
            resolved-config (config/resolve-config config-dir raw-config)]
        (try
          (let [prompt-map (prompts/prompts resolved-config)
                simple-prompt (get prompt-map "next-simple")]
            (is (map? prompt-map))
            (is (map? simple-prompt))
            (is (= "next-simple" (:name simple-prompt)))
            (let [message-text (get-in simple-prompt [:messages 0 :content :text])]
              (is (string? message-text))
              (is (re-find #"Commit the task tracking changes" message-text))))
          (finally
            (fs/delete (io/file prompts-dir "simple.md"))
            (fs/delete prompts-dir)
            (fs/delete mcp-tasks-dir)
            (fs/delete temp-dir)))))

    (testing "prompts receive config and generate correctly with git mode disabled"
      (let [temp-dir (File/createTempFile "mcp-tasks-test" "")
            _ (fs/delete temp-dir)
            _ (.mkdirs temp-dir)
            mcp-tasks-dir (io/file temp-dir ".mcp-tasks")
            prompts-dir (io/file mcp-tasks-dir "category-prompts")
            _ (.mkdirs prompts-dir)
            _ (spit (io/file prompts-dir "simple.md") "Test instructions\n")
            config-dir (.getPath temp-dir)
            raw-config {:use-git? false}
            resolved-config (config/resolve-config config-dir raw-config)]
        (try
          (let [prompt-map (prompts/prompts resolved-config)
                simple-prompt (get prompt-map "next-simple")]
            (is (map? prompt-map))
            (is (map? simple-prompt))
            (is (= "next-simple" (:name simple-prompt)))
            (let [message-text (get-in simple-prompt [:messages 0 :content :text])]
              (is (string? message-text))
              (is (not
                    (re-find #"Commit the task tracking changes" message-text)))))
          (finally
            (fs/delete (io/file prompts-dir "simple.md"))
            (fs/delete prompts-dir)
            (fs/delete mcp-tasks-dir)
            (fs/delete temp-dir)))))

    (testing "config flows from load-and-validate-config through to components"
      (let [temp-dir (File/createTempFile "mcp-tasks-test" "")
            _ (fs/delete temp-dir)
            _ (.mkdirs temp-dir)
            mcp-tasks-dir (io/file temp-dir ".mcp-tasks")
            prompts-dir (io/file mcp-tasks-dir "category-prompts")
            _ (.mkdirs prompts-dir)
            _ (spit (io/file prompts-dir "simple.md") "Test instructions\n")
            config-file (io/file temp-dir ".mcp-tasks.edn")]
        (try
          (spit config-file "{:use-git? false}")
          (let [config (#'sut/load-and-validate-config
                        (.getPath temp-dir))
                tools-map (tools/tools config)
                complete-tool (get tools-map "complete-task")
                prompt-map (prompts/prompts config)
                simple-prompt (get prompt-map "next-simple")]
            (is (false? (:use-git? config)))
            (is (= (str (fs/canonicalize temp-dir)) (:base-dir config)))
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
            (fs/delete config-file)
            (fs/delete (io/file prompts-dir "simple.md"))
            (fs/delete prompts-dir)
            (fs/delete mcp-tasks-dir)
            (fs/delete temp-dir)))))))

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
        (is (contains? (:tools server-config) "select-tasks"))
        (is (contains? (:tools server-config) "add-task"))))

    (testing "includes story prompts"
      (let [temp-dir (File/createTempFile "mcp-tasks-test" "")
            _ (fs/delete temp-dir)
            _ (.mkdirs temp-dir)
            mcp-tasks-dir (io/file temp-dir ".mcp-tasks")
            prompts-dir (io/file mcp-tasks-dir "category-prompts")
            _ (.mkdirs prompts-dir)
            _ (spit (io/file prompts-dir "simple.md") "Test instructions\n")
            config-dir (.getPath temp-dir)
            raw-config {:use-git? false}
            resolved-config (config/resolve-config config-dir raw-config)
            transport {:type :in-memory}
            server-config (sut/create-server-config resolved-config transport)]
        (try
          (is (map? (:prompts server-config)))
          (is (contains? (:prompts server-config) "create-story-tasks"))
          (is (contains? (:prompts server-config) "execute-story-child"))
          (is (contains? (:prompts server-config) "review-story-implementation"))
          (is (contains? (:prompts server-config) "create-story-pr"))
          (is (map? (get (:prompts server-config) "create-story-tasks")))
          (is (= "create-story-tasks" (:name (get (:prompts server-config) "create-story-tasks"))))
          (finally
            (fs/delete (io/file prompts-dir "simple.md"))
            (fs/delete prompts-dir)
            (fs/delete mcp-tasks-dir)
            (fs/delete temp-dir)))))

    (testing "merges task prompts and story prompts"
      (let [temp-dir (File/createTempFile "mcp-tasks-test" "")
            _ (fs/delete temp-dir)
            _ (.mkdirs temp-dir)
            mcp-tasks-dir (io/file temp-dir ".mcp-tasks")
            prompts-dir (io/file mcp-tasks-dir "category-prompts")
            _ (.mkdirs prompts-dir)
            _ (spit (io/file prompts-dir "simple.md") "Test instructions\n")
            config-dir (.getPath temp-dir)
            raw-config {:use-git? false}
            resolved-config (config/resolve-config config-dir raw-config)
            transport {:type :in-memory}
            server-config (sut/create-server-config resolved-config transport)]
        (try
          (is (map? (:prompts server-config)))
          (is (contains? (:prompts server-config) "next-simple"))
          (is (contains? (:prompts server-config) "create-story-tasks"))
          (finally
            (fs/delete (io/file prompts-dir "simple.md"))
            (fs/delete prompts-dir)
            (fs/delete mcp-tasks-dir)
            (fs/delete temp-dir)))))

    (testing "review-story-implementation prompt has correct arguments"
      (let [temp-dir (File/createTempFile "mcp-tasks-test" "")
            _ (fs/delete temp-dir)
            _ (.mkdirs temp-dir)
            mcp-tasks-dir (io/file temp-dir ".mcp-tasks")
            prompts-dir (io/file mcp-tasks-dir "category-prompts")
            _ (.mkdirs prompts-dir)
            _ (spit (io/file prompts-dir "simple.md") "Test instructions\n")
            config-dir (.getPath temp-dir)
            raw-config {:use-git? false}
            resolved-config (config/resolve-config config-dir raw-config)
            transport {:type :in-memory}
            server-config (sut/create-server-config resolved-config transport)]
        (try
          (let [prompt (get (:prompts server-config) "review-story-implementation")]
            (is (map? prompt))
            (is (= "review-story-implementation" (:name prompt)))
            (is (vector? (:arguments prompt)))
            (is (= 2 (count (:arguments prompt))))
            (let [[story-name-arg context-arg] (:arguments prompt)]
              (is (= "story-specification" (:name story-name-arg)))
              (is (false? (:required story-name-arg)))
              (is (= "additional-context" (:name context-arg)))
              (is (false? (:required context-arg)))))
          (finally
            (fs/delete (io/file prompts-dir "simple.md"))
            (fs/delete prompts-dir)
            (fs/delete mcp-tasks-dir)
            (fs/delete temp-dir)))))

    (testing "create-story-pr prompt has correct arguments"
      (let [temp-dir (File/createTempFile "mcp-tasks-test" "")
            _ (fs/delete temp-dir)
            _ (.mkdirs temp-dir)
            mcp-tasks-dir (io/file temp-dir ".mcp-tasks")
            prompts-dir (io/file mcp-tasks-dir "category-prompts")
            _ (.mkdirs prompts-dir)
            _ (spit (io/file prompts-dir "simple.md") "Test instructions\n")
            config-dir (.getPath temp-dir)
            raw-config {:use-git? false}
            resolved-config (config/resolve-config config-dir raw-config)
            transport {:type :in-memory}
            server-config (sut/create-server-config resolved-config transport)]
        (try
          (let [prompt (get (:prompts server-config) "create-story-pr")]
            (is (map? prompt))
            (is (= "create-story-pr" (:name prompt)))
            (is (vector? (:arguments prompt)))
            (is (= 2 (count (:arguments prompt))))
            (let [[story-spec-arg context-arg] (:arguments prompt)]
              (is (= "story-specification" (:name story-spec-arg)))
              (is (false? (:required story-spec-arg)))
              (is (= "additional-context" (:name context-arg)))
              (is (false? (:required context-arg)))))
          (finally
            (fs/delete (io/file prompts-dir "simple.md"))
            (fs/delete prompts-dir)
            (fs/delete mcp-tasks-dir)
            (fs/delete temp-dir))))))

  (testing "includes correct server-info"
    (let [config {:use-git? false}
          transport {:type :in-memory}
          server-config (sut/create-server-config config transport)]
      (is (map? (:server-info server-config)))
      (is (= "mcp-tasks" (get-in server-config [:server-info :name])))
      (is (= "0.1.124" (get-in server-config [:server-info :version])))
      (is (= "MCP Tasks Server" (get-in server-config [:server-info :title])))))

  (testing "includes categories resource"
    (let [config {:use-git? false}
          transport {:type :in-memory}
          server-config (sut/create-server-config config transport)]
      (is (map? (:resources server-config)))
      (is (contains? (:resources server-config) "resource://categories"))
      (let [resource (get (:resources server-config) "resource://categories")]
        (is (map? resource))
        (is (= "categories" (:name resource)))
        (is (= "resource://categories" (:uri resource)))
        (is (= "application/json" (:mime-type resource)))
        (is (= "Available task categories and their descriptions" (:description resource)))
        (is (fn? (:implementation resource))))))

  (testing "categories resource end-to-end integration"
    (with-temp-categories {"simple" "---\ndescription: Simple workflow\n---\nTest\n"
                           "custom" "---\ndescription: Custom workflow\n---\nTest\n"}
      false
      [_temp-dir resolved-config]
      (let [transport {:type :in-memory}
            server-config (sut/create-server-config resolved-config transport)
            resource (get (:resources server-config) "resource://categories")
            implementation (:implementation resource)
            result (implementation nil "resource://categories")]
        (is (not (:isError result)))
        (is (vector? (:contents result)))
        (is (= 1 (count (:contents result))))
        (let [content (first (:contents result))
              json-text (:text content)
              parsed (cheshire.core/parse-string json-text true)]
          (is (= "resource://categories" (:uri content)))
          (is (= "application/json" (:mimeType content)))
          (is (contains? parsed :categories))
          (is (vector? (:categories parsed)))
          (is (>= (count (:categories parsed)) 2))
          (let [category-names (set (map :name (:categories parsed)))]
            (is (contains? category-names "simple"))
            (is (contains? category-names "custom")))
          (let [simple-cat (first (filter #(= "simple" (:name %)) (:categories parsed)))]
            (is (= "Simple workflow" (:description simple-cat)))))))))
