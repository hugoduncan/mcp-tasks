# mcp-tasks Workflow

This document describes how to use mcp-tasks for agent-driven task execution.

## Overview

mcp-tasks provides a structured workflow where you plan tasks for an
agent to execute. Tasks are organized into categories, with each
category having its own execution instructions and task tracking.

## Workflow Modes

mcp-tasks supports two workflow modes:

- **Task-ID Based Workflow**: Execute specific tasks by ID for direct control over execution order
- **Category-Based Workflow**: Execute tasks from category queues for sequential processing

Choose task-id based when you need to control execution order, or category-based when you want to process queued tasks sequentially within a category.

## Task-ID Based Workflow

### 1. Add Tasks

Ask the agent to create tasks, or use the `add-task` tool directly:

```bash
# Ask agent to create a task
"Create a simple task to add user profile endpoint"
# Agent uses add-task tool, returns task-id: 123
```

The agent will use the `add-task` tool to create an EDN record with all required fields in `.mcp-tasks/tasks.ednl`.

### 2. Execute Tasks by ID

Execute specific tasks using their ID:

```bash
/mcp-tasks:execute-task 123
```

The agent will:
1. Call the `work-on` tool to set up the task environment
2. Execute the task using the category-specific workflow
3. Commit changes to your repository
4. Use the `complete-task` tool to mark the task as complete

### 3. Review and Iterate

After each task completion:
- Review the changes made by the agent
- Test the implementation
- Add follow-up tasks as needed
- Execute tasks in any order by ID

**When to use:** Direct control over task execution order, working on specific tasks regardless of their position in the queue.

## Category-Based Workflow

### 1. Add Tasks

Tasks are stored in EDN format in `.mcp-tasks/tasks.ednl`. Use the
`add-task` tool to add tasks:

```bash
# Add a task via MCP tool
# The tool will create an EDN record with all required fields
```

**Key points:**
- Tasks are EDN maps with fields: `:id`, `:status`, `:title`,
  `:description`, `:category`, etc.
- Tasks with matching `:category` are processed in order from top to bottom
- You can include detailed specifications in the `:description` field

### 2. Order Tasks

Tasks in `tasks.ednl` are processed in order from top to bottom. The
agent will always process the first task with matching `:category` and
`:status :open`.

To reorder tasks, you can manually edit `tasks.ednl` or use the
`add-task` tool with `prepend: true` to add high-priority tasks at the
beginning.

### 3. Run Task Prompts

Execute the next task in a category by running:

```
/mcp-tasks:category-<category>
```

**Examples:**
- `/mcp-tasks:category-simple` - Process next simple task
- `/mcp-tasks:category-feature` - Process next feature task
- `/mcp-tasks:category-bugfix` - Process next bugfix task

The agent will:
1. Read the first task with matching category and `:status :open`
2. Analyze the task specification
3. Plan the implementation
4. Execute the solution
5. Commit changes to your repository
6. Mark the task as complete (`:status :closed`)

### 4. Review and Iterate

After each task completion:
- Review the changes made by the agent
- Test the implementation
- If needed, add follow-up tasks to the task file
- Continue with the next task

**When to use:** Sequential processing of multiple tasks within a category, queue-based task management.

## Advanced Workflows

For more complex workflows and features, see these guides:

- **[Story-Based Workflow](workflow/story-workflow.md)** - Break down larger features into executable tasks with story refinement, task breakdown, and execution tracking
- **[Git Worktrees](workflow/git-worktrees.md)** - Isolate parallel task streams using git worktrees (manual and automatic modes)
- **[Tools Reference](workflow/tools-reference.md)** - Detailed technical reference for story tools and prompts
- **[Examples](workflow/examples.md)** - Complete workflow examples for git and non-git modes

## Task Categories

Categories allow you to organize tasks by type and apply different
execution strategies.

### Default Categories

The system discovers categories automatically from:
- Tasks in `tasks.ednl` - Each task's `:category` field defines its category
- Prompts in `.mcp-tasks/prompts/` - Optional category-specific
  execution instructions (e.g., `simple.md`, `medium.md`)

### Custom Categories

Create a new category by adding a task with that category:

```bash
# Use the add-task tool with your custom category name
# Example: {:category "my-category" :title "My first task" ...}
```

The category will be automatically discovered from existing tasks, and
you can run:

```
/mcp-tasks:next-my-category
```

### Category-Specific Instructions

Override default task execution by adding a prompt file:

```bash
.mcp-tasks/prompts/<category>.md
```

This file should contain specific instructions for how tasks in this
category should be executed.

## Task File Management

### Moving Tasks Between Categories

Edit the task's `:category` field in `tasks.ednl`:

```bash
# Open tasks.ednl and change the :category field
vim .mcp-tasks/tasks.ednl
# Find the task and change {:category "simple" ...} to {:category "feature" ...}
```

### Reviewing Completed Tasks

Check what has been accomplished:

```bash
cat .mcp-tasks/complete.ednl
```

Completed tasks have `:status :closed` and include the full task
specification as an EDN map.

### Task Specifications

Tasks can be as simple or detailed as needed:

**Simple task:**
```clojure
{:id 1
 :status :open
 :title "Add error logging to the API module"
 :description ""
 :category "simple"
 :type :task
 :design ""
 :meta {}
 :relations []}
```

**Detailed task:**
```clojure
{:id 2
 :status :open
 :title "Implement user authentication system"
 :description "Use JWT tokens for session management
Support email/password login
Add password reset flow
Include rate limiting on login endpoints
Write comprehensive tests"
 :category "medium"
 :type :feature
 :design ""
 :meta {}
 :relations []}
```

The agent will read and consider all details in the `:title` and
`:description` fields when implementing the task.

## Tips and Best Practices

1. **Be specific**: Detailed task descriptions lead to better implementations
2. **One task at a time**: Let the agent focus on completing one task fully
3. **Review regularly**: Check completed work before moving to the next task
4. **Use categories wisely**: Group related tasks for better organization
5. **Leverage worktrees**: For complex projects, worktrees prevent conflicts
6. **Track progress**: The completion archive provides a clear audit trail
7. **Iterate**: Add follow-up tasks based on completed work

## Troubleshooting

**Task not processing:**
- Verify tasks exist: `cat .mcp-tasks/tasks.ednl`
- Check the task has `:status :open`
- Ensure the task has the correct `:category` field
- Tasks are processed in order from top to bottom

**Category not found:**
- Categories are auto-discovered from tasks in `tasks.ednl`
- Add a task with the desired category using the `add-task` tool
- Restart your MCP client to refresh the category list

**Changes not committed:**
- Check git status in both main repo and `.mcp-tasks/` repo
- Verify the agent completed all steps
- Manually commit if needed and add a follow-up task
