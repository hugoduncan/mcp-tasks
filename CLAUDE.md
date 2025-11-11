# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

@doc/glossary.md

## Project Overview

An MCP (Model Context Protocol) server that provides task-based workflow management for agents. Tasks are organized into configurable categories, each with its own execution instructions.

## Documentation Structure

**Documentation Policy:**

- **`doc/*.md` files**: Should be definitive and exhaustive, covering all details, edge cases, configuration options, and troubleshooting for their respective topics
- **`README.md`**: Should address only the most common use cases and provide clear links to the exhaustive documentation in `doc/*.md` files

When updating documentation:
- Add comprehensive details to `doc/*.md` files
- Keep `README.md` concise, covering quick start and common scenarios
- Ensure `README.md` links to relevant `doc/*.md` files for deeper coverage
- Avoid duplicating detailed content between README.md and doc files

## Architecture

**Task Storage Structure:**
- `.mcp-tasks/tasks.ednl` - All incomplete tasks stored as EDN records
- `.mcp-tasks/complete.ednl` - All completed tasks archive
- `.mcp-tasks/category-prompts/<category-name>.md` - Category-specific execution prompts
- `.mcp-tasks/prompt-overrides/<name>.md` - Custom workflow prompt overrides (optional)

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

## Task Dependencies and Blocking

The system supports task dependencies through `:blocked-by` relationships, enabling proper task ordering and dependency management.

**Blocking Logic:**

A task is **blocked** if ANY of its `:blocked-by` relations reference an incomplete task (status `:open`, `:in-progress`, or `:blocked`). A task is **unblocked** if ALL of its `:blocked-by` relations reference completed tasks (status `:closed` or `:deleted`), or if it has no `:blocked-by` relations.

**Status Field vs. blocked-by Relations:**

The system distinguishes between two types of blocking:

- **`:status :blocked`** - Manual blocking for external factors (e.g., waiting on stakeholder decision, external dependency). This is a user-set status indicating the task cannot proceed for reasons outside the task system.

- **`:blocked-by` relations** - Automated dependency-based blocking. The system computes whether a task is blocked by checking the completion status of tasks referenced in its `:blocked-by` relations.

A task can be both manually blocked (`:status :blocked`) and dependency-blocked (has incomplete `:blocked-by` relations). When computing dependency blocking, tasks with `:status :blocked` are treated as incomplete, meaning they can block other tasks that depend on them.

**Creating Dependencies:**

When creating tasks with dependencies:
1. Create all tasks first using `add-task`
2. Use `update-task` to add `:blocked-by` relations after tasks exist
3. Example: Task B depends on Task A → add relation `{:id 1, :relates-to <task-a-id>, :as-type :blocked-by}` to Task B's `:relations` vector

**Querying Blocked Status:**

The `select-tasks` tool automatically computes blocking status for each returned task:
- `:is-blocked` - Boolean indicating if the task is currently blocked
- `:blocking-task-ids` - Vector of task IDs that are blocking this task (empty if unblocked)
- `blocked` parameter - Filter results to only blocked (`blocked: true`) or only unblocked (`blocked: false`) tasks

The `work-on` tool also returns `:is-blocked` and `:blocking-task-ids` fields in its response.

**Story and Task Execution:**

When executing stories, the `execute-story-child` prompt automatically finds the first **unblocked** incomplete child task using `select-tasks` with `blocked: false` parameter. If all incomplete tasks are blocked, the prompt informs the user with details about blocking tasks and suggests completing blocking tasks first.

When executing individual tasks, the `execute-task` prompt validates whether the task is blocked before proceeding and asks for user confirmation if blocked.

**Circular Dependencies:**

The system detects circular dependencies when computing blocked status (e.g., Task A → Task B → Task A). If detected, affected tasks are marked as `:is-blocked true` with `:blocking-task-ids` showing the cycle. The system logs warnings but allows users to proceed manually to resolve the issue.

**Invalid Task IDs:**

If a `:blocked-by` relation references a non-existent task ID, the system treats this as an error. The task is marked as `:is-blocked true` with an error message in the metadata. A warning is logged, but users can choose to proceed.

**CLI Support:**

