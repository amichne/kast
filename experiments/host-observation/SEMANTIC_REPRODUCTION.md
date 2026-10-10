# Native semantic-query reproduction

`reproduce_semantic_queries.py` creates a compiling synthetic Kotlin fixture, pins a loaded CLI/plugin pair independently, and replays public queries against an already imported IDEA project. Its [report](../../docs/reviews/hosted-semantic-reproduction.md) separates release observations from instrumented mechanism evidence.

Use Python 3.11+ with the existing `requirements-test.txt` (`jsonschema`), JDK 25, and `kotlinc` for the optional production-provider harness. Use the packaged v0.38.0 control CLI and its checksum-verified hosted plugin for IDEA build 262.10315.125. Install/load the plugin explicitly using the existing [endpoint workflow](HOSTED_ENDPOINT.md); do not replace a running user's installation implicitly.

Create a fresh fixture outside the repository and every other open project:

```sh
python3 experiments/host-observation/reproduce_semantic_queries.py setup \
  --fixture /private/tmp/kast-semantic-fixture \
  --output /private/tmp/kast-semantic-setup
```

Explicitly open and import that fixture's Gradle build in IDEA. Wait for import and indexing to finish, and save/commit documents. All imports and fixture edits must finish before replay, including imports in other projects that can advance the global PSI modification epoch. Setup never imports; replay never opens, imports, refreshes, invalidates caches, or starts an isolated worker.

```sh
python3 experiments/host-observation/reproduce_semantic_queries.py pin \
  --fixture /private/tmp/kast-semantic-fixture \
  --cli /path/to/v0.38.0/control/bin/kast \
  --idea-contents '/path/to/IntelliJ IDEA.app/Contents' \
  --output /private/tmp/kast-semantic-pin
```

When several IDEA profiles use the same executable, pass `pin --host-pid PID`
to select an existing process. The PID must appear with the exact executable in
the current process observation. Without this option, exactly one matching
process is required. Set `IDEA_PROPERTIES` and `IDEA_VM_OPTIONS` for the intended
profile before invoking its script carrier. A mismatched carrier fails native
PID admission; the runner never launches a replacement host.

`replay-workloads` retains the pinned PID for both boundary pins and requires the
same process start identity. A changed process, reused PID or missing host
rejects replay even when artifact bytes match. Loaded classes, project/model
identity and effective query limits still require independent qualification.

For the current relation work comparison, use
`--qualification-slice RELATION_WORK_REDUCTION`. This profile admits only public
contract version 14. It requires the common and branch-proof owners. It also
requires 19 owners for admission, file enumeration, SDK capture, callback reuse
and read accounting. Missing owners or another contract version reject the pin. The
older version 6 and 7 profiles retain their separate requirements. Match loaded
resources and all plugin JARs to the exact tested candidate before replay;
profile admission alone does not prove source correspondence.

One replay command runs the complete matrix, retains canonical CLI responses, and exercises the production provider without a Codex session:

```sh
python3 experiments/host-observation/reproduce_semantic_queries.py replay \
  --fixture /private/tmp/kast-semantic-fixture \
  --cli /path/to/v0.38.0/control/bin/kast \
  --pin /private/tmp/kast-semantic-pin/pin.json \
  --surface provider --repeats 2 \
  --output /private/tmp/kast-semantic-replay
```

Use `--surface cli` to exercise direct CLI behavior. Each output directory must be fresh. The provider harness compiles against the selected distribution's actual App Server jar and delegates to its production process executor, recording the underlying command, stdin, stdout, stderr, exit, elapsed time and envelope. Its process startup/qualification time is separate from native semantic-stage duration. `pin.json` records executable/JAR hashes, loaded plugin class hashes, IDEA/Kotlin/JBR versions, imported Gradle projects/source sets, IDEA modules/roots, and effective limits. Source checkout hashes are labelled unproven correspondence until matched to an actual build/release receipt. The runner does not equate a CLI version with a plugin version.

