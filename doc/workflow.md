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

Stories are stored in `.mcp-tasks/tasks.ednl` as EDN records with `:type :story`. Story tasks are stored in the same file as regular Task records with `:parent-id` linking them to their parent story.

**Example story in tasks.ednl:**
```clojure
{:id 13
 :status :open
 :type :story
 :title "User Authentication Story"
 :description "## Goal
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
- Follow OAuth 2.0 patterns where applicable"
 :category "large"
 :design ""
 :meta {}
 :relations []}
```

**Example story tasks in tasks.ednl:**
```clojure
{:id 35
 :parent-id 13
 :status :open
 :type :task
 :title "Set up JWT library dependencies"
 :description "Add JWT library to deps.edn, configure for HS256 algorithm"
 :category "simple"
 :design ""
 :meta {}
 :relations []}

{:id 36
 :parent-id 13
 :status :open
 :type :task
 :title "Implement password hashing utilities"
 :description "Create namespace for bcrypt password hashing"
 :category "medium"
 :design ""
 :meta {}
 :relations []}
```

### Story Workflow Cycle

#### 1. Story Refinement

Refine a rough story idea into a detailed specification:

```
/mcp-tasks:refine-task <story-name>
```

**Process:**
- Agent finds the story record in `.mcp-tasks/tasks.ednl` by title pattern
- Asks clarifying questions about requirements, constraints, and approach
- Iteratively refines the story with your feedback
- Updates the story's `:description` field in tasks.ednl

**Example interaction:**
```
User: /mcp-tasks:refine-task user-auth

Agent: I've found your user-auth story in tasks.ednl. Let me ask some clarifying questions:
1. Which JWT library should we use?
2. What's the token expiration policy?
3. Should we support refresh tokens?
...

User: [Provides answers]

Agent: Based on your answers, here's the refined story...
[Shows updated story description]

User: Looks good!

Agent: Story updated in .mcp-tasks/tasks.ednl
```

#### 2. Task Breakdown

Break the refined story into executable tasks:

```
/mcp-tasks:create-story-tasks <story-name>
```

**Process:**
- Agent reads the story from `.mcp-tasks/tasks.ednl`
- Analyzes requirements and identifies discrete tasks
- Assigns each task to an appropriate category (simple, medium, large)
- Uses the `add-task` tool to create each task with `:parent-id` linking to the story
- Gets your approval before creating tasks
- Tasks are added to `.mcp-tasks/tasks.ednl` in dependency order

**Example tasks created:**
```clojure
{:id 40
 :parent-id 13
 :status :open
 :type :task
 :title "Set up JWT library dependencies"
 :description "Add JWT library to deps.edn, configure for HS256 algorithm, add test dependencies for token validation testing."
 :category "simple"
 :design ""
 :meta {}
 :relations []}

{:id 41
 :parent-id 13
 :status :open
 :type :task
 :title "Implement password hashing utilities"
 :description "Create namespace for bcrypt password hashing with functions for hash generation and verification. Include edge case handling for invalid inputs."
 :category "medium"
 :design ""
 :meta {}
 :relations []}

{:id 42
 :parent-id 13
 :status :open
 :type :task
 :title "Build JWT token generation endpoint"
 :description "Implement /api/auth/login endpoint that validates credentials, generates JWT token with user claims, returns token and expiry. Handle invalid credentials gracefully."
 :category "medium"
 :design ""
 :meta {}
 :relations []}
```

**Key elements:**
- Each task has `:parent-id` field linking it to the story
- Tasks stored in unified `.mcp-tasks/tasks.ednl` file
- Use `select-tasks` with `parent-id` filter to query story tasks
- Tasks are ordered by dependencies (earlier tasks first)

#### 3. Task Execution

Execute story tasks one at a time:

```
/mcp-tasks:execute-story-task <story-name>
```

**Process:**
1. Agent finds the story task using `select-tasks` with `title-pattern` and `:unique? true`
2. Finds first incomplete child using `select-tasks` with `parent-id` filter and `:limit 1`
3. Extracts the task category and details from the EDN record
4. Executes the task directly using the category's workflow
5. Upon success, marks the task as complete using `complete-task` tool
6. Task is moved from tasks.ednl to complete.ednl with `:status :closed`

**Example execution:**
```
User: /mcp-tasks:execute-story-task user-auth

Agent: Found story: User Authentication Story (ID: 13)
       Found next task: "Set up JWT library dependencies" (ID: 40)
       Category: simple

       [Executes task using simple workflow...]

       [After completion...]

       Task completed successfully and moved to complete.ednl
```

**Repeat** this command until all story tasks are complete.

#### 4. Progress Tracking

Check story progress at any time by querying the EDN files:

```bash
# View all tasks for a story
grep -A 10 ":parent-id 13" .mcp-tasks/tasks.ednl

# Count incomplete story tasks
grep -c ":parent-id 13" .mcp-tasks/tasks.ednl

# View completed story tasks
grep -A 10 ":parent-id 13" .mcp-tasks/complete.ednl
```

