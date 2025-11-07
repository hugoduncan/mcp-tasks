(ns mcp-tasks.prompt-content-test
  "Integration tests for prompt resource content and structure.
  
  Tests prompt resource content validation, YAML frontmatter, category prompts,
  and story prompt argument documentation."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-clj.mcp-client.core :as mcp-client]
    [mcp-tasks.integration-test-fixtures :as fixtures]))

(use-fixtures :each fixtures/with-test-project)

(deftest ^:integ prompt-resources-content-test
  ;; Test comprehensive validation of prompt resource content, metadata, and formatting.
  ;; Validates YAML frontmatter structure and prompt message text consistency.
  (testing "prompt resources content validation"
    (fixtures/write-config-file "{:use-git? true}")
    (fixtures/init-test-git-repo)

    (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
      (try
        (testing "resource content includes YAML frontmatter with description"
          (let [read-response @(mcp-client/read-resource client "prompt://next-simple")
                text (-> read-response :contents first :text)]
            (is (not (:isError read-response)))
            (is (str/starts-with? text "---\n"))
            (is (str/includes? text "\n---\n"))
            (is (str/includes? text "description:"))
            (let [frontmatter-end (str/index-of text "\n---\n")
                  frontmatter (subs text 0 frontmatter-end)]
              (is (str/includes? frontmatter "simple")))))

        (testing "resource content includes prompt message text"
          (let [read-response @(mcp-client/read-resource client "prompt://next-simple")
                text (-> read-response :contents first :text)]
            (is (not (:isError read-response)))
            (is (str/includes? text "complete the next simple task"))
            (let [frontmatter-end (+ (str/index-of text "\n---\n") 5)
                  message-text (subs text frontmatter-end)]
              (is (pos? (count (str/trim message-text)))))))

        (testing "story prompt includes argument-hint in frontmatter"
          (let [read-response @(mcp-client/read-resource client "prompt://execute-story-child")
                text (-> read-response :contents first :text)]
            (is (not (:isError read-response)))
            (is (str/starts-with? text "---\n"))
            (is (str/includes? text "argument-hint:"))
            (is (str/includes? text "[story-specification]"))
            (is (str/includes? text "Execute the next incomplete task"))))

        (testing "multiple prompts return distinct content"
          (let [simple-response @(mcp-client/read-resource client "prompt://next-simple")
                simple-text (-> simple-response :contents first :text)
                story-response @(mcp-client/read-resource client "prompt://execute-story-child")
                story-text (-> story-response :contents first :text)]
            (is (not (:isError simple-response)))
            (is (not (:isError story-response)))
            (is (not= simple-text story-text))
            (is (str/includes? simple-text "simple"))
            (is (str/includes? story-text "story"))))

        (testing "all listed resources can be read successfully"
          (let [resources-response @(mcp-client/list-resources client)
                resources (:resources resources-response)]
            (doseq [resource resources]
              (let [uri (:uri resource)
                    read-response @(mcp-client/read-resource client uri)
                    text (-> read-response :contents first :text)
                    is-category-resource? (str/starts-with? uri "prompt://category-")
                    is-prompt-resource? (str/starts-with? uri "prompt://")]
                (is (not (:isError read-response))
                    (str "Should read resource successfully: " uri))
                ;; Only prompt resources have frontmatter (not category or resource:// resources)
                (when (and is-prompt-resource? (not is-category-resource?))
                  (is (str/starts-with? text "---\n")
                      (str "Resource should have YAML frontmatter: " uri))
                  (is (str/includes? text "\n---\n")
                      (str "Resource should have complete frontmatter: " uri))
                  (is (str/includes? text "description:")
                      (str "Resource should have description metadata: " uri)))))))

        (finally
          (mcp-client/close! client)
          ((:stop server)))))))

(deftest ^:integ category-prompt-resources-integration-test
  ;; Test comprehensive validation of category prompt resources through MCP protocol.
  ;; Validates that category instructions are accessible as standalone resources
  ;; without task lookup/completion workflow.
  (testing "category prompt resources integration"
    (fixtures/write-config-file "{:use-git? true}")
    (fixtures/init-test-git-repo)

    ;; Create test prompt files
    (let [prompts-dir (io/file (fixtures/test-project-dir) ".mcp-tasks" "prompts")]
      (.mkdirs prompts-dir)
      (spit (io/file prompts-dir "simple.md")
            "---\ndescription: Execute simple tasks with basic workflow\n---\n\n- Analyze the task\n- Implement solution")
      (spit (io/file prompts-dir "medium.md")
            "---\ndescription: Execute medium complexity tasks\n---\n\n- Deep analysis\n- Design approach\n- Implementation")
      (spit (io/file prompts-dir "large.md")
            "---\ndescription: Execute large tasks with detailed planning\n---\n\n- Comprehensive analysis\n- Detailed design\n- Implementation\n- Testing")
      (spit (io/file prompts-dir "clarify-task.md")
            "---\ndescription: Transform informal instructions into clear specifications\n---\n\n- Analyze requirements\n- Clarify ambiguities"))

    (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
      (try
        (testing "category prompt resources are listed"
          (let [resources-response @(mcp-client/list-resources client)
                resources (:resources resources-response)
                category-resources (filter #(str/includes? (:uri %) "prompt://category-")
                                           resources)
                category-uris (set (map :uri category-resources))]
            (is (>= (count category-resources) 4)
                "Should have at least 4 category prompt resources")
            (is (contains? category-uris "prompt://category-simple"))
            (is (contains? category-uris "prompt://category-medium"))
            (is (contains? category-uris "prompt://category-large"))
            (is (contains? category-uris "prompt://category-clarify-task"))))

        (testing "category prompt resources have correct structure"
          (let [resources-response @(mcp-client/list-resources client)
                resources (:resources resources-response)
                category-resources (filter #(str/includes? (:uri %) "prompt://category-")
                                           resources)]
            (doseq [resource category-resources]
              (is (string? (:name resource)))
              (is (string? (:uri resource)))
              (is (= "text/markdown" (:mimeType resource)))
              (is (string? (:description resource))))))

        (testing "can read category prompt resource content"
          (let [read-response @(mcp-client/read-resource client "prompt://category-simple")
                text (-> read-response :contents first :text)]
            (is (not (:isError read-response)))
            (is (string? text))
            (is (pos? (count text)))
            (testing "content includes frontmatter"
              (is (str/includes? text "---")
                  "Should contain frontmatter delimiters")
              (is (str/includes? text "description:")
                  "Should contain frontmatter fields"))
            (testing "content includes instructions"
              (is (str/includes? text "Analyze the task")))))

        (testing "each category resource has distinct content"
          (let [simple-text (-> @(mcp-client/read-resource client "prompt://category-simple")
                                :contents first :text)
                medium-text (-> @(mcp-client/read-resource client "prompt://category-medium")
                                :contents first :text)
                large-text (-> @(mcp-client/read-resource client "prompt://category-large")
                               :contents first :text)]
            (is (not= simple-text medium-text))
            (is (not= simple-text large-text))
            (is (not= medium-text large-text))
            (is (str/includes? simple-text "Analyze the task"))
            (is (str/includes? medium-text "Deep analysis"))
            (is (str/includes? large-text "Comprehensive analysis"))))

        (testing "description comes from frontmatter"
          (let [resources-response @(mcp-client/list-resources client)
                resources (:resources resources-response)
                simple-resource (first (filter #(= (:uri %) "prompt://category-simple")
                                               resources))]
            (is (= "Execute simple tasks with basic workflow"
                   (:description simple-resource)))))

        (testing "existing next-<category> prompts still work"
          (let [prompts-response @(mcp-client/list-prompts client)
                prompts (:prompts prompts-response)
                prompt-names (set (map :name prompts))]
            (is (contains? prompt-names "next-simple"))
            (is (contains? prompt-names "next-medium"))
            (is (contains? prompt-names "next-large"))
            (is (contains? prompt-names "next-clarify-task"))))

        (finally
          (mcp-client/close! client)
          ((:stop server)))))))

(deftest ^:integ story-prompts-arguments-test
  ;; Test that all story prompts properly document their arguments as optional.
  ;; Validates argument parsing, MCP schema, markdown documentation, and consistency.
  (testing "story prompts arguments validation"
    (fixtures/write-config-file "{:use-git? true}")
    (fixtures/init-test-git-repo)

    (let [{:keys [server client]} (fixtures/create-test-server-and-client)
          story-prompt-names ["execute-story-child"
                              "create-story-tasks"
                              "review-story-implementation"
                              "complete-story"
                              "create-story-pr"]]
      (try
        (testing "all story prompts are registered"
          (let [prompts-response @(mcp-client/list-prompts client)
                prompts (:prompts prompts-response)
                prompt-names (set (map :name prompts))]
            (doseq [story-prompt story-prompt-names]
              (is (contains? prompt-names story-prompt)
                  (str "Should have prompt: " story-prompt)))))

        (testing "all story prompts have arguments with :required false"
          (let [prompts-response @(mcp-client/list-prompts client)
                prompts (:prompts prompts-response)]
            (doseq [story-prompt-name story-prompt-names]
              (let [prompt (first (filter #(= (:name %) story-prompt-name) prompts))]
                (testing (str story-prompt-name " has arguments field")
                  (is (contains? prompt :arguments)
                      (str story-prompt-name " should have :arguments field"))
                  (when (contains? prompt :arguments)
                    (let [arguments (:arguments prompt)]
                      (is (seq arguments)
                          (str story-prompt-name " should have at least one argument"))
                      (testing (str story-prompt-name " arguments are all optional")
                        (doseq [arg arguments]
                          (is (false? (:required arg))
                              (str story-prompt-name " argument " (:name arg)
                                   " should have :required false")))))))))))

        (testing "prompt resources include frontmatter with argument-hint"
          (doseq [story-prompt-name story-prompt-names]
            (let [uri (str "prompt://" story-prompt-name)
                  read-response @(mcp-client/read-resource client uri)
                  text (-> read-response :contents first :text)]
              (testing (str story-prompt-name " resource has frontmatter")
                (is (not (:isError read-response)))
                (is (str/starts-with? text "---\n")
                    (str story-prompt-name " should start with frontmatter"))
                (is (str/includes? text "\n---\n")
                    (str story-prompt-name " should have complete frontmatter")))

              (testing (str story-prompt-name " has argument-hint in frontmatter")
                (let [frontmatter-end (str/index-of text "\n---\n")
                      frontmatter (subs text 0 frontmatter-end)]
                  (is (str/includes? frontmatter "argument-hint:")
                      (str story-prompt-name " should have argument-hint in frontmatter"))

                  (testing (str story-prompt-name " argument-hint uses square brackets")
                    (when (str/includes? frontmatter "argument-hint:")
                      (is (str/includes? frontmatter "[")
                          (str story-prompt-name " argument-hint should use square brackets for optional args")))))))))

        (testing "prompt content describes argument flexibility"
          (doseq [story-prompt-name story-prompt-names]
            (let [uri (str "prompt://" story-prompt-name)
                  read-response @(mcp-client/read-resource client uri)
                  text (-> read-response :contents first :text)]
              (testing (str story-prompt-name " documents parsing logic or argument flexibility")
                (is (or (str/includes? text "Parsing Logic")
                        (str/includes? text "Parse the arguments")
                        (str/includes? text "optional"))
                    (str story-prompt-name " should document argument parsing or flexibility"))))))

        (finally
          (mcp-client/close! client)
          ((:stop server)))))))
