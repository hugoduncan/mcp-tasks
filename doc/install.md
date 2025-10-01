# Installing mcp-tasks MCP Server

This guide describes how to install the mcp-tasks MCP server for use with various MCP clients.

## Prerequisites

- Clojure CLI tools installed
- Git (to clone the repository)

## Setup

### 1. Clone the Repository

```bash
git clone https://github.com/hugoduncan/mcp-tasks.git ~/projects/hugoduncan/mcp-tasks
```

### 2. Configure Global Clojure Alias

Add the following alias to your `~/.clojure/deps.edn` file:

```clojure
{:aliases
 {:mcp-tasks
  {:replace-paths []
   :replace-deps {org.hugpduncan/mcp-tasks
                  {:local/root "~/projects/hugoduncan/mcp-tasks"}
                  org.clojure/clojure {:mvn/version "1.12.3"}}
   :exec-fn mcp-tasks.main/start}}}
```

**Note:** Adjust the `:local/root` path if you cloned the repository to a different location.

## Client Configuration

### Claude Code

Add to your Claude Code MCP settings (typically `~/.config/claude-code/mcp-config.json`):

```json
{
  "mcpServers": {
    "mcp-tasks": {
      "command": "clojure",
      "args": ["-M:mcp-tasks"]
    }
  }
}
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
      "args": ["-M:mcp-tasks"]
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
      "args": ["-M:mcp-tasks"]
    }
  }
}
```

## Verification

After configuration, restart your MCP client. The mcp-tasks server should be available with prompts for managing tasks across different categories.

You can verify the installation by:
1. Checking that the server appears in your client's MCP server list
2. Listing available prompts (they should include task management categories)
3. Running a simple task command like `/mcp-tasks:next-simple`

## Troubleshooting

- **Server fails to start:** Verify that the `:local/root` path in `~/.clojure/deps.edn` points to the correct location
- **Dependencies missing:** Ensure you have Clojure 1.12.3 or compatible version installed
- **Permission issues:** Make sure the mcp-tasks directory and all files are readable by your user
