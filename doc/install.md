# Installing mcp-tasks MCP Server

This guide describes how to install the mcp-tasks MCP server for use with various MCP clients.

## Prerequisites

- Clojure CLI tools installed

## Babashka Installation (Optional)

The mcp-tasks CLI can run under Babashka for faster startup times and better scripting support. This is optional but recommended for CLI usage.

### Minimum Version

- **Required:** Babashka 1.0.0 or later (for full Malli support)
- **Current Latest:** 1.12.207
- **Malli Support:** Added in v0.8.9 (July 2022)

### Installation Instructions

**macOS:**
```bash
# Using Homebrew
brew install borkdude/brew/babashka

# Or using the installer script
bash <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)
```

**Linux:**
```bash
# Using the installer script
bash <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)

# Or download binary directly
wget https://github.com/babashka/babashka/releases/latest/download/babashka-$(uname -s | tr '[:upper:]' '[:lower:]')-amd64.tar.gz
tar -xzf babashka-*.tar.gz
sudo mv bb /usr/local/bin/
```

**Windows:**
```powershell
# Using Scoop
scoop install babashka

# Or download the Windows installer from:
# https://github.com/babashka/babashka/releases
```

### bb.edn Setup

After installing Babashka, the mcp-tasks project includes a `bb.edn` configuration file that enables CLI usage. No additional setup is required - just use `bb` commands instead of `clojure` commands.

**Performance:**

Babashka provides dramatically faster startup times compared to JVM Clojure:
- **JVM Clojure:** ~2.2 seconds
- **Babashka (help commands):** ~66ms (0.066 seconds)
- **Babashka (with validation):** ~390ms first run, ~330ms cached
- **Improvement:** ~33x faster for help commands

The CLI uses lazy-loading optimizations to defer loading validation libraries until needed. This means help commands and simple queries are nearly instant, while commands that validate data pay a small one-time cost.

This makes babashka ideal for CLI operations, scripting, and interactive task management.

**Usage Examples:**

```bash
# List available babashka tasks
bb tasks

# Use CLI commands with bb prefix
bb list --category simple
bb add --category simple --title "New task"
bb show --task-id 42
bb complete --task-id 42
bb update --task-id 42 --status in-progress
bb delete --task-id 42

# Or use the main CLI entry point
bb cli list --format json
bb cli add --category feature --title "Add endpoint"
```

**When to Use Babashka vs JVM:**
- **Babashka:** CLI operations, scripting, interactive task management
- **JVM Clojure:** MCP server (requires JVM), development/testing with full Clojure toolchain

**Important:** The MCP server requires JVM Clojure (`clojure -X:mcp-tasks`). Babashka support is for CLI operations only.

**JSON Library Choice:**

The mcp-tasks CLI uses `cheshire.core` for JSON parsing and serialization. This choice enables babashka compatibility:

- **Built into babashka**: cheshire is included in the babashka distribution, requiring no additional dependencies
- **JVM compatible**: Also works seamlessly in standard Clojure when added to deps.edn
- **API compatible**: Provides the same API as clojure.data.json for our usage (`parse-string`/`read-str` with `:key-fn`, `generate-string`/`write-str`)
- **Performance**: Enables the CLI to run under babashka with ~40x faster startup times compared to JVM Clojure

This architectural decision allows the same codebase to run efficiently in both JVM and babashka environments without code changes.

### Platform Considerations

- **Windows:** The bb.bat wrapper is automatically created during installation
- **All Platforms:** The bb.edn uses forward slashes for paths, which work correctly on all platforms including Windows
- **Path separators:** Babashka handles platform-specific path separators automatically

### Verifying Installation

```bash
# Check babashka version (should be 1.0.0 or later)
bb --version

# Test with mcp-tasks
bb list --help
```

### Standalone Executable (Uberscript)

For easier distribution or system-wide installation, you can build a standalone executable using babashka's uberscript feature. This bundles all dependencies into a single file.

**Building the Uberscript:**

```bash
# From the mcp-tasks source directory
cd /path/to/mcp-tasks
bb build-uberscript

# This creates a 48KB executable: ./mcp-tasks
```

**Installation Options:**

```bash
# Option 1: Use locally
./mcp-tasks list --category simple

# Option 2: Install to local bin directory
mkdir -p ~/bin
cp mcp-tasks ~/bin/
# Add ~/bin to PATH if not already there

# Option 3: Install system-wide
sudo cp mcp-tasks /usr/local/bin/
mcp-tasks list --format human
```

