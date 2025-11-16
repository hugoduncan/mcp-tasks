# Customization

This guide describes how to customize mcp-tasks behavior and workflows beyond configuration file settings. For configuration options like git mode and branch management, see [Configuration](config.md).

Customization focuses on adapting task execution workflows, categories, and metadata to match your team's processes, while configuration controls runtime behavior and feature flags.

## Configuration File

For system-level settings like git integration, branch management, and worktree support, see the [Configuration](config.md) documentation. The `.mcp-tasks.edn` configuration file controls how the system operates, while the customizations described below control how tasks are defined and executed.

## Command Line Tools

The mcp-tasks CLI provides commands for managing prompt templates and customizing workflows.

### List Available Prompts

Display all available built-in prompts with their names, types, and descriptions:

```bash
# Human-readable output (default)
mcp-tasks prompts list

# JSON output
mcp-tasks prompts list --format json

# EDN output
mcp-tasks prompts list --format edn
```

The list command shows:
- **Category prompts**: Define execution workflows for task categories (e.g., `simple`, `medium`, `large`)
- **Workflow prompts**: Define operations like executing tasks, refining stories, creating tasks

Example output:
```
Available Prompts:

Category Prompts (4):
  simple          Execute simple tasks with basic workflow
  medium          Execute medium complexity tasks with analysis, design...
  large           Execute large tasks with detailed analysis, design...
  clarify-task    Transform informal task instructions into clear...

Workflow Prompts (7):
  execute-task           Execute a task following category-specific workflow...
  refine-task            Refine a task to improve clarity and completeness
  execute-story-child    Execute the next task from a story's task list
  create-story-tasks     Break down a story into categorized, executable tasks
  complete-story         Mark a story as complete and archive it
  create-story-pr        Create a pull request for a completed story
  review-story-impl...   Review the implementation of a story

Total: 11 prompts (4 category, 7 workflow)
```

### Install Prompt Templates

Install built-in prompt templates to local directories for customization:

```bash
# Install a single prompt
mcp-tasks prompts install simple

# Install multiple prompts
mcp-tasks prompts install simple medium execute-task

# JSON output for scripting
mcp-tasks prompts install simple --format json
```

The install command:
- Detects prompt type (category vs workflow) automatically
- Category prompts install to `.mcp-tasks/category-prompts/`
- Workflow prompts install to `.mcp-tasks/prompt-overrides/`
- Provides clear feedback about what was installed and where
- Supports multiple output formats (human, json, edn)

Example output:
```
Installing prompts...

✓ simple (category)
  → .mcp-tasks/category-prompts/simple.md

✓ execute-task (workflow)
  → .mcp-tasks/prompt-overrides/execute-task.md

Summary: 2 installed, 0 failed
```

**Backward Compatibility:** The system continues to read from deprecated locations (`.mcp-tasks/prompts/` and `.mcp-tasks/story/prompts/`) with warnings. See [Migration Guide](migration/prompt-directories.md) for details.

## Prompt Templates

Prompt templates define the execution workflow for tasks. mcp-tasks provides built-in prompts for common workflows, and you can create custom prompts to match your team's processes.

### Prompt File Format

Prompts are stored as Markdown files with optional YAML frontmatter:

```markdown
---
description: Execute simple tasks with basic workflow
---

- Analyze the task specification in the context of the project
- Plan an implementation approach
- Implement the solution
- Create a git commit with the code changes in the main repository
```

The frontmatter section (delimited by `---`) contains metadata about the prompt. The content section (after frontmatter) contains the execution instructions that guide the AI agent.

### Frontmatter Options

Prompts support the following frontmatter fields:

- **`description`**: Brief description of what the prompt does (used in MCP prompt listings and tool documentation)
- **`argument-hint`**: (Story prompts only) Defines expected arguments using `<required>` and `[optional]` syntax

Example frontmatter:

```yaml
---
description: Execute medium complexity tasks with analysis, design, and user interaction
---
```

For story prompts with arguments:

```yaml
---
description: Execute the next task from a story
argument-hint: <story-name> [additional-context...]
---
```

### Prompt Templating

Prompts support templating for reusable content and reduced duplication. The system uses Selmer-based syntax for includes and variable substitution.

#### Template Syntax

**File Includes:**
```markdown
{% include "path/to/file.md" %}
```

Include paths are relative to the prompt base directory (`resources/prompts/` for built-in prompts). The include directive inserts the entire content of the referenced file.

