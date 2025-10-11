# Project Glossary

## Task Management Terms

**Archive**: The `.mcp-tasks/complete/` directory where completed tasks are moved with `- [x]` markers.

**Category**: A task organization unit that determines which prompt will be used to execute a task.
Each category has a queue of tasks in .mcp-tasks/tasks/<category>.md.

**Category Discovery**: Automatic detection of available categories by scanning `.mcp-tasks/` subdirectories for `.md` files.

**Complete**: Moving a task from `tasks/<category>.md` to `complete/<category>.md` and marking it with `- [x]`.

**Completion Comment**: Optional text appended to a task when marking it complete.

**Category Instructions**: Category-specific execution steps defined in `.mcp-tasks/prompts/<category>.md`.

**Frontmatter**: YAML-style metadata at the start of prompt files delimited by `---`, containing key-value pairs.

**Incomplete Task**: A task marked with `- [ ]` checkbox syntax in markdown.

**Next Task**: The first incomplete task (first `- [ ]` item) in a category's task file.

**Prompt**: An MCP resource that instructs agents on how to execute tasks for a specific category.

**Task**: A single checkbox item (`- [ ]` or `- [x]`) in a markdown file under `.mcp-tasks/`.

**Task File**: A markdown file containing checkbox-formatted tasks at `.mcp-tasks/tasks/<category>.md`.

**Task Text**: The content of a task line after the checkbox marker (e.g., for `- [ ] Fix bug`, the task text is "Fix bug").

**Task Tracking Repository**: The `.mcp-tasks/` directory as a separate git repository for version-controlling task history.

## MCP Terms

**MCP Client**: An application that connects to MCP servers (e.g., Claude Code, Claude Desktop).

**MCP Server**: A process implementing the Model Context Protocol, providing tools/prompts/resources to AI agents.

**Tool**: An MCP capability that agents can invoke to perform actions (e.g., `next-task`, `complete-task`, `add-task`).

## Workflow Terms

**Audit Trail**: Historical record of completed tasks preserved in `.mcp-tasks/complete/` with full context.

**Execution Instructions**: The steps an agent follows when processing a task (steps 4-6 in the workflow).

**Prepend**: Adding a new task at the beginning of a task file rather than the end.

**Task Lifecycle**: The progression of a task from creation → incomplete → completed → archived.

**Worktree Workflow**: Using git worktrees to isolate task execution by category in separate directories.
