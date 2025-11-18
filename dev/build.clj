(ns build
  (:require
    [babashka.fs :as fs]
    [clojure.java.io :as io]
    [clojure.string]
    [clojure.tools.build.api :as b]
    [deps-deploy.deps-deploy :as dd]))

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

(defn generate-prompt-manifest
  "Generate manifest file listing all workflow prompts.

  Scans resources/prompts/ directory and creates resources/prompts-manifest.edn
  containing a vector of workflow prompt names (without .md extension).

  This enables prompt discovery in GraalVM native images where directory
  listing via io/resource is not supported."
  []
  (let [prompts-dir (io/file "resources/prompts")
        workflow-files (->> (file-seq prompts-dir)
                            (filter #(and (.isFile %)
                                          (clojure.string/ends-with? (.getName %) ".md")
                                          ;; Only root-level files, not infrastructure/
                                          (= prompts-dir (.getParentFile %))))
                            (map #(clojure.string/replace (.getName %) #"\.md$" ""))
                            sort
                            vec)
        manifest-file (io/file "resources/prompts-manifest.edn")]
    (spit manifest-file (pr-str workflow-files))
    (println (format "Generated manifest with %d workflow prompts" (count workflow-files)))
    (count workflow-files)))

(defn jar
  "Build JAR file with Main-Class manifest

  Options:
  - :version - Version string to use (default: calculated from git)"
  [{version-str :version}]
  (let [v (or version-str (version nil))
        basis (b/create-basis {:project "deps.edn"})
        jar-file (format "%s/mcp-tasks-%s.jar" target-dir v)]
    (println "Version:" v)
    (println "Building JAR:" jar-file)
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version v
                  :basis basis
                  :src-dirs ["src"]
                  :scm {:url "https://github.com/hugoduncan/mcp-tasks"
                        :connection "scm:git:git://github.com/hugoduncan/mcp-tasks.git"
                        :developerConnection "scm:git:ssh://git@github.com/hugoduncan/mcp-tasks.git"
                        :tag (str "v" v)}
                  :pom-data [[:description "Task-based workflow management for AI agents via Model Context Protocol (MCP)"]
                             [:url "https://github.com/hugoduncan/mcp-tasks"]
                             [:licenses
                              [:license
                               [:name "Eclipse Public License 2.0"]
                               [:url "https://www.eclipse.org/legal/epl-2.0/"]
                               [:distribution "repo"]]]
                             [:developers
                              [:developer
                               [:name "Hugo Duncan"]]]]})
    (b/copy-dir {:src-dirs ["src"]
                 :target-dir class-dir})
    (b/jar {:class-dir class-dir
            :jar-file jar-file
            :main 'mcp-tasks.main})
    (println "JAR built successfully:" jar-file)))

(defn- validate-deployment-files
  "Validate that JAR and POM files exist for deployment.

  Throws ex-info with helpful error message if files are missing."
  [jar-file pom-file]
  (when-not (fs/exists? jar-file)
    (throw (ex-info "JAR file not found. Run 'clj -T:build jar' first."
                    {:jar-file jar-file})))
  (when-not (fs/exists? pom-file)
    (throw (ex-info "POM file not found. Run 'clj -T:build jar' first."
                    {:pom-file pom-file}))))

(defn- validate-credentials
  "Validate that CLOJARS_USERNAME and CLOJARS_PASSWORD environment variables are set.

  Throws ex-info with helpful error message if credentials are missing."
  []
  (when-not (System/getenv "CLOJARS_USERNAME")
    (throw (ex-info "CLOJARS_USERNAME environment variable not set.\nSet your Clojars username: export CLOJARS_USERNAME=your-username"
                    {:missing-env-var "CLOJARS_USERNAME"})))
  (when-not (System/getenv "CLOJARS_PASSWORD")
    (throw (ex-info "CLOJARS_PASSWORD environment variable not set.\nSet your Clojars deploy token: export CLOJARS_PASSWORD=your-token\nNote: Use your deploy token, not your password."
                    {:missing-env-var "CLOJARS_PASSWORD"}))))

