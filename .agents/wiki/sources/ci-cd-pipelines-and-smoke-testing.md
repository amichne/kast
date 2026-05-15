# CI/CD Pipelines and Smoke Testing

This page summarizes the raw note [[CI-CD-Pipelines-and-Smoke-Testing]]. It is
the source that makes release validation explicit.

## Source

This source is a CI and smoke-testing note.

- Type: note
- Date: Unknown
- Author: Unknown
- Location: [[CI-CD-Pipelines-and-Smoke-Testing]]

## Summary

This note explains the GitHub Actions workflows, release pipeline, smoke
scripts, helper scripts, and automated validation hooks that gate builds and
releases. Its main contribution is showing how Kast validates packaging and
end-to-end behavior outside local unit and integration tests.

It also shows that smoke coverage is treated as part of product reliability, not
just CI decoration.

## Key claims

- CI and release workflows validate more than compilation.
- Smoke scripts are a deliberate safeguard for packaging and install behavior.
- Validation hooks help enforce quality before release artifacts move forward.

## Connections

This source feeds the trust and release pages.

- Reinforces [[concepts/testing-and-verification]]
- Adds detail to [[concepts/installation-and-instance-management]]
- Supports [[analyses/safety-and-correctness-story]]

## Open questions

This source is clear on flow and lighter on thresholds.

- Which failures most often surface only in smoke tests?
- Which pipeline checks are treated as hard release gates?

## Pages updated from this source

The pages below now reflect this source.

- [[concepts/testing-and-verification]]
- [[concepts/installation-and-instance-management]]
- [[analyses/safety-and-correctness-story]]
