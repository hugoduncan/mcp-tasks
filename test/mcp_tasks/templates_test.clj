(ns mcp-tasks.templates-test
  (:require
    [babashka.fs :as fs]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [mcp-tasks.templates :as templates]))

(deftest render-test
  ;; Test basic variable substitution functionality.
  ;; Contracts being tested:
  ;; - Variables are substituted with context values
  ;; - Missing variables are handled based on configuration
  ;; - Multiple variables can be substituted in one template
  ;; - Nested data access works correctly
  ;; - HTML entities are escaped by default (Selmer behavior)

  (testing "render"
    (testing "substitutes single variable"
      (is (= "Hello World!"
             (templates/render "Hello {{name}}!" {:name "World"}))))

    (testing "substitutes multiple variables"
      (is (= "Hello Alice, welcome to Home!"
             (templates/render "Hello {{user}}, welcome to {{place}}!"
                               {:user "Alice" :place "Home"}))))

    (testing "escapes HTML entities in values"
      (is (= "Hello &lt;script&gt;!"
             (templates/render "Hello {{name}}!" {:name "<script>"})))
      ;; Apostrophes are also escaped
      (is (= "Bob&#39;s place"
             (templates/render "{{place}}" {:place "Bob's place"}))))

    (testing "handles nested data access"
      (is (= "User: Alice"
             (templates/render "User: {{person.name}}"
                               {:person {:name "Alice"}})))
      (is (= "First item"
             (templates/render "{{items.0.title}}"
                               {:items [{:title "First item"}]}))))

    (testing "preserves text without variables"
      (is (= "No variables here"
             (templates/render "No variables here" {}))))

    (testing "handles empty context"
      (is (= "Hello {{name}}!"
             (templates/render "Hello {{name}}!" {}))))

    (testing "missing variable behavior"
      (testing "with :leave (default) leaves variable syntax"
        (is (= "Hello {{missing}}!"
               (templates/render "Hello {{missing}}!" {}))))

      (testing "with :empty replaces with empty string"
        (is (= "Hello !"
               (templates/render "Hello {{missing}}!" {}
                                 {:missing-value-formatter :empty}))))

      (testing "with :error throws exception"
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"Missing template variable"
              (templates/render "Hello {{missing}}!" {}
                                {:missing-value-formatter :error})))
        (let [ex (try
                   (templates/render "{{var}}" {}
                                     {:missing-value-formatter :error})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (= :missing-variable (:type (ex-data ex))))
          (is (= "var" (:variable (ex-data ex)))))))))

(deftest render-file-test
  ;; Test file-based template rendering.
  ;; Contracts being tested:
  ;; - Templates are loaded from file path
  ;; - Variable substitution works after loading

  (testing "render-file"
    (testing "loads and renders template from file"
      (let [temp-dir (fs/create-temp-dir {:prefix "templates-test"})
            template-file (fs/file temp-dir "test.txt")]
        (try
          (spit template-file "Hello {{name}}!")
          (is (= "Hello World!"
                 (templates/render-file (str template-file)
                                        {:name "World"})))
          (finally
            (fs/delete-tree temp-dir)))))

    (testing "throws on missing file"
      (is (thrown? java.io.FileNotFoundException
            (templates/render-file "/nonexistent/path.txt" {}))))))

(deftest render-resource-test
  ;; Test resource-based template rendering.
  ;; Contracts being tested:
  ;; - Templates are loaded from classpath resources
  ;; - Returns nil for missing resources

  (testing "render-resource"
    (testing "loads template from classpath"
      ;; Using known resource from project
      (let [result (templates/render-resource
                     "prompts/infrastructure/branch-management.md"
                     {})]
        (is (string? result))
        (is (str/includes? result "Branch Management"))))

    (testing "returns nil for missing resource"
      (is (nil? (templates/render-resource "nonexistent.md" {}))))))

