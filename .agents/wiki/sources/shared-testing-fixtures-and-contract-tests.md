# Shared Testing Fixtures and Contract Tests

This page summarizes the raw note
[[Shared-Testing-Fixtures-and-Contract-Tests]]. It is the most direct source for
the shared contract fixture.

## Source

This source is a shared-testing note.

- Type: note
- Date: Unknown
- Author: Unknown
- Location: [[Shared-Testing-Fixtures-and-Contract-Tests]]

## Summary

This note explains the `AnalysisBackendContractFixture`, shared assertions, the
fake backend, and the process for validating new backend implementations. Its
main contribution is showing how Kast preserves behavioral consistency across
multiple implementations of the same contract.

It also makes clear that correctness is defined partly by canonical examples and
expected outcomes, not just by implementation-specific tests.

## Key claims

- Shared fixtures are the behavioral anchor for backend compatibility.
- The fake backend is useful because it mirrors contract behavior quickly.
- New backends are expected to prove themselves against shared assertions.

## Connections

This source reinforces the verification story.

- Reinforces [[concepts/testing-and-verification]]
- Adds detail to [[entities/analysis-api]]
- Supports [[analyses/safety-and-correctness-story]]

## Open questions

This source does not cover every future-proofing concern.

- How often does the fixture evolve to cover new language features?
- Which operations are hardest to model faithfully in the fake backend?

## Pages updated from this source

The pages below now reflect this source.

- [[concepts/testing-and-verification]]
- [[entities/analysis-api]]
- [[analyses/safety-and-correctness-story]]
