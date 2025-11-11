# GitHub Workflows

This document provides comprehensive documentation for all GitHub Actions workflows in this repository.

## Workflows

### test.yml

Comprehensive testing workflow with three jobs.

**Triggers:**
- Pushes to master branch
- All pull requests

**Jobs:**

1. **test** (ubuntu-latest)
   - Runs cljstyle check
   - Runs clj-kondo lint with `--fail-level warning`
   - Runs unit tests (with `:unit` focus)
   - Runs integration tests (with `:integration` focus, skipping `:native-binary` tests)

2. **babashka-test** (ubuntu-latest)
   - Verifies bb.edn is valid (`bb tasks`)
   - Tests CLI help commands for all subcommands (list, add, show, complete, update, delete)
   - Runs smoke test with `bb list --format json`

3. **install-scripts-lint** (ubuntu-latest)
   - Runs shellcheck on the `install` script

**Caching Strategy:**
- Caches `~/.m2/repository` and `~/.gitlibs` directories
- Cache key based on `deps.edn` hash for automatic invalidation

### build-binaries.yml

Reusable workflow for building native binaries.

**Triggers:**
- `workflow_call` - Called by other workflows (e.g., release.yml)
- `workflow_dispatch` - Manual triggering for testing

**Jobs:**

1. **test** (ubuntu-latest)
   - Runs cljstyle check
   - Runs clj-kondo lint with `--fail-level warning`
   - Runs unit tests (with `:unit` focus)
   - Runs integration tests (with `:integration` focus, skipping `:native-binary` tests)
   - Must pass before binaries are built

2. **build-matrix** (multi-platform)
   - Builds binaries for multiple OS/architecture combinations
   - **Matrix Strategy:**
     - `linux-amd64` (ubuntu-latest) - Standard x86_64 Linux binary
     - `macos-universal` (macos-latest) - Universal binary with x86_64 and arm64 slices
   - **Build Process:**
     - Builds CLI JAR → Native CLI binary
     - Builds Server JAR → Native server binary
     - Uses GraalVM for native compilation
     - Universal binaries created with `:universal true` flag
   - **Architecture Verification:**
     - macOS universal: Verifies both x86_64 and arm64 slices present
     - Linux amd64: Verifies x86-64 only (no ARM)
     - Fails build if architecture requirements not met
   - **Artifacts:**
     - CLI binary: `mcp-tasks-{os}-{arch}`
     - Server binary: `mcp-tasks-server-{os}-{arch}`

3. **test-binaries** (multi-platform)
   - Tests built binaries on their target platforms
   - **Testing Strategy:**
     - **macOS** (`test-focus: smoke`): Fast smoke tests only
       - Skips comprehensive tests for faster feedback
       - Uses `--skip-meta :comprehensive` flag
     - **Linux** (`test-focus: comprehensive`): Full test suite
       - Runs all native binary tests
       - Provides complete validation
   - **Environment Variables:**
     - `BINARY_TARGET_OS`: Target OS (linux/macos)
     - `BINARY_TARGET_ARCH`: Target architecture (amd64/universal)
   - Tests use these variables to locate correct binary

### release.yml

Manual workflow for releasing new versions.

**Triggers:**
- `workflow_dispatch` - Manual triggering from GitHub Actions UI
  - `dry-run` input (optional, default: false) - Skip deployment steps for testing

**Dependencies:**
- Calls `build-binaries.yml` reusable workflow to build native binaries
- Waits for binary builds to complete before proceeding

**Jobs:**

1. **build-binaries** (reusable workflow call)
   - Builds all native binaries (CLI and server for linux-amd64 and macos-universal)
   - See build-binaries.yml documentation above for details

2. **release** (ubuntu-latest)
   - **Prerequisites:**
     - Requires `build-binaries` job to complete successfully
     - Only runs on master branch or when dry-run is enabled
     - Requires GitHub environment named "Release"
   
   - **Version Calculation:**
     - Uses commit count-based versioning: `0.1.N` where N is commit count
     - Calculated via `clojure -T:build version`
     - Outputs both version (e.g., "0.1.123") and tag (e.g., "v0.1.123")
   
   - **SHA Reference Updates:**
     - Updates documentation files with new version tag and commit SHA
     - Files updated: `doc/install.md`, `plugins/mcp-tasks-skill/README.md`
     - Uses `scripts/update-sha-refs.sh` script
     - Commits changes with message: "docs: update to <tag> (<sha>)"
     - Gracefully handles case where no updates are needed
   
   - **JAR Build and Validation:**
     - Builds JAR artifact with calculated version
     - Validates JAR exists at expected location
     - Prints JAR size and SHA256 checksum
     - Runs smoke test to verify basic functionality
   
   - **Changelog Generation:**
     - Uses git-cliff to generate changelog
     - If no previous tag exists, generates full changelog
     - Otherwise, generates changelog from previous tag to HEAD
     - Saves changelog to file and GitHub Actions output
   
   - **Binary Artifact Validation:**
     - Downloads all 4 binary artifacts from build-binaries job:
       - `mcp-tasks-linux-amd64`
       - `mcp-tasks-macos-universal`
       - `mcp-tasks-server-linux-amd64`
       - `mcp-tasks-server-macos-universal`
     - Validates all binaries exist before proceeding
     - Fails release if any binary is missing
   
   - **Deployment Steps (skipped in dry-run mode):**
     - **Git Tag**: Creates annotated tag and pushes to remote
     - **Clojars Deployment**: Deploys JAR using credentials from GitHub secrets
     - **GitHub Release**: Creates release with:
       - Tag name (e.g., "v0.1.123")
       - Generated changelog as release body
       - JAR artifact
       - All 4 binary artifacts
       - Non-draft, non-prerelease status

