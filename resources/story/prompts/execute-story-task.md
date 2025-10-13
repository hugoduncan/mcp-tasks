---
title: Execute Story Task
description: Execute the next task from a story's task list
argument-hint: <story-name> [additional-context...]
---

Execute the next incomplete task from the story.

Parse the arguments: $ARGUMENTS
- The first word/token is the story name (without .md extension)
- Everything after is additional context to consider when executing the task

## Process

1. Read the story tasks file from `.mcp-tasks/story/story-tasks/<story-name>-tasks.md`
   - If the file doesn't exist, inform the user and stop

2. Find the first incomplete task using the `next-story-task` tool.
   - If no incomplete tasks found, inform the user that all tasks are
     complete and stop
   - If no category is found for the task, inform the user and stop

3. Add the task to the category queue:
   - Use the `add-task` tool to prepend this task to the category's
     queue
   - Parameters: category (returned by `next-story-task` tool),
     task-text (full multi-line task description), prepend (true), do
     not pass the story-name, so that we do not write back to the story
     tasks file.

4. Execute the task:
   - execute `/mcp-tasks:next-<catagory> (MCP)`.For example, if <category> is
     "simple", execute `/mcp-tasks:next-simple (MCP)`
   - This will run the task using the category-specific workflow
   - Complete all implementation steps according to the category workflow

5. Mark the story task as complete:
   - After task execution completes successfully, use the
     `complete-story-task` tool to mark the story complete
   - Confirm to the user that the story task has been marked complete

## Notes

- The task text includes the full multi-line description from the story
  tasks file
- The CATEGORY line is used only for routing and should not be included
  in the task text added to the category queue
- If task execution fails, do not mark the story task as complete
- The story task file tracks overall story progress, while individual
  category queues manage immediate execution
