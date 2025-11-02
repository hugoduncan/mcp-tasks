# mcp-tasks

Task-based workflow management for AI agents via Model Context Protocol (MCP).

## Installation

### Quick Start: Claude Code Marketplace

Install the skill plugin directly from Claude Code:

```bash
/plugin install hugoduncan/mcp-tasks
```

This installs the **mcp-tasks-skill plugin**, which provides comprehensive documentation and guidance for using mcp-tasks workflows in Claude Code.

**Important:** The skill plugin provides documentation and usage patterns. To use mcp-tasks functionality (tools, prompts, resources), you must also install the **mcp-tasks MCP server** separately (see below).

### MCP Server Setup

After installing the skill plugin, configure the mcp-tasks MCP server to enable task management tools and prompts:

1. **Add to `~/.clojure/deps.edn`:**

```clojure
{:aliases
 {:mcp-tasks
  {:replace-paths []
   :replace-deps {org.hugoduncan/mcp-tasks
                  {:git/url "https://github.com/hugoduncan/mcp-tasks"
                   :git/sha "2d82cffb53e3f03deced02365f5be314c7377f0b"}
                  org.clojure/clojure {:mvn/version "1.12.3"}}
   :exec-fn mcp-tasks.main/start}}}
```

2. **Configure Claude Code:**

```bash
claude mcp add mcp-tasks -- $(which clojure) -X:mcp-tasks
```

3. **Initialize task directories in your project:**

```bash
mkdir -p .mcp-tasks/prompts

# Optional: Initialize as git repository for version control
cd .mcp-tasks && git init && git commit --allow-empty -m "Initialize task tracking" && cd ..
```

**What You Get:**
- **Skill Plugin**: Documentation, workflow guidance, and usage examples accessible in Claude Code
- **MCP Server**: Actual task management functionality - tools (add-task, select-tasks, etc.), prompts (/mcp-tasks:next-simple, etc.), and resources

For complete installation instructions including Claude Desktop setup, see **[doc/install.md](doc/install.md)**.

## Quick Start

```bash
# Add to ~/.clojure/deps.edn
{:aliases
 {:mcp-tasks
  {:replace-paths []
   :replace-deps {org.hugpduncan/mcp-tasks
                  {:git/url "https://github.com/hugoduncan/mcp-tasks"
                   :git/sha "2d82cffb53e3f03deced02365f5be314c7377f0b"}
                  org.clojure/clojure {:mvn/version "1.12.3"}}
   :exec-fn mcp-tasks.main/start}}}

# Initialize task directories
mkdir -p .mcp-tasks/prompts

# (Optional) Initialize as git repository for version control
cd .mcp-tasks && git init && git commit --allow-empty -m "Initialize task tracking" && cd ..

# Using with MCP Client (Claude Code, Claude Desktop, etc.)
# Configure Claude Code
claude mcp add mcp-tasks -- $(which clojure) -X:mcp-tasks

# Add your first task using the MCP tool
# In Claude Code, use the add-task tool:
# - category: "simple"
# - title: "Add README badges for build status"

# Execute tasks in Claude Code
/mcp-tasks:next-simple

# Or use the command-line interface directly
# Add tasks from the terminal
clojure -M:cli add --category simple --title "Add README badges"

# List tasks
clojure -M:cli list --format human

# Execute tasks in your MCP client or complete them via CLI
clojure -M:cli complete --task-id 1

# Reopen a task if needed
clojure -M:cli reopen --task-id 1
```

**[Installation Guide](doc/install.md)** • **[Workflow Documentation](doc/workflow.md)**

---

### Using with Babashka (Faster Startup)

