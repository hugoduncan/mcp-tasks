(ns build
  (:require
    [babashka.fs :as fs]
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
    (when-not (fs/exists? jar-file)
      (throw (ex-info "JAR file not found. Run 'clj -T:build jar' first."
                      {:jar-file jar-file})))
    (when-not (fs/exists? pom-file)
      (throw (ex-info "POM file not found. Run 'clj -T:build jar' first."
                      {:pom-file pom-file})))
    (println "Files validated for deployment")))

;; Native Image Build

(defn- shell
  "Execute a shell command and return the result.
  Throws an exception if the command fails."
  [& args]
  (let [result (apply b/process args)]
    (when-not (zero? (:exit result))
      (throw (ex-info (format "Command failed with exit code %d: %s"
                              (:exit result)
                              (pr-str args))
                      {:exit (:exit result)
                       :out (:out result)
                       :err (:err result)})))
    result))

(defn- detect-platform
  "Detect the current platform and architecture.
  Returns a map with :os and :arch keys."
  []
  (let [os-name (System/getProperty "os.name")
        os-arch (System/getProperty "os.arch")
        os (cond
             (re-find #"(?i)linux" os-name) :linux
             (re-find #"(?i)mac|darwin" os-name) :macos
             (re-find #"(?i)windows" os-name) :windows
             :else (throw (ex-info (format "Unsupported OS: %s" os-name)
                                   {:os-name os-name})))
        arch (cond
               (re-find #"(?i)amd64|x86_64" os-arch) :amd64
               (re-find #"(?i)aarch64|arm64" os-arch) :arm64
               :else (throw (ex-info (format "Unsupported architecture: %s" os-arch)
                                     {:os-arch os-arch})))]
    {:os os :arch arch}))

(defn- platform-binary-name
  "Generate platform-specific binary name.
  Examples: mcp-tasks-linux-amd64, mcp-tasks-macos-arm64"
  [{:keys [os arch]}]
  (format "mcp-tasks-%s-%s" (name os) (name arch)))

(defn jar-cli
  "Build uberjar for CLI with mcp-tasks.native-init as Main-Class"
  [_]
  (let [v (version nil)
        basis (b/create-basis {:project "deps.edn"})
        jar-file (format "%s/mcp-tasks-cli-%s.jar" target-dir v)]
    (println "Building CLI uberjar:" jar-file)
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version v
                  :basis basis
                  :src-dirs ["src"]})
    (b/copy-dir {:src-dirs ["src" "resources"]
                 :target-dir class-dir})
    (b/compile-clj {:basis basis
                    :src-dirs ["src"]
                    :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file jar-file
             :basis basis
             :main 'mcp-tasks.native-init})
    (println "CLI uberjar built successfully:" jar-file)))

(defn native-cli
  "Build native CLI binary using GraalVM native-image.

  Requires GraalVM with native-image installed and GRAALVM_HOME environment variable set.
  Output: target/mcp-tasks-<platform>-<arch> (e.g., target/mcp-tasks-macos-arm64)

  The native binary provides a standalone CLI without requiring JVM or Babashka."
  [_]
  (let [graalvm-home (System/getenv "GRAALVM_HOME")]
    (when-not graalvm-home
      (throw (ex-info "GRAALVM_HOME environment variable not set. Please set it to your GraalVM installation directory."
                      {:required "GRAALVM_HOME"})))

    (let [platform (detect-platform)
          v (version nil)
          jar-file (format "%s/mcp-tasks-cli-%s.jar" target-dir v)
          binary-name (platform-binary-name platform)
          output-binary (str target-dir "/" binary-name)
          native-image-bin (str graalvm-home "/bin/native-image")]

      (when-not (fs/exists? native-image-bin)
        (throw (ex-info (format "native-image not found at %s. Please install native-image or verify GRAALVM_HOME is correct." native-image-bin)
                        {:graalvm-home graalvm-home
                         :native-image-bin native-image-bin})))

      (println (format "Building native CLI binary for %s %s..."
                       (name (:os platform))
                       (name (:arch platform))))

      (when-not (fs/exists? jar-file)
        (throw (ex-info "CLI JAR file not found. Run 'clj -T:build jar-cli' first."
                        {:jar-file jar-file})))

      (println "Running native-image (this may take several minutes)...")
      (shell {:command-args [native-image-bin
                             "-jar" jar-file
                             "--no-fallback"
                             "-H:+ReportExceptionStackTraces"
                             "--initialize-at-build-time"
                             "--report-unsupported-elements-at-runtime"
                             "-o" output-binary]})

      (println (format "✓ Native CLI binary built: %s" output-binary))
      (println (format "  Platform: %s %s" (name (:os platform)) (name (:arch platform))))
      (println (format "  Size: %.1f MB" (/ (fs/size output-binary) 1024.0 1024.0))))))