(defn deploy
  "Deploy JAR to Clojars using deps-deploy.

  Options:
  - :version  - Version string (required - must match JAR build version)
  - :dry-run  - If true, validate but don't deploy (default: false)

  Requires CLOJARS_USERNAME and CLOJARS_PASSWORD environment variables.
  CLOJARS_PASSWORD should contain your deploy token, not your actual password."
  [{version-str :version :keys [dry-run] :or {dry-run false}}]
  (when-not version-str
    (throw (ex-info "Version parameter is required. Pass :version from workflow."
                    {:missing-param :version})))
  (let [jar-file (format "%s/mcp-tasks-%s.jar" target-dir version-str)
        pom-file (format "%s/classes/META-INF/maven/%s/%s/pom.xml"
                         target-dir
                         (namespace lib)
                         (name lib))]
    (println "Version:" version-str)
    (println "Deploying to Clojars:" jar-file)
    (println "POM file:" pom-file)

    ;; Validate files and credentials
    (validate-deployment-files jar-file pom-file)
    (validate-credentials)

    (if dry-run
      (println "Dry-run mode: Skipping deployment")
      (try
        (println "Calling deps-deploy...")
        (dd/deploy {:installer :remote
                    :artifact jar-file
                    :pom-file pom-file})
        (println "Successfully deployed to Clojars")
        (catch Exception e
          (throw (ex-info (format "Deployment failed: %s" (.getMessage e))
                          {:jar-file jar-file
                           :pom-file pom-file
                           :cause e}
                          e)))))))

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
  Examples: mcp-tasks-linux-amd64, mcp-tasks-macos-arm64, mcp-tasks-macos-universal, mcp-tasks-windows-amd64.exe"
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
    (println "Generating prompt manifest...")
    (generate-prompt-manifest)
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version v
                  :basis basis
                  :src-dirs ["src"]
                  :scm {:url "https://github.com/hugoduncan/mcp-tasks"
                        :connection "scm:git:git://github.com/hugoduncan/mcp-tasks.git"
                        :developerConnection "scm:git:ssh://git@github.com/hugoduncan/mcp-tasks.git"
                        :tag (str "v" v)}
                  :pom-data [[:description "Task-based workflow management for AI agents via Model Context Protocol (MCP)"]
                             [:url "https://github.com/hugoduncan/mcp-tasks"]
                             [:licenses
                              [:license
                               [:name "Eclipse Public License 2.0"]
                               [:url "https://www.eclipse.org/legal/epl-2.0/"]
                               [:distribution "repo"]]]
                             [:developers
                              [:developer
                               [:name "Hugo Duncan"]]]]})
    (b/copy-dir {:src-dirs ["src" "resources"]
                 :target-dir class-dir})
    (b/compile-clj {:basis basis
                    :src-dirs ["src"]
                    :class-dir class-dir
                    :java-opts ["-Dborkdude.dynaload.aot=true"]})
    (b/uber {:class-dir class-dir
             :uber-file jar-file
             :basis basis
             :main main-ns})
    (println (format "%s uberjar built successfully: %s" basename jar-file))))

(defn- verify-rosetta-available
  "Check if Rosetta 2 is available on the system.
  Returns true if Rosetta is installed and functional."
  []
  (try
    (let [result (b/process {:command-args ["arch" "-x86_64" "/usr/bin/true"]})]
      (zero? (:exit result)))
    (catch Exception _
      false)))

(defn- build-macos-arch-binary
  "Build a single-architecture macOS native binary.
  
  Parameters:
  - graalvm-home-arm64: Path to ARM64 GraalVM installation
  - graalvm-home-x86_64: Path to x86_64 GraalVM installation
  - jar-file: Path to input JAR file
  - arch: Target architecture (:arm64 or :amd64)
  - output-path: Path for output binary
  
  For :arm64 - builds natively using ARM64 GraalVM
  For :amd64 - builds under Rosetta using x86_64 GraalVM with arch -x86_64 prefix"
  [graalvm-home-arm64 graalvm-home-x86_64 jar-file arch output-path]
  (let [graalvm-home (if (= arch :amd64) graalvm-home-x86_64 graalvm-home-arm64)
        native-image-bin (str graalvm-home "/bin/native-image")
        base-args [native-image-bin
                   "-jar" jar-file
                   "--no-fallback"
                   "-H:+ReportExceptionStackTraces"
                   "-H:IncludeResources=prompts/.*\\.md,category-prompts/.*\\.md"
                   "--initialize-at-build-time"
                   "-o" output-path]
        ;; For amd64, prefix with arch -x86_64 to run x86_64 native-image under Rosetta
        final-args (if (= arch :amd64)
                     (concat ["arch" "-x86_64"] base-args)
                     base-args)]
    (println (format "  Building %s slice..." (name arch)))
    (shell {:command-args final-args})
    output-path))

