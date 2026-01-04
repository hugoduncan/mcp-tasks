---
title: Refine Task
description: Interactively refine a task document with user feedback
argument-hint: [task-specification] [additional-context...]
---

Refine the task through an interactive process.

{% include "infrastructure/task-parsing.md" %}

## Process

{% if cli %}
1. Find task via `mcp-tasks show --task-id N --format edn` or `mcp-tasks list --title-pattern "..." --limit 1 --format edn`. Extract `id`, `title`, `description`, `design`, `type`.
{% else %}
1. Find task via `select-tasks` (unique). Extract `:id`, `:title`, `:description`, `:design`, `:type`.
{% endif %}

2. **Clarify user intention**: Before analyzing implementation details, confirm the user's purpose. What problem are they solving? What outcome do they want? Ensure the "why" is clear before addressing the "how".

3. Analyze in project context: review description/design, research patterns/exemplars, examine codebase, identify forgotten aspects, check scope.

4. Display task (type, title, description, design) and analysis (context findings, affected files, patterns, forgotten aspects, scope concerns).

5. **Identify assumptions**: Systematically check for implicit decisions across categories:
   - **Technical approach**: Libraries, patterns, architectures not specified
   - **Scope boundaries**: What's implicitly in/out of scope
   - **User preferences**: Implementation details, naming, structure
   - **Requirements**: Inferred but not explicitly stated requirements
   - **Context**: Assumed codebase knowledge, environment, constraints

6. **Resolve assumptions**: For each assumption found:
   - State the assumption explicitly
   - Ask user to confirm or provide direction
   - Update working copy with explicit decision
   - Continue until no implicit decisions remain

7. Interactive refinement:
   - Suggest improvements: clarify requirements, remove ambiguity, reduce complexity, add acceptance criteria, describe implementation
   - Get user feedback, update working copy if approved
   - Continue until satisfied
   - Don't expand scope without explicit intent

8. Save:
   - Verify all assumptions from step 6 have been resolved with explicit user input
   - Show final task for approval
{% if cli %}
   - Call `mcp-tasks update --task-id <id> --description "..." --design "..." --meta '{"refined": "true"}'`
   - Note: Preserve existing meta fields, add `"refined": "true"`
{% else %}
   - Call `update-task`: `task-id`, `description`, `design`, `meta` (preserve existing, add `"refined": "true"`)
{% endif %}
   - Confirm save

## Scope

**Do:** Clarify requirements/acceptance criteria, identify missing context, describe implementation phases in `:design`, improve clarity/completeness, add technical notes.

**Don't:** Create child tasks, add time estimates, expand scope without confirmation.

`:design` field is ideal for implementation phases/approach. Be collaborative, get approval before changes, adapt to task type/context.
