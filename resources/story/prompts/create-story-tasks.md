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

5. Present breakdown: tasks organized by section, categories, dependencies. Get approval.

6. Add in dependency order:
   - `category`, `title` (include "Part of story: task-id N \"title\""), `parent-id`, `type`
   - `relations`: `[{"id": 1, "relates-to": <id>, "as-type": "blocked-by"}]` for dependencies
   - Create dependencies first, then dependent tasks

7. Confirm: task count, dependency count, mention `/mcp-tasks:execute-story-child`.
