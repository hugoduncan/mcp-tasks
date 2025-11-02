# Git Worktrees for Task Isolation

[← Back to Main Workflow](../workflow.md)

For complex projects with multiple parallel task streams, you can use git worktrees to isolate work by category, story, or individual task. mcp-tasks supports both manual and automatic worktree management.

## Choosing Manual vs Automatic Mode

**Use automatic mode (`:worktree-management? true`)** when:
- Working on stories with multiple related tasks
- Need guaranteed isolation between parallel work streams
- Want the system to handle worktree creation/cleanup automatically
- Prefer a guided workflow with minimal manual git operations

**Use manual mode** when:
- Need fine-grained control over worktree organization
- Want to organize worktrees by category rather than by task
- Have complex worktree requirements not covered by automatic mode
- Prefer explicit control over all git operations

## Automatic Worktree Management

Enable automatic worktree management in `.mcp-tasks.edn`:

```clojure
{:worktree-management? true}  ; Automatically enables :branch-management? true
```

**How it works:**

1. **Worktree creation** - When executing a task or story:
   - System creates worktree in sibling directory: `../<project-name>-<task-or-story-name>`
   - Worktree is created on the appropriate branch (per `:branch-management?` convention)
   - Example: Story "Add Git Worktree Management" → `../mcp-tasks-add-git-worktree-management`

2. **Worktree reuse** - On subsequent task executions:
   - System checks if worktree already exists
   - Verifies it's on the correct branch
   - Warns if working directory is not clean
   - Switches to existing worktree directory

3. **Cleanup workflow** - After task/story completion:
   - AI agent asks for user confirmation before removing worktree
   - If confirmed: Removes worktree using `git worktree remove <path>`
   - If declined: Leaves worktree in place for manual cleanup

**Benefits of automatic mode:**
- Zero manual worktree setup
- Consistent naming and organization
- Automatic isolation per story/task
- Guided cleanup with confirmation prompts

## Manual Worktree Management

For category-based organization or custom workflows, manually create worktrees:

```bash
# Create a worktree for each category
git worktree add ../project-feature feature-branch
git worktree add ../project-bugfix bugfix-branch
git worktree add ../project-refactor refactor-branch
```

**Manual workflow:**

1. **Separate task streams** - Each worktree can have its own `.mcp-tasks/tasks.ednl` with relevant tasks

2. **Process tasks independently** - Work in each worktree:
   ```bash
   cd ../project-feature
   # Run /mcp-tasks:next-feature

   cd ../project-bugfix
   # Run /mcp-tasks:next-bugfix
   ```

3. **Merge appropriately** - Integrate completed work:
   ```bash
   # In main repository
   git merge feature-branch
   git merge bugfix-branch
   ```

## Benefits of Worktree Workflow

- **Isolation**: Different work streams don't interfere with each other
- **Parallel execution**: Process multiple task streams simultaneously
- **Focused commits**: Each branch contains related changes
- **Easy rollback**: Discard a worktree branch if work isn't needed
- **Clean working directory**: Main worktree remains unaffected by experimental work

## See Also

- [Story Workflow](story-workflow.md) - Story-based workflow with worktrees
- [Main Workflow](../workflow.md) - Basic task workflow
- [Examples](examples.md) - Complete workflow examples
