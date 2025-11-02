# Building from Source

This guide describes how to build native binaries for mcp-tasks from source code.

## Requirements

- GraalVM 21+ with native-image installed
- Set `GRAALVM_HOME` environment variable
- Clojure CLI tools

## Building Binaries

### CLI Binary

```bash
# Build CLI JAR first
clj -T:build jar-cli

# Build native binary for your platform
GRAALVM_HOME=/path/to/graalvm clj -T:build native-cli

# Binary created at: target/mcp-tasks-<platform>-<arch>
```

### Server Binary

```bash
# Build server JAR first
clj -T:build jar-server

# Build native binary for your platform
GRAALVM_HOME=/path/to/graalvm clj -T:build native-server

# Or use Babashka task (builds both JAR and native binary)
bb build-native-server

# Binary created at: target/mcp-tasks-server-<platform>-<arch>
```

## Distribution Comparison

| Feature | Babashka Uberscript | Native Binary |
|---------|-------------------|---------------|
| **Size** | 48 KB | 38.3 MB |
| **Startup Time** | ~66ms (help commands) | ~instant (<10ms typical) |
| **Runtime Dependency** | Requires Babashka installed | None - fully standalone |
| **Distribution** | Single portable script | Platform-specific executable |
| **Best For** | Systems with Babashka, scripting | Standalone deployment, no dependencies |

### Choosing a Distribution

**Use Native Binary when:**
- You need true standalone execution without any runtime
- Deploying to systems where installing runtimes is restricted
- Absolute fastest startup time is critical
- You can accept larger binary size for convenience

**Use Babashka Uberscript when:**
- You already have Babashka installed
- Minimizing disk space is important (48KB vs 35MB)
- You want a single cross-platform script
- You prefer the Babashka ecosystem

## Troubleshooting

### Build Issues

**Build failures with GraalVM:**
- Verify GraalVM 21+ is installed: `native-image --version`
- Ensure `GRAALVM_HOME` points to GraalVM root directory
- Check that native-image component is installed
- Build CLI JAR first before building native binary

**Malli validation warnings during build:**
- Expected behavior - Malli validation is disabled for native builds
- Warnings don't affect functionality

### Installation Issues

**Installation script fails with "Permission denied" (Unix):**
- The default installation location `/usr/local/bin` requires elevated privileges
- Solution: The script will automatically prompt you to use sudo when needed
- Alternative: Install to a user-writable directory (manual installation)

**Installation script fails with "curl/wget not found" (Unix):**
```bash
# Install curl on Debian/Ubuntu
sudo apt-get install curl

# Install curl on macOS
brew install curl

# Or install wget as alternative
sudo apt-get install wget  # Debian/Ubuntu
brew install wget          # macOS
```

**Installation script fails with network errors:**
- Check internet connection
- Verify GitHub is accessible: `curl -I https://github.com`
- Try manual download from [GitHub Releases](https://github.com/hugoduncan/mcp-tasks/releases)

**"Permission denied" errors after manual installation (Unix):**
```bash
chmod +x mcp-tasks
```

**macOS "unidentified developer" warning:**
```bash
# First run: Right-click → Open, then click "Open"
# Or remove quarantine attribute:
xattr -d com.apple.quarantine mcp-tasks
```

**Windows SmartScreen warning:**
- Click "More info" → "Run anyway"
- Binaries are not code-signed (open source project)

**"Library not found" errors:**
- Native binaries are statically linked and should work standalone
- Ensure you downloaded the correct platform binary
- Try re-downloading if file was corrupted

### Verification

**Post-installation verification:**
```bash
# Verify CLI installation
mcp-tasks --help

# Verify server installation
mcp-tasks-server --help
```

## MCP Client Configuration

Configure the server binary in your MCP client:

### Claude Code

```bash
# Using system-wide installation
claude mcp add mcp-tasks -- /usr/local/bin/mcp-tasks-server

# Or using local binary
claude mcp add mcp-tasks -- /path/to/mcp-tasks-server
```

### Claude Desktop

Edit your Claude Desktop configuration file:

- **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Windows**: `%APPDATA%\Claude\claude_desktop_config.json`
- **Linux**: `~/.config/Claude/claude_desktop_config.json`

Add the server configuration:

```json
{
  "mcpServers": {
    "mcp-tasks": {
      "command": "/usr/local/bin/mcp-tasks-server"
    }
  }
}
```

### Other MCP Clients

For other MCP-compatible clients, configure the server binary path according to your client's configuration format. The server uses stdio transport and requires no additional arguments.
