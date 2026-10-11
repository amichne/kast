# Configure semantic reads and diagnose limits

The existing-IDE read path admits an immutable settings policy when its project service starts. The CLI and provider admit their policy when their processes start. Caller execution budgets control semantic output; default transport byte capacities impose only the JVM representation ceiling. The authoritative parameter identities, ranges and cross-limit checks are in [ReadLimits.kt](../kernel/src/main/kotlin/io/github/amichne/kast/kernel/ReadLimits.kt); `kast config schema --json` exposes the generated catalogue. Client exchange time must strictly exceed host connection time, and both provider invocation deadlines must strictly exceed client exchange time. Equality rejects configuration so an outer timer cannot expire at the inner boundary. Semantic and hosted query settings may remain equal because hosted admission reserves completion time before selecting the effective semantic grant.

## Apply a setting

Every row below has an environment key `KAST_READ_<NAME>` and an IDE JVM property `kast.read.<name with lowercase words separated by dots>`. For example, 512 IDEA modules can be admitted with `KAST_READ_MODEL_MODULES=512` in the IDE launch environment, or this line in the IDE's custom VM options:

```text
-Dkast.read.model.modules=512
```

Restart the IDE to activate changed JVM settings. Explicit plugin reactivation also creates fresh project services; the native regression check uses this route. Editing settings during a query cannot change that query's admitted policy or its epoch authority. IDEA module counts include source-set modules and can exceed Gradle project counts.

CLI and provider settings can be supplied as environment values or literal assignments in the installation's selected `config/environment` file. Preserve other assignments when editing that file. Validate the file before activation:

```sh
kast config validate --file /absolute/installation/config/environment --json
kast config show --json
kast config explain KAST_READ_HOST_RESPONSE_BYTES
```

The IDE does not read the CLI installation file. Apply the IDE's corresponding JVM/environment values as well. The provider retains its settings until the broker is restarted; new CLI processes read the selected saved configuration. The existing-IDE CLI route performs configuration admission before opening the socket.

JVM properties take precedence over the IDE environment. CLI precedence is command-line settings, process environment, saved workspace settings where supported, saved installation settings, then defaults. Shadowed supplied values are still validated. Unknown reserved keys, nondecimal numbers, zero, overflow and inconsistent paired limits reject configuration; they never silently fall back. Rejections expose parameter identities and finite conditions, without echoing raw values.

For a five-second semantic budget, a consistent IDE configuration is:

```text
-Dkast.read.semantic.millis=5000
-Dkast.read.host.query.millis=6000
-Dkast.read.host.connection.millis=7000
```

Also set `-Dkast.read.client.exchange.millis=8000` in the IDE policy, and apply the same four values with their `KAST_READ_` environment names in the CLI/provider configuration. The inner semantic and diagnostic deadlines must fit the host query deadline, which must fit connection, client, provider and process deadlines. Result/source bytes must fit host responses, provider output and process output. Increasing one inner value beyond its outer value is rejected. A larger result cap does not establish complete coverage when work, time or bytes run out.

## Default values

All values are positive decimal integers, up to 2,147,483,646. Transport and process byte capacities have a minimum of 256 bytes. Counts, identities and protocol grammar remain distinct: these parameters tune operational capacities; they do not change compiler identity rules, canonical token formats, or OS socket-path constraints.

