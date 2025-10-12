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

1. Read the story file from `.mcp-tasks/story/stories/<story-name>.md`
   - If the file doesn't exist, inform the user and stop

2. Analyze the current branch against the description of the story:
   - Does the code implement the story correctly?
   - Does it have extra functionality that was not requested?

3. Analyze the quality of the code in the implementation:
   - Is it as simple as possible?
   - Is the code DRY?
   - Is the naming used consistent?
   - Check error handling, input validation, and edge cases

4. Analyze the structure of the code:
   - Separation of concerns
   - Single responsibility

5. Report findings:
   - List issues clearly and numbered (e.g., "1. Add error handling...", "2. Improve test coverage...")
   - Suggest improvements in a numbered list format

6. Interactive task creation from suggestions:
   - After presenting all findings and suggestions, ask the user:
     "Would you like to create story tasks for any of these suggestions? (enter numbers separated by commas, e.g., '1,3' or 'all' for all suggestions, or 'none' to skip)"
   
   - If user selects suggestions:
     a) For each selected suggestion:
        - Present the suggestion text
        - Ask: "Task description (press Enter to use suggestion as-is, or type custom description):"
        - If user provides text, use it; otherwise use the original suggestion text
        - Ask: "Category (simple/medium/large) [default: medium]:"
        - Validate category input; if invalid, re-prompt
        - If user just presses Enter, use "medium"
     
     b) After collecting all task details, append to story task file:
        - Target file: `.mcp-tasks/story/story-tasks/<story-name>-tasks.md`
        - Create parent directories if needed
        - Read existing content (or treat as empty if file doesn't exist)
        - Ensure existing content ends with proper spacing (add blank line if needed)
        - For each new task, append in this format:
          ```
          - [ ] <task description>
          CATEGORY: <category>
          
          ```
        - Write the complete content back to the file
     
     c) Confirm to user:
        "✓ Added N task(s) to <story-name>-tasks.md:"
        List each task with its category in a bullet format

   - If user selects 'none' or provides no selections, skip task creation

## Notes

- All suggestions should be numbered from the start to make selection easy
- Task descriptions can be refined by the user or used verbatim from suggestions
- Categories help route tasks to appropriate execution workflows
- The task file format must match story task conventions with `- [ ]` and `CATEGORY:` on separate line
- Always preserve existing tasks when appending new ones
