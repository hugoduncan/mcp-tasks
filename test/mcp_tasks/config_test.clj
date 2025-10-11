(ns mcp-tasks.config-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-tasks.config :as sut]))

(def test-project-dir "test-resources/config-test")

(defn- cleanup-test-project
  []
  (let [dir (io/file test-project-dir)]
    (when (.exists dir)
      (doseq [file (reverse (file-seq dir))]
        (.delete file)))))

(defn- setup-test-project
  []
  (cleanup-test-project)
  (.mkdirs (io/file test-project-dir)))

(defn- write-config-file
  [content]
  (spit (io/file test-project-dir ".mcp-tasks.edn") content))

(defn- with-test-project
  [f]
  (setup-test-project)
  (try
    (f)
    (finally
      (cleanup-test-project))))

(use-fixtures :each with-test-project)

(deftest validate-config-with-valid-maps
  ;; Test that validate-config accepts valid config maps with various structures
  (testing "validate-config"
    (testing "accepts empty config"
      (is (= {} (sut/validate-config {}))))

    (testing "accepts config with use-git? true"
      (is (= {:use-git? true} (sut/validate-config {:use-git? true}))))

    (testing "accepts config with use-git? false"
      (is (= {:use-git? false} (sut/validate-config {:use-git? false}))))

    (testing "accepts config with unknown keys for forward compatibility"
      (is (= {:use-git? true :unknown-key "value"}
             (sut/validate-config {:use-git? true :unknown-key "value"}))))))

(deftest validate-config-rejects-invalid-structures
  ;; Test that validate-config throws clear errors for invalid structures
  (testing "validate-config"
    (testing "rejects non-map config"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Config must be a map"
            (sut/validate-config "not a map")))
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Config must be a map"
            (sut/validate-config []))))

    (testing "rejects non-boolean use-git? value"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Expected boolean for :use-git\?, got .*String"
            (sut/validate-config {:use-git? "true"})))
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Expected boolean for :use-git\?, got .*Long"
            (sut/validate-config {:use-git? 1}))))))

(deftest validate-config-error-data-structure
  ;; Test that validation errors include structured data for programmatic handling
  (testing "validate-config error data"
    (testing "includes type and config for non-map"
      (try
        (sut/validate-config "not a map")
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :invalid-config (:type (ex-data e))))
          (is (= "not a map" (:config (ex-data e)))))))

    (testing "includes detailed data for invalid type"
      (try
        (sut/validate-config {:use-git? "true"})
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :invalid-config-type (:type (ex-data e))))
          (is (= :use-git? (:key (ex-data e))))
          (is (= "true" (:value (ex-data e))))
          (is (= 'boolean? (:expected (ex-data e)))))))))

(deftest read-config-with-missing-file
  ;; Test that read-config returns nil when config file doesn't exist
  (testing "read-config"
    (testing "returns nil when file doesn't exist"
      (is (nil? (sut/read-config test-project-dir))))))

(deftest read-config-with-valid-files
  ;; Test that read-config successfully parses valid config files
  (testing "read-config"
    (testing "reads and validates empty config"
      (write-config-file "{}")
      (is (= {} (sut/read-config test-project-dir))))

    (testing "reads and validates config with use-git? true"
      (write-config-file "{:use-git? true}")
      (is (= {:use-git? true} (sut/read-config test-project-dir))))

    (testing "reads and validates config with use-git? false"
      (write-config-file "{:use-git? false}")
      (is (= {:use-git? false} (sut/read-config test-project-dir))))

    (testing "reads and validates config with extra keys"
      (write-config-file "{:use-git? true :other-key \"value\"}")
      (is (= {:use-git? true :other-key "value"}
             (sut/read-config test-project-dir))))))

(deftest read-config-with-malformed-edn
  ;; Test that read-config provides clear errors for malformed EDN
  (testing "read-config"
    (testing "throws clear error for malformed EDN"
      (write-config-file "{:use-git? true")
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Failed to parse \.mcp-tasks\.edn:"
            (sut/read-config test-project-dir))))

    (testing "includes error type and file path in ex-data"
      (write-config-file "{:key")
      (try
        (sut/read-config test-project-dir)
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :malformed-edn (:type (ex-data e))))
          (is (string? (:file (ex-data e))))
          (is (.endsWith (:file (ex-data e)) ".mcp-tasks.edn")))))))

(deftest read-config-with-invalid-schema
  ;; Test that read-config validates schema and provides clear errors
  (testing "read-config"
    (testing "throws clear error for invalid use-git? type"
      (write-config-file "{:use-git? \"true\"}")
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Expected boolean for :use-git\?"
            (sut/read-config test-project-dir))))

    (testing "includes validation error details in ex-data"
      (write-config-file "{:use-git? 123}")
      (try
        (sut/read-config test-project-dir)
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :invalid-config-type (:type (ex-data e))))
          (is (= :use-git? (:key (ex-data e))))
          (is (= 123 (:value (ex-data e)))))))))

