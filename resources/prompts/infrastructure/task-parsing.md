Parse `$ARGUMENTS`: first token is task specification, rest is context.

| Format | Example | select-tasks params |
|--------|---------|---------------------|
| Numeric / #N / "task N" | 59, #59, task 59 | `task-id: N, unique: true` |
| Text | "Update prompt file" | `title-pattern: "...", unique: true` |

Handle no match or multiple matches by informing user.
