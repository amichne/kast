# Kast

Use reachable Kast tools first for supported Kotlin semantic tasks in this exact workspace.
Use `query_symbols` for identity, callers, references, implementations, and affected uses; `check_diagnostics` for compiler diagnostics.
Read source for unresolved questions. Reuse returned or listed paths.

Do not enumerate the repository for supplied paths.
Find unknown paths within the known scope before reads.
Apply the task's source-set and path exclusions before each read batch.
Remove duplicate paths and reuse unchanged source reads.
Discard cached source evidence after a source change or freshness rejection.

Batch independent reads within the output and remaining tool allowances.
Keep needed evidence, original line numbers, and qualifications.
Tasks without a Kotlin semantic question need no Kast call.

Chain discovery, exact selection, and reference expansion inside one supported host code call.
Keep intermediate results inside it.
Expose task findings with scope, completion, omissions, and recovery facts.
Keep requested matches and failures. Ambiguity needs a qualified stop, never the first overload.
Stop when evidence answers the task.

In Pi `codemode`, filter large structured results before `text()` or `store()`.
Use local variables for one chain and `store()`/`load()` for bounded reuse across calls.
Keep needed issued references with their identity, freshness, and completion evidence.

Read the host's result shape before field access.
Compact and canonical rows can differ. Use `verbose:true` for canonical rows.
Failed or truncated inventories grant no path or count claims.

A complete relation proves only its semantics and scope.
Before a complete source-use answer, resolve imports, companion qualifiers, and unmatched sites too.

Use an available schema directly; otherwise discover the tool once through the enabled host surface.
Obey the installed schema. Read only the needed section or named example for unfamiliar shapes.

In Pi, obey the `mcp_servers` section.
For `codemode` exposure, use `searchTools` and `describeTool` inside `codemode`.
For deferred exposure, use `tool_search`.
Disabled host extensions can prevent tool access.

Preparation can save editor buffers and refresh the imported project.
Registration and schema admission do not prove semantic readiness.
Use the first useful operation, not a readiness probe.
If tools, readiness, or scope block a query, use ordinary reads and state the limitation.

Do not repeat discovery or failures without new evidence or changed prerequisites.
Incomplete results and scope qualifications do not prove absence. Preserve them.
Public tool contract: `{{CONTRACT_VERSION}}`. The installed schema governs.

`RUN` needs `source`, optional transformation `steps`, and one non-null `terminalStep` object tagged `EMIT` or `TRACE`.
Use `terminalStep.output`, not `RUN.output`. `EMIT` accepts all outputs; `TRACE` accepts only `SYMBOLS` and owns `expansionScope` directly.
Never put `TRACE` in `steps` or add `traceOptions`. Allow at most 1000 steps for EMIT, 999 before TRACE.
Omitted or null output selects `NAME` and `LOCATION`; explicit `SYMBOLS` requires `fields`, with `[]` for identity and kind only.
Keep retention, mode, and budget on RUN. Other actions have no terminal; `READ_RESULT.output` stays unchanged.

Minimal interface query:

```json
{{INTERFACE_QUERY}}
```

- `declarationName` is a simple name. `nameMatch` accepts `{{NAME_MATCH}}`. Omission means case-sensitive `EXACT`.
- `declarationKinds`: `{{DECLARATION_KINDS}}`. `CLASS` includes interfaces, objects, `enum` and annotation classes. `INTERFACE` is invalid.
- For a known directory, use `scope.type=DIRECTORY` with workspace-relative `relativeDirectoryPath`. Use `.` for the root.
- For a known package, use `scope.type=PACKAGE` with dotted `packageName`, independent of its directory.
- Directory paths exclude absolute paths, files, globs, and parent traversal. Descendant inclusion defaults to `true`.
- `sourceSetNames`: exact imported Gradle names, such as `main`, `test`, or `commonMain`. Default: `main` and `test`.
- Apply known name, kind, scope, and source-set constraints before discovery. Empty or duplicate kind and source-set arrays reject.
- For `SYMBOLS`, `fields` selects `{{SYMBOL_FIELDS}}` without filtering eligible declarations.
- Select exact owner, receiver, location, and signature. Reuse the issued `ref` unchanged in `source.type=SYMBOL_REFS`, `symbolRefs:[ref]`.
- Use a relation for direct callers or references; terminal `TRACE` adds implementations and another caller layer.
- Discovery scope selects seeds; expansion scope selects destinations. `WORKSPACE` includes clients across modules and is the TRACE default.
- Relation enums are `{{RELATIONS}}`. Read the schema before other steps or output variants.
- The host can reduce the numeric `executionBudget` grant. Preserve `COMPLETE_ONLY` rejection and recovery evidence.
- `retention=RETAIN` keeps evidence for `READ_RESULT` with an issued result reference and cursors.
- `RESUME` presents produced output using an issued `query-output:v1` token. `READ_SOURCE.candidateRef` accepts issued candidates, exact symbols, or source selectors; `region` and `text` are separate choices.
- Never invent references, exchange token families, or resubmit a query to recover lost output.
- Diagnostic paths are workspace-relative. Changes need the user's request and an issued `exactTarget`.

Use supplied schema and example paths on demand. Placeholders grant no source access or semantic authority.
