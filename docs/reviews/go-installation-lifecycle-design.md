# Go installation lifecycle: design specification

## Conclusion

The Go `kast` binary can own the whole installation lifecycle with four records, one lock file, and one recovery rule.
The records are the `R/current` link and three immutable files in each version directory.
The lock is a POSIX record lock with two modes: shared for every user of `R`, exclusive for every change to its structure.
Every lifecycle command first takes the lock, then deletes whatever `R/current` does not name.
That one rule replaces the replacement journal, the shutdown fence, the uninstall journal, and the three recovery routes.

The design needs one Kotlin change that cannot ship apart from the Go switch.
Kotlin derives the installation root from its code location in about 60 places (question 2).
A preparatory pull request routes every one of them through one typed `InstallationLayout` without changing behavior.
The switch pull request then flips that one owner to `R/config` and `R/state`, in the same release as the Go switch.

Four facts from the current source shape the design:

- Linux has no persistent unit. The coordinator runs as a transient `systemd-run --user --collect` unit that nothing restarts (question 1).
- On macOS, the Codex broker job and its login agent name an absolute path inside the Control payload (`PersistentBrokerService.kt:262-263`). Current retirement does not stop them.
- The semantic service label and socket identity derive from the installation path (`SemanticServiceLaunch.kt:43`, `:60`, `:118-120`). With a directory per version, they change on every upgrade.
- `install.sh` comes from `main`, not from a release (`docs/public/reference/installation.mdx:29`). So it cannot pin a release binary's digest without a separate bootstrap release.

Section 12 lists eight decisions that need the user's answer before implementation.

## Revision and scope

