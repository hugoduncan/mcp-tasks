# Project Glossary

## Task Management Terms

**Archive**: The `.mcp-tasks/complete.ednl` file where completed tasks are stored with `:status :closed`.

**Category**: A task organization unit that determines which prompt will be used to execute a task. Each task has a `:category` field that determines its execution workflow.

**Category Discovery**: Automatic detection of available categories by scanning `.mcp-tasks/prompts/` for `.md` files.

**Complete**: Changing a task's `:status` from `:open` to `:closed` and moving it from `tasks.ednl` to `complete.ednl`.

**Completion Comment**: Optional text appended to a task's `:description` field when marking it complete.

**Category Prompts**: Category-specific execution steps defined in `.mcp-tasks/prompts/<category>.md`.

**EDN (Extensible Data Notation)**: Clojure's data format used for task storage, providing rich data types and human-readable structure.

**EDNL (EDN Lines)**: Line-oriented EDN format where each line contains a complete EDN data structure. Used in `tasks.ednl` and `complete.ednl` files.

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

**Execution Instructions**: The steps an agent follows when processing a task.

**Prepend**: Adding a new task at the beginning of `tasks.ednl` rather than the end. Tasks are ordered, with earlier tasks having higher precedence.

**Task Lifecycle**: The progression of a task from creation → `:status :open` → `:status :closed` → archived in `complete.ednl`.

**Worktree Workflow**: Using git worktrees to isolate task execution by category in separate directories.
