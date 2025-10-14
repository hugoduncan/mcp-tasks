---
title: Create Story Tasks
description: Break down a story into categorized, executable tasks
argument-hint: <story-name> [additional-context...]
---

Create a task breakdown for the story. Do not implement the story.

Parse the arguments: $ARGUMENTS
- The first word/token is the story name (without .md extension)
- Everything after is additional context to consider when creating tasks

## Process

1. Retrieve the story using the `select-tasks` tool with `title-pattern` matching the story name and `:unique? true`
   - The story is stored as a task with `:type :story` in `.mcp-tasks/tasks.ednl`
   - If the story doesn't exist, inform the user and stop

2. Display the story content to the user

3. Analyze the story and break it down into specific, actionable tasks:
   - Each task should be concrete and achievable
   - Tasks should follow a logical sequence (dependencies first)
   - A single task may contain multiple steps, as long as they are
     logically grouped
   - Don't create lots of very simple tasks, try and group them
   - The task description contains enough context to implement it without
     any other context
   - Update unit tests if needed, as part of each task
   - Each task should reference the story file for context

4. Category selection guidance:
   - `simple` - Straightforward tasks, small changes, documentation updates
   - `medium` - Tasks requiring analysis and design, moderate complexity
   - `large` - Complex tasks needing extensive planning and implementation
   - `clarify-task` - Tasks that need clarification before execution

5. Present the task breakdown to the user:
   - Show all tasks organized by section
   - Include category assignments for each task
   - Get user feedback and approval
   - Make adjustments based on feedback

6. Once approved, add each task using the `add-task` tool:
   - For each task, call `add-task` with:
     - `category`: the selected category (simple, medium, large, clarify-task)
     - `task-text`: task title on first line, then description including
       "Part of story: task-id <story-id> \"<story-title>\""
     - `story-name`: the story name (this automatically sets :parent-id)
     - `type`: "task" (or "bug", "feature", etc. if appropriate)
   - Tasks will be added to `.mcp-tasks/tasks.ednl` with the story as parent
   - Add tasks in order (dependencies first)

7. Confirm task creation to the user:
   - Report how many tasks were created
   - Mention they can be executed with `/mcp-tasks:execute-story-task <story-name>`

## Notes

- Task descriptions should be specific enough to be actionable without additional context
- All story tasks automatically get `:parent-id` linking to the story
- Tasks are stored in the unified `tasks.ednl` file, not separate markdown files
- Use `select-tasks` with `parent-id` filtering to find story tasks
- Tasks should be ordered to respect dependencies (e.g., create before use)
