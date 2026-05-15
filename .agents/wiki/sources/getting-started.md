# Getting Started

This page summarizes the raw note [[Getting-Started]]. It captures the first-run
experience and the minimum operational path into Kast.

## Source

This source is an onboarding and setup note.

- Type: note
- Date: Unknown
- Author: Unknown
- Location: [[Getting-Started]]

## Summary

This note explains prerequisites, published versus local installation paths, the
CLI discovery cascade, and the basic daemon lifecycle. Its main contribution is
showing how a caller gets from "Kast is installed" to "the daemon is usable."

It also makes the warm versus cold path visible by distinguishing prewarming,
first command execution, and daemon shutdown.

## Key claims

- Installation and binary discovery are part of the user experience, not hidden
  implementation details.
- The first command pays setup costs that later commands can avoid.
- Daemon lifecycle management is an explicit operator concern.

## Connections

This source links directly into the operational wiki pages.

- Reinforces [[concepts/installation-and-instance-management]]
- Adds detail to [[entities/kast-cli]]
- Supports [[analyses/operator-journeys]]

## Open questions

This source stops short of a few operational comparisons.

- When is prewarming materially worth the extra step?
- Which installation path is preferred for agent-heavy workflows?

## Pages updated from this source

The pages below now reflect this source.

- [[entities/kast-cli]]
- [[concepts/installation-and-instance-management]]
- [[analyses/operator-journeys]]
