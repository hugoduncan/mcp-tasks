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

**3. Retrieve Category Instructions:**

Use `ReadMcpResourceTool` with server "mcp-tasks", uri `prompt://category-<category>`. If missing, inform user and stop.

**Display parent shared context:**

If the parent story has `:parent-shared-context`, display it to provide context from previous tasks:

```
**Shared Context from Previous Tasks:**

<parent-shared-context>
```

**Context precedence:** Shared context takes precedence over static fields like `:description` or `:design` when there are conflicts or updates from previous task execution.

**4. Execute task:**

**IMPORTANT: Treat the category prompt as the primary execution guide.** You MUST follow the workflow steps defined in `prompt://category-<category>` in their specified order. Category workflows are mandatory and define the required process for executing this task. Do not deviate from the category workflow unless there are legitimate blockers or errors.

Skip refinement check. Execute by strictly adhering to the `prompt://category-<category>` resource instructions.

**While executing:** For out-of-scope issues, create task with `add-task`, link with `:discovered-during` relation via `update-task`. Continue current task. See execute-task prompt for details.

**Updating shared context:**

During task execution, update the parent story's shared context to record important information for subsequent tasks:

```clojure
;; Update parent story shared context
(update-task 
  {:task-id <parent-story-id>
   :shared-context "Your update text here"})
```

The system automatically prefixes your update with "Task NNN:" where NNN is the current task ID. Multiple updates accumulate with newest first.

**Security Note:** Do not store sensitive data (passwords, API keys, tokens, PII) in shared context. Context is stored in task files and may appear in git history and PR descriptions.

**What to add to shared context:**
- Key decisions made and their rationale
- Implementation discoveries (e.g., "function X now returns Y format")
- API or schema changes
- Dependencies added or modified
- Edge cases handled
- Deviations from original design

**When to update:**
- Update context **during execution**, not just at completion
- Add entries as you make significant decisions or discoveries
- Ask yourself: "What would the next tasks need to know?"

**Example updates:**
```clojure
;; After adding a new field
(update-task 
  {:task-id 604
   :shared-context "Added :parent-shared-context field to Task schema"})

;; After discovering implementation details
(update-task 
  {:task-id 604
   :shared-context "select-tasks tool now returns :parent-shared-context in response"})
```

**5. Finalize shared context:**

Before completing the task, review what future tasks need to know and add any missing context to the parent story.

**6. Complete:**

Call `complete-task` with `task-id`, optional `completion-comment`.

**Never complete the parent story.** Only complete child tasks. User reviews before story completion.

**On failure:** Execution state persists. Starting new task overwrites automatically.

## Category Adherence

**Category prompts define the required execution workflow.** Each category specifies mandatory steps that must be followed in their defined order. These workflows are not suggestions—they are the required process for executing tasks in that category.

**Relationship between shared context and category workflow:**

- **Category workflow is primary:** The category prompt retrieved in step 3 defines the mandatory execution process
- **Shared context supplements workflow:** The `:parent-shared-context` from previous tasks provides additional context about implementation details, decisions, and discoveries
- **Context does not replace workflow:** Even if shared context contains information about implementation approaches, you must still follow all steps defined in the category workflow
- **Use both together:** Apply the category workflow steps while incorporating knowledge from shared context

**Example:** If executing a "medium" category task:
1. Follow all steps from `prompt://category-medium` (e.g., analysis, design, implementation, testing)
2. Use `:parent-shared-context` to inform your decisions within each step
3. Do not skip workflow steps even if shared context suggests an implementation approach

**When to deviate from category workflow:**

Only deviate from the category workflow when:
- The category prompt resource is missing or malformed (handled by step 3 error)
- A legitimate blocker prevents following a specific step (document the blocker and inform the user)
- The user explicitly instructs you to modify the workflow
