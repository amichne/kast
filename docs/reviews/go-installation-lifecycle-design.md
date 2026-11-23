# Go installation lifecycle: design specification

## Conclusion

The Go `kast` binary can own the whole installation lifecycle with:

- one pointer, `R/current`;
- immutable version directories, each with three records;
- runtime and base entries that Go alone publishes;
- one lock file with two byte ranges, used through one primitive;
- one recovery rule.

The rule has three parts:

1. Every lifecycle command takes the lock exclusively.
2. It finishes or deletes every Kast-named leftover that `R/current` and the command's own staging tree do not bind.
3. Before it deletes or replaces any version directory or runtime entry, it proves that no process uses it.

New content stays in a private staging tree until the command holds the lock exclusively, so a leftover never blocks a rerun.
The rule replaces the replacement journal, the shutdown fence, the uninstall journal, and the three recovery routes.

The release that installs a build performs the whole transaction itself.
The selecting binary reads only a frozen selection envelope. It checks the layout protocol, verifies the target's Control executable, and runs it in place with a versioned handoff document.
The selecting binary holds no lock across that step, so two versions never share one transaction.

Go never runs a legacy binary. An installation in the old layout is rejected with one code, and the user removes it with its own `kast uninstall` first.

Kotlin keeps every effect on its own state: client configuration, coordinator state, workspace containers, user state in `~/.kast`, and retirement.
It stops reading and writing every lifecycle record.
Every JVM entry takes its own lock on `S/kast.lock` and checks that `R/current` still names its version. Correctness does not depend on a lock surviving `execve`.

The switch is gated twice. First, a stable promotion of a build in the new layout must exist. Second, a bootstrap binary must be published as a pre-release.
Only then does `install.sh` change.

