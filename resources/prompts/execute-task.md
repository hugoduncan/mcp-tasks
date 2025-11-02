---
description: Execute a task based on selection criteria or context
argument-hint: [selection-criteria...]
---

Execute a task based on provided selection criteria or work with a task
already in context.

## Argument Parsing

Parse the arguments: $ARGUMENTS

If arguments are provided:
- Interpret them as selection criteria for the `select-tasks` tool
- Map the criteria to the tool's parameters (category, parent-id,
  status, title-pattern, type, task-id)
- Examples of argument formats you might encounter:
  - "category=simple" or "simple" → category filter
  - "parent-id=51" or "parent 51" → parent-id filter
  - "type=bug" or "bug" → type filter
  - "status=open" → status filter
  - "title-pattern=execute" → title-pattern filter
  - Multiple criteria: "category=medium type=feature"

If no arguments are provided:
- Assume a task is already in context (from previous conversation or selection)
- Skip directly to task execution steps

## Process

### 1. Find the Task

If arguments were provided:
- Use the `select-tasks` tool with the interpreted parameters
- Do not include `limit` or `unique` parameters initially
- Handle the response:
  - **No matches**: Inform the user no tasks match the criteria, suggest
    adjustments
  - **One match**: Proceed to step 2 with this task
  - **Multiple matches**: List the matching tasks with their task-ids
    and titles, then ask the user to clarify which task to execute (they
    can provide a task-id or more specific criteria)

If no arguments were provided:
- Work with the task already in context
- Proceed directly to step 2

### 2. Check Refinement Status

Once you have identified the task to execute:

1. **Check if refined**: Examine the task's `:meta` map for the key `"refined"`
   - If the value is `"true"`, the task has been refined - proceed to step 3
   - If the key is absent or has any other value, the task is unrefined - continue to step 2

2. **Warn and confirm**: If the task is not refined:
   - Display warning: "⚠️  This task has not been refined. Running unrefined tasks may lead to unclear requirements or scope creep."
   - Use the `AskUserQuestion` tool to ask: "Do you want to proceed anyway?"
     - Options:
       - "Yes, proceed" - continue to step 3
       - "No, refine first" - stop execution
   - If user chooses "No, refine first":
     - Suggest: "You can refine this task by running `/mcp-tasks:refine-task <task-id>`"
     - Stop execution - do not proceed to step 3
   - If user chooses "Yes, proceed":
     - Continue to step 3

### 3. Validate Task Dependencies

Once you have confirmed the task:

1. **Check if task is blocked**: Examine the task for `:is-blocked` field
   - If the field is absent or `false`, the task is not blocked - proceed to step 4
   - If `true`, the task is blocked by other incomplete tasks - continue to step 2

2. **Display blocking information**: If the task is blocked:
   - Get the `:blocking-task-ids` vector from the task (if available)
   - For each blocking task ID, retrieve the task title using `select-tasks` with `task-id` filter
   - Display error: "⚠️  Task #<task-id> is blocked by the following incomplete tasks:"
   - List each blocking task: "#<blocking-id>: <blocking-title>"

3. **Ask user to proceed**: Use the `AskUserQuestion` tool:
   - Question: "This task is blocked by other incomplete tasks. Do you want to proceed anyway?"
   - Options:
     - "Yes, proceed" - continue to step 4 with a warning
     - "No, complete blocking tasks first" - stop execution
   - If user chooses "No, complete blocking tasks first":
     - Suggest: "Complete the blocking tasks first, then try executing this task again"
     - Stop execution - do not proceed to step 4
   - If user chooses "Yes, proceed":
     - Display warning: "⚠️  Proceeding with blocked task. This may cause issues if dependencies are not met."
     - Continue to step 4

### 4. Retrieve Category Instructions

Once you have identified a single task:

1. **Extract the category**: Get the `:category` field from the task
   (e.g., "simple", "medium", "large")

