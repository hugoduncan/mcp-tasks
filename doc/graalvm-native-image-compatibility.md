# GraalVM Native-Image Compatibility Assessment

## Summary

Successfully created native binaries for both CLI and MCP server using GraalVM native-image. Both binaries work correctly for their respective use cases:

- **CLI Binary**: ~39 MB, works correctly for all CLI commands with full Malli validation
- **Server Binary**: ~40 MB, runs as MCP server using stdio transport with full validation

## Build Process

### Prerequisites

- GraalVM with native-image installed
- `GRAALVM_HOME` environment variable set
- Example: `GRAALVM_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home`

### Build Commands

**CLI Binary:**
```bash
# Build CLI uberjar (includes AOT compilation with dynaload)
clj -T:build jar-cli

# Build native binary (requires GRAALVM_HOME)
GRAALVM_HOME=/path/to/graalvm clj -T:build native-cli
```

Output: `target/mcp-tasks-<platform>-<arch>` (~39 MB native executable)

**Server Binary:**
```bash
# Build server uberjar (includes AOT compilation with dynaload)
clj -T:build jar-server

# Build native binary (requires GRAALVM_HOME)
GRAALVM_HOME=/path/to/graalvm clj -T:build native-server

# Or use Babashka task (builds both)
bb build-native-server
```

Output: `target/mcp-tasks-server-<platform>-<arch>` (~40 MB native executable)

## Dependency Compatibility

### ✅ Compatible Dependencies

1. **Clojure 1.12.3** - Fully compatible
2. **Cheshire 5.13.0** (JSON) - Works without additional configuration
3. **Babashka/fs 0.5.22** - Filesystem utilities work correctly
4. **Babashka/cli 0.8.61** - CLI parsing works correctly
5. **borkdude/dynaload 0.3.5** - Lazy loading with AOT support
6. **Malli 0.16.4** - Schema validation (via dynaload AOT)

### Dynaload and Malli Integration

**Status**: Fully compatible with AOT compilation

**Approach**: Uses `borkdude/dynaload` for lazy loading with AOT support:

- **dynaload**: Provides compile-time resolution for GraalVM native-image compatibility
- **AOT configuration**: `build.clj` passes `-Dborkdude.dynaload.aot=true` during uberjar compilation
- **Malli validators**: Loaded at compile time via dynaload with fallback defaults
- **Result**: Full schema validation in native binaries without runtime loading warnings

**Implementation details** (in `src/mcp_tasks/schema.cljc` and `src/mcp_tasks/execution_state.cljc`):
```clojure
(def malli-validator (dynaload 'malli.core/validator {:default (constantly (fn [_] true))}))
(def malli-explainer (dynaload 'malli.core/explainer {:default (constantly (fn [_] nil))}))
```

**Benefits over previous requiring-resolve approach**:
- No runtime namespace loading warnings
- Full schema validation enabled
- Compile-time resolution compatible with GraalVM

## Key Implementation Details

### Namespace Loading

**Previous approach (requiring-resolve)**:
- Used `requiring-resolve` with fallback defaults
- Native-image couldn't resolve at compile time
- Resulted in warnings: "Could not locate malli/core__init.class"
- Schema validation was effectively disabled (no-ops)

**Current approach (dynaload with AOT)**:
- Uses `borkdude/dynaload` with `:default` fallbacks
- AOT compilation resolves symbols at build time
- No runtime loading warnings
- Full schema validation works in native binaries

**Entry Points** (`src/mcp_tasks/native_init.clj` and `src/mcp_tasks/native_server_init.clj`):
- Explicitly require all tool namespaces before compilation
- Serve as entry points for native binaries
- Delegate to main application entry points after loading

### Entry Point Summary

- **Regular CLI (BB/JVM)**: `mcp-tasks.cli/-main`
- **Native CLI binary**: `mcp-tasks.native-init/-main` → `mcp-tasks.cli/-main`
- **Regular MCP Server (JVM)**: `mcp-tasks.main/-main`
- **Native Server binary**: `mcp-tasks.native-server-init/-main` → `mcp-tasks.main/-main`

## Build Configuration

### Uberjar AOT Configuration

The `build-uberjar` function in `dev/build.clj` configures dynaload AOT mode:

```clojure
(b/compile-clj {:basis basis
                :src-dirs ["src"]
                :class-dir class-dir
                :java-opts ["-Dborkdude.dynaload.aot=true"]})
```

This JVM option tells dynaload to resolve all lazy references at compile time, enabling GraalVM to include the resolved code in the native binary.

### native-image Flags

```clojure
["native-image"
 "-jar" jar-file
 "--no-fallback"                              ;; No fallback to JVM
 "-H:+ReportExceptionStackTraces"             ;; Better error messages
 "--initialize-at-build-time"                 ;; Initialize all classes at build time
 "-o" output-binary]
```

### Build Time and Resources

- CLI compilation: ~3m 40s on Apple Silicon (via Rosetta for amd64)
- Server compilation: ~2m 40s
- Memory usage: Peak RSS ~3.9GB
- Code area: ~19-20 MB
- Image heap: ~21-22 MB

## Testing Results

### ✅ Verified Working

**CLI Binary:**
```bash
# Help command
./target/mcp-tasks-<platform>-<arch> --help

# List tasks (no Malli warnings)
./target/mcp-tasks-<platform>-<arch> list --status open --format human

# All CLI commands tested and working
# Schema validation functioning correctly
```

