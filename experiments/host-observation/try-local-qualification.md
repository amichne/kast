# Try/catch and local identity qualification

The fixture templates and source intent are independently authored inputs. The
Python checker validates captured observations. Neither establishes native K2
or Pi execution by itself. Every pin, build wrapper, driver invocation and receipt
check requires the same explicit `--qualification-slice`.

`TRY_BRANCH_RESULTS` requires public contract 6 and the exact common/try owner
class set. It runs all authored try cases and the original Konditional scenario,
and excludes local discovery, reference and stale-local cases.
`TRY_LOCAL_IDENTITIES` requires public contract 7 and the exact combined owner
class set, including local/source projection and compiler-type proof enums. It
runs the full fixture and Konditional scenarios. Unknown profiles, missing or
extra native owners, incompatible versions and cross-slice receipts fail closed.

`run_try_local_pi.mjs` starts a real authenticated Pi SDK session, loads the
candidate's shipped extension, asks its model to dispatch one registered probe,
and invokes `query_symbols` through `ctx.executeTool`. This uses the registered
tool pipeline and hooks. The deterministic probe sequence is a qualification
workload; it is not evidence of an agent independently discovering the answer.

The driver admits only an owned `fixture` or `konditional-copy` root and a start
native pin for that exact root and candidate. It does not provision, install,
launch, import or close an IDE. Use an already imported isolated native host and
retain successful build/composition receipts. Never use the sole-installation
installer or mixed-version acceptance runner to prepare this isolation.

For the fixture, the probe queries each exact producer, drains retained paths
one row at a time, replays each retained cursor, discovers local declarations,
resolves them exactly, and reads their compiler-bound references. It then edits
one owned initializer from `Voltage.encrypt` to `Voltage.decrypt` without
changing the local name or any offsets. A native script verifies the admitted
process and saved disk/VFS/document/PSI hashes before the stale-reference query.
The old handle must produce a typed stale/content rejection; rediscovery must
produce a fresh readable handle. `finally` restores the original bytes and
refreshes the owned PSI. This stage is a controlled owned-fixture mutation.

The Konditional scenario queries the original `FeatureId.parse(plainId)` call
from the independently copied tracked sources. Its proof belongs to the copied
workspace. It does not imply a query succeeded against the user's original
running workspace.

The supplemental source contains final Unit, nonnullable Nothing and nullable
Nothing producers, plus same-named locals in two enum-entry overrides. Unit
must preserve `UNSUPPORTED_EXPRESSION`, Nothing must preserve
`ABRUPT_COMPLETION`, and nullable Nothing must carry one normal try result to
the return. Both enum entries must retain different compiler owner identities
through discovery, exact source reads and references. The full fixture includes
these cases. A separate `supplemental` scenario runs against the same owned
fixture in its own capture directory, preserving previous receipt paths.

Prepare source intent after staging all three templates in the owned imported
fixture. Substitute the admitted paths below; the model must already exist in
the selected Pi provider's local catalog.

```sh
python3 experiments/host-observation/try_local_oracle.py --source "$OWNED/fixture/logging/src/main/kotlin/representation/fixture/RepresentationImpactFixture.kt" --other-source "$OWNED/fixture/logging/src/main/kotlin/representation/other/LocalIdentityOtherFixture.kt" --supplemental-source "$OWNED/fixture/logging/src/main/kotlin/representation/supplemental/SupplementalTrustFixture.kt" > "$OWNED/evidence/try-local-oracle.json"
python3 experiments/host-observation/konditional_try_oracle.py --root "$OWNED/konditional-copy" > "$OWNED/evidence/konditional-oracle.json"
node experiments/host-observation/run_try_local_pi.mjs --qualification-slice TRY_LOCAL_IDENTITIES --root "$OWNED/fixture" --owned-root "$OWNED" --candidate "$CANDIDATE" --oracle "$OWNED/evidence/try-local-oracle.json" --start-pin "$OWNED/start-pin/pin.json" --pi-package "$PI_PACKAGE" --auth "$PI_AUTH" --model "$PI_MODEL"
python3 experiments/host-observation/verify_try_local_receipts.py --qualification-slice TRY_LOCAL_IDENTITIES --scenario fixture --root "$OWNED/fixture" --source "$OWNED/fixture/logging/src/main/kotlin/representation/fixture/RepresentationImpactFixture.kt" --other-source "$OWNED/fixture/logging/src/main/kotlin/representation/other/LocalIdentityOtherFixture.kt" --supplemental-source "$OWNED/fixture/logging/src/main/kotlin/representation/supplemental/SupplementalTrustFixture.kt" --receipts "$OWNED/pi-qualification-fixture/receipts.json"
```

