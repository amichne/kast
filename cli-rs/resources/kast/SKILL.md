---
name: kast
description: Use when Kotlin or Gradle work needs compiler-backed file discovery, symbol and reference traversal, graph analysis, diagnostics, or validated source changes through the Kast CLI.
---

# Kast

Use `kast` as the public interface for Kotlin and Gradle semantic work.

## Operate

1. Run `kast` from the target workspace. Confirm the selected `root`, `ready`,
   `referenceIndexReady`, `limitation`, and `next` fields.
2. If evidence is not ready, run the exact suggested `next` command, usually
   `kast up`, then retry the requested semantic command.
3. Run `kast <command> --help` when syntax is uncertain. Do not use the retired
   `kast agent` surface or assume `kastctl` is on `PATH`.

Select the narrowest operation:

- Use `kast files [PATTERN]` for Kotlin source and script inventory.
- Use `kast symbol find <QUERY>`, then `show`, `refs`, `callers`, `callees`,
  `implementations`, `supertypes`, or `subtypes` with the returned identity.
- Use `kast graph summary`, `nodes`, `neighbors`, `topology`, `communities`, or
  `impact` for persisted structure and bounded impact.
- Use `kast check [PATH...]` for compiler diagnostics.
- Use `kast refresh [PATH...]` after direct source edits. Externalize an
  eligible failure only when the user accepts an explicit `UNKNOWN` boundary.

## Compose validated changes

Use `kast change rename`, `replace`, `add-file`, or `add-declaration` to create
one root-bound plan. The last three commands read Kotlin content from standard
input. Use a quoted heredoc or pipe a trusted file so the shell does not alter
complex source text.

Review the preview, proof, and limitations before writing. Capture the exact
`planId`, then run `kast apply <PLAN_ID>` as a separate command. Do not pipe a
new plan directly into `apply`.

Treat only `VERIFIED` as success. For `RECOVERY_REQUIRED`, capture the exact
`recoveryId` and run `kast recover <RECOVERY_ID>`. Treat `REJECTED`,
`CONFLICTED`, and `ROLLED_BACK` as typed non-success outcomes. Retrying a
terminal receipt does not repeat source writes.

## Read and present evidence

Kast emits compact TOON without an output-format flag. Read named fields; do
not scrape display position. When a result has `nextPage`, repeat the same
`files`, symbol relationship, `graph nodes`, or `graph impact` command with
`--page <nextPage>`. Aggregate only the requested evidence and stop when
`nextPage` is absent.

Present the outcome first, then the decisive symbols, files, proof, or
limitations, followed by the next action. Do not paste the full result when a
small field set proves the claim. Never treat an empty result as complete when
coverage, `limitation`, or `next` says otherwise.

For setup, runtime control, configuration, local-state inspection, raw RPC, or
release work, invoke `/kast:developer`. Read `developerOperations.cli` from
`kast`; do not invent or reuse a control path.
