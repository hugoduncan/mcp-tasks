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
