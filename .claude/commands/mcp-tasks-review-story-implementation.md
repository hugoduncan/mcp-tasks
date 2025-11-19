---
description: Review the implementation of a story
argument-hint: [story-specification] [additional-context...]
---


Review the implementation of a story against its requirements and code quality standards.

Parse `$ARGUMENTS`: first token is story specification, rest is context.


| Format | Example | CLI command |
|--------|---------|-------------|
| Numeric / #N / "story N" | 59, #59, story 59 | `mcp-tasks show --task-id N` (verify type is story) |
| Text | "Make prompts flexible" | `mcp-tasks list --title-pattern "..." --type story --limit 1` |


Handle no match or multiple matches by informing user.


## Process


1. Find the story task using `mcp-tasks show --task-id N --format edn` or `mcp-tasks list --title-pattern "..." --type story --limit 1 --format edn`
   - Handle no match or multiple matches by informing user
   - Extract the story's `id`, `title`, and `description` fields


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

     a) For each selected suggestion, use `mcp-tasks add`:
        - `mcp-tasks add --category <category> --title "CR: <description>" --description "<details>" --parent-id <story-id>`

        Example:
        ```bash
        mcp-tasks add --category medium --title "CR: Add error handling for edge cases" \
          --description "throw exceptions if edge cases not supported" \
          --parent-id 42
        ```


     c) Confirm to user:
        "✓ Added N task(s) to tasks.ednl for story '<story-name>':"
        List each task with its category in a bullet format

   - If user selects 'none' or provides no selections, skip task creation

## Notes

- All suggestions should be numbered from the start to make selection easy
- Task descriptions can be refined by the user or used verbatim from suggestions
- Categories help route tasks to appropriate execution workflows

- The `mcp-tasks add` command automatically handles file creation and formatting
