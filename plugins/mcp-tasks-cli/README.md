# MCP-Tasks CLI Plugin

Pre-built CLI tool for mcp-tasks task management in Claude Code.

## Overview

This Claude Code plugin provides the `mcp-tasks` command-line tool and its usage documentation. The CLI is a self-contained Babashka uberscript that can be used for scripting and automation workflows.

**What this plugin provides:**
- Pre-built `mcp-tasks` CLI binary (Babashka uberscript)
- Command reference documentation via skill file
- Standalone tool for scripting and automation

**When to use the CLI:**
- Scripting and automation (shell scripts, CI/CD pipelines)
- Quick command-line queries and updates
- Integration with other tools

**When to use the MCP server:**
- Agent-driven workflows with Claude Code
- Interactive task execution and refinement
- Story-based development workflows

See the [mcp-tasks-skill](https://github.com/hugoduncan/mcp-tasks/tree/main/plugins/mcp-tasks-skill) plugin for MCP server documentation.

## Prerequisites

**Required:**
- Babashka (bb) must be installed on your system
- See https://babashka.org/#installation for installation instructions

The CLI is a Babashka uberscript that bundles all dependencies. No other installation is required.

## Installation

### From Claude Code Marketplace (Recommended)

```bash
/plugin install hugoduncan/mcp-tasks-cli
```

### From Local Path (Development/Testing)

```bash
/plugin install /path/to/mcp-tasks/plugins/mcp-tasks-cli
```

## Usage

After installation, the `mcp-tasks` binary is available in the plugin's `bin/` directory.

### Adding to PATH (Optional for Outside Claude Usage)

**Note:** This step is only needed if you want to use the CLI tool outside of Claude Code (e.g., in terminal scripts or automation). Within Claude Code, the tool is automatically available.

For command-line access outside Claude Code, add the binary to your PATH or create a symlink:

```bash
# Option 1: Symlink to a directory in your PATH
ln -s ~/.claude/plugins/hugoduncan/mcp-tasks-cli/bin/mcp-tasks /usr/local/bin/mcp-tasks

# Option 2: Add plugin bin directory to PATH (add to ~/.bashrc or ~/.zshrc)
export PATH="$HOME/.claude/plugins/hugoduncan/mcp-tasks-cli/bin:$PATH"
```

### Command Reference

Use the skill file for complete CLI documentation:

```bash
/skill cli-usage
```

### Quick Examples

```bash
# List all tasks
mcp-tasks list

# Add a new task
mcp-tasks add --category simple "Fix authentication bug"

# Show task details
mcp-tasks show 123

# Complete a task
mcp-tasks complete 123

# Get JSON output for scripting
mcp-tasks list --format json
```

## Binary Maintenance

The included `mcp-tasks` binary is a pre-built Babashka uberscript generated from the mcp-tasks project source.

**For plugin maintainers:**

To update the binary when a new version is released:

1. **Build the uberscript:**
   ```bash
   cd /path/to/mcp-tasks
   bb build-uberscript
   ```

2. **Copy to plugin:**
   ```bash
   bb copy-uberscript-to-plugin
   ```

3. **Test the binary:**
   ```bash
   ./plugins/mcp-tasks-cli/bin/mcp-tasks --version
   ```

4. **Update plugin version:**
   - Update `version` in `.claude-plugin/plugin.json`
   - Commit and push changes

## Documentation & Support

- **mcp-tasks Repository:** https://github.com/hugoduncan/mcp-tasks
- **CLI Documentation:** Use `/skill cli-usage` after installing this plugin
- **MCP Server Documentation:** Install the [mcp-tasks-skill](https://github.com/hugoduncan/mcp-tasks/tree/main/plugins/mcp-tasks-skill) plugin
- **Issues & Feedback:** https://github.com/hugoduncan/mcp-tasks/issues

## License

This plugin inherits the license from the parent mcp-tasks project (EPL-2.0).

## Version

1.0.0