Completed tasks are moved to `complete.ednl` with `:status :closed`, preserving the full task context including `:parent-id`.

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
# 1. Create initial story using add-task tool
# Use the MCP add-task tool or manually add to tasks.ednl:
# {:id 50 :status :open :type :story :title "User Authentication"
#  :description "We need user authentication with JWT tokens."
#  :category "large" :design "" :meta {} :relations []}

# 2. Refine the story
# Run: /mcp-tasks:refine-task "User Authentication"
# [Interactive refinement with agent]

# 3. Break into tasks
# Run: /mcp-tasks:create-story-tasks "User Authentication"
# [Agent proposes task breakdown, you approve]
# Tasks are created in tasks.ednl with :parent-id 50

# 4. Execute tasks one by one
# Run: /mcp-tasks:execute-story-task "User Authentication"
# [First task executes and moves to complete.ednl]

# Run: /mcp-tasks:execute-story-task "User Authentication"
# [Second task executes and moves to complete.ednl]

# ... repeat until all tasks complete

# 5. Review the completed work
grep ":parent-id 50" .mcp-tasks/complete.ednl
# All story tasks with :status :closed

# 6. Merge story branch (if using branch management)
git checkout master
git merge user-authentication
```

### Story Prompt Customization

Override the default story prompts by creating files in `.mcp-tasks/story/prompts/`:

**Available prompts to override:**
- `refine-task.md` - Task refinement instructions
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

1. Find the story in `.mcp-tasks/tasks.ednl` by title pattern
2. If the story doesn't exist, inform the user and stop
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
7. Get user approval before creating tasks using the add-task tool

Task creation:
For each task, use add-task with:
- category: <category>
- title
- parent-id: {id of story}
- type: "task"
```

This override enforces time-based categorization and explicit dependency tracking that may not be in the default prompt.

#### Relationship to Category Prompts

Story prompts and category prompts serve different purposes:

**Story prompts** (`.mcp-tasks/story/prompts/*.md`):
- Guide story-level operations (refine, break down, execute)
- Handle the story workflow and task distribution
- Route tasks to appropriate categories
- Three prompts: refine-task, create-story-tasks, execute-story-task

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

#### select-tasks

The `select-tasks` tool supports optional filtering parameters that enable story task queries and returns multiple tasks:

**Parameters:**
- `category` (string, optional) - Filter by task category
- `parent-id` (integer, optional) - Filter by parent task ID (for finding story child tasks)
- `title-pattern` (string, optional) - Filter by title pattern (regex or substring match)
- `type` (string, optional) - Filter by task type (task, bug, feature, story, chore)
- `status` (string, optional) - Filter by task status (open, closed, in-progress, blocked). When omitted, filters out closed tasks (default behavior)
- `limit` (integer, optional, default: 5) - Maximum number of tasks to return
- `unique` (boolean, optional, default: false) - Error if more than one task matches (implies `:limit 1`)

All filter parameters are optional and AND-ed together when provided.

**Returns:**

A map with two keys:
- `:tasks` (vector) - Vector of task maps (empty if no matches)
- `:metadata` (map) - Selection metadata (count, total-matches, limited?)

**Example - Finding a story by title:**
```clojure
;; Call
{:title-pattern "user-auth" :unique true}

;; Return
{:tasks [{:id 13 :title "User Authentication" :category "story" ...}]
 :metadata {:count 1 :total-matches 1 :limited? false}}
```

**Example - Finding story child tasks:**
```clojure
;; First find the story
{:title-pattern "user-auth" :unique true}  ; Returns {:tasks [{:id 13 ...}] ...}

;; Then find first incomplete child
{:parent-id 13 :limit 1}

;; Return
{:tasks [{:id 29 :title "Enhance select-tasks tool" :category "medium" ...}]
 :metadata {:count 1 :total-matches 3 :limited? true}}
```

**Usage:**
```
Use select-tasks with title-pattern and unique: true to find story tasks,
then use parent-id to query child tasks. Use :limit to control how many
tasks are returned. The task :id can be used with complete-task.
```

#### complete-task

Story tasks use the same `complete-task` tool as regular tasks. Tasks are stored in `.mcp-tasks/tasks.ednl` with parent-child relationships, where story tasks have a `:parent-id` field linking them to their parent story.

**Parameters:**
- `category` (string, required) - The task category
- `title` (string, required) - Partial text from the beginning of the task to verify
- `completion-comment` (string, optional) - Optional comment to append to the completed task

**Returns:**

Git mode enabled:
- Text item 1: Completion status message
- Text item 2: JSON-encoded map with `:modified-files` key containing `["tasks.ednl", "complete.ednl"]`

Git mode disabled:
- Single text item: Completion status message

**Behavior:**
- Finds the first task with matching category and `:status :open` in `tasks.ednl`
- Verifies the task text matches the provided `title` parameter
- Marks the task as `:status :closed`
- Optionally appends the completion comment to the `:description` field
- Moves the task from `tasks.ednl` to `complete.ednl`
- Returns an error if no matching task is found

