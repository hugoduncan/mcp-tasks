(ns mcp-tasks.tasks-file-test
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest testing is]]
    [mcp-tasks.tasks-file :as tasks-file]))

;; Test that read-ednl, append-task, prepend-task, replace-task, and
;; delete-task operations work correctly for EDNL format with proper
;; atomicity, validation, and edge case handling.

(def test-task-1
  {:id 1
   :parent-id nil
   :status :open
   :title "Task 1"
   :description "Test task"
   :design "Design 1"
   :category "simple"
   :type :task
   :meta {}
   :relations []})

(def test-task-2
  {:id 2
   :parent-id nil
   :status :open
   :title "Task 2"
   :description "Another task"
   :design "Design 2"
   :category "medium"
   :type :task
   :meta {}
   :relations []})

(def test-task-3
  {:id 3
   :parent-id 1
   :status :closed
   :title "Task 3"
   :description "Child task"
   :design "Design 3"
   :category "simple"
   :type :task
   :meta {"priority" "high"}
   :relations []})

(defn temp-file
  []
  (let [file (java.io.File/createTempFile "test-tasks-" ".ednl")]
    (.deleteOnExit file)
    (.getAbsolutePath file)))

(defn temp-file-in-subdir
  []
  (let [temp-dir (System/getProperty "java.io.tmpdir")
        subdir (io/file temp-dir "test-subdir" (str (random-uuid)))
        file (io/file subdir "tasks.ednl")]
    (.getAbsolutePath file)))

(deftest read-ednl-test
  (testing "read-ednl"
    (testing "returns empty vector for missing file"
      (is (= [] (tasks-file/read-ednl "/nonexistent/file.ednl"))))

    (testing "returns empty vector for empty file"
      (let [file (temp-file)]
        (spit file "")
        (is (= [] (tasks-file/read-ednl file)))))

    (testing "reads single valid task"
      (let [file (temp-file)]
        (spit file (pr-str test-task-1))
        (is (= [test-task-1] (tasks-file/read-ednl file)))))

    (testing "reads multiple valid tasks"
      (let [file (temp-file)
            content (str/join "\n" [(pr-str test-task-1)
                                    (pr-str test-task-2)
                                    (pr-str test-task-3)])]
        (spit file content)
        (is (= [test-task-1 test-task-2 test-task-3]
               (tasks-file/read-ednl file)))))

    (testing "skips malformed EDN line"
      (let [file (temp-file)
            content (str/join "\n" [(pr-str test-task-1)
                                    "{:invalid edn"
                                    (pr-str test-task-2)])]
        (spit file content)
        (is (= [test-task-1 test-task-2]
               (tasks-file/read-ednl file)))))

    (testing "skips invalid schema line"
      (let [file (temp-file)
            invalid-task {:id 999 :title "Missing required fields"}
            content (str/join "\n" [(pr-str test-task-1)
                                    (pr-str invalid-task)
                                    (pr-str test-task-2)])]
        (spit file content)
        (is (= [test-task-1 test-task-2]
               (tasks-file/read-ednl file)))))

    (testing "skips blank lines"
      (let [file (temp-file)
            content (str/join "\n" [(pr-str test-task-1)
                                    ""
                                    (pr-str test-task-2)
                                    "   "
                                    (pr-str test-task-3)])]
        (spit file content)
        (is (= [test-task-1 test-task-2 test-task-3]
               (tasks-file/read-ednl file)))))))

(deftest append-task-test
  (testing "append-task"
    (testing "appends task to empty file"
      (let [file (temp-file)]
        (tasks-file/append-task file test-task-1)
        (is (= [test-task-1] (tasks-file/read-ednl file)))))

    (testing "appends task to existing tasks"
      (let [file (temp-file)]
        (tasks-file/append-task file test-task-1)
        (tasks-file/append-task file test-task-2)
        (is (= [test-task-1 test-task-2] (tasks-file/read-ednl file)))))

    (testing "creates parent directories"
      (let [file (temp-file-in-subdir)]
        (tasks-file/append-task file test-task-1)
        (is (= [test-task-1] (tasks-file/read-ednl file)))
        ;; Cleanup
        (io/delete-file file true)
        (io/delete-file (.getParentFile (io/file file)) true)))

    (testing "throws on invalid task schema"
      (let [file (temp-file)
            invalid-task {:id 999 :title "Missing fields"}]
        (is (thrown? Exception
              (tasks-file/append-task file invalid-task)))))))

