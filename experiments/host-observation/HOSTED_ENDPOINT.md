# Existing IDEA index endpoint

The `runtime:hosted` plugin serves bounded semantic reads from an already open
IDEA project over a local Unix socket. The regular executable's `kast ide`
command is the primary client for class indexing. Normal requests use the
installed project service, the cached Gradle
model, IDEA's Kotlin stub index, and K2. They do not invoke the script carrier,
start an indexer process, open another project, or request a Gradle import.

## Build and use

Build against the exact running installation, then install the archive through
IDEA's **Install Plugin from Disk** action:

```shell
./gradlew :runtime:hosted:hostedPlugin \
  -PhostedIdeaHome='/path/to/IntelliJ IDEA.app/Contents'
# Archive: runtime/hosted/build/distributions/kast-ide-hosted-0.1.0.zip
./gradlew :cli:installDist
cli/build/install/kast/bin/kast ide status --root /absolute/path/to/kast
cli/build/install/kast/bin/kast ide classes Refinement --root /absolute/path/to/kast
cli/build/install/kast/bin/kast ide generate-completion zsh
```

`--root` defaults to discovering the Gradle root from the current directory.
The native CLI selects this command family before isolated-product bootstrap;
it can query an installed hosted plugin without configuring a sidecar runtime.
Help and completion for Bash, Zsh, and Fish run locally. The client validates
the shared schemas, exact response root and class name, and descriptor host
identity, and rejects duplicate JSON fields and trailing documents. Following
the CLI's existing outcome convention, received JSON goes to stdout with exit
0; inspect `outcome` or `type` for semantic/host rejections. Client boundary
failures go to stderr with a nonzero exit status.

The Python acceptance client additionally exposes the manual direct-supertype
operation:

```shell
python3 -m venv /tmp/kast-hosted-query-venv
/tmp/kast-hosted-query-venv/bin/python -m pip install \
  -r experiments/host-observation/requirements-test.txt
/tmp/kast-hosted-query-venv/bin/python experiments/host-observation/kast_ide.py \
  --root /absolute/path/to/kast status
/tmp/kast-hosted-query-venv/bin/python experiments/host-observation/kast_ide.py \
  --root /absolute/path/to/kast classes Refinement
/tmp/kast-hosted-query-venv/bin/python experiments/host-observation/kast_ide.py \
  --root /absolute/path/to/kast supertype-of io.github.amichne.kast.kernel.Refinement.Refined
/tmp/kast-hosted-query-venv/bin/python experiments/host-observation/kast_ide.py \
  --root /absolute/path/to/kast supertype \
  kernel/src/main/kotlin/io/github/amichne/kast/kernel/Refinement.kt 383
```

The `supertype` offset is a UTF-16 position in a class name. Recompute it after
editing the source. `status` establishes endpoint identity and advertised
operations; each semantic request must separately establish project readiness.
For the Python client, exit 0 means a schema-validated answer, 2 means a typed host or semantic
rejection, and 1 means client admission or transport failure. A missing IDE
endpoint remains unavailable; this client has no isolated fallback.

## Ownership and protocol

The project service owns a private directory under
`~/.kast/ide-hosted/<root-digest>/`, an exclusive file lock, `host.sock`, and
`endpoint.json`. The digest is the first 16 SHA-256 bytes of the canonical root.
The descriptor binds that root, socket path, host PID, protocol version, and
closed operation list. A competing owner or unknown socket/descriptor is
rejected. Retirement removes only files whose filesystem identity matches the
original owner; it retains the lock file for later attachment.

Each socket connection carries one request: a four-byte big-endian byte length
followed by strict UTF-8 JSON. Request frames are limited to 16 KiB and responses
to 64 KiB. The host admits connections serially, with a five-second connection
budget around framing, the two-second semantic budget, and response delivery.
Cancellation drains original semantic work before ownership is released.
Plugin unload and project disposal cancel the platform-injected service scope.
No read lock spans socket I/O.

Operations are `DESCRIBE`, `CLASS_LOOKUP` with an exact `name`, and
`DIRECT_SUPERTYPE` with either an exact `qualifiedName`, or a relative `file`
and nonnegative UTF-16 `offset`. Mixing the two selectors is rejected. Qualified
selection uses the existing full-class-name index, requires exactly one candidate,
and checks its resolved compiler identity inside the same admitted read. Missing,
ambiguous, or mismatched declarations have distinct closed failures.
All require the exact canonical `root`. Unknown operations, duplicate keys,
extra fields, malformed UTF-8, and oversized frames fail closed. Descriptor and
host responses use [the endpoint schema](hosted-endpoint.schema.json); semantic
answers use [the query schema](hosted-query.schema.json).