`receipts.json` contains one classification per case, independent expected identities, actual identities/counts, occurrences, aggregate and connection coverage, host/epoch, assertions, and diagnostic correlation. Raw `request.json`, `response.json`, `process.json` and provider process receipts remain beside each case. A nonmatching observation is a successful measurement (exit 0); missing execution prerequisites give exit 2. Stale authority/epoch drift is blocked evidence. Keep local receipts private: they include host paths and opaque references. No receipts belong in public commits.

`semantic-fixture/expected.json` is an independently authored source oracle. It includes five helper calls, six alias type uses (four constructor owners, a builder and a top-level function), four concrete descendants and three overrides. It never decodes references or derives the expected declarations from Kast. The runtime-issued tokens must survive byte-for-byte, and repeated references are a separate deduplication control. Fields are requested independently. Constructor-property discovery failures remain visible in `ALL`; the oracle does not remove them to match output.

For causal experiments, run setup in separate directories, import explicitly, then pin and replay serially. Change one dimension at a time:

| Setup option | Independent variable |
|---|---|
| `--callee kotlin` (default) | One Kotlin helper call; complete control |
| `--callee java` | Adds only `java.lang.System.nanoTime()` |
| `--callee outside` | Adds a Kotlin helper in another retained package/module |
| `--java-references` | Adds Java-only manager-class references |
| `--noise-modules N` | Unrelated Gradle projects; IDEA module count is measured separately |
| `--noise-names N --noise-kind class\|function` | Unrelated declarations inside one admitted module |

`--cases all-core-alias,core-alias` selects the same tiny package/type-alias ALL, exact and fuzzy controls at each load. Other useful prefixes are `trace-callees`, `manager-references` and `unused-references`. `first` means first invocation in that replay, not a cold IDE cache. No timing threshold is tied to developer hardware. Production budgets remain unchanged.

For diagnosis **after** capturing unchanged-release behavior, build the instrumented plugin with `:runtime:hosted:hostedPlugin -PhostedIdeaHome=...`, explicitly load it, and pin it into a new evidence directory. Its version may equal the release version; its hashes do not. Current builds log diagnostics by default; earlier opt-in diagnostic builds require their historical enable property. Add `--idea-log /path/to/idea.log` during replay. Restore temporary experiment settings and the selected plugin after the experiment, unless the task explicitly calls for installing the corrected build.

One `kast_semantic_read` JSON record is emitted after the read drains. Finite enums identify stages, contributors, counters and termination reasons; counts saturate. The record has no names, source text, paths or references. It records remaining outer time at semantic entry, module/root admission, name/candidate caps, filtering, K2 refinement, relation facts, native unsupported targets, and time/work/result/byte limits. The collector reads only a bounded appended log segment and reports rotation/overflow gaps. Name/candidate caps may map to public `work-limit-reached`; the diagnostic reason preserves the narrower cause. Host-only correlation before authority admission never invents an epoch. A diagnostic `completed` outcome means the hosted boundary returned; it does not assert complete semantic coverage. Contributor-specific completion can coexist with another contributor's cap. Current execution logs by default. The effective policy and bounded unexpected-failure frames accompany the request receipt; see [configuration](../../docs/hosted-read-configuration.md).

Run the focused deterministic checks with:

```sh
./gradlew hostObservationTest :workspace:intellij-read:test \
  --tests '*HostedReadDiagnosticsTest' --tests '*HostedQueryExecutorTest' \
  :symbol:intellij:test --tests '*SymbolDiscoveryTest' :relation:intellij:test
./gradlew productBuildGate knowledgeImpact verifyKnowledgeBase
```

The fake-clock deadline test proves ordering and owner recovery only. Native receipts establish actual discovery and K2 behavior. This runner is opt-in; it adds no routine native CI matrix.

## Compare complete workloads locally

The `replay-workloads` and `compare` modes extend this runner's pins, process
captures, source fixtures and native receipts. They use the installed public
Tool RPC path, without a model or API call. Both profiles include four workloads.
The older `replay` mode remains
available for its historical matrix.

