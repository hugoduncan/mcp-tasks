---
title: Refine Story
description: Interactively refine a story document with user feedback
argument-hint: <story-name> [additional-context...]
---

Refine the story through an interactive process.

Parse the arguments: $ARGUMENTS
- The first word/token is the story name (without .md extension)
- Everything after is additional context to consider when refining

## Process

1. Read the story file from `.mcp-tasks/story/stories/<story-name>.md`
   - If the file doesn't exist, inform the user and stop

2. Display the current story content to the user

3. Enter an interactive refinement loop:
   - Analyze the story for clarity, completeness, and feasibility
   - Suggest specific improvements (e.g., clarify requirements, add acceptance criteria, break down complexity)
   - Present suggestions to the user
   - Get user feedback on the suggestions
   - If user approves changes, apply them to the story content
   - If user requests modifications, incorporate their feedback
   - Continue until user is satisfied

4. Once refinement is complete:
   - Show the final refined story to the user for approval
   - If approved, write the updated content back to `.mcp-tasks/story/stories/<story-name>.md`
   - Confirm the save operation to the user

## Notes

- The refinement process should be collaborative and iterative
- Always get explicit user approval before making changes
- Focus on improving clarity, completeness, and actionability of the story
- Do not make assumptions about requirements - ask the user for clarification
