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

Stories are stored in `.mcp-tasks/story/stories/<story-name>.md` as markdown files containing:

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
- Agent reads the initial story from `.mcp-tasks/story/stories/<story-name>.md`
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

Agent: Story saved to .mcp-tasks/story/stories/user-auth.md
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
- Creates task file at `.mcp-tasks/story/story-tasks/<story-name>-tasks.md`
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
cat .mcp-tasks/story/story-tasks/<story-name>-tasks.md

# Count completed vs total tasks
grep -c "^- \[x\]" .mcp-tasks/story/story-tasks/user-auth-tasks.md
grep -c "^- \[ \]" .mcp-tasks/story/story-tasks/user-auth-tasks.md
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
cat > .mcp-tasks/story/stories/user-auth.md <<EOF
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
cat .mcp-tasks/story/story-tasks/user-auth-tasks.md
# All tasks marked with [x]

# 6. Merge story branch (if using branch management)
git checkout master
git merge user-auth
```

### Story Prompt Customization

Override the default story prompts by creating files in `.mcp-tasks/story/prompts/`:

**Available prompts to override:**
- `refine-story.md` - Story refinement instructions
- `create-story-tasks.md` - Task breakdown instructions
- `execute-story-task.md` - Task execution workflow

#### Frontmatter Format

Override files must include YAML frontmatter delimited by `---` markers:

**Supported fields:**
- `title` (string) - Human-readable prompt name shown in listings
- `description` (string) - Brief explanation of the prompt's purpose

**Format requirements:**
- Frontmatter must appear at the start of the file
- Use `key: value` format (simple YAML)
- Both opening and closing `---` delimiters are required
- Frontmatter is parsed but title and description are optional

**Basic override file structure:**
```markdown
---
title: Custom Story Refinement
description: My team's story refinement process
---

[Your custom instructions here]
Use {story-name} as a placeholder for the story name argument.
```

#### Override Precedence

The system checks for story prompt overrides in this order:

1. **Project override**: `.mcp-tasks/story/prompts/<prompt-name>.md`
   - Your team-specific customization
   - Takes precedence over built-ins
   - Can customize frontmatter and instructions

2. **Built-in default**: Resource files in the MCP server
   - System-provided story prompts
   - Used when no override exists
   - Cannot be modified without rebuilding the server

#### Complete Working Example

Create a custom task breakdown prompt with strict category rules:

**File**: `.mcp-tasks/story/prompts/create-story-tasks.md`
```markdown
---
title: Strict Category Task Breakdown
description: Break down stories with strict 2-hour task limits
---

Break down the story into tasks following these rules:

1. Read the story from `.mcp-tasks/story/stories/{story-name}.md`
2. If the file doesn't exist, inform the user and stop
3. Analyze the story and create specific, actionable tasks
4. Apply strict category rules:
   - simple: Must complete in under 2 hours, no external dependencies
   - medium: 2-4 hours, may involve API integration
   - large: Over 4 hours, requires design discussion first
5. Each task must have:
   - Clear acceptance criteria
   - Estimated time in task description
   - Explicit dependencies listed
6. Present the breakdown with time estimates
7. Get user approval before writing to `.mcp-tasks/story/story-tasks/{story-name}-tasks.md`

Task format:
- [ ] STORY: {story-name} - <title>
  <description>
  Estimated time: X hours
  Dependencies: <list or "none">

