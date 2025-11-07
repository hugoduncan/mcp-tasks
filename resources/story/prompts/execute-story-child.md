---
title: Execute Story Child
description: Execute the next task from a story's task list
argument-hint: [story-specification] [additional-context...]
---

Execute the next incomplete task from the story.

Parse arguments from $ARGUMENTS: first word/token is story specification, remainder is additional context.

## Parse Story Specification

| Format | Example | Treatment |
|--------|---------|-----------|
| Numeric | "59" | Use as task-id |
| Hash-prefixed | "#59" | Strip "#", use as task-id |
| Story N pattern | "story 59" | Extract N, use as task-id |
| Text | "Make prompts flexible" | Use as title-pattern |

Call `select-tasks`:
- For task-id: `task-id: N, type: story, unique: true`
- For title-pattern: `title-pattern: "...", type: story, unique: true`
- If no match: Inform user, suggest checking available stories
- If multiple matches: List with IDs, ask for clarification

## Process

**1. Find first unblocked incomplete child:**

Call `select-tasks` with `parent-id: <story-id>`, `blocked: false`, `limit: 1`.

Display progress: "Task X of Y" where X = `:completed-task-count`, Y = `(+ :open-task-count :completed-task-count)`

**If no unblocked tasks returned:**
- Call `select-tasks` again with `parent-id` only (no `blocked` filter)
- If incomplete tasks exist (all blocked):
  - Display: "All remaining tasks are blocked. Blocked tasks:"
  - List each: ID, title, blocking task IDs (from `:blocking-task-ids`)
  - Suggest completing blockers first
  - Stop
- If no incomplete tasks:
  - If `:completed-task-count` > 0:
    - Inform all tasks complete
    - Suggest reviewing implementation or creating PR
    - Stop
  - If `:completed-task-count` = 0:
    - If story not refined: suggest refining story
    - If story refined: suggest creating story tasks
    - Stop

**If task has no category:** Inform user, stop

Display the task to user.

**2. Set up environment:**

Call `work-on` with `task-id: <story-id>`.

Display environment context (if present in response):
- Worktree: `<worktree-name>` at `<worktree-path>`
- Branch: `<branch-name>`

**3. Execute task:**

Do NOT check task refinement status. Story tasks are already in the queue and the category workflow will find them. Execute the task by following the `prompt://category-<category>` resource prompt.

**While executing:** Watch for out-of-scope issues:
- Create tasks immediately with `add-task`
- Link via `update-task` with `:discovered-during` relation: `{:id 1, :relates-to <current-task-id>, :as-type :discovered-during}`
- Continue current task without sidetracking
- Final check before completion to capture all discoveries
- See execute-task prompt's "Discovering Issues Beyond Current Scope" for details

**4. Complete task:**

Call `complete-task` with `task-id: <task-id>` and optional `completion-comment`.

**IMPORTANT:** Never complete the parent story task. Only complete individual child tasks. User must review before declaring story complete.

**On failure/interruption:** Execution state persists (managed by `work-on`). External tools detect stale execution via `:task-start-time`. Starting new task overwrites state automatically.
