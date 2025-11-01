---
title: Review Story Implementation
description: Review the implementation of a story
argument-hint: [story-specification] [additional-context...]
---

Review the implementation of a story against its requirements and code quality standards.

Parse the arguments: $ARGUMENTS
- The first word/token is the story specification
- Everything after is additional context to consider when reviewing

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
   - Extract the story's `:id`, `:title`, and `:description` fields

2. Analyze the current branch against the description of the story:
   - Does the code implement the story correctly?
   - Does it have extra functionality that was not requested?
   - Could the logic be simplified while remaining clear?

3. Analyze the quality of the code in the implementation:
   - Is it as simple as possible? Is there unnecessary complexity?
   - Is the code DRY?
   - Are names descriptive and consistent with the codebase?
   - Check error handling, input validation, and edge cases
   - Is the code readable and self-documenting? Would someone unfamiliar
     with it understand what it does?

4. Analyze the structure of the code:
   - Separation of concerns
   - Single responsibility
   - is good use made of namespaces for grouping code?

5. Report findings:
   - List issues clearly and numbered (e.g., "1. Add error handling...", "2. Improve test coverage...")
   - Suggest improvements in a numbered list format

6. Interactive task creation from suggestions:
   - After presenting all findings and suggestions, ask the user:
     "Would you like to create story tasks for any of these suggestions? (enter numbers separated by commas, e.g., '1,3' or 'all' for all suggestions, or 'none' to skip)"

   - If user selects suggestions:
     a) For each selected suggestion, use the `add-task` tool for each task:
        - Use the `add-task` tool with these parameters:
          - `category`: pick an appropriate category based on the task complexity
          - `title`: "CR: <task description>"
          - `description`: the task description (can span multiple lines)
          - `parent-id`: the story's task ID from step 1
          - `prepend`: false (to append tasks)

        Example:
        ```
        add-task(
          category="medium",
          title="CR: Add error handling for edge cases",
          description="throw exceptions if edge cases not supported",
          parent-id=42,
          prepend=false
        )
        ```

     c) Confirm to user:
        "✓ Added N task(s) to tasks.ednl for story '<story-name>':"
        List each task with its category in a bullet format

   - If user selects 'none' or provides no selections, skip task creation

## Notes

- All suggestions should be numbered from the start to make selection easy
- Task descriptions can be refined by the user or used verbatim from suggestions
- Categories help route tasks to appropriate execution workflows
- The `add-task` tool automatically handles file creation, formatting, and task preservation
