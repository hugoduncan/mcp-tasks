---
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

### 1. Find first unblocked incomplete child


Call `select-tasks` with `parent-id: <story-id>`, `blocked: false`, `limit: 1`.

Display progress: "Task X of Y" where X = `:completed-task-count`, Y = `(+ :open-task-count :completed-task-count)`

**If no unblocked tasks:** Call `select-tasks` with `parent-id` only:
- Incomplete tasks exist (all blocked): List ID, title, `:blocking-task-ids`; suggest completing blockers; stop
- No incomplete + `:completed-task-count` > 0: All tasks complete; suggest review/PR; stop
- No incomplete + `:completed-task-count` = 0: Suggest refining story or creating tasks; stop


**If task lacks category:** Inform user; stop

Display task to user.

### 2. Set up environment


Call `work-on` with `task-id: <child-task-id>`. Display environment:
worktree name/path, branch (if present).


### 3. Display parent shared context


If the parent story has `:parent-shared-context`, display it to provide
context from previous tasks:


```
**Shared Context from Previous Tasks:**

<parent-shared-context>
```

**Context precedence:** Shared context takes precedence over static
fields like `:description` or `:design` when there are conflicts or
updates from previous task execution.

### 4. Retrieve Category Instructions


Use `ReadMcpResourceTool` with server "mcp-tasks", uri
`prompt://category-<category>`. If missing, inform user and stop.


### 5. Execute task

Skip refinement check.


Execute by strictly adhering to the `prompt://category-<category>`
resource instructions.  The prompt steps must be followed in their
defined order. These workflows are not suggestions—they are the required
process for executing tasks in that category.


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


**Example update:**
```
;; After adding a new field
(update-task
  {:task-id 604
   :shared-context "Added :parent-shared-context field to Task schema"})
```


### 7. Add tasks for out-of-scope-issues

For each out-of-scope issue you discovered while executing the task,
create a task describing the issue.

Use `add-task`, link with
`:discovered-during` relation via `update-task`.


**Capture:** Unrelated bugs, technical debt, missing tests,
documentation gaps. **Don't capture:** In-scope issues, direct blockers,
minor fixes.


### 8. Complete


Call `complete-task` with `task-id`, optional `completion-comment`.


**Never complete the parent story.** Only complete child tasks. User
reviews before story completion.

**Never clear execution state.** The `complete-task` tool automatically
preserves story-level tracking when completing child tasks. Do not call
`execution-state action:"clear"`.

**On failure:** Execution state persists. Starting new task overwrites
automatically.