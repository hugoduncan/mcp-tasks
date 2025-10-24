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

## Execution State Tracking

The system tracks which story and task are currently being executed, making this information discoverable from outside the agent.

**Execution State Storage:**
- `.mcp-tasks-current.edn` - Current execution state file in project root
- `resource://current-execution` - MCP resource exposing the same information

**State Schema:**
```clojure
{:story-id 177              ;; nil if standalone task
 :task-id 180
 :started-at "2025-10-20T14:30:00Z"}
```

**State Lifecycle:**
- State file is created when task/story execution starts
- State file is cleared when task completes successfully via `complete-task`
- Each worktree maintains its own state file in its root directory
- External tools can detect stale executions via `:started-at` timestamp

**Querying Current Execution:**

Via filesystem:
```bash
# Read current execution state
cat .mcp-tasks-current.edn
```

Via MCP resource:
```clojure
;; Access via MCP client
(read-resource "resource://current-execution")
```

**Use Cases:**
- Monitor agent progress from external tools
- Resume work after interruptions
- Coordinate between multiple agents or tools
- Provide status visibility in dashboards or CLIs

## Story-Based Workflow

The system provides story support for managing larger features or initiatives that span multiple tasks:

**Story Storage Structure:**
- Stories are stored as tasks in `.mcp-tasks/tasks.ednl` with `:type :story`
- Completed stories are moved to `.mcp-tasks/complete.ednl` with `:status :closed`
- `.mcp-tasks/story/prompts/<story-name>.md` - Custom story-specific prompts (optional)

**Story Workflow:**

1. **Refine Story** - Interactively improve story clarity
   - Collaborative refinement of story requirements
   - Improves clarity, completeness, and actionability

2. **Create Story Tasks** - Break down a story into categorized, executable tasks
   - Reads story
   - Creates tasks in `.mcp-tasks/tasks.ednl` with `:parent-id` linking to story
   - Each task uses appropriate `:category` for execution workflow

3. **Execute Story Task** - Execute the next incomplete task from a story
   - Finds story and first incomplete child using `select-tasks` tool with filtering
   - Executes task directly using category-specific workflow (e.g., simple, medium, large)
   - Marks task as complete using `complete-task` tool after successful execution

4. **Review Story Implementation** - Review completed implementation
   - Analyzes implementation against story requirements
   - Checks code quality, structure, and best practices
   - Optionally creates additional tasks for improvements

5. **Complete Story** - Archive completed stories
   - Moves story and tasks to archive directories
   - Preserves implementation history for reference

**Story Task Format:**
Story tasks are stored in `.mcp-tasks/tasks.ednl` as regular Task records with:
- `:parent-id` field set to the story's task ID
- `:type` typically set to `:task`, `:bug`, or `:feature`
- `:category` field determining which execution workflow to use
- All other standard Task schema fields (`:title`, `:description`, `:design`, etc.)

Story tasks are retrieved using the `select-tasks` tool with `parent-id` filtering.

**Branch Management:**
Story execution includes automatic branch management:
- Creates `<story-name>` branch if not already on it (branch name is the story title lowercased, with spaces replaced by dashes, and all special characters removed)
- Keeps all story tasks on the same branch
- Manual merge/push after story completion

**Story Tools:**
- `select-tasks` - Get tasks with optional filtering (use `parent-id` for story tasks)
- `complete-task` - Mark any task (including story tasks) as complete

## Git Worktree Support

The system fully supports working with git worktrees, allowing you to isolate different tasks or stories in separate working directories while sharing the same repository.

**How It Works:**

When the MCP server starts from within a git worktree:
- Config discovery searches up from the worktree directory to find `.mcp-tasks.edn`
- The system automatically detects the worktree environment
- Repository-wide operations (like `git worktree list`) use the main repository path
- Context-specific operations (like `git status`) use the current worktree path

**Automatic Detection:**

The config resolution automatically handles worktree environments:
1. If the config directory is a worktree → uses main repo for repository operations
2. If the current directory is a worktree → uses main repo for repository operations  
3. Works seamlessly whether config is in the worktree or inherited from parent

**Configuration:**

Enable worktree management in `.mcp-tasks.edn`:
```clojure
{:worktree-management? true
 :worktree-prefix :project-name  ;; or :none
 :base-branch "main"}
```

When `:worktree-management?` is enabled, the `work-on` tool automatically:
- Creates a worktree for the task/story if it doesn't exist
- Switches to the appropriate worktree
- Manages branches within the worktree

**Common Scenarios:**

1. **Config in main repo, working in worktree:**
   - Place `.mcp-tasks.edn` in the main repository directory
   - Start MCP server from any worktree
   - Config is inherited, main repo detected automatically

2. **Shared config across worktrees:**
   - Place `.mcp-tasks.edn` in a common parent directory
   - All worktrees inherit the same configuration
   - Each worktree maintains its own execution state

3. **Manual worktree usage:**
   - Create worktrees manually with `git worktree add`
   - Start MCP server from the worktree
   - All operations work seamlessly

**Troubleshooting:**

If you encounter worktree-related issues:
- **"fatal: not a git repository"**: Ensure the main repository exists and is accessible
- **Execution state not found**: Check that `.mcp-tasks-current.edn` exists in the worktree root
- **Config not found**: Verify `.mcp-tasks.edn` exists in the worktree or a parent directory
- **Branch conflicts**: Ensure the worktree is on the expected branch for the task

**Limitations:**

- Worktree paths must be accessible from the main repository
- The main repository's `.git` directory must exist and be valid
- Concurrent operations in different worktrees should coordinate via execution state

## If you see a problem that needs fixing

If you see something that is a problem, or could be improved, that is
not directly to the current task, add a task to address the issue and
move on with the original task.

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
