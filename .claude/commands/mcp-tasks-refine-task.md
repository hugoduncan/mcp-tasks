---
description: Interactively refine a task document with user feedback
argument-hint: [task-specification] [additional-context...]
---


Refine the task through an interactive process.

Parse `$ARGUMENTS`: first token is task specification, rest is context.


| Format | Example | CLI command |
|--------|---------|-------------|
| Numeric / #N / "task N" | 59, #59, task 59 | `mcp-tasks show --task-id N` |
| Text | "Update prompt file" | `mcp-tasks list --title-pattern "..." --limit 1` |


Handle no match or multiple matches by informing user.


## Process


1. Find task via `mcp-tasks show --task-id N --format edn` or `mcp-tasks list --title-pattern "..." --limit 1 --format edn`. Extract `id`, `title`, `description`, `design`, `type`.


2. Analyze in project context: review description/design, research patterns/exemplars, examine codebase, identify forgotten aspects, check scope.

3. Display task (type, title, description, design) and analysis (context findings, affected files, patterns, forgotten aspects, scope concerns).

4. Interactive refinement:
   - Suggest improvements: clarify requirements, remove ambiguity, reduce complexity, add acceptance criteria, describe implementation
   - Get user feedback, update working copy if approved
   - Continue until satisfied
   - Don't expand scope without explicit intent

5. Save:
   - Show final task for approval

   - Call `mcp-tasks update --task-id <id> --description "..." --design "..." --meta '{"refined": "true"}'`
   - Note: Preserve existing meta fields, add `"refined": "true"`

   - Confirm save

## Scope

**Do:** Clarify requirements/acceptance criteria, identify missing context, describe implementation phases in `:design`, improve clarity/completeness, add technical notes.

**Don't:** Create child tasks, add time estimates, expand scope without confirmation.

`:design` field is ideal for implementation phases/approach. Be collaborative, get approval before changes, adapt to task type/context.