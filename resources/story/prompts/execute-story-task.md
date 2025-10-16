---
title: Execute Story Task
description: Execute the next task from a story's task list
argument-hint: [story-specification] [additional-context...]
---

Execute the next incomplete task from the story.

Parse the arguments: $ARGUMENTS
- The first word/token is the story specification
- Everything after is additional context to consider when executing the task

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

1. Find the story and its first incomplete child task:
   - First, use `select-tasks` with the appropriate filter (task-id or title-pattern) and `type: story, unique: true` to find the story task
   - Handle errors:
     - **No match**: Inform user no story found, suggest checking available stories
     - **Multiple matches** (if using title-pattern without unique): List matching stories with IDs and ask for clarification
   - Then use `select-tasks` with `parent-id` filter and `:limit 1` to get the first incomplete child
   - If no incomplete tasks found, inform the user that all tasks are
     complete and stop
   - If no category is found for the task, inform the user and stop
   - The tool returns :tasks (a vector) and :metadata

2. Execute the task directly using the category workflow:
   - Execute the `catgeory-<category>` prompt from the `mcp-tasks` server
   - For example, if category is "simple", execute the `category-simple` prompt
   - Run `/mcp-tasks:category-<category>` or use the
     `prompt://category-<category>` resource to access the prompt
   - The task is already in the tasks queue
   - Complete all implementation steps according to the category workflow

3. Mark the task as complete:
   - After task execution completes successfully, use the `complete-task`
     tool
   - Parameters: category (from step 1), title (partial match from
     beginning of task), and optionally completion-comment
   - Confirm to the user that the task has been marked complete

## Notes

- Story tasks are child tasks with :parent-id pointing to the story
- The category workflow will find and execute the task by its position
  in the queue
- If task execution fails, do not mark the task as complete
