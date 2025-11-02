# Story-Based Workflow

[← Back to Main Workflow](../workflow.md)

Stories provide a higher-level workflow for breaking down larger
features or epics into executable tasks. A story represents a cohesive
piece of work that gets refined, broken into tasks, and executed
systematically.

## Story File Structure

Stories are stored in `.mcp-tasks/tasks.ednl` as EDN records with `:type
:story`. Story tasks are stored in the same file as regular Task records
with `:parent-id` linking them to their parent story.

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

## Story Workflow Cycle

### 1. Story Refinement

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

Agent: I've found your user-auth story. Let me ask some clarifying questions:
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

### 2. Task Breakdown

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

### 3. Task Execution

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

### 4. Progress Tracking

Track story progress by asking the agent questions. The agent will query the task system and provide clear summaries.

**Example queries:**
- "Show me all tasks for story #13"
- "How many incomplete tasks does story #13 have?"
- "List completed tasks from the user-auth story"

The agent uses the `select-tasks` tool with filters like `parent-id: 13` to find story child tasks, `status: "closed"` to filter completed tasks, and combines results into readable summaries.

This agent-first approach means you don't need to learn EDN query syntax or file locations.

## Story Branch Management

When `:branch-management? true` is configured in `.mcp-tasks.edn`,
the system automatically manages git branches for stories:

**Automatic branch operations:**
- Creates a `<story-name>` branch from the default branch when starting story work
- Ensures the branch is up-to-date with the remote before task execution
- All story task commits go to the story branch
- Branch persists across multiple task executions

**Manual branch workflow (default when `:branch-management? false`):**
- You manually create and manage story branches
- System respects your current branch
- Gives you full control over branch strategy

**Example with branch management enabled:**
```bash
# Configure story branch management
echo '{:use-git? true :branch-management? true}' > .mcp-tasks.edn

# Start story work - automatically creates/switches to user-auth branch
# Run: /mcp-tasks:execute-story-task user-auth

# All subsequent task executions stay on user-auth branch until story is complete
```

## Complete Story Workflow Example

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

## Story Prompt Customization

Override the default story prompts by creating files in
`.mcp-tasks/story/prompts/`:

**Available prompts to override:**
- `refine-task.md` - Task refinement instructions
- `create-story-tasks.md` - Task breakdown instructions
- `execute-story-task.md` - Task execution workflow

### Frontmatter Format

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

### Override Precedence

The system checks for story prompt overrides in this order:

1. **Project override**: `.mcp-tasks/story/prompts/<prompt-name>.md`
   - Your team-specific customization
   - Takes precedence over built-ins
   - Can customize frontmatter and instructions

2. **Built-in default**: Resource files in the MCP server
   - System-provided story prompts
   - Used when no override exists
   - Cannot be modified without rebuilding the server

### Complete Working Example

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

This override enforces time-based categorization and explicit dependency
tracking that may not be in the default prompt.

### Relationship to Category Prompts

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
4. The task executes using the `simple` **category prompt** from
   `.mcp-tasks/prompts/simple.md`
5. After completion, the story task is marked as done

**Customization strategy:**
- Override story prompts to change how stories are managed and broken down
- Override category prompts to change how tasks are implemented
- Both can be customized independently to fit your workflow

## Story Workflow Tips

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

## See Also

- [Tools Reference](tools-reference.md) - Detailed tool and prompt documentation
- [Git Worktrees](git-worktrees.md) - Advanced worktree management for stories
- [Examples](examples.md) - Complete workflow examples
- [Main Workflow](../workflow.md) - Basic task workflow