- Base revision: `a4b830ff2e` (`origin/main`, after #1371, #1373 and #1374).
- Inputs: issue #1369 and sections 1 to 6 of `docs/reviews/installation-lifecycle-inventory.md` on branch `docs/lifecycle-inventory`.
- Method: source reading and reference search. No build, test, or native lifecycle run supports a claim here.
- Notation: `R` is `~/.local/share/kast`. `V` is one version directory, `R/versions/<id>`. `S` is `~/.local/share`.
- The Go frontend has 868 lines of non-test source today (`frontend/cmd/kast/main.go` and four files in `frontend/internal/frontend`).
- Citations are repository-relative. Where the text states a judgment or an expectation, it says so.

## 1. Answers to the open questions

### 1.1 Coordinator and Host supervision, and retirement

**Linux today.**

- The coordinator is a transient user unit.
  `LinuxPersistentBrokerServiceHost.submitExclusively` first runs `systemctl --user is-active --quiet <label>.service`.
  When that call reports the unit absent, it starts the unit with `systemd-run --user --quiet --collect --unit=<label>.service --property=Type=exec` (`app-server/.../LinuxPersistentBrokerServiceHost.kt:19-47`, `:61-74`).
- The unit sets `WorkingDirectory` to the installation directory, so the working directory sits inside the Control payload (`:69`).
- No unit file is written, no unit is enabled, and no `Restart=` property is set. Nothing restarts the coordinator at login or after an exit.
  The next semantic request starts it again through `InstalledSemanticCoordinator.connect` (`app-server/.../InstalledSemanticCoordinator.kt:58-80`).
- The label is `io.github.amichne.kast.semantic.<first 32 hex of sha256(installation path)>` (`SemanticServiceLaunch.kt:43`, `:60`, `:118-122`).
  The probe computes the same unit name to read the journal (`packaging/internal/verification/linux_coordinator_probe.py:164-166`).
- The Codex broker service is unavailable on Linux. `ensure` returns `UNAVAILABLE` (`LinuxPersistentBrokerServiceHost.kt:13-14`). There is no login entry.
- Workspace Hosts are not supervised. The coordinator starts each one as a child with `ProcessBuilder` (`app-server/.../ide/IdeLaunchPlatform.kt:17-33`).
  The unit keeps the default kill mode. So I expect the `systemd` manager to stop Hosts in the unit's control group when the coordinator exits.
  No native run supports that expectation.

**macOS today.**

- The coordinator is a launchd job in `gui/<uid>`. Its property list lives at `<installation>/state/broker/<profile>/semantic-service.plist`.
  `submitSemanticExclusively` bootstraps it when `launchctl list <label>` reports it absent, or `kickstart`s an owned job (`MacOsPersistentBrokerServiceHost.kt:321-351`, `:933-947`).
- The semantic job has `RunAtLoad`, `WorkingDirectory` set to the installation, and `AbandonProcessGroup`, with no `KeepAlive` (`BrokerLaunchdServiceDocument.kt:36-55`). It uses the same installation-derived label as Linux.
- The Codex broker job is a separate launchd job. Its label is `io.github.amichne.kast.broker.<sha256(codexHome)[:32]>` (`PersistentBrokerService.kt:190-202`).
  It has `KeepAlive` on unsuccessful exit with a 10-second throttle (`BrokerLaunchdServiceDocument.kt:40-43`).
  Its program is `<installation>/share/kast/libexec/kast-daemon` (`PersistentBrokerService.kt:262-263`).
- The login agent at `~/Library/LaunchAgents/<broker label>.login.plist` is the same broker job document with `--login` (`ServiceLoginAgent.kt:21-59`).
  `AppServerManagement` publishes it on `Bootstrap` and `Enable` (`AppServerManagement.kt:270-284`). The Codex client launcher calls `Enable` at each client launch (`host/InstalledCodexClientLauncher.kt:382`).
- Hosts are coordinator children here too. `AbandonProcessGroup` lets them outlive the coordinator.

**Retirement today.**
`--internal-install managed-retire` runs `retireManagedProduct` from the outgoing Control (`distribution/cli/.../InternalManagementInstall.kt:28-29`, `:59-72`).

1. It leases every workspace container and writes the shutdown fence inside the lease (`ManagedWorkspaceRetirement.kt:41-80`).
2. It stops each Host with `share/kast/libexec/kast-service disable` (`ContainerManagement.kt:45-79`).
3. It retires the coordinator with `kast-service retire-semantic` (`InstallationLifecycle.kt:42-57`, `:169-189`).
   That command sends `DaemonManagementRequest.Retire` over the private socket and waits for the exact peer to exit (`SemanticCoordinatorRetirement.kt:25-88`).
   On macOS, `withdrawSemanticLaunchdJob` also boots out the owned job and waits for launchd to report it absent (`SemanticLaunchdJob.kt:24-47`).
   On Linux, no `systemctl` call stops the unit. The exit of the main process ends the transient unit.

The Linux probe proves only that the coordinator and Host process identifiers are gone after `managed-retire` (`linux_coordinator_probe.py:100-120`).

**Retirement under Go.**

- Go keeps the outgoing version's Kotlin as the retirement owner. Go runs `V_old/share/kast/libexec/kast-management --internal-install managed-retire` as a child, while Go holds the lock exclusively.
  The child has a bounded deadline and a closed exit contract.
  The outgoing version directory is immutable and present until the switch, so the code that started the processes stops them.
- Kotlin's fence step becomes an assertion. The child proves that a transition is in progress when its shared-lock attempt fails, instead of writing `shutdown.json`.
- After the child exits with success, Go confirms supervisor absence:
  - On Linux, `systemctl --user is-active --quiet <label>.service` must report inactive or unknown.
  - On macOS, `launchctl list <label>` must report the job absent, for the semantic label.
  - Go computes the semantic label with the same pure function that Kotlin uses (decision D2 makes it stable).
- New starts are held off by the lock, not by a file. Every Kotlin start gate attempts the shared lock without waiting. A failed attempt reports today's `TRANSITION_IN_PROGRESS`.
  The 14 call sites of `InstallationLifecycleFence.observe` become callers of that lock attempt (`app-server/.../InstallationLifecycleFence.kt:19-29` and its callers in `InstalledBrokerServer.kt:92`, `MacOsPersistentBrokerServiceHost.kt:363`, `:399`, `SemanticCoordinatorPublication.kt:69`, `:151`, `InstalledCoordinator.kt:32`, `AppServerManagement.kt:139`, `SemanticCoordinatorRetirement.kt:26`, `:68`, `BrokerServiceStartLock.kt:50`, `:276`, `runtime/CoordinatorControl.kt:130`, `:191`, `:224`).
- The Codex broker job and login agent need a rule that does not exist today. Decision D1 settles it. I recommend that `managed-retire` also run the existing `BrokerServiceDeactivation` in `DISABLE` mode (`BrokerServiceDeactivation.kt:24-48`).
  The next Codex client launch republishes both through `Enable` (`InstalledCodexClientLauncher.kt:382`).

### 1.2 Kotlin path derivations from the installation directory

Every derivation starts from one of three roots:

- The JVM code location: `<installation>/lib/<jar>`, whose parent's parent is the installation (`cli/.../InstalledControlLocation.kt:24-45`).
- The executable: `kast.parent.parent` or `kast.toRealPath().parent.parent` (`AppServerManagement.kt:133`, `PersistentBrokerService.kt:263`, `KastServiceMain.kt:147`, `:232`).
- The management root: `HOME/.local/share/kast`, fixed by `ManagementRootResolution` (`distribution/cli/.../ManagementInstallation.kt:23-53`), then `root.resolve("installation")`.

| Family | Sites (`file:line`) | Moves to |
| --- | --- | --- |
| Client configuration file | `PublishedBrokerServiceCommand.kt:113`, `:135`; `AppServerStatus.kt:68`; `KastServiceMain.kt:147`; `ConfigurationInspection.kt:232`; `InstalledClientContext.kt:150`; `LifecycleProcesses.kt:346`; `PassiveRuntimeObservation.kt:253`; `PassiveRuntimeSocket.kt:33`; `WorkspaceSharingAdmission.kt:28`; `InstalledWorkspaceConfigurationIngress.kt:43` | `R/config/environment` |
| Workspace overlays | `InstalledWorkspaceConfigurationIngress.kt:78` | `R/config/workspaces/<64 hex>/environment` |
| Workspace registry | `AppServerManagement.kt:143`, `:192`; `AppServerStatus.kt:69`; `InstalledBrokerServer.kt:555`; `InstalledCoordinator.kt:195`; `runtime/CoordinatorControl.kt:83` | `R/config/workspaces.json` |
| Selected IDE | `ManagedHeadlessShutdown.kt:40`; `PassiveApplicationHost.kt:111` | `R/config/selected-ide.json` |
| Coordinator state root | `BrokerInstallationLayout.kt:14-17` (`state`, `state/broker/<16 hex>`, `state/run`); `MacOsPersistentBrokerServiceHost.kt:483`; `InstallationLifecycle.kt:49` | `R/state` |
| Installation epoch | `BrokerInstallationState.kt:38`, `:44`, `:103`, `:120`; `PassiveRuntimeObservation.kt:153`, `:252` | `R/state/epoch.json` |
| Sockets | `BrokerServiceStartLock.kt:86`, `:250`; `SemanticCoordinatorPublication.kt:196`, `:263`; `PassiveRuntimeSocket.kt:70`; `PublicFrontendCatalog.kt:39` (Go dials it beneath the installation, `catalog.go:163`) | `R/state/run/c.sock`; the catalog's `socket` becomes relative to `R` |
| Readiness record | `InstalledSemanticCoordinator.kt:23-26`; `PassiveRuntimeObservation.kt:188` | `R/state/broker/<16 hex>/service-readiness.json` |
| Invocation directories | `OneShotInvocationRecord.kt:85-88`; `OneShotObservation.kt:56`; `PriorInstallationPreparation.kt:14` | Removed if decision D4 adopts the lock; otherwise `R/state/run/{one-shot,tool-sessions}` |
| Legacy worker state | `runtime/CoordinatorControl.kt:221` | Delete with the switch |
| Semantic identity and label | `SemanticServiceLaunch.kt:43`, `:48`, `:60`, `:116-122`; `SemanticLaunchdJob.kt:14`, `:21`; `LinuxPersistentBrokerServiceHost.kt:52`, `:69` | Derive from `R`, not from `V` (decision D2) |
| Broker daemon program | `PersistentBrokerService.kt:262-263` | Stays inside `V`; covered by retirement (decision D1) |
| Management receipt | `ManagementReceiptAdmission.kt:40`; `ProductManagementReceipt.kt:49-67`; Go `control.go:99-122` | Deleted. Go reads the runtime digest from `V/share/kast/build-record.json` |
| Product selection and runtime root | `InstalledProductRuntime.kt:124-185`, `:173`, `:205-230`; `InstallationLifecycle.kt:201`; `ManagedWorkspaceQuery.kt:87` | Kotlin keeps a read-only derivation of the runtime root (decision D3) |
| Payload manifest | `BundledManifest.kt:46`, `:59`; `InstallationLifecycle.kt:170-175`, `:200-209`, `:234-247`; `ContainerManagement.kt:55-58` | Kotlin stops reading it. Go verifies `V` before it can run |
| Store siblings | `ManagedWorkspaceLifecycle.kt:102`; `ManagedWorkspaceRemoval.kt:20`; `ManagedWorkspaceRetirement.kt:12`; `ManagedWorkspaceStatus.kt:43`; `WorkspaceSharingInspection.kt:48`; `InstalledProductRuntime.kt:173` | Unchanged: `S/kast-workspaces`, `S/kast-runtime-bases` |
| Lifecycle records in Kotlin | `ManagementFileLock.kt:10-15`; `UninstallManagedRoot.kt:29`, `:139-147`; `ForceResetFence.kt:107`, `:237`; `RecordedRecoveryInstaller.kt:83-126`; `ManagementUninstall.kt:56-230` | Deleted (stage 6) |

Workspace container paths such as `ContainerStore.kt:122` resolve beneath the container profile, not the installation, so they do not move.
The IDE plugin keeps its durable state under `~/.kast` (`runtime/hosted/.../HostedChangeResources.kt:32`), which is independent of `R`.

**One owner.** `admitJvmControlRoot` already turns the code location into the installation path (`InstalledControlLocation.kt:36-45`).
The design extends it into one Kotlin value class:

```kotlin
/** The only Kotlin derivation of installation paths. */
class InstallationLayout private constructor(val root: Path, val version: Path) {
    val config: Path get() = root.resolve("config")
    val state: Path get() = root.resolve("state")
    companion object {
        /** code source -> lib -> V; V.parent is R/versions; R is HOME/.local/share/kast. */
        fun admit(codeSource: Path, home: Path): Refinement<InstallationLayout, InstallationLayoutFailure>
    }
}
```

The Gradle start scripts `kast-service`, `kast-daemon`, `kast-mcp` and `kast-codex` start the JVM without the Go frontend (`cli/build.gradle.kts:122-160`).
So Kotlin cannot rely on a system property from Go; the code location is the one input that every entry has.

**Same release.** Yes, in two steps.
Stage 3 replaces every site in the table with `InstallationLayout` while it still resolves the old layout, so it changes no behavior.
Stage 5 changes the body of `InstallationLayout.admit` and ships with the Go switch.
An architecture rule rejects new `resolve("config` and `resolve("state` calls outside the owner from stage 3 onward.
Without it, a site added between stages 3 and 5 escapes the flip.

### 1.3 Release delivery

**Today.**

- `install.sh` is generated (`packaging/internal/bootstrap/bootstrap.sh.in`). Users fetch it from `main` (`docs/public/reference/installation.mdx:29`).
- It pins, per target, a Python archive URL and digest (`install.sh:35-41`), the `defusedxml` wheel digest (`:62-63`), and an embedded engine digest (`:66-73`). Then it runs the engine (`:76-78`).
- The engine resolves the selection over HTTPS from a catalog (`managed_release.py:39-41`, `:1076-1215`).
  It checks the build record against the digest from the catalog or `release-mapping.json` (`:1795-1867`), then checks each asset against the record (`managed_product.py:355-377`).
- The Control executable already has a qualified digest. Staging rejects a Control payload whose `share/kast/libexec/kast-management` differs from `qualification.controlSha256` (`managed_product.py:459-464`).
- Publication uploads `record.assets()`, the acceptance assets, and `build-record.json` (`.github/scripts/release/publish_developer.py:295-312`).
  Per target, these are the composed setup bundle, the runtime archive, the qualification receipt, and the optional runtime base (`managed_release.py:542-554`). No asset is a standalone Go binary.
- Stable releases promote retained preview bytes without a build (`.github/workflows/release.yml:30-55`).
  Targets are `linux-x86_64` on `ubuntu-24.04` and `macos-aarch64` on `macos-15` (`.github/workflows/composed-linux.yml:68-69`).

**New assets.** Each release target gains one top-level asset: `kast-v<version>-<target>`, the static, `cgo`-free Go binary.
Its bytes equal the `kast-management` inside that target's Control payload.
The build record schema gains a required `control` asset per target, with `control.sha256 == qualification.controlSha256`.
The product gate and `publish_developer.py` reject a record that breaks that equality.
Stable promotion copies the asset with the others, so it needs no build.

**Pinning.** `install.sh` cannot pin a release asset's digest, because it comes from `main` and selects a release at run time.
I recommend a separate bootstrap binary (decision D5):

- A `bootstrap-<12 hex>` release holds `kast-bootstrap-<target>` for both targets. The suffix is the first 12 hexadecimal characters of the source tree digest.
- `install.sh` pins each target's URL and SHA-256, as it pins Python today. The generator computes those digests from a reproducible `go build -trimpath -buildvcs=false` with the `go.mod` toolchain. CI rebuilds them and compares.
- `install.sh` downloads the binary, verifies it, and runs `kast-bootstrap install <selector>`.
- The bootstrap binary verifies the selected record and every asset, including the release's own `kast-v<version>-<target>`. That second binary becomes `V/share/kast/libexec/kast-management`.
- The digest chain becomes: `install.sh` pins, then the bootstrap binary, then the catalog or mapping record digest over HTTPS, then the asset digests in the record.
  The catalog link is HTTPS-only today too. This design neither strengthens nor weakens it.

### 1.4 Existing installations

There is no migration. Go classifies `R` and the home paths into one closed observation before any effect:

```go
type LayoutObservation uint8

const (
	LayoutAbsent     LayoutObservation = iota + 1 // no R, no store, no public command
	LayoutCurrent                                 // R/current names an existing V
	LayoutIncomplete                              // R/versions exists; R/current is absent or dangling
	LayoutLegacy                                  // R/installation, R/management.json, R/recovery or R/shutdown.json exists
	LayoutForeign                                 // R exists and matches none of the above
)
```

A legacy layout has any of `R/installation`, `R/management.json`, `R/recovery`, `R/shutdown.json`, `R/management.lock` or `R/activation.lock`.
The sibling records `S/.kast-uninstall-*.json`, `S/.kast-reset-*.json` and `S/.kast-install-*` also mark it (inventory, section 1).

On `LayoutLegacy`, `kast install` does the following, and nothing else:

1. Reject unless the user passed `--reset-legacy`. The failure names the check, `LEGACY_LAYOUT`, and states the destroyed data: client configuration, workspace containers, and exact-root trust.
2. With `--reset-legacy`, run the legacy product's own forced reset: `R/installation/share/kast/libexec/kast-management uninstall --force --json`. It owns every legacy record and process (`KastManagementMain.kt:187-200`, `ForceInstallationReset.kt`).
   Go reads only its exit status and the typed `ForceResetExit`.
3. Require `LayoutAbsent` afterward. Any remaining legacy path rejects with `LEGACY_RESET_INCOMPLETE` and its path relative to `S`.
4. Continue as a fresh install.

If `R/installation` is missing or its executable is not runnable, Go rejects with `LEGACY_RESET_UNAVAILABLE`.
The user then runs the legacy `install.sh` once with `uninstall --force`. Go never deletes legacy paths itself, because their ownership proofs live in the legacy records (requirement 6).

The old `kast upgrade` must not install a new release into the old layout.
The new Control payload drops the `share/kast/managed-install-v1` marker.
The old engine then rejects it with `INSTALLER_CONTRACT_UNSUPPORTED` (`managed_release.py:1538-1540`).
The Control release notes and that rejection direct the user to `install.sh`.

### 1.5 Commands: which move into Go

| Command | Owner after the switch | Reason |
| --- | --- | --- |
| `install.sh` engine | Go `kast-bootstrap install` | Replaces the private Python interpreter and engine |
| `kast upgrade [--channel/--version/--commit] [--repair] [--dry-run] [--json]` | Go | Writes `R/current`, `V` and the stores |
| `kast uninstall` | Go | Removes `R`, the public command, and the stores |
| `kast uninstall --force` | Go entry, Kotlin process discovery (decision D6) | Forced reset of unknown state |
| `kast versions [--json]` | Go | Shares the one catalog decoder with `upgrade`; deletes `BuildCatalog.kt`, `ReleaseDocumentSource.kt`, `ManagementBuilds.kt` |
| `kast status [--json]` | Kotlin, with Go facts | Runtime status stays in Kotlin. Installation identity comes from `V`'s records through Go (section 2.4) |
| `kast stop` | Kotlin | Stops Hosts; the next request can restart them. It changes no structure, so it takes the lock shared |
| `kast config` | Kotlin | Writes `R/config` under the shared lock |
| `kast install skill` | Kotlin | Writes outside `R` |
| `--internal-install managed-retire` | Kotlin, run by Go | Retirement runs the outgoing version's code |
| `--internal-install reset`, `preflight-reset` | Deleted | Only verification harnesses call them (inventory, section 7) |
| IDE plugin | Unchanged | It reads `~/.kast` and the environment Control passes. Lifecycle never stops a user's desktop IDE |

The frontend guide forbids lowering flags in Go (`frontend/AGENTS.md`, Rules).
Stage 1 amends that rule. Go parses exactly the lifecycle commands above and forwards every other vector unchanged.

## 2. Layout and records

### 2.1 Paths

```text
S/kast.lock                         lock file (one primitive, two modes)
S/kast/                             R, mode 0700
  current -> versions/<id>          the single pointer (relative symlink)
  versions/<id>/                    V, read-only after its rename
    bin/, lib/, share/kast/...      Control payload
    share/kast/installation.json    payload manifest
    share/kast/product-selection.json
    share/kast/build-record.json    release bytes, unchanged
  config/                           Kotlin-owned client configuration
  state/                            Kotlin-owned coordinator state
  .staging-<16 hex>/                at most one per running command
S/.kast-removing-<16 hex>/          a tree being deleted
S/kast-workspaces/                  existing store: runtimes/, containers/, locks/
S/kast-runtime-bases/               existing store
~/.local/bin/kast -> S/kast/current/share/kast/libexec/kast-management
```

The lock file lives beside `R`, not in it.
Uninstall renames `R`. With the lock inside `R`, a later process can create a fresh `R` and lock a different file.
Every opener verifies that the locked descriptor and the path name the same inode, and retries once otherwise (section 4.1).

The public command becomes a symbolic link. Its target string is fixed, so upgrade never rewrites it.
`locateInstallation` already resolves links and strips the payload path (`control.go:77-95`). It then yields the physical `V`.

### 2.2 Version identity

```go
// VersionID names a version directory: the build record digest plus one of two slots.
// Repair of the current record installs into the other slot, so no rename ever replaces the directory that current names.
type VersionID struct {
	Record Digest // SHA-256 of build-record.json
	Slot   Slot
}

type Slot uint8

const (
	SlotA Slot = iota + 1 // directory name "<64 hex>"
	SlotB                 // directory name "<64 hex>-b"
)
```

A name that is not one of these two forms is foreign content.
Upgrade to a different record uses `SlotA`. Repair of the current record uses the slot that `current` does not name.

### 2.3 Record schemas

Each record has an explicit `schemaVersion` that the decoder reads first.
Decoders reject unknown fields, duplicate keys, trailing data, and unknown enum values.
Encoders write every field, including defaults.

```go
type Digest [32]byte // encodes as 64 lowercase hex characters

type Target uint8 // closed: "macos-aarch64", "linux-x86_64"

// V/share/kast/installation.json: payload manifest.
type PayloadManifest struct {
	SchemaVersion int           `json:"schemaVersion"` // 1
	Version       VersionID     `json:"version"`       // binds the manifest to its own directory name
	Files         []PayloadFile `json:"files"`         // sorted by Path; every regular file in V except this one
	Links         []PayloadLink `json:"links"`        // sorted; relative targets that stay inside V
}

type PayloadFile struct {
	Path   RelativePath `json:"path"`   // filepath.IsLocal, no "..", at most 512 bytes
	SHA256 Digest       `json:"sha256"`
	Bytes  int64        `json:"bytes"`
	Mode   FileMode     `json:"mode"` // closed: "0444" or "0555"
}

type PayloadLink struct {
	Path   RelativePath `json:"path"`
	Target RelativePath `json:"target"`
}

// V/share/kast/product-selection.json: why this version was selected.
type ProductSelection struct {
	SchemaVersion  int       `json:"schemaVersion"` // 1
	Target         Target    `json:"target"`
	RecordSHA256   Digest    `json:"recordSha256"`
	ReleaseTag     string    `json:"releaseTag"`    // "developer-v<version>" or "v<semver>"
	Policy         Policy    `json:"policy"`        // closed: "TRACK_STABLE", "TRACK_MAIN", "PINNED_BUILD", "LOCAL"
	PublicVersion  *SemVer   `json:"publicVersion"` // explicit null unless a stable release
	SourceRevision Revision  `json:"sourceRevision"`
}

// V/share/kast/build-record.json: the release's bytes, decoded into the existing product record schema.
// Its SHA-256 must equal ProductSelection.RecordSHA256 and VersionID.Record.
type BuildRecord struct {
	SchemaVersion  int             `json:"schemaVersion"` // the release schema after stage 4
	Repository     string          `json:"repository"`
	SourceRevision Revision        `json:"sourceRevision"`
	SourceTree     Revision        `json:"sourceTree"`
	Version        string          `json:"version"`
	Producer       Producer        `json:"producer"` // sealed: workflow run or LOCAL
	Targets        []ProductTarget `json:"targets"`  // includes the new Control asset per target
}
```

The `R/current` link has a closed shape instead of a JSON schema. Its target is exactly `versions/<64 hex>` or `versions/<64 hex>-b`.

The failure document (section 5) is the only other Go-encoded JSON contract. Status output from `--json` commands is a closed outcome union with the same version-first rule.

### 2.4 Writers and readers

| Record | Single writer | Readers |
| --- | --- | --- |
| `S/kast.lock` | Go creates and unlinks it. Every participant locks it | Go lifecycle (exclusive and shared); Go frontend (shared, decision D4); Kotlin start gates, `kast config`, `kast stop` (shared, never waiting) |
| `R/current` | Go lifecycle, exclusive mode only | Go lifecycle; Go frontend, through the public link |
| `V/share/kast/installation.json` | Go lifecycle, before the rename into `versions/` | Go lifecycle (repair and status checks) |
| `V/share/kast/product-selection.json` | Go lifecycle, before the rename | Go lifecycle (repair re-selection, `upgrade` no-op check), Go status facts |
| `V/share/kast/build-record.json` | Go lifecycle copies the verified release bytes | Go lifecycle; Go frontend (runtime root); Kotlin runtime-root derivation (decision D3) |
| `S/kast-workspaces/runtimes/<sha>`, `S/kast-runtime-bases/<sha>` | Go lifecycle, through stage and no-replace rename | Go lifecycle; Kotlin workspace generations (`ContainerModel.kt:51-56`) |
| `R/config/**`, `R/state/**` | Kotlin, unchanged ownership | Kotlin. Go only deletes them with `R` |
| `~/.local/bin/kast` | Go lifecycle | The shell; Go frontend |

Go passes installation facts to `kast status` as JVM system properties set by the frontend: version identifier, record digest, and policy.
Kotlin presents them and reads no lifecycle record.
Start scripts that bypass the frontend never run `status`.

Deleted records:

- `management.json`, `shutdown.json`, `management.lock` and `activation.lock`.
- The replacement journal and its preimages, and the uninstall journal.
- The retained execution bundle, the recovery bundle, and the captured selection file.
- The reset fence, the capability markers, and the private interpreter.

## 3. Operations as state machines

### 3.1 Common prelude

Every lifecycle command runs this prelude. Each step is a pure decision over an observation, followed by at most one effect.

| Step | Effect | Lock |
| --- | --- | --- |
| P1 | Admit `HOME` (absolute, normalized, owned by the user, mode without group or other write) and the platform | none |
| P2 | Open `S/kast.lock` with `O_CREAT\|O_NOFOLLOW\|O_CLOEXEC`, mode 0600, and take the command's lock mode without waiting | taken |
| P3 | Observe the layout (section 1.4) | held |
| P4 | In exclusive mode only, delete leftovers: `R/.staging-*`, `S/.kast-removing-*`, store reservations `.kast-runtime-*` and `.kast-base-*`, and every `V` that `current` does not name | held |

P4 is the recovery rule. A leftover is deleted only if its name matches a Kast pattern, the user owns it, and it is not a symbolic link.
Each tree is first renamed to `S/.kast-removing-<random>`, then deleted. So an interrupted deletion leaves only one recognizable name.
A shared holder can be a command that is still staging, which is why P4 needs exclusive mode.

### 3.2 Install

Precondition: `LayoutAbsent`, `LayoutIncomplete`, or `LayoutCurrent` (which makes install idempotent).

| Step | Action | Lock | Power loss leaves |
| --- | --- | --- | --- |
| I1 | Prelude, shared mode. On `LayoutLegacy`, follow section 1.4. On `LayoutForeign`, reject | shared | nothing new: **old** |
| I2 | Check home paths (V8): `~/.local/bin/kast` is absent or the exact owned link. `S/kast-workspaces/containers` is absent or empty on a first install | shared | **old** |
| I3 | Resolve the selection and download the record into memory. Verify its digest (V1) | shared | **old** |
| I4 | Publish missing runtime and base entries: stage `.kast-runtime-<random>`, verify, `fsync`, then rename it to `runtimes/<sha>` without replacement. An existing entry is verified (V2) | shared | **old**; a reservation that P4 deletes; or a complete entry that later commands reuse |
| I5 | Stage `R/.staging-<random>`. Extract Control and verify its digest against the record (V1). Train the class-data archive with `java` from the runtime entry. Write the three records, make files read-only, and `fsync` the tree | shared | **old** with a staging tree: **completed by rerunning** |
| I6 | Rename the staging tree to `R/versions/<id>` without replacement; `fsync R/versions` | shared | `LayoutIncomplete`: **completed by rerunning** `install.sh` (P4 deletes the unnamed `V`) |
| I7 | Convert the lock to exclusive, without waiting | exclusive | as I6 |
| I8 | Create `R/current.next`, then rename it over `R/current`; `fsync R` | exclusive | **new**, without the public command: **completed by rerunning** `install.sh` |
| I9 | Create `~/.local/bin/kast.next-<random>` and rename it to `~/.local/bin/kast`. Renaming without replacement applies when the link is absent. Plain rename applies only over the exact owned link. `fsync ~/.local/bin` | exclusive | **new** |
| I10 | P4 cleanup; release the lock | released | **new** |

Install never starts a Host or the coordinator. The first explicit semantic request does that, as today (`packaging/AGENTS.md`, Navigation Hints).

### 3.3 Upgrade and repair

Precondition: `LayoutCurrent`. Upgrade with an unchanged record and no `--repair` returns `ALREADY_CURRENT` after U3.

| Step | Action | Lock | Power loss leaves |
| --- | --- | --- | --- |
| U1 | Prelude, shared mode | shared | **old** |
| U2 | Verify the current `V` against its manifest (T3). Damage is reported, and only `--repair` proceeds | shared | **old** |
| U3 | Resolve and verify the record (V1). Decide the target `VersionID` | shared | **old** |
| U4 | As I4. Under `--repair`, verify every referenced entry. Stage a replacement for each damaged one, but do not rename it yet | shared | **old** with reservations that P4 deletes |
| U5 | As I5 and I6 for the target `V` | shared | **old** with an unnamed `V`: **completed by rerunning** |
| U6 | Convert the lock to exclusive without waiting. On failure, reject with `LOCK_HELD` and holder evidence | exclusive | as U5 |
| U7 | Run the outgoing `managed-retire` child (T8). Confirm supervisor absence | exclusive | **old** with stopped processes. They restart on demand from the old `V`: **old** |
| U8 | Repair only: rename each damaged entry to `.kast-removing-*` and rename its staged replacement into place | exclusive | **old** with a missing or repaired runtime entry: **completed by rerunning** `kast upgrade --repair` |
| U9 | Swap `current` as in I8 | exclusive | **new** |
| U10 | P4 cleanup deletes the outgoing `V`; release the lock | released | **new**; a leftover `V` that the next command deletes |

The `kast` command in `R/current` stays runnable through every step. So every "completed by rerunning" outcome names a command the user can still run.
A missing runtime entry after U8 breaks semantic queries, but not the Go binary or `upgrade --repair`.

### 3.4 Uninstall

Precondition: `LayoutCurrent` or `LayoutIncomplete`.

| Step | Action | Lock | Power loss leaves |
| --- | --- | --- | --- |
| X1 | Prelude, exclusive mode (P4 runs) | exclusive | **old** (installed) |
| X2 | Retire through the current `V` (U7). With `LayoutIncomplete`, no process can run from an unnamed `V`, so retirement is skipped | exclusive | installed, processes stopped: **completed by rerunning** `kast uninstall` |
| X3 | Take the workspace store's lifetime lock exclusively (`ContainerQueryLease.kt:65-77`, `:186-204`). Rename `S/kast-workspaces` and `S/kast-runtime-bases` to `S/.kast-removing-*` | exclusive | installed, without stores: **completed by rerunning** `kast uninstall` |
| X4 | Remove `~/.local/bin/kast` only if it is a link whose target string is the exact owned target (V8) | exclusive | `R` intact, no public command: **completed by rerunning** `install.sh`, which restores the link, then `kast uninstall` |
| X5 | Rename `R` to `S/.kast-removing-<random>`; `fsync S` | exclusive | **new** (uninstalled) with inert leftovers that the next `install.sh` deletes |
| X6 | Delete every `S/.kast-removing-*`. Unlink `S/kast.lock` while still holding it, then close it | released | **new** |

The semantic property list goes with `R/state` in X5 and X6. Decision D1 covers the login agent outside `R`.
The workspace store keeps its own lifetime lock. That lock uses the same `fcntl` primitive (`ContainerQueryLease.kt:186-204`), so there is still one primitive.

### 3.5 Forced reset

`kast uninstall --force` is a separate tool for state that the prelude classifies as `LayoutForeign`, or for a failed retirement.
It takes the lock exclusively, so a crash releases it and leaves no fence.
Decision D6 settles whether its process discovery stays in Kotlin.

## 4. The lock

### 4.1 Primitive

- POSIX record locks through `fcntl(F_SETLK)` on the whole file, from `golang.org/x/sys/unix`.
  `FileChannel.tryLock` in the JVM uses the same family on both targets (`ManagementFileLock.kt:23-24`, `ContainerQueryLease.kt:204`). #1371 aligned Python to it.
- Record locks belong to the process. Closing any descriptor for the file releases them, so each process opens the file exactly once.
- After locking, the holder compares `fstat` of the descriptor with `lstat` of the path. On a mismatch, it closes the descriptor and retries once, then rejects with `LOCK_REPLACED`.
  So uninstall can remove the file at its end without letting two holders lock different files.
- Converting from shared to exclusive is not atomic. A failed conversion keeps the shared lock and reports `LOCK_HELD` with the holder's process identifier from `F_GETLK`.

### 4.2 Modes

| Mode | Holders | Duration |
| --- | --- | --- |
| Shared | Go install, upgrade and repair through staging; Kotlin coordinator and Host start gates; `kast config`; `kast stop`; one-shot and session JVM entries (decision D4) | The decision or the invocation, never a coordinator's lifetime |
| Exclusive | Go retirement, switch, cleanup, uninstall, forced reset | From conversion or acquisition to release |

The coordinator (`daemon` entry), `kast-service` and the broker never hold the lock for their lifetime.
A lifetime holder blocks every upgrade, because retirement needs the exclusive mode first.
Retirement children only observe it: a failed shared attempt is their proof that a transition is in progress.

## 5. The failure type

```go
// Failure is the only rejection a lifecycle command returns. Code names exactly one check.
type Failure struct {
	SchemaVersion int      `json:"schemaVersion"` // 1
	Code          Code     `json:"code"`
	Stage         Stage    `json:"stage"`
	Evidence      Evidence `json:"evidence"`
}

type Stage uint8 // closed: PRELUDE, SELECTION, DOWNLOAD, VERIFY, STAGE, PUBLISH, RETIRE, SWITCH, CLEANUP, REMOVE

// Evidence is bounded: no payload bytes, no environment values, no absolute path.
type Evidence struct {
	Root      *RootKind     `json:"root"`      // R, RUNTIME_STORE, BASE_STORE, PUBLIC_COMMAND, HOME_SHARE
	Path      *RelativePath `json:"path"`      // relative to Root, at most 512 bytes
	Asset     *AssetName    `json:"asset"`     // a record-declared asset name
	Expected  *Digest       `json:"expected"`
	Observed  *Digest       `json:"observed"`
	Transfer  *Transfer     `json:"transfer"`  // closed: TLS, DNS, CONNECT, TIMEOUT, HTTP_STATUS, SIZE_LIMIT, REDIRECT
	HTTP      *int          `json:"http"`      // status only
	Holder    *int          `json:"holder"`    // process identifier from F_GETLK
	ExitCode  *int          `json:"exitCode"`
	Supervisor *SupervisorKind `json:"supervisor"` // LAUNCHD, SYSTEMD
}
```

Absent evidence fields encode as explicit `null`. The decoder used by tests and by `kast status` rejects unknown codes.

| Code | Check | Inventory group |
| --- | --- | --- |
| `SELECTOR_INVALID`, `SELECTORS_CONFLICT` | Exactly one valid selector | V1 |
| `PLATFORM_UNSUPPORTED` | `macos-aarch64` or `linux-x86_64` | V1 |
| `CATALOG_UNAVAILABLE`, `CATALOG_INVALID`, `COMMIT_AMBIGUOUS`, `BUILD_UNAVAILABLE` | Catalog and mapping shapes and selection | V1 |
| `RECORD_DIGEST_MISMATCH`, `RECORD_INVALID` | Record bytes and fields; the (a) value checks of T6 (digest form, sizes, names) | V1, T6 (a) |
| `ASSET_UNAVAILABLE`, `ASSET_SIZE_MISMATCH`, `ASSET_DIGEST_MISMATCH` | Downloaded bytes against the record | V1 |
| `QUALIFICATION_INVALID` | Receipt outcome, revision and bundle digest | V1 |
| `ARCHIVE_ENTRY_REJECTED` | Archive entries are local, unique and of admitted types | V1 |
| `BUNDLE_INVENTORY_INVALID` | Composed inventory and identities | V1 |
| `CONTROL_DIGEST_MISMATCH` | Control executable equals `controlSha256` | V1 |
| `LOCAL_ARTIFACT_REQUIRED` | A local record is never fetched remotely | V1 |
| `RUNTIME_ENTRY_DAMAGED`, `BASE_ENTRY_DAMAGED` | Published store entries are unchanged | V2, T9 (a) |
| `PAYLOAD_MANIFEST_INVALID`, `PAYLOAD_FILE_DAMAGED` | `V` matches its manifest | T3 |
| `HOME_INVALID` | `HOME` admission | V8 |
| `ROOT_FOREIGN` | `R` matches a known layout | V8 |
| `LEGACY_LAYOUT`, `LEGACY_RESET_UNAVAILABLE`, `LEGACY_RESET_INCOMPLETE` | Existing installations (section 1.4) | new |
| `PUBLIC_COMMAND_FOREIGN` | `~/.local/bin/kast` is absent or the owned link | V8 |
| `WORKSPACE_STORE_OCCUPIED` | No containers before a first install | V8 |
| `LEFTOVER_FOREIGN` | A cleanup candidate is the user's, not a link, and matches a Kast pattern | V8 |
| `LOCK_HELD`, `LOCK_REPLACED`, `LOCK_UNAVAILABLE` | The lifecycle lock; a shared holder is also an unsettled invocation | T10 (a), V10 |
| `RETIREMENT_REJECTED`, `RETIREMENT_TIMED_OUT` | Child exit and deadline | T8 (a) |
| `SUPERVISOR_STILL_ACTIVE` | No unit or job remains after retirement | T8 (a) |
| `FILESYSTEM_REJECTED` | An effect failed. `Stage` and `Path` name it | T9 (a) |
| `ALREADY_CURRENT` | Not a failure: a closed outcome of `upgrade` | none |

V10 survives as the lock check under decision D4.
Without D4, a separate `INVOCATION_UNSETTLED` code keeps the current directory check (`product_state_removal.py:74-104`).
The V1 interpreter checks (`private_interpreter.py:67-98`) have no successor, because no interpreter remains.

## 6. Go package boundaries

```text
frontend/
  cmd/kast/main.go                 process wiring only
  internal/frontend/               existing query and JVM routes; reads the version records for the runtime root
  internal/lifecycle/
    failure/                       Code, Stage, Evidence; encoding and strict decoding        (pure)
    record/                        section 2.3 types and strict decoders                      (pure)
    layout/                        LayoutObservation from an observed tree; VersionID names   (pure)
    selection/                     catalog and mapping decoders; selector resolution          (pure)
    plan/                          each state machine as Next(state, observation) -> (effect, state) (pure)
    run/                           the interpreter: performs one effect, observes, feeds plan
    effect/fs/                     lock, openat-relative reads, no-replace rename, link swap, fsync, owned deletion
    effect/archive/                tar.gz and zip extraction through effect/fs, with entry admission
    effect/net/                    HTTPS GET with size limit, timeout, HTTPS-only redirects
    effect/process/                child execution with deadline, closed exit observation
    effect/supervisor/             launchctl list and print; systemctl --user is-active
```

- The pure packages import no `os`, `net`, `os/exec` or `time` package. An architecture test in `frontend/check gate` enforces that with `go list -deps`.
- `run` receives each adapter as an interface value, constructed in `main.go` from explicit configuration. There is no global state and no environment lookup below `main.go`.
- No-replace rename uses `renameat2(RENAME_NOREPLACE)` on Linux and `renamex_np(RENAME_EXCL)` on macOS. Both come from `golang.org/x/sys/unix`, which keeps the build free of `cgo`.
- The interpreter emits one bounded stage event per step to stderr: step name, outcome, and elapsed milliseconds. That event is durable instrumentation (Engineering Dictum 9), and the interruption tests use it.

## 7. Tests at the narrowest boundary

| Boundary | What it proves | Inputs |
| --- | --- | --- |
| `failure`, `record`, `selection` | Encoded shape against checked-in expected JSON for each closed variant, explicit nulls and defaults, and rejection of unknown fields, codes and versions | Literal values; no filesystem |
| `layout` | Every `LayoutObservation` from a described tree, including foreign and legacy names | In-memory tree descriptions |
| `plan` | Each state machine step: the effect it chooses for each observation, and the power-loss outcome after each effect | Pure tables; the expected outcome comes from section 3, not from the implementation |
| `effect/fs` | Lock modes, conversion failure, inode verification, no-replace rename, link swap, owned deletion refusing links and foreign owners | A private `t.TempDir()` |
| `effect/fs` interoperability | A Java `FileChannel.tryLock` holder excludes Go, and a Go holder excludes Java, in both modes | A real JVM child, as `ManagementFileLockTest` does with Python |
| `effect/archive` | Rejection of absolute paths, `..`, duplicates, links that escape, and oversize entries | Archives built in the test |
| `effect/net` | Size limit, timeout, HTTPS-only redirects, status mapping | `httptest.NewTLSServer` |
| `effect/process`, `effect/supervisor` | Exact argument vectors and exit mapping | A scripted executor that rejects unexpected, excess or unconsumed calls |
| `run`, interruption | For each step boundary, kill the process with `SIGKILL` and rerun the same command. The result is old, new, or finished by the rerun, as section 3 states | The built binary, a `--local-artifact` fixture product with a stub outgoing `managed-retire`, a private `HOME` |
| `run`, concurrency | Two commands at once: one wins, the other rejects with `LOCK_HELD` and holder evidence | The built binary, two children |

The interruption harness reads the stage events and sends `SIGKILL` when a chosen step starts. No product test flag exists.
`SIGKILL` proves process interruption, not power loss.
Durability against power loss rests on review of the `fsync` placement in section 3 and is not proven by these tests.
A Linux fault-injection device test can prove more. This plan does not include one.

## 8. Installed acceptance on both targets

The existing native harness runs on `ubuntu-24.04` for `linux-x86_64` and `macos-15` for `macos-aarch64` (`composed-linux.yml:68-69`).
Its cases already include fresh install, upgrade to a different build, and uninstall (`packaging/internal/verification/product_acceptance.py:15-30`).
The switch adds or changes these cases on both targets:

1. **Install** through the generated `install.sh` with the pinned bootstrap binary, then a first semantic query.
2. **Upgrade** to a different build while a coordinator and a Host run. The case asserts that the old process identifiers exited and the supervisor entry is absent: `systemctl --user is-active` on Linux, `launchctl list` on macOS. A query then succeeds from the new `V`.
3. **Repair** after a damaged runtime file, then a query.
4. **Uninstall**: no `R`, no stores, no `S/.kast-removing-*`, no lock file, no public command, no supervisor entry, and no owned login agent on macOS.
5. **Interruption** at each step boundary of install, upgrade and uninstall, with the rerun from section 3.
6. **Busy refusal**: an open MCP session makes `upgrade` reject with `LOCK_HELD` (decision D4).
7. **Legacy reset**: install a legacy baseline product, then run the new `install.sh` without and with `--reset-legacy`.
8. **Old upgrade refusal**: the legacy `kast upgrade` rejects the new release with `INSTALLER_CONTRACT_UNSUPPORTED`.

The Linux coordinator probe changes its unit identity input from `root / "installation"` to `R` (`linux_coordinator_probe.py:164-165`, decision D2).

## 9. Staged implementation plan

Each stage is one pull request with its own tests. Stages 1 to 4 change no installed behavior.

1. **Go lifecycle core.** Add `failure`, `record`, `selection`, `layout` and `plan` with their pure tests, plus the dependency-boundary check. Amend the frontend guide's flag rule for lifecycle commands only. Nothing is wired.
2. **Go effect adapters.** Add `effect/*` with tests at their boundaries, including the Java lock interoperability test.
3. **Kotlin layout owner.** Introduce `InstallationLayout` and route every site from section 1.2 through it, still resolving the current layout. Add the architecture rule against new installation-relative `config` and `state` derivations. Behavior is unchanged, and existing tests prove it.
4. **Release assets.** Publish `kast-v<version>-<target>` and add the required `control` asset to the record, with the product gate equality check. Update the Python decoder in the same pull request, because it rejects unknown target fields (`managed_release.py:608-617`). Add the bootstrap release workflow and its reproducibility check.
5. **Go lifecycle end to end, unwired.** Add `run`, the `install`, `upgrade`, `uninstall` and `versions` commands, and the interruption and concurrency tests against fixture products. `install.sh` still uses Python.
6. **Switch.** These changes land in one pull request:
   - Flip `InstallationLayout` to `R/config` and `R/state`, and derive the semantic identity from `R`.
   - Replace fence observation with the shared lock attempt, and adapt `managed-retire`.
   - Wire the Go commands. Regenerate `install.sh` to pin and run the bootstrap binary.
   - Drop `managed-install-v1` and delete `management.json` reading in Go.
   - Run installed acceptance on both targets.
7. **Deletion.** Delete `packaging/internal/{installation,product}`, `packaging/internal/bootstrap/{bootstrap_entry.py,private_interpreter.py,install-candidate.sh}`, and the engine embedding in `generate_bootstrap.py`.
   - Kotlin: `ManagedUpgrade.kt`, `ManagementUninstall.kt`, `UninstallManagedRoot.kt`, `UninstallRetirementJournal.kt`, `ProductRemovalJournal.kt`, `RecordedRecoveryInstaller.kt`, `InstallationReplacementReceipt.kt`, `ManagementReceiptAdmission.kt`, `ProductManagementReceipt.kt`, `BundledManifest.kt`, `InstallationRecoveryRemoval.kt`, `ManagedRecoveryRemovalProof.kt`, `PriorInstallationPreparation.kt`, `ManagementFileLock.kt`, the shutdown fence contract, and the catalog files in section 1.5.
   - Gradle tasks that exercise them: `installationRecoveryTest`, `installerRemovalTest`, `installationSystemPythonTest`, `localInstallationTest`, and their `productBuildGate` entries (`build.gradle.kts:637-652`).
   - Contributor assembly in `local_product_install.py` moves to Go `install --local-artifact` before its directory is deleted.

Every green push to `main` publishes a preview (`.github/workflows/developer-release.yml:36-44`).
So stages 6 and 7 merge back to back as a stack, before the next stable promotion.
A preview built between them is functionally the new product with dead code.

## 10. Risks

- **One large switch.** Stage 6 changes Kotlin paths, start gates, retirement, the installer and `install.sh` together. Stages 3 and 5 move most of the volume earlier. Installed acceptance on both targets is the gate.
- **Lost user state at the switch.** The legacy reset destroys client configuration, workspace containers and exact-root trust once. Decision D7 in section 12 offers carrying the two configuration files.
- **Broker login agent.** On macOS, a login agent that names a deleted `V` restarts every 10 seconds under `KeepAlive` (`BrokerLaunchdServiceDocument.kt:40-43`). Decision D1 removes that state.
- **Lock starvation.** Long MCP or Codex sessions holding the shared lock (decision D4) block upgrade until they end. That matches today's refusal for unsettled invocations, now with a holder process identifier.
- **Record lock semantics.** A Go or Kotlin path that opens the lock file twice in one process silently releases the lock. The single opener in each language and the interoperability test reduce this risk.
- **Bootstrap pin lag.** A new bootstrap release must exist before `install.sh` on `main` names it, or installation fails closed with `ASSET_UNAVAILABLE`.
- **Durability.** Power-loss behavior depends on `fsync` ordering that no planned test proves (section 7).
- **Disk use.** An interrupted upgrade re-downloads on rerun, because P4 deletes unnamed versions instead of trusting them.

## 11. What stays true

| Requirement (inventory, section 5) | Where this design holds it |
| --- | --- |
| 1. Digest chain before execution | Section 1.3; steps I3 to I5 |
| 2. Hosts and coordinator stop before a switch; starts held off | Section 1.1; U6 and U7; section 4.2 |
| 3. Configuration, containers and trust survive upgrade and repair | `R/config`, `R/state` and the stores sit outside every `V` |
| 4. No switch or removal while an invocation is unsettled | Section 4.2 with decision D4 |
| 5. Power loss leaves old, new, or completed by rerunning | Section 3 tables |
| 6. Never delete a path Kast did not create | P4 ownership rule; X4; section 1.4 |
| 7. One lock primitive | Section 4.1 |
| 8. Each rejection names its check | Section 5 |
| 9. Offline repair only if promised | Repair re-downloads, as today (`InstalledProductRuntime.kt:205-230`) |

## 12. Decisions for the user

1. **D1, broker job and login agent on macOS.**
   - Option A (recommended): `managed-retire` runs `BrokerServiceDeactivation` in `DISABLE` mode, and the next Codex client launch republishes it.
   - Option B: the broker job names a stable path through `R/current`, and Kotlin admits that one link.
   - Option C: Go removes the owned login agent itself.

   Option A keeps one owner and needs no path exception. Its cost is that login start is absent until the next Codex launch.
2. **D2, semantic service identity.**
   - Option A (recommended): derive the label, readiness identity and runtime-directory owner from `R`. Then one installation has one label across versions, and Go can confirm its absence.
   - Option B: keep the per-version derivation. Then Go must compute the outgoing label from the outgoing `V`.
3. **D3, Kotlin's runtime root.**
   - Option A (recommended): Kotlin reads `V/share/kast/build-record.json` read-only and derives the runtime root with the same rule as Go. The build record is the release's own immutable bytes, not a transition record.
   - Option B: replace the Gradle start scripts with links to the Go binary, so that Go passes the runtime root to every JVM. This requires every entry to pass through the frontend.

   Option A narrows the acceptance criterion "only writer and reader of every lifecycle record" to transition records. That narrowing needs your agreement.
4. **D4, unsettled invocations.**
   - Option A (recommended): one-shot, MCP and Codex session entries hold the shared lock for their lifetime. The Go frontend takes it and lets the JVM inherit the descriptor. Invocation records and their directories are deleted. A crash releases the lock, so no stale record blocks upgrade.
   - Option B: keep Kotlin's invocation records, and Go reads them (V10 as `INVOCATION_UNSETTLED`).
5. **D5, bootstrap pinning.**
   - Option A (recommended): a separate `bootstrap-<12 hex>` release with reproducible digests pinned in `install.sh`.
   - Option B: make `install.sh` a release asset, pinned per release, and change the documented install URL.
   - Option C: embed the binary in `install.sh` (several megabytes per target).
6. **D6, forced reset.**
   - Option A (recommended): Go owns the `kast uninstall --force` entry and the lock. Kotlin's process discovery (`ForceDaemonRuntime.kt`, `NativeForceDaemonRuntime.kt`, `ResetSocketPeerOwnership.kt`) runs as a child with no reset fence.
   - Option B: port the discovery to Go in a later stage.
7. **D7, user state at the switch.**
   - Option A (recommended, no compatibility obligation): require `--reset-legacy` and discard everything.
   - Option B: before the reset, Go copies `R/installation/config/environment` and `config/workspaces/*/environment` into `R/config`.
8. **D8, stages 6 and 7 as one pull request or a stack of two.** I recommend a stack, so each diff stays reviewable, with both merged before the next stable promotion.