| Name (append to `KAST_READ_`) | Default | Unit |
|---|---:|---|
| `MODEL_CACHED_GRADLE_MODELS` | 8 | count |
| `MODEL_MODULES` | 256 | count |
| `MODEL_SOURCE_ROOTS_PER_MODULE` | 256 | count |
| `MODEL_CLASSPATH_ENTRIES_PER_MODULE` | 2,048 | count |
| `MODEL_IDENTITY_CHARACTERS` | 512 | characters |
| `MODEL_PATH_CHARACTERS` | 4,096 | characters |
| `MODEL_CLASSPATH_URL_CHARACTERS` | 8,192 | characters |
| `EPOCH_CACHED_GRADLE_MODELS` | 16 | count |
| `EPOCH_VFS_EVENTS` | 4,096 | count |
| `EPOCH_PATH_CHARACTERS` | 4,096 | characters |
| `EPOCH_PATH_BYTES` | 8,192 | bytes |
| `HOST_QUERY_MILLIS` | 30,000 | milliseconds |
| `SEMANTIC_MILLIS` | 2,000 | milliseconds |
| `SEMANTIC_WORK` | 100,000 | count |
| `SEMANTIC_RESULTS` | 128 | count |
| `SEMANTIC_RETURNED_BYTES` | 49,152 | bytes |
| `QUERY_CHECKPOINT_BYTES` | 8,388,608 | bytes |
| `QUERY_CONTINUATION_ENTRIES` | 64 | count |
| `QUERY_CONTINUATION_BYTES` | 33,554,432 | bytes |
| `QUERY_CONTINUATION_TTL_MILLIS` | 600,000 | milliseconds |
| `DISCOVERY_NAMES` | 10,000 | count |
| `DISCOVERY_CANDIDATES` | 10,000 | count |
| `RELATION_CANDIDATES` | 10,000 | count |
| `SOURCE_ENTITY_WORK` | 10,000 | count |
| `SOURCE_CONTINUATIONS` | 1,024 | count |
| `SOURCE_ENTITIES` | 128 | count |
| `SOURCE_RETURNED_BYTES` | 49,152 | bytes |
| `TRAVERSAL_DEPTH` | 16 | count |
| `TRAVERSAL_FRONTIER` | 128 | count |
| `HOST_REQUEST_BYTES` | 2,147,483,646 | bytes |
| `HOST_RESPONSE_BYTES` | 2,147,483,646 | bytes |
| `HOST_DESCRIPTOR_BYTES` | 16,384 | bytes |
| `HOST_ACCEPT_BACKLOG` | 64 | count |
| `HOST_CONNECTIONS` | 16 | count |
| `HOST_CONNECTION_MILLIS` | 31,000 | milliseconds |
| `CLIENT_EXCHANGE_MILLIS` | 32,000 | milliseconds |
| `HOST_FILE_CHARACTERS` | 262,144 | characters |
| `HOST_CLASS_CANDIDATES` | 32 | count |
| `DIAGNOSTIC_SCOPE_FILES` | 256 | count |
| `DIAGNOSTIC_SCOPE_WORK` | 20,000 | count |
| `DIAGNOSTIC_SCOPE_MILLIS` | 2,000 | milliseconds |
| `DIAGNOSTIC_COUNT` | 1,000,000,000 | count |
| `DIAGNOSTIC_FAILURES` | 16 | count |
| `DIAGNOSTIC_FRAMES` | 8 | count |
| `DIAGNOSTIC_TEXT_CHARACTERS` | 256 | characters |
| `PROVIDER_OUTPUT_BYTES` | 2,147,483,646 | bytes |
| `PROVIDER_INVOCATION_MILLIS` | 1,080,000 | milliseconds |
| `PROVIDER_GRAPH_INVOCATION_MILLIS` | 1,260,000 | milliseconds |
| `PROCESS_INPUT_BYTES` | 2,147,483,646 | bytes |
| `PROCESS_OUTPUT_BYTES` | 2,147,483,646 | bytes |
| `PROCESS_TIMEOUT_MILLIS` | 1,260,000 | milliseconds |

## Read the evidence

The IDE's `idea.log` receives a `kast_semantic_read` JSON record by default after each admitted request drains, including rejected requests. No diagnostic enable switch is required. Each record includes the host/epoch correlation when available, stage durations, remaining outer deadline at semantic entry, bounded counters, exact native termination reasons, the final hosted outcome, and effective limit values with their sources. Early endpoint/transport failures emit `kast_hosted` records with a stage and closed failure code.

Callback proof retention records up to six bounded gauges in `kast_semantic_read`.
`CALLBACK_PROOF_BYTE_ALLOWANCE`, `CALLBACK_PROOF_RETAINED_BYTES` and
`CALLBACK_PROOF_REQUIRED_BYTES` describe the latest actual retention attempt:
the admitted allowance, admitted storage after the decision, and storage required
by that attempted addition. A rejected addition leaves admitted storage unchanged.
Required accounting saturates at `Long.MAX_VALUE` if addition would overflow.
The three `CALLBACK_PROOF_BYTE_REJECTION_*` gauges (`ALLOWANCE`, `RETAINED_BYTES`,
`REQUIRED_BYTES`) preserve one coherent latest byte-rejected attempt even if a
later attempt succeeds with a smaller allowance. They describe that rejecting
ledger, not the ledger making the latest successful attempt.

`CALLBACK_PROOF_RETENTION_ADMITTED`, `CALLBACK_PROOF_RETENTION_RESULT_REJECTED`
and `CALLBACK_PROOF_RETENTION_BYTE_REJECTED` count the actual admission decisions.
Result capacity is checked first; a result rejection does not also count as a byte
rejection. Independent ledgers are not summed. These bytes are conservative
accounting for detached callback evidence, not measured heap use or encoded
response bytes. The final response has its own encoded-byte guard. Public query
results retain the finite callback cause, scan state and obligations; these numeric
receipts belong to the diagnostic log, not the public semantic result.