The default `RELIABILITY_FIXTURE` profile is a synthetic control and cannot
establish an efficiency improvement. `--workload-profile KAST_SOURCE` selects
production source: exact `QueryPlanSyntax` with source, `QueryStepSyntax`
references across the workspace, and public kernel functions through scoped
`ALL_DECLARATIONS`. Full compiler evidence and exhaustive terminal coverage are
required, alongside independently selected declaration/site witnesses. The
witnesses are sufficiency checks; they do not replace comparison of every row,
qualification, omission, failure, source range and scope.

`exact-negative` searches for `KastReplayNegativeDeclaration9C22D9` with the same
exact-name, class-kind and main-source directory constraints as the positive
exact search. It requests name, location and signature. A usable negative answer
must be complete, exhaustive and empty, with no failures or omissions. Partial
emptiness and returned items fail the control. Empty output does not imply zero
enumeration work. Every warmup and repetition must include this control before
the suite can establish improvement.
Older three-workload runs require recapture; the loader rejects that corpus.

Use one immutable `git archive --format=tar` of a full local commit for both
artifacts. Extract it into an owned directory, prepare its build conventions,
then import it through the existing IDE workflow. The runner verifies the
retained archive against that Git object and every fixture file. Git metadata,
IDE metadata and generated `build`, `.gradle` and `.kotlin` files are excluded
from source inventory. No source file can change between captures or comparison.
For this machine's clean snapshot, preparing `build-logic:compileKotlin` with
`--no-build-cache --rerun-tasks` repaired incomplete Kotlin DSL accessors before
IDE import; setup is outside workload timing.

With the immutable source imported and the selected artifact pinned, the
production reproduction command is:

```sh
build/python-tests/env/bin/python3 experiments/host-observation/reproduce_semantic_queries.py replay-workloads \
  --workload-profile KAST_SOURCE \
  --source-archive /private/tmp/kast-source.tar --source-commit FULL_COMMIT_SHA \
  --fixture /private/tmp/kast-source \
  --cli /absolute/path/to/installation/bin/kast-mcp-complete \
  --pin /private/tmp/baseline-pin/pin.json \
  --idea-contents '/absolute/path/to/IntelliJ IDEA.app/Contents' \
  --idea-log /absolute/path/to/idea.log \
  --warmups 1 --repeats 2 --output /private/tmp/baseline-run
```

This profile declares 10000 ms / 100000 work units / 20 results / 49152 bytes
before execution, unchanged for every RUN and RESUME on both artifacts. The
synthetic profile keeps its original 2000 ms grant. Two-second production probes
that hit reference inventory limits remain incomplete evidence; no grant is
increased within a trial. Warmup failures remain in raw runs and prevent their
comparison admission. Existing host caches are retained; this is not a cold
cache experiment.