The CLI provides filtering and display options for blocked tasks:
- `--blocked true/false` - Filter tasks by dependency-blocked status (based on `:blocked-by` relations)
- `--show-blocking` - Append a "Blocking Details" section showing which task IDs are blocking each task
- Blocked indicator (⊠) is always shown in table output for dependency-blocked tasks
- `why-blocked` command - Explains why a specific task is blocked

See `doc/command-line.md` for complete CLI documentation and examples.

## Git Synchronization Strategy

The system provides git synchronization to ensure agents work with the latest task state when making modifications. This is implemented as a separate concern from file locking.

**Two Helper Functions:**

1. **`sync-and-prepare-task-file`** (in `src/mcp_tasks/tools/helpers.clj`)
   - **Use for**: Tools that MODIFY tasks.ednl (add/update/delete operations)
   - **Behavior**: Pulls latest changes from git remote (if configured), then loads tasks
   - **When**: Called inside `with-task-lock` after acquiring lock, before modification
   - **Trade-offs**: 
     - ✅ Agents always work with latest remote state
     - ✅ Reduces merge conflicts
     - ❌ Slightly slower due to network round-trip
     - ❌ Requires network connectivity (or fails gracefully if no remote)

2. **`prepare-task-file`** (in `src/mcp_tasks/tools/helpers.clj`)
   - **Use for**: Read-only operations (e.g., `select-tasks`, `work-on`)
   - **Behavior**: Loads tasks from local filesystem without git operations
   - **When**: Any operation that doesn't modify tasks.ednl
   - **Trade-offs**:
     - ✅ Fast and predictable
     - ✅ No network dependency
     - ❌ May work with stale data if remote has updates

**When to Use Each Function:**

| Tool Type | Function to Use | Rationale |
|-----------|----------------|-----------|
| `add-task` | `sync-and-prepare-task-file` | Modifies tasks.ednl - needs latest state |
| `update-task` | `sync-and-prepare-task-file` | Modifies tasks.ednl - needs latest state |
| `delete-task` | `sync-and-prepare-task-file` | Modifies tasks.ednl - needs latest state |
| `complete-task` | `sync-and-prepare-task-file` | Modifies tasks.ednl - needs latest state |
| `select-tasks` | Direct load (neither) | Read-only - no sync needed |
| `work-on` | Direct load (neither) | Read-only - no sync needed |

**Sync Behavior:**

The sync process handles various git states gracefully:
- **Not a git repository**: Skips sync, loads tasks normally (local-only repo)
- **Empty git repository**: Skips sync, loads tasks normally (no commits yet)
- **No remote configured**: Skips sync, loads tasks normally (acceptable)
- **Pull succeeds**: Reloads tasks with latest changes
- **Pull conflicts**: Returns error map - operation must be aborted
- **Network errors**: Returns error map - operation must be aborted

**Relationship with File Locking:**

Git sync and file locking are **separate concerns** that work together:
- **File locking** (via `with-task-lock`): Prevents concurrent file modifications
- **Git sync** (via `sync-and-prepare-task-file`): Ensures latest remote state

Typical workflow for modification tools:
```clojure
(helpers/with-task-lock config
  (fn []
    ;; 1. Acquire lock (prevents concurrent file access)
    ;; 2. Sync with remote + reload tasks
    (let [sync-result (helpers/sync-and-prepare-task-file config)]
      (if (and (map? sync-result) (false? (:success sync-result)))
        ;; Handle sync error
        (build-error-response ...)
        ;; 3. Perform modification with latest state
        (let [tasks-file sync-result]
          ;; ... modify tasks ...
          (tasks/save-tasks! tasks-file)
          ;; Return result for git commit outside lock
          {...})))))
;; 4. Release lock
;; 5. Git commit (outside lock - last writer wins)
```

**Git Operations and Locking:**

- Git operations (pull/commit/push) are **NOT locked**
- Last writer wins for git commits and pushes
- This is acceptable because:
  - File locking prevents file corruption
  - Git can handle concurrent commits naturally
  - Pull conflicts are detected and fail the operation

**Adoption Strategy:**

The system currently uses `sync-and-prepare-task-file` for all modification tools:
- ✅ `add-task` - integrated
- ✅ `update-task` - integrated
- ✅ `delete-task` - integrated
- ✅ `complete-task` - integrated

Read-only operations continue to load tasks directly without sync overhead.