(deftest prepend-task-test
  (testing "prepend-task"
    (testing "prepends task to empty file"
      (let [file (temp-file)]
        (tasks-file/prepend-task file test-task-1)
        (is (= [test-task-1] (tasks-file/read-ednl file)))))

    (testing "prepends task to existing tasks"
      (let [file (temp-file)]
        (tasks-file/append-task file test-task-1)
        (tasks-file/prepend-task file test-task-2)
        (is (= [test-task-2 test-task-1] (tasks-file/read-ednl file)))))

    (testing "creates parent directories"
      (let [file (temp-file-in-subdir)]
        (tasks-file/prepend-task file test-task-1)
        (is (= [test-task-1] (tasks-file/read-ednl file)))
        ;; Cleanup
        (io/delete-file file true)
        (io/delete-file (.getParentFile (io/file file)) true)))

    (testing "throws on invalid task schema"
      (let [file (temp-file)
            invalid-task {:id 999}]
        (is (thrown? Exception
              (tasks-file/prepend-task file invalid-task)))))))

(deftest replace-task-test
  (testing "replace-task"
    (testing "replaces existing task by id"
      (let [file (temp-file)
            updated-task (assoc test-task-1 :title "Updated Title")]
        (tasks-file/append-task file test-task-1)
        (tasks-file/append-task file test-task-2)
        (tasks-file/replace-task file updated-task)
        (is (= [updated-task test-task-2] (tasks-file/read-ednl file)))))

    (testing "replaces task in middle of list"
      (let [file (temp-file)
            updated-task (assoc test-task-2 :status :closed)]
        (tasks-file/append-task file test-task-1)
        (tasks-file/append-task file test-task-2)
        (tasks-file/append-task file test-task-3)
        (tasks-file/replace-task file updated-task)
        (is (= [test-task-1 updated-task test-task-3]
               (tasks-file/read-ednl file)))))

    (testing "throws on task not found"
      (let [file (temp-file)
            missing-task (assoc test-task-1 :id 999)]
        (tasks-file/append-task file test-task-1)
        (is (thrown-with-msg? Exception #"Task not found"
              (tasks-file/replace-task file missing-task)))))

    (testing "throws on invalid task schema"
      (let [file (temp-file)
            invalid-task {:id 1 :title "Missing fields"}]
        (tasks-file/append-task file test-task-1)
        (is (thrown? Exception
              (tasks-file/replace-task file invalid-task)))))))

(deftest delete-task-test
  (testing "delete-task"
    (testing "deletes task by id"
      (let [file (temp-file)]
        (tasks-file/append-task file test-task-1)
        (tasks-file/append-task file test-task-2)
        (tasks-file/delete-task file 1)
        (is (= [test-task-2] (tasks-file/read-ednl file)))))

    (testing "deletes task from middle of list"
      (let [file (temp-file)]
        (tasks-file/append-task file test-task-1)
        (tasks-file/append-task file test-task-2)
        (tasks-file/append-task file test-task-3)
        (tasks-file/delete-task file 2)
        (is (= [test-task-1 test-task-3] (tasks-file/read-ednl file)))))

    (testing "deletes last task"
      (let [file (temp-file)]
        (tasks-file/append-task file test-task-1)
        (tasks-file/delete-task file 1)
        (is (= [] (tasks-file/read-ednl file)))))

    (testing "throws on task not found"
      (let [file (temp-file)]
        (tasks-file/append-task file test-task-1)
        (is (thrown-with-msg? Exception #"Task not found"
              (tasks-file/delete-task file 999)))))))

(deftest atomic-write-test
  (testing "atomic write operations"
    (testing "write creates no intermediate state"
      (let [file (temp-file)]
        (tasks-file/append-task file test-task-1)
        ;; If write was not atomic, temp file would still exist
        (is (not (.exists (io/file (str file ".tmp")))))))))
