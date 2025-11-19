---
description: Mark a story as complete and archive it
argument-hint: [story-specification] [completion-comment...]
---


Mark a story as complete and move it to the archive.

Parse `$ARGUMENTS`: first token is story specification, rest is context.


| Format | Example | CLI command |
|--------|---------|-------------|
| Numeric / #N / "story N" | 59, #59, story 59 | `mcp-tasks show --task-id N` (verify type is story) |
| Text | "Make prompts flexible" | `mcp-tasks list --title-pattern "..." --type story --limit 1` |


Handle no match or multiple matches by informing user.


## Purpose

Use this tool after all story tasks are complete and the implementation has been verified. It marks the story as complete and archives it for future reference.

## Process


1. Retrieve the story using `mcp-tasks show --task-id N --format edn` or `mcp-tasks list --title-pattern "..." --type story --limit 1 --format edn`
   - The story is stored as a task with type `story` in `.mcp-tasks/tasks.ednl`
   - If the story doesn't exist in tasks.ednl, check complete.ednl
   - If already in complete.ednl, inform the user it's already completed

2. Verify all child tasks are complete:
   - Use `mcp-tasks list --parent-id <story-id> --status open --format edn` to check for incomplete child tasks
   - If incomplete tasks remain, inform the user and list them
   - Do not proceed with story completion until all tasks are done

3. Request user confirmation before completing:
   - Ask the user: "All story tasks are complete. Archive story #<id> '<title>'? (yes/no)"
   - If user says no, stop execution without completing the story
   - If user says yes, proceed to step 4

4. Complete the story using `mcp-tasks complete --task-id <story-id>`:
   - Include the completion comment if provided: `--comment "..."`
   - The story will be marked with status `closed` and moved from `tasks.ednl` to `complete.ednl`

5. Complete all child tasks (if not already done):
   - For each child task of the story, use `mcp-tasks complete --task-id <child-id>`
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

- Both the story task and all child tasks are marked with status `closed` and moved to `complete.ednl`

- If git workflow is enabled, you should commit the changes after completion
- The story and its tasks can be reviewed by reading `complete.ednl` or using appropriate tools