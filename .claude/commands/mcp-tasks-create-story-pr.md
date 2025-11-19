---
description: Create a pull request for a completed story
argument-hint: [story-specification] [additional-context...]
---


Create pull request for completed story.

Parse `$ARGUMENTS`: first token is story specification, rest is context.


| Format | Example | CLI command |
|--------|---------|-------------|
| Numeric / #N / "story N" | 59, #59, story 59 | `mcp-tasks show --task-id N` (verify type is story) |
| Text | "Make prompts flexible" | `mcp-tasks list --title-pattern "..." --type story --limit 1` |


Handle no match or multiple matches by informing user.


## Process


1. Find story via `mcp-tasks show --task-id N --format edn` or `mcp-tasks list --title-pattern "..." --type story --limit 1 --format edn`. Handle errors. Extract `id`, `title`, `description`, `design`.


2. Verify branch: not on master/main, on story branch per naming convention.

3. Collect commits from story branch vs default branch. Stop if none found.

4. Generate PR:
   - **Title**: story title, optional semantic prefix (feat:, fix:)
   - **Description**: story title/description, design notes, commit summary, additional context from `$ARGUMENTS`

5. Create PR targeting default branch. Handle errors (no remote, creation failure, missing tools).

6. Return PR URL.