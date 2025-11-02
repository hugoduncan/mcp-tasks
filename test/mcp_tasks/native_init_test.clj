(ns mcp-tasks.native-init-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [mcp-tasks.cli]
    [mcp-tasks.native-init :as sut]))

(deftest required-namespaces-loadable-test
  ;; Test that all tool namespaces explicitly required in native-init
  ;; are loadable and accessible. This verifies the static require
  ;; declarations work correctly for native image compilation.
  (testing "required namespaces"
    (testing "malli.core is loadable"
      (is (find-ns 'malli.core)))

    (testing "tool namespaces are loadable"
      (is (find-ns 'mcp-tasks.tool.select-tasks))
      (is (find-ns 'mcp-tasks.tool.add-task))
      (is (find-ns 'mcp-tasks.tool.complete-task))
      (is (find-ns 'mcp-tasks.tool.update-task))
      (is (find-ns 'mcp-tasks.tool.delete-task))
      (is (find-ns 'mcp-tasks.tool.reopen-task)))

    (testing "cli namespace is loadable"
      (is (find-ns 'mcp-tasks.cli)))))

(deftest main-delegation-test
  ;; Test that -main correctly delegates to mcp-tasks.cli/-main
  ;; with all arguments passed through.
  (testing "-main"
    (testing "delegates to mcp-tasks.cli/-main with no arguments"
      (let [called (atom false)
            received-args (atom nil)]
        (with-redefs [mcp-tasks.cli/-main
                      (fn [& args]
                        (reset! called true)
                        (reset! received-args args))]
          (sut/-main)
          (is @called)
          (is (empty? @received-args)))))

    (testing "delegates to mcp-tasks.cli/-main with single argument"
      (let [called (atom false)
            received-args (atom nil)]
        (with-redefs [mcp-tasks.cli/-main
                      (fn [& args]
                        (reset! called true)
                        (reset! received-args args))]
          (sut/-main "list")
          (is @called)
          (is (= ["list"] @received-args)))))

    (testing "delegates to mcp-tasks.cli/-main with multiple arguments"
      (let [called (atom false)
            received-args (atom nil)]
        (with-redefs [mcp-tasks.cli/-main
                      (fn [& args]
                        (reset! called true)
                        (reset! received-args args))]
          (sut/-main "show" "--task-id" "123")
          (is @called)
          (is (= ["show" "--task-id" "123"] @received-args)))))))
