---
description: Fix bugs through systematic debugging workflow
---

## Clarify

- clarify bug symptoms and expected behaviour
- confirm understanding with user (skip if task has `automated:true` in meta)

## Track Investigation

- create a temporary scratchpad file (e.g., `.scratch-<task-id>.md`)
  for tracking hypotheses and findings

## Analyze

- analyze the bug in project context
- locate relevant code and potential causes

## Hypothesis Loop

- form a root cause hypothesis
- confirm with user (skip if task has `automated:true` in meta)
- validate hypothesis with targeted investigation or minimal test
- if validation fails, return to hypothesis step

## Fix

- plan the fix
- implement the fix
- run tests to verify fix and avoid regressions

## Finalize

- commit the fix
- delete the scratchpad file
