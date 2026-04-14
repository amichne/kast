# Testing Infrastructure

This page summarizes the raw note [[Testing-Infrastructure]]. It is the broad
source for Kast's testing strategy.

## Source

This source is a testing strategy note.

- Type: note
- Date: Unknown
- Author: Unknown
- Location: [[Testing-Infrastructure]]

## Summary

This note explains Kast's testing tiers, including contract testing, integration
testing, and performance baselines. Its main contribution is showing how the
project distributes trust across different levels of realism and cost.

It also clarifies that testing spans both the shared contract and the concrete
standalone backend, which matters because semantic behavior cannot be proven from
one layer alone.

## Key claims

- Kast uses multiple testing tiers for different risk classes.
- Shared fixtures and contract assertions keep backend implementations aligned.
- Performance regression tracking is part of the testing story.

## Connections

This source feeds the safety-oriented pages.

- Reinforces [[concepts/testing-and-verification]]
- Adds detail to [[analyses/safety-and-correctness-story]]
- Supports [[entities/backend-standalone]]

## Open questions

This source describes the layers but not every decision rule.

- Which performance baselines are release-blocking?
- Which mutation paths remain hardest to test exhaustively?

## Pages updated from this source

The pages below were updated from this source.

- [[concepts/testing-and-verification]]
- [[analyses/safety-and-correctness-story]]
- [[entities/backend-standalone]]
