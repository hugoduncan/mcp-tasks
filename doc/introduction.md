# Introduction to mcp-tasks

When working with AI agents on software projects, you quickly encounter a fundamental challenge: how do you maintain continuity across multiple chat sessions, ensure the agent understands exactly what you want, and get consistently high-quality results without constant supervision? mcp-tasks is designed to solve these problems by providing structure and workflow management for agent-driven development.

## What mcp-tasks Is

### A tool to help you get more done with an AI agent

mcp-tasks amplifies your productivity when working with AI agents by creating a systematic workflow for task execution. Instead of repeatedly explaining context, requirements, and project conventions in every chat session, you define tasks once with all necessary details. The agent then has everything it needs to work autonomously and effectively. This means you spend less time managing the agent and more time on higher-level planning and review.

### Enables multiple sessions on a single project

Software projects rarely fit into a single conversation. With traditional chat-based AI assistance, each new session starts from scratch, losing the context and momentum from previous work. mcp-tasks maintains persistent task lists across sessions, allowing you to break down large projects into manageable pieces. You can work on different aspects of your project across multiple days or weeks, with each session picking up exactly where the last one left off. The task history provides a complete audit trail of what was done, when, and why.

### Reduces the amount of time spent checking and correcting the agent

When agents work from vague or incomplete instructions, you end up in a frustrating cycle of reviewing output, identifying problems, and asking for corrections. mcp-tasks addresses this by encouraging upfront task refinement. Before execution begins, you work with the agent to clarify requirements, identify edge cases, and remove ambiguity. This investment in clarity pays dividends: the agent produces correct implementations the first time, reducing the iteration cycles that consume your time and attention.

### Improves the quality of the agent's output

Quality suffers when agents don't understand the full scope of what they're building or lack context about project conventions and patterns. By providing detailed task descriptions, design notes, and category-specific execution workflows, mcp-tasks gives agents the information they need to make good decisions. The result is code that follows your project's patterns, handles edge cases properly, and integrates cleanly with existing systems.

## How It Reaches These Goals

### Creates tasks as explicit descriptions of what the agent is expected to do

The foundation of mcp-tasks is the task description. Rather than informal chat messages that might miss important details, you create structured task records with titles, descriptions, design notes, and metadata. These explicit descriptions serve as specifications that remove guesswork. When an agent reads a well-defined task, it knows exactly what to build, what constraints to respect, and what success looks like. This clarity eliminates the most common source of agent errors: misunderstanding requirements.

### Enables refinement to remove ambiguity and ensure all aspects are considered up front

Before any code is written, mcp-tasks provides a refinement workflow where you collaborate with the agent to improve task clarity. The agent analyzes the task in project context, identifies missing details, points out potential scope creep, and suggests improvements. This interactive process catches problems early when they're cheap to fix, rather than discovering them during implementation when they're expensive. The refinement step is where quality is built in, not bolted on later.

### Splits stories into tasks, so each task can be executed without context compaction

AI agents have context window limits. When you try to accomplish too much in a single session, the agent starts losing important details as earlier context gets compressed or dropped. mcp-tasks addresses this with story-based workflows that break large features into smaller, self-contained tasks. Each task fits comfortably within the agent's context window, ensuring it can access all relevant information throughout execution. This decomposition also creates natural checkpoints where you can review progress and adjust direction.

### Provides workflows that make the agent work in the way that you want

Different types of tasks require different approaches. A bug fix follows a different workflow than implementing a new feature or refactoring existing code. mcp-tasks uses categories to define custom execution workflows for different task types. You can specify that feature tasks should start with design documentation, that bug fixes should include regression tests, or that refactoring tasks should maintain existing behavior. These category-specific workflows encode your team's best practices, ensuring the agent follows them consistently. See [doc/workflow.md](workflow.md) for details on category-based workflows.

### Builds in review of work done

Quality assurance is built into the mcp-tasks workflow. After completing each task, the agent commits its changes and moves the task to a completed archive. This creates natural review points where you can examine what was done before proceeding. The completed task archive provides full context about what was implemented and why, making it easy to review changes or understand decisions later. This systematic approach to review prevents the quality drift that happens when changes accumulate unchecked.

### Enables parallel sessions through built-in branch and worktree management

Real-world development rarely proceeds in a straight line. You might need to pause work on a feature to fix an urgent bug, or you might want multiple agents working on different tasks simultaneously. mcp-tasks supports this through automatic branch and worktree management. When you start working on a task, the system can automatically create a dedicated branch and, optionally, an isolated worktree (a separate working directory). This isolation means you can have multiple tasks in progress at once without any interference—each task has its own workspace, its own branch, and its own working directory.

This capability transforms how you can use AI agents. You can have one agent working on a feature in one worktree while another agent fixes a bug in a different worktree. You can switch between tasks without losing your place or risking merge conflicts. When a task is complete, the worktree is automatically cleaned up, keeping your workspace organized. The branch and worktree management is configurable, so you can enable it when you need isolation or disable it when you prefer a simpler workflow. See [doc/workflow.md](workflow.md) for details on worktree workflows.

## Making It Work Your Way

### Different prompts for different categories of tasks

mcp-tasks recognizes that one workflow doesn't fit all situations. The category system lets you define custom execution instructions for different task types. Create a "feature" category that emphasizes design and testing, a "bugfix" category that focuses on root cause analysis, or a "docs" category optimized for documentation tasks. Each category can have its own prompt template that tells the agent exactly how to approach that type of work. This flexibility means the tool adapts to your process, not the other way around. See [doc/config.md](config.md) for configuration details.

### Overridable review instructions

Just as execution workflows vary by task type, so do review criteria. mcp-tasks allows you to customize review instructions per category, ensuring the agent checks for the things that matter for each type of work. Features might require comprehensive test coverage and documentation updates, while bug fixes might focus on regression prevention and root cause documentation. By making review criteria explicit and customizable, you ensure consistent quality standards without imposing unnecessary constraints.

## Next Steps

To start using mcp-tasks:

- **Installation**: See [doc/install.md](install.md) for setup instructions
- **Basic Workflow**: See [doc/workflow.md](workflow.md) for task management patterns
- **Configuration**: See [doc/config.md](config.md) for customization options

For the quick start guide and feature overview, see the main [README](../README.md).
