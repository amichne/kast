# Kast: compact semantic workflow

Use Kast first for Kotlin declaration discovery, references, callers, implementations, and impact. Work without a Kotlin semantic question needs no Kast call. Obey the worker's answer convention; every final answer must be independently correct against exact source evidence.

## Direct native calls
For these known contract-14 shapes, call `tools.mcp__kast__query_symbols` directly inside Pi `codemode`. Do not discover, describe, or read full schemas/examples first. Registration is not semantic readiness: let the first useful query establish readiness, without a separate probe. If the installed schema differs, obey it; read only the needed schema section once for unfamiliar operations.

Search template (replace placeholders with task constraints):
```javascript
await tools.mcp__kast__query_symbols({request:{
  type:"RUN",
  source:{type:"SEARCH_DECLARATIONS",declarationName:"<simpleName>",
    declarationKinds:["CLASS"],
    scope:{type:"PACKAGE",packageName:"<package>",
      includeSubpackages:false,sourceSetNames:["main"]}},
  output:{type:"SYMBOLS",fields:["NAME","LOCATION","SIGNATURE"]},
  executionBudget:{maxResults:50}
}})
```
Apply known name, kind, scope, and source-set constraints before the search. Use FUNCTION for methods. Use CLASS for interfaces, classes, and objects. DIRECTORY scope uses workspace-relative `relativeDirectoryPath` and `includeSubdirectories`. Use exact imported source-set names. Production scope selects main only. Select exact owner/receiver, location, and signature, never simple name alone.

Relations reuse the issued ref unchanged:
```javascript
await tools.mcp__kast__query_symbols({request:{
  type:"RUN",source:{type:"SYMBOL_REFS",symbolRefs:[unchangedIssuedRef]},
  steps:[{type:"EXPAND_RELATION",relation:"REFERENCES",
    expansionScope:{type:"WORKSPACE"}}],
  output:{type:"OCCURRENCES"}
}})
```
Other relations: `CALLERS`, `IMPLEMENTATIONS`, `TYPE_USES`, `OVERRIDES`, `INHERITORS`, `CALLEES`. SYMBOLS output serves destination declaration names/locations. Discovery scope selects seeds, not expansion scope; preserve all required clients. For wider impact, examine the needed TRACE schema section.

## Keep evidence, reduce transport
Inside Pi `codemode`, a native MCP call returns a CallToolResult. Its `structuredContent` is the Kast document. Its `content` repeats that document as text. Read `structuredContent` directly. Preserve invocation/error status separately from semantic readiness/completion status.

Inside `codemode`, decode and select before `text()` or `store()`. Keep exact refs, identities, signatures, paths, source line numbers, requested occurrences, scope, completion, omissions, truncation, and recovery evidence. Omit irrelevant metadata and source bodies. Do not drop required matches to save tokens. Compose dependent calls in one `codemode` execution when possible, selecting seeds from returned evidence; batch independent work. Stop when evidence answers the task.

Read source only to answer remaining questions or interpret evidence. Use supplied paths directly with bounded reads. If a path is unknown, find it within the known scope. Keep issued refs unchanged. Keep token families distinct. Use issued recovery handles under their contract. Preserve budget grants and COMPLETE_ONLY rejection/recovery evidence.

Unavailable tools, unready workspaces, unsupported scopes, or incomplete evidence justify scoped source fallback with explicit qualification. Unknown/incomplete evidence never proves absence. Do not retry unchanged failures.

## Reference chains

Compose discovery, exact selection, expansion, source-scope review, and final projection inside one `codemode` call. Keep successful intermediate results and issued refs in local variables. Do not call `text()` or `store()` for those results. Return the requested terminal values with scope, completion, omissions, and recovery evidence. Stop with bounded ambiguity or failure evidence when code cannot decide.

For programmatic reference processing, use `verbose:true` on the native MCP call. This selects canonical rows inside code. Default compact output can use `reference-edge` rows and a `relation_graph` with shared nodes and witnesses. Neither representation changes semantic scope. Never assume that a compact row contains an inline occurrence. Do not expose those tables to the model merely to decode them.

A complete relation proves its declared scope. It does not prove that every source use of a class name resolves directly to that class declaration. Class qualifiers can involve companion members. If the task asks all source uses, collect independent whole-word candidates within the declared source scope inside code. Examine imports, receiver identity, and source for unmatched sites. Keep that work internal. Do not discard a qualified member use solely because a class-reference relation omits it. If the evidence remains incomplete, emit a qualified stop instead of a complete file list.

For a file-list answer, apply the task's import and declaring-file exclusions inside code. Return all distinct requested files. Omit opaque seed handles from successful output, including copies in relation metadata. Preserve the selected qualified identity and all relation qualifications. Read only the source facts that remain necessary. Do not enumerate the repository to interpret known occurrence paths.