When retained results or continuations become unavailable, inspect
`kast_project_read_epoch` records with the same `host`. A `MOVED` outcome names
the changed signals: `PSI`, `VFS`, `WORKSPACE_MODEL`, `ROOT_MODEL`, `INDEXING`,
`PROJECT_ROOT`, `GRADLE_ROOT`, or `IMPORT_STATE`. These are differences between
successful native samples, not a count of admitted query epochs. `REJECTED`
preserves the finite observation failure; `RECOVERED` means observation resumed
with the last proven signals unchanged. Repeated unchanged samples and identical
rejections are silent. Records contain no paths, source text or raw counters.
After epoch movement, rerun the original query to acquire current handles.

Declaration inventory records `DISCOVERY_PARTITIONS_OBSERVED` and
`DISCOVERY_PARTITIONS_ACCEPTED`. Its termination labels distinguish an absent,
invalid, noncanonical or wrong-kind partition, exhausted partition capacity,
and unavailable source. These explain a public `partition-unavailable` or
`provider-failure` without weakening its incomplete coverage.

Unexpected native exceptions retain only the exception class and bounded Kast adapter class/method/line frames. Exception messages, causes, source payloads, file paths and opaque references are excluded. Counter and exception collection capacities are configurable too. A hosted `completed` outcome means execution returned; inspect the canonical response and termination reasons for semantic completeness. For example, `NAME_CAP` and `CANDIDATE_CAP` can both produce public `work-limit-reached`, while the log preserves the actual cause.

`CONFIGURATION_REJECTED` precedes semantic execution. `MODULE_ADMISSION_LIMIT` occurs during model capture, before the semantic budget starts. `TIME_LIMIT`, `WORK_LIMIT`, `RESULT_LIMIT` and `BYTE_LIMIT` identify different exhausted resources. `LIBRARY_POLICY_EXCLUSION` records an intentionally excluded library target; unresolved project targets still qualify coverage.

The [reproduction guide](../experiments/host-observation/SEMANTIC_REPRODUCTION.md) separates native setup, artifact pinning and replay. Its settings evidence reads the loaded service's admitted policy rather than assuming static defaults. The [review](reviews/hosted-semantic-reproduction.md) retains baseline findings and subsequent fixes separately.

## Deadline admission and search ordering

The host deadline covers admission, model capture, semantic evaluation, freshness
revalidation and detachment. Its default is 30,000 ms; semantic work retains a
2,000 ms default. Caller allowances may raise that default within the operator and
host limits. At semantic entry, the host subtracts elapsed request time and
reserves the smaller of 250 ms or one eighth of the host limit (at least 1 ms)
for completion. Semantic and diagnostic-scope allowances are each capped by the
remaining time after that reserve. A nonpositive allowance rejects before the
evaluator runs. Configured equal limits remain supported through this runtime
refinement. The hard timer still cancels and drains work that overruns; cooperative
compiler work is not guaranteed to respond within the completion reserve.

Schema-4 `kast_semantic_read` receipts retain configured limits and an explicit
`semanticBudget`: `not-admitted`, `admitted` with remaining host time, reserve and
effective allowances, or `exhausted`. Semantic qualification and host rejection
remain distinct outcomes.

Project-only fuzzy declaration reads and `ALL` select scoped Kotlin files before
PSI enumeration. Declaration-kind constraints select eligible indexes or PSI
families before candidate capacity; exact names retain direct short-name indexes.
Mixed-family queries use one symbol pass. Package checks run after file-index
callbacks and before collecting declarations; excluded containers are still
traversed for eligible members. Library-inclusive fuzzy and filename reads retain
their contributor path. Returned byte/work/time qualifications remain explicit.

## Compact references and encoded response limits

Hosted exact and candidate references normally return `exact:v4:<digest>` and
`candidate:v4:<digest>` handles (73 and 77 ASCII characters). Pass them back
unchanged. The project host looks up the full token and performs the existing
authority and freshness checks. Source snapshot and continuation tokens retain
their existing formats.

The table is limited by `KAST_READ_HOST_REFERENCE_ENTRIES` (16,384) and
`KAST_READ_HOST_REFERENCE_BYTES` (33,554,432 UTF-8 bytes). Entries survive repeated
reads of the same epoch; an admitted epoch change or project disposal clears
them. Unknown handles fail as stale. Capacity keeps a valid inline token and
records `REFERENCE_INLINE_CAPACITY`, without evicting current-epoch handles.
Bounded counters also record handle issuance, restoration and rejection; logs
never contain the reference text.

