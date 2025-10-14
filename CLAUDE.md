# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

@doc/glossary.md

## Project Overview

An MCP (Model Context Protocol) server that provides task-based workflow management for agents. Tasks are organized into configurable categories, each with its own execution instructions.

## Architecture

**Task Storage Structure:**
- `.mcp-tasks/tasks.ednl` - All incomplete tasks stored as EDN records
- `.mcp-tasks/complete.ednl` - All completed tasks archive
- `.mcp-tasks/prompts/<category-name>.md` - Category-specific execution prompts

**Task File Format:**
Tasks are stored in EDNL (EDN Lines) format where each line is a valid EDN map representing a Task record. The Task schema (defined in `src/mcp_tasks/schema.clj`) includes:

```clojure
{:id            ;; int - unique task identifier
 :parent-id     ;; int or nil - optional parent task reference
 :status        ;; :open | :closed | :in-progress | :blocked
 :title         ;; string - task title
 :description   ;; string - detailed task description
 :design        ;; string - design notes
 :category      ;; string - execution category (simple, medium, large, etc.)
 :type          ;; :task | :bug | :feature | :story | :chore
 :meta          ;; map - arbitrary string key-value metadata
 :relations     ;; vector of Relation maps
}
```

**Relation Schema:**
Task relationships are defined as:

```clojure
{:id         ;; int - relation identifier
 :relates-to ;; int - target task ID
 :as-type    ;; :blocked-by | :related | :discovered-during
}
```

**Dependencies:**
- Depends on local `mcp-clj` server library at `../mcp-clj/projects/server`
- Ensure the sibling mcp-clj repository is available for development

## Story-Based Workflow

The system provides story support for managing larger features or initiatives that span multiple tasks:

**Story Storage Structure:**
- `.mcp-tasks/story/stories/<story-name>.md` - Active story descriptions
- `.mcp-tasks/story/story-tasks/<story-name>-tasks.md` - Task breakdown for each story
- `.mcp-tasks/story/complete/<story-name>.md` - Completed stories archive
- `.mcp-tasks/story/story-tasks-complete/<story-name>-tasks.md` - Completed task lists archive
- `.mcp-tasks/story/prompts/<story-name>.md` - Custom story-specific prompts (optional)

**Story Workflow:**

1. **Refine Story** - Interactively improve story clarity
   - Collaborative refinement of story requirements
   - Improves clarity, completeness, and actionability

2. **Create Story Tasks** - Break down a story into categorized, executable tasks
   - Reads story from `.mcp-tasks/story/stories/<story-name>.md`
   - Creates task breakdown with STORY prefix and CATEGORY metadata
   - Writes tasks to `.mcp-tasks/story/story-tasks/<story-name>-tasks.md`

3. **Execute Story Task** - Execute the next incomplete task from a story
   - Finds first incomplete task using `next-story-task` tool
   - Adds task to appropriate category queue based on CATEGORY metadata
   - Executes using category-specific workflow (e.g., simple, medium, large)
   - Marks story task as complete after successful execution

4. **Review Story Implementation** - Review completed implementation
   - Analyzes implementation against story requirements
   - Checks code quality, structure, and best practices
   - Optionally creates additional tasks for improvements

5. **Complete Story** - Archive completed stories
   - Moves story and tasks to archive directories
   - Preserves implementation history for reference

**Story Task Format:**
Story tasks are stored in markdown format at `.mcp-tasks/story/story-tasks/<story-name>-tasks.md`:

```markdown
- [ ] STORY: <story-name> - <brief task title>
  <additional task details on continuation lines>
  <more details as needed>

Part of story: @path-to-story-file
CATEGORY: <category>
```

When executed, story tasks are added to the category queue in `.mcp-tasks/tasks.ednl` as regular Task records with `parent-id` linking to the story.

**Branch Management:**
Story execution includes automatic branch management:
- Creates `<story-name>` branch if not already on it
- Keeps all story tasks on the same branch
- Manual merge/push after story completion

**Story Tools:**
- `next-story-task` - Get next incomplete task from a story
- `complete-story-task` - Mark a story task as complete
- `complete-story` - Archive completed story and tasks

## Development Commands

**REPL:**
```bash
clj
```

**Linting:**
```bash
clj-kondo --lint src test --fail-level warning
```

**Testing:**
```bash
# Run unit tests (default)
clojure -M:dev:test --focus :unit

# Run integration tests
clojure -M:dev:test --focus :integration

# Run all tests
clojure -M:dev:test

# Run specific test namespace
clojure -M:dev:test --focus mcp-tasks.main-test

# Run specific test
clojure -M:dev:test --focus mcp-tasks.main-test/config-threading-integration-test
```

**Build:**
```bash
# Build JAR (creates target/mcp-tasks-<version>.jar)
clj -T:build jar

# Clean build artifacts
clj -T:build clean

# Check version
clj -T:build version
```

## Changelog

**Tool:** git-cliff (config: `cliff.toml`)

**Generate changelog:**
```bash
git-cliff --unreleased  # Preview unreleased changes
git-cliff -o CHANGELOG.md  # Update CHANGELOG.md
```

**Commit prefixes:**
- `feat:` - New features
- `fix:` - Bug fixes
- `docs:` - Documentation changes
- `refactor:` - Code restructuring
- `test:` - Test additions/changes
- `perf:` - Performance improvements
- `build:` - Build system changes
- `ci:` - CI/CD changes
- `chore:` - Maintenance tasks
- `style:` - Code formatting

See `doc/dev/changelog.md` for setup details.

## GitHub Actions

**Workflows:**

- **test.yml** - Runs on pushes to master and all PRs
  - Runs cljstyle check
  - Runs clj-kondo lint with `--fail-level warning`
  - Runs unit tests and integration tests separately
  - Caches Clojure dependencies for faster runs

- **release.yml** - Manual workflow for releasing new versions
  - Runs all tests and linting
  - Builds JAR with version calculated from commit count
  - Generates changelog using git-cliff
  - Creates git tag
  - Deploys to Clojars
  - Creates GitHub Release with JAR artifact
  - Supports dry-run mode for testing

**Release Process:**
1. Go to Actions → Release workflow
2. Click "Run workflow"
3. Optionally enable dry-run to test without deploying
4. Workflow automatically calculates version (0.1.N based on commit count)

## Key Concepts

- **Categories**: Organize tasks by type/purpose, each with custom execution instructions
- **Default Categories**: Server provides defaults, but system is fully configurable
- **Task Lifecycle**: Tasks move from incomplete → complete → archived


## Other

- Use semantic commit messages, and semantic pull request titles
- run `cljstyle fix` before making commits
- run `clj-kondo --lint src test --fail-level warning` before commiting

- when merging a PR provide a clean commit message using semntic commit
  message style.  Do no just use the default message or a concatenation
  of all the commit messages.  The message should reflect the scope and
  logical content of the PR, not all the interim work used to implement
  it.
- the git repo in ~/.mcp-tasks does not have a remote
