(ns build
  (:require
    [clojure.tools.build.api :as b]))

(def lib 'org.hugoduncan/mcp-tasks)
(def version-base "0.1")
(def target-dir "target")
(def class-dir (str target-dir "/classes"))

(defn version
  "Calculate version from git commit count"
  [_]
  (let [commit-count (b/git-count-revs nil)
        v (format "%s.%s" version-base commit-count)]
    (println "Version:" v)
    v))

(defn clean
  "Remove target directory"
  [_]
  (println "Cleaning target directory...")
  (b/delete {:path target-dir}))

(defn jar
  "Build JAR file with Main-Class manifest"
  [_]
  (let [v (version nil)
        basis (b/create-basis {:project "deps.edn"})
        jar-file (format "%s/mcp-tasks-%s.jar" target-dir v)]
    (println "Building JAR:" jar-file)
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version v
                  :basis basis
                  :src-dirs ["src"]})
    (b/copy-dir {:src-dirs ["src"]
                 :target-dir class-dir})
    (b/jar {:class-dir class-dir
            :jar-file jar-file
            :main 'mcp-tasks.main})
    (println "JAR built successfully:" jar-file)))

(defn deploy
  "Deploy JAR to Clojars using deps-deploy.
  
  Requires CLOJARS_USERNAME and CLOJARS_PASSWORD environment variables.
  CLOJARS_PASSWORD should contain your deploy token, not your actual password."
  [_]
  (let [v (version nil)
        jar-file (format "%s/mcp-tasks-%s.jar" target-dir v)
        pom-file (format "%s/classes/META-INF/maven/%s/%s/pom.xml"
                         target-dir
                         (namespace lib)
                         (name lib))]
    (println "Deploying to Clojars:" jar-file)
    (println "POM file:" pom-file)
    ;; deps-deploy will be called via clojure -X:deploy from CI
    ;; This function just validates the files exist
    (when-not (.exists (clojure.java.io/file jar-file))
      (throw (ex-info "JAR file not found. Run 'clj -T:build jar' first."
                      {:jar-file jar-file})))
    (when-not (.exists (clojure.java.io/file pom-file))
      (throw (ex-info "POM file not found. Run 'clj -T:build jar' first."
                      {:pom-file pom-file})))
    (println "Files validated for deployment")))
