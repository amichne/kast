# Query Kotlin with the public CLI

The public `kast` command exposes the existing `query_symbols` contract without MCP setup.
The Host in IntelliJ IDEA supplies semantic authority for the exact workspace root.
Version 1 acceptance and its evidence limits appear in the [implementation report](reviews/public-cli-v1-evidence.md).

## Discover and plan

Use the installed package to discover the active interface:

```sh
kast describe
kast describe query --section input
kast describe query --section output
kast describe query --section examples
kast query plan --name OrderService --in services/orders
```

Discovery reads packaged contracts.
Planning admits a request and reports `REQUEST_SHAPE_ONLY` advice with `NOT_MEASURED` estimates.
These commands do not prepare a workspace or start the Host.
A plan does not prove workspace readiness or semantic completion.

## Submit one query

Use flags for a common query shape:

```sh
kast query --name OrderService --in services/orders --source-set main \
  --fields name,signature,location --retain
```

Use `--workspace PATH` when the current directory differs from the target workspace.
The CLI defaults to `--catalog complete`.
An explicit `--catalog common` keeps its restrictions and rejects unsupported syntax before host work.

Exact-name discovery can return several overloads.
Examine the owner, signature, and location before you select an issued exact reference.
The `--in` option restricts discovery, without a restriction on later relationship expansion.

For the full grammar, submit one public argument document:

```json
{
  "request": {
    "type": "RUN",
    "source": {
      "type": "SEARCH_DECLARATIONS",
      "declarationName": "OrderService"
    },
    "terminalStep": {
      "type": "EMIT",
      "output": {
        "type": "SYMBOLS",
        "fields": ["NAME", "SIGNATURE", "LOCATION"]
      }
    }
  }
}
```

Save that document as `query.json`:

```sh
kast query --request query.json
kast query --request - < query.json
```

The document contains the public `request` field, without an RPC envelope.
Raw input supports `RUN`, `READ_RESULT`, `RESUME`, and `READ_SOURCE`.
Do not mix raw input with semantic flags or `--verbose`.
The CLI reads stdin only for an explicit `--request -`.

## Select presentation

Omitted `--fields` preserves the default name and location fields.
An explicit `--fields ''` requests no optional symbol fields.
Identity, kind, outcome, qualifications, and recovery evidence remain part of the result.
Valid fields are `name`, `location`, `signature`, and `source`.
Occurrence output rejects `--fields`.

`--max-results` limits the output page under runner-controlled execution.
It does not authorize incomplete semantic proof.
`--max-returned-bytes` uses the existing presentation allowance.
That allowance excludes the outer RPC envelope, newline, and stderr.
The CLI does not truncate JSON to fit an allowance.

An explicit `--max-elapsed-ms` supplies the canonical caller grant for `RUN`.
Omission adds no CLI semantic timeout.
The grant covers the existing host admission and read work, without a whole-process deadline.

## Reuse issued results

The examples here need tokens from a prior response:

```sh
kast query --ref "$SYMBOL_REF" --relation callers --fields name,location
kast result read --ref "$RESULT_REF" --cursor 100 --evidence-cursor 0
kast result resume --continuation "$OUTPUT_TOKEN"
kast source read --ref "$SYMBOL_REF" --region callable-body --text complete
```

Keep each issued token unchanged.
A result reference belongs to its workspace, semantic basis, and lifetime.
An expired or foreign reference rejects without a new name search or query producer.
The `result resume` command accepts only an issued output continuation.

The CLI emits one page and never drains successors implicitly.
A successor cursor means that more output remains.
It does not make a complete page unsuccessful.
Row and evidence cursors advance independently.
A selected row subset keeps its incomplete-input qualification.

## Read output and exit status

Semantic commands emit the existing RPC JSON outcome plus one newline.
The output shape stays the same in terminals and pipes.
`--json` explicitly selects this default.
Progress and bounded diagnostics use stderr.

Read the outer outcome and the canonical document together.
Retained `POLICY_REJECTED_EVIDENCE` remains unsuccessful even when retrieval reaches its final page.
A complete `SOURCE_RELATIONSHIPS` interpretation can include callback evidence without that policy rejection.

| Exit | Meaning |
| --- | --- |
| 0 | Complete operation and requested presentation, or successful metadata |
| 1 | Unexpected local defect |
| 2 | Invalid command or request |
| 3 | Exact workspace discovery failure |
| 4 | Unavailable or rejected installation or runtime |
| 5 | Transport or output I/O failure, including a broken pipe |
| 6 | Protocol, schema, or encoding mismatch |
| 9 | Bootstrap failure |
| 10 | Qualified or rejected semantic proof |
| 11 | Requested content was not delivered |
| 124 | Typed expiry of an explicit caller grant |
| 130 | Handled SIGINT |
| 143 | Handled SIGTERM |

For a pipeline that depends on the producer status, enable `pipefail`:

```sh
set -o pipefail
kast query --request query.json | jq .
```

A formatter does not preserve the producer status by itself.
Signals and closed pipes can interrupt a JSON record.
Local cancellation or JVM termination does not prove native settlement.

## Source owners

- `cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/` owns public parsing, descriptors, output, and shell exits.
- `app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolContract.kt` owns public admission.
- `cli/src/main/kotlin/io/github/amichne/kast/cli/direct/KastDirectToolSession.kt` owns direct execution composition.
- `distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PublicQueryDispatch.kt` owns native delegation.

These examples describe the interface.
The implementation report records which examples and native paths ran.
