# MCP-Tasks Skill Plugin

Guidance for using the mcp-tasks MCP server for task and story management in Claude Code.

## Overview

This Claude Code skill plugin provides comprehensive documentation and usage patterns for the [mcp-tasks](https://github.com/hugoduncan/mcp-tasks) MCP server. It helps you work effectively with task-based workflows, story management, and category-driven execution.

**What this plugin provides:**
- Documentation of all mcp-tasks tools (add-task, select-tasks, complete-task, etc.)
- Reference for available prompts (execute-task, create-story-tasks, refine-task, etc.)
- Common workflow examples
- Best practices for task and story management

**Important:** This is a *skill plugin* that provides guidance and documentation. It requires the separate mcp-tasks MCP server to be installed and configured.

## Prerequisites

**Required:** The mcp-tasks MCP server must be installed and configured before using this plugin.

See the [mcp-tasks Installation Guide](https://github.com/hugoduncan/mcp-tasks#installation) for complete setup instructions.

### Quick Server Setup

If you haven't installed the mcp-tasks server yet:

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

For detailed installation instructions and configuration options, see the [mcp-tasks README](https://github.com/hugoduncan/mcp-tasks).

## Installation

### From Claude Code Marketplace (Recommended)

```bash
/plugin install hugoduncan/mcp-tasks
```

This installs the mcp-tasks-skill plugin, which provides comprehensive documentation for the mcp-tasks MCP server.

**Note:** This plugin is part of the mcp-tasks marketplace package. Installing via `/plugin install hugoduncan/mcp-tasks` automatically includes this skill plugin.

### From Local Path (Development/Testing)

```bash
/plugin install /path/to/mcp-tasks/plugins/mcp-tasks-skill
```

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