## Execution State Tracking

The system tracks which story and task are currently being executed, making this information discoverable from outside the agent.

**Execution State Storage:**
- `.mcp-tasks-current.edn` - Current execution state file in project root
- `resource://current-execution` - MCP resource exposing the same information

**State Schema:**
```clojure
{:story-id 177              ;; nil if standalone task
 :task-id 180
 :task-start-time "2025-10-20T14:30:00Z"}
```

**State Lifecycle:**
- State file is created when task/story execution starts via `work-on`
- For **child tasks**: Upon completion, `:task-id` is removed while `:story-id` and `:task-start-time` are preserved to maintain story-level state
- For **standalone tasks**: State file is cleared completely upon completion
- For **stories**: State file is cleared completely upon completion
- Each worktree maintains its own state file in its root directory
- External tools can detect stale executions via `:task-start-time` timestamp

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
- `.mcp-tasks/prompt-overrides/<name>.md` - Custom workflow prompt overrides for story operations (optional)

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
- Creates `<id>-<story-name>` branch if not already on it
  - Format: `<story-id>-<title-slug>` (e.g., `123-implement-user-auth`)
  - Title is limited to first N words (default: 4, configurable via `:branch-title-words`)
  - Slugification: lowercase, spaces to dashes, special characters removed
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

**Advanced: The `start-dir` Parameter:**

The `resolve-config` function accepts an optional `start-dir` parameter that represents the directory where configuration search started. This is distinct from `config-dir` (where the config file was found):

- **`config-dir`**: The directory containing `.mcp-tasks.edn`
- **`start-dir`**: The directory where the search for `.mcp-tasks.edn` began (defaults to `config-dir`)

This distinction matters in the **inherited configuration scenario**:

**Example scenario:**
```
/Users/duncan/projects/mcp-tasks/              # Main repo with .mcp-tasks.edn
/Users/duncan/projects/mcp-tasks-123-fix-bug/  # Worktree (no .mcp-tasks.edn)
```

When starting the MCP server from the worktree:
1. Config search starts in `/Users/duncan/projects/mcp-tasks-123-fix-bug/` (`start-dir`)
2. Config is found in parent `/Users/duncan/projects/mcp-tasks/` (`config-dir`)
3. `resolve-config` detects that `start-dir` is a worktree even though `config-dir` is not
4. Main repo path is correctly resolved from the worktree's `.git` file

**Resolution logic:**
```clojure
;; Simplified from src/mcp_tasks/config.clj
(cond
  ;; Config dir itself is a worktree
  (in-worktree? base-dir)
  (find-main-repo base-dir)

  ;; Start dir is different from config dir AND is a worktree (inherited config)
  (and (not= start-dir base-dir)
       (in-worktree? start-dir))
  (find-main-repo start-dir)

  ;; Default: not a worktree
  :else
  base-dir)
```

This ensures worktree detection works correctly whether the config file is in the worktree itself or inherited from a parent directory.

**Configuration:**

Enable worktree management in `.mcp-tasks.edn`:
```clojure
{:worktree-management? true
 :worktree-prefix :project-name  ;; or :none
 :base-branch "main"
 :branch-title-words 4}          ;; Default: 4, nil for unlimited
```

**Configuration Options:**
- `:worktree-management?` - Enable automatic worktree creation and management
- `:worktree-prefix` - Prefix worktree directory names (`:project-name` or `:none`)
- `:base-branch` - The base branch to create feature branches from
- `:branch-title-words` - Number of words from title to use in branch/worktree names
  - Default: `4`
  - Set to `nil` for unlimited (use all words from title)
  - Examples:
    - With `4`: Task "Implement user authentication with OAuth support" → `123-implement-user-authentication-with`
    - With `2`: Task "Implement user authentication with OAuth support" → `123-implement-user`
    - With `nil`: Task "Implement user authentication with OAuth support" → `123-implement-user-authentication-with-oauth-support`

When `:worktree-management?` is enabled, the `work-on` tool automatically:
- Creates a worktree for the task/story if it doesn't exist
- Switches to the appropriate worktree
- Manages branches within the worktree

**work-on Tool Return Values:**

