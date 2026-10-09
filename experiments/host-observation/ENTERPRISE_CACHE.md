# Qualify the five enterprise cache feedback cases

The supplied photos report outcomes from a saved, indexed enterprise workspace.
They do not supply raw response documents, exact loaded artifacts or a source
snapshot. This portable fixture preserves the reported mechanism: a generic
interface method, class and object implementations, an inline reified adapter
with `noinline` callbacks and a default predicate, and a client invocation.
It adds one test implementation/call, an inferred receiver call through a factory,
and an unrelated same-name interface and
adapter as negative controls. It contains no enterprise source.

## Run portable proof first

From the Kast checkout:

```sh
mise exec -- ./gradlew -p experiments/host-observation/enterprise-cache-fixture compileKotlin compileTestKotlin --console=plain
python3 -m venv /tmp/kast-enterprise-feedback-python
/tmp/kast-enterprise-feedback-python/bin/python -m pip install -r experiments/host-observation/requirements-test.txt
/tmp/kast-enterprise-feedback-python/bin/python -m unittest discover -s experiments/host-observation -p test_enterprise_cache_qualification.py -v
```

Compilation proves the fixture's Kotlin types and callback signature compile.
The Python checks validate request shapes against the generated canonical
schema, independent UTF-16 positions, fixture integrity, identical grants for
scope controls and rejected-evidence/presentation invariants. They do not run
K2 queries or prove native behavior.

## Observe the installed native path

The exact fixture must already be open, saved, Gradle-imported and indexed in
IntelliJ with the selected Kast host active. The runner first uses the existing
`kast_ide.exchange` descriptor/socket admission; an unavailable exact endpoint
stops before any public RPC. It invokes no installation, IDE restart, project
opening or model refresh command.

```sh
/tmp/kast-enterprise-feedback-python/bin/python experiments/host-observation/qualify_enterprise_cache.py \
  --root "$PWD/experiments/host-observation/enterprise-cache-fixture" \
  --rpc "$HOME/.local/share/kast/installation/bin/kast-tool-rpc-complete" \
  --output /tmp/kast-enterprise-cache-observation
```

Use a new output directory for every run. The runner uses the installed input
schema and the checkout's generated semantic output schema. Schema mismatch is
a failure, not compatibility widening. Each raw process/request/response is
retained, along with fixture and executable hashes. It acquires exact references
from that run; enterprise handles and retained-result IDs are never copied.
Retained row and evidence cursors drain separately without replaying semantics.
The source oracle authors expected occurrences before reading any response.

| Original case | Required observation and independent control |
| --- | --- |
| Common trace | Implementations include Redis, NoOp and test declarations; scoped interface `CALLERS` is the adapter. Adapter references contain exactly the authored import, production call and test call with separate occurrence ranges and ownership. Add `--trace` for a candidate exposing the terminal `TRACE` step; require the adapter and both downstream callers, require interface tracing to include its member and the factory-inferred receiver call, and exclude the unrelated adapter. |
| Fast narrow queries | Package and directory selections enumerate the same one interface with identical kind, output and grants. Neither admits the unrelated same-name interface. Native duration evidence is retained by the existing process capture; this is a functional scope control, not an enterprise latency benchmark. |
| Incomplete-query recovery | The photo's one-hop `WALK(CALLEES)` request uses 15,000 ms and 100 results. Record its actual qualifications. A small fixture need not reproduce byte exhaustion. If complete, require the independently authored adapter-to-interface occurrence. The reader rejects a stalled presentation, moved basis, or rejected interpretation becoming ordinary query evidence. |
| Vocabulary and location | `CLASS` discovers the interface; `INTERFACE` and object-shaped `retention: {"type":"RETAIN"}` fail the schema. Positions in the generic list and method name identify the interface method; the implementation body identifies its override. A supplementary character before those positions guards UTF-16 conversion. |
| Reference freshness | Two reads of one issued adapter reference retain its exact identity and unchanged basis. This does not prove refresh behavior; controlled native refresh and stale-handle recovery need their own wider qualification. |

`--concurrency-control` runs seven identical method searches sequentially and
concurrently. It keeps name, kind, package, outputs and grants fixed. Its result
is an observation, not a causal conclusion. The photographed sequential retry
also changed kind and time allowance, so it cannot isolate concurrency.

## Widen only for the remaining claim

Small-fixture success does not prove enterprise latency, native cancellation
latency or callback byte exhaustion. For scale qualification, retain the same
queries and grants in an explicitly selected larger fixture and record effective
limits, scan stages and `kast_semantic_read` counters. Compare package/directory
and narrow/workspace relation domains with equivalent expected targets. Change
one scheduling or scale factor at a time; keep repetitions, warmups, loaded
artifacts and indexing conditions matched. Distinguish semantic allowance from
whole-request time and cancellation drainage.

For reference movement, record the original basis and acquisitions, perform an
authorized controlled edit/refresh in the disposable fixture, acquire the new
basis, and test old exact/result/continuation/source handles according to their
separate contracts. Unchanged signature/location is not enough to assert reuse.
Inspect bounded `kast_project_read_epoch` changed signals; do not infer which
signal moved from opaque handle spelling.

For a current-source native claim, retain a native loaded-class/artifact pin and
match it to the candidate build before and after the observations using the
[existing reproduction workflow](SEMANTIC_REPRODUCTION.md). This runner labels
runtime correspondence unproven without that separate evidence. The portable
schema/control checks and a running IDE alone do not establish that the
candidate host/control are installed. `TRACE` needs both candidate contracts.

The enterprise's 97 interface references, 78 adapter references and 32 production
calls across 24 files remain reported historical counts. The portable fixture
has its own exact oracle; do not use those enterprise counts as fixture expected
results or present portable success as reproduced enterprise performance.

See [the local validation record](ENTERPRISE_CACHE_VALIDATION.md) for executed
checks, evidence levels, skipped tests and remaining native qualification.
