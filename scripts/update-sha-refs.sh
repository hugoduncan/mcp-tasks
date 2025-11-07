#!/usr/bin/env bash
# Update git SHA references in documentation files
#
# Usage:
#   scripts/update-sha-refs.sh [SHA]
#
# Arguments:
#   SHA - The git commit SHA to use (optional, defaults to current HEAD)
#
# This script updates :git/sha references in documentation files to ensure
# they point to the correct commit. It's designed to be idempotent and safe
# to run multiple times.
#
# Files updated:
#   - doc/install.md
#   - plugins/mcp-tasks-skill/README.md
#
# Exit codes:
#   0 - Success (changes made or no changes needed)
#   1 - Error (invalid SHA, files not found, etc.)

set -euo pipefail

# Determine SHA to use
if [ $# -eq 0 ]; then
  # Default to current HEAD
  SHA=$(git rev-parse HEAD)
  echo "No SHA provided, using current HEAD: $SHA"
elif [ $# -eq 1 ]; then
  SHA="$1"
  echo "Using provided SHA: $SHA"
else
  echo "Error: Too many arguments" >&2
  echo "Usage: $0 [SHA]" >&2
  exit 1
fi

# Validate SHA format (40 hex characters)
if ! echo "$SHA" | grep -qE '^[0-9a-f]{40}$'; then
  echo "Error: Invalid SHA format: $SHA" >&2
  echo "Expected 40 hexadecimal characters" >&2
  exit 1
fi

# File paths to update
FILES=(
  "doc/install.md"
  "plugins/mcp-tasks-skill/README.md"
)

# Verify all files exist
MISSING=()
for file in "${FILES[@]}"; do
  if [ ! -f "$file" ]; then
    MISSING+=("$file")
  fi
done

if [ ${#MISSING[@]} -gt 0 ]; then
  echo "Error: Missing files: ${MISSING[*]}" >&2
  exit 1
fi

# Update SHA references
# Pattern matches :git/sha followed by a 40-character hex SHA
# This avoids matching shorter SHAs (like 7-char ones used for mcp-clj)
echo "Updating SHA references to $SHA..."

UPDATED=()
for file in "${FILES[@]}"; do
  # Create backup
  cp "$file" "$file.bak"
  
  # Perform replacement
  sed -i.tmp "s/:git\/sha \"[0-9a-f]\{40\}\"/:git\/sha \"$SHA\"/g" "$file"
  
  # Check if file changed
  if ! cmp -s "$file" "$file.bak"; then
    UPDATED+=("$file")
    echo "  ✓ Updated $file"
  else
    echo "  - No changes needed in $file"
  fi
  
  # Clean up
  rm "$file.bak" "$file.tmp"
done

# Summary
if [ ${#UPDATED[@]} -gt 0 ]; then
  echo ""
  echo "Updated ${#UPDATED[@]} file(s):"
  for file in "${UPDATED[@]}"; do
    echo "  - $file"
  done
  echo ""
  echo "Changes made. Review with: git diff ${FILES[*]}"
else
  echo ""
  echo "No updates needed - all files already reference SHA $SHA"
fi

exit 0