(defn- combine-universal-binary
  "Combine arm64 and amd64 binaries into a universal binary using lipo.
  
  Parameters:
  - arm64-binary: Path to arm64 binary
  - amd64-binary: Path to amd64 binary  
  - output-path: Path for universal binary output
  
  Cleans up intermediate binaries after successful combination."
  [arm64-binary amd64-binary output-path]
  (println "  Combining architectures with lipo...")
  (shell {:command-args ["lipo" "-create"
                         arm64-binary
                         amd64-binary
                         "-output" output-path]})

  ;; Verify the universal binary
  (println "  Verifying universal binary...")
  (shell {:command-args ["lipo" "-info" output-path]})

  ;; Clean up intermediate binaries
  (println "  Cleaning up intermediate binaries...")
  (fs/delete-if-exists arm64-binary)
  (fs/delete-if-exists amd64-binary)

  output-path)

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
  - binary-type: Human-readable type for messages (e.g., 'CLI', 'server')
  - opts: Optional map with:
    - :target-platform - Override platform for cross-compilation (e.g., {:os :macos :arch :amd64})
    - :universal - Build macOS universal binary containing both arm64 and amd64 (requires macOS host with Rosetta)"
  [jar-basename binary-basename binary-type & [opts]]
  (let [graalvm-home (System/getenv "GRAALVM_HOME")
        graalvm-home-x86-64 (System/getenv "GRAALVM_HOME_X86_64")]
    (when-not graalvm-home
      (throw (ex-info "GRAALVM_HOME environment variable not set. Please set it to your GraalVM installation directory."
                      {:required "GRAALVM_HOME"})))

    (let [host-platform (detect-platform)
          universal? (:universal opts)
          target-platform (cond
                            universal? {:os :macos :arch :universal}
                            (:target-platform opts) (:target-platform opts)
                            :else host-platform)
          cross-compile? (not= host-platform target-platform)
          v (version nil)
          jar-file (format "%s/%s-%s.jar" target-dir jar-basename v)
          binary-name (platform-binary-name binary-basename target-platform)
          output-binary (str target-dir "/" binary-name)]

      (when-not (fs/exists? jar-file)
        (throw (ex-info (format "%s JAR file not found. Run 'clj -T:build jar-%s' first."
                                binary-type
                                (clojure.string/lower-case binary-type))
                        {:jar-file jar-file})))

      ;; Handle universal binary build separately
      (if universal?
        (do
          (when-not (= (:os host-platform) :macos)
            (throw (ex-info "Universal binaries can only be built on macOS"
                            {:host-platform host-platform})))

          (when-not (verify-rosetta-available)
            (throw (ex-info "Rosetta 2 is required to build universal binaries but is not available. Install with: softwareupdate --install-rosetta"
                            {:rosetta-required true})))

          (println (format "Building universal %s binary for macOS (arm64 + amd64)..." binary-type))
          (println "This may take several minutes as both architectures are built...")

          ;; Build both architectures
          (let [arm64-binary (str target-dir "/" binary-basename "-arm64-temp")
                amd64-binary (str target-dir "/" binary-basename "-amd64-temp")]
            (when-not graalvm-home-x86-64
              (throw (ex-info "GRAALVM_HOME_X86_64 environment variable not set. Required for building universal binaries with AMD64 slice."
                              {:required "GRAALVM_HOME_X86_64"})))
            (try
              (build-macos-arch-binary graalvm-home graalvm-home-x86-64 jar-file :arm64 arm64-binary)
              (build-macos-arch-binary graalvm-home graalvm-home-x86-64 jar-file :amd64 amd64-binary)
              (combine-universal-binary arm64-binary amd64-binary output-binary)

              (println (format "✓ Universal %s binary built: %s" binary-type output-binary))
              (println (format "  Platform: macOS (universal: arm64 + amd64)"))
              (println (format "  Size: %.1f MB" (/ (fs/size output-binary) 1024.0 1024.0)))

              (catch Exception e
                ;; Clean up on error
                (fs/delete-if-exists arm64-binary)
                (fs/delete-if-exists amd64-binary)
                (throw e)))))

        ;; Single-architecture build (original logic)
        (let [output-name-for-native-image (if (= (:os target-platform) :windows)
                                             (clojure.string/replace output-binary #"\.exe$" "")
                                             output-binary)
              native-image-bin (if (= (:os host-platform) :windows)
                                 (str graalvm-home "\\bin\\native-image.cmd")
                                 (str graalvm-home "/bin/native-image"))]

          (when cross-compile?
            (println (format "Cross-compiling from %s %s to %s %s"
                             (name (:os host-platform))
                             (name (:arch host-platform))
                             (name (:os target-platform))
                             (name (:arch target-platform)))))

          (println (format "Building native %s binary for %s %s..."
                           binary-type
                           (name (:os target-platform))
                           (name (:arch target-platform))))

          (println "Running native-image (this may take several minutes)...")
          (let [base-args [native-image-bin
                           "-jar" jar-file
                           "--no-fallback"
                           "-H:+ReportExceptionStackTraces"
                           "-H:IncludeResources=prompts/.*\\.md,category-prompts/.*\\.md"
                           "--initialize-at-build-time"
                           "-o" output-name-for-native-image]
                ;; Add architecture flag for macOS cross-compilation
                ;; For macOS, use -march=compatibility for cross-arch builds
                all-args (if (and cross-compile? (= (:os target-platform) :macos))
                           (concat base-args ["-march=compatibility"])
                           base-args)]
            (shell {:command-args all-args}))

          (println (format "✓ Native %s binary built: %s" binary-type output-binary))
          (println (format "  Platform: %s %s" (name (:os target-platform)) (name (:arch target-platform))))
          (println (format "  Size: %.1f MB" (/ (fs/size output-binary) 1024.0 1024.0))))))))

