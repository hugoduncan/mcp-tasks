---
description: Create a pull request for a completed story
argument-hint: [story-specification] [additional-context...]
---


Create pull request for completed story.

Parse `$ARGUMENTS`: first token is story specification, rest is context.


| Format | Example | select-tasks params |
|--------|---------|---------------------|
| Numeric / #N / "story N" | 59, #59, story 59 | `task-id: N, type: story, unique: true` |
| Text | "Make prompts flexible" | `title-pattern: "...", type: story, unique: true` |


Handle no match or multiple matches by informing user.


## Process


1. Find story via `select-tasks` with `type: story, unique: true`. Handle errors. Extract `:id`, `:title`, `:description`, `:design`.


2. Verify branch: not on master/main, on story branch per naming convention.

3. Collect commits from story branch vs default branch. Stop if none found.

4. Generate PR:
   - **Title**: story title, optional semantic prefix (feat:, fix:)
   - **Description**: story title/description, design notes, commit summary, additional context from `$ARGUMENTS`

5. Create PR targeting default branch. Handle errors (no remote, creation failure, missing tools).

6. Return PR URL.