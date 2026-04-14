# Testing and verification

Kast uses several layers of testing and automated validation to keep analysis
and mutation trustworthy. This page captures that safety net as a single idea.

## Summary

The system does not rely on one proof mechanism. It combines contract tests,
integration tests, smoke checks, CI workflows, performance baselines, and edit
validation so semantic behavior can stay consistent across modules and releases.

## What the wiki currently believes

- Shared contract fixtures are the anchor that keep multiple backend
  implementations behaviorally aligned.
- Integration tests matter because backend correctness depends on realistic
  compiler, workspace, and cache state.
- CI and smoke tests exist to catch packaging and release regressions that unit
  tests alone would miss.

## Evidence and sources

These pages define the verification story.

- [[sources/testing-infrastructure]] - Summarizes the overall testing strategy.
- [[sources/shared-testing-fixtures-and-contract-tests]] - Explains the shared
  fixture and fake backend.
- [[sources/backend-standalone-integration-tests]] - Covers backend invariants,
  performance baselines, and compatibility shims.
- [[sources/ci-cd-pipelines-and-smoke-testing]] - Explains release and smoke
  validation.

## Related pages

These pages rely on the verification story to stay credible.

- [[concepts/semantic-analysis-operations]]
- [[concepts/indexing-and-caching]]
- [[concepts/telemetry-and-observability]]
- [[analyses/safety-and-correctness-story]]

## Open questions

The current notes leave some quality questions open.

- Which mutation scenarios remain hardest to verify automatically?
- Which performance baselines are treated as release gates versus advisory
  signals?
