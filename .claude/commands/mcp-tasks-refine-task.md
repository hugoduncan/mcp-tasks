---
description: Interactively refine a task document with user feedback
argument-hint: [task-specification] [additional-context...]
---


Refine the task through an interactive process.

Parse `$ARGUMENTS`: first token is task specification, rest is context.


| Format | Example | select-tasks params |
|--------|---------|---------------------|
| Numeric / #N / "task N" | 59, #59, task 59 | `task-id: N, unique: true` |
| Text | "Update prompt file" | `title-pattern: "...", unique: true` |


Handle no match or multiple matches by informing user.


## Process


1. Find task via `select-tasks` (unique). Extract `:id`, `:title`, `:description`, `:design`, `:type`.


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

   - Call `update-task`: `task-id`, `description`, `design`, `meta` (preserve existing, add `"refined": "true"`)

   - Confirm save

## Scope

**Do:** Clarify requirements/acceptance criteria, identify missing context, describe implementation phases in `:design`, improve clarity/completeness, add technical notes.

**Don't:** Create child tasks, add time estimates, expand scope without confirmation.

`:design` field is ideal for implementation phases/approach. Be collaborative, get approval before changes, adapt to task type/context.