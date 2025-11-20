---
title: Create Story Tasks
description: Break down a story into categorized, executable tasks
argument-hint: [story-specification] [additional-context...]
---

Create task breakdown for story. Don't implement.

{% include "infrastructure/story-parsing.md" %}

## Process

{% if cli %}
1. Retrieve story via `mcp-tasks show --task-id N --format edn` or `mcp-tasks list --title-pattern "..." --type story --limit 1 --format edn`. Handle no match or multiple matches.

2. Check `meta` field for `"refined": "true"`. If unrefined, warn user and ask: "Task has not been refined. Proceed anyway? (yes/no)". If user declines, suggest `/mcp-tasks-refine-task` and stop.
{% else %}
1. Retrieve story via `select-tasks` with `type: story, unique: true`. Handle no match or multiple matches.

2. Check `:meta` for `"refined": "true"`. If unrefined, warn and use `AskUserQuestion`. If user declines, suggest `/mcp-tasks:refine-task` and stop.
{% endif %}

3. Display story to user.

4. Break down:
   - Logical sequence (dependencies first), group related steps
   - Each task: implementation + verification (don't split "implement X" and "test X")
   - Clear, concrete, self-contained descriptions with appropriate category
   - Identify dependencies: implementation order, data flow, component dependencies

## Task Categorization

{% if cli %}
Use `mcp-tasks prompts list` to see available categories. Category prompts define execution workflows:
- **simple**: Direct implementation tasks
- **medium**: Tasks requiring some analysis and design
- **large**: Complex tasks needing detailed analysis and user interaction

**Examples:**
- "Update README with installation instructions" → **simple** (direct documentation change)
- "Add user profile editing with avatar upload" → **medium** (multiple fields, file handling, validation)
- "Implement real-time notifications with WebSocket fallback" → **large** (architecture, multiple protocols, state management)
{% else %}
Use `ReadMcpResourceTool` with server "mcp-tasks", uri
"resource://categories" to retrieve the available categories. If
missing, inform user and stop.

Parse the JSON response to extract available categories and their
descriptions. Use category descriptions to inform categorization
decisions.

**Examples** (if resource://categories returns simple, medium and large categories):
- "Update README with installation instructions" → **simple** (direct documentation change)
- "Add user profile editing with avatar upload" → **medium** (multiple fields, file handling, validation)
- "Implement real-time notifications with WebSocket fallback" → **large** (architecture, multiple protocols, state management)
{% endif %}

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

{% if cli %}
Use `blocked-by` relations when task B requires task A's output:

**Example:** Task 2 (API endpoint) depends on Task 1 (data model):
```bash
# Create tasks first
mcp-tasks add --category simple --title "Add user profile data model..." --parent-id <story-id>
# Returns: task-id 1
mcp-tasks add --category medium --title "Implement profile edit API endpoint..." --parent-id <story-id>
# Returns: task-id 2

# Add dependency
mcp-tasks update --task-id 2 --relations '[{:id 1 :relates-to 1 :as-type :blocked-by}]'
```
{% else %}
Use `blocked-by` relations when task B requires task A's output:

**Example:** Task 2 (API endpoint) depends on Task 1 (data model):
```
add-task: task-id 1, category simple, title "Add user profile data model..."
add-task: task-id 2, category medium, title "Implement profile edit API endpoint..."
update-task: task-id 2, relations [{"id": 1, "relates-to": 1, "as-type": "blocked-by"}]
```
{% endif %}

Common dependency patterns:
- Data model → API endpoint → UI component
- Configuration → Service implementation → Integration tests
- Core feature → Error handling → User documentation

5. Present breakdown: tasks organized by section, categories, dependencies. Get approval.

{% if cli %}
6. Add in dependency order:
   - `mcp-tasks add --category <cat> --title "..." --parent-id <story-id> --type task`
   - Title should include "Part of story: <story-id> \"<story-title>\""
   - After creating dependent tasks, add relations: `mcp-tasks update --task-id <id> --relations '[...]'`
   - Create dependencies first, then dependent tasks

7. Update parent story shared context:
   - After creating all tasks, update the parent story's shared-context with a list of created task IDs and titles
   - Format: `"Story breakdown: Task #<id1> '<title1>', Task #<id2> '<title2>', ..."`
   - Command: `mcp-tasks update --task-id <story-id> --shared-context "Story breakdown: Task #<id1> '<title1>', Task #<id2> '<title2>', ..."`
   - Include all created tasks in creation order
   - Use single quotes around titles to avoid escaping issues

8. Confirm: task count, dependency count, mention `/mcp-tasks-execute-story-child`.
{% else %}
6. Add in dependency order:
   - `category`, `title` (include "Part of story: task-id N \"title\""), `parent-id`, `type`
   - `relations`: `[{"id": 1, "relates-to": <id>, "as-type": "blocked-by"}]` for dependencies
   - Create dependencies first, then dependent tasks

7. Update parent story shared context:
   - After creating all tasks, update the parent story's shared-context with a list of created task IDs and titles
   - Format: `"Story breakdown: Task #<id1> '<title1>', Task #<id2> '<title2>', ..."`
   - Call `update-task` with `task-id: <story-id>`, `shared-context: ["Story breakdown: Task #<id1> '<title1>', Task #<id2> '<title2>', ..."]`
   - Include all created tasks in creation order
   - Use single quotes around titles to avoid escaping issues

8. Confirm: task count, dependency count, mention `/mcp-tasks:execute-story-child`.
{% endif %}
