# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

An MCP (Model Context Protocol) server that provides task-based workflow management for agents. Tasks are organized into configurable categories, each with its own execution instructions.

## Architecture

**Task Storage Structure:**
- `.mcp-tasks/task/<category-name>.md` - Incomplete tasks for each category
- `.mcp-tasks/completed/<category-name>.md` - Completed tasks archive
- `.mcp-tasks/prompt/<category-name>.md` - Category-specific execution prompts

**Task File Format:**
Each category's markdown file contains tasks as checkbox items that can be marked incomplete `- [ ]` or complete `- [x]`.

**Dependencies:**
- Depends on local `mcp-clj` server library at `../mcp-clj/projects/server`
- Ensure the sibling mcp-clj repository is available for development

## Development Commands

**REPL:**
```bash
clj
```

**Linting:**
```bash
clj-kondo --lint src test
```

## Key Concepts

- **Categories**: Organize tasks by type/purpose, each with custom execution instructions
- **Default Categories**: Server provides defaults, but system is fully configurable
- **Task Lifecycle**: Tasks move from incomplete → complete → archived


## Other

- Use semantic commit messages, and semantic pull request titles
- run `cljstyle fix` before making commits
- run `(clj-kondo --lint src test)
  ⎿  linting took 225ms, errors: 0, warnings: 0

⏺ No clj-kondo errors found. The codebase passes linting cleanly.

───────────────────────────────────────────────────────────────────────────────────────
#  run  
───────────────────────────────────────────────────────────────────────────────────────
  # to memorize                                                         ⧉ In main.clj
- run `clj-kondo --lint src test` before commiting
- when merging a PR provide a clean commit message using semntic commit message style.  Do no just use the default message or a concatenation of all the commit messages.  The message should reflect the scope and logical content of the PR, not all the interim work used to implement it.