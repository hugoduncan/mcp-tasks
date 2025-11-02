# mcp-tasks

Task-based workflow management for AI agents via Model Context Protocol (MCP).

## What is mcp-tasks?

mcp-tasks enables you to manage development tasks in markdown files and have AI agents execute them. Unlike todo tools, mcp-tasks integrates task planning with execution—agents don't just track tasks, they complete them.

**Key Benefits:**
- **Persistent Planning**: Tasks survive across chat sessions in markdown files
- **Category-Based Organization**: Group tasks by type (features, bugfixes, refactoring) with custom execution strategies
- **Story-Based Development**: Break down epics into tasks with dedicated workflows
- **Audit Trail**: Completed tasks automatically archived with full context
- **Flexible Workflows**: Supports both git-tracked and standalone task management

For a deeper understanding, see **[Introduction to mcp-tasks](doc/introduction.md)**.

## Quick Start

### Installation

**Install native binaries (recommended):**

```bash
# Unix (Linux/macOS)
curl -sL https://raw.githubusercontent.com/hugoduncan/mcp-tasks/master/install | bash

# Windows (PowerShell)
iwr -useb https://raw.githubusercontent.com/hugoduncan/mcp-tasks/master/install.ps1 | iex
```

This installs:
- `mcp-tasks` CLI for terminal task management
- `mcp-tasks-server` MCP server for AI agent integration

See **[Installation Guide](doc/install.md)** for alternative installation methods (Clojure git dependency, building from source).

### Configuration

**Configure Claude Code:**

```bash
claude mcp add mcp-tasks -- /usr/local/bin/mcp-tasks-server
```

**Initialize task directories in your project:**

```bash
mkdir -p .mcp-tasks/prompts

# Optional: Initialize as git repository for version control
cd .mcp-tasks && git init && git commit --allow-empty -m "Initialize task tracking" && cd ..
```

### Your First Task

**Add a task:**

In Claude Code, use the `add-task` tool:
- category: `simple`
- title: `Add README badges for build status`

**Execute the task:**

```
/mcp-tasks:category-simple
```

The agent will:
- Read the task from `.mcp-tasks/tasks.ednl`
- Analyze requirements in project context
- Implement the solution
- Commit changes to your repository
- Move completed task to `.mcp-tasks/complete.ednl`

**Review:**

```bash
# Check what was completed
git log -1 --stat

# View completed tasks using select-tasks tool in Claude Code
```

## Core Concepts

**Tasks**: Units of work stored in `.mcp-tasks/tasks.ednl` as EDN records with fields like `:id`, `:category`, `:title`, `:description`, `:status`.

**Categories**: Task organization by type/workflow. Each category has a prompt file (e.g., `.mcp-tasks/prompts/simple.md`) defining execution steps. Built-in categories include `simple`, `medium`, `large`.

**Stories**: Large features broken down into multiple related tasks. Story workflows include refinement, task creation, and sequential execution.

**Prompts**: MCP prompts that guide agents through task execution (e.g., `/mcp-tasks:category-simple`). Each category has a corresponding prompt.

See **[Glossary](doc/glossary.md)** and **[Workflow Documentation](doc/workflow.md)** for complete details.

## Common Workflows

### Basic Task Workflow

```bash
# Add tasks using add-task tool in Claude Code
# category: "feature", title: "Add user profile endpoint"
# category: "bugfix", title: "Fix memory leak in worker process"

# Execute by category
/mcp-tasks:category-bugfix      # Agent fixes bug, commits
/mcp-tasks:category-feature     # Agent adds endpoint, commits

# Check completion history in .mcp-tasks/complete.ednl
```

### Story-Based Workflow

```bash
# Create a story using add-task tool
# category: "large", type: "story"
# title: "User Authentication System"
# description: "Implement JWT-based authentication..."

# Refine the story interactively
/mcp-tasks:refine-task "User Authentication System"

# Break down into tasks
/mcp-tasks:create-story-tasks "User Authentication System"

# Execute tasks sequentially
/mcp-tasks:execute-story-task "User Authentication System"
```

See **[Workflow Documentation](doc/workflow.md)** for advanced patterns including git worktrees and parallel task execution.

## Documentation

- **[Introduction](doc/introduction.md)** - Deep dive into how mcp-tasks helps you work with AI agents
- **[Installation Guide](doc/install.md)** - Complete installation instructions for all platforms
- **[Workflow Documentation](doc/workflow.md)** - Task execution workflows, story workflows, and advanced patterns
- **[Configuration](doc/config.md)** - Config file options, git integration, worktree management
- **[Customization](doc/customization.md)** - Custom categories, prompt templates, task metadata, MCP resources
- **[Command Line Interface](doc/command-line.md)** - CLI commands, output formats, and usage
- **[Development](doc/development.md)** - Development setup, architecture, testing, and building
- **[Glossary](doc/glossary.md)** - Terms and concepts
- **[Building from Source](doc/build.md)** - Build instructions for native binaries and JAR

## Status & License

**Status:** Alpha. Core functionality stable; API may evolve.

**License:** EPL-2.0 (see LICENSE file)

**Issues & Feedback:** https://github.com/hugoduncan/mcp-tasks/issues

**Changelog:** [CHANGELOG.md](CHANGELOG.md)
