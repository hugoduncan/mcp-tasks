# Installing mcp-tasks MCP Server

This guide describes how to install the mcp-tasks MCP server for use with various MCP clients.

## Prerequisites

- Clojure CLI tools installed

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

- **Server fails to start:** Verify that the `:git/sha` in `~/.clojure/deps.edn` is correct and accessible
- **Dependencies missing:** Ensure you have Clojure 1.12.3 or compatible version installed
- **Git access issues:** Make sure you can access GitHub and clone repositories
