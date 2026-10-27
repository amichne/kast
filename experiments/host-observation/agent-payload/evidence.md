# Agent payload evidence

The implementation base is `d280e9fb04cfb3d266c84371fe97e103b99ccb59`.
The fetched `origin/main` matches that revision. No later source changes need reconciliation.
The original clean checkout remains named by `preserve/agent-payload-original-9269df7`.
The task branch is `feat/agent-payload`.

## Acceptance contract

The consumer is an agent with an eager or deferred tool catalog.
Exact selection must preserve overload identity, location, scope, freshness, and the original semantic conclusion.
The implementation extends the existing public admission, query delivery, retained presentation, and reference owners.
It does not change the semantic engine or weaken completion policy.

The independent oracle is `../pi-fixtures/agent-payload-oracle.json`.
Its source resides in the existing `semantic-fixture/logging` project.
The wrong-file control has the same signature in another package.
The existing `QueryDeliveryFixtures` owner supplies qualified and rejected serialized outcomes.
The large-witness control needs a summary followed by a small entry and exact detail reconstruction.
Portable fixture success does not prove a native client workflow.

## Baseline measurements

These figures come from the installed MCP process and Pi converter before product changes.
The installed Kast product version is `0.0.3719`.
The Pi version is `1.4.0`.
The converter identity, options, hashes, and full compressed declarations reside in `baseline/`.
The serialization uses `JSON.stringify` without whitespace and UTF-8.

| Item | Unicode characters | UTF-8 bytes |
| --- | ---: | ---: |
| Query input schema | 51,134 | 51,134 |
| Query output schema | 843,990 | 843,990 |
| Pi query declaration | 53,721 | 53,721 |
| All Pi provider declarations | 59,212 | 59,212 |

The supplied screenshot reports 105,401 declaration characters and 102,809 parameter characters.
It also reports 3,251 discovery characters and a roughly 51 KB search result.
Those supplied figures are distinct from the reproduced Pi conversion.
The expanding adapter identity and conversion options remain unknown.
Its conversion and replay criterion is BLOCKED.
Task input tokens, cache accounting, discovery calls, recovery calls, and model-facing result bytes are unavailable.
No task-level improvement claim follows from these declarations.

The MCP capture used legacy `initialize` and `tools/list` on one stdio process.
Its exact responses reside in `baseline/tools-list.json.gz`.
The initial direct `tools/list` control rejected with `missing-request-meta`.

```sh
mise exec -- node experiments/host-observation/capture_agent_payload.mjs \
  /Users/amichne/.pi/agent/install/releases/1.4.0/node_modules/@earendil-works/pi-coding-agent \
  /tmp/kast-payload-baseline-tools-list.json \
  "$PWD/experiments/host-observation/agent-payload/baseline"
```

## Segment 1

The generated index names six common topics and advanced example addresses.
Common topics have a 4,096-byte UTF-8 cap.
Canonical enum and default mutations update the generated help.
The existing admission test admits all authored examples used by the topics.
The source-size and expanded-equivalence guards remain active.

| Criterion | Status | Evidence |
| --- | --- | --- |
| Topic generation and canonical metadata | PASS | 28 Python tests |
| Independent source oracle | PASS | Authored overload, caller, trace, and wrong-file expectations |
| Actual baseline conversion | PASS | Installed Pi converter and complete MCP catalog |
| Actual expanding adapter | BLOCKED | Adapter identity unavailable |
| Native codemode task replay | NOT RUN | No replay yet |
| Matched task consumption | NOT RUN | No provider token results yet |

The focused generation tests first failed because named topic outputs did not exist.
After the generator change, all 28 tests passed.
The exact command was `mise exec -- python3 packaging/test-public-query-generation.py`.
