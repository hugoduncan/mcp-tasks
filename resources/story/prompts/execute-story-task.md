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

1. Find the story and its first incomplete child task:
   - First, use `select-tasks` with `title-pattern` and `unique: true` to find the story task
   - Then use `select-tasks` with `parent-id` filter and `:limit 1` to get the first incomplete child
   - If no incomplete tasks found, inform the user that all tasks are
     complete and stop
   - If no category is found for the task, inform the user and stop
   - The tool returns :tasks (a vector) and :metadata

2. Execute the task directly using the category workflow:
   - Execute the `next-<category>` prompt from the `mcp-tasks` server
   - For example, if category is "simple", execute the `next-simple` prompt
   - Run `/mcp-tasks:next-<category>` or use the
     `prompt://next-<category>` resource to access the prompt
   - The task is already in the tasks queue
   - Complete all implementation steps according to the category workflow

3. Mark the task as complete:
   - After task execution completes successfully, use the `complete-task`
     tool
   - Parameters: category (from step 1), title (partial match from
     beginning of task), and optionally completion-comment
   - Confirm to the user that the task has been marked complete

## Notes

- Tasks are stored in `.mcp-tasks/tasks.ednl` with parent-child relationships
- Story tasks are child tasks with :parent-id pointing to the story
- The category workflow will find and execute the task by its position
  in the queue
- If task execution fails, do not mark the task as complete
