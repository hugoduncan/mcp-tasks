# Installing mcp-tasks MCP Server

This guide describes how to install the mcp-tasks MCP server for use with various MCP clients.

## Prerequisites

Binary installation requires no prerequisites (Linux/macOS only). For Clojure git dependency installation (alternative method, works on all platforms including Windows), you need Clojure CLI tools installed.

## Binary Installation (Linux/macOS Only)

The recommended installation method for Linux and macOS is using the pre-built native binaries, which provide the best performance and simplest setup.

**Quick Install (Unix/macOS):**
```bash
curl -fsSL https://raw.githubusercontent.com/hugoduncan/mcp-tasks/master/install | bash
```

**Note:** Windows native binaries are not currently supported due to file locking complexities. Windows users should use the Clojure git dependency installation method below.

For manual installation and troubleshooting, see the [Installation section in README.md](../README.md#installation). For building from source, see [build.md](build.md).

## Alternative: Clojure Git Dependency (All Platforms)

This method works on all platforms including Windows and is the recommended installation method for Windows users.

### Configure Global Clojure Alias

Add the following alias to your `~/.clojure/deps.edn` file:

```clojure
{:aliases
 {:mcp-tasks
  {:replace-paths []
   :replace-deps {org.hugpduncan/mcp-tasks
                  {:git/url "https://github.com/hugoduncan/mcp-tasks"
                   :git/tag "v0.1.155"
                   :git/sha "33125c03c29a7719ec71088495fcd940f3be2df1"}
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
