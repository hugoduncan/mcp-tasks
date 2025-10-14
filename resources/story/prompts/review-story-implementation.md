---
title: Review Story Implementation
description: Review the implementation of a story
argument-hint: <story-name> [additional-context...]
---

Review the implementation of a story against its requirements and code quality standards.

Parse the arguments: $ARGUMENTS
- The first word/token is the story name
- Everything after is additional context to consider when reviewing

## Process

1. Get the story task using the `next-task` tool:
   - Use `title-pattern` parameter with the story name to find the story task
   - If the task doesn't exist, inform the user and stop
   - The story description is in the task's `:description` field

2. Analyze the current branch against the description of the story:
   - Does the code implement the story correctly?
   - Does it have extra functionality that was not requested?
   0 Could the logic be simplified while remaining clear?

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
          - `task-text`: "STORY: <story-name> - <task description>" the task description can span multiple lines.
          - `story-name`: the story name from step 1
          - `prepend`: false (to append tasks)

        Example:
        ```
        add-task(
          category="medium",
          task-text="STORY: my-story - Add error handling for edge cases\n throw exceptions if edge cases not supported",
          story-name="my-story",
          prepend=false
        )
        ```

     c) Confirm to user:
        "✓ Added N task(s) to <story-name>-tasks.md:"
        List each task with its category in a bullet format

   - If user selects 'none' or provides no selections, skip task creation

## Notes

- All suggestions should be numbered from the start to make selection easy
- Task descriptions can be refined by the user or used verbatim from suggestions
- Categories help route tasks to appropriate execution workflows
- The `add-task` tool automatically handles file creation, formatting, and task preservation
