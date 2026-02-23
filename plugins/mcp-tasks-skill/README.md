# MCP-Tasks Skill Plugin

Guidance for using the mcp-tasks MCP server for task and story management in Claude Code.

## Overview

This Claude Code skill plugin provides comprehensive documentation and usage patterns for the [mcp-tasks](https://github.com/hugoduncan/mcp-tasks) MCP server. It helps you work effectively with task-based workflows, story management, and category-driven execution.

**What this plugin provides:**
- Documentation of all mcp-tasks tools (add-task, select-tasks, complete-task, etc.)
- Reference for available prompts (execute-task, create-story-tasks, refine-task, etc.)
- Common workflow examples
- Best practices for task and story management
- Automatic MCP server configuration and installation

## Installation

### From Claude Code Marketplace (Recommended)

```bash
/plugin install hugoduncan/mcp-tasks
```

Installing the plugin automatically configures the mcp-tasks MCP server in Claude Code — no separate `claude mcp add` step needed.

**First use:** If `mcp-tasks-server` is not already on your PATH, it is automatically downloaded and installed to `~/.local/bin/mcp-tasks-server` when the server first starts.

**Supported platforms:** Linux (amd64), macOS (x86_64 and Apple Silicon via universal binary). Windows is not supported.

### From Local Path (Development/Testing)

```bash
/plugin install /path/to/mcp-tasks/plugins/mcp-tasks-skill
```

## Initialize Task Directories

After installing the plugin, initialize task directories in your project:

```bash
mkdir -p .mcp-tasks/category-prompts

# Optional: Initialize as git repository for version control
cd .mcp-tasks && git init && git commit --allow-empty -m "Initialize task tracking" && cd ..
```

## Manual Server Installation (Alternative)

If you prefer to install the server binary explicitly rather than relying on auto-install:

```bash
# Download and install the latest release
curl -fsSL https://github.com/hugoduncan/mcp-tasks/releases/latest/download/mcp-tasks-server-$(uname -s | tr '[:upper:]' '[:lower:]')-$(uname -m) -o ~/.local/bin/mcp-tasks-server
chmod +x ~/.local/bin/mcp-tasks-server
```

See the [mcp-tasks Installation Guide](https://github.com/hugoduncan/mcp-tasks#installation) for complete setup instructions.

## Usage

Once installed, the skill provides reference documentation for mcp-tasks workflows. Access it as you would any Claude Code skill.

### What's Included

The skill documents:

**MCP Tools:**
- `select-tasks` - Query tasks with flexible filtering
- `add-task` - Create new tasks
- `update-task` - Modify existing task fields
- `complete-task` - Mark tasks as complete
- `delete-task` - Delete tasks
- `work-on` - Set up task execution environment
- `execution-state` - Manage execution state tracking

**Prompts:**
- `execute-task` - Execute a task by selection criteria
- `refine-task` - Interactively refine task specifications
- `next-<category>` - Execute next task for a specific category
- `create-story-tasks` - Break down stories into tasks
- `execute-story-child` - Execute next task from a story
- `review-story-implementation` - Review completed story
- `complete-story` - Mark story as complete and archive
- `create-story-pr` - Create pull request for story

**Resources:**
- Category instruction resources
- Execution state resource

**Common Workflows:**
- Executing simple tasks
- Working on stories
- Refining tasks before execution
- Managing task categories

See the full skill content for detailed documentation of each tool, prompt, and workflow pattern.

## Example Workflows

### Execute a Simple Task

```
/mcp-tasks:next-simple
```

### Work on a Story

```bash
# 1. Create tasks from story
/mcp-tasks:create-story-tasks 59

# 2. Execute tasks one by one
/mcp-tasks:execute-story-child 59

# 3. Review implementation
/mcp-tasks:review-story-implementation 59

# 4. Complete story
/mcp-tasks:complete-story 59
```

### Refine Before Executing

```
/mcp-tasks:refine-task 123
/mcp-tasks:execute-task task-id=123
```

## Programmatic Tool Calling (PTC)

This plugin's MCP server tools are configured with `allowed_callers` for Programmatic Tool Calling readiness. When Anthropic enables PTC for MCP connector tools, the server will support automated tool invocation without additional configuration.

## Documentation & Support

- **mcp-tasks Repository:** https://github.com/hugoduncan/mcp-tasks
- **Installation Guide:** https://github.com/hugoduncan/mcp-tasks#installation
- **Workflow Documentation:** https://github.com/hugoduncan/mcp-tasks/blob/main/doc/workflow.md
- **Claude Code Plugins:** https://docs.claude.com/en/docs/claude-code/plugins.md
- **Issues & Feedback:** https://github.com/hugoduncan/mcp-tasks/issues

## License

This plugin inherits the license from the parent mcp-tasks project.

## Version

1.0.0
