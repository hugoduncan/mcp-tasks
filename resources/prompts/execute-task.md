---
description: Execute a task based on selection criteria or context
argument-hint: [selection-criteria...]
---

Execute a task based on selection criteria or context.

Parse `$ARGUMENTS`. If empty, skip to step 3 (task in context).

{% if cli %}
| Format | Example | CLI command |
|--------|---------|-------------|
| Numeric / #N / "task N" | 59, #59, task 59 | `mcp-tasks show --task-id N --format edn` |
| category / category=X | simple, category=simple | `mcp-tasks list --category X --status open --limit 1 --format edn` |
| type / type=X | bug, type=bug | `mcp-tasks list --type X --status open --limit 1 --format edn` |
| parent N / parent-id=N | parent 51, parent-id=51 | `mcp-tasks list --parent-id N --status open --limit 1 --format edn` |
| status=X | status=open | `mcp-tasks list --status X --limit 1 --format edn` |
| Text / title-pattern=X | execute, title-pattern=execute | `mcp-tasks list --title-pattern X --limit 1 --format edn` |
| Multiple | category=medium type=feature | Combine filters (AND) |
{% else %}
| Format | Example | select-tasks params |
|--------|---------|---------------------|
| Numeric / #N / "task N" | 59, #59, task 59 | `task-id: N` |
| category / category=X | simple, category=simple | `category: "X"` |
| type / type=X | bug, type=bug | `type: X` |
| parent N / parent-id=N | parent 51, parent-id=51 | `parent-id: N` |
| status=X | status=open | `status: X` |
| Text / title-pattern=X | execute, title-pattern=execute | `title-pattern: "X"` |
| Multiple | category=medium type=feature | Combine filters (AND) |

Call `select-tasks` (omit `limit`, `unique`). Handle no match, one match, or multiple matches.
{% endif %}

## Process

### 1. Check Refinement

{% if cli %}
Check `meta` field for `"refined": "true"`. If unrefined, warn and ask: "Task has not been refined. Proceed anyway? (yes/no)". If no, suggest `/mcp-tasks-refine-task` and stop.
{% else %}
Check `:meta` for `"refined": "true"`. If unrefined, warn and use `AskUserQuestion`: "Proceed anyway?" If no, suggest `/mcp-tasks:refine-task` and stop.
{% endif %}

### 2. Validate Dependencies

{% if cli %}
Check `is-blocked` field. If blocked, retrieve blocking task titles via `blocking-task-ids`, display, and ask user: "Task is blocked. Proceed anyway? (yes/no)". If user declines, stop.
{% else %}
Check `:is-blocked`. If blocked, retrieve blocking task titles via `:blocking-task-ids`, display, and use `AskUserQuestion`. If user declines, stop.
{% endif %}

### 3. Retrieve Category Instructions

{% if cli %}
Use `mcp-tasks prompts show <category>` to retrieve the category-specific execution instructions. If missing, inform user and stop.
{% else %}
Use `ReadMcpResourceTool` with server "mcp-tasks", uri `prompt://category-<category>`. If missing, inform user and stop.
{% endif %}

### 4. Prepare Environment

{% if cli %}
Set up your working environment:
- Create a feature branch if not already on one: `git checkout -b <task-id>-<task-title-slug>`
- Track which task you're working on (note the task ID for later completion)
- Ensure working directory is clean
{% else %}
Call `work-on` with `task-id`. Display environment: worktree name/directory, branch (if present).
{% endif %}

### 5. Discovering Issues

{% include "infrastructure/out-of-scope-issues.md" %}

Before completion, verify all discoveries captured.

### 6. Execute

Follow category instructions with task context (ID, title, description, design, type, relations). Execute workflow steps in order.

### 7. Mark Done

Mark the task as `:done` to indicate implementation is complete but awaiting merge.

{% if cli %}
Call `mcp-tasks update --task-id <id> --status done`. Don't mark done if execution failed.
{% else %}
Call `update-task` with `task-id` and `status: "done"`. Don't mark done if execution failed.
{% endif %}

**Note:** The `:done` status indicates the implementation is complete and a PR has been created, but the changes haven't been merged yet. Call `complete-task` manually after the PR is merged to move the task to `:closed`.
