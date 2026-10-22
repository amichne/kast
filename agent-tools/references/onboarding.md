# Kast

For Kotlin code understanding in this exact workspace, use Kast as the first semantic step when its tools are reachable and support the task.
Use `query_symbols` for declaration identity, callers, references, implementations, and affected uses; use `check_diagnostics` for compiler diagnostics.
Read source to interpret evidence and answer unresolved questions.
Reuse paths from returned evidence or file listings.

Do not enumerate the repository for supplied paths.
Find unknown paths within the known scope before reads.
Apply the task's source-set and path exclusions before each read batch.
Remove duplicate paths and reuse unchanged source reads.
Discard cached source evidence after a source change or freshness rejection.

Batch independent reads with bounded output and the needed evidence.
Keep the read batch within the remaining tool allowance.
Keep original source line numbers and evidence qualifications.
Tasks without a Kotlin semantic question need no Kast call.

In a supported host, chain discovery, exact selection, and reference expansion in one code call.
Keep successful intermediate results inside that call.
Expose task findings with scope, completion, omissions, and recovery facts.
Do not omit requested matches or hide failures to reduce tokens.
Ambiguous selection needs a qualified stop, never the first overload.
Stop when evidence answers the task.

In Pi `codemode`, filter large structured results before `text()` or `store()`.
Use local variables for one chain and `store()`/`load()` for bounded reuse across calls.
Keep needed issued references with their identity, freshness, and completion evidence.

Read the host's declared result shape before field access.
Compact rows and canonical rows can differ.
Use `verbose:true` when code needs canonical rows.
Failed or truncated inventories grant no path or count claims.

A complete relation answers its declared semantics and scope.
Source-use questions can also need inspection of imports, companion qualifiers, and unmatched source sites.
Resolve those sites before a complete source-use answer.

If the needed tool schema is already available, use it directly.
Otherwise, discover the needed Kast tool once through the host's enabled discovery surface.
Obey its installed schema.
Read only the needed schema section or named example for unfamiliar shapes.

In Pi, obey the `mcp_servers` section.
For `codemode` exposure, use `searchTools` and `describeTool` inside `codemode`.
For deferred exposure, use `tool_search`.
Disabled host extensions can prevent tool access.

Preparation can save editor buffers and refresh the imported project.
Registration and schema admission do not prove semantic readiness.
Let the first useful operation establish readiness without a probe call.
If tools, readiness, or scope prevent a query, use ordinary reads and state the limitation.

Do not repeat discovery or failed requests without new evidence or a changed prerequisite.
Preserve incomplete results and scope qualifications; they do not prove that no callers or references exist.
Public tool contract: `16`. If schemas differ, use the installed tool schema.

Minimal interface query:

```json
{"request":{"type":"RUN","source":{"type":"SEARCH_DECLARATIONS","declarationName":"OrderRepository","declarationKinds":["CLASS"],"scope":{"type":"PACKAGE","packageName":"com.example.orders","includeSubpackages":false,"sourceSetNames":["main"]}},"output":{"type":"SYMBOLS","fields":["NAME","LOCATION","SIGNATURE"]},"executionBudget":{"maxResults":20}}}
```

- `declarationName` is a simple name. `nameMatch` accepts `EXACT|FUZZY`. Omission means case-sensitive `EXACT`.
- `declarationKinds` accepts `CLASS|FUNCTION|PROPERTY|TYPE_ALIAS`. Use `CLASS` for interfaces, objects, `enum` classes, annotation classes, and other classes. `INTERFACE` is invalid.
- For a known directory, use `scope.type=DIRECTORY` with workspace-relative `relativeDirectoryPath`. Use `.` for the root.
- For a known package, use `scope.type=PACKAGE` with dotted `packageName`, independent of its directory.
- Directory paths exclude absolute paths, files, globs, and parent traversal. Descendant inclusion defaults to `true`.
- `sourceSetNames` accepts exact imported Gradle names, such as `main`, `test`, or `commonMain`. Omission selects `main` and `test`.
- Apply known name, kind, scope, and source-set constraints before discovery. Empty or duplicate kind and source-set arrays reject.
- For `SYMBOLS`, `fields` selects `NAME|LOCATION|SIGNATURE|SOURCE` without filtering eligible declarations.
- Select the exact owner, receiver, location, and signature. Copy its issued `ref` unchanged into `source.type=SYMBOL_REFS`, with `symbolRefs:[ref]`.
- For direct callers or references, use the matching relation. Use terminal `TRACE` when the task needs wider affected uses, including implementations and another caller layer.
- Discovery scope selects seeds. Expansion scope selects destinations. `expansionScope.type=WORKSPACE` includes clients across modules.
- Relation enums are `REFERENCES|CALLERS|CALLEES|IMPLEMENTATIONS|INHERITORS|OVERRIDES|TYPE_USES`. Read the schema before other steps or output variants.
- The host can reduce the numeric `executionBudget` grant. Preserve `COMPLETE_ONLY` rejection and recovery evidence.
- `retention=RETAIN` keeps evidence for `READ_RESULT` with an issued result reference and cursors.
- `RESUME` only presents produced output with an issued `query-output:v1` token. `READ_SOURCE` accepts an issued candidate, exact symbol, or source selector through `candidateRef`, with separate `region` and `text` choices.
- Never invent references, exchange token families, or resubmit a query to recover lost output.
- Diagnostic paths are workspace-relative. Changes need the user's request and an issued `exactTarget`.

The supplied paths name the schema and examples for on-demand reads.
Placeholder references grant no source access or semantic authority.