The final query response guard measures actual encoded bytes. Oversized positive
results retain a qualified prefix, the original proven minimum, every item
failure, and existing limitations plus `BYTE_LIMIT_REACHED`. If the mandatory
evidence cannot fit, the response stays rejected.

Query continuations retain detached pipeline state and encoded-output suffixes in bounded project-owned stores. Each store applies the continuation entry, byte, and TTL limits. A checkpoint must fit `QUERY_CHECKPOINT_BYTES`, which cannot exceed `QUERY_CONTINUATION_BYTES`. Epoch movement and project disposal clear retained state; expired or evicted handles return `continuation-unavailable`. Returned-byte authority covers final items and failures, independently of bounded child-read bytes.

## Per-call execution budgets

`query_symbols` and `source_read`
accept an optional `execution_budget` object with
`max_elapsed_ms`, `max_work_units`, `max_results`, and `max_returned_bytes`.
Supplied numbers must be positive integers. Omitted controls select configured
defaults. Intent tools also normalize null controls to defaults. The IDE admits each dimension against the corresponding
`KAST_READ_EXECUTION_MAX_*` operator ceiling and applicable transport capacity.
These ceilings and transport byte capacities default to 2,147,483,646, leaving
one unit of headroom for signed JVM count APIs and overflow probes. A caller's
512 KiB or 1.5 MiB byte grant therefore requires no transport overrides. The
49,152-byte semantic/source defaults still apply when the caller omits a grant.
Explicitly lowered transport capacities still clamp grants and reject oversized
frames. The broker also uses the representation ceiling for semantic messages,
process I/O and client messages; tool payloads reserve 4,096 bytes within it for
the existing RPC envelope. This ceiling is not a promise that a near-ceiling
payload fits available memory. Result paging, retention quotas, schema admission,
concurrency and remaining hosted deadlines still apply. Raising a caller allowance cannot extend the configured host or client
deadline. The admitted time excludes already elapsed model work and reserves
publication headroom.

A supplied byte allowance must hold at least the serialized canonical schema and
operation identity. The host rejects smaller allowances before semantic dispatch.
This necessary bound is derived by the wire owner; passing it does not promise that
the response body, report, or continuation will fit. Final encoding still measures
the complete envelope and returns its existing finite outcome when publication cannot fit.

Read result metadata reports the selected default or caller value, operator
ceiling, effective value, and finite clamping reasons. Relation work units are examined
semantic relation items; cheap exclusions and replay verification are bounded by
the provider's candidate and elapsed limits. Result units are occurrence facts,
so two distinct call sites remain two results. A page-result allowance does not
change relationship or scope semantics. Byte fitting measures the complete
canonical response, including this metadata and any cursor.

Public query RUN requires complete execution. Public RESUME accepts only issued
`query-output:v1` tokens and presents already-produced output; an optional page
budget changes its presentation allowance, never the original semantic grant.
Internal pipeline checkpoints are not admitted public RESUME tokens. READ_RESULT
uses one immutable retained result with independent row and evidence cursors,
without running semantic providers. Storage capacity and expiry retain their
operator limits. Query result units are emitted symbol, occurrence, or traversal
record rows; work units include candidate refinement and child relation or walk
reads. Output replay preserves the original query identity and evidence and does
not renew retention expiry. A lost output suffix or unavailable checkpoint remains
an explicit delivery blocker; a tokenless qualified prefix is not a complete answer.

Source entity continuations remain owned by the original project. `SOURCE_CONTINUATIONS` bounds entries, `SOURCE_CONTINUATION_BYTES` bounds charged retention (default 32 MiB), and `SOURCE_CONTINUATION_TTL_MILLIS` bounds original token age (default ten minutes). The byte charge conservatively includes detached identity text, scope constraints, and object/container overhead; it is not a heap measurement. The store retains no source text or PSI. Replay and deduplication preserve the original creation time. Each output page also requires every referenced dependency to remain within its original age bound, so a younger page cannot extend an older cursor's validity. An active claim preserves physical storage until release; it does not permit fresh admission or publication after expiry. Expired or evicted tokens are rejected; reacquire a source selection and start a fresh read. A checkpoint that cannot fit is rejected before issuance.

Source result units are structural entities. Native source work units are visited PSI elements, checked before another unit starts; setup time and final-unit overruns remain charged. The query walk consumes relation records and attenuates each one-hop read under its aggregate work and time grant. Its semantic depth and strategy remain fixed across internal semantic checkpoints. The source output cap and query's final encoded-byte guard measure their respective responses.
