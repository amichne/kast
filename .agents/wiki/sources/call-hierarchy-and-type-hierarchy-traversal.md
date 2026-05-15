# Call Hierarchy and Type Hierarchy Traversal

This page summarizes the raw note
[[Call-Hierarchy-and-Type-Hierarchy-Traversal]]. It is the direct source for
Kast's hierarchy features.

## Source

This source is a hierarchy feature note.

- Type: note
- Date: Unknown
- Author: Unknown
- Location: [[Call-Hierarchy-and-Type-Hierarchy-Traversal]]

## Summary

This note explains how Kast computes call hierarchies and type hierarchies,
including traversal logic, natural-language-to-code mapping, type resolution,
and resource management. Its main contribution is clarifying how Kast expands
from one symbol into a structural neighborhood.

It also reinforces that hierarchy features are especially important for agent
use because they provide context without requiring the whole codebase to be
loaded into prompt space.

## Key claims

- Kast supports both call and type hierarchy traversal.
- Traversal depends on correctly mapping natural-language intent to code
  identity.
- Bounds and resource handling matter because hierarchy results can grow
  quickly.

## Connections

This source shapes the traversal-related pages.

- Reinforces [[concepts/hierarchy-traversal]]
- Adds detail to [[concepts/llm-agent-workflows]]
- Supports [[analyses/end-to-end-request-lifecycle]]

## Open questions

This source defines mechanics more than defaults.

- What result bounds are used most often in practice?
- Which hierarchy mode yields the biggest gains for agent workflows?

## Pages updated from this source

The pages below now reflect this source.

- [[concepts/hierarchy-traversal]]
- [[concepts/llm-agent-workflows]]
- [[analyses/end-to-end-request-lifecycle]]
