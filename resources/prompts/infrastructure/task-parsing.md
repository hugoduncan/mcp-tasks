Parse `$ARGUMENTS`: first token is task specification, rest is context.

{% if cli %}
| Format | Example | CLI command |
|--------|---------|-------------|
| Numeric / #N / "task N" | 59, #59, task 59 | `mcp-tasks show --task-id N` |
| Text | "Update prompt file" | `mcp-tasks list --title-pattern "..." --limit 1` |
{% else %}
| Format | Example | select-tasks params |
|--------|---------|---------------------|
| Numeric / #N / "task N" | 59, #59, task 59 | `task-id: N, unique: true` |
| Text | "Update prompt file" | `title-pattern: "...", unique: true` |
{% endif %}

Handle no match or multiple matches by informing user.
