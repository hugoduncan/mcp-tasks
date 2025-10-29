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

## Complete Task Lifecycle Walkthrough

### Prerequisites
- mcp-tasks MCP server configured and running
- Project initialized with `.mcp-tasks/` directory
- Optional: `:worktree-management?` enabled in `.mcp-tasks.edn`
- Optional: `:base-branch` configured for branch management

### Example: Medium Complexity Task

This walkthrough demonstrates executing task #123 "Add user authentication" (category: medium).

#### 1. Refine Task (Optional - Skip for Simple/Clear Tasks)

```bash
/mcp-tasks:refine-task 123
```

**What happens:**
- Agent analyzes task in project context
- Reviews design patterns and codebase
- Suggests improvements interactively
- Updates task with `update-task` tool
- Sets `meta: {"refined": "true"}`

**When to skip:** Simple tasks with clear requirements need no refinement.

#### 2. Execute Task

```bash
/mcp-tasks:execute-task task-id=123
```

**Behind the scenes - work-on tool (automatic):**

The execute prompt calls `work-on` which:
- Writes execution state to `.mcp-tasks-current.edn`
- Creates/switches to worktree (if `:worktree-management?` enabled)
- Creates/switches to branch `123-add-user-authentication` (if branch management configured)

**Example work-on response:**
```clojure
{:task-id 123
 :category "medium"
 :type :task
 :title "Add user authentication"
 :status :open
 :worktree-path "/Users/user/project-123-add-user-authentication"  ; if enabled
 :worktree-name "project-123-add-user-authentication"              ; if enabled
 :branch-name "123-add-user-authentication"                        ; if enabled
 :worktree-clean? true
 :worktree-created? true
 :execution-state-file ".mcp-tasks-current.edn"}
```

**Agent displays working environment:**
```
Worktree: project-123-add-user-authentication
Directory: /Users/user/project-123-add-user-authentication
Branch: 123-add-user-authentication
```

**Category workflow execution (medium):**

1. **Analyze** - Agent reviews task spec, researches patterns
2. **Check understanding** - Agent confirms understanding with user
3. **Plan** - Agent creates implementation plan
4. **Implement** - Agent writes code
5. **Commit** - Agent creates git commit in main repository

#### 3. Complete Task

After successful implementation, the agent calls:

```clojure
(complete-task :task-id 123 :completion-comment "Implemented JWT auth")
```

**What happens:**
- Task status changed to `:closed`
- Task moved from `tasks.ednl` to `complete.ednl`
- Execution state cleared from `.mcp-tasks-current.edn`
- **Automatic worktree cleanup** (if `:worktree-management?` enabled):
  - Safety checks: No uncommitted changes, all commits pushed
  - Worktree removed with `git worktree remove`
  - Branch preserved for PR creation
  - If cleanup fails: Warning shown, task still marked complete

**Completion message examples:**

Success with cleanup:
```
Task completed. Worktree removed at /path/to/worktree (switch directories to continue)
```

Cleanup skipped (uncommitted changes):
```
Task completed. Warning: Could not remove worktree: Uncommitted changes exist
```

### Configuration Impact

| Feature | Enabled | Disabled |
|---------|---------|----------|
| **Worktree Management** | Creates sibling directory worktree, auto-cleanup on completion | Works in current directory |
| **Branch Management** | Creates `<id>-<title-slug>` branch automatically | Uses current branch |
| **Branch Naming** | `123-add-user-authentication` (ID + first 4 title words by default) | N/A |
| **Worktree Naming** | `<project>-<id>-<title>` or `<id>-<title>` (depends on `:worktree-prefix`) | N/A |
| **Cleanup Safety** | Checks uncommitted changes + unpushed commits before removal | N/A |

**Manual cleanup (if automatic fails):**
```bash
# From main repository
git worktree remove /path/to/worktree
# Or force if needed
git worktree remove --force /path/to/worktree
```

### Error Recovery

**Execution fails/interrupted:**
- Execution state remains in `.mcp-tasks-current.edn`
- Task status stays `:open`
- Restart by calling execute prompt again
- `work-on` overwrites stale execution state automatically

**Blocked task:**
- Use `update-task` to set `status: :blocked`
- Add relation: `{:relates-to <other-task-id>, :as-type :blocked-by}`
- Execute other tasks first

**Need to modify task during execution:**
- Use `update-task` to change description/design
- Continue execution with updated task

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
