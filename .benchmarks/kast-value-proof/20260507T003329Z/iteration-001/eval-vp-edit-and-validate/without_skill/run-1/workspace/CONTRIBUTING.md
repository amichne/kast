# Contributing to Konditional

Thank you for contributing to Konditional.

This guide explains how to propose changes that are easy to review and safe to
merge.

## Development setup

To work on the project locally, use this baseline:

1. Install JDK 21.
2. Clone the repository.
3. Run `./gradlew -v` to confirm your toolchain.
4. Run `make build` once to validate local setup.

## Repository architecture constraints

Before writing code, keep these invariants in mind:

- `konditional-core` is pure domain logic and must not depend on runtime or
  serialization modules.
- External input is untrusted and must be parsed into typed models.
- Core evaluation behavior must remain deterministic.
- Runtime state updates must preserve atomic snapshot semantics.

For deeper context, read `AGENTS.md` and the guides under `docs/`.

## Development workflow

Use the narrowest command that validates your change:

- `make build` for compilation checks
- `make test` for behavior checks
- `make detekt` for static analysis
- `make check` for standard CI-equivalent verification

If you change documentation under `docs/` or `zensical.toml`, also run:

- `make docs-build`

## Testing expectations

Behavioral changes must include tests that prove affected invariants.

Examples:

- determinism: same input yields same output
- boundary parsing: invalid payloads return typed failures
- atomicity: no partial reads during updates
- namespace isolation: no cross-namespace side effects

## Commit and pull request guidelines

Follow these rules for all pull requests:

1. Use Conventional Commits (for example, `feat:`, `fix:`, `docs:`).
2. Keep each pull request focused on one concern.
3. Update docs when public API or behavior changes.
4. Include tests for non-trivial changes.
5. Ensure CI is green before requesting review.

## Pull request checklist

Before opening a pull request, confirm:

- [ ] Build and tests pass locally.
- [ ] Public API changes include KDoc and usage docs.
- [ ] New boundary behavior returns typed errors, not ad hoc exceptions.
- [ ] No dependency direction violations were introduced.
- [ ] Changelog impact was considered (`CHANGELOG.md` when applicable).

## Reporting issues

- For bugs and feature proposals, use GitHub Issues.
- For security issues, do not open a public issue; use `SECURITY.md`.
