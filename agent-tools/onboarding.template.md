# Kast

Use Kast for compiler-grounded Kotlin declarations, relationships, source, and diagnostics in this exact workspace.
Discover the installed `query_symbols` tool and obey its schema before a call.
Kast prepares the existing IntelliJ IDEA project. Preparation can save editor buffers and refresh the imported project.
Tool registration and schema admission do not prove semantic readiness.
Public tool contract: `{{CONTRACT_VERSION}}`. If schemas differ, use the installed tool schema.

Minimal interface query:

```json
{{INTERFACE_QUERY}}
```

- Start with `request.type=RUN` and `request.source.type=SEARCH_DECLARATIONS`.
- `declarationName` is a simple name, such as `OrderRepository`. `nameMatch` is an enum: `{{NAME_MATCH}}`. Omission means case-sensitive `EXACT`.
- `declarationKinds` is an array of `{{DECLARATION_KINDS}}`. Use `CLASS` for interfaces, classes, objects, enum classes, and annotation classes. `INTERFACE` is invalid.
- Prefer `scope.type=DIRECTORY` for a known module or source directory. `relativeDirectoryPath` is workspace-relative. Use `.` for the root.
- Prefer `scope.type=PACKAGE` for a known Kotlin package. `packageName` uses dots, such as `com.example.orders`. A package is independent of its directory.
- Directory paths cannot be absolute paths, files, globs, or parent traversal. `includeSubdirectories` and `includeSubpackages` are booleans with default `true`.
- `sourceSetNames` is an array of exact imported Gradle names, such as `main`, `test`, or `commonMain`. Omission selects `main` and `test`.
- Apply known name, kind, scope, and source-set constraints before discovery. Empty and duplicate kind or source-set arrays reject.
- `output.type=SYMBOLS` accepts `fields` from `{{SYMBOL_FIELDS}}`. Fields select returned content, not eligible declarations.
- Select the declaration by location and signature. Copy its issued `ref` unchanged into `source.type=SYMBOL_REFS`, with `symbolRefs:[ref]`.
- Use one terminal `TRACE` for affected uses. `expansionScope.type=WORKSPACE` includes clients across modules. Discovery scope only selects seeds.
- Relation enums are `{{RELATIONS}}`. Read the schema before other steps or output variants.
- `executionBudget` uses numeric limits. The host can reduce the grant. Preserve `COMPLETE_ONLY` rejection and recovery evidence.
- `retention=RETAIN` keeps result evidence for later reads. `READ_RESULT` uses an issued result reference and cursors.
- `RESUME` only presents produced output with an issued `query-output:v1` token. `READ_SOURCE` needs an issued candidate selector.
- Never invent references, exchange token families, or resubmit a query to recover lost output.
- `check_diagnostics.relativePath` is a workspace-relative file or directory. Changes need the user's request and an issued `exactTarget`.

Read the absolute reference paths supplied with this brief for the full schema and a JSON array of named examples.
Examples teach argument shapes. Placeholder references do not grant source access or semantic authority.
