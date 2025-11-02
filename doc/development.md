# Development

This guide covers development workflows, project architecture, and contribution guidelines for mcp-tasks.

## Development Setup

### Prerequisites

- [Clojure CLI](https://clojure.org/guides/install_clojure) (1.11 or later)
- Java JDK 11 or later
- [clj-kondo](https://github.com/clj-kondo/clj-kondo) for linting
- Local [mcp-clj](https://github.com/hugoduncan/mcp-clj) repository at `../mcp-clj/projects/server`

### REPL

Start a REPL for interactive development:

```bash
clj
```

### Linting

Run clj-kondo to check code quality:

```bash
clj-kondo --lint src test --fail-level warning
```

The project uses `--fail-level warning` to ensure both errors and warnings are addressed.

### Testing

Run tests using the test alias:

```bash
# Run unit tests only (default)
clojure -M:dev:test --focus :unit

# Run integration tests
clojure -M:dev:test --focus :integration

# Run all tests
clojure -M:dev:test

# Run specific test namespace
clojure -M:dev:test --focus mcp-tasks.main-test

# Run specific test
clojure -M:dev:test --focus mcp-tasks.main-test/config-threading-integration-test
```

### Code Formatting

Format code with cljstyle:

```bash
cljstyle fix
```

Always run `cljstyle fix` before making commits.

## Architecture

### Overview

mcp-tasks is a task management layer built on top of the [clojure-mcp](https://github.com/hugoduncan/mcp-clj) server library:

- **clojure-mcp**: Provides MCP protocol infrastructure
- **mcp-tasks**: Adds task-based workflow automation

### Task Storage

Tasks are stored in EDNL (EDN Lines) format:

```
.mcp-tasks/          # Task tracking directory
├── .git/            # Optional: Version control for task history
├── tasks.ednl       # Active tasks (EDN lines format)
├── complete.ednl    # Completed task archive (EDN lines format)
└── prompts/         # Category-specific instructions (optional)
    └── feature.md
```

**EDNL Format**: Each line in `tasks.ednl` and `complete.ednl` is a complete EDN map representing a task record.

### Key Components

**Task Discovery and Category Management** (src/mcp_tasks/prompts.clj:29)
- Scans `.mcp-tasks/prompts/` directory for category definitions
- Auto-discovers categories from `.md` filenames
- Registers categories with the MCP server

**MCP Prompt Generation** (src/mcp_tasks/prompts.clj:85)
- Dynamically creates MCP prompts for each category
- Generates prompt resources (`prompt://category-<name>`)
- Includes story workflow prompts

**Custom Instruction Loading** (src/mcp_tasks/prompts.clj:76)
- Loads category-specific execution instructions
- Parses YAML frontmatter for metadata
- Falls back to built-in prompts when custom prompts not found

### Task Schema

Tasks are represented as EDN maps with the following structure (defined in `src/mcp_tasks/schema.clj`):

```clojure
{:id            ;; int - unique task identifier
 :parent-id     ;; int or nil - optional parent task reference
 :status        ;; :open | :closed | :in-progress | :blocked
 :title         ;; string - task title
 :description   ;; string - detailed task description
 :design        ;; string - design notes
 :category      ;; string - execution category
 :type          ;; :task | :bug | :feature | :story | :chore
 :meta          ;; map - arbitrary string key-value metadata
 :relations     ;; vector of Relation maps
}
```

**Relation Schema:**

```clojure
{:id         ;; int - relation identifier
 :relates-to ;; int - target task ID
 :as-type    ;; :blocked-by | :related | :discovered-during
}
```

### MCP Protocol Integration

The server exposes three types of MCP capabilities:

1. **Tools**: Operations for task management
   - `add-task`, `select-tasks`, `complete-task`, etc.
   - Defined in `src/mcp_tasks/tools/`

2. **Prompts**: Workflow execution instructions
   - Category prompts: `prompt://category-<name>`
   - Story prompts: `prompt://refine-task`, etc.
   - Defined in `resources/prompts/` and `.mcp-tasks/prompts/`

3. **Resources**: Queryable information
   - `resource://current-execution` - Current execution state
   - Prompt resources for inspection

## Building

### JAR

Build a JAR file:

```bash
# Build
clj -T:build jar

# Clean build artifacts
clj -T:build clean

# Check version
clj -T:build version
```

### Native Binaries

For native binary builds using GraalVM, see **[Building from Source](build.md)**.

## Testing Strategy

The test suite is organized by scope:

- **Unit tests** (`:unit` metadata): Fast, isolated tests
- **Integration tests** (`:integration` metadata): Tests with external dependencies (filesystem, git)

Integration tests may require:
- Git repository setup
- Filesystem access
- Temporary directories

## Continuous Integration

GitHub Actions workflows:

- **test.yml**: Runs on all pushes and PRs
  - Runs cljstyle check
  - Runs clj-kondo with `--fail-level warning`
  - Runs unit and integration tests separately

- **release.yml**: Manual release workflow
  - Builds JAR and native binaries
  - Generates changelog with git-cliff
  - Creates GitHub release

## Project Status

**Status:** Alpha

Core functionality is stable, but the API may evolve based on user feedback and real-world usage patterns.

## See Also

- [Configuration](config.md) - Configuration options and setup
- [Customization](customization.md) - Custom categories and prompts
- [Building from Source](build.md) - Native binary build instructions
- [clojure-mcp](https://github.com/hugoduncan/mcp-clj) - MCP server library
