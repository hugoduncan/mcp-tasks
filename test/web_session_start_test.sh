#!/usr/bin/env bash

# Integration tests for the web-session-start script

set -e

# Colors for test output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
NC='\033[0m' # No Color

# Test counters
TESTS_RUN=0
TESTS_PASSED=0
TESTS_FAILED=0

# Cleanup tracking
CLEANUP_DIRS=()

# Test helper functions
log_info() {
  echo -e "${YELLOW}ℹ${NC} $*" >&2
}

assert_equals() {
  local expected="$1"
  local actual="$2"
  local test_name="$3"

  TESTS_RUN=$((TESTS_RUN + 1))

  if [ "$expected" = "$actual" ]; then
    echo -e "${GREEN}✓${NC} $test_name"
    TESTS_PASSED=$((TESTS_PASSED + 1))
  else
    echo -e "${RED}✗${NC} $test_name"
    echo "  Expected: $expected"
    echo "  Actual:   $actual"
    TESTS_FAILED=$((TESTS_FAILED + 1))
  fi
}

assert_file_exists() {
  local file_path="$1"
  local test_name="$2"

  TESTS_RUN=$((TESTS_RUN + 1))

  if [ -f "$file_path" ]; then
    echo -e "${GREEN}✓${NC} $test_name"
    TESTS_PASSED=$((TESTS_PASSED + 1))
  else
    echo -e "${RED}✗${NC} $test_name"
    echo "  File does not exist: $file_path"
    TESTS_FAILED=$((TESTS_FAILED + 1))
  fi
}

assert_file_executable() {
  local file_path="$1"
  local test_name="$2"

  TESTS_RUN=$((TESTS_RUN + 1))

  if [ -x "$file_path" ]; then
    echo -e "${GREEN}✓${NC} $test_name"
    TESTS_PASSED=$((TESTS_PASSED + 1))
  else
    echo -e "${RED}✗${NC} $test_name"
    echo "  File is not executable: $file_path"
    TESTS_FAILED=$((TESTS_FAILED + 1))
  fi
}

assert_file_contains() {
  local file_path="$1"
  local pattern="$2"
  local test_name="$3"

  TESTS_RUN=$((TESTS_RUN + 1))

  if [ -f "$file_path" ] && grep -q "$pattern" "$file_path"; then
    echo -e "${GREEN}✓${NC} $test_name"
    TESTS_PASSED=$((TESTS_PASSED + 1))
  else
    echo -e "${RED}✗${NC} $test_name"
    echo "  Pattern not found in file: $pattern"
    echo "  File: $file_path"
    if [ -f "$file_path" ]; then
      echo "  File contents:"
      cat "$file_path" | sed 's/^/    /'
    else
      echo "  File does not exist"
    fi
    TESTS_FAILED=$((TESTS_FAILED + 1))
  fi
}

assert_exit_code() {
  local expected_code="$1"
  local actual_code="$2"
  local test_name="$3"

  TESTS_RUN=$((TESTS_RUN + 1))

  if [ "$expected_code" -eq "$actual_code" ]; then
    echo -e "${GREEN}✓${NC} $test_name"
    TESTS_PASSED=$((TESTS_PASSED + 1))
  else
    echo -e "${RED}✗${NC} $test_name"
    echo "  Expected exit code: $expected_code"
    echo "  Actual exit code: $actual_code"
    TESTS_FAILED=$((TESTS_FAILED + 1))
  fi
}

