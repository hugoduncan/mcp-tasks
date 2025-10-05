# Release Process

This document describes the release process for mcp-tasks, including version calculation, changelog generation, and artifact publishing.

## Overview

mcp-tasks uses an automated versioning strategy based on commit count and conventional commits for changelog generation. Releases are currently manual but designed to be automatable via GitHub Actions.

## Version Numbering

**Format:** `0.1.<commit-count>`

**Calculation:**
- Version is calculated automatically using `b/git-count-revs` in `dev/build.clj`
- The commit count is deterministic for any given commit SHA
- Example: If the repository has 42 commits, the version is `0.1.42`

**Why commit-based versioning:**
- Deterministic: Same commit always produces same version
- Monotonically increasing: Each new commit increments the version
- No manual version file to maintain
- Works well with conventional commits for semantic meaning

## Git Tags (Critical for Changelog)

**⚠️ IMPORTANT:** Git tags are required for git-cliff to segment the changelog by version. Without tags, all commits appear as "unreleased".

**Tag Format:**
- Use format: `v<version>` (e.g., `v0.1.42`)
- Tags mark release boundaries for changelog generation
- Must match the version calculated by `dev/build.clj`

**When to Create Tags:**
1. After generating/updating changelog
2. Before building the JAR
3. Before creating GitHub Release

**How to Create Tags:**
```bash
# Calculate current version (inspect build script output)
clj -T:build version

# Create annotated tag with that version
git tag -a v0.1.42 -m "Release v0.1.42"

# Push tag to remote
git push origin v0.1.42
```

**Initial Baseline Tag:**
The repository has an initial baseline tag `v0.1.0` on the first commit (7bf270e). This establishes the baseline for all subsequent changelog generation.

## Changelog Generation

**Tool:** [git-cliff](https://github.com/orhun/git-cliff)

**Configuration:** See `cliff.toml` in repository root

### Local Usage

```bash
# Preview unreleased changes since last tag
git cliff --unreleased

# Generate full changelog
git cliff

# Update CHANGELOG.md file
git cliff -o CHANGELOG.md

# Generate changelog for specific version range
git cliff v0.1.0..HEAD
```

### Conventional Commit Types

git-cliff parses conventional commit messages and groups them by type:

- `feat:` → Features
- `fix:` → Bug Fixes
- `perf:` → Performance
- `refactor:` → Refactoring
- `docs:` → Documentation
- `test:` → Testing
- `build:` → Build System
- `ci:` → CI/CD
- `chore:` → Chores
- `style:` → Code Style

### Excluded Commits

The following commits are automatically filtered from the changelog:

- Commits with scope `(release)` or `(changelog)`
- Commits containing `[skip changelog]` or `[changelog skip]`
- Merge commits from `local-integration-branch`

## Release Workflow

### Manual Release Steps

1. **Ensure all tests pass**
   ```bash
   clj-kondo --lint src test
   clojure -X:kaocha
   ```

2. **Calculate next version**
   ```bash
   clj -T:build version
   # Note the output, e.g., "0.1.12"
   ```

3. **Generate changelog for unreleased commits**
   ```bash
   # Preview what will be added
   git cliff --unreleased

   # Update CHANGELOG.md
   git cliff -o CHANGELOG.md
   ```

4. **Review and commit changelog**
   ```bash
   git add CHANGELOG.md
   git commit -m "docs(changelog): update for v0.1.12"
   ```

5. **Create git tag**
   ```bash
   # Use the version from step 2
   git tag -a v0.1.12 -m "Release v0.1.12"
   ```

6. **Build JAR**
   ```bash
   clj -T:build jar
   # Creates target/mcp-tasks-0.1.12.jar
   ```

7. **Test the JAR**
   ```bash
   # Basic smoke test
   clojure -Sdeps '{:deps {org.hugpduncan/mcp-tasks {:local/root "."}}}' \
     -X mcp-tasks.main/start
   ```

8. **Create GitHub Release**
   ```bash
   # Push tag first
   git push origin v0.1.12

   # Create release using gh CLI
   gh release create v0.1.12 \
     target/mcp-tasks-0.1.12.jar \
     --title "v0.1.12" \
     --notes "$(git cliff --unreleased --strip all)"
   ```

9. **Deploy to Clojars** (future)
   ```bash
   # Not yet implemented
   clj -T:build deploy
   ```

### Automated Release (Future)

The release process will be automated via GitHub Actions workflow:

1. Trigger via tag push or manual workflow dispatch
2. Calculate version from commit count
3. Generate changelog with git-cliff
4. Commit updated CHANGELOG.md
5. Create git tag with calculated version
6. Build JAR
7. Run smoke tests
8. Deploy to Clojars
9. Create GitHub Release with changelog excerpt

See `.github/workflows/release.yml` once implemented.

## Version Numbering Strategy

**Current:** `0.1.x` series
- Major version 0 indicates pre-1.0 (API may change)
- Minor version 1 is arbitrary baseline
- Patch version is commit count

**Future Considerations:**
- When to bump to 1.0.0? When API is stable and battle-tested
- Could migrate to semantic versioning with breaking change detection
- Could use conventional commits to auto-calculate semantic versions
- For now, commit count provides simplicity and automation

## Changelog Review and Editing

While the changelog is auto-generated, you can manually edit it before releases:

1. Generate changelog: `git cliff -o CHANGELOG.md`
2. Review the output
3. Edit entries for clarity if needed (e.g., rewording, grouping)
4. Commit the edited version
5. Future re-generations will preserve manual edits between version markers

**Best Practice:** Keep commit messages clear and descriptive to minimize need for manual editing.

## Troubleshooting

**Issue:** Changelog shows all commits as "unreleased"
- **Cause:** No git tags exist for version boundaries
- **Fix:** Create baseline tag on an earlier commit, e.g., `git tag -a v0.1.0 <commit-sha> -m "Initial release"`

**Issue:** Version doesn't match expectation
- **Cause:** Version is based on total commit count
- **Fix:** This is expected behavior; version always reflects commit count

**Issue:** Commits missing from changelog
- **Cause:** Commits may be filtered by configuration or not using conventional format
- **Fix:** Check `cliff.toml` filters and ensure commits follow conventional commit format

**Issue:** git-cliff not installed
- **Fix:** Install via Homebrew (`brew install git-cliff`) or download binary from releases page

## Related Documentation

- [cliff.toml](../cliff.toml) - git-cliff configuration
- [CHANGELOG.md](../CHANGELOG.md) - Generated changelog
- [dev/build.clj](../dev/build.clj) - Build script with version calculation
- [Conventional Commits](https://www.conventionalcommits.org/) - Commit message specification
- [git-cliff documentation](https://git-cliff.org/docs/)
