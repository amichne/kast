# Updated implementation plan

This plan replaces `initial_plan.md`. It preserves the four independent work
streams while correcting stale assumptions about the current repository. Each
stream should land as its own branch or PR when practical. Within a stream, use a
TDD tracer-bullet loop: write one narrow failing test, implement the smallest
vertical slice that makes it pass, then harden edge cases and parity.

## Current facts to preserve

- There is no current top-level `kast build` CLI command. `kast.sh build` builds
  portable distribution artifacts.
- Gradle task execution belongs under a new `kast gradle run` command. Keep the
  command shape open for a later `kast gradle debug`.
- `source_set` is already live in index metrics. Do not remove or reintroduce it.
- Prefix and filename indexes already exist. Extend the current index design
  instead of planning duplicate indexes.
- `.gradle.kts` exclusion is primarily an IntelliJ/source-index eligibility
  issue. The standalone backend already filters tracked source files to `.kt`.
- `DeclarationScope`, outline ancestry helpers, and
  `MetricsEngine.searchSymbols` already exist. Extend them instead of replacing
  them with parallel models or search paths.
- `site/` is generated output and is not hand-edited.

## Interfaces and contracts

The planned public contract changes are:

- JSON-RPC references query:
  `ReferencesQuery.includeUsageSiteScope: Boolean = false`.
- Location model:
  `Location.usageSiteScope: DeclarationScope? = null`.
- CLI references command:
  add an option whose name and semantics match
  `ReferencesQuery.includeUsageSiteScope`.
- Gradle runner CLI:
  `kast gradle run <task>` emits structured JSON.

`kast gradle run <task>` should return a stable JSON object suitable for tools
and agents. Include at least:

- task name
- exit code
- duration
- log file path when available
- cache and up-to-date counts when available
- failure summary when the task fails

Do not plan a source-index wire or schema change for Stream A unless the tracer
bullet discovers a required migration. If that happens, document the migration,
the producer that requires it, and the compatibility impact before changing the
schema.

Generated docs, OpenAPI files, and package manifests should change only when a
stream changes their contract surface.

## Stream A: Source index eligibility

Goal: define one runtime-agnostic source-index eligibility policy and use it
where files enter or remain in source-index tables.

### Tracer bullet

1. Add one narrow failing policy test in `index-store` that accepts `.kt` files
   and rejects `.gradle.kts` files.
2. Implement the smallest shared policy in `index-store` that passes the test.
3. Wire the policy into the IntelliJ reference indexing path so Kotlin script
   files no longer enter indexed source references.

### Implementation steps

- Introduce a small source-index file policy in `index-store` that can be reused
  without depending on IntelliJ or standalone runtime APIs.
- Use the policy from IntelliJ source-reference indexing, especially the file
  enumeration path that can currently see `.gradle.kts`.
- Keep standalone behavior equivalent to today's `.kt` tracking. Adopt the
  shared policy only if it does not widen the standalone scan.
- Add defensive store cleanup for stale `.gradle.kts` rows in source-index
  tables if the tracer bullet proves existing stores can retain them.
- Preserve current `source_set` metrics behavior.
- Do not add `symbol_kind`, remove `source_set`, duplicate prefix or filename
  indexes, or bump the schema version unless a failing tracer bullet proves the
  current schema cannot support the fix.

### Hardening

- Cover path casing and nested Gradle script names only if the current path
  policy treats them as eligible.
- Verify cleanup is idempotent and safe on empty stores.
- Document any discovered schema constraint before implementing a migration.

## Stream B: Reference usage context

Goal: allow callers to request the enclosing declaration for each reference
usage site while preserving the default lightweight reference response.

### Tracer bullet

1. Add one failing contract test showing
   `ReferencesQuery.includeUsageSiteScope` defaults to `false` and round-trips
   when set to `true`.
2. Add one failing backend or parity test for a reference inside a function that
   expects `Location.usageSiteScope` to contain the enclosing `DeclarationScope`
   only when requested.
3. Implement the smallest vertical slice through the API model, server path, and
   one backend, then bring the second backend to parity.

### Implementation steps

- Extend `ReferencesQuery` with
  `includeUsageSiteScope: Boolean = false`.
- Extend `Location` with
  `usageSiteScope: DeclarationScope? = null`.
- Reuse and extend existing `DeclarationScope` and outline ancestry helpers in
  `backend-shared`.
- Put the PSI ancestry logic in shared backend code that both standalone and
  IntelliJ paths can call.
- In the standalone references path, attach usage-site scope only when the query
  flag is true.
- In the IntelliJ references path, attach the same scope shape and keep backend
  parity.