**Example:**
```clojure
;; Call
{:category "medium"
 :title "Simplify story workflow"
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

#### refine-task

Interactively refine a task with user feedback.

**Arguments:**
- `story-name` - The title or pattern matching the story to refine

**Behavior:**
1. Finds the story in `.mcp-tasks/tasks.ednl` using title pattern matching
2. If the story doesn't exist, informs the user and stops
3. Displays the current story content from the `:description` field
4. Enters an interactive refinement loop:
   - Analyzes the story for clarity, completeness, and feasibility
   - Suggests specific improvements
   - Presents suggestions to the user
   - Gets user feedback on the suggestions
   - Applies approved changes
   - Incorporates user modifications
   - Continues until user is satisfied
5. Shows the final refined story for approval
6. If approved, updates the story's `:description` field in tasks.ednl
7. Confirms the update operation

**Key characteristics:**
- Collaborative and iterative process
- Always gets explicit user approval before making changes
- Focuses on improving clarity, completeness, and actionability
- Does not make assumptions about requirements - asks for clarification

**Usage example:**
```
/mcp-tasks:refine-task "User Authentication"

The agent will find the User Authentication task in tasks.ednl, analyze it,
and guide you through an interactive refinement process to improve the task's
clarity and completeness.
```

#### create-story-tasks

Break down a story into categorized, executable tasks.

**Arguments:**
- `story-name` - The title or pattern matching the story to break down

**Behavior:**
1. Finds the story in `.mcp-tasks/tasks.ednl` using title pattern matching
2. If the story doesn't exist, informs the user and stops
3. Displays the story content from the `:description` field
4. Analyzes the story and breaks it down into specific, actionable tasks:
   - Each task is concrete and achievable
   - Tasks follow a logical sequence (dependencies first)
   - Related tasks are grouped logically
5. Applies category selection guidance:
   - `simple` - Straightforward tasks, small changes, documentation updates
   - `medium` - Tasks requiring analysis and design, moderate complexity
   - `large` - Complex tasks needing extensive planning and implementation
   - `clarify-task` - Tasks that need clarification before execution
6. Presents the task breakdown to the user with category assignments
7. Gets user feedback and makes adjustments
8. Once approved, creates each task using the `add-task` tool:
   - Sets `:parent-id` to link the task to the story
   - Assigns the appropriate `:category`
   - Sets `:type` (typically "task", "bug", or "feature")
   - Includes reference to story in description
   - Tasks are added in dependency order
9. Confirms task creation with count and next steps

**Task creation parameters:**
- `category`: The selected category (simple, medium, large, clarify-task)
- `title`: Task title
- `description`: Task description (optional, multiline supported)
- `parent-id`: Parent story's task ID (optional, for story tasks)
- `type`: "task", "bug", "feature", or "chore"

**Key characteristics:**
- Task descriptions are specific enough to be actionable without additional context
- Tasks are stored in unified `.mcp-tasks/tasks.ednl` with `:parent-id` linking
- Use `select-tasks` with `parent-id` filter to query story tasks
- Multi-line descriptions are supported in the `:description` field
- Tasks are ordered to respect dependencies

**Usage example:**
```
/mcp-tasks:create-story-tasks "User Authentication"

The agent will find the User Authentication story in tasks.ednl, break it down
into discrete tasks, assign categories, and create them using the add-task tool
after your approval.
```

#### execute-story-task

Execute the next task from a story.

**Arguments:**
- `story-name` - The title or pattern matching the story

**Behavior:**
1. Finds the story and its first incomplete child task:
   - First, uses `select-tasks` with `title-pattern` and `unique: true` to find the story in tasks.ednl
   - Then uses `select-tasks` with `parent-id` filter and `:limit 1` to get the first incomplete child
   - If no incomplete tasks found, informs the user and stops
   - If no category is found for the task, informs the user and stops
2. Executes the task directly using the category workflow:
   - The task is in tasks.ednl with its `:id` and `:parent-id`
   - Uses the category-specific workflow from `.mcp-tasks/prompts/<category>.md`
   - Completes all implementation steps according to the category workflow
3. After successful execution, marks the task as complete:
   - Uses the `complete-task` tool with category and title
   - Parameters: category, title (partial match), and optionally completion-comment
   - Task is marked as `:status :closed` and moved from tasks.ednl to complete.ednl
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
- All data stored in `.mcp-tasks/tasks.ednl` with parent-child relationships via `:parent-id`
- Story tasks are EDN records with `:parent-id` pointing to the story's `:id`
- The category workflow finds and executes the task by its position in the queue
- If task execution fails, the task is not marked as complete
- Branch management is optional and controlled by configuration

**Usage example:**
```
/mcp-tasks:execute-story-task "User Authentication"

The agent will find the next incomplete task from the User Authentication story
in tasks.ednl, execute it using that category's workflow, and mark it complete
using the complete-task tool upon success.
```

## Task Categories

Categories allow you to organize tasks by type and apply different execution strategies.

### Default Categories

The system discovers categories automatically from:
- Tasks in `tasks.ednl` - Each task's `:category` field defines its category
- Prompts in `.mcp-tasks/prompts/` - Optional category-specific execution instructions (e.g., `simple.md`, `medium.md`)

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

1. **Separate task streams** - Each worktree can have its own `.mcp-tasks/tasks.ednl` with relevant tasks

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
