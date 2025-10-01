# mcp-tasks Workflow

This document describes how to use mcp-tasks for agent-driven task execution.

## Overview

mcp-tasks provides a structured workflow where you plan tasks for an agent to execute. Tasks are organized into categories, with each category having its own execution instructions and task tracking.

## Setup

### Initialize .mcp-tasks as a Git Repository

The `.mcp-tasks` directory should be its own separate git repository to track task history independently from your project code:

```bash
# In your project root
mkdir -p .mcp-tasks/tasks .mcp-tasks/complete .mcp-tasks/prompts
cd .mcp-tasks
git init
git add .
git commit -m "Initialize mcp-tasks repository"
cd ..

# Add .mcp-tasks to your project's .gitignore
echo ".mcp-tasks/" >> .gitignore
```

**Why a separate repository?**
- Task tracking commits don't clutter your project history
- Task files are version controlled and shareable
- Completed task archive provides an audit trail
- Each project can have its own task repository

## Basic Workflow

### 1. Edit Task Files

Tasks are stored in markdown files under `.mcp-tasks/tasks/<category>.md`. Each task is a checkbox item:

```markdown
- [ ] First task to complete
- [ ] Second task to complete
- [ ] Third task to complete
```

**Key points:**
- Each line starting with `- [ ]` is an incomplete task
- Tasks are processed in order from top to bottom
- You can include detailed specifications and sub-bullets within each task

### 2. Order Tasks

Arrange tasks in the sequence you want them executed. The agent will always process the first incomplete task (the first `- [ ]` item).

**Example:**

```markdown
- [ ] High priority: Fix critical bug in authentication
- [ ] Medium priority: Add user profile feature
- [ ] Low priority: Refactor deprecated code
```

### 3. Run Task Prompts

Execute the next task in a category by running:

```
/mcp-tasks:next-<category>
```

**Examples:**
- `/mcp-tasks:next-simple` - Process next simple task
- `/mcp-tasks:next-feature` - Process next feature task
- `/mcp-tasks:next-bugfix` - Process next bugfix task

The agent will:
1. Read the first incomplete task
2. Analyze the task specification
3. Plan the implementation
4. Execute the solution
5. Commit changes to your repository
6. Mark the task as complete and move it to `.mcp-tasks/complete/<category>.md`

### 4. Review and Iterate

After each task completion:
- Review the changes made by the agent
- Test the implementation
- If needed, add follow-up tasks to the task file
- Continue with the next task

## Task Categories

Categories allow you to organize tasks by type and apply different execution strategies.

### Default Categories

The system discovers categories automatically by scanning `.mcp-tasks/` subdirectories:
- `tasks/` - Active task files
- `complete/` - Completed task archives
- `prompts/` - Category-specific execution instructions (optional)

### Custom Categories

Create a new category by adding a task file:

```bash
echo "- [ ] My first task" > .mcp-tasks/tasks/my-category.md
```

The category will be automatically discovered, and you can run:

```
/mcp-tasks:next-my-category
```

### Category-Specific Instructions

Override default task execution by adding a prompt file:

```bash
.mcp-tasks/prompts/<category>.md
```

This file should contain specific instructions for how tasks in this category should be executed.

## Advanced Workflow: Git Worktrees

For complex projects with multiple parallel task streams, you can use git worktrees to isolate work by category.

### Setup Worktrees

```bash
# Create a worktree for each category
git worktree add ../project-feature feature-branch
git worktree add ../project-bugfix bugfix-branch
git worktree add ../project-refactor refactor-branch
```

### Workflow with Worktrees

1. **Separate task streams** - Each worktree can have its own `.mcp-tasks/tasks/<category>.md` with relevant tasks

2. **Process tasks independently** - Work in each worktree:
   ```bash
   cd ../project-feature
   # Run /mcp-tasks:next-feature
   
   cd ../project-bugfix
   # Run /mcp-tasks:next-bugfix
   ```

3. **Merge appropriately** - Integrate completed work:
   ```bash
   # In main repository
   git merge feature-branch
   git merge bugfix-branch
   ```

### Benefits of Worktree Workflow

- **Isolation**: Different categories don't interfere with each other
- **Parallel execution**: Process multiple task streams simultaneously
- **Focused commits**: Each branch contains related changes
- **Easy rollback**: Discard a worktree branch if work isn't needed

## Task File Management

### Moving Tasks Between Categories

Simply cut and paste task lines between category files:

```bash
# Move a task from simple to feature category
vim .mcp-tasks/tasks/simple.md     # Remove task line
vim .mcp-tasks/tasks/feature.md    # Add task line
```

### Reviewing Completed Tasks

Check what has been accomplished:

```bash
cat .mcp-tasks/complete/<category>.md
```

Completed tasks are marked with `- [x]` and include the full task specification.

### Task Specifications

Tasks can be as simple or detailed as needed:

**Simple task:**
```markdown
- [ ] Add error logging to the API module
```

**Detailed task:**
```markdown
- [ ] Implement user authentication system
  - Use JWT tokens for session management
  - Support email/password login
  - Add password reset flow
  - Include rate limiting on login endpoints
  - Write comprehensive tests
```

The agent will read and consider all details when implementing the task.

## Tips and Best Practices

1. **Be specific**: Detailed task descriptions lead to better implementations
2. **One task at a time**: Let the agent focus on completing one task fully
3. **Review regularly**: Check completed work before moving to the next task
4. **Use categories wisely**: Group related tasks for better organization
5. **Leverage worktrees**: For complex projects, worktrees prevent conflicts
6. **Track progress**: The completion archive provides a clear audit trail
7. **Iterate**: Add follow-up tasks based on completed work

## Example Session

```bash
# 1. Add tasks to a category
echo "- [ ] Fix login timeout issue" >> .mcp-tasks/tasks/bugfix.md
echo "- [ ] Add user profile endpoint" >> .mcp-tasks/tasks/feature.md

# 2. Process a bugfix task
# Run: /mcp-tasks:next-bugfix
# Agent implements the fix and commits

# 3. Review the changes
git log -1 --stat

# 4. Process a feature task
# Run: /mcp-tasks:next-feature
# Agent implements the feature and commits

# 5. Check completed tasks
cat .mcp-tasks/complete/bugfix.md
cat .mcp-tasks/complete/feature.md
```

## Troubleshooting

**Task not processing:**
- Verify the task file exists: `ls .mcp-tasks/tasks/<category>.md`
- Check the task has `- [ ]` format (incomplete checkbox)
- Ensure no other tasks are marked incomplete above it

**Category not found:**
- The category is auto-discovered from filenames
- Create the task file if it doesn't exist
- Restart your MCP client to refresh the category list

**Changes not committed:**
- Check git status in both main repo and `.mcp-tasks/` repo
- Verify the agent completed all steps
- Manually commit if needed and add a follow-up task