Example:
```markdown
## Parse Arguments

{% include "infrastructure/story-parsing.md" %}

## Execute Task
...
```

**Variable Substitution:**
```markdown
Hello {{name}}!
```

Variables are replaced with values from the rendering context. If a variable is missing, it remains as `{{variable}}` by default.

#### Include Resolution

Includes are resolved in this order:
1. User override directory (`.mcp-tasks/prompt-overrides/` or `.mcp-tasks/category-prompts/`)
2. Built-in resources on classpath (`resources/prompts/`)

This allows you to override specific infrastructure files while still using built-in templates.

#### Creating Reusable Template Fragments

Store shared content in the `infrastructure/` subdirectory:

```
resources/prompts/
├── execute-task.md
├── execute-story-child.md
└── infrastructure/
    ├── story-parsing.md
    ├── out-of-scope-issues.md
    └── branch-management.md
```

Example infrastructure file (`infrastructure/story-parsing.md`):
```markdown
Parse `$ARGUMENTS`: first token is story specification, rest is context.

| Format | Example | select-tasks params |
|--------|---------|---------------------|
| Numeric / #N / "story N" | 59, #59, story 59 | `task-id: N, type: story, unique: true` |
| Text | "Make prompts flexible" | `title-pattern: "...", type: story, unique: true` |

Handle no match or multiple matches by informing user.
```

Then include in multiple prompts:
```markdown
---
description: Execute the next task from a story
---

{% include "infrastructure/story-parsing.md" %}

## Process
1. Find first unblocked incomplete child...
```

#### Template Behavior

**Missing Variables:** By default, missing variables are left as-is (`{{var}}`). The system supports three modes:
- `:leave` (default) - Keep `{{var}}` in output
- `:empty` - Replace with empty string
- `:error` - Throw exception

**Recursive Includes:** Includes can contain other includes, up to a depth of 10 levels to prevent circular references.

**HTML Entity Escaping:** Selmer automatically escapes HTML entities (e.g., apostrophes become `&#39;`). For markdown prompts, this is typically not an issue as the content is rendered as text.

**Error Handling:** Template errors provide clear messages indicating:
- Missing include files with searched paths
- Missing variables when using `:error` mode
- Circular include detection when depth exceeds 10

#### Best Practices

1. **Factor out repeated patterns** - If the same text appears in multiple prompts, extract it to an infrastructure file.

2. **Use descriptive paths** - Name infrastructure files by their content, not their usage (e.g., `story-parsing.md` not `story-prompt-header.md`).

3. **Keep templates focused** - Each infrastructure file should contain one logical piece of content.

4. **Document dependencies** - Note in comments which prompts use which infrastructure files.

5. **Test template rendering** - After creating templates, verify they render correctly in the MCP client.

6. **Avoid deep nesting** - Keep include depth shallow (1-2 levels) for maintainability.

### Creating Custom Prompts

To create a custom category prompt:

1. Create a `.md` file in `.mcp-tasks/category-prompts/` directory
2. Add frontmatter with a `description` field (optional but recommended)
3. Write execution instructions in Markdown
4. The filename (without `.md`) becomes the category name

Example `.mcp-tasks/category-prompts/research.md`:

```markdown
---
description: Execute research tasks with comprehensive analysis
---

- Define research questions and scope
- Identify relevant sources and documentation
- Gather information systematically
- Synthesize findings into actionable insights
- Document results with citations
- Identify gaps or areas requiring deeper investigation
```

Tasks assigned to the `research` category will use these execution instructions.

### Prompt Discovery

The system automatically discovers prompts by:

1. **Category prompts**: Scanning `.mcp-tasks/category-prompts/` directory for `.md` files
2. **Workflow prompts**: Reading from `.mcp-tasks/prompt-overrides/` for custom workflow overrides
3. Extracting names from filenames (without extension)
4. Parsing frontmatter to extract metadata
5. Registering prompts with the MCP server

**Built-in prompts** serve as defaults:
- Category prompts: `resources/category-prompts/` (simple.md, medium.md, large.md, clarify-task.md)
- Workflow prompts: `resources/prompts/` (execute-task.md, refine-task.md, complete-story.md, etc.)

**Custom prompts** override built-in prompts:
- Category overrides: `.mcp-tasks/category-prompts/<category>.md`
- Workflow overrides: `.mcp-tasks/prompt-overrides/<name>.md`

### Category Prompts vs Workflow Prompts

mcp-tasks has two types of prompts:

