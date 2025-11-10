## Worktree Management

When `:worktree-management?` is enabled, the `work-on` tool automatically manages git worktrees for task isolation.

**Worktree Setup:**
The `work-on` tool handles worktree creation and switching:
- Creates a worktree in a sibling directory if it doesn't exist
- Reuses an existing worktree if already created
- Verifies the worktree is on the correct branch
- Warns if the working directory is not clean

**Worktree Completion:**
After completing the task implementation:

1. Ask the user for confirmation before removing the worktree:
   - "The task is complete. Would you like me to remove the worktree at `<worktree-path>`?"
   
2. If the user confirms removal:
   - Use: `git worktree remove <worktree-path>`
   - Inform the user that the worktree has been removed
   
3. If the user declines removal:
   - Inform the user that the worktree has been left in place
   - Provide the worktree path for reference
