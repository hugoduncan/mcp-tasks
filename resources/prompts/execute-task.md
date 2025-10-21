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

### 3. Retrieve Category Instructions

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
   - Stop execution - do not proceed to step 4

4. **Extract instructions**: The resource will return a `text` field
   containing the category-specific workflow steps

### 4. Write Execution State

Before executing the task, record execution state using the `execution-state` MCP tool:
- Use the `mcp__mcp-tasks__execution-state` tool with the following parameters:
  - `action`: "write"
  - `task-id`: The task's ID number
  - `started-at`: Current timestamp in ISO-8601 format (e.g., "2025-10-20T14:30:00Z")
  - `story-id`: Include the task's `:parent-id` value if it exists (for story tasks), otherwise omit this parameter

This creates a `.mcp-tasks-current.edn` file that external tools can use to monitor progress.

### 5. Execute the Task

Follow the category-specific instructions retrieved in step 3 to execute
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

### 6. Mark Task Complete

After successfully completing all execution steps:
- Use the `complete-task` tool to mark the task as complete
- Required parameters:
  - `task-id`: The task's ID number
  - `title`: The task's title (for verification)
- Optional parameter:
  - `completion-comment`: Any notes about the completion

The tool will automatically clear the execution state and confirm completion to the user.

## Notes

- All `select-tasks` filters are AND-ed together
- The `title-pattern` parameter accepts regex or substring matching
- Task status defaults to "open" unless specified otherwise
- If task execution fails or is blocked, do not mark the task as complete
- If task execution fails or is interrupted:
  - Do NOT clear the execution state - leave it in place
  - The stale state can be detected by external tools via the `:started-at` timestamp
  - When starting a new task, the execution state will be overwritten automatically