(defn native-cli
  "Build native CLI binary using GraalVM native-image.

  Requires GraalVM with native-image installed and GRAALVM_HOME environment variable set.
  Output: target/mcp-tasks-<platform>-<arch> (e.g., target/mcp-tasks-macos-arm64)

  The native binary provides a standalone CLI without requiring JVM or Babashka.

  Options:
  - :universal - Build macOS universal binary (arm64 + amd64) instead of single-architecture
  - :target-os - Target OS for cross-compilation (:macos, :linux, :windows)
  - :target-arch - Target architecture (:amd64, :arm64)"
  [opts]
  (let [universal? (:universal opts)
        target-platform (when (and (not universal?)
                                   (or (:target-os opts) (:target-arch opts)))
                          {:os (:target-os opts)
                           :arch (:target-arch opts)})
        build-opts (cond
                     universal? {:universal true}
                     target-platform {:target-platform target-platform}
                     :else nil)]
    (build-native-binary "mcp-tasks-cli" "mcp-tasks" "CLI" build-opts)))

(defn native-server
  "Build native server binary using GraalVM native-image.

  Requires GraalVM with native-image installed and GRAALVM_HOME environment variable set.
  Output: target/mcp-tasks-server-<platform>-<arch> (e.g., target/mcp-tasks-server-macos-arm64)

  The native binary provides a standalone MCP server without requiring JVM or Babashka.

  Options:
  - :universal - Build macOS universal binary (arm64 + amd64) instead of single-architecture
  - :target-os - Target OS for cross-compilation (:macos, :linux, :windows)
  - :target-arch - Target architecture (:amd64, :arm64)"
  [opts]
  (let [universal? (:universal opts)
        target-platform (when (and (not universal?)
                                   (or (:target-os opts) (:target-arch opts)))
                          {:os (:target-os opts)
                           :arch (:target-arch opts)})
        build-opts (cond
                     universal? {:universal true}
                     target-platform {:target-platform target-platform}
                     :else nil)]
    (build-native-binary "mcp-tasks-server" "mcp-tasks-server" "server" build-opts)))
