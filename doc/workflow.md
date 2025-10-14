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
  {"modified-files": ["tasks.ednl", "complete.ednl"]}
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

### 1. Add Tasks

Tasks are stored in EDN format in `.mcp-tasks/tasks.ednl`. Use the `add-task` tool to add tasks:

```bash
# Add a task via MCP tool
# The tool will create an EDN record with all required fields
```

**Key points:**
- Tasks are EDN maps with fields: `:id`, `:status`, `:title`, `:description`, `:category`, etc.
- Tasks with matching `:category` are processed in order from top to bottom
- You can include detailed specifications in the `:description` field

### 2. Order Tasks

Tasks in `tasks.ednl` are processed in order from top to bottom. The agent will always process the first task with matching `:category` and `:status :open`.

To reorder tasks, you can manually edit `tasks.ednl` or use the `add-task` tool with `prepend: true` to add high-priority tasks at the beginning.

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
1. Read the first task with matching category and `:status :open`
2. Analyze the task specification
3. Plan the implementation
4. Execute the solution
5. Commit changes to your repository
6. Mark the task as complete (`:status :closed`) and move it to `.mcp-tasks/complete.ednl`

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
1. Agent finds the story task and first incomplete child using `next-task` tool with filtering
2. Extracts the task text and category
3. Executes the task directly using the category's workflow
4. Upon success, marks the task as complete using `complete-task` tool
5. Task is moved from tasks.ednl to complete.ednl

**Example execution:**
```
User: /mcp-tasks:execute-story-task user-auth

Agent: Found next task: "STORY: user-auth - Set up JWT library dependencies"
       Task ID: 42
       Category: simple

       [Executes task using simple workflow...]

       [After completion...]

       Task completed successfully and moved to complete.ednl
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

#### next-task (with filtering for stories)

The `next-task` tool supports optional filtering parameters that enable story task queries:

**Parameters:**
- `category` (string, optional) - Filter by task category
- `parent-id` (integer, optional) - Filter by parent task ID (for finding story child tasks)
- `title-pattern` (string, optional) - Filter by title pattern (regex or substring match)

All parameters are optional and AND-ed together when provided.

**Returns:**

A map with three keys:
- `:task` (string) - The full task text (title + description)
- `:category` (string) - The task's category
- `:task-id` (integer) - The task's unique identifier

Returns `{:status "No matching tasks found"}` if no tasks match the filters.

**Example - Finding a story by title:**
```clojure
;; Call
{:title-pattern "user-auth"}

;; Return
{:task "Complete remaining work for EDN storage migration"
 :category "story"
 :task-id 13}
```

**Example - Finding story child tasks:**
```clojure
;; First find the story
{:title-pattern "user-auth"}  ; Returns {:task-id 13 ...}

;; Then find first incomplete child
{:parent-id 13}

;; Return
{:task "Enhance next-task tool to support filtering\n\n- Change the next-task tool..."
 :category "medium"
 :task-id 29}
```

**Usage:**
```
Use next-task with title-pattern to find story tasks, then use parent-id
to query child tasks. The returned task-id can be used with complete-task.
This replaces the deprecated next-story-task tool.
```

#### complete-task

Story tasks use the same `complete-task` tool as regular tasks. Tasks are stored in `.mcp-tasks/tasks.ednl` with parent-child relationships, where story tasks have a `:parent-id` field linking them to their parent story.

**Parameters:**
- `category` (string, required) - The task category
- `task-text` (string, required) - Partial text from the beginning of the task to verify
- `completion-comment` (string, optional) - Optional comment to append to the completed task

**Returns:**

Git mode enabled:
- Text item 1: Completion status message
- Text item 2: JSON-encoded map with `:modified-files` key containing `["tasks.ednl", "complete.ednl"]`

Git mode disabled:
- Single text item: Completion status message

**Behavior:**
- Finds the first task with matching category and `:status :open` in `tasks.ednl`
- Verifies the task text matches the provided `task-text` parameter
- Marks the task as `:status :closed`
- Optionally appends the completion comment to the `:description` field
- Moves the task from `tasks.ednl` to `complete.ednl`
- Returns an error if no matching task is found

**Example:**
```clojure
;; Call
{:category "medium"
 :task-text "Simplify story workflow"
 :completion-comment "Removed redundant tools"}

