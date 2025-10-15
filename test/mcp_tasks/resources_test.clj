(ns mcp-tasks.resources-test
  "Unit tests for resource definitions"
  (:require
    [babashka.fs :as fs]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-tasks.config :as config]
    [mcp-tasks.prompts :as tp]
    [mcp-tasks.resources :as resources]))

(def test-project-dir
  (.getAbsolutePath (io/file "test-resources/resources-test")))

(defn- cleanup-test-project
  []
  (let [dir (io/file test-project-dir)]
    (when (fs/exists? dir)
      (doseq [file (reverse (file-seq dir))]
        (fs/delete file)))))

(defn- setup-test-project
  []
  (cleanup-test-project)
  (let [mcp-tasks-dir (io/file test-project-dir ".mcp-tasks")
        tasks-dir (io/file mcp-tasks-dir "tasks")]
    (.mkdirs tasks-dir)
    ;; Create a simple.md file so discover-categories finds at least one category
    (spit (io/file tasks-dir "simple.md") "- [ ] test task\n")))

(defn- with-test-project
  [f]
  (setup-test-project)
  (try
    (f)
    (finally
      (cleanup-test-project))))

(use-fixtures :each with-test-project)

(deftest format-argument-hint-test
  ;; Test that format-argument-hint correctly formats various argument combinations.
  (testing "format-argument-hint"
    (testing "returns nil for nil arguments"
      (is (nil? (#'resources/format-argument-hint nil))))

    (testing "returns nil for empty arguments vector"
      (is (nil? (#'resources/format-argument-hint []))))

    (testing "formats single required argument"
      (is (= "<story-name>"
             (#'resources/format-argument-hint
              [{:name "story-name" :required true}]))))

    (testing "formats single optional argument"
      (is (= "[context]"
             (#'resources/format-argument-hint
              [{:name "context" :required false}]))))

    (testing "formats multiple required arguments"
      (is (= "<category> <task-text>"
             (#'resources/format-argument-hint
              [{:name "category" :required true}
               {:name "task-text" :required true}]))))

    (testing "formats multiple optional arguments"
      (is (= "[option1] [option2]"
             (#'resources/format-argument-hint
              [{:name "option1" :required false}
               {:name "option2" :required false}]))))

    (testing "formats mixed required and optional arguments"
      (is (= "<story-name> [additional-context]"
             (#'resources/format-argument-hint
              [{:name "story-name" :required true}
               {:name "additional-context" :required false}]))))

    (testing "formats complex argument combinations"
      (is (= "<category> <task-text> [prepend] [story-name]"
             (#'resources/format-argument-hint
              [{:name "category" :required true}
               {:name "task-text" :required true}
               {:name "prepend" :required false}
               {:name "story-name" :required false}]))))

    (testing "handles argument without explicit required field (treats as optional)"
      (is (= "[implicit-optional]"
             (#'resources/format-argument-hint
              [{:name "implicit-optional"}]))))))

(deftest prompt-resources-test
  ;; Test that prompt-resources creates resources for all configured prompts.
  (testing "prompt-resources"
    (let [config (config/resolve-config test-project-dir {})
          all-prompts (merge (tp/prompts config) (tp/story-prompts config))
          resources-map (resources/prompt-resources all-prompts)]

      (testing "returns a map of resources"
        (is (map? resources-map))
        (is (pos? (count resources-map))))

      (testing "includes resources for category prompts"
        (is (contains? resources-map "prompt://next-simple"))
        (let [resource (get resources-map "prompt://next-simple")]
          (is (= "next-simple" (:name resource)))
          (is (= "prompt://next-simple" (:uri resource)))
          (is (= "text/markdown" (:mime-type resource)))
          (is (string? (:description resource)))
          (is (fn? (:implementation resource)))))

      (testing "includes resources for story prompts"
        (is (contains? resources-map "prompt://execute-story-task"))
        (let [resource (get resources-map "prompt://execute-story-task")]
          (is (= "execute-story-task" (:name resource)))
          (is (= "prompt://execute-story-task" (:uri resource)))
          (is (= "text/markdown" (:mime-type resource)))
          (is (string? (:description resource)))
          (is (fn? (:implementation resource)))))

      (testing "all resources have required fields"
        (doseq [[uri resource] resources-map]
          (is (string? uri))
          (is (str/starts-with? uri "prompt://"))
          (is (string? (:name resource)))
          (is (string? (:uri resource)))
          (is (= uri (:uri resource)))
          (is (= "text/markdown" (:mime-type resource)))
          (is (fn? (:implementation resource)))))

      (testing "resource count matches prompt count"
        (is (= (count all-prompts) (count resources-map)))))))

(deftest prompt-resource-implementation-test
  ;; Test that resource implementations return correct content.
  (testing "prompt resource implementation"
    (let [config (config/resolve-config test-project-dir {})
          all-prompts (merge (tp/prompts config) (tp/story-prompts config))
          resources-map (resources/prompt-resources all-prompts)]

      (testing "returns prompt content for valid URI"
        (let [resource (get resources-map "prompt://next-simple")
              implementation (:implementation resource)
              result (implementation nil "prompt://next-simple")
              contents (:contents result)]
          (is (not (:isError result)))
          (is (vector? contents))
          (is (= 1 (count contents)))
          (let [content (first contents)
                text (:text content)]
            (is (= "prompt://next-simple" (:uri content)))
            (is (= "text/markdown" (:mimeType content)))
            (is (string? text))
            (is (pos? (count text)))
            (is (str/includes? text "complete the next simple task")))))

      (testing "content includes YAML frontmatter with description"
        (let [resource (get resources-map "prompt://next-simple")
              implementation (:implementation resource)
              result (implementation nil "prompt://next-simple")
              text (-> result :contents first :text)]
          (is (str/starts-with? text "---\n"))
          (is (str/includes? text "description:"))
          (is (str/includes? text "\n---\n"))))

      (testing "content includes description metadata"
        (let [resource (get resources-map "prompt://next-simple")
              implementation (:implementation resource)
              result (implementation nil "prompt://next-simple")
              text (-> result :contents first :text)
              ;; Find the description line in frontmatter
              lines (str/split-lines text)
              frontmatter-lines (take-while #(not= "---" %) (rest lines))
              desc-line (first (filter #(str/starts-with? % "description:") frontmatter-lines))]
          (is (some? desc-line))
          (is (str/includes? desc-line "simple"))))

      (testing "returns error for non-existent prompt"
        (let [resource (get resources-map "prompt://next-simple")
              implementation (:implementation resource)
              result (implementation nil "prompt://nonexistent")]
          (is (:isError result))
          (is (= "Prompt not found: nonexistent"
                 (-> result :contents first :text)))))

      (testing "story prompt resource contains expected content"
        (let [resource (get resources-map "prompt://execute-story-task")
              implementation (:implementation resource)
              result (implementation nil "prompt://execute-story-task")
              contents (:contents result)]
          (is (not (:isError result)))
          (let [content (first contents)
                text (:text content)]
            (is (= "prompt://execute-story-task" (:uri content)))
            (is (= "text/markdown" (:mimeType content)))
            (is (string? text))
            (is (str/includes? text "Execute the next incomplete task")))))

      (testing "story prompt with arguments includes argument-hint in frontmatter"
        (let [resource (get resources-map "prompt://execute-story-task")
              implementation (:implementation resource)
              result (implementation nil "prompt://execute-story-task")
              text (-> result :contents first :text)
              lines (str/split-lines text)
              frontmatter-lines (take-while #(not= "---" %) (rest lines))
              arg-hint-line (first (filter #(str/starts-with? % "argument-hint:") frontmatter-lines))]
          (is (some? arg-hint-line))
          (is (str/includes? arg-hint-line "<story-name>")))))))
