# Changelog Setup

This document describes the changelog generation setup for mcp-tasks.

## Overview

The project uses [git-cliff](https://github.com/orhun/git-cliff) to automatically generate changelogs from conventional commit messages.

## Installation

**macOS:**
```bash
brew install git-cliff
```

**Other platforms:**
Download from [git-cliff releases](https://github.com/orhun/git-cliff/releases)

## Configuration

**File:** `cliff.toml` in repository root

**Key settings:**
- Parses conventional commit format
- Groups commits by type in changelog
- Filters excluded commits
- Links to GitHub issues/PRs
- Formats for semantic versioning tags

## Conventional Commit Types

Commits are grouped by prefix:

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

## Excluded Commits

Automatically filtered from changelog:

- Commits with scope `(release)` or `(changelog)`
- Commits containing `[skip changelog]` or `[changelog skip]`
- Merge commits from `local-integration-branch`

## Usage

**Preview unreleased changes:**
```bash
git-cliff --unreleased
```

**Generate full changelog:**
```bash
git-cliff
```

**Update CHANGELOG.md:**
```bash
git-cliff -o CHANGELOG.md
```

**Generate for version range:**
```bash
git-cliff v0.1.0..HEAD
```

## Git Tags and Versioning

**Critical:** git-cliff uses git tags to segment changelog by version.

**Tag format:** `v<version>` (e.g., `v0.1.42`)

**Baseline tag:** `v0.1.0` exists on initial commit (7bf270e)

**Version calculation:** Automatic via `b/git-count-revs` in `dev/build.clj`
- Format: `0.1.<commit-count>`
- Example: 42 commits = version `0.1.42`

Without tags, all commits appear as "unreleased".

## Workflow

1. Make commits using conventional format
2. Generate changelog: `git-cliff -o CHANGELOG.md`
3. Review and commit CHANGELOG.md
4. Create tag matching version: `git tag -a v0.1.X -m "Release v0.1.X"`
5. Next release will show changes since tag

## Troubleshooting

**All commits show as "unreleased":**
- No git tags exist for version boundaries
- Create baseline tag: `git tag -a v0.1.0 <commit-sha> -m "Initial release"`

**Commits missing from changelog:**
- Check commit follows conventional format
- Check `cliff.toml` filters
- Verify commit isn't excluded by scope or message pattern

**Template errors:**
- Validate `cliff.toml` syntax
- Check Tera template syntax in body section

## Related Files

- `cliff.toml` - Configuration
- `CHANGELOG.md` - Generated output
- `doc/dev/release.md` - Release process including changelog
- [Conventional Commits Spec](https://www.conventionalcommits.org/)
- [git-cliff Documentation](https://git-cliff.org/docs/)