# Setup test environment
setup_test_env() {
  local test_dir
  test_dir=$(mktemp -d)
  CLEANUP_DIRS+=("$test_dir")

  # Determine platform suffix for binaries
  local platform_suffix
  if [ "$(uname -s)" = "Darwin" ]; then
    platform_suffix="macos-universal"
  else
    platform_suffix="linux-amd64"
  fi

  # Create mock binaries directory (simulating GitHub releases)
  local mock_bin_dir="$test_dir/mock-binaries"
  mkdir -p "$mock_bin_dir"

  # Create fake binaries with platform-specific names
  cat > "$mock_bin_dir/mcp-tasks-${platform_suffix}" << 'EOF'
#!/usr/bin/env bash
echo "mcp-tasks CLI"
EOF
  chmod +x "$mock_bin_dir/mcp-tasks-${platform_suffix}"

  cat > "$mock_bin_dir/mcp-tasks-server-${platform_suffix}" << 'EOF'
#!/usr/bin/env bash
echo "mcp-tasks Server"
EOF
  chmod +x "$mock_bin_dir/mcp-tasks-server-${platform_suffix}"

  # Create mock HOME
  local mock_home="$test_dir/home"
  mkdir -p "$mock_home"

  # Create mock CLAUDE_ENV_FILE
  local mock_env_file="$mock_home/.claude-env"
  touch "$mock_env_file"

  # Create mock PATH directory with curl, wget, and claude
  local mock_path_dir="$test_dir/mock-path"
  mkdir -p "$mock_path_dir"

  # Mock curl that copies from local directory
  cat > "$mock_path_dir/curl" << EOF
#!/usr/bin/env bash
# Mock curl for testing
if [ "\$1" = "-fsSL" ] && [ "\$2" = "-o" ]; then
  output_file="\$3"
  url="\$4"
  filename=\$(basename "\$url")
  if [ -f "$mock_bin_dir/\$filename" ]; then
    cp "$mock_bin_dir/\$filename" "\$output_file"
    exit 0
  else
    echo "Error: File not found: \$filename" >&2
    exit 1
  fi
fi
# Default: fail
exit 1
EOF
  chmod +x "$mock_path_dir/curl"

  # Mock wget that copies from local directory
  cat > "$mock_path_dir/wget" << EOF
#!/usr/bin/env bash
# Mock wget for testing
if [ "\$1" = "-q" ] && [ "\$2" = "--server-response" ] && [ "\$3" = "--content-on-error=off" ] && [ "\$4" = "-O" ]; then
  output_file="\$5"
  url="\$6"
  filename=\$(basename "\$url")
  if [ -f "$mock_bin_dir/\$filename" ]; then
    cp "$mock_bin_dir/\$filename" "\$output_file"
    echo "HTTP/1.1 200 OK" >&2
    exit 0
  else
    exit 1
  fi
fi
# Default: fail
exit 1
EOF
  chmod +x "$mock_path_dir/wget"

  # Mock claude CLI
  cat > "$mock_path_dir/claude" << 'EOF'
#!/usr/bin/env bash
# Mock claude CLI for testing
if [ "$1" = "mcp" ] && [ "$2" = "add" ]; then
  # Simulate successful MCP registration
  echo "MCP server registered: $3"
  exit 0
fi
echo "Mock claude CLI"
exit 0
EOF
  chmod +x "$mock_path_dir/claude"

  # Save original environment
  ORIGINAL_HOME="$HOME"
  ORIGINAL_PATH="$PATH"
  ORIGINAL_CLAUDE_ENV_FILE="${CLAUDE_ENV_FILE:-}"

  # Export test environment
  export HOME="$mock_home"
  export CLAUDE_ENV_FILE="$mock_env_file"
  export PATH="$mock_path_dir:$PATH"

  echo "$test_dir"
}

# Restore original environment
restore_env() {
  export HOME="$ORIGINAL_HOME"
  export PATH="$ORIGINAL_PATH"
  if [ -n "$ORIGINAL_CLAUDE_ENV_FILE" ]; then
    export CLAUDE_ENV_FILE="$ORIGINAL_CLAUDE_ENV_FILE"
  else
    unset CLAUDE_ENV_FILE
  fi
}

# Cleanup test environment
cleanup_test_env() {
  restore_env
  for dir in "${CLEANUP_DIRS[@]}"; do
    if [ -d "$dir" ]; then
      rm -rf "$dir"
    fi
  done
  CLEANUP_DIRS=()
}

# Test 1: Non-web session mode (CLAUDE_CODE_REMOTE != "true")
test_non_web_session_exit() {
  log_info "Running test: Non-web session mode exits cleanly"

  setup_test_env >/dev/null

  local exit_code=0
  CLAUDE_CODE_REMOTE="false" scripts/web-session-start >/dev/null 2>&1 || exit_code=$?

  assert_exit_code 0 "$exit_code" "Non-web session mode exits with code 0"

  cleanup_test_env
}

# Test 2: Web session mode - binaries downloaded and executable
test_web_session_binaries_installed() {
  log_info "Running test: Web session mode installs binaries"

  setup_test_env >/dev/null

  local exit_code=0
  CLAUDE_CODE_REMOTE="true" scripts/web-session-start >/dev/null 2>&1 || exit_code=$?

  assert_exit_code 0 "$exit_code" "Web session installation succeeds"
  assert_file_exists "$HOME/.local/bin/mcp-tasks" "CLI binary created"
  assert_file_exists "$HOME/.local/bin/mcp-tasks-server" "Server binary created"
  assert_file_executable "$HOME/.local/bin/mcp-tasks" "CLI binary is executable"
  assert_file_executable "$HOME/.local/bin/mcp-tasks-server" "Server binary is executable"

  cleanup_test_env
}

