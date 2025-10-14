# mcp-tasks

Task-based workflow management for AI agents via Model Context Protocol (MCP).

## Quick Start

```bash
# Add to ~/.clojure/deps.edn
{:aliases
 {:mcp-tasks
  {:replace-paths []
   :replace-deps {org.hugpduncan/mcp-tasks
                  {:git/url "https://github.com/hugoduncan/mcp-tasks"
                   :git/sha "2d82cffb53e3f03deced02365f5be314c7377f0b"}
                  org.clojure/clojure {:mvn/version "1.12.3"}}
   :exec-fn mcp-tasks.main/start}}}

# Configure Claude Code
claude mcp add mcp-tasks -- $(which clojure) -X:mcp-tasks

# Initialize task directories
mkdir -p .mcp-tasks/prompts

# (Optional) Initialize as git repository for version control
cd .mcp-tasks && git init && git commit --allow-empty -m "Initialize task tracking" && cd ..

# List available prompt templates
clojure -M:mcp-tasks --list-prompts

# Install prompt templates (optional)
clojure -M:mcp-tasks --install-prompts simple,clarify-task

# Add your first task using the MCP tool
# In Claude Code, use the add-task tool:
# - category: "simple"
# - task-text: "Add README badges for build status"

# Or add a story for larger features
# - category: "large" 
# - type: "story"
# - task-text: "Add CI Pipeline"

# Execute tasks in Claude Code
/mcp-tasks:next-simple
# Or for story-based development
/mcp-tasks:create-story-tasks "Add CI Pipeline"
```

**[Installation Guide](doc/install.md)** • **[Workflow Documentation](doc/workflow.md)**

---

## What & Why

mcp-tasks enables you to manage development tasks in markdown files and have AI agents execute them. Unlike todo tools, mcp-tasks integrates task planning with execution—agents don't just track tasks, they complete them.

**Key Benefits:**
- **Persistent Planning**: Tasks survive across chat sessions in markdown files
- **Category-Based Organization**: Group tasks by type (features, bugfixes, refactoring) with custom execution strategies
- **Story-Based Development**: Break down epics into tasks with dedicated workflows for refinement and execution
- **Audit Trail**: Completed tasks automatically archived with full context
- **Flexible Workflows**: Supports both git-tracked and standalone task management

**When to Use:**
- Complex projects requiring systematic task execution
- Multi-agent workflows with parallel task streams
- Projects where task history and planning context matter
- Teams coordinating agent-driven development