(deftest git-repo-exists-detects-git-directory
  ;; Test that git-repo-exists? correctly detects presence of .mcp-tasks/.git
  (testing "git-repo-exists?"
    (testing "returns false when .mcp-tasks/.git doesn't exist"
      (is (false? (sut/git-repo-exists? test-project-dir))))

    (testing "returns true when .mcp-tasks/.git exists"
      (.mkdirs (io/file test-project-dir ".mcp-tasks" ".git"))
      (is (true? (sut/git-repo-exists? test-project-dir))))))

(deftest determine-git-mode-with-explicit-config
  ;; Test that explicit config values take precedence over auto-detection
  (testing "determine-git-mode with explicit config"
    (testing "returns true when config has :use-git? true"
      (is (true? (sut/determine-git-mode test-project-dir {:use-git? true}))))

    (testing "returns false when config has :use-git? false"
      (is (false? (sut/determine-git-mode test-project-dir {:use-git? false}))))

    (testing "explicit true overrides missing git repo"
      (is (false? (sut/git-repo-exists? test-project-dir)))
      (is (true? (sut/determine-git-mode test-project-dir {:use-git? true}))))

    (testing "explicit false overrides existing git repo"
      (.mkdirs (io/file test-project-dir ".mcp-tasks" ".git"))
      (is (true? (sut/git-repo-exists? test-project-dir)))
      (is (false? (sut/determine-git-mode test-project-dir {:use-git? false}))))))

(deftest determine-git-mode-with-auto-detection
  ;; Test that auto-detection works when no explicit config is provided
  (testing "determine-git-mode with auto-detection"
    (testing "returns false when no config and no git repo"
      (is (false? (sut/determine-git-mode test-project-dir {}))))

    (testing "returns true when no config but git repo exists"
      (.mkdirs (io/file test-project-dir ".mcp-tasks" ".git"))
      (is (true? (sut/determine-git-mode test-project-dir {}))))))

(deftest resolve-config-adds-use-git
  ;; Test that resolve-config adds :use-git? to config map
  (testing "resolve-config"
    (testing "adds :use-git? false when no config and no git repo"
      (is (= {:use-git? false}
             (sut/resolve-config test-project-dir {}))))

    (testing "adds :use-git? true when no config but git repo exists"
      (.mkdirs (io/file test-project-dir ".mcp-tasks" ".git"))
      (is (= {:use-git? true}
             (sut/resolve-config test-project-dir {}))))

    (testing "preserves explicit :use-git? true"
      (is (= {:use-git? true}
             (sut/resolve-config test-project-dir {:use-git? true}))))

    (testing "preserves explicit :use-git? false even with git repo"
      (.mkdirs (io/file test-project-dir ".mcp-tasks" ".git"))
      (is (= {:use-git? false}
             (sut/resolve-config test-project-dir {:use-git? false}))))

    (testing "preserves other config keys"
      (cleanup-test-project)
      (setup-test-project)
      (is (= {:use-git? false :other-key "value"}
             (sut/resolve-config test-project-dir {:other-key "value"}))))))

(deftest validate-git-repo-with-git-disabled
  ;; Test that validation passes when git mode is disabled
  (testing "validate-git-repo with git disabled"
    (testing "returns nil when :use-git? is false"
      (is (nil? (sut/validate-git-repo test-project-dir {:use-git? false}))))

    (testing "returns nil when :use-git? is not present"
      (is (nil? (sut/validate-git-repo test-project-dir {}))))))

(deftest validate-git-repo-with-git-enabled
  ;; Test that validation checks for git repository when git mode is enabled
  (testing "validate-git-repo with git enabled"
    (testing "returns nil when git repo exists"
      (.mkdirs (io/file test-project-dir ".mcp-tasks" ".git"))
      (is (nil? (sut/validate-git-repo test-project-dir {:use-git? true}))))

    (testing "throws clear error when git repo missing"
      (cleanup-test-project)
      (setup-test-project)
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Git mode enabled but \.mcp-tasks/\.git not found"
            (sut/validate-git-repo test-project-dir {:use-git? true}))))

    (testing "includes error details in ex-data"
      (cleanup-test-project)
      (setup-test-project)
      (try
        (sut/validate-git-repo test-project-dir {:use-git? true})
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :git-repo-missing (:type (ex-data e))))
          (is (= test-project-dir (:project-dir (ex-data e))))
          (is (string? (:git-dir (ex-data e)))))))))

(deftest validate-startup-passes-with-valid-config
  ;; Test that startup validation passes with valid configurations
  (testing "validate-startup"
    (testing "passes with git disabled and no git repo"
      (is (nil? (sut/validate-startup test-project-dir {:use-git? false}))))

    (testing "passes with git disabled and git repo present"
      (.mkdirs (io/file test-project-dir ".mcp-tasks" ".git"))
      (is (nil? (sut/validate-startup test-project-dir {:use-git? false}))))

    (testing "passes with git enabled and git repo present"
      (.mkdirs (io/file test-project-dir ".mcp-tasks" ".git"))
      (is (nil? (sut/validate-startup test-project-dir {:use-git? true}))))))

(deftest validate-startup-fails-with-invalid-config
  ;; Test that startup validation fails with invalid configurations
  (testing "validate-startup"
    (testing "fails when git enabled but repo missing"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Git mode enabled but \.mcp-tasks/\.git not found"
            (sut/validate-startup test-project-dir {:use-git? true}))))))