**Release Process:**
1. Go to Actions → Release workflow
2. Click "Run workflow"
3. Optionally enable dry-run to test without deploying
4. Workflow automatically:
   - Builds binaries for all platforms
   - Calculates version (0.1.N based on commit count)
   - Updates SHA references in documentation
   - Builds and validates JAR
   - Generates changelog
   - Validates binary artifacts
   - Creates git tag (if not dry-run)
   - Deploys to Clojars (if not dry-run)
   - Creates GitHub Release with JAR and binaries (if not dry-run)

## Custom Actions

### setup-clojure

`.github/actions/setup-clojure/action.yml`

Reusable action that sets up the Clojure development environment. Used by all workflows to ensure consistent environment configuration.

**Purpose:** Centralizes Java/GraalVM setup, Clojure tools installation, and dependency caching

**Inputs:**
- `java-distribution`: Java distribution to use (`temurin` or `graalvm`, default: `temurin`)
- `java-version`: Java version (default: `21`)
- `install-linters`: Whether to install cljstyle and clj-kondo (default: `false`)
- `cache-key-suffix`: Optional cache key suffix for platform-specific caching
- `github-token`: GitHub token (required for GraalVM distribution)

**Features:**
- Supports both Temurin and GraalVM distributions
- For macOS universal binaries: Installs both ARM64 (via graalvm/setup-graalvm) and x86_64 GraalVM for cross-compilation
- Installs Clojure CLI and Babashka via DeLaGuardo/setup-clojure
- Optionally installs linters (cljstyle, clj-kondo)
- Caches Maven and gitlibs dependencies with configurable cache keys

## Binary Testing

### Test Organization

Native binary integration tests are located in:
- [test/mcp_tasks/native_binary_integration_test.clj](../test/mcp_tasks/native_binary_integration_test.clj) - CLI binary tests
- [test/mcp_tasks/native_server_binary_integration_test.clj](../test/mcp_tasks/native_server_binary_integration_test.clj) - Server binary tests

Tests use metadata tags for classification:
- `:native-binary` - All native binary tests (required for filtering)
- `:comprehensive` - Thorough validation tests (slower, complete coverage)
- `:smoke` - Fast feedback tests (quick validation of basic functionality)

### Running Tests Locally

Build the native binaries first:
```bash
# Build CLI binary
clj -T:build native-cli

# Build server binary
clj -T:build native-server
```

Run tests with environment variables to specify target platform:
```bash
# Smoke tests only (fast feedback)
BINARY_TARGET_OS=macos BINARY_TARGET_ARCH=universal \
  clojure -M:dev:test --focus :native-binary --skip-meta :comprehensive

# Comprehensive tests (full validation)
BINARY_TARGET_OS=linux BINARY_TARGET_ARCH=amd64 \
  clojure -M:dev:test --focus :native-binary
```

**Environment Variables:**
- `BINARY_TARGET_OS` - Target OS (`linux` or `macos`)
- `BINARY_TARGET_ARCH` - Target architecture (`amd64` or `universal`)

### CI Testing Strategy

The CI workflows use a platform-specific testing approach to balance speed and coverage:

- **macOS** (`test-focus: smoke`): Fast smoke tests only
  - Skips comprehensive tests for faster feedback
  - Uses `--skip-meta :comprehensive` flag
  - Provides quick validation that binaries work on macOS

- **Linux** (`test-focus: comprehensive`): Full test suite
  - Runs all native binary tests (both smoke and comprehensive)
  - Provides complete validation on Linux platform
  - Ensures thorough coverage before release

**Rationale:**
This split allows the CI pipeline to provide fast feedback on macOS while still maintaining comprehensive test coverage on Linux. macOS universal binaries are tested for basic functionality, while the more critical Linux binaries receive full validation. This approach reduces CI runtime without sacrificing test quality.
