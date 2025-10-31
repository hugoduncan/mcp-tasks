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
   - The tool returns :tasks (a vector) and :metadata
   - use :open-task-count and :completed-task-count to show story progress,
     like "2 of 5 tasks completed", where 2 is :completed-task-count
     and 5 is (+ :open-task-count :completed-task-count)
   - If no tasks found,
	  - if completed-task-count is positive:
         - inform the user that all tasks are complete
         - suggest the user reviews the story implementation or creates a PR
         - stop - do not take any further actions
	  - if completed-task-count is zero:
         - if the story is not refined, suggest the user refines the story
         - else if the story is refined, suggest the user creates story tasks
		   for the story
         - stop - do not take any further actions
   - If no category is found for the task, inform the user and stop
   - show the task to the user

2. Set up task environment and execute the task:
   - First, set up the task environment using the `work-on` tool:
     - Call `mcp__mcp-tasks__work-on` with:
       - `task-id`: <task-id-from-step-1>
     - The tool will automatically:
       - Write execution state with story-id and timestamp
       - Handle branch management if configured
       - Handle worktree management if configured
   - After calling the work-on tool, display the working environment context:
     - If the response includes `:worktree-name` and `:worktree-path`, display:
           Worktree: <worktree-name>
           Directory: <worktree-path>
     - If the response includes `:branch-name`, display:
           "Branch: <branch-name>"
     - Format this as a clear header before proceeding with task execution

   Example output:
   ```
Worktree: mcp-tasks-fix-bug
Directory: /Users/duncan/projects/mcp-tasks-fix-bug
Branch: fix-bug
   ```
   - While executing the task, watch for issues beyond the current task scope:
     - Create new tasks immediately using `add-task` tool
     - Link them with `:discovered-during` relation using `update-task`
     - Example relation: `{:id 1, :relates-to <current-task-id>, :as-type :discovered-during}`
     - Continue with the current task without getting sidetracked
     - Do a final check before completion to capture all discoveries
     - See "Discovering Issues Beyond Current Scope" guidance in execute-task prompt for details
   - Then execute the task using the category workflow:
     - Do NOT check the refinement status of the task
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
   - The tool will automatically clear the execution state

## Notes

- Story tasks are child tasks with :parent-id pointing to the story
- The category workflow will find and execute the task by its position
  in the queue
- If task execution fails, do not mark the task as complete

## Error Handling

- If task execution fails or is interrupted:
  - The execution state remains in place (managed by work-on tool)
  - External tools can detect stale execution via the `:started-at` timestamp
  - When starting a new task, work-on will overwrite the execution state automatically
