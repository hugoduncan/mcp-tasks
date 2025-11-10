# Prompt Directory Migration Guide

## Overview

Starting with version 0.1.50, the prompt directory structure has been reorganized to improve clarity and consistency between category prompts and workflow prompts.

## What Changed

### Directory Structure

**OLD structure:**
```
.mcp-tasks/
├── prompts/                    # Mixed: categories + some workflows
│   ├── simple.md
│   ├── medium.md
│   └── large.md
└── story/
    └── prompts/               # Story workflows only
        ├── complete-story.md
        └── execute-story-child.md
```

**NEW structure:**
```
.mcp-tasks/
├── category-prompts/          # Category execution workflows
│   ├── simple.md
│   ├── medium.md
│   └── large.md
└── prompt-overrides/          # Task/story workflow overrides
    ├── execute-task.md
    ├── refine-task.md
    ├── complete-story.md
    └── execute-story-child.md
```

### Built-in Resource Changes

**OLD built-in structure:**
```
resources/
├── prompts/                   # Mixed: categories + workflows + infrastructure
│   ├── simple.md
│   ├── medium.md
│   ├── execute-task.md
│   ├── default-prompt-text.md
│   └── branch-management.md
└── story/
    └── prompts/
        └── complete-story.md
```

**NEW built-in structure:**
```
resources/
├── category-prompts/          # Category workflows only
│   ├── simple.md
│   ├── medium.md
│   ├── large.md
│   └── clarify-task.md
├── prompts/                   # Task/story workflows
│   ├── execute-task.md
│   ├── refine-task.md
│   ├── complete-story.md
│   ├── create-story-tasks.md
│   └── execute-story-child.md
└── prompts/infrastructure/    # Internal files
    ├── default-prompt-text.md
    ├── branch-management.md
    └── worktree-management.md
```

## Why This Change?

**Problems with old structure:**
1. Category prompts mixed with workflow prompts in same directory
2. Inconsistent override paths (`.mcp-tasks/prompts/` vs `.mcp-tasks/story/prompts/`)
3. Unclear which prompts are categories vs. workflows
4. Code needed to filter categories from workflow prompts

**Benefits of new structure:**
1. Clear separation between category prompts and workflow prompts
2. Consistent override paths for both types
3. Better discoverability and organization
4. Simpler code (no filtering needed)

## Migration Steps

### 1. Check If You Have Custom Prompts

```bash
# Check for category prompts
ls .mcp-tasks/prompts/*.md 2>/dev/null

# Check for story workflow prompts
ls .mcp-tasks/story/prompts/*.md 2>/dev/null
```

If these commands return no files, you don't need to migrate anything.

### 2. Create New Directories

```bash
mkdir -p .mcp-tasks/category-prompts
mkdir -p .mcp-tasks/prompt-overrides
```

### 3. Move Category Prompts

Category prompts define task execution workflows. Common examples include:
- `simple.md`
- `medium.md`
- `large.md`
- `clarify-task.md`
- Any custom categories you created

```bash
# Move category prompts
for file in .mcp-tasks/prompts/{simple,medium,large,clarify-task}.md; do
  if [ -f "$file" ]; then
    mv "$file" .mcp-tasks/category-prompts/
  fi
done

# Move any custom category prompts
# (You'll need to identify these based on your setup)
```

### 4. Move Workflow Prompts

Workflow prompts define task/story operations. Examples include:
- `execute-task.md`
- `refine-task.md`
- `complete-story.md`
- `create-story-tasks.md`
- `execute-story-child.md`

```bash
# Move workflow prompts from .mcp-tasks/prompts/
for file in .mcp-tasks/prompts/{execute-task,refine-task}.md; do
  if [ -f "$file" ]; then
    mv "$file" .mcp-tasks/prompt-overrides/
  fi
done

# Move story workflow prompts
for file in .mcp-tasks/story/prompts/*.md; do
  if [ -f "$file" ]; then
    mv "$file" .mcp-tasks/prompt-overrides/
  fi
done
```

### 5. Clean Up Old Directories

```bash
# Remove old directories if empty
rmdir .mcp-tasks/prompts 2>/dev/null
rmdir .mcp-tasks/story/prompts 2>/dev/null
rmdir .mcp-tasks/story 2>/dev/null
```

### 6. Verify Migration

```bash
# Check new category prompts location
ls .mcp-tasks/category-prompts/

# Check new workflow overrides location
ls .mcp-tasks/prompt-overrides/

# Ensure old directories are gone or empty
ls .mcp-tasks/prompts/ 2>/dev/null
ls .mcp-tasks/story/prompts/ 2>/dev/null
```

## Backward Compatibility

Version 0.1.50 and later maintain backward compatibility with the old directory structure:

**Category prompts** are resolved in this order:
1. `.mcp-tasks/category-prompts/<category>.md` (new location)
2. `.mcp-tasks/prompts/<category>.md` (deprecated, with warning)
3. `resources/category-prompts/<category>.md` (built-in)

**Workflow prompts** are resolved in this order:
1. `.mcp-tasks/prompt-overrides/<name>.md` (new location)
2. `.mcp-tasks/story/prompts/<name>.md` (deprecated, with warning)
3. `resources/prompts/<name>.md` (built-in)

**Deprecation warnings** will be logged when files are read from old locations:
```
WARN: Found category prompt at deprecated location .mcp-tasks/prompts/simple.md
      Please move to .mcp-tasks/category-prompts/simple.md
      Support for old location will be removed in version 1.0.0
```

## Troubleshooting

### "Prompt not found" errors

If you encounter "Prompt not found" errors after migration:

1. Verify files are in the correct new locations
2. Check file permissions (should be readable)
3. Ensure filenames match exactly (case-sensitive)

### Prompts still reading from old location

If you've moved files but the system still reads from old locations:

1. Restart the MCP server
2. Clear any caches your MCP client may have
3. Verify old files were actually removed

### Mixed old and new structure

If you have files in both old and new locations:

- The new location takes precedence
- Old location is ignored (no warning if new location exists)
- Remove files from old locations to avoid confusion

## Deprecation Timeline

**Version 0.1.50 (current):**
- New directory structure introduced
- Backward compatibility with old locations
- Deprecation warnings for old locations

**Future minor versions (0.1.x, 0.2.x):**
- Backward compatibility maintained
- Deprecation warnings continue

**Version 1.0.0 (future major version):**
- Backward compatibility removed
- Only new locations supported
- Old locations will cause "Prompt not found" errors

## Getting Help

If you encounter issues during migration:

1. Check the deprecation warnings in MCP server logs
2. Review this migration guide
3. Open an issue at https://github.com/hugoduncan/mcp-tasks/issues

Include:
- Current directory structure (`ls -R .mcp-tasks/`)
- Any error messages
- mcp-tasks version
