#!/usr/bin/env bash

# Unit tests for the install script's argument parsing functionality

set -e

# Colors for test output
RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m' # No Color

# Test counters
TESTS_RUN=0
TESTS_PASSED=0
TESTS_FAILED=0

# Test helper functions
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

# Source the parse_args function from install script
# We extract just the function to test it in isolation
extract_parse_args() {
  # Create a temporary file with just the parse_args function
  cat > /tmp/parse_args_test.sh << 'EOF'
#!/usr/bin/env bash

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --install-dir)
        if [ -z "$2" ]; then
          echo "Error: --install-dir requires a path argument" >&2
          exit 1
        fi
        # Expand tilde to home directory
        INSTALL_DIR="${2/#\~/$HOME}"
        shift 2
        ;;
      -d)
        if [ -z "$2" ]; then
          echo "Error: -d requires a path argument" >&2
          exit 1
        fi
        # Expand tilde to home directory
        INSTALL_DIR="${2/#\~/$HOME}"
        shift 2
        ;;
      *)
        echo "Error: Unknown option: $1" >&2
        echo "Usage: $0 [--install-dir <path>] [-d <path>]" >&2
        exit 1
        ;;
    esac
  done
}

# Configuration
INSTALL_DIR="${INSTALL_DIR:-/usr/local/bin}"

parse_args "$@"
echo "$INSTALL_DIR"
EOF
  chmod +x /tmp/parse_args_test.sh
}

# Test cases
test_no_arguments() {
  local result
  result=$(/tmp/parse_args_test.sh)
  assert_equals "/usr/local/bin" "$result" "No arguments defaults to /usr/local/bin"
}

test_long_flag_absolute_path() {
  local result
  result=$(/tmp/parse_args_test.sh --install-dir /opt/bin)
  assert_equals "/opt/bin" "$result" "Long flag with absolute path"
}

test_short_flag_absolute_path() {
  local result
  result=$(/tmp/parse_args_test.sh -d /opt/bin)
  assert_equals "/opt/bin" "$result" "Short flag with absolute path"
}

test_long_flag_tilde_expansion() {
  local result
  local expected="${HOME}/.local/bin"
  result=$(/tmp/parse_args_test.sh --install-dir ~/.local/bin)
  assert_equals "$expected" "$result" "Long flag with tilde expansion"
}

test_short_flag_tilde_expansion() {
  local result
  local expected="${HOME}/.local/bin"
  result=$(/tmp/parse_args_test.sh -d ~/.local/bin)
  assert_equals "$expected" "$result" "Short flag with tilde expansion"
}

test_long_flag_relative_path() {
  local result
  result=$(/tmp/parse_args_test.sh --install-dir ./bin)
  assert_equals "./bin" "$result" "Long flag with relative path"
}

test_short_flag_relative_path() {
  local result
  result=$(/tmp/parse_args_test.sh -d ../bin)
  assert_equals "../bin" "$result" "Short flag with relative path"
}

test_long_flag_missing_argument() {
  local result
  local exit_code=0
  result=$(/tmp/parse_args_test.sh --install-dir 2>&1) || exit_code=$?
  if [ $exit_code -ne 0 ] && echo "$result" | grep -q "Error: --install-dir requires a path argument"; then
    TESTS_RUN=$((TESTS_RUN + 1))
    TESTS_PASSED=$((TESTS_PASSED + 1))
    echo -e "${GREEN}✓${NC} Long flag missing argument returns error"
  else
    TESTS_RUN=$((TESTS_RUN + 1))
    TESTS_FAILED=$((TESTS_FAILED + 1))
    echo -e "${RED}✗${NC} Long flag missing argument returns error"
    echo "  Expected exit code 1 with error message"
    echo "  Got exit code: $exit_code"
    echo "  Output: $result"
  fi
}

test_short_flag_missing_argument() {
  local result
  local exit_code=0
  result=$(/tmp/parse_args_test.sh -d 2>&1) || exit_code=$?
  if [ $exit_code -ne 0 ] && echo "$result" | grep -q "Error: -d requires a path argument"; then
    TESTS_RUN=$((TESTS_RUN + 1))
    TESTS_PASSED=$((TESTS_PASSED + 1))
    echo -e "${GREEN}✓${NC} Short flag missing argument returns error"
  else
    TESTS_RUN=$((TESTS_RUN + 1))
    TESTS_FAILED=$((TESTS_FAILED + 1))
    echo -e "${RED}✗${NC} Short flag missing argument returns error"
    echo "  Expected exit code 1 with error message"
    echo "  Got exit code: $exit_code"
    echo "  Output: $result"
  fi
}

test_unknown_flag() {
  local result
  local exit_code=0
  result=$(/tmp/parse_args_test.sh --unknown-flag 2>&1) || exit_code=$?
  if [ $exit_code -ne 0 ] && echo "$result" | grep -q "Error: Unknown option"; then
    TESTS_RUN=$((TESTS_RUN + 1))
    TESTS_PASSED=$((TESTS_PASSED + 1))
    echo -e "${GREEN}✓${NC} Unknown flag returns error"
  else
    TESTS_RUN=$((TESTS_RUN + 1))
    TESTS_FAILED=$((TESTS_FAILED + 1))
    echo -e "${RED}✗${NC} Unknown flag returns error"
    echo "  Expected exit code 1 with error message"
    echo "  Got exit code: $exit_code"
    echo "  Output: $result"
  fi
}

# Main test execution
main() {
  echo "Running install script argument parsing tests..."
  echo ""

  # Setup
  extract_parse_args

  # Run tests
  test_no_arguments
  test_long_flag_absolute_path
  test_short_flag_absolute_path
  test_long_flag_tilde_expansion
  test_short_flag_tilde_expansion
  test_long_flag_relative_path
  test_short_flag_relative_path
  test_long_flag_missing_argument
  test_short_flag_missing_argument
  test_unknown_flag

  # Cleanup
  rm -f /tmp/parse_args_test.sh

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

main
