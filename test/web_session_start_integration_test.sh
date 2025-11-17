#!/usr/bin/env bash

# Integration tests for web-session-start script
# Tests actual downloads from GitHub releases
#
# Usage:
#   RUN_INTEGRATION_TESTS=true ./test/web_session_start_integration_test.sh
#
# Note: This test downloads actual binaries from GitHub and should not run
# in CI by default to avoid rate limiting and unnecessary network usage.

# Disable shellcheck warnings for functions defined after early exit check
# These functions are invoked conditionally and shellcheck incorrectly flags them
# shellcheck disable=SC2317,SC2329

set -e

# Check if integration tests are enabled
if [ "$RUN_INTEGRATION_TESTS" != "true" ]; then
  echo "Skipping integration tests (set RUN_INTEGRATION_TESTS=true to enable)"
  exit 0
fi

# Colors for test output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
NC='\033[0m' # No Color

# Test counters
TESTS_RUN=0
TESTS_PASSED=0
TESTS_FAILED=0

# Configuration (must match scripts/web-session-start)
GITHUB_REPO="hugoduncan/mcp-tasks"
BASE_URL="https://github.com/${GITHUB_REPO}/releases/latest/download"

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

assert_success() {
  local exit_code="$1"
  local test_name="$2"

  TESTS_RUN=$((TESTS_RUN + 1))

  if [ "$exit_code" -eq 0 ]; then
    echo -e "${GREEN}✓${NC} $test_name"
    TESTS_PASSED=$((TESTS_PASSED + 1))
  else
    echo -e "${RED}✗${NC} $test_name"
    echo "  Command failed with exit code: $exit_code"
    TESTS_FAILED=$((TESTS_FAILED + 1))
  fi
}

# Detect platform (must match scripts/web-session-start logic)
detect_platform() {
  local os
  local arch

  case "$(uname -s)" in
    Linux*)
      os="linux"
      ;;
    Darwin*)
      os="macos"
      ;;
    *)
      echo "Error: Unsupported OS: $(uname -s)" >&2
      return 1
      ;;
  esac

  case "$os:$(uname -m)" in
    linux:x86_64)
      arch="amd64"
      ;;
    macos:x86_64|macos:arm64|macos:aarch64)
      arch="universal"
      ;;
    *)
      echo "Error: Unsupported platform: ${os} $(uname -m)" >&2
      return 1
      ;;
  esac

  echo "${os}-${arch}"
}

# Check if command exists
has() {
  command -v "$1" >/dev/null 2>&1
}

# Download file using curl or wget
download() {
  local url="$1"
  local output="$2"

  if has curl; then
    curl -fsSL -o "$output" "$url"
  elif has wget; then
    wget -q --server-response --content-on-error=off -O "$output" "$url" 2>&1 | grep -q "HTTP/.* 200" || return 1
  else
    echo "Error: Neither curl nor wget found" >&2
    return 1
  fi
}

# Setup test environment
setup_test_env() {
  local test_dir
  test_dir=$(mktemp -d)
  CLEANUP_DIRS+=("$test_dir")
  echo "$test_dir"
}

# Cleanup test environment
cleanup_test_env() {
  for dir in "${CLEANUP_DIRS[@]}"; do
    if [ -d "$dir" ]; then
      rm -rf "$dir"
    fi
  done
  CLEANUP_DIRS=()
}

# Test 1: Platform detection works
test_platform_detection() {
  log_info "Running test: Platform detection"

  local platform
  platform=$(detect_platform)
  local exit_code=$?

  assert_success "$exit_code" "Platform detection succeeds"

  # Verify platform is one of the expected values
  if [ "$platform" = "linux-amd64" ] || [ "$platform" = "macos-universal" ]; then
    TESTS_RUN=$((TESTS_RUN + 1))
    TESTS_PASSED=$((TESTS_PASSED + 1))
    echo -e "${GREEN}✓${NC} Platform is valid: $platform"
  else
    TESTS_RUN=$((TESTS_RUN + 1))
    TESTS_FAILED=$((TESTS_FAILED + 1))
    echo -e "${RED}✗${NC} Platform is valid"
    echo "  Expected: linux-amd64 or macos-universal"
    echo "  Actual: $platform"
  fi
}

