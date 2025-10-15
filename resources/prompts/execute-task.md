---
description: Execute a task based on selection criteria or context
argument-hint: [selection-criteria...]
---

Execute a task based on provided selection criteria or work with a task already in context.

## Argument Parsing

Parse the arguments: $ARGUMENTS

If arguments are provided:
- Interpret them as selection criteria for the `select-tasks` tool
- Map the criteria to the tool's parameters (category, parent-id, status, title-pattern, type, task-id)
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
  - **No matches**: Inform the user no tasks match the criteria, suggest adjustments
  - **One match**: Proceed to step 2 with this task
  - **Multiple matches**: List the matching tasks with their task-ids and titles, then ask the user to clarify which task to execute (they can provide a task-id or more specific criteria)

If no arguments were provided:
- Work with the task already in context
- Proceed directly to step 2

### 2. Retrieve Category Instructions

Once you have identified a single task:
- Extract the task's `:category` field
- Retrieve the category-specific execution instructions from the `prompt://category-<category>` resource
- If the resource is not found, inform the user that category instructions are missing for this category and stop

### 3. Execute the Task

Follow the category-specific instructions retrieved in step 2 to execute the task.

The category instructions define the execution workflow (e.g., analysis, design, implementation steps).

### 4. Mark Task Complete

After successfully completing all execution steps:
- Use the `complete-task` tool to mark the task as complete
- Required parameters:
  - `task-id`: The task's ID number
  - `title`: The task's title (for verification)
- Optional parameter:
  - `completion-comment`: Any notes about the completion

Confirm to the user that the task has been marked complete.

## Notes

- All `select-tasks` filters are AND-ed together
- The `title-pattern` parameter accepts regex or substring matching
- Task status defaults to "open" unless specified otherwise
- If task execution fails or is blocked, do not mark the task as complete
