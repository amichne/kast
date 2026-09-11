# Dedicated hosted change fixture and baseline

The native fixture runner opens a separate IDEA process with private home, IDE
configuration, plugins, system, logs, runtime paths, and Gradle cache. It imports
one authored Kotlin Gradle project, waits for an exact semantic class read, and
keeps that process available until a bounded deadline or an explicit stop file.
It does not qualify source mutation or expose a change tool catalog.

Build the matched inputs with `:runtime:hosted:hostedPlugin` and
`stageKastControlProduct`, then run:

```shell
python3 packaging/run-hosted-acceptance-fixture.py \
  --idea-home /absolute/IDEA.app/Contents \
  --plugin /absolute/kast-ide-hosted.zip \
  --product /absolute/pristine-control-product \
  --report /absolute/hosted-fixture.json
```

Use absolute paths. The product must be a pristine staged control artifact with
no state, configuration, or runtime payload directory. The report gives the
private fixture path, dedicated PID, exact input digests, live read evidence, and
the sibling `.stop` path. Create that stop file after the caller finishes using
the fixture. Successful cleanup removes the private tree; failures retain it.
Only a successful read yields `semantic-readiness: completed`. Startup retries
are limited to the named unavailable-model and indexing states. Other failures
stop with bounded stage evidence. Executable search references stay in a private
file under the fixture and are excluded from the report.

The hosted SDK admission follows `ide-host-build` and `ide-kotlin-plugin-build`
from the repository catalog. It intentionally does not weaken the separately
pinned installed-worker admission in `acceptance_idea.py`. The external IDEA
application remains a read-only input. The runner copies no developer profile or
cache, uses a single exact trusted fixture path, suppresses the fresh-profile UI
tour, and lets IDEA import the real Gradle model. It synthesizes no modules,
source roots, library ownership, or semantic evidence.

## Observed baseline, September 11, 2026

A dedicated IDEA `262.10315.125`, bundled Kotlin `262.10315.125-IJ`, completed a
real production `KastProviderQualifier` → `Broker.dispatch` → CLI → hosted-plugin
`search_classes` call. It returned one exact declaration under live owner
`2c37dc4a-00fa-435f-832e-248731a397a5`, epoch 1, with `SAVED_PSI_COMMITTED` evidence.
The reference was passed unchanged into the production provider's `change_plan`
request. The request and raw results remained private.

The baseline planning route started a private coordinator and quarantined an
installed-worker reservation. The provider returned `MALFORMED_KAST_OUTPUT`;
the direct same-reference CLI call exited 4 with
`worker-control-recovery-required`. No plan was returned, and the source digest
remained unchanged. No worker endpoint or worker process identity was published.
This is evidence of an unwanted worker-acquisition attempt, not evidence that
planning remained within the hosted runtime. The private coordinator was then
disabled successfully. The developer IDE and its services were not changed.

The local evidence is `/private/tmp/kast-a-5oxbbrir/baseline-evidence.json`.
Artifact identity is:

| Artifact | SHA-256 |
| --- | --- |
| Hosted plugin archive | `6974013ff4595d83af66127c0177d4a62881b9e3e9ada47a227a62314ff8eee7` |
| CLI JAR | `05b84ee48acca754d1e4cc1521c3cff04948712190854c6e1e0cc422f97d5476` |
| App Server JAR | `6a9da1573dc0a9398a897fe9cac23eea7b6c580e9996a1692bf2228d7850aeb8` |

The artifacts report Git version metadata for `e408c4bd6`. Control staging ran
while other agents edited the shared working tree, so the hashes identify these
artifacts; a clean build of that commit is not claimed.

A separate fresh run of the reusable runner completed import and exact search
under owner `d74a3133-0ab7-42c4-b2d1-2b778e8ca6c4`, epoch 1, then retired the IDE
and private Gradle daemons and removed its fixture. Its receipt is
`/private/tmp/kast-a-5oxbbrir/reproducible-smoke-3.json`. Eight offline fixture
checks also pass. These observations qualify fixture readiness and baseline
rejection only; the complete hosted change workflow remains unqualified.
