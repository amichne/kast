---
name: kast
description: Use Kast to find and read Kotlin declarations, trace compiler-confirmed relationships, compose bounded semantic queries, inspect IDEA diagnostics, or apply an explicitly requested exact-target declaration or function-body change in a Kotlin Gradle repository.
---

# Kast

Use the installed Kast tools for compiler-grounded facts about one exact Kotlin
Gradle repository or worktree. The skill works with direct MCP, hosted Codex
tools, or the one-shot Tool RPC. Its query examples teach the public input
shape; the installed catalog is the authority for the current release.

## Establish the workspace and contract

1. Identify the user's exact repository root or worktree. Bind the tool process
   to that root before reading; a similarly named checkout is a different
   workspace. Read [connection.md](references/connection.md) when connecting a
   client or verifying its workspace binding.
2. Discover the installed tools and their schemas. Use MCP `tools/list`, the
   conversation's hosted catalog, or `kast-tool-rpc-complete catalog` for the
   active transport. Keep its wrapper and canonical operation document distinct.
3. For direct MCP, call `health_check` and verify that its workspace root is the
   intended root. Then run one bounded declaration query. Tool registration and
   readiness alone do not establish a compiler-grounded answer.

Kast prepares its existing IDEA project when a semantic call needs it. Let the
installed operation own that preparation. Report a finite preparation blocker
with its operation identity; do not orchestrate IDE opening, repair, cache
invalidation, or forced synchronization to manufacture readiness.

## Follow the query and trace path

1. Resolve the seed with `SEARCH_DECLARATIONS`. Apply the known simple name,
   declaration kind, directory or package, and source sets before discovery.
   Select by `LOCATION` and `SIGNATURE`; do not trace all same-name matches.
   If the declaration position is already known, use `AT_LOCATION` within it.
2. Pass that declaration's unchanged issued `ref` in `SYMBOL_REFS`. For affected
   uses, apply one terminal `TRACE` with `SYMBOLS` output. Use `WORKSPACE` for
   clients across modules. Choose `SOURCE_DOMAIN` only when the question excludes
   other destinations. The seed's discovery scope does not restrict expansion.
3. Require the fixed `COMPLETE_ONLY` verdict. Read completion and coverage before
   answering. Use the trace example's elapsed grant and read the admitted budget.
   On `COMPLETION_UNPROVEN`, inspect its retained recovery evidence with
   `READ_RESULT`. A historical semantic checkpoint grants no execution.
   `RESUME` presents already-produced output. A larger requested timeout cannot remove host limits.

TRACE owns references, implementations or overrides, direct callers, and one
further caller layer. Its topology is fixed. Do not reconstruct that flow with
parallel branch queries, repeated TRACE calls, or an unbounded WALK. A narrow
one-hop question can use one `EXPAND_RELATION`; a requested graph depth can use
`WALK`.

Read [query-patterns.md](references/query-patterns.md) when choosing a source,
relation, traversal, or retained composition. The exact argument documents live
in generated [query-examples.json](references/query-examples.json).

Request only fields the task needs.
`SIGNATURE` distinguishes overloads; `SOURCE` reads a bounded committed source
window. Scope on discovery does not constrain later relation destinations.

Copy returned exact `ref` values unchanged into exact-symbol follow-up reads.
A name, path, candidate selector, result reference, or continuation cannot replace
that capability. `AT_LOCATION` finds the containing named declaration at a UTF-16
offset; it does not resolve the reference expression at that offset.

For an anchored occurrence or anonymous body, pass its issued `candidateSelector`
unchanged as `READ_SOURCE.candidateRef`; that capability is distinct from a symbol
`ref`. Consult the query patterns for candidate source reads, native relation
expansion scopes, and retained value-impact investigations.

## Preserve what the result proves

Read the canonical document's status, coverage, qualification, failures, and
omissions before answering. A complete empty result can establish absence only
within its requested scope. A qualified result preserves its returned facts;
unexamined matches or relations remain unknown. A rejected or unavailable call
does not establish a successful semantic result.

Keep these issued values in their owning operations:

| Value | Use |
| --- | --- |
| Exact symbol `ref` | Fresh exact-symbol query or exact-target change. |
| Issued `candidateSelector` | `READ_SOURCE.candidateRef` for its exact anchored source range. |
| Retained result reference | `RESULT` source, set/join right input, or `READ_RESULT`. |
| Issued row ID | Select a row from its owning retained result. |
| Issued output continuation | `RESUME` to present produced output without resending source or steps. |
| Result cursor | Page the same immutable retained result with `READ_RESULT`. |

Retained rows and continuations require the same semantic basis and available
owner state. If a build, edit, model refresh, expiry, or eviction invalidates
them, rerun the query sequence and reacquire its issued values. Fresh exact-symbol
reads may perform bounded revalidation; that does not refresh retained rows or
continuations. Difference and anti-join need complete right coverage to prove
absence. Preserve their typed rejection when that proof is missing.

For diagnostics, use `check_diagnostics` with the smallest relevant relative
path and retain its coverage and failures. Retrieved IDEA diagnostics do not
prove that the whole Gradle project builds.

## Apply an authorized change

Only use `add_declaration` or `replace_body` when the user's task authorizes that
source change, and honor the client's write-approval policy. First obtain and
inspect an exact target in this workspace. Pass the issued reference and the
complete intended Kotlin text through the installed tool schema.

`add_declaration` adds one declaration to an existing file. `replace_body`
requires a supported existing non-inline named function and one complete block
body including braces. These operations do not grant arbitrary ranges, new
files, or multi-file refactors. Use a verified returned `freshRef` for a
post-write follow-up when supplied.

Report verification and the receipt. For an uncertain write or lost response,
preserve its plan identity, phase, and recovery evidence. Inspect current source
and follow the reported recovery outcome; do not automatically replay the write.

An answer should state the exact workspace and scope, established facts, material
qualifications, and any next action the typed outcome requires.
