# Manual IntelliJ host observation

For the opt-in synthetic semantic-query matrix, use [SEMANTIC_REPRODUCTION.md](SEMANTIC_REPRODUCTION.md).

This experiment implements the observer-first slice through safe replacement. It runs the checked-in carrier in IDEA's bundled Kotlin scripting engine and observes one explicitly admitted open project. It does not participate in Kast readiness, workspace publication, VFS refresh scheduling, or index retention decisions.

The implementation starts from remote `main` at `74e47f4efe348036ddb8db5927eff1b6ed9e6382`. It preserves the existing worker recovery, coroutine test dispatcher injection, and exclusion of `.kotlin` from Gradle model inputs. Kast's platform dependency remains unchanged.

## Qualified host and lifetime

The carrier admits only IDEA build **262.10315.125** with Kotlin plugin **262.10315.125-IJ**, through the installed `org.jetbrains.kotlin.jsr223.Jsr223KotlincScriptEngineFactory`. The tested installation reports IDEA 2026.2.2, engine `Kotlin - Beta` / `2.4.20-ij262-52`, and JBR `25.0.4+1-b508.27`. Every invocation rechecks the host and records actual engine metadata. No engine download or custom classloader is used.

This is a manually operated experiment. **Detach before changing or unloading the scripting engine, Kotlin plugin, or other script dependencies.** Project disposal and explicit stop are qualified lifetime boundaries. Safe dynamic plugin unload, automatic startup, attach-only process discovery, classloader collection, and unattended deployment remain unqualified. Project ownership alone cannot establish plugin unload safety; see the [platform disposal guidance](https://plugins.jetbrains.com/docs/intellij/disposers.html).

The controller never launches an IDE. The separate, explicit host acceptance runner uses IDEA's native `ideScript` command, which **may launch IDEA**. A launcher exit code of zero does not establish that a script evaluated successfully; the checker requires correlated carrier receipts. If an engine exception exposes an out-of-memory cause, the bootstrap records `RESOURCE_EXHAUSTED` with the request ID and artifact digest; the controller reports an unmet precondition. Other engine failures remain `EXECUTION_UNCONFIRMED`. Neither command restarts a process for recovery.

## Operation

Run commands from the repository root. Choose fresh directories outside **every** open project and content root. The controller creates them with mode 0700; it never overwrites a previous invocation.

```sh
python3 experiments/host-observation/controller.py preflight --directory /private/tmp/kast-preflight-1
```

Evaluate the entire generated `/private/tmp/kast-preflight-1/console.kts` in IDEA's bundled Kotlin IDE Scripting Console. Then inspect the detached preflight result:

```sh
python3 experiments/host-observation/controller.py verify --directory /private/tmp/kast-preflight-1
python3 experiments/host-observation/controller.py attach \
  --directory /private/tmp/kast-attach-1 \
  --preflight /private/tmp/kast-preflight-1 \
  --project /exact/canonical/basePath/from/preflight
```

Evaluate the new `console.kts`. Attachment rechecks the process start instant, PID, build, and exact canonical project base path plus location hash. Focus and project display names do not select the target. Preflight alone registers nothing and proves no attachment.

```sh
python3 experiments/host-observation/controller.py status --directory /private/tmp/kast-attach-1
python3 experiments/host-observation/controller.py stop --directory /private/tmp/kast-attach-1
python3 experiments/host-observation/controller.py journal --directory /private/tmp/kast-attach-1
```

Stop writes a data-only, exact-session request. The original owner disconnects listeners, retires admitted callbacks and its tasks, finalizes output, and releases admission. The controller requires the original owner’s retirement marker and released admission before accepting a terminal status. A later engine never disposes another engine's objects. Controller exit codes are 0 for prepared/observed evidence, 1 for rejected input/evidence, and 2 for an unmet precondition. A historical receipt is not a host liveness check.

For replacement, stop the original session and prepare a fresh attach directory/session. The shared host/project admission directory rejects overlap even across independent engines and output directories. Missing acknowledgment or `RETIREMENT_UNCONFIRMED` does not authorize removing the admission directory. There is no force-unlock or restart fallback. The same original owner can complete retirement after a temporary stall clears.

## Evidence and bounds

`protocol.schema.json` contains named `Request`, `Receipt`, `Observation`, `Session`, `Control`, and `Evaluation` contracts. Its root validates `Receipt`. Boundary validators additionally enforce correlation, canonical paths/instants, duplicate-field rejection, byte limits, and relationships JSON Schema cannot express. Unexpected versions and fields are rejected.

The carrier observes background VFS batches (`VFS_CHANGES_BG`), project-scoped scanning/indexing histories, and dumb-mode entry/exit. Indexing completion/cancellation and dumb mode are diagnostic events. They do not prove current symbols, index persistence, compatible index seeds, or a Kast publication generation. VFS scope is the admitted project base/content-root snapshot; workspace-model changes are not tracked in this slice.

| Boundary | Limit |
|---|---|
| Request / carrier source / receipt or status | 16 KiB / 256 KiB / 128 KiB |
| Open project descriptors / content roots per project | 64 / 256 |
| Simultaneously admitted callbacks / queued records | 32 / 256 |
| VFS inspected events / retained paths / total path characters | 128 / 32 / 8192 |
| One path / encoded journal record | 1024 characters / 64 KiB |
| Journal segments per session | Four, each at most 1 MiB |
| Retained sessions per host/project | Four; only original-owner `RETIRED` proof permits eviction |
| Retirement confirmation deadline | 10 seconds; admission stays held on timeout |

Callbacks hold a small admission permit only long enough to project bounded detached data. They perform no file I/O, waits, semantic lookup, or refresh. Three owned daemon tasks provide the writer, control polling, and independent retirement. The queue does not carry project, PSI, VFS, or history objects. Loss metadata is retained outside the queue. The `dropped` counter counts rejected admissions, discarded transport records, and output-failure incidents; it does not count changed files or source changes.

Storage lives under `~/.local/state/kast-host-observation/<host-hash>/<project-hash>/`, outside open projects. Ownership uses atomic directory creation. Journals contain paths and bounded activity data, never source text. Status is replaced atomically; this does not claim crash durability for every journal record. `INITIAL_GAP` is always present. Overflow, projection limits, rotation, and output failures preserve explicit limitations. The retired-journal reader retains valid prefixes and reports truncated tails, rejected records, and sequence gaps; its result always declares `semanticAuthority: NONE`.

## Verification

Routine controller checks require only Python's standard library and are part of root `check`:

```sh
./gradlew hostObservationTest knowledgeImpact verifyKnowledgeBase
```

Exact carrier checks compile a byte-for-byte copy of the carrier declarations against the selected installation, then exercise its actual callback gate and parsers. This does not launch IDEA or substitute a lifecycle model for the owner:

```sh
python3 experiments/host-observation/check_carrier.py \
  --idea-contents '/path/to/IntelliJ IDEA.app/Contents' --jdk '/path/to/JDK-25'
```

Schema checks use an external test environment; no dependency is installed into IDEA:

```sh
python3 -m venv build/host-observation/schema-env
build/host-observation/schema-env/bin/pip install -r experiments/host-observation/requirements-test.txt
build/host-observation/schema-env/bin/python experiments/host-observation/check_schema.py
```

The opt-in acceptance command opens and trusts only its freshly created disposable fixture projects, edits fixture Java files, runs scoped fixture refreshes, tests the actual carrier, and closes its fixtures. It does not close the user's projects. A private evidence directory records requests, receipts, fixture stages, and the passed cases. Timeouts remain failures; the runner never restarts IDEA to obtain success.

```sh
python3 experiments/host-observation/run_host_acceptance.py \
  --idea-contents '/path/to/IntelliJ IDEA.app/Contents'
build/host-observation/schema-env/bin/python experiments/host-observation/check_schema.py \
  --evidence /path/printed/by/acceptance
```

The implementation uses JDK threads and synchronous pure checks, with no suspend functions or coroutine scheduler. `runTest` is therefore not needed in this slice. Any later coroutine consumer should use the repository's injected test dispatcher and `runTest` conventions.

See [ACCEPTANCE.md](ACCEPTANCE.md) for executed evidence and promotion limits. Incremental consumption/coalescing, targeted local refresh, workspace-model events, and all live-copy/seed work remain separate future slices. Ordinary Kast behavior and existing index ownership are unchanged.
