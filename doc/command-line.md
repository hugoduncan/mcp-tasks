# Command Line Interface

The CLI provides direct access to task management from the terminal, complementing the primary MCP interface. Use the CLI when you need to manage tasks from scripts, inspect task state, or work outside of MCP-enabled environments.

For complete task execution workflows and story-based development, see **[workflow.md](workflow.md)**.

## Installation

The CLI is available via the `:cli` alias included in the project's `deps.edn`:

```bash
# From within the mcp-tasks directory
clojure -M:cli <command> [options]

# Or create a convenient alias in your shell
alias mcp-tasks='clojure -M:cli'
mcp-tasks list --format human
```

For initial setup and installation instructions, see **[install.md](install.md)**.

## Commands

### list - Query tasks with optional filters

```bash
# List all open tasks in human format
clojure -M:cli list --status open --format human

# List tasks by category
clojure -M:cli list --category simple

# List child tasks of a story
clojure -M:cli list --parent-id 31

# Filter by title pattern (regex)
clojure -M:cli list --title-pattern "authentication"

# Combine filters
clojure -M:cli list --status open --category medium --limit 10
```

### show - Display detailed information about a task

```bash
# Show task by ID
clojure -M:cli show --task-id 42

# Show with different formats
clojure -M:cli show --task-id 42 --format json
```

### add - Create new tasks

```bash
# Add a simple task
clojure -M:cli add --category simple --title "Fix authentication bug"

# Add with full details
clojure -M:cli add \
  --category feature \
  --title "User profile endpoint" \
  --description "Add REST endpoint for user profile retrieval" \
  --type feature

# Add as child of a story
clojure -M:cli add \
  --category medium \
  --title "Implement JWT validation" \
  --parent-id 31

# Prepend to task list (higher priority)
clojure -M:cli add --category simple --title "Critical fix" --prepend
```

### update - Modify existing task fields

```bash
# Update task status
clojure -M:cli update --task-id 42 --status in-progress

# Update multiple fields
clojure -M:cli update \
  --task-id 42 \
  --title "Updated title" \
  --description "New description" \
  --category medium

# Change task category
clojure -M:cli update --task-id 42 --category large
```

### complete - Mark tasks as complete

```bash
# Complete a task by ID
clojure -M:cli complete --task-id 42

# Complete with a comment
clojure -M:cli complete --task-id 42 \
  --completion-comment "Fixed via PR #123"

# Complete by title
clojure -M:cli complete --title "Fix authentication bug"
```

### reopen - Reopen closed tasks

Reopen tasks that were previously completed. Works with tasks in both `tasks.ednl` (closed but not archived) and `complete.ednl` (archived).

```bash
# Reopen a task by ID
clojure -M:cli reopen --task-id 42

# Reopen by title
clojure -M:cli reopen --title "Fix authentication bug"
```

**Behavior:**
- **Tasks in tasks.ednl**: Changes status from `:closed` to `:open` in place
- **Tasks in complete.ednl**: Moves task back to `tasks.ednl` with status `:open`, appended to the end
- All task metadata (`:meta`, `:relations`, `:parent-id`) is preserved during reopening
- Child tasks follow the same rules as standalone tasks (moved from `complete.ednl` if archived)

**Example scenarios:**
```bash
# Scenario 1: Task was completed but needs more work
clojure -M:cli reopen --task-id 42
# → Task moved back to active tasks at the end of tasks.ednl

# Scenario 2: Archived task needs to be revisited
clojure -M:cli reopen --title "User authentication"
# → Task retrieved from complete.ednl and added to tasks.ednl

# Scenario 3: Use with work-on to start working on reopened task
clojure -M:cli reopen --task-id 42
# Then in MCP client: use work-on tool with task-id 42
```

### delete - Remove tasks

```bash
# Delete by task ID
clojure -M:cli delete --task-id 42

# Delete by title pattern (regex)
clojure -M:cli delete --title-pattern "^OLD:"
```

