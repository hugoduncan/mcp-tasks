---
title: Complete Story
description: Mark a story as complete and archive it
argument-hint: <story-name> [completion-comment...]
---

Mark a story as complete and move it to the archive.

Parse the arguments: $ARGUMENTS
- The first word/token is the story name (without .md extension)
- Everything after is an optional completion comment to append to the story

## Purpose

Use this tool after all story tasks are complete and the implementation has been verified. It marks the story as complete and archives it for future reference.

## Process

1. Read the story file from `.mcp-tasks/story/stories/<story-name>.md`
   - If the file doesn't exist, inform the user and stop
   - If the file is already in `.mcp-tasks/story/complete/`, inform the user it's already completed

2. If a completion comment is provided:
   - Append the comment to the story content
   - Add a separator and timestamp if appropriate

3. Move the story file from `.mcp-tasks/story/stories/<story-name>.md` to `.mcp-tasks/story/complete/<story-name>.md`

4. Move the story tasks file from `.mcp-tasks/story/story-tasks/<story-name>-tasks.md` to `.mcp-tasks/story/story-tasks-complete/<story-name>-tasks.md`
   - If the tasks file doesn't exist, skip this step with a note

5. If git workflow is enabled:
   - Stage the file changes (both story and tasks files, source and destination)
   - Return the modified file paths for commit

6. Confirm completion to the user

## When to Use

- After all story tasks have been completed
- After reviewing the story implementation with `/review-story-implementation`
- When you're ready to archive the story and its history

## Notes

- Completed stories and their task lists remain accessible in the archive for historical reference
- Both the story file and its task list are moved to their respective complete directories
- If git workflow is enabled, you should commit the changes after completion
