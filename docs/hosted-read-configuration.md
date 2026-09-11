# Configure semantic reads and diagnose limits

The existing-IDE read path admits an immutable settings policy when its project service starts. The CLI and provider admit their policy when their processes start. Defaults preserve the previous capacities and deadlines. The authoritative parameter identities, ranges and cross-limit checks are in [ReadLimits.kt](../kernel/src/main/kotlin/io/github/amichne/kast/kernel/ReadLimits.kt); `kast config schema --json` exposes the generated catalogue.

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
| `HOST_QUERY_MILLIS` | 2,000 | milliseconds |
| `SEMANTIC_MILLIS` | 2,000 | milliseconds |
| `SEMANTIC_WORK` | 100,000 | count |
| `SEMANTIC_RESULTS` | 128 | count |
| `SEMANTIC_RETURNED_BYTES` | 49,152 | bytes |
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
