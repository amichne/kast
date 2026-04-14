# LLM agent workflows

Kast includes a dedicated workflow layer for LLM agents. This page captures how
the CLI, skill files, and wrapper scripts turn semantic code intelligence into a
repeatable agent workflow.

## Summary

The core idea is that agents should not have to guess shell syntax or parse
fragile text. Kast packages wrappers that expose structured JSON and a staged
"golden path" from natural-language intent to precise code identity.

## What the wiki currently believes

- Agent workflows start from an approximate symbol description and narrow toward
  a precise code coordinate before deeper operations run.
- Wrapper scripts exist to reduce prompt fragility and shell-level mistakes.
- Agent-oriented workflows depend on bounded results, explicit visibility, and
  conflict-aware mutation plans.

## Evidence and sources

These pages define the current agent integration story.

- [[sources/using-kast-from-an-llm-agent]] - Covers the golden path, scripts,
  and agent-specific considerations.
- [[sources/cli-command-reference]] - Shows the underlying operations the agent
  wrappers invoke.
- [[sources/kast-cli-native-cli-module]] - Shows how CLI runtime management
  supports those flows.
- [[sources/installation-and-instance-management]] - Explains skill
  installation.

## Related pages

These pages explain the systems agent workflows depend on.

- [[entities/kast-cli]]
- [[concepts/semantic-analysis-operations]]
- [[concepts/hierarchy-traversal]]
- [[analyses/operator-journeys]]

## Open questions

The current sources define the scaffolding clearly, but usage patterns remain
open.

- Which wrappers cover most real-world agent tasks, and which still require raw
  CLI composition?
- How often do agents need human confirmation before applying a rename plan?
