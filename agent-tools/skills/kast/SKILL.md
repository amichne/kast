---
name: kast
description: Use Kast to prove claims about Kotlin or Java code in a Gradle workspace from the compiler model. Use it to prove that something is absent ("nothing in module A uses B", "no test calls this"), to combine exact sets of declarations (callers of both X and Y, public API without callers), to measure the blast radius before an edit and compile-check the affected files after it without a build, and to trace where a produced value flows. Use it when the answer depends on overload or call resolution. Skip it when reading or searching known source answers the question.
---

# Kast

Text search finds text. It cannot select between overloads, and it cannot prove that something is absent.
Kast answers from the IntelliJ compiler model of one Gradle workspace.
It labels every answer as complete or qualified. A complete answer is a proof within the static compiler model.
Use Kast for claims that text search cannot prove. A file read stays cheaper for everything else.

The first query trusts the workspace build code and prepares a private Host. Preparation can take minutes.
Later calls reuse that Host. Use Kast only in a workspace whose build code the user trusts.

## What only Kast can do

Choose the strongest capability that answers the task:

1. **Prove absence.** A complete, empty result proves that no match exists in the stated scope.
   Text search can show presence. It cannot prove absence.
2. **Combine exact sets.** Keep results with `--retain` and combine them by compiler identity.
   Use intersection, union, difference, semi joins, and anti joins. Overloads and same-named declarations stay distinct.
3. **Map an edit, then verify it.** `TRACE` lists the affected declarations and files before the edit.
   After the edit, `diagnostic check` compiles exactly those files without a Gradle build.
4. **Follow values.** `IMPACT` follows a produced value forward and records each boundary that it cannot resolve.
5. **Separate stored lambdas.** Kast separates a call that runs in place from a lambda that later code invokes.

## Decide first

Use Kast when the claim depends on resolution or on completeness:

- which overload or same-named declaration a name or call selects
- callers, callees, implementations, overrides, or references outside one file
- "none", "never", "only", "all", or "unused" in the claim
- compound questions over two or more relationships
- the affected surface of an edit, and its compiler diagnostics afterward
- any answer that depends on input outside the tracked files, such as a binary dependency's declarations or the projects that the build imports

Source text alone cannot decide that last kind of question. Query Kast before you report that the evidence is insufficient.

Skip Kast for file content, a literal, or an edit in a known file. A Kast call costs more tokens than a read.

## Set up the verdict filter

Run commands from the workspace root, or add `--workspace /absolute/root`. Set this filter once:

```sh
verdict='{type, status: .document.status, exhaustive: .document.coverage.exhaustive,
  failures: .document.original_evidence_summary.failure_count,
  omissions: .document.original_evidence_summary.omission_count,
  total: (.document.row_window.total // (.document.items | length)),
  rows: .document.row_window.type, next_cursor: .document.next_cursor,
  result: .document.retention.reference,
  items: [.document.items[]? |
    if .type == "exact-symbol" then {name, signature, ref, file: .location.file} else . end]}'
```

For a retained set, project the verdict without its rows:

```sh
handle='{type, status: .document.status, exhaustive: .document.coverage.exhaustive,
  failures: .document.original_evidence_summary.failure_count,
  omissions: .document.original_evidence_summary.omission_count,
  result: .document.retention.reference}'
```

A result is **complete** only when `type` and `status` are `complete`, `exhaustive` is `true`, and both counts are `0`.
Match the strength of each claim to the verdict:

| Verdict | Claim |
| --- | --- |
| Complete with rows | "Exactly these, in this scope." |
| Complete and empty | "None exist in this scope, under static compiler resolution." |
| Qualified or rejected | "At least these." Report the omissions and failures. Never claim absence. |

`rows` set to `MORE` means that more rows exist. The query still reports `complete`.
`total` counts every row of the query, not only this page. Do not read pages to count rows.
Read the next page with `kast result read`. Use the original output kind for every page.
Never repeat a query to recover output.

## Select exactly

