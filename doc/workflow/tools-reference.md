# Story Tools and Prompts Reference

[← Back to Main Workflow](../workflow.md) | [← Back to Story Workflow](story-workflow.md)

This document provides detailed technical reference for story-related tools and prompts.

## Story Tools

### select-tasks

The `select-tasks` tool supports optional filtering parameters that
enable story task queries and returns multiple tasks:

**Parameters:**
- `category` (string, optional) - Filter by task category
- `parent-id` (integer, optional) - Filter by parent task ID (for
  finding story child tasks)
- `title-pattern` (string, optional) - Filter by title pattern (regex or
  substring match)
- `type` (string, optional) - Filter by task type (task, bug, feature,
  story, chore)
- `status` (string, optional) - Filter by task status (open, closed,
  in-progress, blocked). When omitted, filters out closed tasks (default
  behavior)
- `limit` (integer, optional, default: 5) - Maximum number of tasks to return
- `unique` (boolean, optional, default: false) - Error if more than one
  task matches (implies `:limit 1`)

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

### complete-task

Story tasks use the same `complete-task` tool as regular tasks. Tasks
are stored in `.mcp-tasks/tasks.ednl` with parent-child relationships,
where story tasks have a `:parent-id` field linking them to their parent
story.

**Parameters:**
- `category` (string, required) - The task category
- `title` (string, required) - Partial text from the beginning of the
  task to verify
- `completion-comment` (string, optional) - Optional comment to append
  to the completed task

**Returns:**

Git mode enabled:
- Text item 1: Completion status message
- Text item 2: JSON-encoded map with `:modified-files` key containing
  `["tasks.ednl", "complete.ednl"]`

Git mode disabled:
- Single text item: Completion status message

**Behavior:**
- Finds the first task with matching category and `:status :open` in
  `tasks.ednl`
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

## Story Prompts

### refine-task

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

### create-story-tasks

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
- Task descriptions are specific enough to be actionable without
  additional context
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

### execute-story-task

Execute the next task from a story.

**Arguments:**
- `story-name` - The title or pattern matching the story

**Behavior:**
1. Finds the story and its first incomplete child task:
   - First, uses `select-tasks` with `title-pattern` and `unique: true`
     to find the story in tasks.ednl
   - Then uses `select-tasks` with `parent-id` filter and `:limit 1` to
     get the first incomplete child
   - If no incomplete tasks found, informs the user and stops
   - If no category is found for the task, informs the user and stops
2. Executes the task directly using the category workflow:
   - The task is in tasks.ednl with its `:id` and `:parent-id`
   - Uses the category-specific workflow from `.mcp-tasks/prompts/<category>.md`
   - Completes all implementation steps according to the category workflow
3. After successful execution, marks the task as complete:
   - Uses the `complete-task` tool with category and title
   - Parameters: category, title (partial match), and optionally
     completion-comment
   - Task is marked as `:status :closed` and moved from tasks.ednl to
     complete.ednl
   - Confirms completion to the user

**Branch management (conditional):**

If configuration includes `:branch-management? true`:
1. Before starting task execution:
   - Checks if currently on a branch named `<story-name>`
   - If not, checks out the default branch, ensures it's up to date with
     origin, then creates the `<story-name>` branch
2. After task completion:
   - Remains on the `<story-name>` branch for the next task
   - Does not merge or push automatically

If `:branch-management?` is false (default):
- Executes tasks on the current branch without any branch operations

**Key characteristics:**
- All data stored in `.mcp-tasks/tasks.ednl` with parent-child
  relationships via `:parent-id`
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

## See Also

- [Story Workflow](story-workflow.md) - Complete story workflow guide
- [Main Workflow](../workflow.md) - Basic task workflow