2. **Retrieve category instructions**: Use the `ReadMcpResourceTool` to
   fetch the category-specific instructions:
   - `server`: "mcp-tasks"
   - `uri`: "prompt://category-<category>" (replace `<category>` with
     the task's category value)
   - Example: For a task with `:category "medium"`, use URI
     `"prompt://category-medium"`

3. **Handle missing resources**: If the `ReadMcpResourceTool` returns an
   error or the resource is not found:
   - Inform the user: "Category instructions for '<category>' are not
     available. Please ensure the category prompt resource exists or
     contact the maintainer."
   - Stop execution - do not proceed to step 5

4. **Extract instructions**: The resource will return a `text` field
   containing the category-specific workflow steps

### 5. Prepare Task Environment

Before executing the task, set up the task environment using the `work-on` MCP tool:
- Use the `mcp__mcp-tasks__work-on` tool with the following parameter:
  - `task-id`: The task's ID number

This tool automatically:
- Records execution state in `.mcp-tasks-current.edn` for external monitoring
- Handles optional branch management (creates/switches to task branch if configured)
- Handles optional worktree management (creates/switches to task worktree if configured)
- Returns task details for confirmation

After calling the work-on tool, display the working environment context:
- If the response includes `:worktree-name` and `:worktree-path`, display:
    Worktree: <worktree-name>
    Directory: <worktree-path>
- If the response includes `:branch-name`, display:
  "Branch: <branch-name>"
- Format this as a clear header before proceeding with task execution

Example output:
```
Worktree: mcp-tasks-fix-bug
Directory: /Users/duncan/projects/mcp-tasks-fix-bug
Branch: fix-bug
```

### 6. Discovering Issues Beyond Current Scope

While executing this task, you may notice issues, bugs, or improvements that are **beyond the current task scope**. When you encounter such issues:

1. **Create a task immediately** using the `add-task` tool:
   - Choose appropriate category (simple, medium, large, clarify-task)
   - Write clear title and description
   - Set appropriate type (:bug, :chore, :task, :feature)

2. **Link the discovery** using the `update-task` tool after creation:
   - Add a relation with `:as-type :discovered-during` pointing to the current task
   - Example: If current task ID is 123 and new task ID is 456:
     ```
     mcp__mcp-tasks__update-task
       task-id: 456
       relations: [{:id 1, :relates-to 123, :as-type :discovered-during}]
     ```
   - This creates an audit trail
   - Note: Current task ID is available from the task context in step 4

3. **Continue with your current task** - don't get sidetracked

4. **Before marking the task complete**: Do a final check to ensure all discovered issues have been captured as tasks

**Examples of issues to capture:**
- Unrelated bugs in nearby code
- Technical debt in files you reviewed
- Missing test coverage in unrelated areas
- Documentation gaps discovered
- Code quality issues outside current scope

**Don't create tasks for:**
- Issues within current task scope (handle directly)
- Direct blockers (use `:blocked-by` instead)
- Minor fixes in code you're modifying (fix now)

### 7. Execute the Task

Follow the category-specific instructions retrieved in step 4 to execute
the task:

1. **Provide task context**: When executing the category instructions,
   keep the following task information in mind:
   - Task ID: `<task-id>`
   - Title: `<title>`
   - Description: `<description>`
   - Design notes: `<design>` (if provided)
   - Type: `<type>`
   - Relations: `<relations>` (if any)

2. **Follow the workflow**: The category instructions define a workflow
   with specific steps (e.g., analysis, design, planning,
   implementation). Execute each step in order.

### 8. Mark Task Complete

After successfully completing all execution steps:
- Use the `complete-task` tool to mark the task as complete
- Required parameters:
  - `task-id`: The task's ID number
  - `title`: The task's title (for verification)
- Optional parameter:
  - `completion-comment`: Any notes about the completion

The tool will automatically clear the task environment state and confirm completion to the user.

## Notes

- All `select-tasks` filters are AND-ed together
- The `title-pattern` parameter accepts regex or substring matching
- Task status defaults to "open" unless specified otherwise
- If task execution fails or is blocked, do not mark the task as complete
- If task execution fails or is interrupted:
  - The task environment state remains in place for external tools to detect
  - External tools can detect stale executions via the `:task-start-time` timestamp
  - When starting a new task, the `work-on` tool will overwrite the previous state automatically
