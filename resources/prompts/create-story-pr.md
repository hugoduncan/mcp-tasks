---
title: Create Story PR
description: Create a pull request for a completed story
argument-hint: [story-specification] [additional-context...]
---

Create pull request for completed story.

{% include "infrastructure/story-parsing.md" %}

## Process

{% if cli %}
1. Find story via `mcp-tasks show --task-id N --format edn` or `mcp-tasks list --title-pattern "..." --type story --limit 1 --format edn`. Handle errors. Extract `id`, `title`, `description`, `design`.
{% else %}
1. Find story via `select-tasks` with `type: story, unique: true`. Handle errors. Extract `:id`, `:title`, `:description`, `:design`.
{% endif %}

2. Verify branch: not on master/main, on story branch per naming convention.

3. Collect commits from story branch vs default branch. Stop if none found.

4. Generate PR:
   - **Title**: story title, optional semantic prefix (feat:, fix:)
   - **Description**: story title/description, design notes, commit summary, additional context from `$ARGUMENTS`

5. Create PR targeting default branch. Handle errors (no remote, creation failure, missing tools).

6. Record PR number on story:
   - Extract PR number from `gh pr create` output (URL contains the number, e.g., `.../pull/123`)
{% if cli %}
   - Call `mcp-tasks update --task-id <story-id> --pr-num <number>` to record it
{% else %}
   - Call `update-task` with `task-id: <story-id>, pr-num: <number>` to record it
{% endif %}
   - Only set after confirmed PR creation success

7. Return PR URL.
