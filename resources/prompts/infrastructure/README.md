# Prompt Infrastructure Directory

Reusable markdown fragments for prompt templating.

## Purpose

This directory contains shared content that can be included in multiple prompts to avoid duplication. Files here represent common patterns, parsing logic, and instructions that appear across workflow prompts.

## What Belongs Here

- **Common parsing logic** - Argument parsing tables and patterns (e.g., `story-parsing.md`)
- **Repeated instructions** - Standard steps used in multiple workflows (e.g., `out-of-scope-issues.md`)
- **Shared patterns** - Documentation fragments or reference sections that appear in multiple prompts

Files should be self-contained, cohesive sections that make sense when included.

## How to Include

Use Selmer's include directive in prompt files:

```markdown
{% include "infrastructure/story-parsing.md" %}
```

The include path is relative to the `resources/prompts/` directory. When the prompt is loaded, the include directive is replaced with the file's content.

## Naming Conventions

- **Lowercase with hyphens** - `story-parsing.md`, not `StoryParsing.md`
- **Descriptive names** - Name should indicate the content's purpose
- **`.md` extension** - All fragments are markdown files
- **No frontmatter** - Infrastructure files are plain markdown without YAML frontmatter

## Current Files

- `story-parsing.md` - Story argument parsing table and format examples
- `task-parsing.md` - Task argument parsing table and format examples
- `out-of-scope-issues.md` - Pattern for discovering and tracking out-of-scope issues
- `branch-management.md` - Git branch management instructions (reference)
- `worktree-management.md` - Git worktree workflow instructions (reference)
- `default-prompt-text.md` - Default message for missing category prompts
