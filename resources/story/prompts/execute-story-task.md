---
title: Execute Story Task
description: Execute the next task from a story's task list
---

Execute the next incomplete task from the story specified by the `story-name` argument.

## Arguments

- `story-name` - The name of the story (without .md extension)

## Process

1. Read the story tasks file from `.mcp-tasks/story/story-tasks/<story-name>-tasks.md`
   - If the file doesn't exist, inform the user and stop

2. Find the first incomplete task (marked with `- [ ]`)
   - Parse the task to extract the task text and CATEGORY metadata
   - If no incomplete tasks found, inform the user that all tasks are complete and stop
   - If no CATEGORY line found, inform the user and stop

3. Add the task to the category queue:
   - Extract the task text (everything from `- [ ] STORY:` until the `CATEGORY:` line, excluding the CATEGORY line itself)
   - Use the `add-task` tool to prepend this task to the category's queue
   - Parameters: category (extracted category name), task-text (full multi-line task description), prepend (true)

4. Execute the task:
   - Use the category-specific next-task workflow to execute the task
   - Follow the category's execution instructions from `.mcp-tasks/prompts/<category>.md`
   - Complete all implementation steps according to the category workflow

5. Mark the story task as complete:
   - After task execution completes successfully, update the story tasks file
   - Change `- [ ]` to `- [x]` for the task that was just executed
   - Write the updated content back to `.mcp-tasks/story/story-tasks/<story-name>-tasks.md`
   - Use the `complete-story-task` tool if available
   - Confirm to the user that the story task has been marked complete

## Branch Management (Conditional)

If the configuration includes `:story-branch-management? true`:

1. Before starting task execution:
   - Check if currently on a branch named `<story-name>`
   - If not, checkout the default branch, ensure it's up to date with origin, then create the `<story-name>` branch

2. After task completion:
   - Remain on the `<story-name>` branch for the next task
   - Do not merge or push automatically

If `:story-branch-management?` is false (default):
   - Execute tasks on the current branch without any branch operations

## Notes

- The task text includes the full multi-line description from the story tasks file
- The CATEGORY line is used only for routing and should not be included in the task text added to the category queue
- If task execution fails, do not mark the story task as complete
- The story task file tracks overall story progress, while individual category queues manage immediate execution
- Branch management is optional and controlled by configuration
