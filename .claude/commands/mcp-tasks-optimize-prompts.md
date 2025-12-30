---
description: Analyze session events from completed stories to optimize prompts
---


Analyze session events from completed stories to identify prompt improvements.

**Goal:** Ensure Claude follows prompts reliably. User corrections indicate
prompts need clearer or more explicit instructions. Compactions indicate
context issues (verbose prompts, under-specified tasks, or tasks not split
small enough).

## Process

### 1. Initialize State


Use `init-state!` function from `mcp-tasks.prompt-optimisation` namespace to
load or create the state file at `.mcp-tasks/prompt-optimisation.edn`.

The state tracks:
- `:last-run` - timestamp of last optimization run
- `:processed-story-ids` - set of story IDs already analyzed
- `:modifications` - vector of changes made to prompts


### 2. Collect Unprocessed Stories


Use `collect-unprocessed-stories` function with the config directory and state.
Returns stories with session events that haven't been analyzed yet.


**If no unprocessed stories:** Inform user "No new stories with session events
to analyze." and stop.

### 3. Analyze Session Events


Use `analyze-session-events` function with the collected stories.
Returns structured findings with `:compactions`, `:corrections`, `:restarts`
vectors plus `:story-count` and `:event-count`.


**If no findings:** Inform user "Analyzed N stories with M events. No issues
found." Update state with processed story IDs and stop.

### 4. Present Findings


Use `format-findings` function to generate markdown output.


Display all findings at once, grouped by issue type:

**For each compaction finding, show:**
- Story ID and title
- Number of compaction events and triggers (auto/manual)
- Diagnosis: What the compactions indicate
- Proposed fix: Specific prompt or task guidance changes

**For each correction finding, show:**
- Story ID and title
- Sample of the user's correction text
- Diagnosis: What behavior Claude exhibited that needed correction
- Proposed fix: Explicit instruction to add to the relevant prompt

**For each restart finding, show:**
- Story ID and title
- Number of restarts and triggers
- Diagnosis: Why restarts may have occurred
- Proposed fix: Workflow or prompt improvements

### 5. Get User Approval

After presenting all findings, ask:

"Which suggestions would you like to implement? (enter numbers separated by
commas, e.g., '1,3,5' or 'all' for all suggestions, or 'none' to skip)"

- If user selects 'none': Skip to step 7 (update state only)
- If user selects specific numbers: Proceed with those suggestions
- If user selects 'all': Proceed with all suggestions

### 6. Implement Approved Changes

For each approved suggestion:

1. **Identify the target prompt file:**
   - For compaction issues: Usually the category prompt (`category-prompts/<category>.md`)
   - For correction issues: The workflow or category prompt where the behavior occurred
   - For restart issues: May be workflow prompt or task creation guidance

2. **Determine the change:**
   - For verbosity issues: Identify sections to condense or remove
   - For missing instructions: Draft explicit "DO" or "DO NOT" statements
   - For unclear guidance: Rewrite ambiguous sections with concrete examples

3. **Apply the change:**

   - Use file editing tools to modify the prompt
   - Show the change to the user for confirmation


4. **Record the modification:**
   Create a modification record:
   ```
   {:timestamp "<ISO-8601>"
    :prompt-path "<relative-path-to-prompt>"
    :change-summary "<brief description of change>"}
   ```

### 7. Update State


Use `record-optimization-run!` function with:
- config-dir
- current timestamp
- collection of processed story IDs
- vector of modification records (empty if none approved)


Report summary:
- Number of stories analyzed
- Number of findings identified
- Number of changes implemented
- Next steps (if any findings were deferred)

## Correction Pattern Recognition

User corrections often indicate missing explicit instructions. Common patterns:

| User Said | Likely Missing Instruction |
|-----------|---------------------------|
| "No, don't..." | Add "DO NOT <action>" to prompt |
| "Wait, first..." | Add step ordering or prerequisite |
| "Actually, I meant..." | Clarify ambiguous instruction |
| "Stop and..." | Add checkpoint or confirmation step |
| "That's not what I asked" | Improve task parsing or validation |
| "Undo that" / "Revert" | Add caution or confirmation before action |

When implementing fixes for corrections, prefer explicit negative instructions
(what NOT to do) over implicit positive ones, as they prevent specific
unwanted behaviors.

## Notes

- All findings are numbered sequentially across categories for easy selection
- Prompt changes should be minimal and targeted - avoid rewriting entire prompts
- When uncertain about a fix, suggest it but mark as "needs review"
- Modifications are tracked for audit trail and to measure improvement over time
- Run this workflow periodically after completing several stories