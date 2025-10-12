# mcp-tasks Workflow

This document describes how to use mcp-tasks for agent-driven task execution.

## Overview

mcp-tasks provides a structured workflow where you plan tasks for an agent to execute. Tasks are organized into categories, with each category having its own execution instructions and task tracking.

## Configuration

### Configuration File

mcp-tasks supports an optional configuration file `.mcp-tasks.edn` in your project root (sibling to the `.mcp-tasks/` directory). This file allows you to explicitly control whether git integration is used for task tracking.

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
├── .mcp-tasks.edn    # Optional configuration file
└── src/              # Your project code
```

### Auto-Detection Mechanism

When no `.mcp-tasks.edn` file is present, mcp-tasks automatically detects whether to use git mode by checking for the presence of `.mcp-tasks/.git` directory:

- **Git mode enabled**: If `.mcp-tasks/.git` exists, the system assumes you want git integration
- **Non-git mode**: If `.mcp-tasks/.git` does not exist, the system operates without git

**Precedence:** Explicit configuration in `.mcp-tasks.edn` always overrides auto-detection.

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

- **Git mode**: Returns completion message plus JSON with modified file paths for commit workflows
  ```
  Task completed: <task description>
  {"modified-files": ["tasks/simple.md", "complete/simple.md"]}
  ```

- **Non-git mode**: Returns only the completion message
  ```
  Task completed: <task description>
  ```

#### Prompt Instructions

Task execution prompts adapt based on git mode:

- **Git mode**: Includes instructions to commit task file changes to `.mcp-tasks` repository
- **Non-git mode**: Omits git instructions, focuses only on file operations

### Important Notes

**Main repository independence**: The git mode configuration only affects the `.mcp-tasks` directory. Your main project repository commits are completely independent:

- Git mode ON: Commits are made to both `.mcp-tasks/.git` (for task tracking) and your main repo (for code changes)
- Git mode OFF: Commits are made only to your main repo (for code changes), task files are updated without version control

## Setup

### Initialize .mcp-tasks as a Git Repository (Optional)

The `.mcp-tasks` directory can optionally be its own separate git repository to track task history independently from your project code:

**Note:** This step is optional. If you skip git initialization, mcp-tasks will operate in non-git mode, managing task files without version control.

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

## Story-Based Workflow

Stories provide a higher-level workflow for breaking down larger features or epics into executable tasks. A story represents a cohesive piece of work that gets refined, broken into tasks, and executed systematically.

### Story File Structure

Stories are stored in `.mcp-tasks/stories/<story-name>.md` as markdown files containing:

**Example story file:**
```markdown
# User Authentication Story

## Goal
Implement a complete user authentication system with JWT tokens.

## Requirements
- Email/password login
- JWT token generation and validation
- Password reset flow
- Rate limiting on auth endpoints
- Comprehensive test coverage

## Technical Approach
- Use industry-standard JWT libraries
- Implement bcrypt for password hashing
- Add Redis for rate limiting
- Follow OAuth 2.0 patterns where applicable
```

### Story Workflow Cycle

#### 1. Story Refinement

Refine a rough story idea into a detailed specification:

```
/mcp-tasks:refine-story <story-name>
```

**Process:**
- Agent reads the initial story from `.mcp-tasks/stories/<story-name>.md`
- Asks clarifying questions about requirements, constraints, and approach
- Iteratively refines the story with your feedback
- Saves the refined story back to the file

**Example interaction:**
```
User: /mcp-tasks:refine-story user-auth

Agent: I've read your user-auth story. Let me ask some clarifying questions:
1. Which JWT library should we use?
2. What's the token expiration policy?
3. Should we support refresh tokens?
...

User: [Provides answers]

Agent: Based on your answers, here's the refined story...
[Shows updated story]

User: Looks good!

Agent: Story saved to .mcp-tasks/stories/user-auth.md
```

#### 2. Task Breakdown

Break the refined story into executable tasks:

```
/mcp-tasks:create-story-tasks <story-name>
```

**Process:**
- Agent reads the story file
- Analyzes requirements and identifies discrete tasks
- Assigns each task to an appropriate category (simple, medium, large)
- Creates task file at `.mcp-tasks/story-tasks/<story-name>-tasks.md`
- Gets your approval before saving

**Task file format:**
```markdown
# Tasks for user-auth Story

## Foundation

- [ ] STORY: user-auth - Set up JWT library dependencies
  Add JWT library to deps.edn, configure for HS256 algorithm, add
  test dependencies for token validation testing.

CATEGORY: simple

- [ ] STORY: user-auth - Implement password hashing utilities
  Create namespace for bcrypt password hashing with functions for
  hash generation and verification. Include edge case handling for
  invalid inputs.

CATEGORY: medium

## Authentication Core

- [ ] STORY: user-auth - Build JWT token generation endpoint
  Implement /api/auth/login endpoint that validates credentials,
  generates JWT token with user claims, returns token and expiry.
  Handle invalid credentials gracefully.

CATEGORY: medium
```

**Key format elements:**
- Each task starts with `- [ ] STORY: <story-name> - <description>`
- Multi-line task descriptions are indented under the checkbox line
- Each task has a `CATEGORY: <category>` line on its own line
- Tasks can be organized into sections with markdown headings

#### 3. Task Execution

Execute story tasks one at a time:

```
/mcp-tasks:execute-story-task <story-name>
```

**Process:**
1. Agent finds the first incomplete task in the story task file
2. Extracts the task text and category
3. Adds the task to the front of the category's queue
4. Executes the task using the category's workflow
5. Upon success, marks the story task as complete
6. Updates the story task file with `- [x]` marker

**Example execution:**
```
User: /mcp-tasks:execute-story-task user-auth

