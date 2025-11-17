For each out-of-scope issue you discovered while executing the task,
create a task describing the issue.
{% if cli %}
Use `mcp-tasks add --category <category> --title "..." --description "..."` to create the task,
then `mcp-tasks update --task-id <new-id> --relations '[{:id 1 :relates-to <current-task-id> :as-type :discovered-during}]'`
to link with `:discovered-during` relation.
{% else %}
Use `add-task`, link with
`:discovered-during` relation via `update-task`.
{% endif %}

**Capture:** Unrelated bugs, technical debt, missing tests,
documentation gaps. **Don't capture:** In-scope issues, direct blockers,
minor fixes.