The service retains one packaged compatibility policy authority across repeated
requests. Reconstructing an equal-looking policy per request would lose the
original admission proof and correctly cause `RETAINED_AUTHORITY_MISMATCH`.
IDE log events record bounded `BIND`, `REQUEST`, and `RETIREMENT` stages with
`STARTED`, `COMPLETED`, `REJECTED`, or `CANCELLED` outcomes. Request completion
means transport delivery, including delivery of a typed semantic rejection.

## Qualification

```shell
./gradlew :workspace:intellij-read:test :runtime:hosted:test verifyArchitecture \
  knowledgeImpact verifyKnowledgeBase
./gradlew :cli:test :cli:nativeTest :cli:verifyMintlifyCallableReference
./gradlew -p build-logic test
/tmp/kast-hosted-query-venv/bin/python -m unittest discover \
  -s experiments/host-observation -p 'test_*.py'
/tmp/kast-hosted-query-venv/bin/python experiments/host-observation/qualify_hosted_index.py \
  --root /absolute/path/to/kast \
  --idea-contents '/path/to/IntelliJ IDEA.app/Contents'
# Add --with-supertype to exercise qualified selection, renaming, ambiguity,
# restoration, and removal through the same admitted relation read.
```

The last command is an explicit acceptance effect for the Kast repository. It
creates one uniquely named fixture under `kernel/src/main/kotlin`, refreshes
that owned file in the original IDE, renames its class, then removes the file.
Every update checks ownership before replacement or cleanup. Normal queries
never perform these writes or refreshes. Its report records the same host PID,
class counts, index authority, and confirmed cleanup. Failed queries retain
bounded client/host stage and failure evidence.

On 2026-09-10, IDEA `262.10315.125` / Kotlin `262.10315.125-IJ` served repeated
socket requests in the existing Kast project. The incremental sequence returned
counts `1, 0, 0, 1, 0` for creation, absent future name, old-name removal,
new-name discovery, and final removal. The fixture was removed. Explicit
plugin unload retired the socket and descriptor; a client then returned
`HOST_UNAVAILABLE`. Reattachment in the same native process served queries again.
The built native `kast ide` executable then described that same host and returned
six compiler-resolved `Refinement` declarations. Portable command tests cover
help, completion, exact-root routing, and unavailable hosts; native socket tests
cover complete, oversized, truncated, and invalid-UTF-8 responses.

Qualified selection also resolved `io.github.amichne.kast.kernel.Refinement.Refined`
to `io.github.amichne.kast.kernel.Refinement` in that running host. Portable tests
cover missing and duplicate candidates and compiler-identity mismatch. The new
`--with-supertype` incremental acceptance sequence was attempted, but its script
carrier did not confirm fixture refresh. Disk cleanup completed; the refresh
receipt and expanded live sequence remain unqualified. This failure did not
prevent direct socket queries from resolving the saved repository declaration.

`manage_hosted_endpoint.py` and its script template provide explicit development
load/unload of an already unpacked owned plugin. This management path uses
IDEA's script carrier; ordinary socket requests do not. The [manual-query
runbook](HOSTED_QUERY.md) records the carrier's independent limitations.
Do not overwrite loaded or previously loaded JARs in place: script compiler
processes may retain open archive mappings even after plugin unload. Use atomic
file replacement after unload. One development reload produced a script-daemon
metadata parsing failure while the socket continued serving valid index results.
The identified idle script daemon was stopped manually before repeating the
acceptance check; the client has no process-restart recovery path. The fixture
driver records disk removal separately from confirmed IDE refresh.

## Remaining boundaries

Class lookup is exact-name discovery of up to 32 authored-source Kotlin classes,
including a complete empty answer. Saved and PSI-committed content and one
retained epoch are required. Results preserve cached source-folder provenance;
they do not claim producer-attested source-set ownership or disk hashes.

General semantic CLI commands, App Server, and the workspace publication graph
still use their existing isolated assembly. Integrating those contracts needs
explicit hosted routing and a proven model/publication mapping. This endpoint
does not advertise broader production operations. Full IDE restart startup,
multiple simultaneously open roots, and crash-left endpoint cleanup still need
separate qualification. Unknown leftover artifacts are preserved and rejected.