# Test 3: PATH is added to CLAUDE_ENV_FILE
test_path_added_to_env_file() {
  log_info "Running test: PATH is added to CLAUDE_ENV_FILE"

  setup_test_env >/dev/null

  CLAUDE_CODE_REMOTE="true" scripts/web-session-start >/dev/null 2>&1

  assert_file_contains "$CLAUDE_ENV_FILE" '/.local/bin' "CLAUDE_ENV_FILE contains .local/bin"
  assert_file_contains "$CLAUDE_ENV_FILE" 'export PATH=' "CLAUDE_ENV_FILE has PATH export"

  cleanup_test_env
}

# Test 4: Idempotency - running twice doesn't fail
test_idempotency() {
  log_info "Running test: Script is idempotent (safe to run multiple times)"

  setup_test_env >/dev/null

  # First run
  local exit_code1=0
  CLAUDE_CODE_REMOTE="true" scripts/web-session-start >/dev/null 2>&1 || exit_code1=$?
  assert_exit_code 0 "$exit_code1" "First run succeeds"

  # Second run (should also succeed)
  local exit_code2=0
  CLAUDE_CODE_REMOTE="true" scripts/web-session-start >/dev/null 2>&1 || exit_code2=$?
  assert_exit_code 0 "$exit_code2" "Second run succeeds (idempotent)"

  cleanup_test_env
}

# Test 5: PATH not duplicated when already present
test_path_not_duplicated() {
  log_info "Running test: PATH not duplicated when already present"

  setup_test_env >/dev/null

  # Add PATH manually first
  # shellcheck disable=SC2016
  echo 'export PATH="$PATH:~/.local/bin"' >> "$CLAUDE_ENV_FILE"

  # Run script
  CLAUDE_CODE_REMOTE="true" scripts/web-session-start >/dev/null 2>&1

  # Count occurrences of .local/bin in env file
  local count
  count=$(grep -c '/.local/bin' "$CLAUDE_ENV_FILE" || echo 0)

  assert_equals "1" "$count" "PATH appears exactly once in CLAUDE_ENV_FILE"

  cleanup_test_env
}

# Test 6: Script fails gracefully when CLAUDE_ENV_FILE not set
test_missing_env_file_variable() {
  log_info "Running test: Script handles missing CLAUDE_ENV_FILE gracefully"

  setup_test_env >/dev/null
  unset CLAUDE_ENV_FILE

  local exit_code=0
  CLAUDE_CODE_REMOTE="true" scripts/web-session-start >/dev/null 2>&1 || exit_code=$?

  # Should fail because CLAUDE_ENV_FILE is required
  if [ "$exit_code" -ne 0 ]; then
    TESTS_RUN=$((TESTS_RUN + 1))
    TESTS_PASSED=$((TESTS_PASSED + 1))
    echo -e "${GREEN}✓${NC} Script fails when CLAUDE_ENV_FILE not set"
  else
    TESTS_RUN=$((TESTS_RUN + 1))
    TESTS_FAILED=$((TESTS_FAILED + 1))
    echo -e "${RED}✗${NC} Script fails when CLAUDE_ENV_FILE not set"
    echo "  Expected non-zero exit code, got: $exit_code"
  fi

  cleanup_test_env
}

# Main test execution
main() {
  echo "Running web-session-start script tests..."
  echo ""

  # Run tests
  test_non_web_session_exit
  test_web_session_binaries_installed
  test_path_added_to_env_file
  test_idempotency
  test_path_not_duplicated
  test_missing_env_file_variable

  # Final cleanup
  cleanup_test_env

  # Summary
  echo ""
  echo "Test Summary:"
  echo "  Total:  $TESTS_RUN"
  echo -e "  ${GREEN}Passed: $TESTS_PASSED${NC}"
  if [ $TESTS_FAILED -gt 0 ]; then
    echo -e "  ${RED}Failed: $TESTS_FAILED${NC}"
    exit 1
  else
    echo "  Failed: $TESTS_FAILED"
    exit 0
  fi
}

# Trap cleanup on exit
trap cleanup_test_env EXIT

main
