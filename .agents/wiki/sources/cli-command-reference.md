# CLI Command Reference

This page summarizes the raw note [[CLI-Command-Reference]]. It is the most
direct catalog of Kast's user-facing verbs.

## Source

This source is a command reference note.

- Type: note
- Date: Unknown
- Author: Unknown
- Location: [[CLI-Command-Reference]]

## Summary

This note enumerates global options, workspace lifecycle commands, read
analysis commands, mutation commands, and selected JSON output shapes. Its main
contribution is to make Kast's operational surface explicit and queryable.

It also clarifies that the CLI serves both humans and automation by documenting
structured outputs in addition to command syntax.

## Key claims

- The CLI exposes both lifecycle commands and semantic operations.
- Structured JSON output is a first-class part of the interface.
- Mutation commands live beside read commands in the same tool surface.

## Connections

This source informs the main interface pages.

- Reinforces [[entities/kast-cli]]
- Reinforces [[concepts/semantic-analysis-operations]]
- Adds detail to [[concepts/llm-agent-workflows]]

## Open questions

This source is broad but not deeply evaluative.

- Which command outputs are most stable for long-lived tooling?
- Which commands are used most often together in real workflows?

## Pages updated from this source

The pages below incorporate this source.

- [[entities/kast-cli]]
- [[concepts/semantic-analysis-operations]]
- [[analyses/operator-journeys]]
