# Kast reference tasks

Use native Kast first. Inside one Pi `codemode` call, discover the declaration, select its exact identity, expand references, review the requested source scope, and project the terminal answer. Keep successful intermediate results in local variables. Never `text()` or `store()` those results. Do not discover tools or read full references for these known contract-14 shapes.

```javascript
const query = request => tools.mcp__kast__query_symbols({request,verbose:true});
const found = await query({type:"RUN",source:{type:"SEARCH_DECLARATIONS",
  declarationName:"<simpleName>",declarationKinds:["CLASS"],
  scope:{type:"PACKAGE",packageName:"<knownPackage>",includeSubpackages:false,sourceSetNames:["main"]}},
  output:{type:"SYMBOLS",fields:["NAME","LOCATION","SIGNATURE"]}});
// Select the issued ref inside code, then expand it without a model turn.
const end = await query({type:"RUN",source:{type:"SYMBOL_REFS",symbolRefs:[issuedRef]},
  steps:[{type:"EXPAND_RELATION",relation:"REFERENCES",expansionScope:{type:"WORKSPACE"}}],
  output:{type:"OCCURRENCES"}});
```

Replace placeholders with task facts. `CLASS` includes interfaces and objects. Use `FUNCTION` or `PROPERTY` for those declarations. A native result is a CallToolResult. Its `structuredContent` is the Kast document. `verbose:true` selects canonical rows inside code. Default compact rows can use a shared relation graph.

Select the exact qualified identity, location, receiver, and signature. Never select the first overload. Keep its issued ref unchanged. Handle MCP errors separately from semantic status. Incomplete coverage, nonfinal output, stale references, and ambiguous selection need bounded qualified stops or issued recovery. They never prove an empty answer. Use issued continuations under their schema.

For a source-use question, also collect scoped whole-word candidates inside code. A complete class-reference relation does not prove coverage of every class-name token. Examine imports, companion qualifiers, and unmatched source sites. Resolve their meaning before a complete answer. Keep source work internal unless unresolved evidence needs a model decision.

Apply the task's import, source-set, generated-file, test-file, and declaring-file exclusions inside code. Return all requested distinct files or occurrences with scope and completion evidence. Preserve original ranges, omissions, failures, and recovery facts. Omit opaque seed handles, including copies in relation metadata. Use supplied paths directly. Stop when the evidence answers the task.
