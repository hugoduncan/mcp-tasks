---
title: Refine Story
description: Interactively refine a story document with user feedback
argument-hint: [story-specification] [additional-context...]
---

Refine the story through an interactive process.

Parse the arguments: $ARGUMENTS
- The first word/token is the story specification
- Everything after is additional context to consider when refining

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

1. Find the story task using `select-tasks` with the appropriate filter (task-id or title-pattern) and `type: story, unique: true`
   - Handle errors:
     - **No match**: Inform user no story found, suggest checking available stories
     - **Multiple matches** (if using title-pattern without unique): List matching stories with IDs and ask for clarification
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