**vs. clojure-mcp:** This is a task management layer built on the [clojure-mcp](https://github.com/hugoduncan/mcp-clj) server library. clojure-mcp provides MCP infrastructure; mcp-tasks adds workflow automation on top.

## Installation

See **[doc/install.md](doc/install.md)** for complete setup instructions for Claude Code, Claude Desktop, and other MCP clients.

**TL;DR:**
- Add `:mcp-tasks` alias to `~/.clojure/deps.edn` with git coordinates
- Configure your MCP client to run `clojure -X:mcp-tasks`

## Core Usage

### 1. Create Task Files

Tasks are stored in `.mcp-tasks/tasks.ednl` as EDN records:

```clojure
{:id 1 :category "simple" :title "Implement user authentication" :status :open ...}
{:id 2 :category "simple" :title "Add error handling to API endpoints" :status :open ...}
{:id 3 :category "simple" :title "Write integration tests" :status :open ...}
```

### 2. Run Task Prompts

Execute the first incomplete task in a category:

```
/mcp-tasks:next-<category>
```

Example:
```
/mcp-tasks:next-simple
```

The agent will:
- Read the first task with matching category from `tasks.ednl`
- Analyze requirements in project context
- Implement the solution
- Commit changes to your repository
- Move completed task to `complete.ednl` with `:status :closed`
- Commit the task repo (if git mode enabled)

### 3. Review and Iterate

```bash
# Check what was completed
# Use the select-tasks tool to view completed tasks in .mcp-tasks/complete.ednl

# Review the git commit
git log -1 --stat

# Add more tasks using the add-task tool
# In Claude Code: add-task with category "simple" and task-text "Optimize database queries"
```

### Real Workflow Example

```bash
# Add tasks for different categories using the add-task tool
# In Claude Code:
# - add-task category: "feature", task-text: "Add user profile endpoint"
# - add-task category: "bugfix", task-text: "Fix memory leak in worker process"  
# - add-task category: "refactor", task-text: "Extract validation logic to separate module"

# Process tasks by priority
/mcp-tasks:next-bugfix      # Agent fixes memory leak, commits
/mcp-tasks:next-feature     # Agent adds endpoint, commits
/mcp-tasks:next-refactor    # Agent extracts validation, commits

# Check completion history
# View completed tasks in .mcp-tasks/complete.ednl
```

See **[doc/workflow.md](doc/workflow.md)** for advanced patterns including git worktrees for parallel task execution.

### Story-Based Workflows

For larger features that require multiple related tasks, use story workflows:

```bash
# Create a story using the add-task tool
# In Claude Code:
# add-task with:
#   category: "large"
#   type: "story"
#   task-text: "User Authentication System"
#   description: "Implement JWT-based authentication with user registration, login, and password reset.
#
# Requirements:
# - User registration with email validation
# - JWT token generation and validation
# - Password hashing with bcrypt
# - Password reset via email"

# Refine the story interactively with agent
/mcp-tasks:refine-story "User Authentication System"

# Break down into executable tasks
/mcp-tasks:create-story-tasks user-auth

# Execute tasks one by one
/mcp-tasks:execute-story-task user-auth
```

The agent will:
- Guide you through story refinement with feedback loops
- Break the story into categorized tasks
- Execute tasks sequentially, tracking progress
- Optionally manage feature branches automatically

See **[doc/workflow.md#story-workflows](doc/workflow.md#story-workflows)** for complete documentation.

## Configuration

### Git Integration

mcp-tasks supports two workflows:

**1. Zero-Config Auto-Detection (Default)**

The server automatically detects if `.mcp-tasks/.git` exists and enables git features accordingly. No configuration needed:

```bash
# Git mode: Auto-enabled if .mcp-tasks is a git repository
cd .mcp-tasks && git init && cd ..

# Non-git mode: Auto-enabled if no .mcp-tasks/.git directory
# Just create the directory structure - no git needed
mkdir -p .mcp-tasks/prompts
```

**2. Explicit Configuration (Optional)**

Override auto-detection by creating `.mcp-tasks.edn` in your project root (sibling to `.mcp-tasks/`):

```clojure
{:use-git? true}   ; Force git mode on
{:use-git? false}  ; Force git mode off
```

**Behavior by Mode:**

| Feature | Git Mode | Non-Git Mode |
|---------|----------|--------------|
| Task tracking | ✓ | ✓ |
| Task completion | ✓ | ✓ |
| Automated commits | ✓ | ✗ |
| Modified file list | ✓ | ✗ |

**Precedence:** Explicit config (`.mcp-tasks.edn`) overrides auto-detection.

**Note:** Your project's git repository is independent of `.mcp-tasks` git tracking. The `:use-git?` setting only affects task tracking commits within `.mcp-tasks/`.

### Custom Categories

Categories are defined by creating prompt files in `.mcp-tasks/prompts/`. Create new categories by adding prompt templates:

```bash
mkdir -p .mcp-tasks/prompts
cat > .mcp-tasks/prompts/docs.md <<'EOF'
---
description: Execute documentation tasks
---
# Documentation Task Workflow
[Your custom execution steps here]
EOF
```

Now `/mcp-tasks:next-docs` is available for tasks with `category: "docs"`.

### Category-Specific Instructions

Override default task execution by creating `.mcp-tasks/prompts/<category>.md`:

```bash
# Install built-in prompt templates
clojure -M:mcp-tasks --install-prompts

# Or create custom prompts
mkdir -p .mcp-tasks/prompts
cat > .mcp-tasks/prompts/feature.md <<'EOF'
- Review existing code architecture
- Design the feature following project patterns
- Write tests first (TDD approach)
- Implement the feature
- Update relevant documentation
EOF
```

Available built-in templates (use `--list-prompts` to see all):
- `simple` - Basic task execution with standard implementation steps
- `clarify-task` - Transform vague instructions into detailed specifications

The prompt file provides custom execution instructions for the category, replacing the default implementation approach.

**Prompt Structure:**
- Initial steps: Read next task from `tasks.ednl` using `select-tasks` tool
- Middle steps: Custom instructions (from `.mcp-tasks/prompts/<category>.md`) or default implementation steps
- Final steps: Commit changes, mark task complete using `complete-task` tool (moves to `complete.ednl`), update task tracking

See **[doc/workflow.md#category-specific-instructions](doc/workflow.md#category-specific-instructions)** for examples.

## MCP Resources

mcp-tasks exposes its execution prompts as MCP resources, enabling advanced use cases like prompt composition and inspection.

### Prompt Resources

Each category and story workflow has an associated prompt resource accessible via the `prompt://` URI scheme:

**Category prompts:**
```
prompt://next-<category>
```

Examples:
- `prompt://next-simple` - Simple task execution prompt
- `prompt://next-feature` - Feature task execution prompt
- `prompt://next-bugfix` - Bugfix task execution prompt

**Story prompts:**
```
prompt://refine-story
prompt://create-story-tasks
prompt://execute-story-task
```

### Resource Format

Prompt resources include YAML frontmatter with metadata:

```markdown
---
description: Execute simple tasks with basic workflow
---

Please complete the next simple task following these steps:
[... prompt content ...]
```

**Frontmatter fields:**
- `description` (string) - Brief explanation of the prompt's purpose
- Additional metadata may be present in custom prompts

### Accessing Resources

**List all prompt resources:**

Most MCP clients provide a way to list available resources. The exact method depends on your client.

**Read a prompt resource:**

Use your MCP client's resource reading capability with the `prompt://` URI. For example, in Claude Code you might use tools to inspect prompt resources programmatically.

### Use Cases

**1. Prompt Composition**

Build custom workflows by combining or referencing existing prompts:

```markdown
# Custom prompt that references another
Execute the feature task, then follow the review process from prompt://review-checklist
```

**2. Prompt Inspection**

Understand what a category prompt does before executing tasks:
- Read `prompt://next-simple` to see the simple task workflow
- Compare different category prompts to choose appropriate categories

**3. Documentation**

Generate workflow documentation by extracting prompt content and descriptions.

**4. Tooling Integration**

Build tools that discover and utilize mcp-tasks prompts programmatically via the MCP resource protocol.

## Development

```bash
# REPL
clj

# Lint
clj-kondo --lint src test
```

**Dependencies:**
- Requires local [mcp-clj](https://github.com/hugoduncan/mcp-clj) at `../mcp-clj/projects/server`

## Architecture

**Task Storage:**

The `.mcp-tasks` directory can optionally be a git repository for version control, but this is not required:

```
.mcp-tasks/          # Task tracking directory
├── .git/            # Optional: Version control for task history
├── tasks.ednl       # Active tasks (EDN lines format)
├── complete.ednl    # Completed task archive (EDN lines format)
└── prompts/         # Category-specific instructions (optional)
    └── feature.md
```

**Key Components:**
- `mcp_tasks.prompts/discover-categories` - Auto-discovers categories from filesystem (src/mcp_tasks/prompts.clj:29)
- `mcp_tasks.prompts/create-prompts` - Generates MCP prompts dynamically for each category (src/mcp_tasks/prompts.clj:85)
- `mcp_tasks.prompts/read-prompt-instructions` - Loads custom instructions from `.mcp-tasks/prompts/<category>.md` (src/mcp_tasks/prompts.clj:76)

**Status:** Alpha. Core functionality stable; API may evolve.

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for release notes and version history.

### Generating Changelog Locally

Install git-cliff:
```bash
# macOS
brew install git-cliff

# Or download from https://github.com/orhun/git-cliff/releases
```

Generate changelog:
```bash
# Preview unreleased changes
git cliff --unreleased

# Update CHANGELOG.md
git cliff -o CHANGELOG.md
```

---

**Issues & Feedback:** https://github.com/hugoduncan/mcp-tasks/issues
