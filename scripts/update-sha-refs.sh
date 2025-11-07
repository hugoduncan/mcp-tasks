#!/usr/bin/env bash
# Update git tag and SHA references in documentation files
#
# Usage:
#   scripts/update-sha-refs.sh [--tag TAG] [--sha SHA]
#
# Arguments:
#   --tag TAG - The git tag to use (e.g., "v0.1.114")
#   --sha SHA - The git commit SHA to use (accepts 7-40 char hex, defaults to current HEAD)
#
# If no arguments provided, uses current HEAD for SHA and no tag.
#
# This script updates :git/tag and :git/sha references in documentation files to ensure
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

# Initialize variables
TAG=""
SHA=""

# Parse arguments
while [[ $# -gt 0 ]]; do
  case $1 in
    --tag)
      TAG="$2"
      shift 2
      ;;
    --sha)
      SHA="$2"
      shift 2
      ;;
    *)
      echo "Error: Unknown argument: $1" >&2
      echo "Usage: $0 [--tag TAG] [--sha SHA]" >&2
      exit 1
      ;;
  esac
done

# Determine SHA to use
if [ -z "$SHA" ]; then
  # Default to current HEAD (short form)
  SHA=$(git rev-parse --short=7 HEAD)
  echo "No SHA provided, using current HEAD: $SHA"
else
  echo "Using provided SHA: $SHA"
fi

# Validate SHA format (7-40 hex characters)
if ! echo "$SHA" | grep -qE '^[0-9a-f]{7,40}$'; then
  echo "Error: Invalid SHA format: $SHA" >&2
  echo "Expected 7-40 hexadecimal characters" >&2
  exit 1
fi

# Display tag if provided
if [ -n "$TAG" ]; then
  echo "Using tag: $TAG"
else
  echo "No tag provided - tag field will not be updated"
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

# Update references
echo "Updating documentation references..."

UPDATED=()
for file in "${FILES[@]}"; do
  # Create backup
  cp "$file" "$file.bak"

  # Update SHA first (matches 7-40 character hex SHAs)
  sed -i.tmp "s/:git\/sha \"[0-9a-f]\{7,40\}\"/:git\/sha \"$SHA\"/g" "$file"

  # Update or add tag if provided
  if [ -n "$TAG" ]; then
    # Check if :git/tag already exists in the file
    if grep -q ':git/tag' "$file"; then
      # Update existing :git/tag
      sed -i.tmp "s/:git\/tag \"[^\"]*\"/:git\/tag \"$TAG\"/g" "$file"
    else
      # Add :git/tag before :git/sha line using perl for better newline handling
      perl -i.tmp -pe "s/( *):git\/sha/\$1:git\/tag \"$TAG\"\n\$1:git\/sha/g" "$file"
    fi
  fi

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
  if [ -n "$TAG" ]; then
    echo "Changes made. Review with: git diff ${FILES[*]}"
    echo "Tag: $TAG, SHA: $SHA"
  else
    echo "Changes made. Review with: git diff ${FILES[*]}"
    echo "SHA: $SHA"
  fi
else
  echo ""
  if [ -n "$TAG" ]; then
    echo "No updates needed - all files already reference tag $TAG and SHA $SHA"
  else
    echo "No updates needed - all files already reference SHA $SHA"
  fi
fi

exit 0
