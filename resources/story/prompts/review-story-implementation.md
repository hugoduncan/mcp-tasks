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
   - List issues
   - Suggest improvements
