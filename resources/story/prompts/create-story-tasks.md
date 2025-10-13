---
title: Create Story Tasks
description: Break down a story into categorized, executable tasks
argument-hint: <story-name> [additional-context...]
---

Create a task breakdown for the story.  Do not implement the story.

Parse the arguments: $ARGUMENTS
- The first word/token is the story name (without .md extension)
- Everything after is additional context to consider when creating tasks

## Process

1. Read the story file from `.mcp-tasks/story/stories/<story-name>.md`
   - If the file doesn't exist, inform the user and stop

2. Display the story content to the user

3. Analyze the story and break it down into specific, actionable tasks:
   - Each task must be prefixed with `- [ ] STORY: <story-name> - ` to
     indicate it belongs to this story
   - Each task should be concrete and achievable
   - Tasks should follow a logical sequence (dependencies first)
   - A single task may contain multiple steps, as long as they are
     logically grouped
   - Don't create lots of very simple tasks, try and group them
   - The task description contains enough context to implement it without
     any other context.
   - update unit tests if needed, as part of each task
   - Each task must have a reference to the story file
   - Each task must have a `CATEGORY: <category>` line on its own line
     after the task description

4. Category selection guidance:
   - `simple` - Straightforward tasks, small changes, documentation updates
   - `medium` - Tasks requiring analysis and design, moderate complexity
   - `large` - Complex tasks needing extensive planning and implementation
   - `clarify-task` - Tasks that need clarification before execution

5. Task format (multi-line supported):

   Each task should be a checkbox item.

   ```
   - [ ] STORY: <story-name> - <brief task title>
     <additional task details on continuation lines>
     <more details as needed>

   Part of story: @path-to-story-file
   CATEGORY: <category>
   ```

6. Present the task breakdown to the user:
   - Show all tasks organized by section
   - Include category assignments
   - Get user feedback and approval
   - Make adjustments based on feedback

7. Once approved, write the tasks to `.mcp-tasks/story/story-tasks/<story-name>-tasks.md`:
   - Include a header: `# Tasks for <story-name> Story`
   - Organize tasks by logical sections with `## Section Name` headers
   - Maintain the checkbox format with STORY prefix and CATEGORY metadata
   - Ensure blank lines between tasks for readability

8. Confirm the save operation to the user

## Notes

- Task descriptions should be specific enough to be actionable without additional context
- The STORY prefix helps track which story each task belongs to
- The CATEGORY line is metadata used for routing and should be on its own line
- Multi-line task descriptions are supported and encouraged for clarity
- Tasks should be ordered to respect dependencies (e.g., create before use)
- Section headers help organize related tasks for better readability