(deftest create-resource-loader-test
  ;; Test resource loader creation and path resolution.
  ;; Contracts being tested:
  ;; - Override directory takes precedence
  ;; - Falls back to classpath resources
  ;; - Throws on missing resources

  (testing "create-resource-loader"
    (testing "loads from classpath when no override"
      (let [loader (templates/create-resource-loader nil "prompts")]
        (is (string? (loader "infrastructure/branch-management.md")))
        (is (str/includes?
              (loader "infrastructure/branch-management.md")
              "Branch Management"))))

    (testing "prefers override directory when file exists"
      (let [temp-dir (fs/create-temp-dir {:prefix "override-test"})
            override-file (fs/file temp-dir "test.md")]
        (try
          (spit override-file "Override content")
          (let [loader (templates/create-resource-loader
                         (str temp-dir) "prompts")]
            (is (= "Override content" (loader "test.md"))))
          (finally
            (fs/delete-tree temp-dir)))))

    (testing "falls back to classpath when override doesn't exist"
      (let [temp-dir (fs/create-temp-dir {:prefix "override-test"})]
        (try
          (let [loader (templates/create-resource-loader
                         (str temp-dir) "prompts")]
            (is (str/includes?
                  (loader "infrastructure/branch-management.md")
                  "Branch Management")))
          (finally
            (fs/delete-tree temp-dir)))))

    (testing "throws when not found in override or classpath"
      (let [loader (templates/create-resource-loader nil "prompts")]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"Template include not found"
              (loader "nonexistent.md")))))))

(deftest render-with-includes-test
  ;; Test include directive processing.
  ;; Contracts being tested:
  ;; - {% include "path" %} syntax is recognized
  ;; - Included content replaces the directive
  ;; - Variables are substituted after includes are resolved
  ;; - Nested includes are supported (with depth limit)
  ;; - Error handling for missing includes

  (testing "render-with-includes"
    (testing "processes single include"
      (let [template "Start\n{% include \"infrastructure/branch-management.md\" %}\nEnd"
            result (templates/render-with-includes template {}
                                                   {:resource-base "prompts"})]
        (is (str/starts-with? result "Start\n"))
        (is (str/ends-with? result "\nEnd"))
        (is (str/includes? result "Branch Management"))))

    (testing "combines includes with variable substitution"
      (let [temp-dir (fs/create-temp-dir {:prefix "include-test"})
            include-file (fs/file temp-dir "greeting.md")]
        (try
          (spit include-file "Hello {{name}}!")
          (let [template "{% include \"greeting.md\" %}"
                result (templates/render-with-includes
                         template
                         {:name "World"}
                         {:override-dir (str temp-dir)})]
            (is (= "Hello World!" result)))
          (finally
            (fs/delete-tree temp-dir)))))

    (testing "handles multiple includes"
      (let [temp-dir (fs/create-temp-dir {:prefix "multi-include"})
            file1 (fs/file temp-dir "one.md")
            file2 (fs/file temp-dir "two.md")]
        (try
          (spit file1 "ONE")
          (spit file2 "TWO")
          (let [template "{% include \"one.md\" %}-{% include \"two.md\" %}"
                result (templates/render-with-includes
                         template {}
                         {:override-dir (str temp-dir)})]
            (is (= "ONE-TWO" result)))
          (finally
            (fs/delete-tree temp-dir)))))

    (testing "handles nested includes"
      (let [temp-dir (fs/create-temp-dir {:prefix "nested-include"})
            outer (fs/file temp-dir "outer.md")
            inner (fs/file temp-dir "inner.md")]
        (try
          (spit inner "INNER")
          (spit outer "OUTER({% include \"inner.md\" %})")
          (let [template "{% include \"outer.md\" %}"
                result (templates/render-with-includes
                         template {}
                         {:override-dir (str temp-dir)})]
            (is (= "OUTER(INNER)" result)))
          (finally
            (fs/delete-tree temp-dir)))))

    (testing "detects circular includes"
      (let [temp-dir (fs/create-temp-dir {:prefix "circular-include"})
            file-a (fs/file temp-dir "a.md")
            file-b (fs/file temp-dir "b.md")]
        (try
          (spit file-a "{% include \"b.md\" %}")
          (spit file-b "{% include \"a.md\" %}")
          (is (thrown-with-msg?
                clojure.lang.ExceptionInfo
                #"Include depth exceeded"
                (templates/render-with-includes
                  "{% include \"a.md\" %}" {}
                  {:override-dir (str temp-dir)})))
          (finally
            (fs/delete-tree temp-dir)))))

    (testing "throws on missing include"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Failed to load include"
            (templates/render-with-includes
              "{% include \"nonexistent.md\" %}" {}
              {:resource-base "prompts"}))))

    (testing "handles whitespace in include syntax"
      (let [temp-dir (fs/create-temp-dir {:prefix "whitespace-test"})
            test-file (fs/file temp-dir "test.md")]
        (try
          (spit test-file "CONTENT")
          (is (= "CONTENT"
                 (templates/render-with-includes
                   "{%   include    \"test.md\"   %}" {}
                   {:override-dir (str temp-dir)})))
          (finally
            (fs/delete-tree temp-dir)))))))

