(ns mcp-tasks.mcp-protocol-test
  "Integration tests for MCP protocol features.
  
  Tests that tools, prompts, and resources are properly advertised and
  accessible through the MCP protocol."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-clj.mcp-client.core :as mcp-client]
    [mcp-tasks.integration-test-fixtures :as fixtures]))

(use-fixtures :each fixtures/with-test-project)

(deftest ^:integ tools-available-test
  ;; Test that all expected tools are advertised and callable.
  ;; Tool behavior details are covered in tools_test.clj unit tests.
  (testing "tools availability"
    (fixtures/write-config-file "{:use-git? false}")

    (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
      (try
        (testing "lists expected tools"
          (let [tools-response @(mcp-client/list-tools client)
                tools (:tools tools-response)]
            (is (map? tools-response))
            (is (vector? tools))

            (let [tool-names (set (map :name tools))]
              (is (contains? tool-names "complete-task"))
              (is (contains? tool-names "select-tasks"))
              (is (contains? tool-names "add-task"))

              (doseq [tool tools]
                (is (contains? tool :name))
                (is (contains? tool :description))
                (is (string? (:name tool)))
                (is (string? (:description tool)))))))
        (finally
          (mcp-client/close! client)
          ((:stop server)))))))

(deftest ^:integ prompts-available-test
  ;; Test that prompts are advertised with correct capabilities.
  ;; Prompt content details are covered in prompts_test.clj unit tests.
  (testing "prompts availability"
    (fixtures/write-config-file "{:use-git? true}")
    (fixtures/init-test-git-repo)

    (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
      (try
        (testing "server advertises prompt capabilities"
          (is (mcp-client/available-prompts? client))
          (let [prompts-response @(mcp-client/list-prompts client)
                prompts (:prompts prompts-response)]
            (is (map? prompts-response))
            (is (vector? prompts))
            (is (pos? (count prompts)))))
        (finally
          (mcp-client/close! client)
          ((:stop server)))))))

(deftest ^:integ resources-available-test
  ;; Test that resources are advertised and can be read.
  ;; Resource content details are covered in resources unit tests.
  (testing "resources availability"
    (fixtures/write-config-file "{:use-git? true}")
    (fixtures/init-test-git-repo)

    (let [{:keys [server client]} (fixtures/create-test-server-and-client)]
      (try
        (testing "server advertises resource capabilities"
          (is (mcp-client/available-resources? client))
          (let [resources-response @(mcp-client/list-resources client)
                resources (:resources resources-response)]
            (is (map? resources-response))
            (is (vector? resources))
            (is (pos? (count resources)))

            (testing "all resources have required fields"
              (doseq [resource resources]
                (is (contains? resource :name))
                (is (contains? resource :uri))
                (is (contains? resource :mimeType))
                (is (string? (:name resource)))
                (is (string? (:uri resource)))
                (is (string? (:mimeType resource)))
                (is (or (str/starts-with? (:uri resource) "prompt://")
                        (str/starts-with? (:uri resource) "resource://")))))

            (testing "includes resources for all configured prompts"
              (let [resource-names (set (map :name resources))]
                ;; Check for some known prompts
                (is (contains? resource-names "next-simple"))
                (is (contains? resource-names "execute-story-child"))
                (is (contains? resource-names "execute-task"))))))

        (testing "can read resource content"
          (let [resources-response @(mcp-client/list-resources client)
                resources (:resources resources-response)
                ;; Find a prompt resource to test (not resource://)
                prompt-resource (first (filter #(str/starts-with? (:uri %) "prompt://") resources))
                uri (:uri prompt-resource)
                read-response @(mcp-client/read-resource client uri)
                contents (:contents read-response)]
            (is (not (:isError read-response)))
            (is (vector? contents))
            (is (pos? (count contents)))
            (let [content (first contents)]
              (is (= uri (:uri content)))
              (is (= "text/markdown" (:mimeType content)))
              (is (string? (:text content)))
              (is (pos? (count (:text content)))))))

        (testing "returns error for non-existent resource"
          (let [read-response @(mcp-client/read-resource client "prompt://nonexistent")]
            (is (:isError read-response))
            (is (re-find #"Resource not found"
                         (-> read-response :contents first :text)))))

        (finally
          (mcp-client/close! client)
          ((:stop server)))))))
