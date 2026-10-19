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
Inside native Pi `codemode`, the return value is an MCP CallToolResult. Its `structuredContent` is the Kast document. Its `content` repeats that document as text. Read `structuredContent` directly. Do not print the wrapper or both representations. Preserve invocation/error status separately from semantic readiness/completion status.

Inside `codemode`, decode and select before `text()` or `store()`. Keep exact refs, identities, signatures, paths, source line numbers, requested occurrences, scope, completion, omissions, truncation, and recovery evidence. Omit irrelevant metadata and source bodies. Do not drop required matches to save tokens. Compose dependent calls in one `codemode` execution when possible, selecting seeds from returned evidence; batch independent work. Stop when evidence answers the task.

Read source only to answer remaining questions or interpret evidence. Use supplied paths directly with bounded reads. If a path is unknown, find it within the known scope. Keep issued refs unchanged. Keep token families distinct. Use issued recovery handles under their contract. Preserve budget grants and COMPLETE_ONLY rejection/recovery evidence.

Unavailable tools, unready workspaces, unsupported scopes, or incomplete evidence justify scoped source fallback with explicit qualification. Unknown/incomplete evidence never proves absence. Do not retry unchanged failures.

## Keep intermediate reference results inside code

For a reference task, compose discovery, exact selection, expansion, and terminal projection in one `codemode` call. Keep intermediate documents and issued refs in local variables. Never call `text()` or `store()` for successful discovery or selection. Return only the requested terminal evidence and its qualifications. Do not return opaque refs when the answer needs files or names.

Replace the example name and package with the task inputs. Keep the workspace expansion scope. Adapt the final projection to the requested answer. For overloads, selection also needs the exact receiver and signature.

```javascript
// reference-chain-example
const query = request => tools.mcp__kast__query_symbols({request});
const stop = (stage, reply) => text({stage, isError:reply.isError === true,
  status:reply.structuredContent?.status,
  failure:reply.structuredContent?.failure,
  invocation:reply.structuredContent?.invocation,
  evidence_window:reply.structuredContent?.evidence_window,
  coverage:reply.structuredContent?.coverage,
  retention:reply.structuredContent?.retention});
const complete = r => r.isError !== true && r.structuredContent?.status === "complete"
  && r.structuredContent.coverage?.exhaustive === true
  && r.structuredContent.invocation?.stop?.type === "COMPLETED"
  && r.structuredContent.invocation?.preview?.type === "INLINE"
  && r.structuredContent.evidence_window?.type === "FINAL"
  && Array.isArray(r.structuredContent.items)
  && r.structuredContent.invocation.preview.row_count === r.structuredContent.items.length;
const found = await query({type:"RUN",source:{type:"SEARCH_DECLARATIONS",
  declarationName:"ExampleType",declarationKinds:["CLASS"],
  scope:{type:"PACKAGE",packageName:"com.example",includeSubpackages:false,sourceSetNames:["main"]}},
  output:{type:"SYMBOLS",fields:["NAME","LOCATION","SIGNATURE"]}});
if (!complete(found)) { stop("DISCOVERY_STOP",found); return; }
const seeds = found.structuredContent.items.filter(x => x.type === "exact-symbol"
  && x.signature?.qualifiedIdentity === "com.example.ExampleType");
if (seeds.length !== 1) {
  text({stage:"SELECTION_STOP",count:seeds.length,
    candidates:found.structuredContent.items.map(x => ({name:x.name,location:x.location,signature:x.signature}))});
  return;
}
const seed = seeds[0];
const expanded = await query({type:"RUN",source:{type:"SYMBOL_REFS",symbolRefs:[seed.ref]},
  steps:[{type:"EXPAND_RELATION",relation:"REFERENCES",expansionScope:{type:"WORKSPACE"}}],
  output:{type:"OCCURRENCES"}});
if (!complete(expanded)) { stop("REFERENCES_STOP",expanded); return; }
const d = expanded.structuredContent;
if (d.items.some(x => x.type !== "reference-occurrence"
    || x.occurrence?.target?.selector !== seed.ref || !x.occurrence?.occurrence)) {
  text({stage:"SHAPE_STOP",status:d.status}); return;
}
text({stage:"REFERENCES",status:d.status,coverage:d.coverage,
  invocation:d.invocation.stop,evidence_window:d.evidence_window,live:d.live,
  target:seed.signature.qualifiedIdentity,
  relation_observations:d.relation_observations.map(({subject,...qualification}) => qualification),
  occurrences:d.items.map(x => ({file:x.occurrence.occurrence.file,
    range:x.occurrence.occurrence.range,context:x.occurrence.context,
    provenance:x.occurrence.provenance,coverage:x.occurrence.coverage}))});
```

If a result supplies a continuation, use its documented contract inside code before a terminal answer. Preserve the issued handle and recovery evidence. Never replace incomplete output with an empty answer.

Relation metadata repeats the opaque seed handle in `subject.token`. Project its qualified identity instead. Keep the remaining relation qualifications.

For a file-list answer, apply the task's import and declaring-file exclusions inside code. Return all distinct requested files, with completion and scope evidence. Do not enumerate the whole repository to reinterpret known occurrence paths. Ambiguity and failures need a bounded terminal stop with enough evidence for the next decision.
