# Representation-impact fixture intent

This guide holds the reviewed source-text intent for the representation-impact fixture and the recipe for a fresh investigation.
Public contract 17 uses `RESUME` only for already-produced output, never to continue semantic execution.
Use [the current output guide](../../docs/public/how-to/read-retained-evidence.mdx) for output recovery.
Submit each request through the public `kast` CLI or the native MCP server.

The fixture manifest is authored source-text intent. Its offsets and named uses intentionally distinguish invocations, slots, branch/reassignment uses, and exact callable names. The model intent is a proposed reviewed recipe; it contains no live handles or compiler identity. Actual K2 must revalidate callable identity, source position, basis, and the representation/boundary rules. An anchor match cannot establish that proof.

```sh
build/python-tests/env/bin/python3 experiments/representation-impact/fixture_intent.py \
  --source experiments/host-observation/semantic-fixture/value-flow/RepresentationImpactFixture.kt
build/python-tests/env/bin/python3 -m unittest discover \
  -s experiments/representation-impact -p 'test_*.py' -v
```

## Reacquire the fixture investigation

The checked-in `fixture-intent.expected.json` is reusable intent, separate from live references, model declaration claims, result handles, and continuations. Use its source hash, four seed anchors, and reviewed rule intent to construct a new question after an epoch change or restart. The following offsets apply only to that exact ASCII fixture copied at the manifest's `sourcePath`.

| Fresh `AT_LOCATION` lookup | UTF-16 offset |
| --- | ---: |
| `investigate` | 794 |
| `Voltage.encrypt` | 161 |
| `Hiped.encrypt` | 273 |
| `Hiped.decrypt` | 320 |
| `display` | 630 |
| `persist` | 745 |

1. Verify the fixture bytes and reacquire these six exact references. Do not reuse references or declaration bases from an earlier run.
2. Run a model-free retained `IMPACT` bootstrap with exact invocation anchors `876–901`, `1296–1319`, `1340–1360`, `1416–1438`, and `1717–1742`, all enclosed by the fresh `investigate` reference. These anchors select native identities needed for the reviewed models; they do not establish representation meaning.
3. Read its `PRODUCERS` witnesses. Use the returned invocation callable identities and enclosing declaration evidence to bind the reviewed models. Require one current live basis across all supplied bindings. Preserve any native rejection or unsupported bootstrap result.
4. Run a new retained investigation with the manifest's four seed anchors, `WORKSPACE`, and `KOTLIN_FORWARD_V1`. Supply the five representation rules as reviewed model data. The separate reviewed persistence model identifies invocation `1717–1742`, argument `1` at `1736–1741`, contract `fixture-account-storage` version `1`, slot `value`, and terminal meaning `REVIEWED_RETENTION`. Retention, decoding compatibility, and migration remain obligations.
5. Examine the `RUN` completion verdict before a complete-answer claim. Read retained `VALUE_PATHS` and witness pages with their issued presentation cursors. `FINDINGS` supplies one compact row per original path. Expand each row on the same result with its path ordinal and examine the original row ID. Retained reads must preserve the original basis, domain, closure, qualifications, and native-counter evidence.

The grant used by the installed experiment is 128 results, 2000 ms, 100000 work units, and 524288 returned bytes. One-row presentation uses the same grant with one result. Record requested and effective limits; these allowances do not discharge semantic obligations. The installed client and host response limits must independently admit the requested envelope.

The fixture's `unmodeled` and `Unrelated.decrypt` functions both return their argument unchanged. Their names do not make them opaque transformations; a compiler-proven wrapper return is legitimate. A separate opaque-transform probe is required to prove an unsupported transformation stop. A source-intent site beyond a mutable-value or constructor stop remains unreached under the supported flow; it must not be reported as absent.

An old retained response is historical evidence on its original basis. After epoch movement or restart, reacquire this recipe's identities and model bindings and run again. A rejected old result or continuation cannot establish the exact expiration cause by itself.

Earlier captures used execution checkpoints to advance queued routes. Public contract 17 does not admit those checkpoints through `RESUME`. An output continuation can change presentation limits, but it cannot increase the original semantic grant or recover unexamined branches.

If execution stops at `WORK_LIMIT_REACHED`, keep its partial transfers and original completion rejection. Start a new investigation with a larger initial grant only when that change matches the question. Keep the earlier result as evidence of its resource limit. `FINDINGS` needs the original investigation ledger and rejects when that ledger is unavailable.

See the [installed proof receipt](../../docs/reviews/representation-impact-proof.md) for the exact tested artifact, terminal accounting, retained presentation measurements, independent audits, and remaining issue criteria.

The focused test checks that the manifest matches the fixture text and records no live handles. It does not launch native IDEA or establish installed product behavior.
