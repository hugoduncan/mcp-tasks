---
title: Create Story PR
description: Create a pull request for a completed story
argument-hint: [story-specification] [additional-context...]
---

Create a pull request for the completed story.

Parse the arguments: $ARGUMENTS
- The first word/token is the story specification
- Everything after is additional context to include in the PR description

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

1. Find the story using `select-tasks` with the appropriate filter (task-id or title-pattern) and `type: story, unique: true`
   - Handle errors:
     - **No match**: Inform user no story found, suggest checking available stories
     - **Multiple matches** (if using title-pattern without unique): List matching stories with IDs and ask for clarification
   - Extract the story's `:id`, `:title`, `:description`, and `:design` fields

2. Verify the current branch:
   - Check current branch name
   - Ensure not on master or main branch
   - If on master/main, inform user they need to be on the story branch
   - The story branch should follow the naming convention from branch-management

3. Collect commits from the story branch:
   - Identify the default branch (master or main)
   - Get all commits on current branch that aren't on the default branch
   - If no commits found, inform user and stop

4. Generate PR content:
   - **PR Title**: Use the story title, optionally prefixed with semantic commit type (feat:, fix:, etc.)
   - **PR Description**:
     - Story title and description
     - Design notes (if any)
     - Auto-generated summary of changes based on commit messages
     - Additional context from $ARGUMENTS (if provided)

5. Create the pull request:
   - Target the default branch (master or main)
   - Use the generated title and description
   - Handle errors:
     - Remote repository not configured
     - Pull request creation fails
     - Required tools not available

6. Return the PR URL to the user

## Notes

- Stories are stored as tasks with `:type :story` in `tasks.ednl`
- Story branches follow the naming convention: lowercase title with spaces→dashes, special chars removed
- This prompt assumes all story tasks are complete but does not verify completion
- Use `/review-story-implementation` before creating PR to verify implementation quality
- The PR creation does not automatically merge or close the story
