---
description: Review the implementation of a story
argument-hint: [story-specification] [additional-context...]
---


Review the implementation of a story against its requirements and code quality standards.

Parse `$ARGUMENTS`: first token is story specification, rest is context.


| Format | Example | select-tasks params |
|--------|---------|---------------------|
| Numeric / #N / "story N" | 59, #59, story 59 | `task-id: N, type: story, unique: true` |
| Text | "Make prompts flexible" | `title-pattern: "...", type: story, unique: true` |


Handle no match or multiple matches by informing user.


## Process


1. Find the story task using `select-tasks` with the appropriate filter (task-id or title-pattern) and `type: story, unique: true`
   - Handle no match or multiple matches by informing user
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

7. Record the review completion:

   - After the review is complete, call `update-task` to record the review timestamp:
     - `task-id`: the story's ID from step 1
     - `code-reviewed`: current timestamp in ISO-8601 format (e.g., "2025-01-15T14:30:00Z")

   Example:
   ```
   update-task(task-id=42, code-reviewed="2025-01-15T14:30:00Z")
   ```


   Note: If the story is reviewed multiple times, the timestamp is overwritten to reflect the most recent review.

## Notes

- All suggestions should be numbered from the start to make selection easy
- Task descriptions can be refined by the user or used verbatim from suggestions
- Categories help route tasks to appropriate execution workflows

- The `add-task` tool automatically handles file creation, formatting, and task preservation
