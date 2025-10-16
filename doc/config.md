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
{:story-branch-management? false}  ; Disable story branch management (default)
{:story-branch-management? true}   ; Enable story branch management
```

**File location:**
```
project-root/
├── .mcp-tasks/       # Task files directory
└── .mcp-tasks.edn    # Optional configuration file
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
