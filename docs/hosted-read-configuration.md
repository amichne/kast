# Configure semantic reads and diagnose limits

The existing-IDE read path admits an immutable settings policy when its project service starts. The CLI and provider admit their policy when their processes start. Defaults preserve the previous capacities and deadlines. The authoritative parameter identities, ranges and cross-limit checks are in [ReadLimits.kt](../kernel/src/main/kotlin/io/github/amichne/kast/kernel/ReadLimits.kt); `kast config schema --json` exposes the generated catalogue. Client exchange time must strictly exceed host connection time, and both provider invocation deadlines must strictly exceed client exchange time. Equality rejects configuration so an outer timer cannot expire at the inner boundary. Semantic and hosted query settings may remain equal because hosted admission reserves completion time before selecting the effective semantic grant.

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
| `HOST_QUERY_MILLIS` | 4,000 | milliseconds |
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
| `HOST_REQUEST_BYTES` | 16,384 | bytes |
| `HOST_RESPONSE_BYTES` | 65,536 | bytes |
| `HOST_DESCRIPTOR_BYTES` | 16,384 | bytes |
| `HOST_ACCEPT_BACKLOG` | 64 | count |
| `HOST_CONNECTIONS` | 16 | count |
| `HOST_CONNECTION_MILLIS` | 5,000 | milliseconds |
| `CLIENT_EXCHANGE_MILLIS` | 6,000 | milliseconds |
| `HOST_FILE_CHARACTERS` | 262,144 | characters |
| `HOST_CLASS_CANDIDATES` | 32 | count |
| `DIAGNOSTIC_SCOPE_FILES` | 256 | count |
| `DIAGNOSTIC_SCOPE_WORK` | 20,000 | count |
| `DIAGNOSTIC_SCOPE_MILLIS` | 2,000 | milliseconds |
| `DIAGNOSTIC_COUNT` | 1,000,000,000 | count |
| `DIAGNOSTIC_FAILURES` | 16 | count |
| `DIAGNOSTIC_FRAMES` | 8 | count |
| `DIAGNOSTIC_TEXT_CHARACTERS` | 256 | characters |
| `PROVIDER_OUTPUT_BYTES` | 524,288 | bytes |
| `PROVIDER_INVOCATION_MILLIS` | 1,080,000 | milliseconds |
| `PROVIDER_GRAPH_INVOCATION_MILLIS` | 1,260,000 | milliseconds |
| `PROCESS_INPUT_BYTES` | 4,194,304 | bytes |
| `PROCESS_OUTPUT_BYTES` | 67,108,864 | bytes |
| `PROCESS_TIMEOUT_MILLIS` | 1,260,000 | milliseconds |

## Read the evidence

The IDE's `idea.log` receives a `kast_semantic_read` JSON record by default after each admitted request drains, including rejected requests. No diagnostic enable switch is required. Each record includes the host/epoch correlation when available, stage durations, remaining outer deadline at semantic entry, bounded counters, exact native termination reasons, the final hosted outcome, and effective limit values with their sources. Early endpoint/transport failures emit `kast_hosted` records with a stage and closed failure code.

Unexpected native exceptions retain only the exception class and bounded Kast adapter class/method/line frames. Exception messages, causes, source payloads, file paths and opaque references are excluded. Counter and exception collection capacities are configurable too. A hosted `completed` outcome means execution returned; inspect the canonical response and termination reasons for semantic completeness. For example, `NAME_CAP` and `CANDIDATE_CAP` can both produce public `work-limit-reached`, while the log preserves the actual cause.

`CONFIGURATION_REJECTED` precedes semantic execution. `MODULE_ADMISSION_LIMIT` occurs during model capture, before the semantic budget starts. `TIME_LIMIT`, `WORK_LIMIT`, `RESULT_LIMIT` and `BYTE_LIMIT` identify different exhausted resources. `LIBRARY_POLICY_EXCLUSION` records an intentionally excluded library target; unresolved project targets still qualify coverage.

The [reproduction guide](../experiments/host-observation/SEMANTIC_REPRODUCTION.md) separates native setup, artifact pinning and replay. Its settings evidence reads the loaded service's admitted policy rather than assuming static defaults. The [review](reviews/hosted-semantic-reproduction.md) retains baseline findings and subsequent fixes separately.

## Deadline admission and search ordering

The host deadline covers admission, model capture, semantic evaluation, freshness
revalidation and detachment. Its default is 4,000 ms; semantic work retains a
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

`read_relations`, `traverse_relations`, `source_read`, `query_symbols`, all three `search_*` tools, and `query run`
accept an optional `execution_budget` object with
`max_elapsed_ms`, `max_work_units`, `max_results`, and `max_returned_bytes`.
Supplied numbers must be positive integers. Omitted controls select configured
defaults. Intent tools also normalize null controls to defaults; the legacy
`query run` grammar rejects explicit null execution controls. The IDE admits each dimension against the corresponding
`KAST_READ_EXECUTION_MAX_*` operator ceiling and applicable transport capacity.
These ceilings default to 2,147,483,646; existing semantic defaults, the 1,000-item
canonical page capacity, response bytes, and remaining hosted deadline still
apply. Raising a caller allowance cannot extend the configured host or client
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

Hosted output cursors can resume with a changed execution budget and page limit.
Each resume admits a new grant, while selector, relationship, authority and epoch
remain bound. Storage capacity and expiry retain their separate operator limits.
Query result units are emitted declarations; work units retain the query pipeline's
existing accounting for candidate refinement and child reads. An explicit `take`
remains part of query semantics. Query output suffixes and pipeline checkpoints
exclude execution allowances from their request identity. Reissuing the same
checkpoint reuses its token without renewing expiry; replay is non-consuming.
Each output page retains
known item failures, and its full canonical encoding includes the current grant.
These controls are implemented for relation and query/search reads. Traversal and
source remain part of the unfinished reliability work.

Source entity continuations remain owned by the original project. `SOURCE_CONTINUATIONS` bounds entries, `SOURCE_CONTINUATION_BYTES` bounds charged retention (default 32 MiB), and `SOURCE_CONTINUATION_TTL_MILLIS` bounds token age (default ten minutes). The byte charge conservatively includes detached identity text, scope constraints, and object/container overhead; it is not a heap measurement. The store retains no source text or PSI. Replay preserves the original creation time. Expired or evicted tokens are rejected; reacquire a source selection and start a fresh read. A checkpoint that cannot fit is rejected before issuance.

Source result units are structural entities. Native source work units are visited PSI elements, checked before another unit starts; setup time and final-unit overruns remain charged. Traversal result units are relation records in the graph, and aggregate work/time attenuate each one-hop relation read. Semantic traversal depth remains fixed across resume. Source and traversal now enforce their full encoded byte caps; automatic fitting of safely retained output is still an implementation gate.
