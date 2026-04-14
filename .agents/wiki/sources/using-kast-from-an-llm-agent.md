# Using Kast from an LLM Agent

This page summarizes the raw note [[Using-Kast-from-an-LLM-Agent]]. It is the
main source for agent-specific usage patterns.

## Source

This source is an agent workflow note.

- Type: note
- Date: Unknown
- Author: Unknown
- Location: [[Using-Kast-from-an-LLM-Agent]]

## Summary

This note explains the agent skill, wrapper scripts, helper utilities, and the
"golden path" that moves from a natural-language symbol description to a precise
code entity and then into deeper operations. Its main contribution is turning
Kast into a repeatable agent interface rather than a loose collection of shell
commands.

It also emphasizes result bounding, visibility, and conflict detection, which
shows that agent workflows are designed around safety and reliability concerns
as much as convenience.

## Key claims

- Agent workflows need a staged natural-language-to-code resolution path.
- Wrapper scripts and JSON outputs reduce shell and prompt fragility.
- Mutation-oriented agent flows depend on explicit conflict awareness.

## Connections

This source shapes the agent-facing part of the wiki.

- Reinforces [[concepts/llm-agent-workflows]]
- Reinforces [[concepts/hierarchy-traversal]]
- Adds detail to [[analyses/end-to-end-request-lifecycle]]

## Open questions

This source defines the scaffolding, but not usage frequency.

- Which wrappers cover most real agent tasks in practice?
- Where do agents still need direct CLI calls instead of the packaged scripts?

## Pages updated from this source

The pages below now reflect this source.

- [[concepts/llm-agent-workflows]]
- [[concepts/hierarchy-traversal]]
- [[analyses/operator-journeys]]
