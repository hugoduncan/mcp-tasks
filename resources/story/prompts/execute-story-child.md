---
title: Execute Story Child
description: Execute the next task from a story's task list
argument-hint: [story-specification] [additional-context...]
---

Execute the next incomplete task from the story.

Parse `$ARGUMENTS`: first token is story specification, rest is context.

| Format | Example | select-tasks params |
|--------|---------|---------------------|
| Numeric / #N / "story N" | 59, #59, story 59 | `task-id: N, type: story, unique: true` |
| Text | "Make prompts flexible" | `title-pattern: "...", type: story, unique: true` |

Handle no match or multiple matches by informing user.

## Process

**1. Find first unblocked incomplete child:**

Call `select-tasks` with `parent-id: <story-id>`, `blocked: false`, `limit: 1`.

Display progress: "Task X of Y" where X = `:completed-task-count`, Y = `(+ :open-task-count :completed-task-count)`

**If no unblocked tasks:** Call `select-tasks` with `parent-id` only:
- Incomplete tasks exist (all blocked): List ID, title, `:blocking-task-ids`; suggest completing blockers; stop
- No incomplete + `:completed-task-count` > 0: All tasks complete; suggest review/PR; stop
- No incomplete + `:completed-task-count` = 0: Suggest refining story or creating tasks; stop

**If task lacks category:** Inform user; stop

Display task to user.

**2. Set up environment:**

Call `work-on` with `task-id: <child-task-id>`. Display environment: worktree name/path, branch (if present).

**3. Execute task:**

Skip refinement check. Execute by following `prompt://category-<category>` resource.

**While executing:** For out-of-scope issues, create task with `add-task`, link with `:discovered-during` relation via `update-task`. Continue current task. See execute-task prompt for details.

**4. Complete:**

Call `complete-task` with `task-id`, optional `completion-comment`.

**Never complete the parent story.** Only complete child tasks. User reviews before story completion.

**On failure:** Execution state persists. Starting new task overwrites automatically.
