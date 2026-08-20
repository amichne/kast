# Task Contract

## Goal

Kast patch release `v0.25.4` publishes successfully without Rust-negation testing.

## Allowed Writes

- `.agent/TASK.md`
- `.github/AGENTS.md`
- `.github/scripts/check-no-rust-product.py`
- `.github/scripts/release/verify-release-contract.py`

No other paths may be modified.

## Allowed Reads

- The complete `.github/` directory.
- Root release and version metadata.
- Pull request #625 and GitHub Actions release evidence.
- Git tags and GitHub releases.

## Non-Goals

- Changing product behavior or Kotlin source.
- Changing release asset formats or destinations.
- Changing secrets, permissions, environments, or deployment targets.
- Removing non-Rust repository checks.
- Refactoring unrelated code.
- Generalizing the implementation.
- Fixing unrelated failures.
- Adding optional improvements.

## Red Proof

Command:

```shell
python3 .github/scripts/release/verify-release-contract.py --root . && test ! -e .github/scripts/check-no-rust-product.py && ! rg -n 'check-no-rust-product|Rust product|retired-product' .github/AGENTS.md .github/workflows .github/scripts/release
```

Expected failure:

The release-contract verifier fails because it still requires the removed CI Rust-negation check, and the obsolete checker still exists.

## Green Proof

Command:

```shell
python3 .github/scripts/release/verify-release-contract.py --root . && test ! -e .github/scripts/check-no-rust-product.py && ! rg -n 'check-no-rust-product|Rust product|retired-product' .github/AGENTS.md .github/workflows .github/scripts/release && python3 .github/scripts/check-repository-shape.py --root .
```

## Done When

- The release contract no longer requires Rust-negation testing.
- The Rust-negation checker and its automation guidance are absent.
- The Green Proof passes.
- The change reaches `main` with passing CI.
- GitHub release `v0.25.4` is published from the resulting `main` commit.
- The release workflow's fresh published installation check passes.
- No files outside Allowed Writes changed.
- No Non-Goal work was performed.

## Execution State

- Live RED: Release run `32376670124` for `0.25.4` failed because the release-contract verifier required `.github/scripts/check-no-rust-product.py` in CI after CI stopped invoking it.
- Release `v0.25.4` and tag `v0.25.4` do not exist.
- Local RED: the declared check failed with the same stale release-contract requirement.
- Implementation: removed the checker, its release-contract token, and its `.github` guidance.
- Local GREEN: the declared Green Proof passed with zero repository-shape violations.

## Out-of-Scope Findings

- None