The CLI can run under [Babashka](https://babashka.org/) for dramatically faster startup times—ideal for scripting and interactive use.

**Performance Comparison:**
- JVM Clojure: ~2.2 seconds
- Babashka (help commands): ~66ms (0.066 seconds)
- Babashka (with validation): ~390ms first run, ~330ms cached
- **~33x faster for help commands**

**Prerequisites:**
```bash
# Install babashka (if not already installed)
# macOS
brew install babashka/brew/babashka

# Linux
bash <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)

# Or see https://github.com/babashka/babashka#installation
```

**Usage:**

The project includes a `bb.edn` configuration with task aliases for all CLI commands:

```bash
# List available babashka tasks
bb tasks

# Use CLI commands with bb prefix
bb list --category simple
bb add --category simple --title "New task"
bb show --task-id 42
bb complete --task-id 42
bb reopen --task-id 42
bb update --task-id 42 --status in-progress
bb delete --task-id 42

# Or use the main CLI entry point
bb cli list --format json
bb cli add --category feature --title "Add endpoint"
```

**Side-by-Side Comparison:**

| Operation | JVM Clojure | Babashka |
|-----------|-------------|----------|
| List tasks | `clojure -M:cli list` | `bb list` |
| Add task | `clojure -M:cli add --category simple --title "..."` | `bb add --category simple --title "..."` |
| Show task | `clojure -M:cli show --task-id 42` | `bb show --task-id 42` |
| Complete task | `clojure -M:cli complete --task-id 42` | `bb complete --task-id 42` |
| Reopen task | `clojure -M:cli reopen --task-id 42` | `bb reopen --task-id 42` |
| Update task | `clojure -M:cli update --task-id 42 --status in-progress` | `bb update --task-id 42 --status in-progress` |

**When to Use Babashka vs JVM:**
- **Babashka**: CLI operations, scripting, interactive task management
- **JVM Clojure**: MCP server (requires JVM), development/testing with full Clojure toolchain

**Note:** The MCP server still requires JVM Clojure (`clojure -X:mcp-tasks`). Babashka support is for CLI operations only.

**Testing with Babashka:**
A `bb test` task is available in `bb.edn`, but currently blocked by a dependency incompatibility. The mcp-clj-server test-dep variant requires clojure.data.json, which doesn't work in babashka. Until this is resolved, use JVM Clojure for testing: `clojure -M:dev:test --focus :unit`

### Standalone Executable (Uberscript)

For distribution or system-wide installation, you can build a standalone executable that bundles all dependencies. For a comparison with the Native Binary distribution, see [Distribution Comparison](#native-binary-distribution).

```bash
# Build the executable (from the mcp-tasks source directory)
bb build-uberscript

# This creates a 48KB executable: ./mcp-tasks
# Use it directly
./mcp-tasks list --category simple
./mcp-tasks add --title "New task" --category simple
./mcp-tasks reopen --task-id 42

# Install system-wide (optional)
sudo cp mcp-tasks /usr/local/bin/
mcp-tasks list --format human
```

**Uberscript Benefits:**
- **Single file distribution** - No need to install source code or dependencies
- **Fast startup** - Same ~66ms performance as `bb` commands
- **Portable** - Copy to any system with babashka installed
- **No classpath setup** - All dependencies bundled in the executable

**Performance:**
The uberscript maintains the same optimized performance as running via `bb`:
- Help commands: ~63ms
- Commands with validation: ~390ms first run, ~330ms cached

**Note:** The uberscript requires babashka to be installed on the target system (`/usr/bin/env bb` shebang).

### Native Binary Distribution

For true standalone execution without any runtime dependencies, native binaries are available for all major platforms. These are built with GraalVM native-image and require no JVM or Babashka installation.

For an alternative distribution option using Babashka, see the [Standalone Executable (Uberscript)](#standalone-executable-uberscript) section above.

**Two Binary Types:**

1. **CLI Binary** (`mcp-tasks`) - Task management commands from the terminal
2. **Server Binary** (`mcp-tasks-server`) - MCP server for AI agent integration

**Download Pre-Built Binaries:**

Download the latest binaries for your platform from the [GitHub Releases](https://github.com/hugoduncan/mcp-tasks/releases) page:

**CLI Binaries:**
- **Linux (amd64)**: `mcp-tasks-linux-amd64`
- **macOS (Intel)**: `mcp-tasks-macos-amd64`
- **macOS (Apple Silicon)**: `mcp-tasks-macos-arm64`
- **Windows**: `mcp-tasks-windows-amd64.exe`

**Server Binaries:**
- **Linux (amd64)**: `mcp-tasks-server-linux-amd64`
- **macOS (Intel)**: `mcp-tasks-server-macos-amd64`
- **macOS (Apple Silicon)**: `mcp-tasks-server-macos-arm64`
- **Windows**: `mcp-tasks-server-windows-amd64.exe`

**Installation:**

**CLI Binary:**
```bash
# Download binary (example for macOS arm64)
curl -L -o mcp-tasks https://github.com/hugoduncan/mcp-tasks/releases/latest/download/mcp-tasks-macos-arm64

# Make executable (Unix-like systems)
chmod +x mcp-tasks

# Install system-wide (optional)
sudo mv mcp-tasks /usr/local/bin/

# Use directly
mcp-tasks list --category simple
mcp-tasks add --title "New task" --category simple
```

**Server Binary:**
```bash
# Download server binary (example for macOS arm64)
curl -L -o mcp-tasks-server https://github.com/hugoduncan/mcp-tasks/releases/latest/download/mcp-tasks-server-macos-arm64

# Make executable (Unix-like systems)
chmod +x mcp-tasks-server

# Install system-wide (optional)
sudo mv mcp-tasks-server /usr/local/bin/

# Configure in Claude Code
claude mcp add mcp-tasks -- /usr/local/bin/mcp-tasks-server

# Or configure in Claude Desktop (see MCP client configuration section below)
```

**Building from Source:**

Requirements:
- GraalVM 21+ with native-image installed
- Set `GRAALVM_HOME` environment variable

**CLI Binary:**
```bash
# Build CLI JAR first
clj -T:build jar-cli

# Build native binary for your platform
GRAALVM_HOME=/path/to/graalvm clj -T:build native-cli

# Binary created at: target/mcp-tasks-<platform>-<arch>
```

**Server Binary:**
```bash
# Build server JAR first
clj -T:build jar-server

# Build native binary for your platform
GRAALVM_HOME=/path/to/graalvm clj -T:build native-server

# Or use Babashka task (builds both JAR and native binary)
bb build-native-server

# Binary created at: target/mcp-tasks-server-<platform>-<arch>
```

**Distribution Comparison:**

| Feature | Babashka Uberscript | Native Binary |
|---------|-------------------|---------------|
| **Size** | 48 KB | 38.3 MB |
| **Startup Time** | ~66ms (help commands) | ~instant (<10ms typical) |
| **Runtime Dependency** | Requires Babashka installed | None - fully standalone |
| **Distribution** | Single portable script | Platform-specific executable |
| **Best For** | Systems with Babashka, scripting | Standalone deployment, no dependencies |

**Choosing a Distribution:**

- **Use Native Binary when:**
  - You need true standalone execution without any runtime
  - Deploying to systems where installing runtimes is restricted
  - Absolute fastest startup time is critical
  - You can accept larger binary size for convenience

- **Use Babashka Uberscript when:**
  - You already have Babashka installed
  - Minimizing disk space is important (48KB vs 35MB)
  - You want a single cross-platform script
  - You prefer the Babashka ecosystem

**Troubleshooting Native Binaries:**

Common issues and solutions:

1. **"Permission denied" errors (Unix)**
   ```bash
   chmod +x mcp-tasks
   ```

2. **macOS "unidentified developer" warning**
   ```bash
   # First run: Right-click → Open, then click "Open"
   # Or remove quarantine attribute:
   xattr -d com.apple.quarantine mcp-tasks
   ```

3. **Windows SmartScreen warning**
   - Click "More info" → "Run anyway"
   - Binaries are not code-signed (open source project)

4. **"Library not found" errors**
   - Native binaries are statically linked and should work standalone
   - Ensure you downloaded the correct platform binary
   - Try re-downloading if file was corrupted

5. **Build failures with GraalVM**
   - Verify GraalVM 21+ is installed: `native-image --version`
   - Ensure `GRAALVM_HOME` points to GraalVM root directory
   - Check that native-image component is installed
   - Build CLI JAR first before building native binary

6. **Malli validation warnings during build**
   - Expected behavior - Malli validation is disabled for native builds
   - Warnings don't affect functionality

**MCP Client Configuration:**

Configure the server binary in your MCP client:

**Claude Code:**
```bash
# Using system-wide installation
claude mcp add mcp-tasks -- /usr/local/bin/mcp-tasks-server

# Or using local binary
claude mcp add mcp-tasks -- /path/to/mcp-tasks-server
```

**Claude Desktop:**

Edit your Claude Desktop configuration file:

- **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Windows**: `%APPDATA%\Claude\claude_desktop_config.json`
- **Linux**: `~/.config/Claude/claude_desktop_config.json`

Add the server configuration:

```json
{
  "mcpServers": {
    "mcp-tasks": {
      "command": "/usr/local/bin/mcp-tasks-server"
    }
  }
}
```

**Other MCP Clients:**

For other MCP-compatible clients, configure the server binary path according to your client's configuration format. The server uses stdio transport and requires no additional arguments.

---

## What & Why

mcp-tasks enables you to manage development tasks in markdown files and have AI agents execute them. Unlike todo tools, mcp-tasks integrates task planning with execution—agents don't just track tasks, they complete them.

**Key Benefits:**
- **Persistent Planning**: Tasks survive across chat sessions in markdown files
- **Category-Based Organization**: Group tasks by type (features, bugfixes, refactoring) with custom execution strategies
- **Story-Based Development**: Break down epics into tasks with dedicated workflows for refinement and execution
- **Audit Trail**: Completed tasks automatically archived with full context
- **Flexible Workflows**: Supports both git-tracked and standalone task management

**When to Use:**
- Complex projects requiring systematic task execution
- Multi-agent workflows with parallel task streams
- Projects where task history and planning context matter
- Teams coordinating agent-driven development

**vs. clojure-mcp:** This is a task management layer built on the [clojure-mcp](https://github.com/hugoduncan/mcp-clj) server library. clojure-mcp provides MCP infrastructure; mcp-tasks adds workflow automation on top.

## Core Usage

### 1. Create Task Files

Tasks are stored in `.mcp-tasks/tasks.ednl` as EDN records:

```clojure
{:id 1 :category "simple" :title "Implement user authentication" :status :open ...}
{:id 2 :category "simple" :title "Add error handling to API endpoints" :status :open ...}
{:id 3 :category "simple" :title "Write integration tests" :status :open ...}
```

### 2. Run Task Prompts

Execute the first incomplete task in a category:

```
/mcp-tasks:next-<category>
```

Example:
```
/mcp-tasks:next-simple
```

The agent will:
- Read the first task with matching category from `tasks.ednl`
- Analyze requirements in project context
- Implement the solution
- Commit changes to your repository
- Move completed task to `complete.ednl` with `:status :closed`
- Commit the task repo (if git mode enabled)

### 3. Review and Iterate

```bash
# Check what was completed
# Use the select-tasks tool to view completed tasks in .mcp-tasks/complete.ednl

# Review the git commit
git log -1 --stat

# Add more tasks using the add-task tool
# In Claude Code: add-task with category "simple" and title "Optimize database queries"
```

### Real Workflow Example

```bash
# Add tasks for different categories using the add-task tool
# In Claude Code:
# - add-task category: "feature", title: "Add user profile endpoint"
# - add-task category: "bugfix", title: "Fix memory leak in worker process"
# - add-task category: "refactor", title: "Extract validation logic to separate module"

# Process tasks by priority
/mcp-tasks:next-bugfix      # Agent fixes memory leak, commits
/mcp-tasks:next-feature     # Agent adds endpoint, commits
/mcp-tasks:next-refactor    # Agent extracts validation, commits

# Check completion history
# View completed tasks in .mcp-tasks/complete.ednl
```

See **[doc/workflow.md](doc/workflow.md)** for advanced patterns including git worktrees for parallel task execution.

### Story-Based Workflows

For larger features that require multiple related tasks, use story workflows:

```bash
# Create a story using the add-task tool
# In Claude Code:
# add-task with:
#   category: "large"
#   type: "story"
#   title: "User Authentication System"
#   description: "Implement JWT-based authentication with user registration, login, and password reset.
#
# Requirements:
# - User registration with email validation
# - JWT token generation and validation
# - Password hashing with bcrypt
# - Password reset via email"

# Refine the task interactively with agent
/mcp-tasks:refine-task "User Authentication System"

# Break down into executable tasks
/mcp-tasks:create-story-tasks user-auth

# Execute tasks one by one
/mcp-tasks:execute-story-task user-auth
```

The agent will:
- Guide you through story refinement with feedback loops
- Break the story into categorized tasks
- Execute tasks sequentially, tracking progress
- Optionally manage feature branches automatically

See **[doc/workflow.md#story-workflows](doc/workflow.md#story-workflows)** for complete documentation.

## Command Line Interface

The CLI provides direct access to task management from the terminal, complementing the primary MCP interface. While the MCP server is the recommended way to use mcp-tasks with AI agents, the CLI is useful for scripting, inspecting task state, and working outside of MCP-enabled environments.

**CLI Distribution Options:**
- **Native Binary**: Standalone executable, no runtime required (see [Native Binary Distribution](#native-binary-distribution) above)
- **Babashka Uberscript**: Fast startup with Babashka runtime (see [Standalone Executable](#standalone-executable-uberscript) above)
- **JVM Clojure**: Full Clojure toolchain via `clojure -M:cli` (for development)

For complete CLI documentation including commands, output formats, workflows, and configuration options, see **[doc/command-line.md](doc/command-line.md)**.

**Available Commands:** `list`, `show`, `add`, `update`, `complete`, `reopen`, `delete`

## Configuration

### Config File Discovery

mcp-tasks automatically discovers configuration by searching up the directory tree from your current working directory. This enables hierarchical project structures where you can place `.mcp-tasks.edn` in your project root and invoke the server from any subdirectory.

**How It Works:**

1. Starting from the current working directory (CWD)
2. Check for `.mcp-tasks.edn` in the current directory
3. If not found, move to the parent directory and repeat
4. Stop at the first `.mcp-tasks.edn` found or at filesystem root
5. Use the directory containing `.mcp-tasks.edn` as the config base directory
6. Symlinks are resolved during traversal

**Example Project Layout:**

```
my-project/                    # Project root
├── .mcp-tasks.edn            # Config discovered from any subdirectory
├── .mcp-tasks/               # Default task storage location
│   ├── tasks.ednl
│   ├── complete.ednl
│   └── prompts/
│       └── feature.md
├── src/
│   └── core.clj
└── test/
    └── core_test.clj

# Works from any directory:
cd my-project/src              # Server finds ../mcp-tasks.edn
cd my-project/test             # Server finds ../mcp-tasks.edn
cd my-project                  # Server finds ./.mcp-tasks.edn
```

**Custom Task Storage Location:**

Use the `:tasks-dir` config key to store tasks in a non-standard location:

```clojure
;; .mcp-tasks.edn in project root
{:tasks-dir ".mcp-tasks"}           ; Relative to config file (default)
{:tasks-dir "../shared-tasks"}      ; Relative path to shared location
{:tasks-dir "/home/user/.mcp-tasks/my-project"}  ; Absolute path
```

**Path Resolution Rules:**

- **Absolute paths**: Used as-is (e.g., `/home/user/.mcp-tasks/project`)
- **Relative paths**: Resolved relative to the config file's directory, not CWD
- **Default**: `.mcp-tasks` relative to config file directory when `:tasks-dir` not specified
- **Validation**: Explicitly specified `:tasks-dir` must exist or server will error

**Benefits of Config Discovery:**

1. **Work from anywhere**: Invoke server from subdirectories without path juggling
2. **Shared tasks**: Store tasks outside project (e.g., `~/.mcp-tasks/my-project/`)
3. **Monorepo support**: Single config at root, tasks accessible from all subprojects
4. **Consistent paths**: All relative paths resolve from config location, not CWD

**When to Use Config Discovery:**

✓ **Use hierarchical structure when:**
- Working in deep directory hierarchies
- Managing tasks for a monorepo with multiple subprojects
- Storing tasks separately from project code
- Multiple team members invoking from different subdirectories

✓ **Use single directory setup when:**
- Simple project structure with all work in one directory
- Tasks are tightly coupled to specific subdirectories
- Each subdirectory needs independent task management

**Example: Shared Task Storage**

```clojure
;; Store all project tasks in a central location
;; ~/projects/my-app/.mcp-tasks.edn
{:tasks-dir "/home/user/.mcp-tasks/my-app"
 :use-git? true}

;; Directory structure:
;; ~/projects/my-app/              # Project code
;; ~/.mcp-tasks/my-app/            # Centralized task storage
;;   ├── .git/
;;   ├── tasks.ednl
;;   ├── complete.ednl
;;   └── prompts/
```

This setup keeps task history separate from project code, useful for:
- Version controlling tasks independently
- Sharing tasks across multiple project clones
- Keeping task history when recreating project environments

### Git Integration

mcp-tasks supports two workflows:

**1. Zero-Config Auto-Detection (Default)**

The server automatically detects if `.mcp-tasks/.git` exists and enables git features accordingly. No configuration needed:

```bash
# Git mode: Auto-enabled if .mcp-tasks is a git repository
cd .mcp-tasks && git init && cd ..

# Non-git mode: Auto-enabled if no .mcp-tasks/.git directory
# Just create the directory structure - no git needed
mkdir -p .mcp-tasks/prompts
```

**2. Explicit Configuration (Optional)**

Override auto-detection by creating `.mcp-tasks.edn` in your project root (sibling to `.mcp-tasks/`):

```clojure
{:use-git? true}   ; Force git mode on
{:use-git? false}  ; Force git mode off
```

**Behavior by Mode:**

| Feature | Git Mode | Non-Git Mode |
|---------|----------|--------------|
| Task tracking | ✓ | ✓ |
| Task completion | ✓ | ✓ |
| Automated commits | ✓ | ✗ |
| Modified file list | ✓ | ✗ |

**Precedence:** Explicit config (`.mcp-tasks.edn`) overrides auto-detection.

**Note:** Your project's git repository is independent of `.mcp-tasks` git tracking. The `:use-git?` setting only affects task tracking commits within `.mcp-tasks/`.

### Custom Categories

Categories are defined by creating prompt files in `.mcp-tasks/prompts/`. Create new categories by adding prompt templates:

```bash
mkdir -p .mcp-tasks/prompts
cat > .mcp-tasks/prompts/docs.md <<'EOF'
---
description: Execute documentation tasks
---
# Documentation Task Workflow
[Your custom execution steps here]
EOF
```

Now `/mcp-tasks:next-docs` is available for tasks with `category: "docs"`.

### Category-Specific Instructions

Override default task execution by creating `.mcp-tasks/prompts/<category>.md`:

```bash
# Install built-in prompt templates
clojure -M:mcp-tasks --install-prompts

# Or create custom prompts
mkdir -p .mcp-tasks/prompts
cat > .mcp-tasks/prompts/feature.md <<'EOF'
- Review existing code architecture
- Design the feature following project patterns
- Write tests first (TDD approach)
- Implement the feature
- Update relevant documentation
EOF
```

Available built-in templates (use `--list-prompts` to see all):
- `simple` - Basic task execution with standard implementation steps
- `clarify-task` - Transform vague instructions into detailed specifications

The prompt file provides custom execution instructions for the category, replacing the default implementation approach.

**Prompt Structure:**
- Initial steps: Read next task from `tasks.ednl` using `select-tasks` tool
- Middle steps: Custom instructions (from `.mcp-tasks/prompts/<category>.md`) or default implementation steps
- Final steps: Commit changes, mark task complete using `complete-task` tool (moves to `complete.ednl`), update task tracking

See **[doc/workflow.md#category-specific-instructions](doc/workflow.md#category-specific-instructions)** for examples.

## MCP Resources

mcp-tasks exposes its execution prompts as MCP resources, enabling advanced use cases like prompt composition and inspection.

### Prompt Resources

Each category and story workflow has an associated prompt resource accessible via the `prompt://` URI scheme:

**Category prompts:**
```
prompt://next-<category>
```

Examples:
- `prompt://next-simple` - Simple task execution prompt
- `prompt://next-feature` - Feature task execution prompt
- `prompt://next-bugfix` - Bugfix task execution prompt

**Story prompts:**
```
prompt://refine-task
prompt://create-story-tasks
prompt://execute-story-task
```

### Resource Format

Prompt resources include YAML frontmatter with metadata:

```markdown
---
description: Execute simple tasks with basic workflow
---

Please complete the next simple task following these steps:
[... prompt content ...]
```

**Frontmatter fields:**
- `description` (string) - Brief explanation of the prompt's purpose
- Additional metadata may be present in custom prompts

### Accessing Resources

**List all prompt resources:**

Most MCP clients provide a way to list available resources. The exact method depends on your client.

**Read a prompt resource:**

Use your MCP client's resource reading capability with the `prompt://` URI. For example, in Claude Code you might use tools to inspect prompt resources programmatically.

### Use Cases

**1. Prompt Composition**

Build custom workflows by combining or referencing existing prompts:

```markdown
# Custom prompt that references another
Execute the feature task, then follow the review process from prompt://review-checklist
```

**2. Prompt Inspection**

Understand what a category prompt does before executing tasks:
- Read `prompt://next-simple` to see the simple task workflow
- Compare different category prompts to choose appropriate categories

**3. Documentation**

Generate workflow documentation by extracting prompt content and descriptions.

**4. Tooling Integration**

Build tools that discover and utilize mcp-tasks prompts programmatically via the MCP resource protocol.

## Development

```bash
# REPL
clj

# Lint
clj-kondo --lint src test
```

**Dependencies:**
- Requires local [mcp-clj](https://github.com/hugoduncan/mcp-clj) at `../mcp-clj/projects/server`

## Architecture

**Task Storage:**

The `.mcp-tasks` directory can optionally be a git repository for version control, but this is not required:

```
.mcp-tasks/          # Task tracking directory
├── .git/            # Optional: Version control for task history
├── tasks.ednl       # Active tasks (EDN lines format)
├── complete.ednl    # Completed task archive (EDN lines format)
└── prompts/         # Category-specific instructions (optional)
    └── feature.md
```

**Key Components:**
- `mcp_tasks.prompts/discover-categories` - Auto-discovers categories from filesystem (src/mcp_tasks/prompts.clj:29)
- `mcp_tasks.prompts/create-prompts` - Generates MCP prompts dynamically for each category (src/mcp_tasks/prompts.clj:85)
- `mcp_tasks.prompts/read-prompt-instructions` - Loads custom instructions from `.mcp-tasks/prompts/<category>.md` (src/mcp_tasks/prompts.clj:76)

**Status:** Alpha. Core functionality stable; API may evolve.

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for release notes and version history.

### Generating Changelog Locally

Install git-cliff:
```bash
# macOS
brew install git-cliff

# Or download from https://github.com/orhun/git-cliff/releases
```

Generate changelog:
```bash
# Preview unreleased changes
git cliff --unreleased

# Update CHANGELOG.md
git cliff -o CHANGELOG.md
```

---

**Issues & Feedback:** https://github.com/hugoduncan/mcp-tasks/issues
