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
