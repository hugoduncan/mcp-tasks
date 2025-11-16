---
description: Execute a task based on selection criteria or context
argument-hint: [selection-criteria...]
---

Execute a task based on selection criteria or context.

Parse `$ARGUMENTS`. If empty, skip to step 3 (task in context).

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

## Process

### 1. Check Refinement

Check `:meta` for `"refined": "true"`. If unrefined, warn and use `AskUserQuestion`: "Proceed anyway?" If no, suggest `/mcp-tasks:refine-task` and stop.

### 2. Validate Dependencies

Check `:is-blocked`. If blocked, retrieve blocking task titles via `:blocking-task-ids`, display, and use `AskUserQuestion`. If user declines, stop.

### 3. Retrieve Category Instructions

Use `ReadMcpResourceTool` with server "mcp-tasks", uri `prompt://category-<category>`. If missing, inform user and stop.

### 4. Prepare Environment

Call `work-on` with `task-id`. Display environment: worktree name/directory, branch (if present).

### 5. Discovering Issues

{% include "infrastructure/out-of-scope-issues.md" %}

Before completion, verify all discoveries captured.

### 6. Execute

Follow category instructions with task context (ID, title, description, design, type, relations). Execute workflow steps in order.

### 7. Complete

Call `complete-task` with `task-id`, `title`, optional `completion-comment`. Don't complete if execution failed.