Agent: Found next task: "STORY: user-auth - Set up JWT library dependencies"
       Category: simple

       [Adds task to simple queue and executes it...]

       [After completion...]

       Task completed successfully. Marked as complete in story tasks file.
```

**Repeat** this command until all story tasks are complete.

#### 4. Progress Tracking

Check story progress at any time:

```bash
# View all tasks
cat .mcp-tasks/story-tasks/<story-name>-tasks.md

# Count completed vs total tasks
grep -c "^- \[x\]" .mcp-tasks/story-tasks/user-auth-tasks.md
grep -c "^- \[ \]" .mcp-tasks/story-tasks/user-auth-tasks.md
```

Completed tasks are marked with `- [x]` but remain in the story task file for the full context.

### Story Branch Management

When `:story-branch-management? true` is configured in `.mcp-tasks.edn`, the system automatically manages git branches for stories:

**Automatic branch operations:**
- Creates a `<story-name>` branch from the default branch when starting story work
- Ensures the branch is up-to-date with the remote before task execution
- All story task commits go to the story branch
- Branch persists across multiple task executions

**Manual branch workflow (default when `:story-branch-management? false`):**
- You manually create and manage story branches
- System respects your current branch
- Gives you full control over branch strategy

**Example with branch management enabled:**
```bash
# Configure story branch management
echo '{:use-git? true :story-branch-management? true}' > .mcp-tasks.edn

# Start story work - automatically creates/switches to user-auth branch
# Run: /mcp-tasks:execute-story-task user-auth

# All subsequent task executions stay on user-auth branch until story is complete
```

### Complete Story Workflow Example

```bash
# 1. Create initial story file
cat > .mcp-tasks/stories/user-auth.md <<EOF
# User Authentication

We need user authentication with JWT tokens.
EOF

# 2. Refine the story
# Run: /mcp-tasks:refine-story user-auth
# [Interactive refinement with agent]

# 3. Break into tasks
# Run: /mcp-tasks:create-story-tasks user-auth
# [Agent proposes task breakdown, you approve]

# 4. Execute tasks one by one
# Run: /mcp-tasks:execute-story-task user-auth
# [First task executes]

# Run: /mcp-tasks:execute-story-task user-auth
# [Second task executes]

# ... repeat until all tasks complete

# 5. Review the completed work
cat .mcp-tasks/story-tasks/user-auth-tasks.md
# All tasks marked with [x]

# 6. Merge story branch (if using branch management)
git checkout master
git merge user-auth
```

### Story Prompt Customization

Override the default story prompts by creating files in `.mcp-tasks/prompts/story/`:

**Available prompts to override:**
- `refine-story.md` - Story refinement instructions
- `create-story-tasks.md` - Task breakdown instructions
- `execute-story-task.md` - Task execution workflow

**Override file format:**
```markdown
---
title: Custom Story Refinement
description: My team's story refinement process
---

[Your custom instructions here]
Use {story-name} as a placeholder for the story name argument.
```

**Precedence:**
1. `.mcp-tasks/prompts/story/<prompt-name>.md` (your override)
2. Built-in defaults from the system

### Story Workflow Tips

**Story sizing:**
- Keep stories focused on a single feature or epic
- Aim for 5-15 tasks per story
- Break very large stories into multiple smaller stories

**Task descriptions:**
- Be specific about acceptance criteria
- Include technical constraints or approaches
- Reference related tasks when dependencies exist
- Use multi-line format for complex tasks

**Category selection:**
- `simple`: Straightforward tasks, < 30 minutes
- `medium`: Moderate complexity, involves planning
- `large`: Complex tasks requiring design and iteration

**Workflow integration:**
- Stories work alongside regular task categories
- Use stories for planned work, regular tasks for ad-hoc items
- Story tasks can reference regular tasks and vice versa

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

## Example Sessions

### Example: Git Mode Workflow

```bash
# 1. Initialize .mcp-tasks with git (enables auto-detection)
mkdir -p .mcp-tasks/tasks .mcp-tasks/complete .mcp-tasks/prompts
cd .mcp-tasks
git init
git add .
git commit -m "Initialize mcp-tasks repository"
cd ..

# 2. Add tasks to a category
echo "- [ ] Fix login timeout issue" >> .mcp-tasks/tasks/bugfix.md

# 3. Process a bugfix task
# Run: /mcp-tasks:next-bugfix
# Agent implements the fix, commits to main repo, and commits task changes to .mcp-tasks repo

# 4. Review the changes
git log -1 --stat                        # Main repo commits
cd .mcp-tasks && git log -1 --stat && cd ..  # Task tracking commits

# 5. Check completed tasks
cat .mcp-tasks/complete/bugfix.md
```

### Example: Non-Git Mode Workflow

```bash
# 1. Create .mcp-tasks directory structure (no git init)
mkdir -p .mcp-tasks/tasks .mcp-tasks/complete .mcp-tasks/prompts

# 2. Optionally configure non-git mode explicitly
echo '{:use-git? false}' > .mcp-tasks.edn

# 3. Add tasks to a category
echo "- [ ] Add user profile endpoint" >> .mcp-tasks/tasks/feature.md

# 4. Process a feature task
# Run: /mcp-tasks:next-feature
# Agent implements the feature, commits to main repo only

# 5. Review the changes
git log -1 --stat                # Only main repo commits

# 6. Check completed tasks (files updated without version control)
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
