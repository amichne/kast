---
name: kast-task-access
description: Use for Kotlin declaration discovery, exact callers, reference occurrences, implementations, affected-use traces, source reads, and compiler diagnostics with the installed Kast runtime. Offers task commands instead of hand-written MCP queries. Use ordinary source tools for non-Kotlin files.
---

# Kast task access candidate

Use `uv run --locked --script <skill-directory>/access.py COMMAND` from the target Kotlin repository.
Replace `<skill-directory>` with this file's directory.
Use `--workspace PATH` when the current directory differs from the target repository.
The command uses the installed Kast tool RPC and its COMPLETE catalog.
It does not install, upgrade, or change the Kast plugin.

## Find before relationships

1. Find the declaration with its known directory, kind, and source sets.
2. Examine the returned owner, signature, receiver, and location.
3. Copy the exact reference for the intended declaration.
4. Use a relationship command with that reference.

```sh
uv run --locked --script <skill-directory>/access.py find QueryCompletionDocument --kind interface --directory protocol --source-set main
uv run --locked --script <skill-directory>/access.py callers --ref '<issued exact reference>'
uv run --locked --script <skill-directory>/access.py references --ref '<issued exact reference>' --directory cli --source-set main
```

Examples contain placeholders, not issued references. Never execute those placeholders.
`find` searches descendants unless you explicitly select `--shallow`.
`--kind interface` searches the CLASS family. Examine the returned declaration before an interface claim.
Exact names can match several overloads. Never expand all matches when the task identifies one overload.
If the task explicitly requests all overloads, preserve each target and its separate evidence.

**A cursor on a call selects the enclosing declaration through AT_LOCATION, not the callee.**
Do not use that location to answer callers of the callee.
Find the callee by name and select its exact owner and signature from source evidence.
For a function call, use `find NAME --kind function`.
The call site's directory does not establish the callee's declaration directory.
Use imports or declaration evidence before you restrict callee discovery to a module or source set.
If the callee's scope is unknown, start with workspace discovery and disclose the selected source sets.
If the evidence cannot distinguish overloads, report the ambiguity.

## Choose the question

| Command | Result |
|---|---|
| `callers`, `callees`, `implementations`, `inheritors`, `overrides` | One-hop declaration relationships |
| `references`, `type_uses` | Individual occurrence evidence |
| `trace` | Fixed affected-use topology, without an arbitrary depth |
| `walk callers --depth 3` | Every established hop through depth three, with traversal records |
| `source --ref REF --context 10` | The declaration anchor, with a bounded source window |
| `source --ref REF --region CALLABLE_BODY` | Callable body under the issued authority |
| `source --ref REF --region CALLABLE_BODY --context 10` | A bounded window, not complete source |
| `diagnostics path` | Compiler diagnostics for a relative file or directory |

Every relationship command needs `--ref`.
Destination scope uses `--directory` and `--source-set`. It is separate from the discovery scope.
An omitted destination scope covers the workspace.
Use `--production-only` or `--exclude-generated` only when the task excludes those destinations.
Three consecutive relation expansions return the third frontier, not all three hop levels. Use `walk` instead.

Start source reads with `source --ref REF --context 10`. Its default region is `ANCHOR`.
Examine that source before you select `CLASS_BODY` or `CALLABLE_BODY`.
A data class can have constructor properties without a class body.
`ENCLOSING_DECLARATION` selects a strict outer declaration. It does not mean the current declaration.
If a body region rejects with `region-absent`, use `ANCHOR` for the declaration itself.
State the changed selector and preserve the first rejection.

## Keep evidence and recover

The command returns the original RPC reply without a semantic summary.
Read both its outer outcome and the document's qualifications.
Location ranges contain UTF-16 offsets, not line numbers.
Use a line number only when source text or explicit line metadata establishes it.
An empty incomplete result does not prove absence.
`--page-size` limits presentation. It does not establish semantic completion.
`--bytes` needs at least 4096. A smaller requested answer needs a concise final response, not an invalid query budget.
Use `--save FILE` to keep the unchanged reply in a new file. Existing files reject before execution.

```sh
uv run --locked --script <skill-directory>/access.py result --ref '<issued result reference>' --output occurrences --cursor 20 --evidence-cursor 3
uv run --locked --script <skill-directory>/access.py resume --token '<issued output continuation>'
```

Copy both issued cursors and the original output kind for a retained result.
Only an issued output continuation belongs to `resume`.
Do not repeat RUN to recover missing output. Do not replace stale references silently.
Kast can save editor buffers and refresh the imported project before semantic reads.
If preparation rejects, report its exact reason and use source reads for qualified fallback evidence.
Cancellation or timeout can leave submission or effects uncertain. Never retry automatically.

## Advanced existing operations

Use `schema --definition NAME` to read one installed query definition.
Use `schema --tool TOOL` to read another tool's complete input schema.
For value flow, retained joins, detail selectors, or source changes, put the existing arguments in a JSON file.
Use `raw --tool TOOL --file PATH --emit-request` to examine schema admission without a semantic call.
Remove `--emit-request` to invoke the existing operation.
Catalog-declared writes also need `--write` and a user-requested source change.
Keep the original mutation receipt or failure. A missing response does not prove that no write occurred.

This candidate does not enforce issued-reference authority locally. Kast owns that decision.
Schema admission and request construction do not prove live semantic readiness or answer correctness.
