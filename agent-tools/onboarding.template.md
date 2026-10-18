# Kast

For Kotlin code understanding in this exact workspace, use Kast as the first semantic step when its tools are reachable and support the task.
Use `query_symbols` for declaration identity, callers, references, implementations, and affected uses; use `check_diagnostics` for compiler diagnostics.
Read files for task context, implementation details, and to interpret the returned evidence.
For source reads, reuse paths from returned evidence or file listings.
If a needed path is unknown, find it within the known scope before reading.
Batch independent source reads with bounded output that keeps the evidence needed for the answer.
In Pi `codemode`, filter large metadata before `store()`. Keep only small identifiers or summaries.
Documentation, configuration, shell work, and other tasks without a Kotlin semantic question need no Kast call.

If the needed tool schema is already available, use it directly.
Otherwise, discover the needed Kast tool once through the host's enabled tool discovery surface and obey its installed schema.
In Pi, follow the `mcp_servers` section: `codemode` exposure uses `searchTools` and `describeTool` inside `codemode`; deferred exposure uses `tool_search`.
Registration alone does not make a tool reachable when those host extensions are disabled.
Kast prepares the existing IntelliJ IDEA project. Preparation can save editor buffers and refresh the imported project.
Tool registration and schema admission do not prove semantic readiness.
Let the first useful operation establish readiness; do not add a probe call solely to satisfy this guidance.
If tools are unavailable, the workspace is not ready, or the requested scope is unsupported, continue with ordinary reading and state the limitation.
Do not repeat discovery or retry the same failed request without new evidence or a changed prerequisite.
Preserve incomplete results and scope qualifications; they do not prove that no callers or references exist.
Public tool contract: `{{CONTRACT_VERSION}}`. If schemas differ, use the installed tool schema.

Minimal interface query:

```json
{{INTERFACE_QUERY}}
```

- Start with `request.type=RUN` and `request.source.type=SEARCH_DECLARATIONS`.
- `declarationName` is a simple name, such as `OrderRepository`. `nameMatch` is an `enum`: `{{NAME_MATCH}}`. Omission means case-sensitive `EXACT`.
- `declarationKinds` is an array of `{{DECLARATION_KINDS}}`. Use `CLASS` for interfaces, classes, objects, `enum` classes, and annotation classes. `INTERFACE` is invalid.
- Prefer `scope.type=DIRECTORY` for a known module or source directory. `relativeDirectoryPath` is workspace-relative. Use `.` for the root.
- Prefer `scope.type=PACKAGE` for a known Kotlin package. `packageName` uses dots, such as `com.example.orders`. A package is independent of its directory.
- Directory paths cannot be absolute paths, files, globs, or parent traversal. `includeSubdirectories` and `includeSubpackages` are booleans with default `true`.
- `sourceSetNames` is an array of exact imported Gradle names, such as `main`, `test`, or `commonMain`. Omission selects `main` and `test`.
- Apply known name, kind, scope, and source-set constraints before discovery. Empty and duplicate kind or source-set arrays reject.
- `output.type=SYMBOLS` accepts `fields` from `{{SYMBOL_FIELDS}}`. Fields select returned content, not eligible declarations.
- Select the declaration by location and signature. Copy its issued `ref` unchanged into `source.type=SYMBOL_REFS`, with `symbolRefs:[ref]`.
- For direct callers or references, use the matching relation. Use terminal `TRACE` when the task needs wider affected uses, including implementations and another caller layer.
- Keep expansion within the task's needed scope. `expansionScope.type=WORKSPACE` includes clients across modules. Discovery scope only selects seeds.
- Relation enums are `{{RELATIONS}}`. Read the schema before other steps or output variants.
- `executionBudget` uses numeric limits. The host can reduce the grant. Preserve `COMPLETE_ONLY` rejection and recovery evidence.
- `retention=RETAIN` keeps result evidence for later reads. `READ_RESULT` uses an issued result reference and cursors.
- `RESUME` only presents produced output with an issued `query-output:v1` token. `READ_SOURCE` needs an issued candidate selector.
- Never invent references, exchange token families, or resubmit a query to recover lost output.
- `check_diagnostics.relativePath` is a workspace-relative file or directory. Changes need the user's request and an issued `exactTarget`.

Read the absolute reference paths supplied with this brief for the full schema and a JSON array of named examples.
Examples teach argument shapes. Placeholder references do not grant source access or semantic authority.
