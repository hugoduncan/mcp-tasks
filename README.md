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

# Initialize .mcp-tasks as a git repository
mkdir -p .mcp-tasks/tasks .mcp-tasks/complete .mcp-tasks/prompts
cd .mcp-tasks && git init && git commit --allow-empty -m "Initialize task tracking" && cd ..

# List available prompt templates
clojure -M:mcp-tasks --list-prompts

# Install prompt templates (optional)
clojure -M:mcp-tasks --install-prompts simple,clarify-task

# Create your first task
echo "- [ ] Add README badges for build status" > .mcp-tasks/tasks/simple.md

# Run in Claude Code
/mcp-tasks:next-simple
```

**[Installation Guide](doc/install.md)** • **[Workflow Documentation](doc/workflow.md)**

---

## What & Why

mcp-tasks enables you to manage development tasks in markdown files and have AI agents execute them. Unlike todo tools, mcp-tasks integrates task planning with execution—agents don't just track tasks, they complete them.

**Key Benefits:**
- **Persistent Planning**: Tasks survive across chat sessions in version-controlled markdown
- **Category-Based Organization**: Group tasks by type (features, bugfixes, refactoring) with custom execution strategies
- **Audit Trail**: Completed tasks automatically archived with full context
- **Git Integration**: Task completions include automated commits to your repository

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

Tasks live in `.mcp-tasks/tasks/<category>.md` as markdown checkboxes:

```markdown
- [ ] Implement user authentication with JWT tokens
- [ ] Add error handling to API endpoints
- [ ] Write integration tests for payment flow
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
- Read the first `- [ ]` task
- Analyze requirements in project context
- Implement the solution
- Commit changes to your repository
- Move completed task to `.mcp-tasks/complete/<category>.md`
- Mark task as `- [x]` in completion archive

### 3. Review and Iterate

```bash
# Check what was completed
cat .mcp-tasks/complete/simple.md

# Review the git commit
git log -1 --stat

# Add more tasks
echo "- [ ] Optimize database queries" >> .mcp-tasks/tasks/simple.md
```

### Real Workflow Example

```bash
# Add tasks for different categories
echo "- [ ] Add user profile endpoint" > .mcp-tasks/tasks/feature.md
echo "- [ ] Fix memory leak in worker process" > .mcp-tasks/tasks/bugfix.md
echo "- [ ] Extract validation logic to separate module" > .mcp-tasks/tasks/refactor.md

# Process tasks by priority
/mcp-tasks:next-bugfix      # Agent fixes memory leak, commits
/mcp-tasks:next-feature     # Agent adds endpoint, commits
/mcp-tasks:next-refactor    # Agent extracts validation, commits

# Check completion history
cat .mcp-tasks/complete/bugfix.md
```

See **[doc/workflow.md](doc/workflow.md)** for advanced patterns including git worktrees for parallel task execution.

## Configuration

### Custom Categories

Categories auto-discover from filenames in `.mcp-tasks/tasks/`. Create new categories by adding task files:

```bash
mkdir -p .mcp-tasks/tasks
echo "- [ ] First documentation task" > .mcp-tasks/tasks/docs.md
```

Now `/mcp-tasks:next-docs` is available.

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
- Initial steps: Read task from `.mcp-tasks/tasks/<category>.md`
- Middle steps: Custom instructions (from `.mcp-tasks/prompts/<category>.md`) or default implementation steps
- Final steps: Commit changes, move to `.mcp-tasks/complete/<category>.md`, update task tracking

See **[doc/workflow.md#category-specific-instructions](doc/workflow.md#category-specific-instructions)** for examples.

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

The `.mcp-tasks` directory is a separate git repository from your project, keeping task tracking independent:

```
.mcp-tasks/          # Separate git repository
├── .git/            # Task tracking history
├── tasks/           # Active tasks (- [ ] format)
│   ├── simple.md
│   ├── feature.md
│   └── bugfix.md
├── complete/        # Completed task archive (- [x] format)
│   ├── simple.md
│   └── feature.md
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
