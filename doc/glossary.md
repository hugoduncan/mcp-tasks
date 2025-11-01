# Project Glossary

## Task Management Terms

**Archive**: The `.mcp-tasks/complete.ednl` file where completed tasks are stored with `:status :closed`.

**Blocked Task**: A task that cannot be executed because it has `:blocked-by` relations referencing incomplete tasks (status `:open`, `:in-progress`, or `:blocked`). Computed automatically by checking the completion status of all tasks referenced in the `:blocked-by` relations. A task is unblocked when all its `:blocked-by` relations point to completed tasks (`:closed` or `:deleted`), or when it has no `:blocked-by` relations.

**Blocking Task**: A task that is preventing another task from being executed due to a `:blocked-by` relation. A task blocks another if it appears in that task's `:blocked-by` relations and has incomplete status (`:open`, `:in-progress`, or `:blocked`). Once the blocking task is completed (`:closed` or `:deleted`), it no longer blocks dependent tasks.

**Category**: A task organization unit that determines which prompt will be used to execute a task. Each task has a `:category` field that determines its execution workflow.

**Category Discovery**: Automatic detection of available categories by scanning `.mcp-tasks/prompts/` for `.md` files.

**Circular Dependency**: A dependency cycle where a chain of `:blocked-by` relations forms a loop (e.g., Task A blocked-by Task B, Task B blocked-by Task C, Task C blocked-by Task A). The system detects circular dependencies and marks affected tasks as blocked with `:blocking-task-ids` showing the cycle. Users must manually resolve circular dependencies by removing or reordering relations.

**Complete**: Changing a task's `:status` from `:open` to `:closed` and moving it from `tasks.ednl` to `complete.ednl`.

**Completion Comment**: Optional text appended to a task's `:description` field when marking it complete.

**Current Execution Resource**: An MCP resource (`resource://current-execution`) that exposes the currently executing story and task information for external tools and monitoring.

**Category Prompts**: Category-specific execution steps defined in `.mcp-tasks/prompts/<category>.md`.

**EDN (Extensible Data Notation)**: Clojure's data format used for task storage, providing rich data types and human-readable structure.

**EDNL (EDN Lines)**: Line-oriented EDN format where each line contains a complete EDN data structure. Used in `tasks.ednl` and `complete.ednl` files.

**Execution State**: A record of the currently executing story and task stored in `.mcp-tasks-current.edn`, containing `:story-id`, `:task-id`, and `:started-at` timestamp. Enables external monitoring and coordination.

**Frontmatter**: YAML-style metadata at the start of prompt files delimited by `---`, containing key-value pairs.

**Incomplete Task**: A task with `:status :open` in the `tasks.ednl` file.

**Next Task**: For a given category, the first task in `tasks.ednl` with matching `:category` field and `:status :open`.

**Prompt**: An MCP prompt that instructs agents on how to execute tasks for a specific category.  May be exposed in the agent as a slash command.

**Prompt Resource**: An MCP resource exposing a prompt via the `prompt://` URI scheme, enabling programmatic access and inspection.

**Relation**: A typed connection between tasks (e.g., `:blocked-by`, `:related`, `:discovered-during`). Stored in a task's `:relations` vector.

**Resource URI**: A unique identifier for an MCP resource, such as `prompt://next-simple` or `prompt://refine-task`.

**Task**: A task is a unot of work that can be executed in a single
agent session without exceeding its context limits.  Represented by an
EDN map with fields defined by the Task schema in
`src/mcp_tasks/schema.clj`.

**Task File**: The `tasks.ednl` or `complete.ednl` file containing tasks in EDNL format.

**Task Schema**: Malli schema defining required task fields: `:id`, `:status`, `:title`, `:description`, `:design`, `:category`, `:type`, `:meta`, `:relations`, and optional `:parent-id`.

**Task Tracking Repository**: The `.mcp-tasks/` directory as a separate git repository for version-controlling task history.

## MCP Terms

**MCP Client**: An application that connects to MCP servers (e.g., Claude Code, Claude Desktop).

**MCP Server**: A process implementing the Model Context Protocol, providing tools/prompts/resources to AI agents.

**Tool**: An MCP capability that agents can invoke to perform actions (e.g., `select-tasks`, `complete-task`, `add-task`).

## Workflow Terms

**Audit Trail**: Historical record of completed tasks preserved in `complete.ednl` with full context.

**Branch Naming Convention**: Branches are named using the format `<id>-<title-slug>` where:
- `<id>` is the task or story ID (variable width, no padding)
- `<title-slug>` is derived from the first N words of the title (default: 4, configurable via `:branch-title-words`)
- Slugification process: lowercase → spaces to dashes → remove special characters
- Example: Task 123 "Implement user authentication with OAuth" → `123-implement-user-authentication-with`

**Execution Instructions**: The steps an agent follows when processing a task.

**Prepend**: Adding a new task at the beginning of `tasks.ednl` rather than the end. Tasks are ordered, with earlier tasks having higher precedence.

**Task Lifecycle**: The progression of a task from creation → `:status :open` → `:status :closed` → archived in `complete.ednl`.

**Worktree Cleanup**: Automatic removal of git worktrees after task completion when `:worktree-management?` is enabled. The `complete-task` tool performs safety checks (no uncommitted changes, all commits pushed) before removing the worktree. The branch is preserved after cleanup. Task completion succeeds even if cleanup fails, with a warning message indicating the reason.

**Worktree Naming Convention**: Worktree directories are named based on the branch naming convention with an optional project prefix:
- With `:worktree-prefix :project-name`: `<project>-<id>-<title-slug>`
- With `:worktree-prefix :none`: `<id>-<title-slug>`
- Example: Task 123 "Fix authentication bug" with project "mcp-tasks" → `mcp-tasks-123-fix-authentication-bug`

**Worktree Workflow**: Using git worktrees to isolate task execution by category in separate directories.