**Uberscript Benefits:**

- **Single file distribution** - No need to install source code or maintain dependencies
- **Fast startup** - Same ~66ms performance as `bb` commands for help operations
- **Portable** - Copy to any system with babashka installed
- **No classpath configuration** - All dependencies bundled in the executable
- **Version locking** - Executable is built from specific source version

**Performance:**

The uberscript maintains the optimized lazy-loading performance:
- **Help commands:** ~66ms (instant feel)
- **Commands with validation:** ~390ms first run (loads Malli), ~330ms cached
- **vs JVM:** ~33x faster for help commands

**Requirements:**

- Babashka must be installed on the target system (the `#!/usr/bin/env bb` shebang requires it)
- The executable is platform-independent (runs on macOS, Linux, Windows with babashka installed)

**Updating:**

When you want to update to a newer version:

```bash
# Pull latest source
cd /path/to/mcp-tasks
git pull

# Rebuild the uberscript
bb build-uberscript

# Reinstall
sudo cp mcp-tasks /usr/local/bin/
```

**Distribution:**

The uberscript can be distributed to other users/systems:

```bash
# Copy to another machine
scp mcp-tasks user@remote-host:~/bin/

# Or commit to your dotfiles repository
cp mcp-tasks ~/dotfiles/bin/mcp-tasks
```

**Note:** While the uberscript provides a convenient CLI, the MCP server still requires JVM Clojure via `clojure -X:mcp-tasks`.

## Setup

### Configure Global Clojure Alias

Add the following alias to your `~/.clojure/deps.edn` file:

```clojure
{:aliases
 {:mcp-tasks
  {:replace-paths []
   :replace-deps {org.hugpduncan/mcp-tasks
                  {:git/url "https://github.com/hugoduncan/mcp-tasks"
                   :git/sha "2d82cffb53e3f03deced02365f5be314c7377f0b"}
                  org.clojure/clojure {:mvn/version "1.12.3"}}
   :exec-fn mcp-tasks.main/start}}}
```

## Client Configuration

### Claude Code

Add the mcp-tasks server using the Claude Code CLI:

```bash
claude mcp add mcp-tasks -- $(which clojure) -X:mcp-tasks
```

### Claude Desktop

Add to your Claude Desktop MCP settings:

**macOS:** `~/Library/Application Support/Claude/claude_desktop_config.json`

**Windows:** `%APPDATA%\Claude\claude_desktop_config.json`

**Linux:** `~/.config/Claude/claude_desktop_config.json`

```json
{
  "mcpServers": {
    "mcp-tasks": {
      "command": "clojure",
      "args": ["-X:mcp-tasks"]
    }
  }
}
```

### Codex

Add to your Codex MCP configuration file (location varies by installation):

```json
{
  "mcpServers": {
    "mcp-tasks": {
      "command": "clojure",
      "args": ["-X:mcp-tasks"]
    }
  }
}
```

For configuration options, see [Configuration](config.md).

## Setup

### Initialize .mcp-tasks as a Git Repository (Optional)

The `.mcp-tasks` directory can optionally be its own separate git
repository to track task history independently from your project code:

**Note:** This step is optional. If you skip git initialization,
mcp-tasks will operate in non-git mode, managing task files without
version control.

```bash
# In your project root
mkdir -p .mcp-tasks/tasks .mcp-tasks/complete .mcp-tasks/prompts
cd .mcp-tasks
git init
git add .
git commit -m "Initialize mcp-tasks repository"
cd ..

# Add .mcp-tasks to your project's .gitignore
echo ".mcp-tasks/" >> .gitignore
```

**Why a separate repository?**
- Task tracking commits don't clutter your project history
- Task files are version controlled and shareable
- Completed task archive provides an audit trail
- Each project can have its own task repository

For prompt customization and management, see [Customization](customization.md).

## Verification

After configuration, restart your MCP client. The mcp-tasks server should be available with prompts for managing tasks across different categories.

You can verify the installation by:
1. Checking that the server appears in your client's MCP server list
2. Listing available prompts (see [Customization](customization.md) for prompt management commands)
3. Running a simple task command like `/mcp-tasks:next-simple`

## Troubleshooting

- **Server fails to start:** Verify that the `:git/sha` in
  `~/.clojure/deps.edn` is correct and accessible
- **Dependencies missing:** Ensure you have Clojure 1.12.3 or compatible
  version installed
- **Git access issues:** Make sure you can access GitHub and clone repositories