After the copied Konditional project has its own successful native import and
start pin, repeat the read-only scenario:

```sh
node experiments/host-observation/run_try_local_pi.mjs --qualification-slice TRY_LOCAL_IDENTITIES --root "$OWNED/konditional-copy" --owned-root "$OWNED" --candidate "$CANDIDATE" --oracle "$OWNED/evidence/konditional-oracle.json" --start-pin "$OWNED/konditional-start-pin/pin.json" --pi-package "$PI_PACKAGE" --auth "$PI_AUTH" --model "$PI_MODEL"
python3 experiments/host-observation/verify_try_local_receipts.py --qualification-slice TRY_LOCAL_IDENTITIES --scenario konditional --root "$OWNED/konditional-copy" --receipts "$OWNED/pi-qualification-konditional/receipts.json"
```

Output directories must be fresh. The bounded capture writes raw receipts,
transport hashes and, for the fixture, source-refresh/mutation receipts. Failures
publish only a finite stage and outcome; inspect the private receipts at that
boundary. A provider authentication failure, empty selection, missing imported
model or absent native receipt is a prerequisite failure, never behavioral
evidence. Existing auth is read through `ReadOnlyAuthStorage`; no credentials are
copied or changed.

Require an end native pin after the fixture has been restored. Compare exact
host/process start, candidate artifact hashes, fixture hashes and imported
source model against the start pin. Source refresh deliberately advances read
authority, so a changed query epoch alone is expected. Preserve all remaining
qualifications; reaching a return does not prove runtime path feasibility or
eliminate an independent unsupported-return obligation.

The checker requires exact producer, branch, return, local declaration and
lexical-owner ranges; separate fallback provenance; excluded shadowed references;
discovery/exact/relation address parity; identical replayed rows, evidence and
cursors; and explicit stale-reference rejection followed by a fresh read.

Qualify the independently built try-only candidate first, using its own exact
source/build receipts and native start/end pins. The pin command also requires
`--qualification-slice TRY_BRANCH_RESULTS`; it must observe catalog version 6.
The registered tool driver uses the candidate's shipped extension, whose own
catalog admission must agree. Run both scenarios:

```sh
node experiments/host-observation/run_try_local_pi.mjs --qualification-slice TRY_BRANCH_RESULTS --root "$OWNED/fixture" --owned-root "$OWNED" --candidate "$TRY_CANDIDATE" --oracle "$OWNED/evidence/try-local-oracle.json" --start-pin "$OWNED/try-start-pin/pin.json" --pi-package "$PI_PACKAGE" --auth "$PI_AUTH" --model "$PI_MODEL"
python3 experiments/host-observation/verify_try_local_receipts.py --qualification-slice TRY_BRANCH_RESULTS --scenario fixture --root "$OWNED/fixture" --source "$OWNED/fixture/logging/src/main/kotlin/representation/fixture/RepresentationImpactFixture.kt" --other-source "$OWNED/fixture/logging/src/main/kotlin/representation/other/LocalIdentityOtherFixture.kt" --receipts "$OWNED/pi-qualification-try-branch-results-fixture/receipts.json"
node experiments/host-observation/run_try_local_pi.mjs --qualification-slice TRY_BRANCH_RESULTS --root "$OWNED/konditional-copy" --owned-root "$OWNED" --candidate "$TRY_CANDIDATE" --oracle "$OWNED/evidence/konditional-oracle.json" --start-pin "$OWNED/try-konditional-start-pin/pin.json" --pi-package "$PI_PACKAGE" --auth "$PI_AUTH" --model "$PI_MODEL"
python3 experiments/host-observation/verify_try_local_receipts.py --qualification-slice TRY_BRANCH_RESULTS --scenario konditional --root "$OWNED/konditional-copy" --receipts "$OWNED/pi-qualification-try-branch-results-konditional/receipts.json"
```

First-slice output directories are separate from combined qualification. Stop
only the admitted owned IDE before replacing its staged plugin with the next
candidate, then obtain fresh process/profile and artifact pins. An in-process
plugin swap cannot establish the second candidate's loaded bytes.
