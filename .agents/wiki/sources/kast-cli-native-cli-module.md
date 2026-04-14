# kast-cli: Native CLI Module

This page summarizes the raw note [[kast-cli-Native-CLI-Module]]. It is the
deepest source for the CLI's implementation role.

## Source

This source is a CLI implementation note.

- Type: note
- Date: Unknown
- Author: Unknown
- Location: [[kast-cli-Native-CLI-Module]]

## Summary

This note explains the CLI entry point, request processing flow, runtime
management, transport communication, and support utilities such as skill
installation and smoke-command helpers. Its main contribution is showing how the
high-level command surface is implemented.

It also reinforces why the CLI is native and lightweight: it orchestrates rather
than hosts semantic state.

## Key claims

- The CLI is a native frontend that delegates semantic work to a backend.
- Runtime management and process launching are core CLI responsibilities.
- Skill installation and smoke support are built into the CLI layer.

## Connections

This source shapes the frontend-facing wiki pages.

- Reinforces [[entities/kast-cli]]
- Adds detail to [[concepts/llm-agent-workflows]]
- Supports [[analyses/operator-journeys]]

## Open questions

This source emphasizes structure more than product policy.

- Which CLI helpers are considered public workflow affordances versus internal
  support tools?
- How much of the CLI runtime management is visible to callers by design?

## Pages updated from this source

The pages below now reflect this source.

- [[entities/kast-cli]]
- [[concepts/llm-agent-workflows]]
- [[analyses/operator-journeys]]