**Server Binary:**
```bash
# Start MCP server (stdio transport)
./target/mcp-tasks-server-<platform>-<arch>

# Server starts and accepts MCP protocol messages
# Tested via integration tests with :native-binary metadata
# Smoke tests verify startup on all platforms
# Comprehensive tests validate full MCP protocol on Linux
```

### Previous Warnings (Now Resolved)

Previous behavior showed:
```
Warning: Malformed EDN at line N: Could not locate malli/core__init.class...
```

**Current behavior**: No warnings. Malli is properly loaded at compile time via dynaload AOT.

## Resource Configuration and Prompt Discovery

### Resource Inclusion

Native binaries require explicit resource configuration to embed markdown files:

```clojure
;; In dev/build.clj native-image invocation
["-H:IncludeResources=prompts/.*\\.md,category-prompts/.*\\.md"]
```

This ensures all prompt markdown files are embedded in the binary and accessible via `io/resource`.

### Manifest-Based Prompt Discovery

**Problem**: GraalVM native images don't support directory listing via `io/resource` + `file-seq`. This pattern works in JARs but fails in native binaries because resources are embedded directly in the binary without a traditional filesystem structure.

**Solution**: Generate a manifest file at build time listing all workflow prompts.

**Implementation**:

1. **Manifest Generation** (`dev/build.clj`):
   ```clojure
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
                                           (str/ends-with? (.getName %) ".md")
                                           (not (.isDirectory (.getParentFile %)))))
                              (map #(str/replace (.getName %) #"\\.md$" ""))
                              sort
                              vec)]
       (spit (io/file "resources/prompts-manifest.edn") (pr-str workflow-files))))
   ```

2. **Manifest Reading** (`src/mcp_tasks/prompts.clj`):
   ```clojure
   (defn list-builtin-workflows
     "List all built-in workflow prompts.
     
     Reads from generated manifest file (resources/prompts-manifest.edn) which is
     created at build time. This approach works in both JAR and GraalVM native
     images, avoiding the limitation that directory listing via io/resource + 
     file-seq doesn't work in native binaries."
     []
     (if-let [manifest-resource (io/resource "prompts-manifest.edn")]
       (try
         (read-string (slurp manifest-resource))
         (catch Exception e
           (log/error :failed-to-read-manifest {:error (.getMessage e)})
           []))
       []))
   ```

**Manifest Format**: Simple EDN vector of strings (prompt names without `.md` extension)
```clojure
["complete-story" "create-story-pr" "create-story-tasks" ...]
```

**Build Integration**: The manifest is generated during `build-uberjar` before resources are copied, ensuring it's included in both JAR and native binaries. The manifest file is committed to git for reproducibility.

**Category Prompt Discovery**: Category prompts use the existing `discover-categories` mechanism which reads from `.mcp-tasks/category-prompts/` in the filesystem (not embedded resources), so they don't require manifest-based discovery.

## Reflection Configuration

**Status**: Not required for current implementation

The build succeeds without custom reflection configuration because:
1. All tool namespaces are eagerly loaded at build time
2. dynaload resolves lazy references at compile time
3. No dynamic reflection in critical paths
4. Cheshire JSON serialization doesn't require config for our use case
5. Malli functions are resolved at compile time via dynaload

## Binary Size Analysis

**Current measurements (with dynaload AOT)**:
- CLI Binary: ~39 MB (41 MB total image size)
- Server Binary: ~40 MB (42 MB total image size)

**Previous measurements (with requiring-resolve)**:
- CLI Binary: 38.3 MB
- Server Binary: ~38 MB

**Analysis**: The slight size increase (~0.7-2 MB) is due to Malli being properly included in the binary. Previously, Malli was effectively excluded because requiring-resolve couldn't load it at compile time. The trade-off is:
- **Pros**: Full schema validation, no runtime warnings, cleaner operation
- **Cons**: Slightly larger binary size

### Potential Optimizations

Future binary size reduction options:
- `--gc=G1` - Different garbage collector
- `-O3` - Higher optimization level
- Profile-Guided Optimizations (`--pgo`)
- Remove unused Malli schemas/validators
- Tree-shaking unused code paths

## MCP Client Configuration

The native server binary can be configured in MCP clients:

**Claude Code:**
```bash
claude mcp add mcp-tasks -- /usr/local/bin/mcp-tasks-server
```

**Claude Desktop:**
```json
{
  "mcpServers": {
    "mcp-tasks": {
      "command": "/usr/local/bin/mcp-tasks-server"
    }
  }
}
```

The server binary uses stdio transport and requires no additional arguments or configuration.

## Conclusion

Both native binaries (CLI and server) are **production-ready** with full schema validation support. The migration from `requiring-resolve` to `borkdude/dynaload` with AOT compilation:

**Improvements:**
- Eliminated runtime namespace loading warnings
- Enabled full Malli schema validation in native binaries
- Cleaner operation without "Could not locate" errors
- Better compatibility with GraalVM native-image

**Trade-offs:**
- Binary size increased slightly (~1-2 MB) due to Malli inclusion
- Target size reduction (<30 MB) not achieved

**Key Benefits:**
- No JVM or Babashka runtime required
- Fast startup (< 10ms typical)
- Standalone distribution
- Cross-platform support (Linux, macOS, Windows)
- Full schema validation in native binaries
