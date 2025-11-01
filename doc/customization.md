# Customization

This guide describes how to customize mcp-tasks for your workflow.

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