Put each known constraint on the first call:

```sh
kast query --name OrderService --in services/orders --source-set main --kind class \
  --fields name,signature,location --jq "$verdict"
```

Select one row by its file and signature, then copy its `ref` unchanged.
A name can match overloads and declarations in other packages. Never take the first row when several rows match.
For a position in a file, use `--file PATH --offset N`. The offset counts UTF-16 code units.
Copy `N` from the `range` of a Kast result. Do not compute it from the file's bytes.

File paths in results are relative to the workspace root, which `live.root` names. Pass them to `--file` and `--in` unchanged.
An absolute path is a file outside the workspace, such as a library.

Follow relations from an exact declaration. Each `--relation` adds one hop, in the order given:

```sh
kast query --ref "$ref" --relation callers --jq "$verdict"
kast query --ref "$ref" --relation implementations --relation callers --jq "$verdict"
```

Relations are `references`, `callers`, `callees`, `implementations`, `inheritors`, `overrides`, and `type-uses`.

## Build a working set

Add `--retain` to keep a result for later stages. Record each `result` reference with the question that it answers.
A retained result holds every row of the investigation, not only the presented page.
It is immutable. Later stages reuse it without semantic work.

Start a pipeline from a retained result with `--from`, or enumerate a scope with `--all`:

```sh
kast query --all --kind function --in services/orders --source-set main --visibility public \
  --retain --jq "$handle"
```

Pipeline flags apply in the order that you write them:

| Flag | Stage | Keeps |
| --- | --- | --- |
| `--relation R` | one relation hop | the related declarations |
| `--visibility public,internal` | visibility filter | the declarations with these visibilities |
| `--intersect RESULT` | intersection | the declarations also in `RESULT` |
| `--union RESULT` | union | the declarations from either side |
| `--minus RESULT` | difference | the declarations not in `RESULT` |
| `--with RESULT` | semi join | the left rows with a match in `RESULT` |
| `--without RESULT` | anti join | the left rows with no match in `RESULT` |

`--minus` and `--without` need complete coverage of `RESULT`. A qualified right side rejects the stage.
Confirm that each right side is complete before you use it.
A retention can fail with a capacity rejection. Narrow the scope, then run the query again.

Each `--relation` hop searches the whole workspace by default.
Restrict its destinations with `--relation-in DIR` and `--relation-source-set NAME`.
Add `--relation-production-only` or `--relation-exclude-generated` to narrow them further.
These flags apply to every `--relation` hop and to `--terminal trace`.
Kast applies them before enumeration, so a restricted hop also costs less.
Without `--relation-source-set`, destinations include `main` and `test`.

Retained results and refs belong to the current Host and its read basis.
They expire after 10 minutes by default.
After a source edit or a Host transition, expect a basis or authority rejection. Select again on the new basis.

## Recipes

Each recipe states its claim. Report the claim only when every result in the recipe is complete.

**Callers of both `A` and `B`:**

```sh
# Save .result as callersA.
kast query --ref "$A" --relation callers --retain --jq "$handle"
kast query --ref "$B" --relation callers --intersect "$callersA" --jq "$verdict"
```

**A module boundary: nothing in `app/` uses `legacy/`:**

Seed every declaration kind in `legacy/`. Do not add `--kind`.
`callers` on a class finds its constructions, so a function-only seed misses `LegacyClient()`.

Read occurrences, not symbols. A symbol row needs a declaration owner, so an import-only use has no symbol row.
Project each use site with this filter:

```sh
uses='{type, status: .document.status, exhaustive: .document.coverage.exhaustive,
  failures: .document.original_evidence_summary.failure_count,
  omissions: .document.original_evidence_summary.omission_count,
  total: (.document.row_window.total // (.document.items | length)),
  rows: .document.row_window.type, next_cursor: .document.next_cursor,
  result: .document.retention.reference,
  items: [.document.items[]? | {target: .occurrence.target.qualifiedIdentity,
    file: .occurrence.occurrence.file, context: .occurrence.context}]}'
```

