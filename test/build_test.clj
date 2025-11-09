(ns build-test
  (:require
    [babashka.fs :as fs]
    [build :as sut]
    [clojure.test :refer [deftest is testing use-fixtures]]))

;; Test fixtures

(def ^:private test-dir "target/build-test")

(defn- clean-test-dir-fixture
  [f]
  (fs/delete-tree test-dir)
  (fs/create-dirs test-dir)
  (f)
  (fs/delete-tree test-dir))

(use-fixtures :each clean-test-dir-fixture)

(defn- create-mock-jar-and-pom
  "Create mock JAR and POM files for testing."
  []
  (let [v (sut/version nil)
        jar-file (format "%s/mcp-tasks-%s.jar" sut/target-dir v)
        pom-file (format "%s/classes/META-INF/maven/%s/%s/pom.xml"
                         sut/target-dir
                         (namespace sut/lib)
                         (name sut/lib))]
    (fs/create-dirs (fs/parent jar-file))
    (fs/create-dirs (fs/parent pom-file))
    (spit jar-file "mock jar content")
    (spit pom-file "mock pom content")
    {:jar-file jar-file :pom-file pom-file}))

(deftest validate-deployment-files-missing-jar-test
  ;; Tests that validate-deployment-files throws clear error when JAR is missing.
  ;; Verifies error message guides user to run build command.
  (testing "validate-deployment-files"
    (testing "throws error when JAR file is missing"
      (let [jar-file "target/nonexistent.jar"
            pom-file "target/classes/META-INF/maven/org.hugoduncan/mcp-tasks/pom.xml"]
        (try
          (#'sut/validate-deployment-files jar-file pom-file)
          (is false "Expected exception to be thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= "JAR file not found. Run 'clj -T:build jar' first."
                   (.getMessage e)))
            (is (= jar-file (:jar-file (ex-data e))))))))))

(deftest validate-deployment-files-missing-pom-test
  ;; Tests that validate-deployment-files throws clear error when POM is missing.
  ;; Verifies error message guides user to run build command.
  (testing "validate-deployment-files"
    (testing "throws error when POM file is missing"
      (let [v (sut/version nil)
            jar-file (format "%s/mcp-tasks-%s.jar" sut/target-dir v)
            pom-file (format "%s/classes/META-INF/maven/%s/%s/pom.xml"
                             sut/target-dir
                             (namespace sut/lib)
                             (name sut/lib))]
        ;; Create JAR but not POM
        (fs/create-dirs (fs/parent jar-file))
        (spit jar-file "mock jar content")
        (try
          (#'sut/validate-deployment-files jar-file pom-file)
          (is false "Expected exception to be thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= "POM file not found. Run 'clj -T:build jar' first."
                   (.getMessage e)))
            (is (= pom-file (:pom-file (ex-data e))))))))))

(deftest validate-deployment-files-success-test
  ;; Tests that validate-deployment-files succeeds when both files exist.
  ;; Verifies no exception is thrown for valid deployment artifacts.
  (testing "validate-deployment-files"
    (testing "succeeds when both JAR and POM exist"
      (let [{:keys [jar-file pom-file]} (create-mock-jar-and-pom)]
        ;; Should not throw
        (#'sut/validate-deployment-files jar-file pom-file)
        (is true "Validation succeeded")))))

(deftest deploy-dry-run-validates-files-test
  ;; Tests that deploy with dry-run validates files exist.
  ;; Verifies dry-run mode performs validation without deployment.
  (testing "deploy"
    (testing "in dry-run mode validates files"
      (testing "throws error when files are missing"
        ;; Clean target directory to ensure files don't exist
        (fs/delete-tree sut/target-dir)
        (try
          (sut/deploy {:version "0.1.999" :dry-run true})
          (is false "Expected exception for missing JAR")
          (catch clojure.lang.ExceptionInfo e
            (is (re-find #"JAR file not found" (.getMessage e)))))))))

(deftest deploy-dry-run-success-test
  ;; Tests that deploy with dry-run succeeds when files and credentials are valid.
  ;; Verifies dry-run mode completes without calling deps-deploy.
  ;;
  ;; NOTE: This test requires CLOJARS_USERNAME and CLOJARS_PASSWORD
  ;; environment variables to be set. Run with:
  ;; CLOJARS_USERNAME=test CLOJARS_PASSWORD=test clojure -M:dev:test --focus build-test/deploy-dry-run-success-test
  (testing "deploy"
    (testing "in dry-run mode succeeds with valid files and credentials"
      (if (and (System/getenv "CLOJARS_USERNAME")
               (System/getenv "CLOJARS_PASSWORD"))
        (let [v (sut/version nil)]
          (create-mock-jar-and-pom)
          ;; Should not throw, should print dry-run message
          (sut/deploy {:version v :dry-run true})
          (is true "Dry-run deployment completed"))
        (is true "Skipping test: CLOJARS_USERNAME and CLOJARS_PASSWORD env vars not set")))))
