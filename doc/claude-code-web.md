# Claude Code Web Session Installation

This guide covers automatic installation and setup of mcp-tasks in Claude Code web sessions.

## Overview

Claude Code web sessions provide a browser-based development environment where local binaries cannot be pre-installed. The `web-session-start` script automates installation during session initialization, ensuring mcp-tasks is available immediately.

## Environment Variables

Claude Code web sessions provide two key environment variables:

- **`CLAUDE_CODE_REMOTE`**: Set to `"true"` in web sessions, absent or different value in desktop sessions
- **`CLAUDE_ENV_FILE`**: Path to a file where environment variables should be written for persistence across sessions

The `web-session-start` script uses these variables to:
1. Detect whether it's running in a web session
2. Persist PATH configuration across session restarts

## Automatic Installation (SessionStart Hook)

The recommended setup uses a SessionStart hook to install mcp-tasks automatically when a web session begins.

### Setup Instructions

1. **Add SessionStart Hook to `.claude/settings.json`:**

```json
{
    "hooks": {
        "SessionStart": [
            {
                "hooks": [
                    {
                        "type": "command",
                        "command": "./scripts/web-session-start"
                    }
                ]
            }
        ]
    }
}
```

2. **Ensure the script is executable:**

```bash
chmod +x scripts/web-session-start
```

3. **Commit the hook configuration:**

```bash
git add .claude/settings.json
git commit -m "feat: add automatic installation for Claude Code web sessions"
```

### What Happens on Session Start

When a Claude Code web session starts:

1. The SessionStart hook executes `scripts/web-session-start`
2. The script checks `CLAUDE_CODE_REMOTE` environment variable
3. If in a web session (`CLAUDE_CODE_REMOTE="true"`):
   - Creates `~/.local/bin` directory if needed
   - Downloads platform-appropriate binaries (`mcp-tasks` and `mcp-tasks-server`)
   - Makes binaries executable
   - Appends `~/.local/bin` to PATH in `CLAUDE_ENV_FILE` (if not already present)
4. If not in a web session, exits silently (no-op)

**Note:** MCP server registration is currently disabled as Claude Code web does not yet support MCP servers.

### Idempotent Behavior

The script is safe to run multiple times:

- **Binaries**: Always updated to latest release (no version checking)
- **PATH**: Only appends `~/.local/bin` if not already present in `CLAUDE_ENV_FILE`

## Manual Installation

If you prefer manual installation or need to troubleshoot:

```bash
# 1. Set environment variables (web sessions set these automatically)
export CLAUDE_CODE_REMOTE=true
export CLAUDE_ENV_FILE=~/.env

# 2. Run the installation script
./scripts/web-session-start
```

## Supported Platforms

The web session installer supports the same platforms as binary installation:

- **Linux**: x86_64 (amd64)
- **macOS**: Universal binaries (x86_64 and ARM64)
- **Windows**: Not supported (native binaries unavailable due to file locking complexities)

