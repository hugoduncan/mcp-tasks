## Branch Management

**Branch Naming Convention:**
The branch name is created from the title by:
- Converting to lowercase
- Replacing spaces with dashes
- Removing all special characters (keeping only a-z, 0-9, and -)
- For story tasks: use the story title
- For standalone tasks: use the task title

For example: "Complete Remaining Work for EDN Storage Migration" becomes "complete-remaining-work-for-edn-storage-migration"

1. Before starting task execution:
   - Check if currently on a branch named according to the convention above
   - If not, checkout the default branch, ensure it's up to date with
     origin, then create the appropriately named branch

2. After task completion:
   - Remain on the branch for the next task
   - Do not merge or push automatically
