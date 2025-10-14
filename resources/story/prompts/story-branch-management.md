## Branch Management

**Branch Naming Convention:**
The `<story-name>` branch is created from the story title by:
- Converting to lowercase
- Replacing spaces with dashes
- Removing all special characters

For example: "Complete Remaining Work for EDN Storage Migration" becomes "complete-remaining-work-for-edn-storage-migration"

1. Before starting task execution:
   - Check if currently on a branch named `<story-name>`
   - If not, checkout the default branch, ensure it's up to date with
     origin, then create the `<story-name>` branch

2. After task completion:
   - Remain on the `<story-name>` branch for the next task
   - Do not merge or push automatically
