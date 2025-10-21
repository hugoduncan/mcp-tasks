## Configuration

### Configuration File

mcp-tasks supports an optional configuration file `.mcp-tasks.edn` in
your project root (sibling to the `.mcp-tasks/` directory). This file
allows you to explicitly control whether git integration is used for
task tracking.

**Configuration schema:**
```clojure
{:use-git? true}   ; Enable git mode
{:use-git? false}  ; Disable git mode
{:branch-management? false}  ; Disable branch management (default when key is absent)
{:branch-management? true}   ; Enable branch management for both story tasks and standalone tasks
{:worktree-management? false}  ; Disable worktree management (default when key is absent)
{:worktree-management? true}   ; Enable automatic worktree management (implies :branch-management? true)
{:worktree-prefix :project-name}  ; Include project name in worktree directory (default)
{:worktree-prefix :none}          ; Omit project name prefix from worktree directory
```

**File location:**
```
project-root/
├── .mcp-tasks/       # Task files directory
└── .mcp-tasks.edn    # Optional configuration file
```

### Branch Management

The `:branch-management?` configuration option enables automatic git branch creation and switching during task execution.

**Default behavior:** When `:branch-management?` is not present in the config, it defaults to `false` (no branch management).

**Applies to:** Both story tasks and standalone tasks

**Dependency on git mode:** Branch management requires git mode to be enabled. This means:
- `:use-git?` must be `true` (either explicitly set or auto-detected via `.mcp-tasks/.git`)
- You cannot use branch management without git mode
- If you explicitly set `:use-git? false` and `:branch-management? true`, branch operations will fail at runtime since there's no git repository for the `.mcp-tasks` directory

**Independent use of git mode:** Note that `:use-git?` can be used independently without `:branch-management?`. This allows you to version-control task tracking history without automatic branch creation for each task.

**Branch naming convention:**
- **Story tasks**: Branch name derived from the story title
- **Standalone tasks**: Branch name derived from the task title

**Sanitization pattern** (same for both):
- Convert to lowercase
- Replace spaces with dashes
- Remove all special characters (keep only a-z, 0-9, -)
- Replace multiple consecutive dashes with single dash
- Trim leading/trailing dashes
- Truncate to 200 characters maximum
- Fallback to `task-<task-id>` if result is empty

**Examples:**
```
Story title: "Implement Branch Management for Tasks"
→ Branch name: "implement-branch-management-for-tasks"

Task title: "Update documentation for new config option"
→ Branch name: "update-documentation-for-new-config-option"

Task title: "Fix bug #123"
→ Branch name: "fix-bug-123"

Task title: "!!!" (empty after sanitization, task-id=45)
→ Branch name: "task-45"
```

### Worktree Management

The `:worktree-management?` configuration option enables automatic git worktree creation and management during task execution. This provides better isolation for parallel development efforts by creating separate working directories for different tasks or stories.

**Default behavior:** When `:worktree-management?` is not present in the config, it defaults to `false` (no worktree management).

**Applies to:** Both story tasks and standalone tasks

**Dependency on branch management:** Worktree management requires branch management to be enabled because worktrees must be on specific branches. When `:worktree-management? true`, the system automatically enables `:branch-management? true`.

**Worktree location:** Worktrees are created in sibling directories (parent of project directory).

**Worktree naming convention:**

The `:worktree-prefix` option controls whether the project name is included in worktree directory names:

- **`:project-name` (default)**: Include project name prefix
  - Format: `<project-name>-<story-or-task-name>`
  - Use when: Working on multiple unrelated projects to avoid conflicts
  
- **`:none`**: Omit project name prefix
  - Format: `<story-or-task-name>`
  - Use when: Working on a single project for shorter directory names

Where:
- `<project-name>`: Derived from current directory name
- `<story-or-task-name>`: Title converted using same sanitization as branch names

**Examples with `:worktree-prefix :project-name` (default):**
```
Project: "mcp-tasks", Story: "Add Git Worktree Management Option"
→ Worktree path: "../mcp-tasks-add-git-worktree-management-option"

Project: "mcp-tasks", Task: "Fix parser bug"
→ Worktree path: "../mcp-tasks-fix-parser-bug"
```

**Examples with `:worktree-prefix :none`:**
```
Project: "mcp-tasks", Story: "Add Git Worktree Management Option"
→ Worktree path: "../add-git-worktree-management-option"

Project: "mcp-tasks", Task: "Fix parser bug"
→ Worktree path: "../fix-parser-bug"
```

**Worktree lifecycle:**

1. **Creation**: When executing a task with `:worktree-management? true`:
   - Check if worktree already exists for the story/task
   - If exists: Verify it's on the correct branch, warn if working directory is not clean
   - If not exists: Create new worktree in sibling directory on appropriate branch
   - Switch to worktree directory for task execution

2. **Reuse**: Existing worktrees are reused across multiple executions of the same story/task

3. **Cleanup**: After task/story completion:
   - AI agent asks user for confirmation before removing worktree
   - If confirmed: Remove worktree using `git worktree remove`
   - If declined: Leave worktree in place and inform user

**Error handling:** All worktree operations fail-fast with clear error messages:
- Worktree creation fails → stop, don't execute task
- Worktree already exists at path but not tracked → stop with error
- Invalid story/task name for path → stop with error

### Auto-Detection Mechanism

When no `.mcp-tasks.edn` file is present, mcp-tasks automatically
detects whether to use git mode by checking for the presence of
`.mcp-tasks/.git` directory:

- **Git mode enabled**: If `.mcp-tasks/.git` exists, the system assumes
  you want git integration
- **Non-git mode**: If `.mcp-tasks/.git` does not exist, the system
  operates without git

**Precedence:** Explicit configuration in `.mcp-tasks.edn` always
overrides auto-detection.

### Startup Validation

When the MCP server starts, it validates the configuration:

1. **Config file parsing**: If `.mcp-tasks.edn` exists, it must be valid EDN with correct schema
2. **Git repository validation**: If git mode is enabled (explicitly or auto-detected), the server verifies that `.mcp-tasks/.git` exists
3. **Error reporting**: Invalid configurations or missing git repositories cause server startup to fail with clear error messages to stderr

**Example error messages:**
```
Git mode enabled but .mcp-tasks/.git not found
Invalid config: :use-git? must be a boolean
Malformed EDN in .mcp-tasks.edn: ...
```

### Git Mode vs Non-Git Mode

The configuration affects two main behaviors:

#### Task Completion Output

When using the `complete-task` tool:

- **Git mode**: Returns completion message plus JSON with modified file
  paths for commit workflows

```
Task completed: <task description>
{"modified-files": ["tasks.ednl", "complete.ednl"]}
```

- **Non-git mode**: Returns only the completion message
```
Task completed: <task description>
```

#### Prompt Instructions

Task execution prompts adapt based on git mode:

- **Git mode**: Includes instructions to commit task file changes to
  `.mcp-tasks` repository

- **Non-git mode**: Omits git instructions, focuses only on file operations

### Important Notes

**Main repository independence**: The git mode configuration only
affects the `.mcp-tasks` directory. Your main project repository commits
are completely independent:

- Git mode ON: Commits are made to both `.mcp-tasks/.git` (for task
  tracking) and your main repo (for code changes)

- Git mode OFF: Commits are made only to your main repo (for code
  changes), task files are updated without version control