- Add the matching `kast references` CLI option and map it directly to
  `ReferencesQuery.includeUsageSiteScope`.

### Hardening

- Verify the default response stays unchanged when the flag is false.
- Verify top-level usages, class-body usages, function-local usages, and usages
  inside nested declarations.
- Treat nearest-N symbols and same-depth grouping as follow-on work after the
  first usage-scope tracer bullet lands.

## Stream C: Fuzzy symbol search

Goal: improve symbol search tolerance without adding a parallel search API or
weakening exact-match ranking.

### Tracer bullet

1. Add one failing `index-store` test against `MetricsEngine.searchSymbols` for
   a small typo that should still find the expected symbol.
2. Extend the existing `MetricsEngine.searchSymbols` path with the smallest
   bounded fuzzy step after cheaper exact, case-insensitive, prefix, or filename
   filters.
3. Preserve existing exact and case-insensitive behavior before broadening the
   algorithm.

### Implementation steps

- Extend `MetricsEngine.searchSymbols`; do not add a separate
  `fuzzySearchSymbols` public path for this stream.
- Keep exact matches ranked first, then case-insensitive matches, then bounded
  fuzzy matches.
- Use existing prefix and filename indexes for coarse narrowing where possible.
- Apply fuzzy matching after cheaper filters and respect the requested limit.
- Keep blank-query behavior explicit and stable.
- Keep the JetBrains text-index investigation as a spike note, not part of the
  first implementation path.

### Hardening

- Add tests for exact, case-insensitive, typo, limit, and blank-query behavior.
- Add enough ranking assertions to prevent fuzzy matches from hiding exact
  matches.
- Bound candidate collection so large indexes do not turn one search into a full
  unbounded scan.

## Stream D: Gradle runner CLI

Goal: add a first-class operator command for structured Gradle task execution
without overloading artifact build commands.

### Tracer bullet

1. Add one failing `kast-cli` parser or catalog test for
   `kast gradle run <task>`.
2. Implement the smallest CLI path that parses the command, invokes the existing
   Gradle runner contract, and emits structured JSON.
3. Add one failing execution test for a failed Gradle task and return structured
   failure JSON.

### Implementation steps

- Add the `kast gradle run <task>` command under `kast-cli`.
- Leave space in the command hierarchy for a later `kast gradle debug`.
- Reuse the existing Kotlin Gradle loop runner contract where practical instead
  of creating a second Gradle execution wrapper.
- Do not add or document a top-level `kast build` command in this stream.
- Keep `kast.sh build` as the artifact-builder path for portable distribution
  artifacts.
- Emit structured JSON with task, exit code, duration, log file, cache and
  up-to-date counts, and failure summary.
- Route coverage through `kast-cli` parser, help/catalog, and execution tests.

### Hardening

- Verify success and failure outputs have the same stable shape.
- Verify extra Gradle arguments are either supported deliberately or rejected
  with a structured error.
- Verify the command works from the intended project root and reports useful
  errors for missing tasks or missing Gradle wrapper files.

## Test plan

- `index-store`: policy tests for `.kt` accepted, `.gradle.kts` rejected, stale
  rows cleaned defensively, existing `source_set` metrics preserved.
- `backend-standalone`: prove existing `.kt` tracking still works and policy
  adoption does not widen the scan.
- `backend-intellij`: fixture test that Kotlin script files are excluded from
  indexed source references.
- `analysis-api` and `analysis-server`: serialization/default tests for the new
  references query field and optional location scope.
- `backend-shared`, `backend-standalone`, `backend-intellij`: parity tests
  showing reference locations can include the enclosing usage declaration.
- `index-store`: fuzzy search tests for exact, case-insensitive, typo, limit,
  and blank-query behavior.
- `kast-cli`: parser/catalog/execution tests for `kast gradle run`, including
  failed Gradle task JSON.

## Assumptions

- `updated_plan.md` is a new root-level Markdown file; `initial_plan.md` remains
  unchanged.
- This refined plan is a full replacement, not an annotated diff.
- `site/` is not hand-edited.
- Generated docs, OpenAPI files, and package manifests are updated only if the
  corresponding stream changes their contract surface.

## Definition of done

- Each stream starts with a failing tracer-bullet test.
- Each stream lands the smallest vertical implementation before hardening.
- Public contract changes include tests and documentation updates when they
  affect generated docs, OpenAPI, CLI help, or packaged manifests.
- Standalone and IntelliJ behavior remains explicit and parity-tested where both
  runtimes support the feature.
- No unrelated files change, and `initial_plan.md` remains unchanged.