CATEGORY: <category>
```

This override enforces time-based categorization and explicit dependency tracking that may not be in the default prompt.

#### Relationship to Category Prompts

Story prompts and category prompts serve different purposes:

**Story prompts** (`.mcp-tasks/story/prompts/*.md`):
- Guide story-level operations (refine, break down, execute)
- Handle the story workflow and task distribution
- Route tasks to appropriate categories
- Three prompts: refine-story, create-story-tasks, execute-story-task

**Category prompts** (`.mcp-tasks/prompts/<category>.md`):
- Define how to execute individual tasks within a category
- Apply to both story tasks and standalone tasks
- Specify implementation steps, testing requirements, commit conventions
- One prompt per category (simple, medium, large, etc.)

**Interaction:**
When you run `/mcp-tasks:execute-story-task user-auth`:
1. The `execute-story-task` **story prompt** finds the next story task
2. It extracts the task's CATEGORY (e.g., "simple")
3. It adds the task to that category's queue
4. The task executes using the `simple` **category prompt** from `.mcp-tasks/prompts/simple.md`
5. After completion, the story task is marked as done

**Customization strategy:**
- Override story prompts to change how stories are managed and broken down
- Override category prompts to change how tasks are implemented
- Both can be customized independently to fit your workflow

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

## Story Tools and Prompts Reference

### Story Tools

#### next-story-task

Returns the next incomplete task from a story's task list.

**Parameters:**
- `story-name` (string, required) - The story name without -tasks.md suffix

**Returns:**

A map with three keys:
- `:task-text` (string or nil) - The full multi-line task text including the STORY prefix, excluding the CATEGORY line
- `:category` (string or nil) - The category assigned to this task
- `:task-index` (integer or nil) - The zero-based index of the task in the story task file

Returns nil values for all keys if no incomplete tasks are found.

**Behavior:**
- Reads story task file from `.mcp-tasks/story/story-tasks/<story-name>-tasks.md`
- Parses the markdown to find the first task marked with `- [ ]`
- Extracts the task text (all continuation lines until the CATEGORY line)
- Extracts the category from the `CATEGORY: <category>` metadata line
- Returns an error if the story task file doesn't exist

**Example:**
```clojure
;; Call
{:story-name "user-auth"}

;; Return value (success)
{:task-text "STORY: user-auth - Set up JWT library dependencies\nAdd JWT library to deps.edn..."
 :category "simple"
 :task-index 0}

;; Return value (no tasks)
{:task-text nil
 :category nil
 :task-index nil}
```

**Usage:**
```
Use the next-story-task tool to inspect the next task without executing it.
This is useful for planning or verifying task order.
```

#### complete-story-task

Completes a task in a story's task list by marking it as done.

**Parameters:**
- `story-name` (string, required) - The story name without -tasks.md suffix
- `task-text` (string, required) - Partial text from the beginning of the task to verify (without the `- [ ]` checkbox prefix)
- `completion-comment` (string, optional) - Optional comment to append to the completed task

**Returns:**

Git mode enabled:
- Text item 1: Completion status message
- Text item 2: JSON-encoded map with `:modified-files` key containing file paths relative to `.mcp-tasks`

Git mode disabled:
- Single text item: Completion status message

**Behavior:**
- Reads story task file from `.mcp-tasks/story/story-tasks/<story-name>-tasks.md`
- Finds the first incomplete task (marked with `- [ ]`)
- Verifies the task text matches the provided `task-text` parameter (case-insensitive, whitespace-normalized)
- Changes `- [ ]` to `- [x]` for the matched task
- Optionally appends the completion comment on a new line after the task
- Writes the updated content back to the story task file
- Returns an error if:
  - Story task file doesn't exist
  - No incomplete tasks are found
  - The first incomplete task doesn't match the provided text

**Example:**
```clojure
;; Call
{:story-name "user-auth"
 :task-text "STORY: user-auth - Set up JWT library"
 :completion-comment "Added buddy-sign library"}

;; Return (git mode)
"Story task completed in .mcp-tasks/story/story-tasks/user-auth-tasks.md"
"{\"modified-files\": [\"story-tasks/user-auth-tasks.md\"]}"

;; Return (non-git mode)
"Story task completed in .mcp-tasks/story/story-tasks/user-auth-tasks.md"
```

**Usage:**
```
Use complete-story-task after successfully executing a story task to mark
it as complete in the story task file. The tool verifies you're completing
the correct task before making changes.

In git mode, use the modified-files output to commit the task tracking change.
```

### Story Prompts

#### refine-story

Interactively refine a story document with user feedback.

**Arguments:**
- `story-name` - The name of the story to refine (without .md extension)

**Behavior:**
1. Reads the story file from `.mcp-tasks/story/stories/<story-name>.md`
2. If the file doesn't exist, informs the user and stops
3. Displays the current story content
4. Enters an interactive refinement loop:
   - Analyzes the story for clarity, completeness, and feasibility
   - Suggests specific improvements
   - Presents suggestions to the user
   - Gets user feedback on the suggestions
   - Applies approved changes
   - Incorporates user modifications
   - Continues until user is satisfied
5. Shows the final refined story for approval
6. If approved, writes the updated content back to the story file
7. Confirms the save operation

**Key characteristics:**
- Collaborative and iterative process
- Always gets explicit user approval before making changes
- Focuses on improving clarity, completeness, and actionability
- Does not make assumptions about requirements - asks for clarification

**Usage example:**
```
/mcp-tasks:refine-story user-authentication

The agent will read the user-authentication story, analyze it, and guide
you through an interactive refinement process to improve the story's clarity
and completeness.
```

#### create-story-tasks

Break down a story into categorized, executable tasks.

**Arguments:**
- `story-name` - The name of the story to break down (without .md extension)

**Behavior:**
1. Reads the story file from `.mcp-tasks/story/stories/<story-name>.md`
2. If the file doesn't exist, informs the user and stops
3. Displays the story content
4. Analyzes the story and breaks it down into specific, actionable tasks:
   - Each task is concrete and achievable
   - Tasks follow a logical sequence (dependencies first)
   - Related tasks are grouped into sections
   - Each task is prefixed with `STORY: <story-name> - `
   - Each task has a `CATEGORY: <category>` line after the task description
5. Applies category selection guidance:
   - `simple` - Straightforward tasks, small changes, documentation updates
   - `medium` - Tasks requiring analysis and design, moderate complexity
   - `large` - Complex tasks needing extensive planning and implementation
   - `clarify-task` - Tasks that need clarification before execution
6. Presents the task breakdown to the user with category assignments
7. Gets user feedback and makes adjustments
8. Once approved, writes the tasks to `.mcp-tasks/story/story-tasks/<story-name>-tasks.md`:
   - Includes a header: `# Tasks for <story-name> Story`
   - Organizes tasks by logical sections with `## Section Name` headers
   - Maintains the checkbox format with STORY prefix and CATEGORY metadata
   - Ensures blank lines between tasks for readability
9. Confirms the save operation

**Task format:**
```markdown
- [ ] STORY: <story-name> - <brief task title>
  <additional task details on continuation lines>
  <more details as needed>

CATEGORY: <category>
```

**Key characteristics:**
- Task descriptions are specific enough to be actionable without additional context
- The STORY prefix helps track which story each task belongs to
- The CATEGORY line is metadata used for routing
- Multi-line task descriptions are supported and encouraged
- Tasks are ordered to respect dependencies

**Usage example:**
```
/mcp-tasks:create-story-tasks user-authentication

The agent will read the user-authentication story, break it down into
discrete tasks, assign categories, and save the task list after your approval.
```

#### execute-story-task

Execute the next task from a story's task list.

**Arguments:**
- `story-name` - The name of the story (without .md extension)

**Behavior:**
1. Reads the story tasks file from `.mcp-tasks/story/story-tasks/<story-name>-tasks.md`
2. If the file doesn't exist, informs the user and stops
3. Finds the first incomplete task (marked with `- [ ]`)
4. Parses the task to extract:
   - Task text (everything from `- [ ] STORY:` until the `CATEGORY:` line)
   - Category (from the `CATEGORY: <category>` line)
5. If no incomplete tasks found, informs the user and stops
6. If no CATEGORY line found, informs the user and stops
7. Adds the task to the category queue:
   - Uses the `add-task` tool to prepend the task to the category's queue
   - Parameters: category (extracted), task-text (full multi-line description), prepend (true)
8. Executes the task:
   - Uses the category-specific next-task workflow
   - Follows the category's execution instructions from `.mcp-tasks/prompts/<category>.md`
   - Completes all implementation steps according to the category workflow
9. After successful execution, marks the story task as complete:
   - Changes `- [ ]` to `- [x]` for the executed task
   - Writes the updated content back to the story tasks file
   - Uses the `complete-story-task` tool if available
   - Confirms completion to the user

**Branch management (conditional):**

If configuration includes `:story-branch-management? true`:
1. Before starting task execution:
   - Checks if currently on a branch named `<story-name>`
   - If not, checks out the default branch, ensures it's up to date with origin, then creates the `<story-name>` branch
2. After task completion:
   - Remains on the `<story-name>` branch for the next task
   - Does not merge or push automatically

If `:story-branch-management?` is false (default):
- Executes tasks on the current branch without any branch operations

**Key characteristics:**
- The task text includes the full multi-line description from the story tasks file
- The CATEGORY line is used only for routing and is not included in the task text added to the category queue
- If task execution fails, the story task is not marked as complete
- The story task file tracks overall story progress
- Individual category queues manage immediate execution
- Branch management is optional and controlled by configuration

**Usage example:**
```
/mcp-tasks:execute-story-task user-authentication

The agent will find the next incomplete task from the user-authentication story,
add it to the appropriate category queue, execute it using that category's
workflow, and mark it as complete upon success.
```

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
