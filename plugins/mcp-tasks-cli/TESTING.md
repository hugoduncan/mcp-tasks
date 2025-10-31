# Plugin Testing Report

## Overview

This document provides testing procedures for the mcp-tasks-cli Claude Code plugin, including binary verification, installation testing, and skill documentation testing.

## Plugin Structure Verification

**Plugin Location**: `plugins/mcp-tasks-cli/`

**Required Files**:
- ✓ `.claude-plugin/plugin.json` - Plugin manifest
- ✓ `bin/mcp-tasks` - Pre-built Babashka uberscript
- ✓ `skills/cli-usage/SKILL.md` - CLI reference documentation
- ✓ `README.md` - Installation and usage instructions
- ✓ `LICENSE` - EPL-2.0 license

**Plugin Manifest** (`.claude-plugin/plugin.json`):
```json
{
  "name": "mcp-tasks-cli",
  "description": "Pre-built CLI tool for mcp-tasks task management",
  "version": "1.0.0",
  "author": {
    "name": "Hugo Duncan",
    "email": "hugo_duncan@yahoo.com"
  },
  "homepage": "https://github.com/hugoduncan/mcp-tasks",
  "repository": "https://github.com/hugoduncan/mcp-tasks",
  "license": "EPL-1.0",
  "keywords": ["cli", "task-management", "babashka", "productivity", "scripting"]
}
```

## Test 1: Binary Verification

### Prerequisites
- Babashka (bb) installed on the system
- mcp-tasks uberscript built via `bb build-uberscript`

### Build and Copy Binary

```bash
# From repository root
cd /path/to/mcp-tasks

# Build the uberscript
bb build-uberscript

# Copy to plugin directory
bb copy-uberscript-to-plugin
```

### Verification Steps

1. **Verify binary exists**:
   ```bash
   ls -lh plugins/mcp-tasks-cli/bin/mcp-tasks
   ```
   - Should show the uberscript file
   - Should be executable

2. **Test binary execution**:
   ```bash
   ./plugins/mcp-tasks-cli/bin/mcp-tasks --version
   ```
   - Should display version information
   - Should not produce errors

3. **Test CLI commands**:
   ```bash
   # List tasks
   ./plugins/mcp-tasks-cli/bin/mcp-tasks list

   # Show help
   ./plugins/mcp-tasks-cli/bin/mcp-tasks --help
   ```

### Expected Results
- Binary is executable
- Version command works
- Basic commands execute without errors

## Test 2: Local Installation

### Prerequisites
- Claude Code installed and running
- Binary verification completed (Test 1)

### Installation Command

From the repository root directory:

```bash
# Get absolute path to plugin
cd /path/to/mcp-tasks/plugins/mcp-tasks-cli
pwd  # Copy this path

# In Claude Code, run:
/plugin install /absolute/path/to/plugins/mcp-tasks-cli
```

### Verification Steps

1. **Verify installation**:
   ```bash
   /plugin list
   # Should show "mcp-tasks-cli" in the installed plugins list
   ```

2. **Verify skill availability**:
   - Run `/help` or `/skill` to see available skills
   - Look for "cli-usage" skill in the list

3. **Test skill invocation**:
   ```bash
   /skill cli-usage
   ```
   - Should display the CLI reference documentation
   - Content should match `skills/cli-usage/SKILL.md`

4. **Verify binary accessibility**:
   ```bash
   # Check if binary is in plugin directory
   ls ~/.claude/plugins/hugoduncan/mcp-tasks-cli/bin/mcp-tasks

   # Test execution
   ~/.claude/plugins/hugoduncan/mcp-tasks-cli/bin/mcp-tasks --version
   ```

### Expected Results
- Plugin installs without errors
- Skill appears in available skills list
- Binary is accessible in plugin directory
- Binary executes correctly

### Cleanup

```bash
/plugin uninstall mcp-tasks-cli
```

## Test 3: Skill Documentation

### Prerequisites
- Plugin installed (via Test 2)

### Verification Steps

1. **Access skill documentation**:
   ```bash
   /skill cli-usage
   ```

2. **Verify content coverage**:
   - All CLI commands documented (list, add, show, complete, update, delete)
   - Command flags and parameters explained
   - Output format options documented
   - Usage examples provided for each command
   - CLI vs MCP server guidance included

3. **Verify accuracy**:
   - Cross-reference skill documentation with actual CLI behavior
   - Test documented examples to ensure they work
   - Verify command syntax matches CLI help output

### Expected Results
- Skill documentation is complete and accurate
- All examples execute successfully
- Documentation matches CLI behavior

## Test 4: Integration Testing