(deftest story-parsing-infrastructure-test
  ;; Test the extracted story parsing logic infrastructure file.
  ;; Contracts being tested:
  ;; - story-parsing.md exists and is loadable from resources
  ;; - Content contains the expected parsing table structure
  ;; - File can be included in templates using {% include %} syntax
  ;; - Included content can be combined with prompt-specific content

  (testing "story-parsing infrastructure"
    (testing "when loading from resources"
      (let [loader (templates/create-resource-loader
                     nil "prompts/infrastructure")
            content (loader "story-parsing.md")]
        (testing "contains parsing instruction"
          (is (str/includes? content "Parse `$ARGUMENTS`:")))

        (testing "contains format table header"
          (is (str/includes? content "| Format | Example |")))

        (testing "contains numeric format examples"
          (is (str/includes? content "59, #59, story 59")))

        (testing "contains text format example"
          (is (str/includes? content "title-pattern:")))

        (testing "contains error handling instruction"
          (is (str/includes? content "Handle no match")))))

    (testing "when included in prompt template"
      (let [template (str "# My Prompt\n\n"
                          "{% include \"infrastructure/story-parsing.md\" %}\n\n"
                          "## Additional Steps\n\n"
                          "1. Do something specific")
            result (templates/render-with-includes
                     template {} {:resource-base "prompts"})]
        (testing "preserves surrounding content"
          (is (str/starts-with? result "# My Prompt"))
          (is (str/includes? result "## Additional Steps")))

        (testing "inlines the parsing logic"
          (is (str/includes? result "Parse `$ARGUMENTS`:")))

        (testing "includes the full table"
          (is (str/includes? result "| Numeric / #N")))))

    (testing "when combined with variables"
      (let [template (str "Process story: {{story-name}}\n\n"
                          "{% include \"infrastructure/story-parsing.md\" %}")
            result (templates/render-with-includes
                     template
                     {:story-name "Test Story"}
                     {:resource-base "prompts"})]
        (is (str/includes? result "Process story: Test Story"))
        (is (str/includes? result "Parse `$ARGUMENTS`:"))))))

(deftest template-error-test
  ;; Test error detection and formatting.
  ;; Contracts being tested:
  ;; - template-error? identifies template exceptions
  ;; - format-error provides human-readable messages

  (testing "template-error?"
    (testing "recognizes missing-variable error"
      (let [ex (ex-info "test" {:type :missing-variable :variable "x"})]
        (is (templates/template-error? ex))))

    (testing "recognizes missing-include error"
      (let [ex (ex-info "test" {:type :missing-include :path "x.md"})]
        (is (templates/template-error? ex))))

    (testing "recognizes include-error"
      (let [ex (ex-info "test" {:type :include-error :path "x.md"})]
        (is (templates/template-error? ex))))

    (testing "rejects non-template errors"
      (is (not (templates/template-error?
                 (ex-info "other" {:type :other}))))
      (is (not (templates/template-error?
                 (Exception. "regular exception"))))))

  (testing "format-error"
    (testing "formats missing-variable error"
      (let [ex (ex-info "test" {:type :missing-variable :variable "name"})]
        (is (= "Template error: Missing variable 'name'"
               (templates/format-error ex)))))

    (testing "formats missing-include error"
      (let [ex (ex-info "test" {:type :missing-include
                                :path "header.md"
                                :searched ["override/header.md"
                                           "prompts/header.md"]})]
        (is (str/includes?
              (templates/format-error ex)
              "Include file not found 'header.md'"))
        (is (str/includes?
              (templates/format-error ex)
              "override/header.md"))))

    (testing "formats include-error"
      (let [ex (ex-info "test" {:type :include-error :path "bad.md"})]
        (is (= "Template error: Failed to load include 'bad.md'"
               (templates/format-error ex)))))

    (testing "falls back to ex-message for non-template errors"
      (is (= "Some other error"
             (templates/format-error (Exception. "Some other error")))))))