- **Category prompts**: Define workflows for task categories (e.g., `simple`, `medium`, `large`). Located in `.mcp-tasks/category-prompts/<category>.md`
- **Workflow prompts**: Define workflows for task/story operations (e.g., executing tasks, refining stories, creating tasks). Located in `.mcp-tasks/prompt-overrides/<name>.md`

## Categories

Categories organize tasks by execution workflow. Each category corresponds to a prompt template that defines how tasks in that category should be executed.

### Category Discovery

Categories are automatically discovered by scanning the `.mcp-tasks/category-prompts/` directory:

1. System looks for `.md` files in `.mcp-tasks/category-prompts/`
2. Filename (without `.md` extension) becomes the category name
3. Each category automatically gets a corresponding MCP prompt

For example, if you have:
```
.mcp-tasks/category-prompts/
├── simple.md
├── medium.md
├── large.md
└── research.md
```

The system discovers four categories: `simple`, `medium`, `large`, and `research`.

### Adding Custom Categories

To add a new category:

1. Create a prompt file in `.mcp-tasks/category-prompts/<category-name>.md`
2. Define execution instructions in the file
3. Assign tasks to the category using the `:category` field

The category becomes immediately available for use in tasks. No additional configuration is required.

### Category Naming Conventions

Category names should:

- Use lowercase letters, numbers, and hyphens
- Describe the workflow or complexity level (e.g., `simple`, `complex`, `research`, `spike`)
- Be concise and memorable
- Match the filename without `.md` extension

Examples:
- `simple` - Basic implementation tasks
- `medium` - Tasks requiring analysis and design
- `large` - Complex tasks with multiple phases
- `research` - Investigation and analysis tasks
- `spike` - Exploratory proof-of-concept tasks

### Relationship Between Categories and Prompts

Categories and prompts have a 1:1 mapping:

- Each category has exactly one prompt file
- Each prompt file defines exactly one category
- The category name equals the prompt filename (without `.md`)

When you create a task with `:category "research"`, the system:
1. Looks for `.mcp-tasks/category-prompts/research.md`
2. Falls back to deprecated `.mcp-tasks/prompts/research.md` (with warning)
3. Falls back to built-in `resources/category-prompts/research.md`
4. Loads the prompt instructions from the first found location
5. Uses those instructions to execute the task

## Task Metadata

Tasks support custom metadata for tracking additional information beyond the core task fields.

### Using the :meta Field

The `:meta` field is a map of string keys to string values that can store arbitrary task information:

```clojure
{:id 42
 :title "Implement user authentication"
 :category "large"
 :meta {"priority" "high"
        "estimate" "3 days"
        "reviewer" "alice"
        "component" "auth"}}
```

The `:meta` field is useful for:

- Priority levels
- Time estimates
- Component or module tags
- Assigned reviewers or owners
- External issue tracker IDs
- Custom workflow states

**Important**: Both keys and values must be strings. Use string representations for other data types (e.g., `"3"` instead of `3`).

### Task Relations

The `:relations` field defines typed relationships between tasks. Each relation is a map with:

- `:id` - Unique identifier for the relation
- `:relates-to` - ID of the related task
- `:as-type` - Type of relationship

**Available relation types:**

- `:blocked-by` - This task is blocked by another task
- `:related` - General relationship without blocking semantics
- `:discovered-during` - This task was discovered while working on another task

Example:

```clojure
{:id 100
 :title "Implement payment processing"
 :category "large"
 :relations [{:id 1
              :relates-to 99
              :as-type :blocked-by}
             {:id 2
              :relates-to 87
              :as-type :related}]}
```

This task (100) is blocked by task 99 and related to task 87.

### Metadata Conventions

While `:meta` and `:relations` support any valid data, consider establishing team conventions:

**Priority levels:**
```clojure
:meta {"priority" "critical|high|medium|low"}
```

**Time estimates:**
```clojure
:meta {"estimate" "1h|2d|1w"}
```

**Component tags:**
```clojure
:meta {"component" "auth|api|ui"}
```

**External tracking:**
```clojure
:meta {"jira-id" "PROJ-1234"
       "github-issue" "123"}
```

Consistent conventions make it easier to filter, report, and manage tasks across your team.

## Examples

### Custom Research Category

Create `.mcp-tasks/category-prompts/research.md`:

