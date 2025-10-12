(ns mcp-tasks.story-prompts
  "Built-in story prompt templates.

  Story prompts are used for story-based workflow management. Each prompt is
  defined as a def var with a docstring and content that includes frontmatter
  metadata.")

(def refine-story
  "Refine a story through interactive feedback with the user.

  Takes a story-name argument and guides the agent through iterative refinement
  of the story document with user feedback before saving changes."
  "---
title: Refine Story
description: Interactively refine a story document with user feedback
---

Refine the story specified by the `story-name` argument through an interactive process.

## Arguments

- `story-name` - The name of the story to refine (without .md extension)

## Process

1. Read the story file from `.mcp-tasks/stories/<story-name>.md`
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
   - If approved, write the updated content back to `.mcp-tasks/stories/<story-name>.md`
   - Confirm the save operation to the user

## Notes

- The refinement process should be collaborative and iterative
- Always get explicit user approval before making changes
- Focus on improving clarity, completeness, and actionability of the story
- Do not make assumptions about requirements - ask the user for clarification
")

(def create-story-tasks
  "Break down a story into executable tasks with category metadata.

  Takes a story-name argument and guides the agent through breaking down the story
  into specific, actionable tasks with appropriate category assignments."
  "---
title: Create Story Tasks
description: Break down a story into categorized, executable tasks
---

Create a task breakdown for the story specified by the `story-name` argument.

## Arguments

- `story-name` - The name of the story to break down (without .md extension)

## Process

1. Read the story file from `.mcp-tasks/stories/<story-name>.md`
   - If the file doesn't exist, inform the user and stop

2. Display the story content to the user

3. Analyze the story and break it down into specific, actionable tasks:
   - Each task should be concrete and achievable
   - Tasks should follow a logical sequence (dependencies first)
   - Group related tasks into sections (e.g., \"Foundation\", \"Implementation\", \"Testing\", \"Documentation\")
   - Each task must be prefixed with `STORY: <story-name> - ` to indicate it belongs to this story
   - Each task must have a `CATEGORY: <category>` line on its own line after the task description

4. Category selection guidance:
   - `simple` - Straightforward tasks, small changes, documentation updates
   - `medium` - Tasks requiring analysis and design, moderate complexity
   - `large` - Complex tasks needing extensive planning and implementation
   - `clarify-task` - Tasks that need clarification before execution

5. Task format (multi-line supported):
   ```
   - [ ] STORY: <story-name> - <brief task title>
     <additional task details on continuation lines>
     <more details as needed>

   CATEGORY: <category>
   ```

6. Present the task breakdown to the user:
   - Show all tasks organized by section
   - Include category assignments
   - Get user feedback and approval
   - Make adjustments based on feedback

7. Once approved, write the tasks to `.mcp-tasks/story-tasks/<story-name>-tasks.md`:
   - Include a header: `# Tasks for <story-name> Story`
   - Organize tasks by logical sections with `## Section Name` headers
   - Maintain the checkbox format with STORY prefix and CATEGORY metadata
   - Ensure blank lines between tasks for readability

8. Confirm the save operation to the user

## Notes

- Task descriptions should be specific enough to be actionable without additional context
- The STORY prefix helps track which story each task belongs to
- The CATEGORY line is metadata used for routing and should be on its own line
- Multi-line task descriptions are supported and encouraged for clarity
- Tasks should be ordered to respect dependencies (e.g., create before use)
- Section headers help organize related tasks for better readability
")
