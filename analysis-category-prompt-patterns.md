# Category Prompt Handling Pattern Analysis

## Comparison: execute-task.md vs execute-story-child.md

### 1. Category Retrieval Patterns

**execute-task.md (Step 3):**
```
### 3. Retrieve Category Instructions

Use `ReadMcpResourceTool` with server "mcp-tasks", uri `prompt://category-<category>`. If missing, inform user and stop.
```

**execute-story-child.md (Step 3):**
```
**3. Execute task:**

Skip refinement check. Execute by following `prompt://category-<category>` resource.
```

**Key Differences:**
- execute-task.md has **explicit retrieval step** as separate section
- execute-task.md includes **error handling** ("If missing, inform user and stop")
- execute-story-child.md **combines** retrieval with execution in single step
- execute-story-child.md **lacks error handling** for missing category prompts

### 2. Error Handling Approaches

**execute-task.md:**
- Explicit: "If missing, inform user and stop"
- Appears in dedicated retrieval step
- Clear failure path

**execute-story-child.md:**
- No error handling for missing category prompts
- Only has error handling for missing category field: "**If task lacks category:** Inform user; stop"

**Gap:** execute-story-child.md needs error handling for missing category prompt files

### 3. Adherence Language Strength

**execute-task.md (Step 6):**
```
### 6. Execute

Follow category instructions with task context (ID, title, description, design, type, relations). Execute workflow steps in order.
```

**execute-story-child.md (Step 3):**
```
Execute by following `prompt://category-<category>` resource.
```

**Key Differences:**
- execute-task.md uses **imperative "Follow"** with explicit context listing
- execute-task.md emphasizes **"Execute workflow steps in order"**
- execute-story-child.md uses weaker **"Execute by following"** phrasing
- execute-story-child.md lacks explicit mention of workflow step ordering

**Gap:** execute-story-child.md needs stronger adherence language

### 4. Structural Elements

**execute-task.md structure:**
1. Check Refinement
2. Validate Dependencies
3. **Retrieve Category Instructions** (dedicated step)
4. Prepare Environment
5. Discovering Issues
6. **Execute** (dedicated step with clear instructions)
7. Complete

**execute-story-child.md structure:**
1. Find first unblocked incomplete child
2. Set up environment (includes shared context display)
3. **Execute task** (combines retrieval + execution + shared context updates)
4. Finalize shared context
5. Complete

**Key Differences:**
- execute-task.md separates retrieval (step 3) from execution (step 6)
- execute-story-child.md combines retrieval and execution in single step
- execute-story-child.md has additional shared context responsibilities

### 5. Specific Phrases to Adopt

From execute-task.md:

1. **Category retrieval:**
   - "Use `ReadMcpResourceTool` with server \"mcp-tasks\", uri `prompt://category-<category>`"
   - "If missing, inform user and stop"

2. **Execution guidance:**
   - "Follow category instructions"
   - "Execute workflow steps in order"
   - Explicit context listing: "with task context (ID, title, description, design, type, relations)"

3. **Issue discovery:**
   - Clear section "Discovering Issues" with guidance on what to capture vs. not capture
   - execute-story-child.md references this but lacks the detail

## Recommended Changes for execute-story-child.md

### Change 1: Insert explicit category retrieval step

**Current step 3:**
```
**3. Execute task:**

Skip refinement check. Execute by following `prompt://category-<category>` resource.
```

**Proposed new structure:**

```
**3. Retrieve category instructions:**

Use `ReadMcpResourceTool` with server "mcp-tasks", uri `prompt://category-<category>`. If missing, inform user and stop.

**4. Execute task:**

Follow category instructions with task context (ID, title, description, design). Execute workflow steps in their defined order.

Skip refinement check (already performed during task creation).
```

### Change 2: Strengthen execution language

Replace:
- "Execute by following" → "Follow category instructions"
- Add: "Execute workflow steps in their defined order"
- Add explicit context mention

### Change 3: Add guidance section on category adherence

Insert before or within execution step:

```
**Category workflow adherence:**

The category prompt defines required steps and their sequence. Treat the category prompt as the primary execution guide. Shared context supplements the workflow but does not replace it.
```

### Change 4: Clarify shared context relationship

In the shared context section, add:

```
**Context precedence:** Shared context takes precedence over static fields like `:description` or `:design` when there are conflicts or updates from previous task execution. However, the category workflow steps remain mandatory unless legitimate reasons exist to deviate.
```

## Summary of Gaps

1. **No explicit category retrieval step** - combined with execution
2. **Missing error handling** for missing category prompt files
3. **Weaker adherence language** - "Execute by following" vs "Follow category instructions"
4. **No explicit workflow ordering emphasis** - lacks "Execute workflow steps in order"
5. **No guidance on category vs. shared context** - unclear relationship between category workflow and shared context
6. **Combined step reduces clarity** - retrieval + execution + shared context in one section

## Implementation Priority

1. **High:** Add explicit category retrieval step with error handling (mirrors execute-task.md)
2. **High:** Strengthen execution language to emphasize mandatory adherence
3. **Medium:** Add guidance section clarifying category workflow as primary guide
4. **Medium:** Update step numbering to accommodate new retrieval step
5. **Low:** Enhance shared context section to clarify relationship with category workflow