# Test 2: Download CLI binary from GitHub
test_download_cli_binary() {
  log_info "Running test: Download CLI binary from GitHub releases"

  local test_dir
  test_dir=$(setup_test_env)

  local platform
  platform=$(detect_platform)

  local binary_name="mcp-tasks-${platform}"
  local download_url="${BASE_URL}/${binary_name}"
  local output_file="${test_dir}/mcp-tasks"

  log_info "Downloading from: $download_url"

  local exit_code=0
  download "$download_url" "$output_file" || exit_code=$?

  assert_success "$exit_code" "CLI binary download succeeds"
  assert_file_exists "$output_file" "CLI binary file exists"

  # Check file size (should be non-zero)
  if [ -f "$output_file" ]; then
    local file_size
    file_size=$(wc -c < "$output_file")
    if [ "$file_size" -gt 0 ]; then
      TESTS_RUN=$((TESTS_RUN + 1))
      TESTS_PASSED=$((TESTS_PASSED + 1))
      echo -e "${GREEN}✓${NC} CLI binary is non-empty (${file_size} bytes)"
    else
      TESTS_RUN=$((TESTS_RUN + 1))
      TESTS_FAILED=$((TESTS_FAILED + 1))
      echo -e "${RED}✗${NC} CLI binary is non-empty"
      echo "  File size: $file_size bytes"
    fi
  fi
}

# Test 3: Download server binary from GitHub
test_download_server_binary() {
  log_info "Running test: Download server binary from GitHub releases"

  local test_dir
  test_dir=$(setup_test_env)

  local platform
  platform=$(detect_platform)

  local binary_name="mcp-tasks-server-${platform}"
  local download_url="${BASE_URL}/${binary_name}"
  local output_file="${test_dir}/mcp-tasks-server"

  log_info "Downloading from: $download_url"

  local exit_code=0
  download "$download_url" "$output_file" || exit_code=$?

  assert_success "$exit_code" "Server binary download succeeds"
  assert_file_exists "$output_file" "Server binary file exists"

  # Check file size (should be non-zero)
  if [ -f "$output_file" ]; then
    local file_size
    file_size=$(wc -c < "$output_file")
    if [ "$file_size" -gt 0 ]; then
      TESTS_RUN=$((TESTS_RUN + 1))
      TESTS_PASSED=$((TESTS_PASSED + 1))
      echo -e "${GREEN}✓${NC} Server binary is non-empty (${file_size} bytes)"
    else
      TESTS_RUN=$((TESTS_RUN + 1))
      TESTS_FAILED=$((TESTS_FAILED + 1))
      echo -e "${RED}✗${NC} Server binary is non-empty"
      echo "  File size: $file_size bytes"
    fi
  fi
}

# Test 4: Downloaded binaries are executable
test_binaries_executable() {
  log_info "Running test: Downloaded binaries are executable"

  local test_dir
  test_dir=$(setup_test_env)

  local platform
  platform=$(detect_platform)

  # Download both binaries
  local cli_url="${BASE_URL}/mcp-tasks-${platform}"
  local server_url="${BASE_URL}/mcp-tasks-server-${platform}"
  local cli_file="${test_dir}/mcp-tasks"
  local server_file="${test_dir}/mcp-tasks-server"

  download "$cli_url" "$cli_file"
  download "$server_url" "$server_file"

  # Make executable
  chmod +x "$cli_file"
  chmod +x "$server_file"

  assert_file_executable "$cli_file" "CLI binary is executable after chmod"
  assert_file_executable "$server_file" "Server binary is executable after chmod"
}

# Test 5: Binaries have basic functionality
test_binaries_functional() {
  log_info "Running test: Downloaded binaries are functional"

  local test_dir
  test_dir=$(setup_test_env)

  local platform
  platform=$(detect_platform)

  # Download and prepare CLI binary
  local cli_url="${BASE_URL}/mcp-tasks-${platform}"
  local cli_file="${test_dir}/mcp-tasks"

  download "$cli_url" "$cli_file"
  chmod +x "$cli_file"

  # Test CLI with --help flag
  local exit_code=0
  local output
  output=$("$cli_file" --help 2>&1) || exit_code=$?

  # CLI should either succeed or return a reasonable exit code
  # Some binaries return non-zero for --help, so we check for output instead
  if [ -n "$output" ]; then
    TESTS_RUN=$((TESTS_RUN + 1))
    TESTS_PASSED=$((TESTS_PASSED + 1))
    echo -e "${GREEN}✓${NC} CLI binary produces output with --help"
  else
    TESTS_RUN=$((TESTS_RUN + 1))
    TESTS_FAILED=$((TESTS_FAILED + 1))
    echo -e "${RED}✗${NC} CLI binary produces output with --help"
    echo "  Exit code: $exit_code"
    echo "  Output: $output"
  fi

  # Note: MCP server binary is not tested with --help because MCP servers
  # are designed to run as stdio servers and expect MCP protocol messages,
  # not command-line flags. The executable test above is sufficient.
}

# Main test execution
main() {
  echo "Running web-session-start integration tests..."
  echo "Note: These tests download actual binaries from GitHub releases"
  echo ""

  # Run tests
  test_platform_detection
  test_download_cli_binary
  test_download_server_binary
  test_binaries_executable
  test_binaries_functional

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
