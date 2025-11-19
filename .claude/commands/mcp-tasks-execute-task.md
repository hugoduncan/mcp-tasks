---
description: Execute a task based on selection criteria or context
argument-hint: [selection-criteria...]
---


Execute a task based on selection criteria or context.

Parse `$ARGUMENTS`. If empty, skip to step 3 (task in context).


| Format | Example | CLI command |
|--------|---------|-------------|
| Numeric / #N / "task N" | 59, #59, task 59 | `mcp-tasks show --task-id N --format edn` |
| category / category=X | simple, category=simple | `mcp-tasks list --category X --status open --limit 1 --format edn` |
| type / type=X | bug, type=bug | `mcp-tasks list --type X --status open --limit 1 --format edn` |
| parent N / parent-id=N | parent 51, parent-id=51 | `mcp-tasks list --parent-id N --status open --limit 1 --format edn` |
| status=X | status=open | `mcp-tasks list --status X --limit 1 --format edn` |
| Text / title-pattern=X | execute, title-pattern=execute | `mcp-tasks list --title-pattern X --limit 1 --format edn` |
| Multiple | category=medium type=feature | Combine filters (AND) |


## Process

### 1. Check Refinement


Check `meta` field for `"refined": "true"`. If unrefined, warn and ask: "Task has not been refined. Proceed anyway? (yes/no)". If no, suggest `/mcp-tasks-refine-task` and stop.


### 2. Validate Dependencies


Check `is-blocked` field. If blocked, retrieve blocking task titles via `blocking-task-ids`, display, and ask user: "Task is blocked. Proceed anyway? (yes/no)". If user declines, stop.


### 3. Retrieve Category Instructions


Use `mcp-tasks prompts show <category>` to retrieve the category-specific execution instructions. If missing, inform user and stop.


### 4. Prepare Environment


Set up your working environment:
- Create a feature branch if not already on one: `git checkout -b <task-id>-<task-title-slug>`
- Track which task you're working on (note the task ID for later completion)
- Ensure working directory is clean


### 5. Discovering Issues

For each out-of-scope issue you discovered while executing the task,
create a task describing the issue.

Use `mcp-tasks add --category <category> --title "..." --description "..."` to create the task,
then `mcp-tasks update --task-id <new-id> --relations '[{:id 1 :relates-to <current-task-id> :as-type :discovered-during}]'`
to link with `:discovered-during` relation.


**Capture:** Unrelated bugs, technical debt, missing tests,
documentation gaps. **Don't capture:** In-scope issues, direct blockers,
minor fixes.


Before completion, verify all discoveries captured.

### 6. Execute

Follow category instructions with task context (ID, title, description, design, type, relations). Execute workflow steps in order.

### 7. Complete


Call `mcp-tasks complete --task-id <id>` with optional `--comment "..."`. Don't complete if execution failed.
