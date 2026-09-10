# Saved Kotlin direct-supertype query

The first hosted semantic deliverable runs a compiled, bounded K2 query in an
explicitly selected, already-open IDEA project. Select the name of a saved Kotlin
class with exactly one explicit supertype. Both declarations must be Kotlin
source in uniquely owned, supported Gradle source folders. The result contains
the canonical compiler identities and signatures of the child and its resolved
direct supertype, plus source ownership and content revision evidence.

This is a manually activated feasibility endpoint. It does not advertise the
production `relation.read` capability or construct a production workspace
publication. Its publication is a request-local snapshot validated against one
retained project epoch. Content means saved, PSI-committed IDE VFS content;
source provenance means the cached source-folder flag. Neither is a disk hash
or the isolated runtime's Gradle producer attestation.

## Run

Keep the chosen project open, successfully imported, and out of indexing mode.
Use the `Contents` directory of that running IDEA installation so compilation
and packaged compatibility metadata target its exact build.

```shell
./gradlew :workspace:intellij-read:hostedQueryPlugin \
  -PhostedIdeaHome='/path/to/IntelliJ IDEA.app/Contents'
python3 -m venv /tmp/kast-hosted-query-venv
/tmp/kast-hosted-query-venv/bin/python -m pip install \
  -r experiments/host-observation/requirements-test.txt
/tmp/kast-hosted-query-venv/bin/python experiments/host-observation/run_hosted_query.py \
  --idea-contents '/path/to/IntelliJ IDEA.app/Contents' \
  --artifact workspace/intellij-read/build/distributions/kast-hosted-query-0.1.0.zip \
  --project /absolute/path/to/kast \
  --file kernel/src/main/kotlin/io/github/amichne/kast/kernel/Refinement.kt \
  --offset 383
```

The offset is a UTF-16 offset inside the declaration name, not a line number.
In the current `Refinement.kt`, offset 383 selects `Refined`. Recompute it after
editing the file. The expected compiler-resolved relationship is
`Refinement.Refined` directly inheriting `Refinement`.

The runner requires one existing native IDEA process and the exact selected
open project. By default it loads the five compiled payload JARs through the
existing Kotlin plugin loader, invokes the project-owned service implementation,
and retires that original owner. Query execution never opens a project, links
Gradle, or refreshes the model. The service has no transport I/O; the manual
script writes the detached response after read access ends.

The printed evidence directory contains `report.json`: the artifact SHA-256,
host PID/build, unchanged project/model correlation, schema-validated result,
read-lock check, and confirmed retirement. Exit 0 means published; exit 2 means
a closed query rejection. Setup or unconfirmed retirement fails separately.
Lifecycle acceptance cases return 0 only when their required rejection and
cleanup evidence are verified. Validation uses closed typed outcomes and stays
enabled under Python `-O`. Invalid evidence returns 1 with an `evidence_rejected`
report and no confirmed-retirement claim. `carrier.json` distinguishes script
entry, bootstrap, result delivery, and retirement failures; an unconfirmed
carrier returns 1 without manufacturing a semantic result.

Add `--dirty-document-check` to verify dirty-document rejection using a temporary
file owned by the runner. This check requires the IDE to start with no unsaved
documents. It changes only its fixture's document, reloads that fixture from
disk afterward, and records `dirty-cleanup.txt` before confirming retirement.

The other opt-in acceptance cases use `--check`:

| Case | Controlled action and required result |
| --- | --- |
| `edit` | After K2 releases its read lock, temporarily edit and restore the selected document in a write command. Require content or epoch movement to reject the result, even though the original text is restored. |
| `cancel` | Cancel the calling scope after semantic detachment. Require `CANCELLED` and completed cleanup. |
| `retire` | Dispose the original service after semantic detachment. Require `RETIRED` at content revalidation. |
| `wrong-root` | Route a different canonical root to the retained Project. Require root-mismatch rejection. |
| `project-close` | Close the selected Project after semantic detachment. Require original-project disposal, zero substitute Projects, and `RETIRED`; then explicitly reopen the project in the same native host and verify its imported model and smart mode. |
| `plugin-lifecycle` | Load the archive through IntelliJ's plugin manager, query its actual platform-created light service, retire it, then unload with classloader-unload waiting enabled. Require successful unload and absence from loaded plugins. |

