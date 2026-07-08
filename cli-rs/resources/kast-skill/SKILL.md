---
name: kast
description: >
  Kotlin semantic work in Gradle repositories. Use when an agent needs compiler-backed
  Kotlin `.kt` or `.kts` discovery, symbol identity, references, callers, diagnostics,
  source-index impact, semantic rename, or focused Gradle validation.
---

# Kast

Use `kast agent` before generic file reads, text search, or hand-written edits for
Kotlin and Gradle semantic work. Treat `kast`, `kast help`, and this skill as the
public dialect; do not use catalog, workflow, hook, or Copilot package helpers as
the first iteration surface.

## Loop

1. Orient with `kast`, `kast help agent`, and read-only `kast ready --workspace-root "$PWD"` when install or backend state matters.
2. Resolve identity with `kast agent symbol --query <name> --workspace-root "$PWD"`. Add `--kind`, `--file-hint`, `--containing-type`, `--references`, or `--callers incoming|outgoing` only when needed.
3. Check changed files with `kast agent diagnostics --file-path <path> --workspace-root "$PWD"`.
4. Query source-index impact with `kast agent impact --symbol <fq-name> --workspace-root "$PWD"`.
5. Rename only by compiler identity: first run `kast agent rename --symbol <fq-name> --new-name <name> --workspace-root "$PWD"`, then add `--apply` after reviewing the plan.
6. Use `--output json` for JSON-only parsed scripts; otherwise `kast agent` defaults to compact TOON.

Completion criterion: every Kotlin semantic claim, edit target, relationship set,
and validation result is backed by a typed `kast agent` command, or the remaining
work is explicitly outside Kotlin semantics.

## Health

Use this section only when a typed `kast agent` command fails, the user asks for
readiness evidence, or backend state is part of the task.

- `kast ready --for agent|kotlin|release|machine --workspace-root "$PWD"` is read-only readiness.
- `kast repair --for agent|kotlin|release|machine --workspace-root "$PWD"` is plan-only repair.
- Add `--apply` to `kast repair` only after the repair plan or readiness output asks for install-state mutation.
- `kast agent verify --workspace-root "$PWD"` proves backend health, runtime status, and capabilities for semantic work.
- On macOS, the IntelliJ plugin prepares workspace guidance and metadata; run `kast developer machine plugin` only to repair Homebrew-managed IDE plugin links.
- `kast runtime status --workspace-root "$PWD"` reports daemon lifecycle only.

Do not teach `kast agent tools`, `kast agent call`, `kast agent workflow`, `kast rpc`,
generated protocol paths, LSP capability internals, backend implementation classes,
portable instruction packages, Copilot package files, or hooks as public agent APIs.
