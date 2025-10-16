---
title: Refine Task
description: Interactively refine a task document with user feedback
argument-hint: [task-specification] [additional-context...]
---

Refine the task through an interactive process.

Parse the arguments: $ARGUMENTS
- The first word/token is the task specification
- Everything after is additional context to consider when refining

### Task Specification Formats

The task can be specified in multiple ways:
- **By ID**: "#59", "59", "task 59" (numeric formats)
- **By title pattern**: "Update prompt file" (text matching)

### Parsing Logic

1. **Extract the first token** from $ARGUMENTS as the task specification
2. **Determine specification type**:
   - If the token is numeric (e.g., "59") → treat as task-id
   - If the token starts with "#" (e.g., "#59") → strip "#" and treat as task-id
   - If the token matches "task N" pattern → extract N and treat as task-id
   - Otherwise → treat as title-pattern
3. **Use appropriate select-tasks filter**:
   - For task-id: `task-id: N, unique: true`
   - For title-pattern: `title-pattern: "...", unique: true`

## Process

1. Find the task using `select-tasks` with the appropriate filter (task-id or title-pattern) and `unique: true`
   - Handle errors:
     - **No match**: Inform user no task found, suggest checking available tasks
     - **Multiple matches** (if using title-pattern without unique): List matching tasks with IDs and ask for clarification
   - Extract the task's `:id`, `:title`, `:description`, `:design`, and `:type` fields

2. **Analyze the task in project context:**
   - Review the task description and design notes
   - Research any design patterns or exemplars mentioned in the task
   - Examine relevant parts of the codebase to understand context
   - Adapt analysis based on task type (story, feature, bug, chore, task)
   - Identify aspects the user may have forgotten to mention
   - Check for unintended scope expansion

3. Display the current task content to the user:
   - Show the task type and title
   - Show the description
   - Show the design notes (if any)
   - Present your analysis including:
     - Project context findings
     - Relevant design patterns or exemplars found
     - Any forgotten aspects identified
     - Potential scope concerns (if any)

4. Enter an interactive refinement loop:
   - Suggest specific improvements (e.g., clarify requirements, add acceptance criteria, break down complexity)
   - Present suggestions to the user
   - Get user feedback on the suggestions
   - If user approves changes, update the working copy of the task content
   - If user requests modifications, incorporate their feedback
   - Continue until user is satisfied
   - **Important**: Do not expand scope without explicit user intent

5. Once refinement is complete:
   - Show the final refined task to the user for approval
   - If approved, use `update-task` tool to save changes:
     - `task-id`: the task's `:id`
     - `description`: the refined description
     - `design`: the refined design notes
   - Confirm the save operation to the user

## Notes

- Tasks are stored in `tasks.ednl` with various `:type` values (`:story`, `:task`, `:bug`, `:feature`, `:chore`)
- The `:description` field contains the main task content
- The `:design` field contains design notes and technical details
- The refinement process should be collaborative and iterative
- Always get explicit user approval before making changes
- Focus on improving clarity, completeness, and actionability of the task
- Do not make assumptions about requirements - ask the user for clarification
- Adapt your analysis and suggestions based on the task type and project context
