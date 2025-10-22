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

## Workflow Configuration

The mcp-tasks server supports both git-based and non-git workflows for managing tasks. The server automatically detects which mode to use.

### Auto-Detection

By default, mcp-tasks automatically detects whether to use git:
- If `.mcp-tasks/.git` directory exists → git mode enabled
- If `.mcp-tasks/.git` directory does not exist → git mode disabled

This means you can start using mcp-tasks immediately without any configuration.

### Explicit Configuration (Optional)

You can override auto-detection by creating a `.mcp-tasks.edn` file in your project root (sibling to the `.mcp-tasks/` directory):

```clojure
{:use-git? true}   ; Force git mode on
{:use-git? false}  ; Force git mode off
```

Explicit configuration takes precedence over auto-detection.

### Git Workflow

When git mode is enabled:
- Task completion includes git commit instructions
- The `complete-task` tool returns modified file paths for git operations
- You can track task history using git commits

**Setup:**
```bash
cd .mcp-tasks
git init
```

### Non-Git Workflow

When git mode is disabled:
- Task completion focuses on file operations only
- No git instructions or commit guidance included
- Tasks are tracked in markdown files without version control

**Setup:**
No additional setup required - just use the `.mcp-tasks/` directory without initializing git.

### When to Use Each Workflow

**Use git workflow when:**
- You want version history of your task changes
- You're working in a team and want to share task state
- You want to track when and why tasks were completed
- Your main project repository is independent (mcp-tasks git repo is separate)

**Use non-git workflow when:**
- You prefer simplicity and don't need version history
- You're working solo and tasks are ephemeral
- You want minimal overhead

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


## Command Line Options

The mcp-tasks server supports command line flags for managing task prompts:

### List Available Prompts

Display all available prompt templates with their descriptions:

```bash
clojure -M:mcp-tasks --list-prompts
```

### Install Prompt Templates

Install prompt templates to `.mcp-tasks/prompts/` directory:

```bash
# Install all available prompts
clojure -M:mcp-tasks --install-prompts

# Install specific prompts (comma-separated)
clojure -M:mcp-tasks --install-prompts simple,clarify-task
```

The `--install-prompts` command:
- Skips files that already exist (exit code 0)
- Warns if a prompt is not found or installation fails (exit code 1)
- Does not start the MCP server

## Verification

After configuration, restart your MCP client. The mcp-tasks server should be available with prompts for managing tasks across different categories.

You can verify the installation by:
1. Checking that the server appears in your client's MCP server list
2. Listing available prompts using `clojure -M:mcp-tasks --list-prompts`
3. Running a simple task command like `/mcp-tasks:next-simple`

## Troubleshooting

- **Server fails to start:** Verify that the `:git/sha` in
  `~/.clojure/deps.edn` is correct and accessible
- **Dependencies missing:** Ensure you have Clojure 1.12.3 or compatible
  version installed
- **Git access issues:** Make sure you can access GitHub and clone repositories
