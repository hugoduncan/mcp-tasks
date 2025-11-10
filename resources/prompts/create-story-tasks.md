---
title: Create Story Tasks
description: Break down a story into categorized, executable tasks
argument-hint: [story-specification] [additional-context...]
---

Create task breakdown for story. Don't implement.

Parse `$ARGUMENTS`: first token is story specification, rest is context.

| Format | Example | select-tasks params |
|--------|---------|---------------------|
| Numeric / #N / "story N" | 59, #59, story 59 | `task-id: N, type: story, unique: true` |
| Text | "Make prompts flexible" | `title-pattern: "...", type: story, unique: true` |

## Process

1. Retrieve story via `select-tasks` with `type: story, unique: true`. Handle no match or multiple matches.

2. Check `:meta` for `"refined": "true"`. If unrefined, warn and use `AskUserQuestion`. If user declines, suggest `/mcp-tasks:refine-task` and stop.

3. Display story to user.

4. Break down:
   - Logical sequence (dependencies first), group related steps
   - Each task: implementation + verification (don't split "implement X" and "test X")
   - Clear, concrete, self-contained descriptions with appropriate category
   - Identify dependencies: implementation order, data flow, component dependencies

## Task Categorization

Choose categories from the add-task tool list.

**Examples:**
- "Update README with installation instructions" → **simple** (direct documentation change)
- "Add user profile editing with avatar upload" → **medium** (multiple fields, file handling, validation)
- "Implement real-time notifications with WebSocket fallback" → **large** (architecture, multiple protocols, state management)

## Self-Contained Tasks

Each task must be executable in a single agent session. Include both implementation and verification:

**Good:** "Add user authentication with password hashing and write unit tests"
**Bad:** Split into "Implement authentication" and "Test authentication" (creates unnecessary dependency)

**Good:** "Refactor database queries to use connection pooling and verify performance improvement"
**Bad:** "Refactor database queries" (missing verification step)

## Task Breakdown Example

For story "Add user profile management":

1. **simple**: "Add user profile data model with fields (name, email, avatar_url) and write unit tests" (Part of story: 42 "Add user profile management")
2. **medium**: "Implement profile edit API endpoint with validation and integration tests" (Part of story: 42 "Add user profile management")
   - Relations: `[{"id": 1, "relates-to": 1, "as-type": "blocked-by"}]`
3. **simple**: "Add profile display component with avatar and edit button" (Part of story: 42 "Add user profile management")
   - Relations: `[{"id": 1, "relates-to": 2, "as-type": "blocked-by"}]`

## Dependency Modeling

Use `blocked-by` relations when task B requires task A's output:

**Example:** Task 2 (API endpoint) depends on Task 1 (data model):
```
add-task: task-id 1, category simple, title "Add user profile data model..."
add-task: task-id 2, category medium, title "Implement profile edit API endpoint..."
update-task: task-id 2, relations [{"id": 1, "relates-to": 1, "as-type": "blocked-by"}]
```

Common dependency patterns:
- Data model → API endpoint → UI component
- Configuration → Service implementation → Integration tests
- Core feature → Error handling → User documentation

5. Present breakdown: tasks organized by section, categories, dependencies. Get approval.

6. Add in dependency order:
   - `category`, `title` (include "Part of story: task-id N \"title\""), `parent-id`, `type`
   - `relations`: `[{"id": 1, "relates-to": <id>, "as-type": "blocked-by"}]` for dependencies
   - Create dependencies first, then dependent tasks

7. Confirm: task count, dependency count, mention `/mcp-tasks:execute-story-child`.
