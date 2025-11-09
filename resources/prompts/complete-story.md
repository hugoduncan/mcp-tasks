---
title: Complete Story
description: Mark a story as complete and archive it
argument-hint: [story-specification] [completion-comment...]
---

Mark a story as complete and move it to the archive.

Parse the arguments: $ARGUMENTS
- The first word/token is the story name (without .md extension)
- Everything after is an optional completion comment to append to the story

## Purpose

Use this tool after all story tasks are complete and the implementation has been verified. It marks the story as complete and archives it for future reference.

## Process

1. Retrieve the story using the `select-tasks` tool with `title-pattern` matching the story name and `unique: true`
   - The story is stored as a task with `:type :story` in `.mcp-tasks/tasks.ednl`
   - If the story doesn't exist in tasks.ednl, check complete.ednl
   - If already in complete.ednl, inform the user it's already completed

2. Verify all child tasks are complete:
   - Use `select-tasks` with `parent-id` filter to check for any incomplete child tasks
   - If incomplete tasks remain, inform the user and list them
   - Do not proceed with story completion until all tasks are done

3. Request user confirmation before completing:
   - Use the `AskUserQuestion` tool to confirm the user wants to archive the story
   - Question format: "All story tasks are complete. Archive story #<id> '<title>'?"
   - Provide two options:
     - "Yes, archive story" (description: "Mark story as complete and move to archive")
     - "No, not yet" (description: "Keep story open for now")
   - If user selects "No, not yet", stop execution without completing the story
   - If user selects "Yes, archive story", proceed to step 4

4. Complete the story using the `complete-task` tool:
   - Use the story's task-id or exact title
   - Include the completion comment if provided
   - The story will be marked with `:status :closed` and moved from `tasks.ednl` to `complete.ednl`

5. Complete all child tasks (if not already done):
   - For each child task of the story, use `complete-task`
   - This moves each task from `tasks.ednl` to `complete.ednl`

6. If git workflow is enabled:
   - Stage the changes to `.mcp-tasks/tasks.ednl` and `.mcp-tasks/complete.ednl`
   - Return the modified file paths for commit

7. Confirm completion to the user

## When to Use

- After all story tasks have been completed
- After reviewing the story implementation with `/review-story-implementation`
- When you're ready to archive the story and its history

## Notes

- Completed stories and their child tasks remain accessible in `.mcp-tasks/complete.ednl` for historical reference
- Both the story task and all child tasks are marked with `:status :closed` and moved to `complete.ednl`
- If git workflow is enabled, you should commit the changes after completion
- The story and its tasks can be reviewed by reading `complete.ednl` or using appropriate tools
