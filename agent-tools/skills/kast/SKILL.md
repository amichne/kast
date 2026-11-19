---
name: kast
description: Use Kast when a Kotlin or Java answer depends on compiler resolution in a Gradle workspace, such as which declaration a name or call selects, callers, callees, implementations, or references across files, which file the imported build compiles, or compiler diagnostics. Skip it when reading or searching known source answers the question.
---

# Kast

Kast answers Kotlin and Java questions from the IntelliJ compiler model of one Gradle workspace.
The first query trusts the workspace build code and prepares a private Host. Preparation can take minutes.
Later calls reuse that Host. Use Kast only in a workspace whose build code the user trusts.

## Decide first

Use Kast when the answer depends on resolution:

- which overload or same-named declaration a name or call selects
- callers, callees, implementations, overrides, or references outside one file
- which file the imported Gradle build compiles
- compiler diagnostics without a build

Skip Kast when a file read or text search answers the question, such as file content, a literal, or an edit in a known file.
A Kast call costs more tokens than a read.

## Commands

Run commands from the workspace root, or add `--workspace /absolute/root`.
Set this filter once. It reports the verdict and the rows that you need:

```sh
verdict='{type, status: .document.status, exhaustive: .document.coverage.exhaustive,
  failures: .document.original_evidence_summary.failure_count,
  omissions: .document.original_evidence_summary.omission_count,
  rows: .document.row_window.type, next_cursor: .document.next_cursor,
  result: .document.retention.reference,
  items: [.document.items[]? |
    if .type == "exact-symbol" then {name, signature, ref, file: .location.file} else . end]}'
```

Find a declaration. Pass its name with `--name`, its directory with `--in`, and its source set with `--source-set`.

```sh
kast query --name OrderService --in services/orders --source-set main --kind class \
  --fields name,signature,location --jq "$verdict"
```

Follow one relation from an exact declaration:

```sh
kast query --ref "$ref" --relation callers --fields name,signature,location --jq "$verdict"
```

Relations are `references`, `callers`, `callees`, `implementations`, `inheritors`, `overrides`, and `type-uses`.
Find and expand in one call only when `--in` narrows the name to one declaration:

```sh
kast query --name AuditSink --in src/main/kotlin/parcel/audit --kind class \
  --relation implementations --jq "$verdict"
```

To restrict relation destinations, use a JSON `RUN` request with `expansionScope` on the `EXPAND_RELATION` step.
Use the installed example to select the directory, source sets, and source policy.

```sh
kast describe query --section examples --jq '[.content[].value | select(.request.steps[0].expansionScope.type == "SOURCE_DOMAIN")] | .[0]'
```

Trace affected uses: references, implementations or overrides, direct callers, and one more caller layer:

```sh
kast query --ref "$ref" --terminal trace --jq "$verdict"
```

Read source for an issued reference. Start with the default anchor region:

```sh
kast source read --ref "$ref" --jq '{type, status: .document.status, text: .document.text.text}'
kast source read --ref "$ref" --region class-body
```

For context lines, pass a `READ_SOURCE` request to `kast query --request -` on stdin.
Replace `ISSUED_REF` with the exact ref. This example requests ten lines on each side within the anchor region.

```json
{"request":{"type":"READ_SOURCE","candidateRef":"ISSUED_REF","region":"ANCHOR","text":{"type":"WINDOW","beforeLines":10,"afterLines":10}}}
```

Check compiler diagnostics. Use `--path .` for the workspace root:

```sh
kast diagnostic check --path src/main/kotlin --max-diagnostics 20 \
  --jq '{type, coverage: .document.coverage, diagnostics: .document.diagnostics}'
```

For several hops of one relation, copy the installed `WALK` example, then replace its placeholder ref:

```sh
kast describe query --section examples --jq '[.content[].value | select(.request.steps[0].type == "WALK")] | .[0]'
kast query --request walk.json --jq "$verdict"
```

For an unknown flag value, inspect that flag:

```sh
kast describe query --jq '.content.flags[] | select(.name == "--kind") | {value_spellings, schema}'
```

## Read the verdict

Claim a complete answer only when `type` and `status` are `complete`, `exhaustive` is `true`, and both counts are `0`.
`rows` set to `MORE` means that more rows exist. The query still reports `complete` and exits `0`.
Read the next page with the retained result and cursor. Use the original output kind:

```sh
kast result read --ref "$result" --cursor "$next_cursor" --output symbols --jq "$verdict"
```

Never repeat a query to recover output. If the result issues an evidence cursor, pass it with `--evidence-cursor`.
A qualified or incomplete result never proves absence. Report its omissions and failures.

## Failures

A rejection prints its document without the `--jq` projection and exits with a nonzero status.
Read `.document.rejection`, `.document.failure`, and `.document.next_action`, then use the [failure guide](public-cli.md#failures).
Never repeat an identical failed call. Change an input or a prerequisite first.
Exit `4` with `GRADLE_DISTRIBUTION_UNAVAILABLE` or `GRADLE_JVM_UNAVAILABLE` names a build setup problem for the user.
Exit `124` or a cancellation leaves native work uncertain. Do not retry automatically.
If Kast cannot answer, use ordinary source reads and state the limitation.

## Pitfalls

- A name can match overloads and same-named declarations in other packages. A relation from `--name` expands every match together.
- A missing `--in` directory returns a complete, empty result. Confirm the directory before an absence claim.
- `--kind class` includes interfaces, objects, and enums. Read source before you state the declaration form.
- Each query can issue a new ref for the same declaration. Copy refs unchanged; compare file and signature instead.
- Java primitives appear as Kotlin types. Java `relay(int)` has the single parameter `kotlin/Int`.
- A cursor on a call selects the enclosing declaration, not the callee.
- Location ranges are UTF-16 offsets, not line numbers.
- Source sets default to `main` and `test`. Pass each `--source-set` the task requests.
- A lambda stored in a constructor or a non-inline parameter has no caller in the enclosing function. See [stored lambdas](public-cli.md#calls-inside-stored-lambdas).

See the guide for [operation choices](public-cli.md#choose-the-operation-first), [request documents](public-cli.md#request-documents), [optional MCP tools](public-cli.md#optional-mcp-tools), and [fresh sessions](public-cli.md#fresh-sessions).