### prompts - Manage prompt templates

The prompts command provides subcommands for listing, viewing, customizing, and installing prompts.

#### prompts list - List available prompts

```bash
# List all available prompts
clojure -M:cli prompts list

# Different output formats
clojure -M:cli prompts list --format json
clojure -M:cli prompts list --format edn
```

Example output:
```
Category Prompts:
  simple           Execute simple tasks with basic workflow
  medium           Execute medium complexity tasks with analysis...
  large            Execute large tasks with detailed analysis...
  clarify-task     Transform informal task instructions into clear...

Workflow Prompts:
  execute-task           Execute the current task
  refine-task            Refine task requirements interactively
  create-story-tasks     Create tasks for a story
  ...

Total: 11 prompts (4 category, 7 workflow)
```

#### prompts show - Display prompt content

```bash
# Show a specific prompt
clojure -M:cli prompts show simple

# Show with different formats
clojure -M:cli prompts show execute-task --format json
```

#### prompts customize - Copy prompts for customization

Copy built-in prompts to your project for customization:

```bash
# Copy a single prompt
clojure -M:cli prompts customize simple

# Copy multiple prompts
clojure -M:cli prompts customize simple medium execute-task

# JSON output for scripting
clojure -M:cli prompts customize simple --format json
```

The customize command:
- Detects prompt type (category vs workflow) automatically
- Category prompts are copied to `.mcp-tasks/category-prompts/`
- Workflow prompts are copied to `.mcp-tasks/prompt-overrides/`

Example output:
```
Customizing prompts...

✓ simple (category)
  → .mcp-tasks/category-prompts/simple.md

✓ execute-task (workflow)
  → .mcp-tasks/prompt-overrides/execute-task.md

Summary: 2 installed, 0 failed
```

#### prompts install - Generate Claude Code slash commands

Generate Claude Code slash command files from available prompts. This allows using mcp-tasks workflows without the MCP server running.

```bash
# Install to default location (.claude/commands/)
clojure -M:cli prompts install

# Install to custom directory
clojure -M:cli prompts install my-commands/

# JSON output for scripting
clojure -M:cli prompts install --format json
```

**What it does:**
- Generates `.md` files in the target directory with names `mcp-tasks-<prompt-name>.md`
- Renders templates with `cli=true` context, replacing MCP tool references with CLI equivalents
- Preserves frontmatter (description, argument-hint) for Claude Code
- Warns when overwriting existing files

Example output:
```
Installing prompts as Claude Code slash commands...

✓ simple (category)
  → .claude/commands/mcp-tasks-simple.md

✓ execute-task (workflow)
  → .claude/commands/mcp-tasks-execute-task.md

- file-metadata (skipped: Infrastructure file, not a prompt)

Warning: 2 files overwritten

Summary: 11 generated, 3 skipped, 0 failed
```

**Use cases:**
- Work with mcp-tasks prompts without an MCP server
- Use familiar Claude Code slash command interface
- Access workflows directly from Claude Code's `/` menu

**Generated command usage:**
After installation, use the generated commands in Claude Code:
```
/mcp-tasks-simple           # Execute simple tasks
/mcp-tasks-execute-task     # Execute current task
/mcp-tasks-refine-task      # Refine task requirements
```

## Output Formats

The CLI supports three output formats via the `--format` option:

### Human Format (default) - Readable tables and text

```bash
clojure -M:cli list --format human
# ID  Status       Category  Title
# 1   open         simple    Add README badges
# 2   in-progress  feature   Implement auth system
```

### EDN Format - Structured data for programmatic use

```bash
clojure -M:cli list --format edn
# {:tasks [{:id 1 :title "..." :status :open ...}]
#  :metadata {:count 1 :total-matches 1}}
```

### JSON Format - For integration with other tools

```bash
clojure -M:cli show --task-id 1 --format json
# {"task": {"id": 1, "title": "...", "status": "open", ...}}
```

