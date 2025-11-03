(ns build
  (:require
    [babashka.fs :as fs]
    [clojure.string]
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

(defn detect-platform
  "Detect the current platform and architecture.
  Returns a map with :os and :arch keys."
  []
  (let [os-name (System/getProperty "os.name")
        os-arch (System/getProperty "os.arch")
        os (cond
             (re-find #"(?i)linux" os-name) :linux
             (re-find #"(?i)mac|darwin" os-name) :macos
             (re-find #"(?i)windows" os-name) :windows
             :else (throw (ex-info (format "Unsupported OS: %s. Supported platforms: Linux, macOS, Windows" os-name)
                                   {:os-name os-name
                                    :supported-platforms [:linux :macos :windows]})))
        arch (cond
               (re-find #"(?i)amd64|x86_64" os-arch) :amd64
               (re-find #"(?i)aarch64|arm64" os-arch) :arm64
               :else (throw (ex-info (format "Unsupported architecture: %s. Supported architectures: amd64, arm64" os-arch)
                                     {:os-arch os-arch
                                      :supported-architectures [:amd64 :arm64]})))]
    {:os os :arch arch}))

(defn platform-binary-name
  "Generate platform-specific binary name.
  Examples: mcp-tasks-linux-amd64, mcp-tasks-macos-arm64, mcp-tasks-windows-amd64.exe"
  ([platform] (platform-binary-name "mcp-tasks" platform))
  ([basename {:keys [os arch]}]
   (let [base-name (format "%s-%s-%s" basename (name os) (name arch))]
     (if (= os :windows)
       (str base-name ".exe")
       base-name))))

(defn- build-uberjar
  "Build an uberjar with the specified basename and main namespace.

  Parameters:
  - basename: The prefix for the JAR filename (e.g., 'mcp-tasks-cli')
  - main-ns: The main namespace symbol (e.g., 'mcp-tasks.native-init)"
  [basename main-ns]
  (let [v (version nil)
        basis (b/create-basis {:project "deps.edn"})
        jar-file (format "%s/%s-%s.jar" target-dir basename v)]
    (println (format "Building %s uberjar: %s" basename jar-file))
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
             :main main-ns})
    (println (format "%s uberjar built successfully: %s" basename jar-file))))

(defn jar-cli
  "Build uberjar for CLI with mcp-tasks.native-init as Main-Class"
  [_]
  (build-uberjar "mcp-tasks-cli" 'mcp-tasks.native-init))

(defn jar-server
  "Build uberjar for server with mcp-tasks.native-server-init as Main-Class"
  [_]
  (build-uberjar "mcp-tasks-server" 'mcp-tasks.native-server-init))

(defn- build-native-binary
  "Build a native binary using GraalVM native-image.

  Common logic for building native CLI and server binaries.

  Parameters:
  - jar-basename: The JAR file prefix (e.g., 'mcp-tasks-cli', 'mcp-tasks-server')
  - binary-basename: The binary file prefix (e.g., 'mcp-tasks', 'mcp-tasks-server')
  - binary-type: Human-readable type for messages (e.g., 'CLI', 'server')"
  [jar-basename binary-basename binary-type]
  (let [graalvm-home (System/getenv "GRAALVM_HOME")]
    (when-not graalvm-home
      (throw (ex-info "GRAALVM_HOME environment variable not set. Please set it to your GraalVM installation directory."
                      {:required "GRAALVM_HOME"})))

    (let [platform (detect-platform)
          v (version nil)
          jar-file (format "%s/%s-%s.jar" target-dir jar-basename v)
          binary-name (platform-binary-name binary-basename platform)
          output-binary (str target-dir "/" binary-name)
          ;; For -o flag: on Windows, don't include .exe extension as native-image adds it automatically
          ;; On other platforms, use the full binary name
          output-name-for-native-image (if (= (:os platform) :windows)
                                         (clojure.string/replace output-binary #"\.exe$" "")
                                         output-binary)
          ;; Construct full path to native-image
          ;; Oracle GraalVM includes native-image by default in bin directory
          native-image-bin (if (= (:os platform) :windows)
                             (str graalvm-home "\\bin\\native-image.cmd")
                             (str graalvm-home "/bin/native-image"))]

      (println (format "Building native %s binary for %s %s..."
                       binary-type
                       (name (:os platform))
                       (name (:arch platform))))

      (when-not (fs/exists? jar-file)
        (throw (ex-info (format "%s JAR file not found. Run 'clj -T:build jar-%s' first."
                                binary-type
                                (clojure.string/lower-case binary-type))
                        {:jar-file jar-file})))

      (println "Running native-image (this may take several minutes)...")
      (shell {:command-args [native-image-bin
                             "-jar" jar-file
                             "--no-fallback"
                             "-H:+ReportExceptionStackTraces"
                             "-o" output-name-for-native-image]})

      (println (format "✓ Native %s binary built: %s" binary-type output-binary))
      (println (format "  Platform: %s %s" (name (:os platform)) (name (:arch platform))))
      (println (format "  Size: %.1f MB" (/ (fs/size output-binary) 1024.0 1024.0))))))

(defn native-cli
  "Build native CLI binary using GraalVM native-image.

  Requires GraalVM with native-image installed and GRAALVM_HOME environment variable set.
  Output: target/mcp-tasks-<platform>-<arch> (e.g., target/mcp-tasks-macos-arm64)

  The native binary provides a standalone CLI without requiring JVM or Babashka."
  [_]
  (build-native-binary "mcp-tasks-cli" "mcp-tasks" "CLI"))

(defn native-server
  "Build native server binary using GraalVM native-image.

  Requires GraalVM with native-image installed and GRAALVM_HOME environment variable set.
  Output: target/mcp-tasks-server-<platform>-<arch> (e.g., target/mcp-tasks-server-macos-arm64)

  The native binary provides a standalone MCP server without requiring JVM or Babashka."
  [_]
  (build-native-binary "mcp-tasks-server" "mcp-tasks-server" "server"))
