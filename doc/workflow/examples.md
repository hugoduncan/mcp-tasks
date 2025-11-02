# Workflow Examples

[← Back to Main Workflow](../workflow.md)

This document provides complete examples of mcp-tasks workflows in different configurations.

## Example: Git Mode Workflow

```bash
# 1. Initialize .mcp-tasks with git (enables auto-detection)
mkdir -p .mcp-tasks/tasks .mcp-tasks/complete .mcp-tasks/prompts
cd .mcp-tasks
git init
git add .
git commit -m "Initialize mcp-tasks repository"
cd ..

# 2. Add tasks to a category (use the add-task MCP tool)
# The tool will append the task to tasks.ednl with category "bugfix"

# 3. Process a bugfix task
# Run: /mcp-tasks:next-bugfix
# Agent implements the fix, commits to main repo, and commits task changes to .mcp-tasks repo

# 4. Review the changes
git log -1 --stat                        # Main repo commits
cd .mcp-tasks && git log -1 --stat && cd ..  # Task tracking commits

# 5. Check completed tasks
cat .mcp-tasks/complete.ednl
```

## Example: Non-Git Mode Workflow

```bash
# 1. Create .mcp-tasks directory structure (no git init)
mkdir -p .mcp-tasks/tasks .mcp-tasks/complete .mcp-tasks/prompts

# 2. Optionally configure non-git mode explicitly
echo '{:use-git? false}' > .mcp-tasks.edn

# 3. Add tasks to a category (use the add-task MCP tool)
# The tool will append the task to tasks.ednl with category "feature"

# 4. Process a feature task
# Run: /mcp-tasks:next-feature
# Agent implements the feature, commits to main repo only

# 5. Review the changes
git log -1 --stat                # Only main repo commits

# 6. Check completed tasks (files updated without version control)
cat .mcp-tasks/complete.ednl
```

## See Also

- [Story Workflow](story-workflow.md) - Complete story-based workflow examples
- [Git Worktrees](git-worktrees.md) - Worktree workflow examples
- [Main Workflow](../workflow.md) - Basic task workflow
