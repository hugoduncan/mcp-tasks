---
title: Execute Story Child
description: Execute the next task from a story's task list
argument-hint: [story-specification] [additional-context...]
---

Execute the next incomplete task from the story.

{% include "infrastructure/story-parsing.md" %}

## Process

### 1. Find first unblocked incomplete child

{% if cli %}
Call `mcp-tasks list --parent-id <story-id> --status open --blocked false --limit 1 --format edn`.

Display progress using the metadata from the list command output.

**If no tasks found:** Call `mcp-tasks list --parent-id <story-id> --format edn`:
- `:open` tasks exist (all blocked): List ID, title, `blocking-task-ids`; suggest completing blockers; stop
- No `:open` tasks + completed count > 0: All tasks complete; suggest review/PR; stop
- No `:open` tasks + completed count = 0: Suggest refining story or creating tasks; stop
{% else %}
Call `select-tasks` with `parent-id: <story-id>`, `status: "open"`, `blocked: false`, `limit: 1`.

Display progress: "Task X of Y" where X = `:completed-task-count`, Y = `(+ :open-task-count :completed-task-count)`

**If no tasks found:** Call `select-tasks` with `parent-id` only:
- `:open` tasks exist (all blocked): List ID, title, `:blocking-task-ids`; suggest completing blockers; stop
- No `:open` tasks + `:completed-task-count` > 0: All tasks complete; suggest review/PR; stop
- No `:open` tasks + `:completed-task-count` = 0: Suggest refining story or creating tasks; stop
{% endif %}

**If task lacks category:** Inform user; stop

Display task to user.

### 2. Set up environment

{% if cli %}
Set up your working environment:
- Create or switch to story branch: `git checkout -b <story-id>-<story-title-slug>` (or `git checkout <branch>` if exists)
- Track which task you're working on (note the task ID for later completion)
- Display current branch and working directory status
{% else %}
Call `work-on` with `task-id: <child-task-id>`. Display environment:
worktree name/path, branch (if present).
{% endif %}

### 3. Display parent shared context

{% if cli %}
If the task has `parent-shared-context` in its data, display it to provide context from previous tasks:
{% else %}
If the parent story has `:parent-shared-context`, display it to provide
context from previous tasks:
{% endif %}

```
**Shared Context from Previous Tasks:**

<parent-shared-context>
```

**Context precedence:** Shared context takes precedence over static
fields like `:description` or `:design` when there are conflicts or
updates from previous task execution.

### 4. Retrieve Category Instructions

{% if cli %}
Use `mcp-tasks prompts show <category>` to retrieve the category-specific execution instructions. If missing, inform user and stop.
{% else %}
Use `ReadMcpResourceTool` with server "mcp-tasks", uri
`prompt://category-<category>`. If missing, inform user and stop.
{% endif %}

### 5. Execute task

Skip refinement check.

{% if cli %}
Execute by strictly adhering to the category instructions retrieved above. The prompt steps must be followed in their defined order. These workflows are not suggestions—they are the required process for executing tasks in that category.
{% else %}
Execute by strictly adhering to the `prompt://category-<category>`
resource instructions.  The prompt steps must be followed in their
defined order. These workflows are not suggestions—they are the required
process for executing tasks in that category.
{% endif %}

### 6. Update shared context

Before completing the task, ask yourself: "What would the next tasks
need to know about the work done in this task?". Update the parent
story's shared context to record important information for subsequent
tasks.

**What to add to shared context:**
- Key decisions made and their rationale
- Implementation discoveries (e.g., "function X now returns Y format")
- API or schema changes
- Dependencies added or modified
- Edge cases handled
- Deviations from original design

**Security Note:** Do not store sensitive data (passwords, API keys,
tokens, PII) in shared context. Context is stored in task files and may
appear in git history and PR descriptions.

{% if cli %}
**Example update:**
```bash
mcp-tasks update --task-id <story-id> --shared-context "Added :parent-shared-context field to Task schema"
```
{% else %}
**Example update:**
```
;; After adding a new field
(update-task
  {:task-id 604
   :shared-context "Added :parent-shared-context field to Task schema"})
```
{% endif %}

### 7. Add tasks for out-of-scope-issues

{% include "infrastructure/out-of-scope-issues.md" %}

### 8. Mark Done

Mark the task as `:done` to indicate implementation is complete but awaiting merge.

{% if cli %}
Call `mcp-tasks update --task-id <id> --status done`. Don't mark done if execution failed.
{% else %}
Call `update-task` with `task-id` and `status: "done"`. Don't mark done if execution failed.
{% endif %}

**Note:** The `:done` status indicates the implementation is complete and a PR has been created, but the changes haven't been merged yet. Call `complete-task` manually after the PR is merged to move the task to `:closed`.

**Never mark the parent story as done.** Only mark child tasks. User
reviews before story completion.

**On failure:** Execution state persists. Starting new task overwrites
automatically.