The checkpoint is outside platform read access and inside the existing request
deadline. It receives no PSI or compiler objects. Ordinary service construction
uses an unobserved checkpoint. Lifecycle effects belong to the opt-in driver;
they are not recovery paths inside query execution. Project restoration also
runs after a failed closure check, provided the original native host still
exists. The plugin-manager case may cause IntelliJ to rescan indexes during load
and unload. It leaves no installed plugin directory behind.

## Bounds and failures

- One admitted request per endpoint, with a two-second cooperative deadline.
  Cancellation drains the active computation before admission is released.
- Existing model-capture bounds apply. Each endpoint file is limited to 262,144
  characters; the transport input/output limit is 65,536 bytes.
- Any unsaved IDE document conservatively rejects the request, including dirty
  dependencies or documents in another open project. The query never saves them.
- Ambiguous ownership, generated/resource roots, external supertypes, unresolved
  symbols, unsupported declarations, indexing, model movement, content movement,
  and lost project authority produce typed rejection data with a bounded stage.
- Each read checks lifecycle and freshness, detaches compiler data, and rechecks
  saved content and the original epoch before publishing. PSI, K2 sessions, and
  compiler symbols do not become response fields.
- Detachment cancels and drains the original service, disconnects its epoch
  listeners, and permanently invalidates its endpoint. Project disposal cancels
  the same owner; it cannot redirect to another project.

## Validation

Run portable contracts against the repository-pinned SDK, without the local SDK
override, then package for the running IDE separately:

```shell
./gradlew :workspace:intellij-read:test verifyArchitecture \
  knowledgeImpact verifyKnowledgeBase
/tmp/kast-hosted-query-venv/bin/python -m unittest discover \
  -s experiments/host-observation -p 'test_*.py'
```

Tests cover endpoint identity, terminal retirement, stale permits, cancellation
drainage, deadline cleanup, path admission, unique supported source ownership,
and bounded wire failure details. Runner tests reject incomplete checkpoint,
restoration, project-identity, and unload evidence. These tests do not substitute
for live K2 resolution. Broad relation search, production transport, and full
hosted-runtime composition remain later deliverables.

### Recorded acceptance, 2026-09-10

The manual check on IDEA `262.10315.125` with Kotlin `262.10315.125-IJ`
successfully resolved `Refinement.Refined` to `Refinement` in the already-open
Kast root. Both endpoints belonged to `kast.kernel.main` and
`kernel/src/main/kotlin`. The final published artifact had SHA-256
`265778b9c68e4e8fadb09524f35b475e796522a555bbf9d392c71962f380e9fc`.

The report established the same native host PID and Project, zero new Projects,
unchanged successful Gradle import timestamps, no read lock during transport,
schema-valid detached results, and original-owner retirement. Separate live
checks rejected indexing with `PROJECT_ADMISSION_REJECTED / DUMB_MODE` and an
owned unsaved document with `DIRTY_DOCUMENTS`; the dirty fixture was restored.

The follow-up acceptance run established:

- A temporary edit restored before revalidation still rejected the in-flight
  result with `FRESHNESS_REJECTED / MOVED`.
- Caller cancellation returned `CANCELLED`; original-owner disposal returned
  `RETIRED`. Both drained successfully. A regression test now preserves the last
  observed stage through retirement instead of resetting it to request admission.
- Wrong-root routing rejected with `PROJECT_ROOT_MISMATCH`.
- Real project closure returned `RETIRED` at content revalidation. The driver
  reopened Kast in the original native process and verified its cached import
  and smart mode.
- The plugin-manager case resolved the known relationship through the actual
  injected project service and unloaded successfully with classloader-unload
  waiting enabled. The final run emitted no new severe IDE log entries.

All 73 IntelliJ read-module tests and 21 observer/controller/runner tests passed,
along with architecture and knowledge validation. The controlled edit and
closure races occur after K2 releases read access and before publication; they
do not force a write inside K2's locked computation. Restart-free upgrades
between different plugin versions and unattended production hosting remain
unqualified.

The resumed review added evidence validation that remains active under Python
optimization and bounded carrier outcomes. All 26 Python tests pass, including
a child run with `-O`. All eight earlier live reports pass the updated evidence
validator. A fresh live query initially failed before script entry because the
IDE scripting daemon had remained in its shutdown state after its idle timeout.
After stopping that identified idle daemon, IDEA recreated it and the same
native host published the known relationship and retired the query owner.
The runner reports carrier unavailability; it does not automatically restart
processes. This remains a limitation of the manual script carrier.

The project currently sets `org.gradle.dependency.verification=off` in
`gradle.properties`, as requested to unblock IDE source downloads. The existing
verification metadata is retained for a future restoration of strict checking.