## Common Workflows

### Add and track tasks

```bash
# Add several tasks
clojure -M:cli add --category simple --title "Write unit tests"
clojure -M:cli add --category feature --title "Add API endpoint"

# Check what's pending
clojure -M:cli list --status open --format human

# Mark as in-progress
clojure -M:cli update --task-id 1 --status in-progress

# Complete with notes
clojure -M:cli complete --task-id 1 --completion-comment "All tests passing"

# Reopen if needed
clojure -M:cli reopen --task-id 1
```

### Query and filter

```bash
# Find all blocked tasks (by dependency)
clojure -M:cli list --blocked true

# Find all unblocked tasks
clojure -M:cli list --blocked false

# Show blocking details for blocked tasks
clojure -M:cli list --blocked true --show-blocking

# Show tasks for a specific category
clojure -M:cli list --category feature --format human

# View completed tasks
clojure -M:cli list --status closed --limit 5

# Find tasks with manual blocked status
clojure -M:cli list --status blocked
```

### Blocked task management

Tasks can be blocked by dependencies (via `:blocked-by` relations) or manually blocked (via `:status :blocked`). The CLI provides filtering and display options for working with blocked tasks:

```bash
# List all tasks blocked by dependencies
clojure -M:cli list --blocked true --format human

# Show blocking details (which tasks are blocking each blocked task)
clojure -M:cli list --blocked true --show-blocking

# List only unblocked tasks (useful for finding next available work)
clojure -M:cli list --blocked false --format human

# Find tasks with manual blocked status (waiting on external factors)
clojure -M:cli list --status blocked

# Explain why a specific task is blocked
clojure -M:cli why-blocked --task-id 42
```

**Blocked indicator:**
- The blocked indicator (⊠) is always shown in the table for dependency-blocked tasks
- Use `--show-blocking` to append a "Blocking Details" section listing which task IDs are blocking each task

**Difference between `--blocked` and `--status blocked`:**
- `--blocked true/false` - Filters by computed dependency blocking (based on `:blocked-by` relations)
- `--status blocked` - Filters by manual blocked status (set by user for external factors)
- A task can be both dependency-blocked and manually blocked

### Story management

For complete story workflow documentation, see **[workflow.md#story-workflows](workflow.md#story-workflows)**.

```bash
# List all stories
clojure -M:cli list --type story

# Show story details
clojure -M:cli show --task-id 31 --format human

# List tasks belonging to a story
clojure -M:cli list --parent-id 31 --format human
```

## Error Handling

### Success - Commands return structured data with exit code 0

```bash
clojure -M:cli show --task-id 1 --format edn
# {:task {:id 1 :title "..." ...}}
echo $?  # 0
```

### Errors - Return error information with non-zero exit codes

```bash
# EDN/JSON formats include :error key
clojure -M:cli show --task-id 999 --format edn
# {:error "Task not found" :task-id 999 :file "/path/to/tasks.ednl"}

# Human format prints to stderr
clojure -M:cli show --task-id 999 --format human
# Error: Task not found
#   Task ID: 999
#   File: /path/to/tasks.ednl
echo $?  # 1
```

## Exit Codes

The CLI follows standard Unix exit code conventions:

- **0** - Success: Command completed without errors
- **1** - Error: Command failed (e.g., task not found, validation error, file I/O error)

This makes the CLI suitable for use in shell scripts where exit codes determine control flow:

```bash
# Example: Only proceed if task exists
if clojure -M:cli show --task-id 42 --format edn > /dev/null 2>&1; then
  echo "Task 42 exists"
  # Perform additional operations
else
  echo "Task 42 not found"
  exit 1
fi
```

## Configuration

The CLI loads configuration from `.mcp-tasks.edn` in the current directory by default. Override with:

```bash
# Use different config location
clojure -M:cli --config-path /path/to/project list --status open
```

This is useful for managing tasks across multiple projects from a single location.