The log capture allows at most 250 ms for an asynchronously appended native
receipt, under the existing 2 MiB append bound. Its policy is recorded in run
limits and must match. It never retries a query. Missing or ambiguous receipts
remain unavailable. Public arrival timestamps are captured before log collection.
Workload wall time excludes prior capture intervals but includes other inter-call
driver work. Process, native-phase and stage clocks retain their own values and
must not be added together. See [timing collection](README.md#semantic-replay-timing)
for explicit connection joins, release evidence and version-2 trial receipts.

Prepare with the existing `setup` command plus `--comparison-workloads`. This
copies the existing dense-reference target, 1001-reference source and 64-function
page source into the fixture's imported logging module. It leaves the historical
setup unchanged when the flag is absent. Import explicitly, finish indexing,
and keep the same fixture directory and content for both artifacts. Keep other
clients idle while observing the host. Do not delete caches between runs.

Install/select each artifact explicitly using the existing installation workflow.
Pin its `kast-mcp-complete` executable with `pin --public-mcp`, the same
fixture, and `--idea-contents`. Keep pins and raw receipts private. A pin records
executable/JAR hashes, loaded plugin class hashes, IDEA/JBR/Kotlin versions,
model/settings/limits and source hashes; a version string alone is insufficient.
The installed sibling Tool RPC supplies the catalog without opening a workspace;
its executable hash is pinned too. Replay also requires identical input schemas
from the actual MCP `tools/list` response. Source
checkout correspondence remains unproven without an independent build receipt.

With an imported fixture and its current pin, this is the reproduction command:

```sh
python3 experiments/host-observation/reproduce_semantic_queries.py replay-workloads \
  --fixture /private/tmp/kast-semantic-fixture \
  --cli /absolute/path/to/installation/bin/kast-mcp-complete \
  --pin /private/tmp/baseline-pin/pin.json \
  --idea-contents '/absolute/path/to/IntelliJ IDEA.app/Contents' \
  --idea-log /absolute/path/to/idea.log \
  --warmups 1 --repeats 2 --output /private/tmp/baseline-run
```

It runs exact `FixtureLogger` with source, dense `REFERENCES` occurrences, and
scoped `ALL_DECLARATIONS` with public-function filtering. The authored oracle is
one exact declaration with source, 1001 exact occurrences (including the import),
and 74 public functions, including the 64 existing page functions. Every trial
starts a fresh search. Only that trial's successor checkpoint is used to resume.
The fixed grant is 2000 ms, 100000 work units, 20 results and 49152 returned bytes
per public call, at concurrency one. Requests are validated against the pinned
installed catalog. Replay stops at terminal completion, a rejection, an
increase-grant requirement, epoch movement, a repeated checkpoint or 512 calls.
It never changes a grant to force completion. A canceled process or harness
timeout stops the whole replay because native drainage is unproven.

Each workload has one fully retained warmup and two measured repetitions by
default. Setup, pinning and import time are excluded. First usable result means
arrival of a nonempty row with the requested source or compiler evidence; it is
separate from sufficient evidence for the complete answer. Completion time runs
from the initial public call through receipt of the terminal reply, including
public dispatch and continuation orchestration. The persistent MCP process and
its initialize/catalog control calls occur before the workload clock; their
exact wire receipts and stderr are retained separately. The manifest records
`MCP_SESSION` or `TOOL_RPC`, and different transports are incompatible. Recording
between calls contributes
to this local runner's completion latency. Post-terminal recording is excluded.

Use one persistent installed MCP session for the entire run. Its existing owner
prepares the workspace once; each trial still begins with a fresh public RUN.
One-shot `pin --public-rpc` is retained, but its repeated workspace preparation
can exhaust the IDE's 256 lifecycle operations before this dense workload drains.
No capacity override, enlarged query grant, or backend fix is applied. The harness
call cap is 512 because the fixed byte grant can fit only three dense occurrences
per public reply, requiring 334 calls. Public calls and native provider pages are
separate measurements.

For temporary native instrumentation, use the existing `hostedPlugin` build and
`manage_hosted_endpoint.py` load/unload workflow. Complete setup/import and all
active reads before changing plugins. Dynamic load starts the existing application
lifecycle owner before the project endpoint; it does not replay IDEA's startup
listener. Preserve the installed plugin path/hashes and restore it after capture.
Pass an explicit `-Pversion=VERSION` when building a candidate, select that
exact output archive, and match its packaged changed-class hash to the compiled
class before loading. Pin the loaded class again; a similarly named older archive
is not correspondence proof. Native pin failures retain a bounded `PIN_CAPTURE_REJECTED` stage receipt rather
than relying on a silent script failure.
Keep artifact assembly and focused test builds sequential: they share compiler
outputs. For a controlled pair, build both with the same flags and verify the
unpacked JAR inventories; this optimization changes only the relation contract
JAR. A full serial assembly with `--no-build-cache --rerun-tasks` repairs partial
outputs before capture. Never replace a loaded artifact in place.

Record another run of the same baseline before changing artifacts. Then install,
pin and replay the candidate with identical content and policy. Compare any two
run manifests with:

```sh
python3 experiments/host-observation/reproduce_semantic_queries.py compare \
  --baseline /private/tmp/baseline-run/run.json \
  --candidate /private/tmp/candidate-run/run.json \
  --output /private/tmp/comparison.json
```

`comparison.json` retains each repetition independently; it does not average
away failed, canceled or incomplete trials. `COMPARISON` contains trial outcomes
`EQUIVALENT`, `SEMANTIC_REGRESSION`, `MISSING_EVIDENCE` or `INCOMPLETE`.
`INCOMPATIBLE` rejects changed fixtures, requests, budgets, environment,
settings, warmth, repetitions or concurrency. `INVALID_EVIDENCE` rejects invalid
or missing receipts. Native manifests must retain IDE/JBR/Kotlin versions and
nonempty CLI/plugin JAR and loaded-class hash evidence; parsed trial states retain
the admitted `TrialState` enum. Exit 0 requires every measured pair to be equivalent with
all required measurements; exit 2 retains nonqualifying observations.

A suite can report `lessWork` when at least one measured trial reduces work and
every measured pair has equivalent sufficient evidence with no work increase
or worse observed locator outcomes.
Unchanged workloads need not improve. Distinct native artifacts and the admitted
production source profile are required; self-comparison and synthetic controls
always keep `lessWork: false`.

Semantic comparison preserves declaration and occurrence identity, compiler
signatures/digests, source text/ranges, ownership, provenance, coverage,
qualifications, failures, omissions, scope/universe and ordering. It normalizes
opaque symbol/candidate handles only to their companion semantic identity, and
host/epoch only after proving the workload stayed on one basis. It never decodes
or replays captured tokens. Paths are not normalized. Resumable prefix
qualifications remain in raw receipts; after a successful full drain the terminal
qualification owns completion. Discovery quantities/durations are measurements,
not part of declaration identity. A terminal incomplete answer cannot qualify
for a work claim. Different row order is conservatively rejected.

Measurements keep public calls, actual UTF-8 stdout bytes (including the RPC
envelope/newline), native pages, counter/contributor counts, first usable and
completion nanoseconds, and per-call native phase/stage durations distinct.
Diagnostic schema 6 explicitly observes returned discovery/relation pages,
including qualified pages, and locator retention/rejection and epoch preemptions.
Unused counters in this set retain explicit zero observations; other absent
counters remain unavailable. Rejected-before-provider calls and canceled attempts
that produce no page retain explicit zero counters. Completed page observations
remain recorded when later work fails or is canceled; request-local zeros prove
observation capability. Older missing page
counters remain unavailable. There must be exactly one bound native receipt in
each bounded appended log window, with both page counters explicitly observed
in every receipt; ambiguity, rotation, saturation or absent
measurements block a work claim. Absent counters are not zeros. Phase and stage
vectors overlap and are never summed. Wall time does not establish CPU cost.

`HostedSemanticServices.exactIssued` records exactly one retained or rejected
outcome after each completed locator-retention pipeline attempt. Both raw outcome
counts remain in `deltas.counters`; their sum is separately reported as
`deltas.locatorRetentionAttempts` with baseline, candidate and delta quantities.
Every production receipt must explicitly contain both labels, including zero.
The work comparison uses completed attempts and all other counters; it also
requires no increase in rejected outcomes and no decrease in retained outcomes.
Changing outcome proportions alone cannot establish less work. Retained outcomes
can include existing tokens, so these quantities do not establish unique stored
locators, allocation cost, store calls or future per-token revalidation availability.

`lessWork` requires equivalent sufficient evidence, every required measurement,
and a componentwise nonincreasing observed work vector with at least one
strict decrease. A smaller response or lower elapsed time alone cannot set it.
Identical artifact hashes always produce repeatability evidence with
`lessWork: false`. Scripted receipts and the explicitly marked synthetic source
fixture also keep `lessWork: false`; their counter differences remain visible.
An improvement claim requires a representative native workload with the same
comparison contract. The subsequent reference-row bound optimization is qualified
separately in the baseline report; synthetic and self-comparisons cannot qualify it.

Focused checks:

```sh
build/python-tests/env/bin/python3 -m unittest discover \
  -s experiments/host-observation -p test_semantic_comparison.py
./gradlew hostObservationTest verifyJsonContracts verifyKastArchitecture \
  knowledgeImpact verifyKnowledgeBase \
  :workspace:intellij-read:check :symbol:intellij:check :relation:intellij:check
```

The [baseline report](../../docs/reviews/semantic-replay-comparison.md) distinguishes
scripted comparator checks from completed installed native repeatability.