;; Return (git mode)
"Task completed and moved to .mcp-tasks/complete.ednl"
"{\"modified-files\": [\"tasks.ednl\", \"complete.ednl\"]}"
```

**Usage:**
```
Use complete-task after successfully executing any task (including story tasks)
to mark it as complete and archive it. The tool works uniformly for both
standalone tasks and story child tasks.

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
1. Finds the first incomplete task using the `next-story-task` tool:
   - Returns task-id, task-text, category, and task-index
   - If no incomplete tasks found, informs the user and stops
   - If no category is found for the task, informs the user and stops
2. Executes the task directly using the category workflow:
   - The task is already in tasks.ednl with its task-id
   - Uses the category-specific workflow from `.mcp-tasks/prompts/<category>.md`
   - Completes all implementation steps according to the category workflow
3. After successful execution, marks the task as complete:
   - Uses the `complete-task` tool with the task-id from step 1
   - Parameters: category, task-text (partial match), and optionally completion-comment
   - Task is marked as :status :closed and moved from tasks.ednl to complete.ednl
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
- Tasks are stored in `.mcp-tasks/tasks.ednl` with parent-child relationships
- Story tasks are child tasks with :parent-id pointing to the story
- The category workflow finds and executes the task by its position in the queue
- If task execution fails, the task is not marked as complete
- Branch management is optional and controlled by configuration

**Usage example:**
```
/mcp-tasks:execute-story-task user-authentication

The agent will find the next incomplete task from the user-authentication story,
execute it directly using that category's workflow, and mark it as complete
using the complete-task tool upon success.
```

## Task Categories

Categories allow you to organize tasks by type and apply different execution strategies.

### Default Categories

The system discovers categories automatically by scanning `.mcp-tasks/` subdirectories:
- `tasks/` - Active task files
- `complete/` - Completed task archives
- `prompts/` - Category-specific execution instructions (optional)

### Custom Categories

Create a new category by adding a task with that category:

```bash
# Use the add-task tool with your custom category name
# Example: {:category "my-category" :title "My first task" ...}
```

The category will be automatically discovered from existing tasks, and you can run:

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

Completed tasks have `:status :closed` and include the full task specification as an EDN map.

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

The agent will read and consider all details in the `:title` and `:description` fields when implementing the task.

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

# 2. Add tasks to a category (use the add-task MCP tool)
# The tool will append the task to tasks.ednl with category "bugfix"

# 3. Process a bugfix task
# Run: /mcp-tasks:next-bugfix
# Agent implements the fix, commits to main repo, and commits task changes to .mcp-tasks repo

# 4. Review the changes
git log -1 --stat                        # Main repo commits
cd .mcp-tasks && git log -1 --stat && cd ..  # Task tracking commits

# 5. Check completed tasks
cat .mcp-tasks/complete.ednl
```

### Example: Non-Git Mode Workflow

```bash
# 1. Create .mcp-tasks directory structure (no git init)
mkdir -p .mcp-tasks/tasks .mcp-tasks/complete .mcp-tasks/prompts

# 2. Optionally configure non-git mode explicitly
echo '{:use-git? false}' > .mcp-tasks.edn

# 3. Add tasks to a category (use the add-task MCP tool)
# The tool will append the task to tasks.ednl with category "feature"

# 4. Process a feature task
# Run: /mcp-tasks:next-feature
# Agent implements the feature, commits to main repo only

# 5. Review the changes
git log -1 --stat                # Only main repo commits

# 6. Check completed tasks (files updated without version control)
cat .mcp-tasks/complete.ednl
```

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
