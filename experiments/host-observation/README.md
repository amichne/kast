# Host observation experiments

For agent evaluations, source controls, and cost comparisons, use the [Promptfoo suites](promptfoo/README.md).

For the installed semantic campaign through native MCP, use [CAMPAIGN_RESULTS.md](CAMPAIGN_RESULTS.md).

## Verification

Root `check` depends on `hostObservationTest`. Run it alone with `./gradlew hostObservationTest`.
The task runs the Python suites for semantic replay timing, MCP replay, and source scope failure documents.
It also runs the locked agent efficiency, log visibility, and campaign query suites with their Node session tests.
It does not run the Promptfoo Node suites. The [Promptfoo guide](promptfoo/README.md) lists their commands.

## Semantic replay timing

`mcp_replay.py` drives one installed native MCP session and drains one public query workload. The [semantic campaign](CAMPAIGN_RESULTS.md) uses it. It never starts, scripts, or restarts an IDE.

The drainer follows issued output continuations with `RESUME`. Under public contract 17, this action presents already-produced output. It does not continue semantic execution. A complete producer can still return a retained preview with unread rows or evidence. The drainer reads those pages with `READ_RESULT` and keeps the original output selection and execution budget. It advances the row cursor and evidence cursor independently. It stops when a checkpoint repeats, the host or epoch moves, a call fails, or the call limit is reached.

The campaign saves each call's appended Host log records in `hosted-observations.json`. Collection uses the original 250 ms limit, two MiB byte limit and one immediate log rotation. It waits for the response's single bound host/epoch receipt, its explicit `kast_transport_read` join and its connection release. It never sends another query to collect diagnostics.

`hosted-timing.json` retains transport stages, smart-mode waits and each joined read's diagnostic document and phase entries. Read-action waits, native calls and native-search callback counts keep their original qualifications in that document. Presemantic retries keep distinct read IDs under one connection. Missing joins, duplicate identities, ambiguous response correlation, partial stages and malformed records produce finite unavailable results with the observed prefix.

`JOINED` proves the observational join and complete transport stages; it grants no semantic or freshness authority. Stage, native-phase and nested wait clocks overlap and must not be added. `ACCEPT` can include listener idle time before invocation and is retained without attributing it to query latency. Missing fields in older diagnostic versions remain unobserved. Pure and scripted collector checks do not establish installed-host latency.

Each call also records `observationCaptureNanos`, the bounded wait for log records. Process and native clocks keep their original values.

Checkpoint admission emits diagnostic schema 13. The record includes the allowance, required estimate and five accounting components: tasks, identity rows, inputs, impact state and joins. These are the existing conservative storage estimates. They do not measure heap usage or encoded response bytes. Admission keeps the same accounting and byte limit.

Each admission records a finite success or capacity-rejection counter. The latest rejection snapshot survives later successful admissions. The record contains no source text, paths or selectors.
