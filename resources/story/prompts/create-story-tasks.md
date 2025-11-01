---
title: Create Story Tasks
description: Break down a story into categorized, executable tasks
argument-hint: [story-specification] [additional-context...]
---

Create a task breakdown for the story. Do not implement the story.

Parse the arguments: $ARGUMENTS
- The first word/token is the story specification
- Everything after is additional context to consider when creating tasks

### Story Specification Formats

The story can be specified in multiple ways:
- **By ID**: "#59", "59", "story 59" (numeric formats)
- **By title pattern**: "Make story prompts flexible" (text matching)

### Parsing Logic

1. **Extract the first token** from $ARGUMENTS as the story specification
2. **Determine specification type**:
   - If the token is numeric (e.g., "59") → treat as task-id
   - If the token starts with "#" (e.g., "#59") → strip "#" and treat as task-id
   - If the token matches "story N" pattern → extract N and treat as task-id
   - Otherwise → treat as title-pattern
3. **Use appropriate select-tasks filter**:
   - For task-id: `task-id: N, type: story, unique: true`
   - For title-pattern: `title-pattern: "...", type: story, unique: true`

## Process

1. Retrieve the story using the `select-tasks` tool:
   - Use the appropriate filter (task-id or title-pattern) with `type: story, unique: true`
   - The story is stored as a task with `:type :story`
   - Handle errors:
     - **No match**: Inform user no story found, suggest checking available stories
     - **Multiple matches** (if using title-pattern without unique): List matching stories with IDs and ask for clarification
   - If the story doesn't exist, inform the user and stop

2. Check story refinement status:
   - Check if the story's `:meta` map contains `"refined": "true"`
   - If not refined:
     - Display warning: "⚠️ This story has not been refined. Creating tasks from unrefined stories may lead to unclear or incomplete task breakdowns."
     - Use `AskUserQuestion` tool to ask: "Do you want to proceed anyway?"
       - Options: "Yes, proceed" and "No, refine first"
     - If user declines, stop execution and suggest running `/mcp-tasks:refine-task <story-id>` first
     - If user accepts, continue with task creation

3. Display the story content to the user

4. Analyze the story and break it down into specific, actionable tasks:
   - Tasks should follow a logical sequence (dependencies first)
   - A single task may contain multiple steps, as long as they are
     logically grouped
   - Don't create lots of very simple tasks, try and group them

   - Each task description should:
      - be concrete and achievable
      - be clear, precise, and unambiguous
      - contain enough context to implement it without
        any other context.
      - instruct to update unit tests if needed, as part of the task

   - Each task should have an appropriate category (the add-task
     description contains a description of the categories)

5. Present the task breakdown to the user:
   - Show all tasks organized by section
   - Include category assignments for each task
   - Get user feedback and approval
   - Make adjustments based on feedback

6. Ask about task dependencies:
   - Ask the user: "Are there any dependencies between these tasks?"
   - If yes:
     - Guide the user to identify which tasks must be completed before others
     - Note which tasks are blocked by other tasks
     - Explain that dependencies will be added as `:blocked-by` relations after task creation
   - If no dependencies, proceed to task creation

7. Once approved, add each task using the `add-task` tool:
   - For each task, call `add-task` with:
     - `category`: the selected category (simple, medium, large, clarify-task)
     - `title`: task title on first line, then description including
       "Part of story: task-id <story-id> \"<story-title>\""
     - `parent-id`: the story id
     - `type`: "task" (or "bug", "feature", etc. if appropriate)
   - Tasks will be added to `.mcp-tasks/tasks.ednl` with the story as parent
   - Add tasks in order (dependencies first)

8. If there are dependencies, add `:blocked-by` relations using `update-task`:
   - For each task that is blocked by another task, call `update-task` with:
     - `task-id`: the ID of the blocked task
     - `relations`: a JSON array containing the new relation(s)
   - Each relation is a JSON object with:
     - `id`: a unique integer for this relation (use 1, 2, 3, etc.)
     - `relates-to`: the task ID that blocks this task
     - `as-type`: "blocked-by"
   - Example: If task B (ID 461) is blocked by task A (ID 460):
     ```json
     update-task task-id: 461, relations: [{"id": 1, "relates-to": 460, "as-type": "blocked-by"}]
     ```
   - If a task is blocked by multiple tasks, include multiple relation objects in the array

9. Confirm task creation to the user:
   - Report how many tasks were created
   - Mention they can be executed with `/mcp-tasks:execute-story-task <story-name>`

## Notes

- Task descriptions should be specific enough to be actionable without additional context
- All story tasks automatically get `:parent-id` linking to the story
- Tasks are stored in the unified `tasks.ednl` file, not separate markdown files
- Use `select-tasks` with `parent-id` filtering to find story tasks
- Tasks should be ordered to respect dependencies (e.g., create before use)