### Prerequisites
- Plugin installed
- Binary added to PATH (via symlink or PATH modification)

### Test Scenarios

1. **Basic workflow**:
   ```bash
   # Add a task
   mcp-tasks add --category simple "Test task from CLI plugin"

   # List tasks
   mcp-tasks list

   # Complete the task
   mcp-tasks complete <task-id>
   ```

2. **JSON output for scripting**:
   ```bash
   # Get tasks as JSON
   mcp-tasks list --format json | jq '.tasks[] | .title'
   ```

3. **Error handling**:
   ```bash
   # Invalid task ID
   mcp-tasks show 99999

   # Missing required argument
   mcp-tasks add
   ```

### Expected Results
- All commands work as documented
- JSON output is valid and parseable
- Error messages are clear and helpful

## Test 5: Cross-Platform Testing

Test installation on different platforms:
- [ ] macOS
- [ ] Linux
- [ ] Windows (via WSL or native Babashka)

### Platform-Specific Notes

**macOS/Linux**:
- Standard Unix PATH configuration
- Standard symlink creation

**Windows**:
- May require WSL for Babashka
- PATH configuration differs
- Test both WSL and native Babashka if available

## Test 6: GitHub Installation

### Prerequisites
- Branch pushed to GitHub
- Repository: `hugoduncan/mcp-tasks`

### Installation Command

```bash
# From Claude Code
/plugin install hugoduncan/mcp-tasks-cli
```

### Verification Steps

Same as Test 2 (Local Installation):
1. Verify installation with `/plugin list`
2. Verify skill availability
3. Test skill invocation
4. Verify binary accessibility

### Known Limitations

- GitHub plugin installation may require root-level marketplace configuration
- If GitHub installation fails, document as known limitation

## Test Results Summary

### Structure Verification
- **Status**: Pending completion
- **Files Created**:
  - ✓ `.claude-plugin/plugin.json`
  - ✓ `README.md`
  - ✓ `LICENSE`
  - ✓ `TESTING.md`
  - ⏳ `bin/mcp-tasks` (next task)
  - ⏳ `skills/cli-usage/SKILL.md` (next task)

### Binary Verification
- **Status**: Pending next task
- **Expected**: Binary builds, executes, and shows version

### Local Installation Test
- **Status**: Pending binary creation
- **Expected**: Plugin installs and skill is accessible

### Skill Documentation Test
- **Status**: Pending skill file creation
- **Expected**: Documentation complete and accurate

### Integration Test
- **Status**: Pending binary and skill creation
- **Expected**: All CLI commands work as documented

### Cross-Platform Test
- **Status**: Pending manual testing
- **Platforms**: macOS, Linux, Windows (WSL)

## Configuration Requirements

### System Requirements

**Babashka**: Required to run the CLI
- Installation: https://babashka.org/#installation
- Version: Latest stable recommended

**Claude Code**: Required for plugin installation
- Installation: https://docs.claude.com/en/docs/claude-code

### Troubleshooting

**Issue**: Binary not executable
- **Solution**: `chmod +x plugins/mcp-tasks-cli/bin/mcp-tasks`

**Issue**: Babashka not found
- **Solution**: Install Babashka from https://babashka.org/#installation

**Issue**: Skill doesn't appear after installation
- **Solution**: Restart Claude Code to reload plugins

**Issue**: Binary not in PATH
- **Solution**: Create symlink or add plugin bin directory to PATH (see README.md)

## Recommendations

1. **Documentation**: Ensure README.md includes all installation methods
2. **Binary Updates**: Document process for updating the binary when new versions are released
3. **Version Management**: Keep plugin version in sync with mcp-tasks project version
4. **Testing Automation**: Consider adding automated tests for binary verification

## Next Steps

1. Complete remaining plugin files:
   - [ ] Add pre-built `mcp-tasks` binary to `bin/` directory
   - [ ] Create `skills/cli-usage/SKILL.md` with CLI reference
2. Execute manual testing following the procedures above:
   - [ ] Test binary verification (Test 1)
   - [ ] Test local installation (Test 2)
   - [ ] Test skill documentation (Test 3)
   - [ ] Test integration scenarios (Test 4)
3. Document actual results in this file:
   - Update "Status" fields in Test Results Summary
   - Document any issues or unexpected behaviors
4. Address any issues found during testing:
   - Fix plugin structure if needed
   - Update documentation based on findings
5. Consider marketplace configuration:
   - Update `.claude-plugin/marketplace.json` at repository root
   - List both `mcp-tasks-skill` and `mcp-tasks-cli` plugins
   - Test installation via marketplace approach