Windows users in Claude Code web sessions should use the Clojure git dependency installation method instead (see [install.md](install.md#alternative-clojure-git-dependency-all-platforms)).

## Installation Flow

The `scripts/web-session-start` script performs these steps:

```bash
# 1. Check CLAUDE_CODE_REMOTE
if [ "$CLAUDE_CODE_REMOTE" != "true" ]; then
  exit 0  # Silent exit if not in web session
fi

# 2. Create installation directory
mkdir -p ~/.local/bin

# 3. Download and install binaries
# - Detects platform (linux-amd64 or macos-universal)
# - Downloads from GitHub releases (latest)
# - Makes binaries executable

# 4. Update PATH in CLAUDE_ENV_FILE
# - Checks if ~/.local/bin already in PATH
# - Appends if not present: export PATH="$PATH:~/.local/bin"
```

**Note:** MCP server registration (previously step 5) is currently disabled because Claude Code web does not yet support MCP servers.

## Troubleshooting

### Script Does Nothing

**Symptom:** The script runs but performs no installation.

**Cause:** `CLAUDE_CODE_REMOTE` is not set to `"true"`.

**Solution:**
- This is expected behavior outside of web sessions
- In web sessions, verify the environment variable: `echo $CLAUDE_CODE_REMOTE`

### Download Fails

**Symptom:** Error message: `"Failed to download ... from https://github.com/..."`

**Causes:**
1. Network connectivity issues
2. GitHub release not available for your platform
3. Missing `curl` or `wget`

**Solutions:**
1. Check network connectivity: `curl -I https://github.com`
2. Verify platform support: `uname -s` and `uname -m`
3. Install `curl` or `wget` if missing

### PATH Not Persisted

**Symptom:** Binaries installed but not available after session restart.

**Cause:** `CLAUDE_ENV_FILE` not set or PATH not written to file.

**Solution:**
1. Verify `CLAUDE_ENV_FILE` is set: `echo $CLAUDE_ENV_FILE`
2. Check file contents: `cat "$CLAUDE_ENV_FILE"`
3. Should contain: `export PATH="$PATH:~/.local/bin"`

### MCP Registration

**Note:** MCP server registration is currently disabled in the `web-session-start` script because Claude Code web does not yet support MCP servers. This functionality may be enabled in a future release when support becomes available.

### Platform Not Supported

**Symptom:** Error message: `"Unsupported operating system: ..."` or `"Unsupported platform: ..."`

**Cause:** Running on Windows or unsupported architecture.

**Solution:** Use Clojure git dependency installation instead (see [install.md](install.md#alternative-clojure-git-dependency-all-platforms)).

## Testing Locally

You can test the installation script locally by simulating a web session environment:

```bash
# Create test environment
TEST_HOME=/tmp/mcp-tasks-test
mkdir -p "$TEST_HOME"

# Simulate web session environment and run installer
HOME="$TEST_HOME" \
CLAUDE_CODE_REMOTE=true \
CLAUDE_ENV_FILE="$TEST_HOME/.env" \
./scripts/web-session-start

# Verify installation
echo ""
echo "=== Verification ==="
ls -lh "$TEST_HOME/.local/bin"
cat "$TEST_HOME/.env"

# Cleanup
rm -rf "$TEST_HOME"
```

### Testing Non-Web Session Behavior

Verify the script exits silently when not in a web session:

```bash
# Should exit 0 without output
CLAUDE_CODE_REMOTE=false ./scripts/web-session-start
echo "Exit code: $?"  # Should print: Exit code: 0
```

### Testing Idempotency

Verify the script can be run multiple times safely:

```bash
# Setup test environment
TEST_HOME=/tmp/mcp-tasks-test
mkdir -p "$TEST_HOME"

# Run installer twice
for i in 1 2; do
  echo "=== Run $i ==="
  HOME="$TEST_HOME" \
  CLAUDE_CODE_REMOTE=true \
  CLAUDE_ENV_FILE="$TEST_HOME/.env" \
  ./scripts/web-session-start
  echo ""
done

# Verify PATH appears only once
echo "=== PATH entries in $TEST_HOME/.env ==="
grep -c "\.local/bin" "$TEST_HOME/.env"  # Should print: 1

# Cleanup
rm -rf "$TEST_HOME"
```

## Error Handling

The script follows these error handling principles:

1. **All errors logged to stderr**: Error messages use `>&2` redirection
2. **Exit code 1 on failure**: Any error returns non-zero exit code
3. **No rollback on partial failure**: If installation partially completes, you can safely re-run the script
4. **Fail fast**: Script exits immediately on first error (via `set -e`)

## Security Considerations

- **Always downloads latest release**: No version pinning or checksum verification
- **No credentials stored**: Script uses public GitHub URLs
- **Limited scope**: Only writes to `~/.local/bin` and `CLAUDE_ENV_FILE`
- **No privilege escalation**: Runs as current user, no `sudo` required

## Related Documentation

- [Installation Guide](install.md) - General installation instructions for all platforms
- [Configuration Guide](config.md) - MCP server configuration options
- [GitHub Workflows](github-workflows.md) - Binary build and release process
