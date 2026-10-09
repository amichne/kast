# TRACE completeness validation suite

This opt-in suite validates two compiler-static TRACE gaps against one frozen
Gradle fixture. It uses a private imported IntelliJ project and matching Control
and Host artifacts. It must not run against the daily IDE profile.

The latest [implementation record](VALIDATION.md) records the native acceptance,
exact candidate inventory, evidence archives and cleanup.

The fixture contains selected upstream source and authored controls. Read
[NOTICE.md](NOTICE.md) for revisions, adaptations and licenses,
[ORACLE.md](ORACLE.md) for expected results, and the
[execution plan](../TRACE_COMPLETENESS_PLAN.md) for scope and acceptance gates.
The fixture retains its original r1 manifest and SHA-256 digest. The eleven
active RUN requests use the current fixed-policy contract without a completion
selector. Their request manifest and digest bind those exact arguments. The
implementation record retains the historical r1 request digest and native
results; it does not qualify these updated requests. Request paths are relative
to the selected fixture root.

## Prepare a private run

Allocate a new path beneath an owned temporary parent. `prepare` rejects an
existing root and copies only the fixture and active manifests:

```sh
python3 experiments/host-observation/trace-completeness/run.py prepare \
  --owned-root "$trace_owned_root"
```

Provision the following beneath that root through the existing disposable native
host procedure. Provisioning is a separate effect boundary and needs explicit
authorization for native execution.

- `IntelliJ IDEA.app`, `config`, `system`, `plugins`, `logs`, `h`, `gradle` and `tmp`.
- `idea.properties` and `idea.vmoptions` with all private paths and private
  `user.home`. Pass these files when launching IDEA and every `ideScript` call.
- `control` containing the matching Control distribution, metadata,
  `config/environment` and its `bin/kast-tool-rpc-complete` launcher.
- `catalog.json` from that Control's real tool catalog. Install Python
  `jsonschema` in an owned environment for the runner.

Import `fixture` through Gradle with its pinned dependencies. Verify the authored
main and test source roots, saved and committed documents, and smart indexes.
Record the private host PID and exact candidate version. These commands do not
install, launch, import or restart an IDE automatically.

## Pin, run and verify

Write a carrier bound to that exact PID and owned root:

```sh
python3 experiments/host-observation/trace-completeness/pin.py \
  --owned-root "$trace_owned_root" --pid "$trace_host_pid"
```

Execute the generated `candidate-pin.kts` using the private IDEA's `ideScript`
entry point and private environment. The resulting `candidate-native-pin.json`
must report `READY`. The carrier records loaded class hashes, compiler-visible
source hashes, actual Gradle roots and private paths. The runner compares those
hashes with installed jars and disk bytes, then checks the live Host endpoint.

```sh
python3 experiments/host-observation/trace-completeness/run.py run \
  --owned-root "$trace_owned_root" --pid "$trace_host_pid" \
  --version "$trace_candidate_version"
python3 experiments/host-observation/trace-completeness/verify.py \
  --owned-root "$trace_owned_root"
```

The runner executes the active manifest requests, reads retained pages, validates actual
public documents against the generated contract, and records bounded native
diagnostics. It allows at most 128 calls, 32 MiB of captured process receipts,
4 MiB stdout and 2 MiB stderr per call, 90 seconds per child, 32 page calls per
query, and 2 MiB of appended native logs per query. RUN grants remain 15,000 ms,
100,000 work units, 100 results and 524,288 bytes. Rejected recovery evidence must
retain `POLICY_REJECTED_EVIDENCE` interpretation.

Export the bounded reports, assertions, native pin, artifact/source inventory and
selected receipts before cleanup. Stop only recorded owned processes, confirm
they have exited, and remove only the owned execution root. Preserve repository
fixtures and review archives. Routine unit tests do not require this native suite.
