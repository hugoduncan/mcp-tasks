---
title: Review Task Implementation
description: Review the implementation of a task before completion
argument-hint: [task-specification] [additional-context...]
---

Review the implementation of a task against its description, design, and code quality standards.

{% include "infrastructure/task-parsing.md" %}

## Process

### 1. Identify Task

Determine the task to review:

{% if cli %}
- **Primary:** Read `task-id` from `.mcp-tasks-current.edn` (execution state file)
  ```bash
  cat .mcp-tasks-current.edn
  ```
- **Fallback:** If no execution state or `$ARGUMENTS` provided, parse task specification from arguments
- If neither available, inform user and stop

Retrieve task details:
```bash
mcp-tasks show --task-id N --format edn
```
{% else %}
- **Primary:** Read `:task-id` from `.mcp-tasks-current.edn` (execution state file)
- **Fallback:** If no execution state or `$ARGUMENTS` provided, parse task specification from arguments
- If neither available, inform user and stop

Retrieve task details using `select-tasks` with `task-id: N, unique: true`.
{% endif %}

Extract the task's `id`, `title`, `description`, and `design` fields.

### 2. Analyze Implementation

Analyze all changes on the current branch against the task specification:

- Does the code implement the task correctly per its `description`?
- Does it follow the `design` notes (if present)?
- Does it have extra functionality that was not requested?
- Could the logic be simplified while remaining clear?

### 3. Review Code Quality

Analyze the quality of the implementation:

- **Simplicity:** Is it as simple as possible? Is there unnecessary complexity?
- **DRY:** Is the code DRY (Don't Repeat Yourself)?
- **Naming:** Are names descriptive and consistent with the codebase?
- **Error handling:** Check error handling, input validation, and edge cases
- **Readability:** Is the code readable and self-documenting?

### 4. Review Code Structure

Analyze the structure of the code:

- Separation of concerns
- Single responsibility
- Appropriate use of namespaces/modules for grouping code

### 5. Present Findings

Report findings as a numbered list:

- List issues clearly (e.g., "1. Add error handling...", "2. Simplify conditional...")
- Suggest improvements in numbered format
- Include specific code locations where applicable

### 6. Implement Selected Improvements

After presenting findings, ask the user:

> "Would you like to implement any of these improvements? (enter numbers separated by commas, e.g., '1,3' or 'all' for all suggestions, or 'none' to skip)"

**If user selects improvements:**
- Implement each selected improvement immediately
- Commit changes after implementing improvements
- Report what was implemented

**If user selects 'none':**
- Skip implementation and proceed to recording

**Note:** Unlike story reviews, task reviews do NOT create follow-up tasks. Improvements are either implemented immediately or deferred by the user.

### 7. Record Review Completion

{% if cli %}
After the review is complete, mark the task as reviewed:
```bash
mcp-tasks update --task-id <task-id> --code-reviewed "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
```
{% else %}
After the review is complete, call `update-task` to record the review timestamp:
- `task-id`: the task's ID from step 1
- `code-reviewed`: current timestamp in ISO-8601 format (e.g., "2025-01-15T14:30:00Z")

Example:
```
update-task(task-id=42, code-reviewed="2025-01-15T14:30:00Z")
```
{% endif %}

Note: If the task is reviewed multiple times, the timestamp is overwritten to reflect the most recent review.

## Notes

- All suggestions should be numbered from the start to make selection easy
- This differs from story review: improvements are implemented immediately, not as follow-up tasks
- The `code-reviewed` timestamp indicates the task has been quality checked
