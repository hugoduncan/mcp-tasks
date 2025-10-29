# MCP-Tasks Story and Task Management

This skill provides guidance on using the mcp-tasks MCP server for task and story management.

## Overview

The mcp-tasks MCP server provides:
- **Tools**: For managing tasks (create, update, select, complete, delete)
- **Prompts**: Workflow templates for executing tasks and stories
- **Resources**: Category instructions and execution state

## MCP Tools

### Task Query and Modification

| Tool | Purpose | Key Parameters |
|------|---------|---------------|
| `select-tasks` | Query tasks with filtering | `task-id`, `parent-id`, `category`, `type`, `status`, `title-pattern`, `limit`, `unique` |
| `add-task` | Create new task | Required: `category`, `title`. Optional: `description`, `parent-id`, `type`, `prepend` |
| `update-task` | Update task fields | Required: `task-id`. Optional: `title`, `description`, `design`, `category`, `type`, `status`, `parent-id`, `meta`, `relations` |
| `complete-task` | Mark complete and archive | Identify by `task-id` or `title`. Optional: `completion-comment` |
| `delete-task` | Delete task | Identify by `task-id` or `title-pattern`. Cannot delete with non-closed children |

### Task Environment Setup

**`work-on`** - Prepares task execution environment (called automatically by execute prompts)
- Required: `task-id`
- Actions: Records execution state, manages branches/worktrees (if configured)
- Returns: Task info (`:task-id`, `:category`, `:type`, `:title`, `:status`), environment info (`:worktree-path`, `:worktree-name`, `:branch-name`, `:worktree-clean?`, `:worktree-created?`), state file path

## Available Prompts

| Prompt | Purpose | Arguments | Specification Formats |
|--------|---------|-----------|----------------------|
| `execute-task` | Execute task by criteria | `[selection-criteria...]` | `category=X`, `parent-id=N`, `type=X` (combinable) |
| `refine-task` | Interactively refine task | `[task-spec] [context...]` | By ID: "#59", "59", "task 59". By pattern: "Update prompt" |
| `next-<category>` | Execute next task for category | None | N/A |
| `create-story-tasks` | Break story into tasks | `[story-spec] [context...]` | By ID: "#59", "59", "story 59". By pattern: "Story title" |
| `execute-story-task` | Execute next story task | `[story-spec] [context...]` | Same as create-story-tasks |
| `review-story-implementation` | Review completed story | `[story-spec]` | Same as create-story-tasks |
| `complete-story` | Archive completed story | `[story-spec]` | Same as create-story-tasks |
| `create-story-pr` | Create PR for story | `[story-spec]` | Same as create-story-tasks |

**Common workflow:** All execution prompts follow pattern: Find task → Call work-on → Execute category workflow → Mark complete

## MCP Resources

- `prompt://category-<category>` - Category-specific execution instructions
- `resource://current-execution` - Current execution state (`story-id`, `task-id`, `started-at`)

## Common Workflows

**Execute simple task:**
```
/mcp-tasks:next-simple
```

**Story workflow:**
```
/mcp-tasks:create-story-tasks 59       # Break into tasks
/mcp-tasks:execute-story-task 59       # Execute (repeat for each task)
/mcp-tasks:review-story-implementation 59
/mcp-tasks:complete-story 59
```

**Refine then execute:**
```
/mcp-tasks:refine-task 123
/mcp-tasks:execute-task task-id=123
```

## Best Practices

- Refine complex/unclear tasks before execution
- Match task complexity to category
- Use `parent-id` when creating story tasks
- Monitor execution via `resource://current-execution`

## File Locations

- Tasks: `.mcp-tasks/tasks.ednl`
- Completed: `.mcp-tasks/complete.ednl`
- Execution state: `.mcp-tasks-current.edn`
- Category prompts: `.mcp-tasks/prompts/<category>.md` (optional overrides)
- Story prompts: `.mcp-tasks/story/prompts/<name>.md` (optional overrides)
