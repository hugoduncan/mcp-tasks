An MCP server that provides a task based workflow

Provides a workflow where you plan tasks for an agent to execute.  The
tasks are divided into categories, where each category can have
different instructions for it execution.

The server provides some default categories, but these are completely
configurable.

The tasks are stored in files under .mcp-tasks/task.  Each category has a
file <category-name>.md, that contains the incomplete tasks.


Completed tasks are moved to files under .mcp-tasks/completed

Each category has a prompt under .mcp-tasks/prompt/<category name>.md
