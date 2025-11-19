---
description: Execute the next task from a story's task list
argument-hint: [story-specification] [additional-context...]
---


Execute the next incomplete task from the story.

Parse `$ARGUMENTS`: first token is story specification, rest is context.


| Format | Example | CLI command |
|--------|---------|-------------|
| Numeric / #N / "story N" | 59, #59, story 59 | `mcp-tasks show --task-id N` (verify type is story) |
| Text | "Make prompts flexible" | `mcp-tasks list --title-pattern "..." --type story --limit 1` |


Handle no match or multiple matches by informing user.


## Process

### 1. Find first unblocked incomplete child


Call `mcp-tasks list --parent-id <story-id> --blocked false --limit 1 --format edn`.

Display progress using the metadata from the list command output.

**If no unblocked tasks:** Call `mcp-tasks list --parent-id <story-id> --format edn`:
- Incomplete tasks exist (all blocked): List ID, title, `blocking-task-ids`; suggest completing blockers; stop
- No incomplete tasks + completed count > 0: All tasks complete; suggest review/PR; stop
- No incomplete tasks + completed count = 0: Suggest refining story or creating tasks; stop


**If task lacks category:** Inform user; stop

Display task to user.

### 2. Set up environment


Set up your working environment:
- Create or switch to story branch: `git checkout -b <story-id>-<story-title-slug>` (or `git checkout <branch>` if exists)
- Track which task you're working on (note the task ID for later completion)
- Display current branch and working directory status


### 3. Display parent shared context


If the task has `parent-shared-context` in its data, display it to provide context from previous tasks:


```
**Shared Context from Previous Tasks:**

<parent-shared-context>
```

**Context precedence:** Shared context takes precedence over static
fields like `:description` or `:design` when there are conflicts or
updates from previous task execution.

### 4. Retrieve Category Instructions


Use `mcp-tasks prompts show <category>` to retrieve the category-specific execution instructions. If missing, inform user and stop.


### 5. Execute task

Skip refinement check.


Execute by strictly adhering to the category instructions retrieved above. The prompt steps must be followed in their defined order. These workflows are not suggestions—they are the required process for executing tasks in that category.


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
```bash
mcp-tasks update --task-id <story-id> --shared-context "Added :parent-shared-context field to Task schema"
```


### 7. Add tasks for out-of-scope-issues

For each out-of-scope issue you discovered while executing the task,
create a task describing the issue.

Use `mcp-tasks add --category <category> --title "..." --description "..."` to create the task,
then `mcp-tasks update --task-id <new-id> --relations '[{:id 1 :relates-to <current-task-id> :as-type :discovered-during}]'`
to link with `:discovered-during` relation.


**Capture:** Unrelated bugs, technical debt, missing tests,
documentation gaps. **Don't capture:** In-scope issues, direct blockers,
minor fixes.


### 8. Complete


Call `mcp-tasks complete --task-id <id>` with optional `--comment "..."`.


**Never complete the parent story.** Only complete child tasks. User
reviews before story completion.

**On failure:** Execution state persists. Starting new task overwrites
automatically.