# Plugin Testing Report

## Overview

This document provides testing procedures for the mcp-tasks-skill Claude Code plugin, including local installation testing and GitHub-based installation.

## Plugin Structure Verification

**Plugin Location**: `plugins/mcp-tasks-skill/`

**Required Files**:
- ✓ `.claude-plugin/plugin.json` - Plugin manifest
- ✓ `skills/story-and-tasks/SKILL.md` - Skill content
- ✓ `README.md` - Installation and usage instructions
- ✓ `LICENSE` - EPL-2.0 license

**Plugin Manifest** (`.claude-plugin/plugin.json`):
```json
{
  "name": "mcp-tasks-skill",
  "description": "Guidance for using the mcp-tasks MCP server for task and story management",
  "version": "1.0.0",
  "author": {
    "name": "Hugo Duncan"
  }
}
```

## Test 1: Local Installation via File Path

### Prerequisites
- Claude Code installed and running
- mcp-tasks MCP server installed and configured

### Installation Command

From the repository root directory:

```bash
# Get absolute path to plugin
cd /path/to/mcp-tasks/plugins/mcp-tasks-skill
pwd  # Copy this path

# In Claude Code, run:
/plugin install /absolute/path/to/plugins/mcp-tasks-skill
```

### Verification Steps

1. **Verify Installation**:
   ```bash
   /plugin list
   # Should show "mcp-tasks-skill" in the installed plugins list
   ```

2. **Verify Skill Availability**:
   - Run `/help` or `/skill` to see available skills
   - Look for "story-and-tasks" skill in the list

3. **Test Skill Invocation**:
   ```bash
   /skill story-and-tasks
   ```
   - Should display the skill content
   - Content should match `skills/story-and-tasks/SKILL.md`

4. **Test Skill Content Display**:
   - Verify all sections are displayed correctly:
     - Overview
     - MCP Tools
     - MCP Prompts
     - MCP Resources
     - Workflow examples

### Expected Results

- Plugin installs without errors
- Skill appears in available skills list
- Skill content is readable and properly formatted
- All markdown formatting is preserved

### Cleanup

```bash
/plugin uninstall mcp-tasks-skill
```

## Test 2: GitHub Installation

### Prerequisites
- Branch pushed to GitHub: `convert-story-and-tasks-skill-to-marketplace-plugin`
- GitHub repository: `hugoduncan/mcp-tasks`

### Installation Command

**Note**: This tests installation from a GitHub repository subdirectory. Claude Code's support for this may vary.

```bash
# Attempt 1: Direct subdirectory reference (may not be supported)
/plugin install hugoduncan/mcp-tasks/plugins/mcp-tasks-skill

# Attempt 2: If marketplace.json is added at root
/plugin marketplace add hugoduncan/mcp-tasks
/plugin install mcp-tasks-skill@hugoduncan/mcp-tasks
```

### Verification Steps

Same as Test 1:
1. Verify installation with `/plugin list`
2. Verify skill availability with `/help` or `/skill`
3. Test skill invocation with `/skill story-and-tasks`
4. Verify content display

### Known Limitations

Based on Claude Code documentation:
- GitHub plugin installation typically requires a `.claude-plugin/marketplace.json` file at the repository root
- Subdirectory-only plugins may not be supported without a root-level marketplace configuration
- If GitHub installation fails, this is a known limitation and not a plugin defect

### Potential Solutions if GitHub Install Fails

1. **Add Root Marketplace File**: Create `.claude-plugin/marketplace.json` at repository root referencing the subdirectory plugin
2. **Separate Repository**: Move plugin to its own dedicated repository
3. **Git Submodule**: Use git submodules to include the plugin as a separate repository

## Test 3: Skill Functionality Testing

### Prerequisites
- Plugin installed (via local or GitHub method)
- mcp-tasks MCP server running

### Test Cases

1. **Tool Reference Verification**:
   - Open skill with `/skill story-and-tasks`
   - Verify all documented MCP tools are listed:
     - `select-tasks`
     - `add-task`
     - `update-task`
     - `complete-task`
     - `delete-task`
     - `work-on`

2. **Prompt Reference Verification**:
   - Verify all documented prompts are listed:
     - Category prompts (simple, medium, large, clarify-task)
     - Story prompts (refine-story, create-story-tasks, execute-story-task, etc.)

3. **Workflow Examples**:
   - Follow one of the documented workflows (e.g., "Execute a Simple Task")
   - Verify the steps work as documented

## Test 4: Cross-Platform Testing

Test installation on different platforms:
- [ ] macOS
- [ ] Linux
- [ ] Windows

## Test Results Summary

### Local Installation Test
- **Status**: Pending manual testing
- **Expected Result**: ✓ Plugin installs and skill is accessible
- **Actual Result**: _To be filled in after testing_
- **Issues Found**: _To be documented_

### GitHub Installation Test
- **Status**: Pending manual testing
- **Expected Result**: May require root marketplace.json
- **Actual Result**: _To be filled in after testing_
- **Issues Found**: _To be documented_

### Skill Functionality Test
- **Status**: Pending manual testing
- **Expected Result**: ✓ All tools and prompts work as documented
- **Actual Result**: _To be filled in after testing_
- **Issues Found**: _To be documented_

## Configuration Requirements

### MCP Server Requirement

The plugin requires the mcp-tasks MCP server to be installed and configured. Users must:

1. Install mcp-tasks MCP server (see README.md for instructions)
2. Configure Claude Code to use the server
3. Restart Claude Code after configuration

### Troubleshooting

**Issue**: Skill doesn't appear after installation
- **Solution**: Restart Claude Code to reload plugins

**Issue**: MCP tools not working
- **Solution**: Verify mcp-tasks MCP server is installed and configured in Claude Code settings

**Issue**: GitHub installation fails
- **Solution**: Use local file path installation as alternative, or add root marketplace.json

## Recommendations

1. **Documentation**: Add troubleshooting section to README.md based on test findings
2. **GitHub Installation**: If subdirectory installation is not supported, document the limitation and provide alternative installation methods
3. **Version Management**: Keep plugin version in sync with mcp-tasks project version
4. **Testing Automation**: Consider adding automated tests for plugin structure validation

## Next Steps

1. Push branch to GitHub
2. Execute manual testing following the procedures above
3. Document actual results in this file
4. Address any issues found during testing
5. Update README.md based on test findings
6. Consider marketplace.json at root if GitHub installation fails
