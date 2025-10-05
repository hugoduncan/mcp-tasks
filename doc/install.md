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