The user approved decisions D1 to D9 as recommended (recorded on #1369). Section 13 records them, and lists the mechanics that later audits changed.
Section 14 answers both audits and the review of the second revision.

## Revision and scope

- Base revision: `fec3978de2` (`origin/main`, after #1377). Citations are against that revision.
  The derivation table in section 1.2 is recorded at `a4b830ff2e`, before #1377 moved those sites, and says so.
- Inputs: issue #1369; sections 1 to 6 of `docs/reviews/installation-lifecycle-inventory.md` on branch `docs/lifecycle-inventory`; #1377; the audit of revision `0e9b820cf8`; and the re-audit of revision `36e1643ede`.
- Method: source reading, reference search, and one kernel history lookup (section 4.1). No build, test, or native lifecycle run supports a claim here.
- Notation: `R` is `~/.local/share/kast`. `V` is one version directory, `R/versions/<id>`. `S` is `~/.local/share`.
- Citations are repository-relative. Where the text states a judgment or an expectation, it says so.

## 1. Answers to the open questions

### 1.1 Coordinator and Host supervision, and retirement

**Linux today.**

- The coordinator is a transient user unit.
  `LinuxPersistentBrokerServiceHost.submitExclusively` first runs `systemctl --user is-active --quiet <label>.service`.
  When that call reports the unit absent, it starts the unit with `systemd-run --user --quiet --collect --unit=<label>.service --property=Type=exec` (`app-server/.../LinuxPersistentBrokerServiceHost.kt:19-47`, `:61-74`).
- The unit sets `WorkingDirectory` to the installation directory (`:69`).
- No unit file is written, no unit is enabled, and no `Restart=` property is set. Nothing restarts the coordinator at login or after an exit.
  The next semantic request starts it again through `InstalledSemanticCoordinator.connect` (`app-server/.../InstalledSemanticCoordinator.kt:56-80`).
- The label is `io.github.amichne.kast.semantic.<first 32 hex of sha256(installation path)>` (`SemanticServiceLaunch.kt:44`, `:61`, `:119-123`).
- The unit needs a private `/run/user/<uid>` owned by the user (`LinuxPersistentBrokerServiceHost.kt:49-59`). Without one, submission rejects and no coordinator starts.
- The Codex broker service is unavailable on Linux. `ensure` returns `UNAVAILABLE` (`LinuxPersistentBrokerServiceHost.kt:13-14`).
- Workspace Hosts are not supervised. The coordinator starts each one as a child with `ProcessBuilder` (`app-server/.../ide/IdeLaunchPlatform.kt:17-33`).
  The unit keeps the default kill mode. So I expect the `systemd` manager to stop Hosts in the unit's control group when the coordinator exits.
  No native run supports that expectation.

**macOS today.**

- The coordinator is a launchd job in `gui/<uid>`. Its property list lives at `<installation>/state/broker/<profile>/semantic-service.plist`.
  `<profile>` is a 16-hex hash of the Codex home (`distribution/contract/.../InstallationLayout.kt:303-316`), not a label.
  `submitSemanticExclusively` bootstraps the job when `launchctl list <label>` reports it absent, or `kickstart`s an owned job (`MacOsPersistentBrokerServiceHost.kt:321-351`, `:933-947`).
- The semantic job has `RunAtLoad`, `WorkingDirectory` set to the installation, and `AbandonProcessGroup`, with no `KeepAlive` (`BrokerLaunchdServiceDocument.kt:36-55`, `:47`). So Hosts outlive the coordinator.
- The Codex broker job is a separate launchd job. Its label is `io.github.amichne.kast.broker.<sha256(codexHome)[:32]>`, so there is one per Codex home (`PersistentBrokerService.kt:191-202`).
  It has `KeepAlive` on unsuccessful exit with a 10-second throttle (`BrokerLaunchdServiceDocument.kt:40-43`).
  Its program is the payload's `kast-daemon` (`PersistentBrokerService.kt:267-268`), and its state directory is the broker profile directory (`:396`).
- The login agent at `~/Library/LaunchAgents/<broker label>.login.plist` is the same broker job document with `--login` (`ServiceLoginAgent.kt:21-59`).
  Ownership is an exact document match (`ServiceLoginAgent.kt:24-35`). Deactivation rejects on any mismatch (`BrokerServiceDeactivation.kt:28-32`).
  `AppServerManagement` publishes the agent on `Bootstrap` and `Enable` (`AppServerManagement.kt:271-285`). The Codex client launcher calls `Enable` at each launch (`host/InstalledCodexClientLauncher.kt:382`).
- Issue #1310 tracks #1319, which removes the legacy login bootstrap of the app server. Decision D1 covers both outcomes.

**Retirement today.**
`--internal-install managed-retire` runs `retireManagedProduct` from the outgoing Control (`distribution/cli/.../InternalManagementInstall.kt:29-30`, `:60-72`).

1. It leases every workspace container and writes the shutdown fence inside the lease (`ManagedWorkspaceRetirement.kt:42-80`; the fence writer is `InstallationLifecycle.kt:135-148`). One rejected container record rejects the whole scope (`ManagedWorkspaceRetirement.kt:84-90`).
2. It stops each Host with `share/kast/libexec/kast-service disable` (`ContainerManagement.kt:46-79`).
3. It retires the coordinator with `kast-service retire-semantic` (`InstallationLifecycle.kt:45-60`, `:172-193`).
   That command sends `DaemonManagementRequest.Retire` over the private socket and waits for the exact peer to exit (`SemanticCoordinatorRetirement.kt:25-88`).
   On macOS, `withdrawSemanticLaunchdJob` also boots out the owned job and waits for its absence (`SemanticLaunchdJob.kt:24-47`).
   On Linux, no `systemctl` call stops the unit.

It does not stop the broker job or remove its login agent.
Before it runs the old code, the Python installer verifies the outgoing payload again (`packaging/internal/product/managed_product_installation.py:1024-1029`).

**Retirement under Go.**

1. Go holds the lock exclusively (section 4).
2. Go verifies the retirement executables against their records: `V_old/share/kast/libexec/kast-management`, `kast-service`, every `lib/` entry, and the runtime entry's `java` and manifest. This is check T3 and V2.
3. If they verify, Go runs `V_old/share/kast/libexec/kast-management --internal-install managed-retire` as a child.
   - The child's stdin is a pipe from Go. The child stops before its next destructive step when that pipe closes, so a killed Go parent cannot leave it working.
   - The child repeats its transition observation (section 4.3) before each destructive step.
   - It has a bounded deadline.
   - It writes one closed retirement document to stdout, with its own `schemaVersion`. The document holds the semantic label it proved absent and each broker label it deactivated (decision D1).
     It also holds each Host process incarnation it proved exited: process identifier plus start time.
   - Go decodes every document version up to its own. A newer version rejects with `RETIREMENT_REJECTED` and reason `DOCUMENT_UNSUPPORTED`. That case is a downgrade, covered by the downgrade policy in section 1.5.
4. If they do not verify, `upgrade --repair` and `uninstall --force` use Go's forced retirement (section 3.7). Every other command rejects with `RETIREMENT_EXECUTABLE_DAMAGED`.
5. Go confirms each fact in the document:
   - **Linux.** Go polls `systemctl --user show --property=LoadState,ActiveState <label>.service` every 100 milliseconds, within the retirement deadline, until it reports `LoadState=not-found`.
     `--collect` unloads a transient unit once it stops, so `not-found` also proves that its control group is gone. `is-active` is not enough, because it treats `deactivating` as inactive.
   - **macOS.** `launchctl list <label>` must report the job absent for every label in the document.
   - **Both.** Each Host incarnation must be absent: `kill(pid, 0)` reports no process, or the start time differs.
6. If the user has no supervisor (no user bus on Linux), the supervisor adapter reports `SUPERVISOR_UNAVAILABLE`. That is a fact, not a failure: no coordinator can run without one. Go then checks only the Host incarnations and the process scan.

Go uses the labels from the document, so the label hash has one owner (`SemanticServiceLaunch.kt:119-121`).
The readiness document gains the version identifier of the coordinator that published it (decision D2).

**The process-use invariant.** Before Go deletes or replaces a `V` or a runtime entry, it proves that no process of the user uses it.
A process uses a tree when its executable, any element of its argument vector, or its working directory lies inside that tree.
The argument vector matters because a JVM runs `java` from the runtime entry but names `V/lib` in `-cp` (`frontend/internal/frontend/control.go:128-146`).
Linux reads `/proc/<pid>/exe`, `/proc/<pid>/cmdline` and `/proc/<pid>/cwd`. macOS reads `kern.procargs2` and the process working directory through `golang.org/x/sys/unix`, which keeps the build free of `cgo`.
A use rejects with `PROCESS_STILL_ALIVE` and names the process identifier. Only forced retirement acts on it.

### 1.2 Kotlin path derivations from the installation directory

Every derivation starts from one of three roots:

- The JVM code location: `<installation>/lib/<jar>`, whose parent's parent is the installation (`cli/.../InstalledControlLocation.kt:10-48`).
- The executable, through `InstallationLayout.ofKastExecutable` (`InstallationLayout.kt:264`; callers such as `AppServerManagement.kt:134` and `KastServiceMain.kt:151`).
- The management root, `HOME/.local/share/kast`, fixed by `ManagementRootResolution` (`distribution/cli/.../ManagementInstallation.kt`), then `InstallationLayout.beneath` (`InstallationLayout.kt:260`).

The table below lists the sites at `a4b830ff2e`, before #1377 routed them through `InstallationLayout`.
After #1377, each family resolves through a member of `InstallationLayout`: `Configuration` (`:47-58`), `State` (`:66-83`), `Run` (`:87`), `Payload` (`:102-141`), and `Management` (`:145-162`).

| Family | Sites at `a4b830ff2e` | Moves to |
| --- | --- | --- |
| Client configuration file | `PublishedBrokerServiceCommand.kt:113`, `:135`; `AppServerStatus.kt:68`; `KastServiceMain.kt:147`; `ConfigurationInspection.kt:232`; `InstalledClientContext.kt:150`; `LifecycleProcesses.kt:346`; `PassiveRuntimeObservation.kt:253`; `PassiveRuntimeSocket.kt:33`; `WorkspaceSharingAdmission.kt:28`; `InstalledWorkspaceConfigurationIngress.kt:43` | `R/config/environment` |
| Workspace overlays | `InstalledWorkspaceConfigurationIngress.kt:78` | `R/config/workspaces/<64 hex>/environment` |
| Workspace registry | `AppServerManagement.kt:143`, `:192`; `AppServerStatus.kt:69`; `InstalledBrokerServer.kt:555`; `InstalledCoordinator.kt:195`; `runtime/CoordinatorControl.kt:83` | `R/config/workspaces.json` |
| Selected IDE | `ManagedHeadlessShutdown.kt:40`; `PassiveApplicationHost.kt:111` | `R/config/selected-ide.json` |
| Coordinator state root | `BrokerInstallationLayout.kt:14-17`; `MacOsPersistentBrokerServiceHost.kt:483`; `InstallationLifecycle.kt:49` | `R/state` |
| Installation epoch | `BrokerInstallationState.kt:38`, `:44`, `:103`, `:120`; `PassiveRuntimeObservation.kt:153`, `:252` | `R/state/epoch.json` |
| Sockets | `BrokerServiceStartLock.kt:86`, `:250`; `SemanticCoordinatorPublication.kt:196`, `:263`; `PassiveRuntimeSocket.kt:70`; `PublicFrontendCatalog.kt:39`, which Go resolves beneath the installation (`frontend/internal/frontend/catalog.go:163`) | `R/state/run/c.sock`; the catalog's `socket` becomes relative to `R` |
| Readiness record | `InstalledSemanticCoordinator.kt:23-26`; `PassiveRuntimeObservation.kt:188` | `R/state/broker/<16 hex>/service-readiness.json` |
| Invocation directories | `OneShotInvocationRecord.kt:85-88`; `OneShotObservation.kt:56`; `PriorInstallationPreparation.kt:14` | Removed under decision D4 |
| Legacy worker state | `runtime/CoordinatorControl.kt:221` | Deleted |
| Semantic identity and label | `SemanticServiceLaunch.kt:43`, `:48`, `:60`, `:116-122`; `SemanticLaunchdJob.kt:14`, `:21`; `LinuxPersistentBrokerServiceHost.kt:52`, `:69` | Derived from `R` (decision D2) |
| Broker daemon program | `PersistentBrokerService.kt:262-263` | Stays inside `V`; covered by retirement (decision D1) |
| Management receipt | `ManagementReceiptAdmission.kt:40`; `ProductManagementReceipt.kt:49-67`; Go `control.go:99-122` | Deleted |
| Product selection and runtime root | `InstalledProductRuntime.kt:124-185`, `:173`, `:205-230`; `InstallationLifecycle.kt:201`; `ManagedWorkspaceQuery.kt:87` | Decision D3 |
| Payload manifest | `BundledManifest.kt:46`, `:59`; `InstallationLifecycle.kt:170-175`, `:200-209`, `:234-247`; `ContainerManagement.kt:55-58` | Kotlin stops reading it at stage 8 |
| Store siblings | `ManagedWorkspaceLifecycle.kt:102`; `ManagedWorkspaceRemoval.kt:20`; `ManagedWorkspaceRetirement.kt:12`; `ManagedWorkspaceStatus.kt:43`; `WorkspaceSharingInspection.kt:48`; `InstalledProductRuntime.kt:173` | Unchanged: `S/kast-workspaces`, `S/kast-runtime-bases` |
| Lifecycle records in Kotlin | `ManagementFileLock.kt:10-15`; `UninstallManagedRoot.kt:29`, `:139-147`; `ForceResetFence.kt:107`, `:237`; `RecordedRecoveryInstaller.kt:83-126`; `ManagementUninstall.kt:56-230` | Deleted (stage 10) |

Sites that still escape one owner at `fec3978de2`:

- **Upward derivations**: `InstalledBrokerServer.kt:93`; `MacOsPersistentBrokerServiceHost.kt:363`, `:399`; `InstalledCoordinatorClient.kt:99`; `InstalledClientContext.kt:119-122`; `ManagementStatus.kt:123`.
- **Inverse derivations**: `InstallationLayout.ofConfigurationFile` (`InstallationLayout.kt:267`), with callers at `KastServiceMain.kt:266` and `ConfigurationInspection.kt:116`, `:228`.
- **One root**: `InstallationLayout` holds one `root`, and `beneath` uses `managementRoot = root.parent` (`InstallationLayout.kt:15-44`, `:260`).

Workspace container paths resolve beneath the container profile, so they do not move.
The IDE plugin keeps its durable state under `~/.kast` (`runtime/hosted/.../HostedChangeResources.kt:32`), which is independent of `R`.

**Target type.** Stage 4 reshapes the owner to hold the share directory, `R`, and `V`, admitted by one function at an effect boundary:

```kotlin
/** The only Kotlin derivation of installation paths. */
class InstallationLayout private constructor(val share: Path, val root: Path, val version: Path) {
    val lifecycleLock: Path get() = share.resolve("kast.lock")
    val config: Path get() = root.resolve("config")
    val state: Path get() = root.resolve("state")
    companion object {
        /**
         * Effect boundary: reads R/current once and requires that the code source lies in the version it names.
         * A process from a version that current no longer names is rejected with STALE_VERSION.
         */
        fun admit(codeSource: Path, home: Path): Refinement<InstallationLayout, InstallationLayoutFailure>
    }
}
```

In the old layout, the same function returns `root = R` and `version = R/installation`, with no `current` check.
Stage 8 changes only its body.
The architecture rule from #1377 extends to `Path.parent` chains on admitted installation paths. `kast.lock` joins its record names (`build-logic/.../policy/InstallationPathDerivationRule.kt:26-36`).
An acceptance check sets a distinctive value in `R/config/environment`. Every entry must then observe it: query, MCP, service, daemon, broker and Host.

Every shipped launcher runs the Go binary. The launchers in `cli/src/main/control-launchers/` end in `exec .../kast-management` with an `--internal-entrypoint` (for example `share/kast/libexec/kast-service`), and assembly copies them (`cli/build.gradle.kts:303-308`).
So every JVM entry passes through the frontend, and the lock table of the frontend (section 4.2) covers all of them.

### 1.3 Release delivery

**Today.**

- `install.sh` is generated (`packaging/internal/bootstrap/bootstrap.sh.in`). Users fetch it from `main` (`docs/public/reference/installation.mdx:29`). It is 72,423 bytes.
- It pins a Python archive per target (`install.sh:35-41`), the `defusedxml` wheel (`:62-63`), and an embedded engine (`:66-73`). Then it runs the engine (`:76-78`).
- The engine resolves the selection over HTTPS (`managed_release.py:39-41`, `:1076-1215`). It checks the build record against the catalog or mapping digest (`:1795-1867`). Then it checks each asset against the record (`managed_product.py:353-377`).
- The Control executable already has a qualified digest, `qualification.controlSha256` (`managed_release.py:530-538`). Staging enforces it (`managed_product.py:459-464`).
- The engine rejects unknown target fields (`managed_release.py:608-617`) and unknown record schemas (`:738-739`). One invalid record rejects the whole catalog (`:1124-1150`).
- Publication uploads the record's assets, the acceptance assets, and `build-record.json` (`.github/scripts/release/publish_developer.py:295-312`). No asset is a standalone Go binary.
- Stable selection reads GitHub's `releases/latest` (`managed_release.py:40`, `:1094-1112`), which returns the newest release that is not a draft or a pre-release. Today that is `v0.52.0`, a build in the old layout.

**New assets, without a record change.**

- Each release target gains one top-level asset with a fixed name, `kast-control-<target>`. Its bytes are the Control executable of that target's payload, and its digest is the existing `qualification.controlSha256`.
- Each release gains a selection envelope (below). Previews list theirs in a new `lifecycle-catalog.json` on the `developer-latest` branch, beside the unchanged `builds.json`. Stable releases carry `lifecycle-envelope.json` as an asset.
- The record schema, `builds.json` and `release-mapping.json` stay unchanged, so installed Python engines keep decoding them.
- The product gate and `publish_developer.py` reject a release whose envelope or asset digest differs from `controlSha256`.

**Selection envelope.** The envelope is the only release metadata that a selecting binary decodes. Its schema is frozen at major version 1.
The decoder reads the named fields and ignores unknown ones, so a newer envelope never breaks an older Go binary.

```go
// lifecycle-envelope.json, and each entry of lifecycle-catalog.json. Frozen; unknown fields are ignored.
type SelectionEnvelope struct {
	SchemaVersion  int                        `json:"schemaVersion"`  // 1
	ReleaseTag     string                     `json:"releaseTag"`
	SourceRevision Revision                   `json:"sourceRevision"`
	RecordSHA256   Digest                     `json:"recordSha256"`
	Layout         int                        `json:"layout"`         // 2 for this design
	Handoff        []int                      `json:"handoff"`        // handoff schema versions the target accepts
	Controls       map[Target]EnvelopeControl `json:"controls"`
}

type EnvelopeControl struct {
	SHA256 Digest `json:"sha256"` // equals the record's qualification.controlSha256 for this target
	Bytes  int64  `json:"bytes"`
}
```

A release with no envelope, such as `v0.52.0`, has no Go-installable layout.

**Positive layout marker.** A Control payload in the new layout carries `share/kast/layout-v2` with the content `2\n`, and drops `share/kast/managed-install-v1`.
The selecting binary decides `RELEASE_LAYOUT_UNSUPPORTED` from the envelope, before it runs anything: a missing envelope, or `layout` other than 2.
The target checks the marker in its own payload again at A7.
An installed Python engine rejects the new payload at the dropped marker (section 1.4).

**Handoff.** The selecting binary is the bootstrap, or an installed `kast upgrade`.

1. It runs the read-only pre-check P0 (section 3.1) and takes no lock.
2. It resolves the selection from the envelope catalog or the release's envelope, and decides `RELEASE_LAYOUT_UNSUPPORTED`.
3. It chooses a handoff schema from `Handoff`. With none in common, it rejects with `HANDOFF_UNSUPPORTED`.
4. It downloads `build-record.json` and checks its digest against `RecordSHA256`. It downloads `kast-control-<target>` and checks it against `Controls[target]`.
5. It writes a private directory from `os.MkdirTemp` in `$TMPDIR` with mode 0700. The directory holds the record bytes, the binary, and the handoff document.
6. It calls `execve` on the verified binary with `--internal-lifecycle-handoff <directory>`. This replaces the process. No child is forked.
   If the selecting binary already has the target digest, it skips the `execve` and continues in place.

```go
// handoff.json, written by the selecting binary and read once by the target.
type Handoff struct {
	SchemaVersion int        `json:"schemaVersion"` // one of the envelope's Handoff values
	Operation     Operation  `json:"operation"`     // closed: INSTALL, UPGRADE, REPAIR
	Selection     Selection  `json:"selection"`     // release tag, policy, public version, source revision
	RecordSHA256  Digest     `json:"recordSha256"`
	ControlSHA256 Digest     `json:"controlSha256"`
	Output        OutputMode `json:"output"`        // closed: TEXT, JSON
}
```

The target decodes the record strictly with its own decoder.
It requires the record's `controlSha256` for this target to equal both `ControlSHA256` and the digest of its own executable.
Then it runs P1 to A15 itself and deletes the handoff directory at the end.
Every record is therefore written and read by the release that defines it.

**Bootstrap.**

- A `bootstrap-<12 hex>` release holds `kast-bootstrap-<target>` for both targets. The suffix is the first 12 hexadecimal characters of the source tree digest.
  It is published as a pre-release, so `releases/latest` never selects it. A CI assertion checks that `releases/latest` names a `v<semver>` tag after every publication.
- The generator computes the pinned digests from a reproducible `go build -trimpath -buildvcs=false` with the `go.mod` toolchain. CI rebuilds them and compares.
- The bootstrap holds only the handoff steps above. Everything else runs in the target.
- The digest chain: `install.sh` pins, then the bootstrap. Next comes the envelope over HTTPS, the same trust root as today's catalog digest. Last come the record digest, the Control digest, and the record's asset digests.
- A failed bootstrap download or digest check is a shell error from `install.sh` (`fail`, `install.sh:6`, `:55-59`), not a Go failure code.
- A pull request that changes the pin merges only after its bootstrap release exists.

### 1.4 Existing installations

Go classifies the home paths into one closed observation:

```go
type LayoutObservation uint8

const (
	LayoutAbsent     LayoutObservation = iota + 1 // no R, no witness, no legacy marker
	LayoutIncomplete                              // R holds only Kast-named entries and no current link
	LayoutCurrent                                 // current names a Kast-named V whose three records decode and agree
	LayoutDamaged                                 // current exists but its target, name or records fail
	LayoutResidual                                // an uninstall witness S/.kast-uninstalled-<id> exists
	LayoutLegacy                                  // any legacy marker exists
	LayoutForeign                                 // R holds an entry with no Kast name, or a witness coexists with a valid current
)
```

The legacy marker set has nine exact patterns:

- in `R`: `installation`, `management.json`, `recovery`, `shutdown.json`, `management.lock`, `activation.lock`;
- in `S`: `.kast-uninstall-<64 hex>.json`, `.kast-reset-<32 hex>.json`, and `.kast-install-<uuid>`.

Precedence is fixed: `Legacy`, then `Foreign`, then `Residual`, then `Damaged`, then `Current`, then `Incomplete`, then `Absent`.
So a stray writer that recreates `R/state` after an uninstall does not hide the witness.
Section 2.1 lists every name at the `S` level and how classification treats it.

On `LayoutLegacy`, every Go lifecycle command rejects with `LEGACY_LAYOUT`.
The message tells the user to run the installed `kast uninstall`, then rerun `install.sh`. Go never executes a legacy binary.
The legacy forced reset rejects every managed installation with `MANAGED_STATE_REQUIRES_UNINSTALL` (`PublicInstallationReset.kt:66-86`). So the legacy product's ordinary `kast uninstall` is the only removal route that its records support.
The legacy `install.sh` has no removal option (`install.sh:26-27`).

On `LayoutForeign`, every command rejects with `ROOT_FOREIGN` and names the first foreign entry relative to `R`.
Kast never deletes an entry it did not name. `R` is Kast's directory, but a user file inside it is still the user's (requirement 6).

On `LayoutDamaged`, `install` and `upgrade` require `--repair`, and `uninstall` requires `--force`. Both use forced retirement, because the outgoing code cannot be trusted to run.
Without the option, the command rejects with `LAYOUT_DAMAGED` and names the failed check.

The installed Python engine rejects a new-layout payload. With the record unchanged, it reaches the marker check. So `kast upgrade` in a legacy installation reports `INSTALLER_CONTRACT_UNSUPPORTED` at stage `CONTRACT` (`managed_release.py:1956-1958`, `:1538-1540`).

### 1.5 Commands: which move into Go

| Command | Owner after the switch | Lock and cleanup |
| --- | --- | --- |
| `install.sh` engine | Go `kast-bootstrap`, then the target | Section 1.3, then section 3.2 |
| `kast upgrade [--channel/--version/--commit] [--repair] [--json]` | Go | Section 3.2 |
| `kast upgrade --dry-run` | Go | P0 only: no lock, no cleanup. Downloads go to a private `$TMPDIR` directory |
| `kast uninstall [--force]` | Go | Section 3.3. `--force` still takes the lock at P3 |
| `kast versions [--json]` | Go | No lock, no cleanup; it reads only the envelope catalog |
| `kast status [--json]` | Kotlin, with Go facts | Shared lock (section 4.2); Go passes the version identifier, record digest and policy as JVM system properties |
| `kast stop`, `kast config`, `kast install skill` | Kotlin | Shared lock; `kast config` edits also take `R/config/edit.lock` (stage 8) |
| `--internal-install managed-retire [--removal]` | Kotlin, run by Go | No lock; it observes its parent's exclusive lock |
| `--internal-install reset`, `preflight-reset` | Deleted | Only verification harnesses call them |
| Top-level help | Kotlin catalog | `ManagementRoot` keeps help nodes for the Go commands, and the frontend answers their exact help vectors from `frontend.json`. A test asserts that Kotlin can never select a Go command |
| IDE plugin | Unchanged | It reads `~/.kast` and the environment that Control passes. Lifecycle never stops a user's desktop IDE |

The frontend guide forbids lowering flags in Go (`frontend/AGENTS.md`, Rules). Stage 1 amends that rule for the lifecycle commands above only.

**Downgrade policy.** A downgrade selects an older release, and that release's binary performs the installation.
If that binary cannot decode the current `V`'s records or its retirement document (their `schemaVersion` is newer), it rejects with `RECORD_SCHEMA_UNSUPPORTED`. The user then runs `kast uninstall` and installs the older release fresh.

## 2. Layout and records

### 2.1 Paths

```text
S/kast.lock                               lifecycle lock; byte 0 is the intent range, byte 1 the main range; never deleted
S/kast/                                   R, mode 0700
  current -> versions/<16 hex>            the single pointer (relative symbolic link)
  versions/<16 hex>/                      V, random identifier; top directory 0700, contents read-only
    bin/, lib/, share/kast/...            Control payload, including share/kast/layout-v2
    share/kast/installation.json          payload manifest
    share/kast/product-selection.json     selection and runtime binding
    share/kast/build-record.json          release bytes, unchanged
  config/                                 Kotlin-owned client configuration, including edit.lock
  state/                                  Kotlin-owned coordinator state
  .staging-<16 hex>/                      a command's private staging tree
  current.next                            transient link during a swap
S/kast-workspaces/                        existing store: runtimes/, containers/, locks/
S/kast-runtime-bases/                     existing store
S/.kast-workspaces.lifetime.lock          Kotlin store lifetime lock; never deleted
S/.kast-removing-<16 hex>/                a tree being deleted
S/.kast-uninstalled-<16 hex>/             uninstall witness: the renamed R
~/.local/bin/kast -> S/kast/current/share/kast/libexec/kast-management
~/.local/bin/kast.next-<16 hex>           transient link during public command creation
/tmp/kast-uds-<32 hex>                    Kotlin socket alias links
```

Every name at the `S` level and its treatment:

| Name | Owner | Classification | Cleanup |
| --- | --- | --- | --- |
| `kast` (`R`) | Go and Kotlin | Section 1.4 | Only uninstall renames it |
| `kast.lock` | Go | Ignored | Never deleted |
| `kast-workspaces`, `kast-runtime-bases` | Go (entries), Kotlin (containers) | Ignored, except `WORKSPACE_STORE_OCCUPIED` on a first install | Uninstall and `Residual` only |
| `.kast-workspaces.lifetime.lock` | Kotlin | Ignored | Never deleted; it survives store deletion by design (`ContainerQueryLease.kt:64-77`) |
| `.kast-removing-<16 hex>` | Go | Ignored | P5 deletes it best effort |
| `.kast-uninstalled-<16 hex>` | Go | `Residual` | Deleted last, in the finishing order (section 3.5) |
| `.kast-uninstall-<64 hex>.json`, `.kast-reset-<32 hex>.json`, `.kast-install-<uuid>` | Legacy | `Legacy` | Never touched by Go |
| `.kast-reset-<32 hex>.json.lock` | Legacy | Ignored; the reset leaves it on purpose (`ForceResetFence.kt:47`; `run-native-public-reset.py:83`) | Never touched by Go |
| Any other name | The user | Ignored | Never touched |

Kast-named entries of `R` are exactly `current`, `current.next`, `versions`, `config`, `state`, and `.staging-<16 hex>`.

- **Version directories.** Each `V` has a random identifier, so a stage never collides with a leftover. `product-selection.json` records which build a `V` holds.
- **Public command.** A symbolic link with a fixed target string, so upgrade never rewrites it. `locateInstallation` resolves links and strips the payload path (`control.go:77-95`), which yields the physical `V`.
- **Home paths.** `~/.local`, `~/.local/share` and `~/.local/bin` must be real directories owned by the user, not symbolic links. Otherwise every command rejects with `HOME_PATH_REJECTED`.
  P1 creates a missing `~/.local/share` or `~/.local/bin` with mode 0700 or 0755.
- **Root user.** Go refuses to run as user 0 (`ROOT_USER_REJECTED`).
- **File system.** `R` and both stores must sit on one local file system. A network file system rejects with `FILESYSTEM_UNSUPPORTED`, because record locks are unreliable there. Different devices reject with `CROSS_DEVICE`.
- **Modes.** The top directory of each `V` stays 0700 so that renames across parents can update it. Its contents are read-only. Deletion first adds the owner's write bit to each directory it empties.
- **`/tmp` aliases.** Kotlin removes its own alias when it retires a coordinator. Uninstall removes any remaining alias only if it is a link owned by the user whose target lay inside `R`.
- **Desktop discovery records.** They sit under the broker owner directory (`DesktopDaemonDiscovery.kt:243-247`), so D1's deactivation releases them. Otherwise they go with `R/state`.

### 2.2 Record schemas

Each record has an explicit `schemaVersion` that the decoder reads first.
Decoders reject unknown fields, duplicate keys, trailing data, and unknown enum values. Encoders write every field, including defaults.
The selection envelope (section 1.3) is the one deliberate exception: it is frozen and ignores unknown fields.

```go
type Digest [32]byte          // encodes as 64 lowercase hex characters
type VersionID [8]byte        // encodes as 16 lowercase hex characters
type Target uint8             // closed: "macos-aarch64", "linux-x86_64"

// V/share/kast/installation.json: payload manifest.
type PayloadManifest struct {
	SchemaVersion int           `json:"schemaVersion"` // 1
	Version       VersionID     `json:"version"`       // equals the directory name
	Files         []PayloadFile `json:"files"`         // sorted by Path; every regular file except this one
	Links         []PayloadLink `json:"links"`         // sorted; relative targets that stay inside V
}

type PayloadFile struct {
	Path   RelativePath `json:"path"` // filepath.IsLocal, at most 512 bytes
	SHA256 Digest       `json:"sha256"`
	Bytes  int64        `json:"bytes"`
	Mode   FileMode     `json:"mode"` // closed: "0444" or "0555"
}

type PayloadLink struct {
	Path   RelativePath `json:"path"`
	Target RelativePath `json:"target"`
}

// V/share/kast/product-selection.json: why this V exists and which entries it binds.
type ProductSelection struct {
	SchemaVersion         int       `json:"schemaVersion"` // 2; version 1 is today's record, below
	Version               VersionID `json:"version"`
	Target                Target    `json:"target"`
	RecordSHA256          Digest    `json:"recordSha256"`
	ReleaseTag            string    `json:"releaseTag"`    // "developer-v<version>" or "v<semver>"
	Policy                Policy    `json:"policy"`        // closed: "TRACK_STABLE", "TRACK_MAIN", "PINNED_BUILD", "LOCAL"
	PublicVersion         *SemVer   `json:"publicVersion"` // explicit null unless a stable release
	SourceRevision        Revision  `json:"sourceRevision"`
	RuntimeArchiveSHA256  Digest    `json:"runtimeArchiveSha256"`  // names S/kast-workspaces/runtimes/<sha>
	RuntimeManifestSHA256 Digest    `json:"runtimeManifestSha256"` // digest of that entry's manifest.json
	BaseSHA256            *Digest   `json:"baseSha256"`            // explicit null without a runtime base
}

// V/share/kast/build-record.json: the release's bytes, decoded strictly by the release's own binary.
type BuildRecord struct { /* the existing ProductRecord fields, unchanged (managed_release.py:542-581) */ }

// S/kast-workspaces/runtimes/<archive sha>/runtime.json: runtime receipt (managed_product_installation.py:183-191).
type RuntimeReceipt struct {
	SchemaVersion  int    `json:"schemaVersion"` // 1
	ArchiveSHA256  Digest `json:"archiveSha256"` // equals the directory name
	ManifestSHA256 Digest `json:"manifestSha256"`
}

// <runtime entry>/host[/Kast.app/Contents]/kast-headless.json: assembly record (managed_product_installation.py:92-107).
type AssemblyRecord struct {
	SchemaVersion      int    `json:"schemaVersion"` // 1
	Home               string `json:"home"`          // the entry's absolute Host home; checked equal to its own location
	DistributionSHA256 Digest `json:"distributionSha256"`
	BootstrapSHA256    Digest `json:"bootstrapSha256"`
	PlatformBuild      string `json:"platformBuild"`
	ProductBuild       string `json:"productBuild"` // PlatformBuild + "-IJ"
}
```

**Selection schema version.** Today's `product-selection.json` is version 1, the 14 fields of `ProductReference` (`packaging/internal/product/managed_product.py:44-58`, written at `managed_product_installation.py:604`). #1378 decodes that shape in Go.
`ProductSelection` above is a different shape under the same file name:

- It drops `runtimeRoot`, `sourceTree`, `buildRecordSha256`, `bundleSha256` and `qualificationSha256`.
- It adds `recordSha256` and `baseSha256`.
- It changes `version` from the product version to the 16-hex identifier of `V`.

A decoder that sees only `schemaVersion: 1` cannot tell the two shapes apart, so the new shape is version 2.
Stage 1 defines the type in `record` at version 2 from its first commit, so no binary ever writes the new shape as version 1.
The shape reaches an installed system at stage 8, the first stage whose A7 writes the record into a `V`. From that stage, the frontend decodes version 2 only, from its own `V`.
Version 1 appears only in `R/installation`, which `LayoutLegacy` already rejects. A version-2 decoder therefore rejects version 1 with `RECORD_SCHEMA_UNSUPPORTED`, and an older decoder rejects version 2 the same way (section 1.5).

The entry's `manifest.json` comes unchanged from the runtime archive. Its digest is `RuntimeManifestSHA256`.
The `R/current` link has a closed shape: its target is exactly `versions/<16 hex>`.
The handoff document, the retirement document, the failure document, and the outcome document are the other contracts.

**Bound entries.** An entry is bound when a `ProductSelection` names it: by `RuntimeArchiveSHA256`, or by `BaseSHA256`.
During a command, the bound set is the union of the entries that `current` binds and those that the command's own staging tree binds.
P5 and A15 delete only entries outside that set (decision D9).

### 2.3 Writers and readers

| Record | Single writer | Readers |
| --- | --- | --- |
| `S/kast.lock` | Created by whichever participant opens it first, with `O_CREAT`; never deleted | Every participant locks it (section 4) |
| Selection envelope and catalog | Release publication | Every selecting Go binary |
| Handoff document | The selecting binary | The target, once |
| `R/current`, `R/current.next` | Go, exclusive mode only | Go; the frontend; Kotlin `InstallationLayout.admit` |
| `V/share/kast/installation.json`, `product-selection.json`, `build-record.json` | Go, inside the staging tree | Go of the same release |
| `V/share/kast/layout-v2` | Release assembly | Go |
| Runtime entry: tree, `manifest.json`, `runtime.json`, `kast-headless.json` | Go, through a reservation and a rename without replacement | Kotlin `ContainerRuntime.kt:100-123` and `ManagedHeadlessInstallation.kt:147`; Go |
| Base entry | Go | Go; Kotlin through the runtime |
| Retirement document | Kotlin `managed-retire` | Go |
| `R/config/**`, `R/state/**`, containers, `~/.kast/**` | Kotlin, unchanged | Kotlin. Go deletes the first three only with uninstall, after Kotlin's removal checks |
| `~/.local/bin/kast` | Go | The shell; the frontend |

`importContainerRuntime` is a second Kotlin writer of runtime entries (`ContainerRuntime.kt:139-224`), but only tests call it. Stage 10 moves it into test fixtures.

**Deleted records.**

- `management.json`, `shutdown.json`, `management.lock` and `activation.lock`.
- The replacement journal and its preimages, and the uninstall journal.
- The retained execution bundle, the recovery bundle, and the captured selection file.
- The reset fence, the capability markers other than `layout-v2`, the invocation records (decision D4), and the private interpreter.

## 3. Operations as state machines

Each row below is one pure decision followed by its primitive effects, in order (section 6).
"Crash after" names the state after the row's last completed effect. Crash points inside a row leave either the state before it or a Kast-named leftover that P5 removes.
**Old** and **new** are the selected versions. **Rerun** names the one command that finishes the state.

Lock notation: `I` is the intent byte, `M` the main byte (section 4.1). `X` is exclusive, `S` shared, `-` not held.

### 3.1 Common prelude

| Step | Primitive effects | Lock (I, M) | Crash after |
| --- | --- | --- | --- |
| P0 | `lstat` the home paths, `R`'s entries and the `S`-level names; classify. Reject `Legacy`, `Foreign` and the user and file system checks with no effect | -, - | nothing changed |
| P1 | `mkdir` a missing `~/.local/share` or `~/.local/bin` | -, - | home directories exist |
| P2 | `open(S/kast.lock, O_CREAT\|O_NOFOLLOW\|O_CLOEXEC, 0600)`; `fstat`: regular file, owned by the user, one link, mode 0600 (`LOCK_FILE_REJECTED`) | -, - | lock file exists |
| P3 | `F_SETLK` I to `X`; then `F_SETLK` M to `X`, retried every 100 milliseconds for at most 30 seconds while I keeps new readers out. On timeout, release I and reject with `LOCK_HELD` and the holder from `F_GETLK` | X, X | no change |
| P4 | Classify again under the lock. A race into `Legacy` or `Foreign` rejects, and the only effects were P1 and P2 | X, X | no change |
| P5 | Finish `Residual` in the section 3.5 order. Delete `R/.staging-*` except the own tree, plus `R/current.next`, `~/.local/bin/kast.next-*`, `S/.kast-removing-*`, and the store reservations `.kast-runtime-*` and `.kast-base-*`. Under the process-use invariant, also delete each `V` that `current` does not name and each runtime or base entry outside the bound set | X, X | the same layout with fewer leftovers |

P5 is the recovery rule.

- A leftover is deleted only if it matches a Kast name, the user owns it, and it is not a symbolic link. The two transient links are the exception: each must name its exact owned target.
- Each tree is first renamed to `S/.kast-removing-<random>`, then deleted. An interrupted deletion leaves one recognizable, inert name.
- A `.kast-removing-*` tree that cannot be fully deleted (for example, a mount point inside it) is kept. The outcome reports it as `RESIDUE_RETAINED` with its name. It never blocks a command, because classification ignores it.
- A command deletes its own staging tree and reservations before it returns any rejection.

### 3.2 Install, upgrade and repair: one machine

`install` and `upgrade` run the same machine. They differ only in selection defaults and in whether `LayoutAbsent` is admitted.
Over `LayoutCurrent`, install therefore retires and verifies like an upgrade. That matches the documented re-run of the remote installer (`docs/public/reference/installation.mdx:32`).
With an unchanged record and no `--repair`, the outcome is `ALREADY_CURRENT`, and the public link is still ensured.

Steps H1 and H2 run in the selecting binary (section 1.3). Steps A1 to A15 run in the target.

| Step | Primitive effects | Lock (I, M) | Crash after |
| --- | --- | --- | --- |
| H1 | P0; resolve the envelope; decide `RELEASE_LAYOUT_UNSUPPORTED` and `HANDOFF_UNSUPPORTED` | -, - | **old** |
| H2 | `mkdir` a private `$TMPDIR` directory; write and `fsync` the record, the binary and `handoff.json`; `execve` | -, - | **old**; a temporary directory |
| A1 | P1 to P5. Admit `Absent`, `Residual`, `Incomplete` or `Current`; admit `Damaged` only with `--repair` | X, X | **old** |
| A2 | Home path checks (V8): the public command is absent or the exact owned link. On a first install, `S/kast-workspaces/containers` is absent or empty | X, X | **old** |
| A3 | With `Current`, verify the current `V` against its manifest and its bound runtime entry against its receipt (T3, V2). Damage proceeds only with `--repair` | X, X | **old** |
| A4 | Downgrade M to `S` with `F_SETLK`, which converts in place. Release I | -, S | **old** |
| A5 | Verify the handoff: the record against `RecordSHA256`, the own executable against the record's `controlSha256` (V1) | -, S | **old** |
| A6 | For each missing bound entry, create the reservation `.kast-runtime-<random>` in the store. Extract, verify, `fsync` each file, `F_FULLFSYNC` once on macOS, then `renameat2(RENAME_NOREPLACE)` or `renamex_np(RENAME_EXCL)` to `runtimes/<sha>`, and `fsync` the store directory. Verify an existing entry. Under `--repair`, stage a replacement for each damaged entry without renaming it | -, S | **old**, with reservations that P5 deletes, or new entries that the next command reuses |
| A7 | `mkdir R/.staging-<random>`. Extract Control and require `layout-v2`. Train the class-data archive with `java` from the runtime entry. Write the three records, set read-only modes, `fsync` each file, and then sync the tree | -, S | **old**, with a staging tree that P5 deletes |
| A8 | `F_SETLK` I to `X`, so new readers wait. Then `F_SETLK` M to `X`, within the P3 budget. On timeout, delete the own staging tree and reservations, then reject with `LOCK_HELD` | X, X | **old** |
| A9 | P5 again, sparing the own staging tree and reservations. The bound set includes the own staging tree's entries | X, X | **old** |
| A10 | With `Current`, retire the outgoing version (section 1.1). With `Damaged` and `--repair`, use forced retirement. A rejection deletes the own staging tree | X, X | **old**, with stopped processes that restart on demand from the old `V`. **Rerun**: the same command |
| A11 | Repair only: for each damaged entry, `renameat2(RENAME_EXCHANGE)` or `renamex_np(RENAME_SWAP)` with its replacement, then `fsync` the store directory | X, X | **old** with repaired entries; the damaged copies sit at reservation names that P5 deletes |
| A12 | `renameat2(RENAME_NOREPLACE)` or `renamex_np(RENAME_EXCL)` of the staging tree to `R/versions/<random>`; `fsync R/versions` | X, X | **old**, with an unnamed `V` that P5 deletes |
| A13 | `symlink R/current.next`; `rename` over `R/current`; `fsync R` | X, X | **new** |
| A14 | Ensure the public link: `symlink ~/.local/bin/kast.next-<random>`, then rename it into place. Use a rename without replacement when the link is absent, and a plain rename only over the exact owned link. `fsync ~/.local/bin` | X, X | **new** |
| A15 | P5, which deletes the outgoing `V` and the entries outside the bound set under the process-use invariant. Delete the handoff directory. Release both bytes | -, - | **new** |

- If A14 fails, A15 still runs, because `current` already names the new `V` and retirement is done. The command then returns `FILESYSTEM_REJECTED` at stage `SWITCH`. **Rerun**: `install.sh`, which finds `LayoutCurrent` with the same record and ensures the link.
- The installed `kast` stays runnable through every row except a first install before A14.
- A start that races A8 to A13 either holds M shared before A8, which delays A8 within its budget, or finds I held and reports `TRANSITION_IN_PROGRESS`.

### 3.3 Uninstall

The commit point is the rename of `R` to the witness. Before it, the installation and the stores are intact, so a rerun can still retire through Kotlin.
Kotlin's user-state removal in `~/.kast` runs before the commit and is not atomic across profiles. Section 3.4 states what a rerun does with it.

| Step | Primitive effects | Lock (I, M) | Crash after |
| --- | --- | --- | --- |
| X1 | P1 to P5. Admit `Current`, `Incomplete` or `Residual`; admit `Damaged` only with `--force` | X, X | installed |
| X2 | With `Current`, verify the retirement executables. Run `managed-retire --removal` (section 3.4) and map its closed reason to `REMOVAL_BLOCKED`. With `--force` or `Damaged`, use forced retirement and skip Kotlin's removal checks | X, X | installed, with stopped processes and possibly some profiles' user state removed. **Rerun**: `kast uninstall` |
| X3 | Prove the process-use invariant for every `V` and bound entry. Then `rename R` to `S/.kast-uninstalled-<random>`, the witness; `fsync S` | X, X | **new** (uninstalled), `Residual` |
| X4 | Finish `Residual` (section 3.5) | X, X | **new**, with less residue |
| X5 | Release both bytes. `S/kast.lock` stays | -, - | **new** |

### 3.4 Kotlin removal checks

`managed-retire --removal` keeps every Kotlin check that guards an external risk:

- rejected and foreign containers (`ManagedWorkspaceRemoval.kt:37-42`, `:65-73`);
- user state in `~/.kast`: `ACTIVE_HOST`, `UNSETTLED_MUTATION`, `CHECKPOINT_REQUIRED` and the other closed reasons (`UninstalledUserState.kt:47-58`).

Today, `captureRetiredWorkspaceRemoval` validates and removes one profile at a time (`ManagedWorkspaceRemoval.kt:168`; `UninstalledUserState.kt:7-43`). A block on a later profile then leaves earlier profiles erased.
Under Go, the command runs in three phases:

1. Validate every container and every profile's user state, with no effect. Any rejection stops here, with nothing removed.
2. Retire the Hosts and the coordinator.
3. Remove user state profile by profile. Before each profile, the child repeats its transition observation and checks that its stdin is open.

An I/O failure in phase 3 can still leave some profiles removed. Removal is idempotent, because removed state is simply absent, so `kast uninstall` finishes it on rerun.

### 3.5 Residue

`LayoutResidual` holds exactly when a witness `S/.kast-uninstalled-<id>` exists.
Any lifecycle command finishes it in P5, without Kotlin, in this fixed order:

1. Under Kotlin's store lifetime lock, held exclusively (`ContainerQueryLease.kt:65-77`, `:186-204`), rename `S/kast-workspaces` and `S/kast-runtime-bases` to `S/.kast-removing-*`. Stores are residue only while a witness exists.
2. Remove `~/.local/bin/kast` if it is the exact owned link. Remove each owned `/tmp/kast-uds-*` alias whose target lay inside `R`.
3. Delete every `S/.kast-removing-*` tree, best effort (section 3.1).
4. Delete the witness contents, then `rmdir` the witness last. Its name is the only `Residual` witness, so it persists until the end.

A crash at any point leaves the witness, and a rerun repeats the remaining steps.
Without a witness, a store that holds containers is not residue, and a first install rejects with `WORKSPACE_STORE_OCCUPIED` (V8).
After X3 the installed `kast` is gone, so the next `install.sh` finishes the residue. Until then it is inert.

### 3.6 Repair after damage

`--repair` is the only path that proceeds on `LayoutDamaged`, a damaged `V`, or a damaged runtime entry.
When the retirement executables fail verification, A10 uses forced retirement.

### 3.7 Forced retirement

Go's forced retirement needs no JVM. It serves `upgrade --repair` with damaged executables and `uninstall --force`.

1. List supervisor entries by label prefix `io.github.amichne.kast.`:
   - Linux: `systemctl --user list-units --all --plain --no-legend 'io.github.amichne.kast.*'`.
   - macOS: `launchctl list`, filtered by the prefix.

   An entry is owned when its program or working directory lies inside `R` or a store (`systemctl show`, `launchctl print`). Stop each owned entry (`systemctl --user stop`, `launchctl bootout gui/<uid>/<label>`), and poll until it is absent (section 1.1).
2. Find the user's processes that use the outgoing `V`, or the runtime entries it binds, under the process-use rule.
3. Send `SIGTERM`, wait up to a bounded deadline, then send `SIGKILL`. Then confirm that each incarnation is absent.
4. Under decision D1, remove each login agent that matches its exact owned document. Leave any other agent, and name it in the outcome.

## 4. The lock

### 4.1 Primitive

- POSIX record locks through `fcntl(F_SETLK)` and `F_GETLK` on two byte ranges of `S/kast.lock`: byte 0 (intent, `I`) and byte 1 (main, `M`). Go uses `golang.org/x/sys/unix`. The JVM uses `FileChannel.tryLock(position, 1, shared)`, the same family on both targets (`ManagementFileLock.kt:26-27`, `ContainerQueryLease.kt:204`).
- **Writers** take I exclusively, then M exclusively, with a bounded wait. While a writer holds I, no new reader enters, so overlapping short queries cannot starve it.
- **Readers** take I shared without waiting, take M shared, then release I. A reader that finds I or M exclusive reports `TRANSITION_IN_PROGRESS`.
- Record locks belong to the process. Closing any descriptor for the file in that process releases every lock it holds on the file.
  So each process has exactly one opener: the Go lock adapter, or the Kotlin `LifecycleLock`. The opener never closes its descriptor before the process ends.
- The lock file is never deleted, so a holder never locks a stale inode. Every opener uses `O_CREAT`, so the file cannot be missing after an open. P2 checks its owner, link count and mode.
- Downgrading exclusive to shared is atomic. Upgrading is not, and a failed upgrade keeps the shared lock.
- `F_GETLK` reports one conflicting holder, not every holder. `LOCK_HELD` evidence says so.

**Locks across `execve`.** POSIX requires that open descriptors and their record locks survive `execve`.
Linux broke that until 5.11 for processes with more than one thread. `unshare_files` ran at the start of `execve`, while other threads still shared the descriptor table. The old table then released its locks.
Commit `b6043501289e` (`exec: Move unshare_files to fix posix file locking during exec`, November 2020) moved it after the other threads exit. A GitHub comparison shows that v5.10 does not contain the commit and v5.11-rc1 does.
A Go process always has more than one thread.
So the design does not rely on a lock surviving `execve`:

- The frontend holds M shared through its own checks (section 4.2), then runs the JVM. On Linux 5.11 or later, and on macOS, the descriptor is passed without `FD_CLOEXEC`, and the lock covers the step. On earlier kernels it does not.
- The `LifecycleLock` in the JVM takes its own lock when it starts, and then checks that `R/current` names the `V` that holds its code (`STALE_VERSION`). A transition that ran in the gap makes that check fail, or makes the lock attempt report `TRANSITION_IN_PROGRESS`. Either way, the JVM fails closed.
- There is no supported minimum kernel for correctness. Linux 5.11 is the minimum for a gap-free lock across `execve`.
  macOS keeps record locks across `execve` as POSIX requires. I did not verify that against a native run, and acceptance case 9 does.

### 4.2 Holders per entry

The frontend acts on every JVM entry (section 1.2: every launcher runs the Go binary):

1. Take M shared as a reader, unless the table says none.
2. Read `R/current` once, and require that its own executable lies in the `V` that it names. Otherwise reject with the catalog's transition status. A frontend started before A13 cannot run old code against a new `V`.
3. Reject any caller-supplied `-Dkast.lifecycle.*` option. The namespace is reserved, and no lock decision reads a property.
4. Run the JVM by `execve`.

The Go lifecycle commands run in the frontend itself and never start a JVM. The frontend routes them before it decodes `product-selection.json` or resolves `java`.
So a missing or damaged selection record, which leaves no `java` to find, never blocks `uninstall --force` or `upgrade --repair`.

| Entry (`--internal-entrypoint`) | Frontend and JVM lock | Why |
| --- | --- | --- |
| `management` with `status`, `stop`, `config`, `install skill`, public queries | M shared for the process lifetime | The invocation reads or writes `R` |
| `management` with `upgrade`, `uninstall`, `versions` | Go lifecycle (section 3) | Go owns these commands |
| `management` with `--internal-install managed-retire` | none | It runs under its parent's exclusive lock and observes it |
| `query` | M shared for the process lifetime | As above |
| `jq` | none | The jq workers are JVM-to-JVM children of a holder (`PublicJqWorker.kt:163-176`, `PublicJqWorkers.kt:113-116`) |
| `mcp`, `codex` | M shared for the process lifetime (decision D4) | A live session is an unsettled invocation (requirement 4) |
| `daemon` | none for its lifetime; gates only | A lifetime holder blocks every upgrade, because retirement needs M exclusive |
| `service` | none for its lifetime; gates only | `kast-service` stops Hosts during retirement |

The `LifecycleLock` in the JVM decides by its own entry. It does not read a property set by the frontend.
Ordinary `java` children of a JVM do not inherit descriptors, because the JVM closes them in the child. So Hosts and workers never hold the lock.

### 4.3 Gates

Kotlin has one gate owner, `LifecycleLock`, a process-wide object:

- **Serialized.** One mutex covers every observation in the process, because Java forbids overlapping locks in one JVM (`ContainerQueryLease.kt:54-55`) and the coordinator observes concurrently (`CoordinatorControl.kt:190-192`, `SemanticCoordinatorPublication.kt:150-153`).
- **Lifetime holders** answer `AVAILABLE` from the lock they hold, because a held M shared excludes every transition. They still evaluate the file half during the dual period.
- **Other processes** probe M shared without waiting on their one open channel. A probe that succeeds releases M at once and reports `AVAILABLE`. A conflict means another process holds M exclusive, which only a lifecycle transition does, so the probe reports `TRANSITION_IN_PROGRESS`.
- **Failures.** An `OverlappingFileLockException`, an `IOException` or a `SecurityException` maps to `OBSERVATION_REJECTED`. It never maps to "transition proven".

The 14 call sites of `InstallationLifecycleFence.observe` call `LifecycleLock`:

- start gates, where only `AVAILABLE` admits: `AppServerManagement.kt:140`, `BrokerServiceStartLock.kt:51`, `InstalledBrokerServer.kt:93`, `InstalledCoordinator.kt:33`, `MacOsPersistentBrokerServiceHost.kt:363`, `:399`, `SemanticCoordinatorPublication.kt:70`, `:152`, `runtime/CoordinatorControl.kt:192`, `:226`;
- inverted gates, where only `TRANSITION_IN_PROGRESS` admits: `SemanticCoordinatorRetirement.kt:26`, `:68`, `runtime/CoordinatorControl.kt:131`, `BrokerServiceStartLock.kt:279`.

Two more gates read lifecycle files and move too: `InstalledProductRuntime.kt:42` (the shutdown fence), and `cli/.../direct/InstalledToolAdmission.kt:15-24` (the fence, the reset fence and the replacement recovery directory).

**Dual period.** Stage 5(b) ships the gate on the old layout. A transition is in progress if the fence file exists or the lock observation reports one.
The lock half uses `S/kast.lock`. Until stage 8, nothing takes M exclusive, so the lock half never reports a transition.
The old-layout upgrade and uninstall stay out of the lock table.
A lifetime holder answers only the lock half from its held lock. It still checks the file half.
Stage 8 deletes the fence writer, and stage 10 deletes the file half.

## 5. Failure type and outcomes

Go has no sum types, so the failure is sealed by construction.
`Failure` has only package-private fields, and each code has exactly one constructor that takes that code's evidence. A code paired with the wrong evidence cannot be built.

```go
type Failure struct {
	code     Code
	stage    Stage    // closed: PRECHECK, PRELUDE, SELECTION, HANDOFF, DOWNLOAD, VERIFY, STAGE, PUBLISH, RETIRE, SWITCH, CLEANUP, REMOVE
	evidence evidence // sealed: one variant per code
}

type evidence interface{ sealed() }

type assetDigest struct { Asset AssetName; Expected, Observed Digest }
type pathEvidence struct { Root RootKind; Path RelativePath } // RootKind: R, RUNTIME_STORE, BASE_STORE, PUBLIC_COMMAND, HOME_SHARE, TMP_ALIAS
type filesystemEvidence struct { Root RootKind; Path RelativePath; Operation FsOperation; Cause FsCause; Errno int }
type transferEvidence struct { Asset AssetName; Cause TransferCause; HTTPStatus int } // HTTPStatus is 0 unless Cause is HTTP_STATUS
type holderEvidence struct { Range LockRange; Mode LockMode; Holder int } // one holder that F_GETLK reported
type processEvidence struct { Holder int; Use ProcessUse } // ProcessUse: EXECUTABLE, ARGUMENT, WORKING_DIRECTORY
type childEvidence struct { ExitCode int; Reason RetirementReason } // RetirementReason mirrors the Kotlin document's closed enum
type supervisorEvidence struct { Supervisor SupervisorKind; Label ServiceLabel; State UnitState }
type noEvidence struct{}

func AssetDigestMismatch(stage Stage, asset AssetName, expected, observed Digest) Failure
// ... one constructor per code
```

- `FsOperation` is closed: `OPEN`, `MKDIR`, `WRITE`, `SYNC`, `RENAME`, `RENAME_NO_REPLACE`, `RENAME_EXCHANGE`, `SYMLINK`, `UNLINK`, `RMDIR`, `CHMOD`, `LOCK`, `STAT`, `STATFS`, `EXEC`.
- `FsCause` is closed: `PERMISSION`, `NO_SPACE`, `READ_ONLY`, `EXISTS`, `NOT_FOUND`, `NOT_EMPTY`, `NAME_TOO_LONG`, `IO`, `UNMAPPED_ERRNO`. `UNMAPPED_ERRNO` carries the bounded `errno` number, so an unmapped error stays exact data.
- The JSON form is `{"schemaVersion":1,"code":...,"stage":...,"evidence":{...}}`. The decoder selects the evidence type by `code` and rejects a mismatch.
- No evidence carries an absolute path, payload bytes, or an environment value.

| Code | Evidence | Check | Inventory group |
| --- | --- | --- | --- |
| `SELECTOR_INVALID`, `SELECTORS_CONFLICT` | none | Exactly one valid selector | V1 |
| `PLATFORM_UNSUPPORTED` | none | `macos-aarch64` or `linux-x86_64` | V1 |
| `CATALOG_UNAVAILABLE` | transfer | The envelope catalog or envelope transfer | V1 |
| `CATALOG_INVALID`, `COMMIT_AMBIGUOUS`, `BUILD_UNAVAILABLE` | none | Envelope shape and selection | V1 |
| `RELEASE_LAYOUT_UNSUPPORTED` | none | An envelope with `layout` 2 exists, decided before `execve`; the payload carries `layout-v2` | new |
| `HANDOFF_UNSUPPORTED`, `HANDOFF_INVALID` | path | A common handoff schema; a valid handoff directory | new |
| `RECORD_DIGEST_MISMATCH` | asset digest | Record bytes against the envelope | V1 |
| `RECORD_INVALID` | path | Record fields; the (a) value checks of T6 | V1, T6 (a) |
| `RECORD_SCHEMA_UNSUPPORTED` | path | A record or document from a newer binary | new |
| `ASSET_UNAVAILABLE` | transfer | Asset transfer | V1 |
| `ASSET_SIZE_MISMATCH`, `ASSET_DIGEST_MISMATCH` | asset digest | Downloaded bytes against the record | V1 |
| `QUALIFICATION_INVALID` | none | Receipt outcome, revision and bundle digest | V1 |
| `ARCHIVE_ENTRY_REJECTED` | path | Archive entries are local, unique and of admitted types | V1 |
| `BUNDLE_INVENTORY_INVALID` | path | Composed inventory and identities | V1 |
| `CONTROL_DIGEST_MISMATCH` | asset digest | Control executable against the envelope and `controlSha256` | V1 |
| `LOCAL_ARTIFACT_REQUIRED` | none | A local record is never fetched remotely | V1 |
| `RUNTIME_ENTRY_DAMAGED`, `BASE_ENTRY_DAMAGED` | path | Store entries match their receipts and manifests | V2, T9 (a) |
| `PAYLOAD_MANIFEST_INVALID`, `PAYLOAD_FILE_DAMAGED` | path | `V` matches its manifest | T3 |
| `RETIREMENT_EXECUTABLE_DAMAGED` | path | Retirement code verifies before it runs | T3, V2 |
| `HOME_INVALID`, `HOME_PATH_REJECTED`, `ROOT_USER_REJECTED` | path or none | Home admission | V8 |
| `FILESYSTEM_UNSUPPORTED`, `CROSS_DEVICE` | path | Local file system, one device | new |
| `ROOT_FOREIGN` | path | `R` holds only Kast names; no witness beside a valid `current` | V8 |
| `LEGACY_LAYOUT` | path | No legacy marker | new |
| `LAYOUT_DAMAGED` | path | `current` and its records are valid, unless `--repair` or `--force` | new |
| `PUBLIC_COMMAND_FOREIGN` | path | The public command is absent or the owned link | V8 |
| `WORKSPACE_STORE_OCCUPIED` | path | No containers before a first install, without a witness | V8 |
| `LEFTOVER_FOREIGN` | path | A cleanup candidate is the user's, not a link, and has a Kast name | V8 |
| `LOCK_FILE_REJECTED` | path | Lock file owner, link count and mode | T10 (a) |
| `LOCK_HELD` | holder | The lifecycle lock; a shared holder is also an unsettled invocation | T10 (a), V10 |
| `PROCESS_STILL_ALIVE` | process | The process-use invariant; retired incarnations are gone | T8 (a) |
| `RETIREMENT_REJECTED`, `RETIREMENT_TIMED_OUT` | child | Child exit, document and deadline | T8 (a) |
| `REMOVAL_BLOCKED` | child | Kotlin's removal checks before uninstall | Kotlin workspace and user-state checks |
| `SUPERVISOR_STILL_ACTIVE` | supervisor | No unit or job remains | T8 (a) |
| `FILESYSTEM_REJECTED` | filesystem | An effect failed | T9 (a) |

Outcomes are a separate closed union: `INSTALLED`, `UPGRADED`, `REPAIRED`, `ALREADY_CURRENT`, `UNINSTALLED`, and `UNINSTALLED_WITHOUT_USER_STATE` for `--force`.
An outcome can carry observations: `SUPERVISOR_UNAVAILABLE`, `RESIDUE_RETAINED`, and the names of login agents left in place.
V10 survives as `LOCK_HELD` under decision D4. The interpreter checks of V1 (`private_interpreter.py:67-98`) have no successor.

**Payload checks per use.** Kotlin hashes `kast-service` against the manifest before it runs it (`InstallationLifecycle.kt:243-251`, `ContainerManagement.kt:56-61`).
Go keeps those checks at the boundaries where code from `V` runs during a lifecycle command: A3 and step 2 of retirement. Section 14 explains why the check on every ordinary use goes.

## 6. Go package boundaries

```text
frontend/
  cmd/kast/main.go                 process wiring only
  cmd/kast-bootstrap/main.go       steps H1 and H2
  internal/frontend/               existing routes; per-entry lock table (section 4.2)
  internal/lifecycle/
    failure/                       Failure, codes, sealed evidence, outcomes                 (pure)
    record/                        section 2.2 types and strict decoders                     (pure)
    envelope/                      the frozen, tolerant selection envelope and handoff       (pure)
    layout/                        LayoutObservation from an observed tree                    (pure)
    selection/                     selector resolution over envelopes                         (pure)
    plan/                          each machine as Next(state, observation) -> (effect, state) (pure)
    run/                           interpreter: performs one effect, observes, feeds plan
    effect/fs/                     one primitive per effect: open, mkdir, write, fsync, full-sync, rename,
                                   rename-no-replace, rename-exchange, symlink, unlink, rmdir, chmod, lock, stat, statfs
    effect/archive/                tar.gz and zip extraction through effect/fs, with entry admission
    effect/net/                    HTTPS GET with size limit, timeout, HTTPS-only redirects
    effect/process/                child execution with deadline and stdin pipe; execve; process-use scan; signals
    effect/supervisor/             launchctl list, print, bootout; systemctl --user list-units, show, stop
```

- Pure packages import no `os`, `net`, `os/exec`, `syscall` or `time`. A dependency check in `frontend/check gate` enforces that with `go list -deps`.
- `run` receives each adapter as an interface value built in `main.go` from explicit configuration. Nothing below `main.go` reads the environment.
- A rename without replacement uses `renameat2(RENAME_NOREPLACE)` on Linux and `renamex_np(RENAME_EXCL)` on macOS. An exchange uses `RENAME_EXCHANGE` and `RENAME_SWAP`.
- Files are synced with `fsync`. On macOS, one `fcntl(F_FULLFSYNC)` before each publishing rename flushes the drive cache, so per-file calls stay cheap.
- The interpreter emits one bounded stage event per step to stderr: step, outcome, and elapsed milliseconds. That is durable instrumentation (Engineering Dictum 9). Tests do not synchronize on it.

## 7. Tests at the narrowest boundary

| Boundary | What it proves | Inputs |
| --- | --- | --- |
| `failure`, `record`, `envelope` | Encoded shape against checked-in expected JSON for each code and outcome. Explicit nulls. Strict rejection of unknown fields, codes, versions and code-evidence mismatches. The envelope's tolerance of unknown fields | Literal values |
| `layout` | Every observation, the fixed precedence, every `S`-level name in section 2.1, and every mixed tree | Tree descriptions |
| `plan` | The effect chosen for each observation. The expected outcome comes from section 3, not from the implementation | Pure tables |
| `plan`, sync order | Every publishing rename follows the sync of its contents, and a sync of the parent follows it | The effect list a plan emits, compared with a pure rule |
| `run`, crash at every effect | For install, upgrade, repair, uninstall and forced uninstall, and for each effect index k: an `effect/fs` double performs effects 0 to k-1, then stops. A rerun of each command on the resulting tree must satisfy the recovery invariant below, and a second rerun must change nothing | A real `t.TempDir()` tree, a stub retirement child, fixture products |
| `run`, power loss | As above, but the double also drops every directory operation after the last `fsync` of its parent | Same |
| `effect/fs` | Lock ranges and modes; atomic downgrade; a failed upgrade keeps the shared lock; lock file checks. Rename without replacement, exchange and link swap. Owned deletion that refuses links and foreign owners. Network file systems reject | A private `t.TempDir()` |
| `effect/fs` interoperability | A JVM holder of either byte range excludes Go, and the reverse, in both modes | A real JVM child, as `ManagementFileLockTest` does with Python |
| Kotlin `LifecycleLock` | Concurrent gates in one JVM never overlap. Inverted gates map an overlap, an `IOException` or a conflict correctly. A lifetime holder keeps its lock across gates | Unit tests on one channel and a holder child |
| Frontend | A real JVM entry passes a gate, and the test's exclusive attempt still fails while it lives. A caller `-Dkast.lifecycle.*` rejects. A frontend whose `V` is not `current` rejects | The built binary and a real JVM |
| Kernel minimum | On Linux 5.11 or later, a lock held by the frontend survives into the JVM. On earlier kernels, the check in the JVM still fails closed after a transition in the gap | Native runner kernel; the earlier-kernel case runs where a runner provides one, and is reported as not run otherwise |
| `effect/archive` | Rejection of absolute paths, `..`, duplicates, escaping links, oversize entries | Archives built in the test |
| `effect/net` | Size limit, timeout, HTTPS-only redirects, status mapping | `httptest.NewTLSServer` |
| `effect/process`, `effect/supervisor` | Exact argument vectors; `LoadState` parsing and polling; exit mapping; process-use scan on fixture process tables | A scripted executor that rejects unexpected, excess and unconsumed calls |
| `run`, lock interactions | Rerun after `LOCK_HELD` succeeds. A reader present at A8 delays A8 within its budget; past it, A8 rejects with the own tree deleted. A second command rejects at P3. A start racing A8 to A13 is refused | A holder child that the test controls, ordered by the lock itself, not by time |
| `run`, broker state | Across an upgrade, D1's calls happen, and no login agent names a deleted `V` | Scripted supervisor executor |
| Kill of the Go parent | A killed Go parent during A10 or X2 leaves its child stopped before its next destructive step | A real child and a closed pipe |

**Recovery invariant.** After every crash prefix and rerun:

- the classification is total and is one of `Absent`, `Current` or the expected terminal state;
- no Kast-named leftover remains except `RESIDUE_RETAINED` trees;
- every bound entry exists and verifies;
- no name in the legacy marker set exists.

Crashes are injected at the adapter by effect index, so the tests cover every point inside a row. Each effect is one primitive, so no crash point falls inside an effect.

## 8. Installed acceptance on both targets

The native harness runs on `ubuntu-24.04` for `linux-x86_64` and `macos-15` for `macos-aarch64` (`composed-linux.yml:68-69`).
Its cases already include fresh install, upgrade to a different build, and uninstall (`packaging/internal/verification/product_acceptance.py:15-30`).
The switch adds or changes these cases on both targets. Each case ends in the state `LayoutCurrent` (or `LayoutAbsent` after uninstall), with no name from the legacy marker set.

1. **Install** through the generated `install.sh` and the pinned bootstrap, then a first semantic query.
2. **Configuration reach**: a distinctive value in `R/config/environment` is observed by every entry (section 1.2).
3. **Upgrade** while a coordinator and a Host run. The old incarnations exit. On Linux, `LoadState=not-found` holds; on macOS, `launchctl list` reports the job absent. A query then succeeds from the new `V`, and the readiness document names it.
4. **Reinstall** over `LayoutCurrent` through `install.sh`, which retires like an upgrade.
5. **Repair** after a damaged runtime file, including damaged retirement executables, through forced retirement.
6. **Uninstall**: no `R`, no witness, no stores, no public command, no owned `/tmp` alias, and no supervisor entry. On macOS, no owned login agent remains.
7. **Interruption**: native kills during A10 and X2, and after X3. A kill of the Go parent during A10 while its child lives. The crash-index tests in section 7 cover every effect.
8. **Busy refusal**: an open MCP session makes `upgrade` reject with `LOCK_HELD` (decision D4).
9. **Lock across `execve`**: the frontend case from section 7, on the installed product, with the runner's kernel version recorded.
10. **Legacy rejection**: over a legacy baseline, `install.sh` rejects with `LEGACY_LAYOUT`. Its only effects are P0's reads; no lock file or directory is created. After the legacy `kast uninstall`, it installs.
11. **Old upgrade refusal**: the legacy `kast upgrade` rejects the new release with `INSTALLER_CONTRACT_UNSUPPORTED` at stage `CONTRACT`.
12. **Old stable refusal**: Go selecting `v0.52.0` rejects with `RELEASE_LAYOUT_UNSUPPORTED` before any `execve`.
13. **Configuration edit**: `kast config` edits do not create a legacy marker, and a later `upgrade` succeeds.
14. **Forced uninstall over a damaged selection**: with `product-selection.json` deleted from the installed `V`, `kast uninstall --force` reaches Go with no `java` lookup and ends in `LayoutAbsent`.

Running without a user supervisor and running as user 0 are covered at the adapter boundary only. Neither CI runner provides those states natively.
The Linux coordinator probe reads labels from the retirement document instead of hashing `root / "installation"` (`linux_coordinator_probe.py:164-165`).

## 9. Staged implementation plan

Each stage is one pull request with its own tests. Stages 1 to 7 change no installed layout.
Stage 1 waits until this revision, with fixes F1 to F4, is accepted.

1. **Go lifecycle core.** Add `failure`, `record`, `envelope`, `selection`, `layout` and `plan`, with pure, sync-order and dependency-boundary tests, and amend the frontend flag rule.
   It encodes decisions D4, D6, D7 and D9 and the machines of section 3. `record` defines `ProductSelection` at `schemaVersion` 2 (section 2.2).
2. **Go effect adapters.** Add `effect/*`, including the process-use scan, `execve`, and the JVM lock interoperability test for both byte ranges.
3. **Kotlin layout owner.** #1377, merged at `fec3978de2`.
4. **Kotlin layout reshape.** Make `InstallationLayout` hold `S`, `R` and `V`, with `lifecycleLock` and the store siblings, admitted at the boundary that reads `current` and reports `STALE_VERSION`.
   Delete `ofConfigurationFile`, route the upward derivations, extend the architecture rule to `parent` chains, and add `kast.lock` to its record names. Add the configuration-reach check. Behavior stays unchanged on the old layout.
5. **Early behavior on the old layout.** One pull request each. Item (a) moved to stage 8, and the other items keep their letters:
   - (b) `LifecycleLock` on `S/kast.lock` with both byte ranges, serialized gates, the failure mapping of section 4.3, and the dual gate (lock half plus file half). Add the per-entry lock table of the frontend, its `current` check (inert until stage 8), the reserved property namespace, and the inheritance tests. Old-layout upgrade and uninstall stay out of the table.
   - (c) `managed-retire` emits the versioned retirement document, deactivates the broker (D1), and supports `--removal` with validate-all-first and the stdin stop rule. The version identifier goes into readiness.
   - (d) `kast status` receives its installation facts from Go.
6. **Release assets.** Publish `kast-control-<target>`, `lifecycle-envelope.json` and `lifecycle-catalog.json`, with the digest equality gate. Add the bootstrap workflow as pre-releases, the reproducibility check, and the `releases/latest` assertion. The record, `builds.json` and `release-mapping.json` stay unchanged.
7. **Go lifecycle end to end, unwired.** Add `run`, `kast-bootstrap`, the handoff, and the install, upgrade, uninstall, versions and forced-retirement commands. Add the crash-index, power-loss and lock-interaction tests against fixture products. Prove the Kotlin behavior that D9 relies on.
8. **Switch.** In one pull request:
   - flip the body of `InstallationLayout.admit` and derive the semantic identity from `R`;
   - give `kast config` its own `R/config/edit.lock`, and delete `withRegistrationLock` and `withWorkspaceEditLock`, whose files `R/management.lock` and `R/activation.lock` are legacy markers (`ManagementFileLock.kt:11-18`, `ManagementWorkspaceConfig.kt:41`);
   - delete the fence writer in `managed-retire` (`InstallationLifecycle.kt:135-148`, called through `ManagedWorkspaceRetirement.kt:28`, `:75`), so retirement never writes `R/shutdown.json`;
   - move the old-record readers in retirement to `InstallationLayout` and Go-verified facts: `InstallationLifecycle.admitProduct` (`:203-220`), `shutdownContainerHost` (`ContainerManagement.kt:56-61`), and `InternalManagementInstall.kt:61-67`;
   - former stage 5(a), #1378: the frontend derives the runtime entry from the version-2 `product-selection.json` in its own `V`, not from `management.json`. It never reads another version's records. Its decoder accepts version 2 only;
   - turn on the `current` check in the frontend, and wire the Go commands. These include `uninstall --force` and `upgrade --repair` through forced retirement (D6);
   - add `layout-v2`, drop `managed-install-v1`, and run installed acceptance on both targets.

   Stage 5(a) depends on D6. In the old layout, `kast uninstall --force` reaches Kotlin's reset through the frontend and the JVM, so it needs `java`.
   Merged first, 5(a) leaves the frontend no `java` to run when `product-selection.json` is missing or damaged. `kast uninstall --force` is then unreachable exactly when the record is damaged.
   Go's forced path cannot move earlier instead, because before stage 8 it must act on the legacy layout, which D7 rejects.
   In stage 8, the Go `uninstall --force` is routed before any `java` lookup (section 4.2), so a damaged record becomes `LayoutDamaged` and Go removes it.
   The two `NativeForceResetCompositionTest` cases that block #1378 test Kotlin's reset through the public launcher. Stage 8 makes that path unreachable and replaces them with acceptance case 14. Stage 10 deletes the Kotlin files.

   Only previews carry this layout until a stable promotion. Until stage 9, Python `install.sh --channel preview` fails closed with `INSTALLER_CONTRACT_UNSUPPORTED`, and the default stable installation still installs the old layout.
9. **Public installer switch.** It merges after a stable promotion of a stage-8 build and after its bootstrap release exists. Regenerate `install.sh` to pin and run the bootstrap.
10. **Deletion.** It merges right after stage 9, as a stack:
    - Delete `packaging/internal/{installation,product}`, `packaging/internal/bootstrap/{bootstrap_entry.py,private_interpreter.py,install-candidate.sh}`, and the engine embedding in `generate_bootstrap.py`.
    - Delete the Kotlin lifecycle files: `ManagedUpgrade.kt`, `ManagementUninstall.kt`, `UninstallManagedRoot.kt`, `UninstallRetirementJournal.kt`, `ProductRemovalJournal.kt`, `RecordedRecoveryInstaller.kt`, `InstallationReplacementReceipt.kt`, `ManagementReceiptAdmission.kt`, `ProductManagementReceipt.kt`, `BundledManifest.kt`, `InstallationRecoveryRemoval.kt`, `ManagedRecoveryRemovalProof.kt`, `PriorInstallationPreparation.kt`, `ManagementFileLock.kt`, the force-reset files, the shutdown fence contract, the catalog files, and the file half of the dual gate.
    - Move `importContainerRuntime` into test fixtures.
    - Delete the Gradle tasks `installationRecoveryTest`, `installerRemovalTest`, `installationSystemPythonTest` and `localInstallationTest`, and their `productBuildGate` entries in the root `build.gradle.kts`.
    - Contributor assembly in `local_product_install.py` moves to `install --local-artifact` first.

Every green push to `main` publishes a preview (`.github/workflows/developer-release.yml:36-44`).
Stage 8 therefore reaches preview users immediately, and stage 9 waits for the stable gate.

## 10. Rollback

Rollback is a forward fix only.
After stage 8, previews install the new layout, and no earlier engine can read it.
Reverting stage 8 on `main` publishes a preview whose Python engine rejects the new layout's `R` as unknown content.
So a defect after stage 8 is fixed by a new release, which the installed Go `kast upgrade` installs.
If the installed Go binary itself cannot upgrade, the user runs `kast uninstall`, then the current `install.sh`.
Stages 1 to 7 revert like any other change, because they change no installed layout.

## 11. Risks

- **The switch.** Stage 8 still changes paths, gates, retirement and the installer in one release. Stages 4 to 7 move the behavior earlier, and installed acceptance on both targets gates it.
- **Preview gap.** Between stages 8 and 9, the public preview installation fails closed.
- **Lost user state.** Users of the old layout lose client configuration, workspace containers and exact-root trust once (decision D7).
- **Broker login agent.** On macOS, a login agent that names a deleted `V` restarts every 10 seconds (`BrokerLaunchdServiceDocument.kt:40-43`). Decision D1 removes that state.
- **Lock starvation.** Long MCP or Codex sessions block upgrade until they end (decision D4). The intent byte stops new readers from extending the wait.
- **Process-wide lock semantics.** A second opener of the lock file in one process releases its lock. The single owner in each language and the `LifecycleLock` tests reduce the risk.
- **Old kernels.** On Linux before 5.11, the lock does not cover the step into the JVM. The lock and `current` check in the JVM keep that gap fail-closed, not gap-free.
- **Process-use scan.** The scan is a runtime observation of other processes, not a proof of intent. A process that opens files in `V` without naming it in its argument vector or working directory escapes the scan. Kast's own processes always name `V` in `-cp`.
- **Durability.** Power-loss behavior depends on sync ordering. The sync-order and power-loss double tests support it; no native power-loss test does.
- **Re-download.** An interrupted command downloads again on rerun, because P5 trusts no unnamed tree.

## 12. What stays true

| Requirement (inventory, section 5) | Where this design holds it |
| --- | --- |
| 1. Digest chain before execution | Section 1.3; H1, H2, A5 to A7 |
| 2. Hosts and coordinator stop before a switch; starts held off | Section 1.1; A8 to A13; section 4 |
| 3. Configuration, containers and trust survive upgrade and repair | `R/config`, `R/state` and the stores sit outside every `V` |
| 4. No switch or removal while an invocation is unsettled | Section 4.2 with decision D4; the process-use invariant |
| 5. Power loss leaves old, new, or a state that one command finishes | Section 3 tables; recovery invariant |
| 6. Never delete a path Kast did not create | P5; `ROOT_FOREIGN`; section 2.1 name table; section 3.5 |
| 7. One lock primitive | Section 4.1 |
| 8. Each rejection names its check | Section 5 |
| 9. Offline repair only if promised | Repair downloads again, as today (`InstalledProductRuntime.kt:219-224`) |

## 13. Decisions

The user approved D1 to D9 as recommended, recorded on #1369.

1. **D1 (approved: A).** `managed-retire` enumerates `R/state/broker/*/service.plist` and runs `BrokerServiceDeactivation` in `DISABLE` mode for each one. Its document lists the labels. The next Codex launch republishes them through `Enable`.
   A login agent that does not match its exact document stays, and retirement rejects with reason `LOGIN_AGENT_FOREIGN`. If #1319 removes the login bootstrap first, only the job remains to retire.
2. **D2 (approved: A).** Derive the label, readiness identity and runtime-directory owner from `R`, and put the version identifier in the readiness document.
3. **D3 (approved: A).** Kotlin derives the runtime root from its own `java.home`, minus the catalog's Java home path for the platform (`ContainerRuntime.kt:397`), then admits the entry through `ContainerRuntime` (`:100-123`). Kotlin reads no version record. Every JVM entry runs on `java` from the runtime entry, through the frontend.
4. **D4 (approved: A).** The per-entry lock table in section 4.2; the invocation records are deleted.
5. **D5 (approved: A).** A pre-release `bootstrap-<12 hex>` with reproducible pinned digests. It hands off to the release's own binary, and the pin moves in a separate pull request after the release exists.
6. **D6 (approved: A).** Go owns `uninstall --force` completely through forced retirement (section 3.7). The Kotlin force-reset files are deleted.
7. **D7 (approved: A).** Reject with `LEGACY_LAYOUT` and direct the user to the legacy `kast uninstall`. Nothing carries over.
8. **D8 (approved).** Stage 8 alone, stage 9 after the stable gate, and stage 10 stacked on stage 9.
9. **D9 (approved: A).** P5 and A15 delete runtime and base entries outside the bound set. A container whose retired generation names a deleted entry gets a new generation on its next start, and stage 7 proves that first.

**Mechanics changed by the re-audit, with each decision kept.**

- **D3.** The first draft rejected option C because start scripts bypass Go. That premise is false: every shipped launcher runs the Go binary (`cli/src/main/control-launchers/share/kast/libexec/kast-service`, `cli/build.gradle.kts:303-308`). Option A stays valid, so the decision stands. Its rationale is now reuse of `ContainerRuntime` admission, not the bypass.
- **D4.** The JVM now takes its own lock instead of relying on an inherited one (F7). The lock table and the deletion of invocation records are unchanged.
- **D5.** The bootstrap now reads a frozen selection envelope, not the product record (F4). The pre-release bootstrap and the separate pin bump are unchanged.
- **D6.** Stage 5(a) now depends on D6 and merges inside stage 8 (section 9). Go's forced path is unchanged; only its position in the plan constrains another stage.
- **D9.** The bound set now includes the command's own staging tree (F1). That refines option A, and the decision stands.

No finding proved a decision wrong.

## 14. Audit responses

### 14.1 First audit

Each finding was checked against `origin/main` at `a4b830ff2e` before the change.

| Finding | Response | Change |
| --- | --- | --- |
| B1 | Accepted. `PublicInstallationReset.kt` rejects managed state, and `install.sh:26-27` has no removal option | Section 1.4: `LEGACY_LAYOUT` only; Go runs no legacy binary |
| B2 | Accepted | Section 3.1: the prelude is always exclusive. Section 3.2: staging stays private until A12, and P5 runs again at A9. Own trees deleted on rejection; random identifiers; transient links in P5 |
| B3 | Accepted. `installation.mdx:32` documents re-running the installer | Section 3.2: one machine with A3 and A10 over `LayoutCurrent` |
| B4 | Accepted | Section 3.3: the rename of `R` is the commit point; section 3.5 |
| B5 | Accepted. `CoordinatedToolClient.kt:89-95` reaches `BrokerServiceStartLock.kt:51` in the same JVM | Section 4.2 and 4.3; revised again under F7 and F8 |
| B6 | Accepted | Section 1.3: record unchanged, fixed asset name, `layout-v2`, pre-release bootstrap, `releases/latest` assertion; stage 9 gated |
| M1 | Accepted | Section 1.2; stage 4 |
| M2 | Accepted | Sections 2.2 and 2.3; D3 |
| M3 | Accepted | Section 1.1 step 2; section 3.7 |
| M4 | Accepted | Section 1.1 steps 3 and 5 |
| M5 | Accepted | D1 |
| M6 | Accepted | Section 4.3 |
| M7 | Accepted; reworked under F4 | Section 1.3 handoff |
| M8 | Accepted in part | Section 3.4; `ROOT_FOREIGN` policy. Rebuttal on per-use verification below |
| M9 | Accepted | Section 7 |
| M10 | Accepted | Stage 1 dependencies; stage 5; section 10 |
| M11 | Accepted | Section 1.3; D5 |
| Minor findings | Accepted | Sections 2.1, 3.5, 4.1, 5 and 6 |

**Rebuttal, per-use payload verification (M8).**
This design keeps manifest verification at every lifecycle boundary where code from `V` runs (A3; retirement step 2). It drops hashing on every ordinary use.
Evidence: ordinary JVM invocations already run `lib/` without any manifest check (`frontend/internal/frontend/control.go:128-146`).
The per-use checks guard only `kast-service`, and only on lifecycle paths (`InstallationLifecycle.kt:172-193`, `ContainerManagement.kt:56-61`), which Go now covers before it runs them.
`V` is read-only, and only a rename under the exclusive lock replaces it. An in-place change needs the owner to change its modes first.
A per-use check keeps Kotlin a reader of `installation.json`, which the decision in #1369 removes.

### 14.2 Re-audit responses

Each finding was checked against `origin/main` at `fec3978de2` before the change.

| Finding | Response | Change |
| --- | --- | --- |
| F1 | Accepted. A9's P5 used only `current` as the bound set | Section 2.2 bound set; A9 and A15 |
| F2 | Accepted. `LayoutIncomplete` covered a missing `current` and an invalid one | `LayoutDamaged`; `LAYOUT_DAMAGED`; `--repair` and `--force`; the process-use invariant in section 1.1, applied in P5, A15 and X3 |
| F3 | Accepted. (a) `ContainerQueryLease.kt:64-77` keeps the lifetime lock on purpose. (b) `ForceResetFence.kt:47` and `run-native-public-reset.py:83` keep the reset lock | Section 2.1 name table; the witness `S/.kast-uninstalled-<id>`; `Residual` ranked above `Damaged`, `Current` and `Incomplete`; the finishing order in section 3.5. A `.kast-removing-*` tree is no longer a witness |
| F4 | Accepted | Section 1.3: frozen envelope, layout decided before `execve`, `execve` in place with no lock, a versioned handoff, and the target runs P1 to A15 |
| F5 | Accepted. `ManagementFileLock.kt:11-18` creates `R/management.lock` and `R/activation.lock`; `InstallationLifecycle.kt:135-148` writes the fence; `InstallationLifecycle.kt:203-220`, `ContainerManagement.kt:56-61` and `InternalManagementInstall.kt:61-67` read old records | Stage 8 list; `R/config/edit.lock`; acceptance case 13 |
| F6 | Accepted | Section 4.3 dual period; stage 5(b) |
| F7 | Accepted. The kernel claim holds (section 4.1). `control.go:142` appends caller options after the options of the frontend | The JVM takes its own lock and checks `current`; no lock decision reads a property; `-Dkast.lifecycle.*` is reserved; `kast.lock` joins the architecture rule |
| F8 | Accepted. Overlap is forbidden in one JVM (`ContainerQueryLease.kt:54-55`); the inverted gates are `SemanticCoordinatorRetirement.kt:26`, `:68`, `CoordinatorControl.kt:131`, `BrokerServiceStartLock.kt:279` | Section 4.3: serialized observations and the failure mapping |
| F9 | Accepted | Section 4.2 step 2: the frontend rejects when its `V` is not `current`. It does not run another binary, so the argument vector and environment stay unchanged |
| F10 | Accepted. Removal runs per profile (`ManagedWorkspaceRemoval.kt:168`; `UninstalledUserState.kt:7-43`). The first draft cited the failure enum, `UninstalledUserState.kt:47-58`, for the removal itself | Section 3.4: validate first, the stdin stop rule, and the corrected commit claim |
| A11 exchange | Accepted | A11 uses `RENAME_EXCHANGE` or `RENAME_SWAP` |
| P1 and P2 effects | Accepted | P0 decides `Legacy` and `Foreign` with no effect; P1 creates home directories; acceptance case 10 states the exact effects |
| Residue that cannot be deleted | Accepted | Section 3.1: kept, reported as `RESIDUE_RETAINED`, never blocking |
| Starvation | Accepted | Section 4.1: intent byte gives writers priority; section 4.2: a reader's failure reports the transition status |
| `--force` and `LOCK_HELD` | Accepted | Section 1.5; `holderEvidence` names one holder |
| `versions` and `--dry-run` | Accepted | Section 1.5 |
| jq workers | Accepted | Section 4.2 table |
| Start scripts | Accepted. The launchers run the Go binary | Section 1.2; D3 note |
| Profile hash in 3.6 | Accepted. `InstallationLayout.kt:303-316` | Section 3.7 lists entries by label prefix |
| M4 polling and document version | Accepted | Section 1.1 steps 3 and 5 |
| Sync cost | Accepted | Section 6 |
| A14 failure | Accepted | Section 3.2 |
| Test plan additions | Accepted | Section 7: every command over every prefix, the recovery invariant, the power-loss double, parent kill, lock cases, and the kernel case. Section 8: the end state and cases 7, 9, 12 and 13 |
| Per-step table | Accepted | Sections 3.1 to 3.3 list the primitive effects per row |

No re-audit finding is rebutted.

### 14.3 Review of the second revision

| Item | Response | Change |
| --- | --- | --- |
| Stage ordering | Accepted. #1378 finds `java` only through `product-selection.json`, and in the old layout `kast uninstall --force` needs `java` | Stage 5(a) depends on D6 and merges inside stage 8; section 4.2 routes Go lifecycle commands before any `java` lookup; acceptance case 14 |
| Selection schema version | Accepted. The new shape drops 5 fields, adds 2, and changes `version` under `schemaVersion: 1` | `ProductSelection` is version 2, defined in stage 1 and first written at stage 8 (section 2.2) |
