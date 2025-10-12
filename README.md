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
mkdir -p .mcp-tasks/tasks .mcp-tasks/complete .mcp-tasks/prompts

# (Optional) Initialize as git repository for version control
cd .mcp-tasks && git init && git commit --allow-empty -m "Initialize task tracking" && cd ..

# List available prompt templates
clojure -M:mcp-tasks --list-prompts

# Install prompt templates (optional)
clojure -M:mcp-tasks --install-prompts simple,clarify-task

# Create your first task
echo "- [ ] Add README badges for build status" > .mcp-tasks/tasks/simple.md

# Or create a story for larger features
mkdir -p .mcp-tasks/stories
echo "# Add CI Pipeline" > .mcp-tasks/stories/ci-setup.md
echo "" >> .mcp-tasks/stories/ci-setup.md
echo "Set up GitHub Actions for automated testing and deployment" >> .mcp-tasks/stories/ci-setup.md

# Run in Claude Code
/mcp-tasks:next-simple
# Or for story-based development
/mcp-tasks:refine-story ci-setup
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
- Commit the task repo (if git mode enabled)

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

### Story-Based Workflows

For larger features that require multiple related tasks, use story workflows:

```bash
# Create a story describing the feature
cat > .mcp-tasks/stories/user-auth.md <<'EOF'
# User Authentication System

Implement JWT-based authentication with user registration, login, and password reset.

Requirements:
- User registration with email validation
- JWT token generation and validation
- Password hashing with bcrypt
- Password reset via email
EOF

# Refine the story interactively with agent
/mcp-tasks:refine-story user-auth

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
mkdir -p .mcp-tasks/tasks .mcp-tasks/complete
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

The `.mcp-tasks` directory can optionally be a git repository for version control, but this is not required:

```
.mcp-tasks/          # Task tracking directory
├── .git/            # Optional: Version control for task history
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
