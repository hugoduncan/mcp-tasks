---
title: Refine Story
description: Interactively refine a story document with user feedback
argument-hint: <story-name> [additional-context...]
---

Refine the story through an interactive process.

Parse the arguments: $ARGUMENTS
- The first word/token is the story name (used to search for the story)
- Everything after is additional context to consider when refining

## Process

1. Find the story task using `select-tasks` with `title-pattern` matching the story name and `:unique? true`
   - If no story task is found, inform the user and stop
   - Extract the story's `:id`, `:title`, `:description`, and `:design` fields

2. Display the current story content to the user:
   - Show the title
   - Show the description (main story content)
   - Show the design notes (if any)

3. Enter an interactive refinement loop:
   - Analyze the story for clarity, completeness, and feasibility
   - Suggest specific improvements (e.g., clarify requirements, add acceptance criteria, break down complexity)
   - Present suggestions to the user
   - Get user feedback on the suggestions
   - If user approves changes, update the working copy of the story content
   - If user requests modifications, incorporate their feedback
   - Continue until user is satisfied

4. Once refinement is complete:
   - Show the final refined story to the user for approval
   - If approved, use `update-task` tool to save changes:
     - `task-id`: the story task's `:id`
     - `description`: the refined description
     - `design`: the refined design notes
   - Confirm the save operation to the user

## Notes

- Stories are stored as tasks with `:type :story` in `tasks.ednl`
- The `:description` field contains the main story content
- The `:design` field contains design notes and technical details
- The refinement process should be collaborative and iterative
- Always get explicit user approval before making changes
- Focus on improving clarity, completeness, and actionability of the story
- Do not make assumptions about requirements - ask the user for clarification