```markdown
---
description: Execute research tasks with systematic investigation
---

## Research Process

1. **Define Scope**
   - Clarify research questions
   - Identify constraints and boundaries

2. **Gather Information**
   - Search documentation and code
   - Review relevant examples and patterns
   - Document sources

3. **Analyze Findings**
   - Synthesize information
   - Identify trade-offs
   - Compare alternatives

4. **Document Results**
   - Summarize key findings
   - List recommendations
   - Note open questions

5. **Commit Documentation**
   - Add research notes to repository
   - Link to relevant issues or tasks
```

Create a research task:

```clojure
{:id 150
 :title "Research authentication libraries for Clojure"
 :description "Evaluate Ring-based auth libraries and OAuth providers"
 :category "research"
 :type :task
 :meta {"area" "security"
        "estimate" "2d"}
 :relations []}
```

### Task with Blocking Relationship

Create a task that blocks another task:

```clojure
;; Infrastructure setup task
{:id 200
 :title "Set up authentication database schema"
 :category "simple"
 :type :task
 :meta {"priority" "high"}
 :relations []}

;; Implementation task (blocked by infrastructure)
{:id 201
 :title "Implement user registration endpoint"
 :category "medium"
 :type :feature
 :meta {"priority" "high"}
 :relations [{:id 1
              :relates-to 200
              :as-type :blocked-by}]}
```

Task 201 cannot proceed until task 200 is completed.

### Custom Metadata for Workflow Tracking

Track additional workflow information:

```clojure
{:id 300
 :title "Refactor payment processing module"
 :category "large"
 :type :chore
 :meta {"component" "payments"
        "technical-debt" "true"
        "estimate" "1w"
        "reviewer" "bob"
        "risk-level" "medium"}
 :relations []}
```

## MCP Resources

mcp-tasks exposes execution prompts and category metadata as MCP resources, enabling advanced use cases like prompt composition, inspection, and category discovery.

### Categories Resource

The categories resource provides a machine-readable list of all available task categories and their descriptions:

**URI:** `resource://categories`

**Format:** JSON

**Structure:**
```json
{
  "categories": [
    {
      "name": "simple",
      "description": "Execute simple tasks with basic workflow"
    },
    {
      "name": "medium",
      "description": "Execute medium complexity tasks..."
    }
  ]
}
```

The categories list is automatically generated from discovered category prompts in `.mcp-tasks/category-prompts/` and includes descriptions extracted from YAML frontmatter. Categories are returned in alphabetical order by name.

**Use cases:**
- Discover available categories programmatically
- Build UI/CLI tools that present category options to users
- Validate task category fields against available categories
- Generate documentation about task categorization

### Prompt Resources

Each category and story workflow has an associated prompt resource accessible via the `prompt://` URI scheme:

**Category prompts:**
```
prompt://category-<category>
```

Examples:
- `prompt://category-simple` - Simple task execution prompt
- `prompt://category-feature` - Feature task execution prompt
- `prompt://category-bugfix` - Bugfix task execution prompt

**Story prompts:**
```
prompt://refine-task
prompt://create-story-tasks
prompt://execute-story-child
```

### Resource Format

Prompt resources include YAML frontmatter with metadata:

```markdown
---
description: Execute simple tasks with basic workflow
---

Please complete the next simple task following these steps:
[... prompt content ...]
```

**Frontmatter fields:**
- `description` (string) - Brief explanation of the prompt's purpose
- Additional metadata may be present in custom prompts

### Accessing Resources

**List all prompt resources:**

Most MCP clients provide a way to list available resources. The exact method depends on your client.

**Read a prompt resource:**

Use your MCP client's resource reading capability with the `prompt://` URI. For example, in Claude Code you might use tools to inspect prompt resources programmatically.

### Use Cases

**1. Prompt Composition**

Build custom workflows by combining or referencing existing prompts:

```markdown
# Custom prompt that references another
Execute the feature task, then follow the review process from prompt://review-checklist
```

**2. Prompt Inspection**

Understand what a category prompt does before executing tasks:
- Read `prompt://category-simple` to see the simple task workflow
- Compare different category prompts to choose appropriate categories

**3. Documentation**

Generate workflow documentation by extracting prompt content and descriptions.

**4. Tooling Integration**

Build tools that discover and utilize mcp-tasks prompts programmatically via the MCP resource protocol.

## See Also

- [Configuration](config.md) - System configuration and feature flags
- [Installation](install.md) - Setup and deployment
- [Workflow Documentation](workflows/) - Task execution workflows
- [MCP Protocol](https://modelcontextprotocol.io/) - Model Context Protocol specification