The `work-on` tool returns a map containing task and environment information:
- `:task-id` - The task ID being worked on
- `:category` - The task's category
- `:type` - The task type (e.g., :task, :bug, :feature)
- `:title` - The task title
- `:status` - The task status
- `:message` - Status message about the operation
- `:worktree-path` - Full path to the worktree (only present when in a worktree)
- `:worktree-name` - The worktree directory name (only present when `:worktree-path` is present)
  - Extracted as the final path component (e.g., `/Users/duncan/projects/mcp-tasks-123-fix-bug/` → `mcp-tasks-123-fix-bug`)
  - Format depends on `:worktree-prefix` config:
    - With `:project-name`: `<parent-dir-name>-<id>-<title-slug>` (where parent-dir-name is the name of the parent directory containing the project)
    - With `:none`: `<id>-<title-slug>`
- `:branch-name` - The current branch name (only present when branch management is active)
- `:worktree-clean?` - Boolean indicating if the worktree has uncommitted changes
- `:execution-state-file` - Path to the execution state file
- `:worktree-created?` - Boolean indicating if a new worktree was created

These return values allow agents to display clear context about their working environment.

**Automatic Worktree Cleanup:**

When `:worktree-management?` is enabled, the `complete-task` tool automatically removes worktrees after successful task completion. This keeps your workspace clean and prevents accumulation of unused worktrees.

**Cleanup Behavior:**
- Triggered automatically when completing standalone tasks (tasks without a parent-id) from within a worktree
- **NOT triggered** for story child tasks (tasks with a parent-id) - these tasks share the same worktree and branch, so the worktree is preserved for remaining story tasks
- Cleanup only occurs when completing the parent story itself
- Performs safety checks before removal:
  - Verifies no uncommitted changes exist
  - Verifies all commits are pushed to remote (if remote configured)
- Preserves the branch after cleanup (not deleted)
- Task completion succeeds even if cleanup fails (with warning)

**Safety Requirements:**

Before removing a worktree, the system verifies:
1. **No uncommitted changes**: Working directory must be clean
2. **All changes pushed**: Commits must be pushed to remote (if remote exists)

If safety checks fail, the task is still marked complete but the worktree remains with a warning message explaining why cleanup was skipped.

**Cleanup Messages:**

Story child task completion (no cleanup):
```
Task completed (staying in worktree for remaining story tasks)
```

Successful cleanup (standalone tasks):
```
Task completed. Worktree removed at /path/to/worktree (switch directories to continue)
```

Failed cleanup (safety checks):
```
Task completed. Warning: Could not remove worktree at /path/to/worktree: Uncommitted changes exist in worktree
```

Failed cleanup (git operation):
```
Task completed. Warning: Could not remove worktree at /path/to/worktree: Failed to remove worktree: <git error>
```

**Manual Cleanup:**

If automatic cleanup fails or is disabled, remove worktrees manually:
```bash
# From the main repository directory
git worktree remove /path/to/worktree

# Or with force flag if worktree has modifications
git worktree remove --force /path/to/worktree
```

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
- **Worktree cleanup skipped**: Check for uncommitted changes or unpushed commits; resolve and cleanup manually if needed
- **Worktree removal fails**: Use `git worktree remove --force <path>` to force removal after verifying no important changes remain

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

- **test.yml** - Comprehensive testing workflow with three jobs:
  
  **Triggers:**
  - Pushes to master branch
  - All pull requests
  
  **Jobs:**
  
  1. **test** (ubuntu-latest)
     - Runs cljstyle check
     - Runs clj-kondo lint with `--fail-level warning`
     - Runs unit tests (with `:unit` focus)
     - Runs integration tests (with `:integration` focus, skipping `:native-binary` tests)
  
  2. **babashka-test** (ubuntu-latest)
     - Verifies bb.edn is valid (`bb tasks`)
     - Tests CLI help commands for all subcommands (list, add, show, complete, update, delete)
     - Runs smoke test with `bb list --format json`
  
  3. **install-scripts-lint** (ubuntu-latest)
     - Runs shellcheck on the `install` script
  
  **Caching Strategy:**
  - Caches `~/.m2/repository` and `~/.gitlibs` directories
  - Cache key based on `deps.edn` hash for automatic invalidation

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
- Use clojure-mcp edit tools for Clojure files to avoid syntax errors
- Always configure git user identity in test repositories for CI compatibilit