```sh
kast query --all --in legacy --source-set main --relation references \
  --relation-in app --relation-source-set main --output occurrences --retain --jq "$uses"
```

If `rows` is `MORE`, use the returned `result` and `next_cursor` to read the next page.

```sh
kast result read --ref "$result" --cursor "$next_cursor" --output occurrences --jq "$uses"
```

Each row names the `legacy/` target, the `app/` file, and the context: `CODE`, `TYPE`, or `IMPORT`.
A complete, empty result proves that no `main` code in `app/` references a declaration from `legacy/`.
That claim covers calls, constructions, imports, property reads, and type uses.
`--all` includes Java classes and methods. A Java field, enum, record, or annotation type in `legacy/` leaves its kinds unproven.
For calls and constructions only, use `--relation callers`. Its symbol rows name the calling declarations.
The seed covers classes, functions, properties, and type aliases. Constructors count through their class.
Reflection, dependency injection, and other uses outside static resolution stay outside the claim.

**Public functions that no test calls directly:**

```sh
# Save .result as tested.
kast query --all --kind function --in services/orders --source-set test \
  --relation callees --retain --jq "$handle"
kast query --all --kind function --in services/orders --source-set main --visibility public \
  --without "$tested" --jq "$verdict"
```

This proves direct test calls to functions only. Constructors and properties stay outside the claim.
`callees` cannot read a Java method, so a Java test leaves the first result unproven.
Indirect calls, reflection, and framework entry points also stay outside it.

**Blast radius before an edit, then compiler verification after it:**

```sh
kast query --ref "$ref" --terminal trace --jq "$verdict"
```

`TRACE` collects references, implementations or overrides, direct callers, and one more caller layer.
Record the affected files before the edit. An edit can invalidate refs from this result.
After the edit, check each affected file or its smallest shared directory:

```sh
kast diagnostic check --path services/orders/src/main/kotlin --max-diagnostics 50 \
  --jq '{type, coverage: .document.coverage, diagnostics: .document.diagnostics}'
```

Report the diagnostics and their coverage. An incomplete empty diagnostic result does not prove a clean scope.

**How many times is `A` used, and by how many functions:**

```sh
kast query --ref "$A" --relation references --output occurrences --max-results 1 --jq "$uses"
kast query --request owners.json --jq "$verdict"
```

Write `owners.json` with the ref of `A`:

```json
{"request":{"type":"RUN","source":{"type":"SYMBOL_REFS","symbolRefs":["REF_OF_A"]},
  "steps":[{"type":"EXPAND_RELATION","relation":"REFERENCES"},{"type":"DISTINCT_SYMBOLS"}],
  "executionBudget":{"maxResults":1},
  "terminalStep":{"type":"EMIT","output":{"type":"SYMBOLS","fields":["NAME"]}}}}
```

The first `total` counts use sites. The second counts the declarations that own them.
A plain `references` query emits one symbol row for each use, so its `total` counts uses.

**Where does the workspace use a library declaration:**

```sh
kast query --library kotlin.collections --name listOfNotNull --fields name,signature,location --jq "$verdict"
kast query --ref "$ref" --relation references --output occurrences --jq "$uses"
```

`--library` takes the exact declaring package. Each overload and each imported library version has its own row.
Select every row that the question covers. One row proves nothing about another overload or version.
Destinations are workspace source. Operators and data class properties of a library stay qualified.
A search that meets a Java library declaration rejects, and so does `callees`.
A package that no dependency declares returns a complete, empty result. Confirm the package first.
A library ref expires when the read basis moves. Select it again.

**Several hops of one relation:** adapt the installed `WALK` example:

```sh
kast describe query --section examples --jq '[.content[].value | select(.request.steps[0].type == "WALK")] | .[0]'
kast query --request walk.json --jq "$verdict"
```

**Value flow from one invocation:** let Kast issue the seed. Never compute an offset or a range.
Each Kotlin call in a `callers` result has a `producerSeed` with its enclosing ref, callable ref, and range:

```sh
kast query --ref "$ref" --relation callers --output occurrences --retain \
  --jq '[.document.items[] | select(.producerSeed) | {file: .relation.occurrence.file, producerSeed}]'
```

Copy one `producerSeed` unchanged into `source.seeds` of this request, then run it with `kast query --request impact.json`:

```json
{"request": {"type": "RUN", "source": {"type": "IMPACT", "seeds": [PRODUCER_SEED], "declarations": [],
  "domain": {"type": "WORKSPACE"}, "flow": "KOTLIN_FORWARD_V1", "models": []},
  "steps": [], "terminalStep": {"type": "EMIT", "output": {"type": "VALUE_PATHS"}}, "retention": "RETAIN"}}
```

A row without a `producerSeed` cannot start value flow. Report unresolved obligations as limits of the answer.

## Read source

```sh
kast source read --ref "$ref" --jq '{type, status: .document.status, text: .document.text.text}'
kast source read --ref "$ref" --region class-body
```

Start with the default anchor region. A body region can be absent.

## Failures

A rejection prints its document without the `--jq` projection and exits with a nonzero status.
Read `.document.rejection`, `.document.failure`, and `.document.next_action`, then use the [failure guide](public-cli.md#failures).
Never repeat an identical failed call. Change an input or a prerequisite first.
Exit `4` with `GRADLE_DISTRIBUTION_UNAVAILABLE` or `GRADLE_JVM_UNAVAILABLE` names a build setup problem for the user.
Exit `124` or a cancellation leaves native work uncertain. Do not retry automatically.
If Kast cannot answer, use ordinary source reads and state the limitation.

## Pitfalls

- A missing `--in` directory returns a complete, empty result. Confirm the directory before an absence claim.
- `--in` takes a directory or one file. It limits discovery, not relation destinations.
- `--kind class` includes interfaces, objects, and enums. Read source before you state the declaration form.
- Each query can issue a new ref for the same declaration. Copy refs unchanged. Compare file and signature instead.
- Java primitives appear as Kotlin types. Java `relay(int)` has the single parameter `kotlin/Int`.
- A cursor on a call selects the enclosing declaration, not the callee.
- Source sets default to `main` and `test`. Pass each `--source-set` that the task requests.
- A lambda stored in a constructor or a non-inline parameter has no caller in the enclosing function.
  A `CALLERS` stage can reject with `CALLBACK_GRAPH_UNPROVEN`. See [stored lambdas](public-cli.md#calls-inside-stored-lambdas).
- To count use sites, use `references`. `callers` omits calls in stored lambdas, such as most coroutine builder bodies.
- Operators, loops, destructuring, and delegates call functions without call syntax. `callers` and `callees` include them.
  Incoming reads of operator `next`, `hasNext`, `getValue`, `setValue`, or `set` stay qualified. Report "at least these".
- Kotlin property syntax calls Java methods: `bean.label` calls `getLabel()`, and `bean.label = x` calls `setLabel(x)`.
  `callees` includes these calls. `callers` and `references` of a Java getter or setter stay qualified if Kotlin code
  in the search scope contains the property name. Report "at least these".
- Kast cannot select a Java field or enum constant. A `--name` that matches one stays qualified.
  `--all` includes Java classes and methods, and a Java field leaves its properties unproven.
  A fuzzy name selects Kotlin declarations only.
- `--name` alone finds workspace source only. A library name, such as `listOfNotNull`, then returns a complete, empty result.
  That result says nothing about its uses. Add `--library PACKAGE` to select the library declaration.
- Static resolution excludes reflection, dependency injection, and runtime dispatch that the compiler cannot see.

See the guide for [operation choices](public-cli.md#choose-the-operation-first), [request documents](public-cli.md#request-documents), [optional MCP tools](public-cli.md#optional-mcp-tools), and [fresh sessions](public-cli.md#fresh-sessions).
