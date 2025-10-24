# MCP-Tasks Story and Task Management

This skill provides guidance on using the mcp-tasks MCP server for task and story management.

## Overview

The mcp-tasks MCP server provides:
- **Tools**: For managing tasks (create, update, select, complete, delete)
- **Prompts**: Workflow templates for executing tasks and stories
- **Resources**: Category instructions and execution state

## MCP Tools

### Task Selection and Query

**`select-tasks`** - Query tasks with flexible filtering
- Parameters (all optional):
  - `task-id`: Filter by exact task ID
  - `parent-id`: Filter by parent task (useful for story tasks)
  - `category`: Filter by category (described in the prompts)
  - `type`: Filter by type (task, bug, feature, story, chore)
  - `status`: Filter by status (open, closed, in-progress, blocked)
  - `title-pattern`: Regex or substring match on title
  - `limit`: Max results to return (default: 5)
  - `unique`: Enforce 0 or 1 match (error if >1)
- Returns: Vector of task maps with all task fields

### Task Creation and Modification

**`add-task`** - Create a new task
- Required parameters:
  - `category`: Categories are listed in the add-task prompt
  - `title`: Task title
- Optional parameters:
  - `description`: Detailed task description
  - `parent-id`: ID of parent task (for story tasks)
  - `type`: task, bug, feature, story, chore (default: task)
  - `prepend`: If true, add at beginning instead of end

**`update-task`** - Update existing task fields
- Required: `task-id`
- Optional fields to update:
  - `title`, `description`, `design`
  - `category`, `type`, `status`
  - `parent-id` (pass null to remove)
  - `meta` (map with string keys/values, pass null to clear)
  - `relations` (vector, pass null to clear)

**`complete-task`** - Mark task as complete
- Identifies by: `task-id` or `title` (at least one required)
- Optional: `completion-comment`
- Automatically moves task from tasks.ednl to complete.ednl

**`delete-task`** - Delete a task
- Identifies by: `task-id` or `title-pattern`
- Cannot delete tasks with non-closed children

### Task Environment

**`work-on`** - Set up environment for task execution
- Required: `task-id`
- Automatically:
  - Records execution state in `.mcp-tasks-current.edn`
  - Handles branch management (if configured)
  - Handles worktree management (if configured)

**`execution-state`** - Manage execution state tracking
- Actions:
  - `write`: Record state (requires task-id, started-at, optional story-id)
  - `clear`: Remove state file

## Available Prompts

### Task Execution Prompts

**`execute-task`** - Execute a task by selection criteria
- Arguments: `[selection-criteria...]`
- Supports multiple selection formats:
  - `category=simple`, `parent-id=51`, `type=bug`, etc.
  - Can combine multiple criteria
- Workflow:
  1. Find task using select-tasks
  2. Check refinement status (warn if unrefined)
  3. Retrieve category instructions
  4. Set up task environment with work-on
  5. Execute using category workflow
  6. Mark complete with complete-task

**`refine-task`** - Interactively refine a task
- Arguments: `[task-specification] [additional-context...]`
- Task specification formats:
  - By ID: "#59", "59", "task 59"
  - By pattern: "Update prompt file"
- Workflow:
  1. Find task
  2. Analyze in project context
  3. Interactive refinement loop
  4. Update with refined content and set `"refined": "true"` in meta

### Category Execution Prompts

**`next-<category>`** - Execute next task for category <category>
- Uses category: <category>
- Workflow: Analyze → Plan → Implement → Commit

### Story Management Prompts

**`create-story-tasks`** - Break down story into tasks
- Arguments: `[story-specification] [additional-context...]`
- Story specification formats:
  - By ID: "#59", "59", "story 59"
  - By pattern: "Make story prompts flexible"
- Workflow:
  1. Retrieve story
  2. Check refinement status
  3. Analyze and break down into tasks
  4. Get user approval
  5. Add tasks with parent-id linking to story

**`execute-story-task`** - Execute next task from story
- Arguments: `[story-specification] [additional-context...]`
- Workflow:
  1. Find story and first incomplete child
  2. Set up task environment (includes story-id)
  3. Execute using category workflow
  4. Mark task complete

**`review-story-implementation`** - Review completed story
- Analyzes implementation against requirements
- Checks code quality and best practices
- Can create additional improvement tasks

**`complete-story`** - Mark story as complete and archive
- Moves story and tasks to archive
- Preserves implementation history

**`create-story-pr`** - Create pull request for story
- Creates PR for completed story implementation

## Category Instructions Resources

Category-specific execution instructions are available as MCP resources:

- `prompt://category-<category>` - <category> task workflow

These resources contain the detailed execution steps for each category.

## Execution State Resource

**`resource://current-execution`** - Current execution state
- Contains: `story-id`, `task-id`, `started-at`
- Updated by work-on tool
- Cleared by complete-task tool
- Enables external monitoring

## Common Workflows

### Execute a Simple Task

```
/mcp-tasks:execute-task category=simple
```
Or directly:
```
/mcp-tasks:next-simple
```

### Work on a Story

1. Create tasks from story:
   ```
   /mcp-tasks:create-story-tasks 59
   ```

2. Execute tasks one by one:
   ```
   /mcp-tasks:execute-story-task 59
   ```
   Repeat until all tasks complete.

3. Review implementation:
   ```
   /mcp-tasks:review-story-implementation 59
   ```

4. Complete story:
   ```
   /mcp-tasks:complete-story 59
   ```

### Refine Before Executing

```
/mcp-tasks:refine-task 123
/mcp-tasks:execute-task task-id=123
```

## Task Categories

Listed in the add-task prompt.

## Best Practices

1. **Refine tasks before execution** - Use refine-task to ensure clarity
2. **Use appropriate categories** - Match task complexity to category
3. **Link story tasks** - Always use parent-id when creating story tasks
4. **Monitor execution state** - Check current-execution resource for status
5. **Complete tasks properly** - Always use complete-task when done

## File Locations

- Tasks: `.mcp-tasks/tasks.ednl`
- Completed: `.mcp-tasks/complete.ednl`
- Execution state: `.mcp-tasks-current.edn`
- Category prompts: `.mcp-tasks/prompts/<category>.md` (optional overrides)
- Story prompts: `.mcp-tasks/story/prompts/<name>.md` (optional overrides)
