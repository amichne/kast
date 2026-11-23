# Go installation lifecycle: design specification

## Conclusion

The Go `kast` binary can own the whole installation lifecycle with:

- one pointer, `R/current`;
- immutable version directories, each with three records;
- runtime and base entries that Go alone publishes;
- one lock file with two modes;
- one recovery rule.

The rule: every lifecycle command takes the lock exclusively, deletes every Kast-named entry that `R/current` does not reach, and only then makes its own change.
New content stays in a private staging tree until the command holds the lock exclusively, so a leftover never blocks a rerun.
That rule replaces the replacement journal, the shutdown fence, the uninstall journal, and the three recovery routes.

The release that installs a build always performs its own installation.
The selecting binary verifies the record and the release's own Control executable, then runs that executable.
So no binary reads records newer than itself, and no old installer code installs a new layout.

Go never runs a legacy binary. An installation in the old layout is rejected with one code, and the user removes it with its own `kast uninstall` first (section 1.4).

Kotlin keeps every effect on its own state: client configuration, coordinator state, workspace containers, user state in `~/.kast`, and retirement.
It stops reading and writing every lifecycle record.
The Kotlin path change cannot ship apart from the Go switch. Two earlier pull requests make it one change in one type (section 1.2).

The switch is gated twice. First, a stable promotion of a build in the new layout must exist. Second, a bootstrap binary must be published as a pre-release.
Only then does `install.sh` change (section 9).

Section 13 lists the decisions that need the user's answer. Section 14 answers each finding of the independent audit.

## Revision and scope

- Base revision: `a4b830ff2e` (`origin/main`, after #1371, #1373 and #1374).
- Inputs: issue #1369, sections 1 to 6 of `docs/reviews/installation-lifecycle-inventory.md` on branch `docs/lifecycle-inventory`, draft #1377, and the audit of this specification.
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
- The unit sets `WorkingDirectory` to the installation directory (`:69`).
- No unit file is written, no unit is enabled, and no `Restart=` property is set. Nothing restarts the coordinator at login or after an exit.
  The next semantic request starts it again through `InstalledSemanticCoordinator.connect` (`app-server/.../InstalledSemanticCoordinator.kt:56-80`).
- The label is `io.github.amichne.kast.semantic.<first 32 hex of sha256(installation path)>` (`SemanticServiceLaunch.kt:43`, `:60`, `:118-122`).
  The probe computes the same unit name (`packaging/internal/verification/linux_coordinator_probe.py:164-166`).
- The unit needs a private `/run/user/<uid>` owned by the user (`LinuxPersistentBrokerServiceHost.kt:49-59`). Without one, submission rejects and no coordinator starts.
- The Codex broker service is unavailable on Linux. `ensure` returns `UNAVAILABLE` (`LinuxPersistentBrokerServiceHost.kt:13-14`).
- Workspace Hosts are not supervised. The coordinator starts each one as a child with `ProcessBuilder` (`app-server/.../ide/IdeLaunchPlatform.kt:17-33`).
  The unit keeps the default kill mode. So I expect the `systemd` manager to stop Hosts in the unit's control group when the coordinator exits.
  No native run supports that expectation.

**macOS today.**

- The coordinator is a launchd job in `gui/<uid>`. Its property list lives at `<installation>/state/broker/<profile>/semantic-service.plist`.
  `submitSemanticExclusively` bootstraps it when `launchctl list <label>` reports it absent, or `kickstart`s an owned job (`MacOsPersistentBrokerServiceHost.kt:321-351`, `:933-947`).
- The semantic job has `RunAtLoad`, `WorkingDirectory` set to the installation, and `AbandonProcessGroup`, with no `KeepAlive` (`BrokerLaunchdServiceDocument.kt:36-55`, `:47`). So Hosts outlive the coordinator.
- The Codex broker job is a separate launchd job. Its label is `io.github.amichne.kast.broker.<sha256(codexHome)[:32]>`, so there is one per Codex home (`PersistentBrokerService.kt:190-202`).
  It has `KeepAlive` on unsuccessful exit with a 10-second throttle (`BrokerLaunchdServiceDocument.kt:40-43`).
  Its program is `<installation>/share/kast/libexec/kast-daemon` (`PersistentBrokerService.kt:262-263`), and its state directory is `<installation>/state/broker/<profile>` (`:391`).
- The login agent at `~/Library/LaunchAgents/<broker label>.login.plist` is the same broker job document with `--login` (`ServiceLoginAgent.kt:21-59`).
  Ownership is an exact document match (`ServiceLoginAgent.kt:24-35`). Deactivation rejects on any mismatch (`BrokerServiceDeactivation.kt:28-32`).
  `AppServerManagement` publishes the agent on `Bootstrap` and `Enable` (`AppServerManagement.kt:270-284`). The Codex client launcher calls `Enable` at each launch (`host/InstalledCodexClientLauncher.kt:382`).
- Issue #1310 tracks #1319, which removes the legacy login bootstrap of the app server. Whether the broker job survives #1310 is not settled. Decision D1 depends on it.

**Retirement today.**
`--internal-install managed-retire` runs `retireManagedProduct` from the outgoing Control (`distribution/cli/.../InternalManagementInstall.kt:28-29`, `:59-72`).

1. It leases every workspace container and writes the shutdown fence inside the lease (`ManagedWorkspaceRetirement.kt:41-80`). One rejected container record rejects the whole scope (`:82-89`).
2. It stops each Host with `share/kast/libexec/kast-service disable` (`ContainerManagement.kt:45-79`).
3. It retires the coordinator with `kast-service retire-semantic` (`InstallationLifecycle.kt:42-57`, `:169-189`).
   That command sends `DaemonManagementRequest.Retire` over the private socket and waits for the exact peer to exit (`SemanticCoordinatorRetirement.kt:25-88`).
   On macOS, `withdrawSemanticLaunchdJob` also boots out the owned job and waits for its absence (`SemanticLaunchdJob.kt:24-47`).
   On Linux, no `systemctl` call stops the unit.

It does not stop the broker job or remove its login agent.
Before it runs the old code, the Python installer verifies the outgoing payload again (`packaging/internal/product/managed_product_installation.py:1024-1029`).

**Retirement under Go.**

1. Go holds the lock exclusively (section 4).
2. Go verifies the retirement executables against their records: `V_old/share/kast/libexec/kast-management`, `kast-service`, every `lib/` entry, and the runtime entry's `java` and manifest. This is check T3 and V2.
3. If they verify, Go runs `V_old/share/kast/libexec/kast-management --internal-install managed-retire` as a child.
   The child has a bounded deadline and writes one closed retirement document to stdout:
   - the semantic label it proved absent;
   - each broker label it deactivated (decision D1);
   - each Host process incarnation that it proved exited: process identifier plus start time.
4. If they do not verify, `upgrade --repair` and `uninstall --force` use Go's forced retirement (section 3.6). Every other command rejects with `RETIREMENT_EXECUTABLE_DAMAGED`.
5. Go confirms each fact in the document:
   - **Linux.** `systemctl --user show --property=LoadState,ActiveState <label>.service` must report `LoadState=not-found`. `--collect` unloads a transient unit once it stops, so `not-found` also proves that its control group is gone.
     `is-active` is not enough, because it treats `deactivating` as inactive.
   - **macOS.** `launchctl list <label>` must report the job absent for every label in the document.
   - **Both.** Each Host incarnation must be absent: `kill(pid, 0)` reports no process, or the start time differs.
6. If the user has no supervisor (no user bus on Linux), the supervisor adapter reports `SUPERVISOR_UNAVAILABLE`. That is a fact, not a failure: no coordinator can run without one (`LinuxPersistentBrokerServiceHost.kt:49-59`). So Go checks only the Host incarnations.

Go uses the labels from the document. It does not compute them, so the label hash has one owner (`SemanticServiceLaunch.kt:118-120`).
The readiness document gains the version identifier of the coordinator that published it (decision D2). So a reader can tell which `V` a live coordinator runs from.

New starts are held off by the lock, not by a file (section 4.3).

### 1.2 Kotlin path derivations from the installation directory

Every derivation starts from one of three roots:

- The JVM code location: `<installation>/lib/<jar>`, whose parent's parent is the installation (`cli/.../InstalledControlLocation.kt:24-45`).
- The executable, through `parent.parent` or `toRealPath().parent.parent` (`AppServerManagement.kt:133`, `PersistentBrokerService.kt:263`, `KastServiceMain.kt:147`, `:232`).
- The management root, `HOME/.local/share/kast`, fixed by `ManagementRootResolution` (`distribution/cli/.../ManagementInstallation.kt:23-53`), then `root.resolve("installation")`.

| Family | Sites (`file:line`) | Moves to |
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
| Upward derivations | `InstalledBrokerServer.kt:92`; `MacOsPersistentBrokerServiceHost.kt:363`, `:399`; `InstalledCoordinatorClient.kt:99`; `InstalledClientContext.kt:119-122`; `ManagementStatus.kt:122` | Through `InstallationLayout` |
| Inverse derivations | `KastServiceMain.kt:262`; `ConfigurationInspection.kt:115`, `:228` (`ofConfigurationFile` in #1377) | Deleted; callers receive the layout |
| Broker daemon program | `PersistentBrokerService.kt:262-263` | Stays inside `V`; covered by retirement (decision D1) |
| Management receipt | `ManagementReceiptAdmission.kt:40`; `ProductManagementReceipt.kt:49-67`; Go `control.go:99-122` | Deleted. Go reads the runtime digest from the selected `V` |
| Product selection and runtime root | `InstalledProductRuntime.kt:124-185`, `:173`, `:205-230`; `InstallationLifecycle.kt:201`; `ManagedWorkspaceQuery.kt:87` | Decision D3 |
| Payload manifest | `BundledManifest.kt:46`, `:59`; `InstallationLifecycle.kt:170-175`, `:200-209`, `:234-247`; `ContainerManagement.kt:55-58` | Kotlin stops reading it; section 3.1 keeps the checks |
| Store siblings | `ManagedWorkspaceLifecycle.kt:102`; `ManagedWorkspaceRemoval.kt:20`; `ManagedWorkspaceRetirement.kt:12`; `ManagedWorkspaceStatus.kt:43`; `WorkspaceSharingInspection.kt:48`; `InstalledProductRuntime.kt:173` | Unchanged: `S/kast-workspaces`, `S/kast-runtime-bases` |
| Lifecycle records in Kotlin | `ManagementFileLock.kt:10-15`; `UninstallManagedRoot.kt:29`, `:139-147`; `ForceResetFence.kt:107`, `:237`; `RecordedRecoveryInstaller.kt:83-126`; `ManagementUninstall.kt:56-230` | Deleted (stage 10) |

Workspace container paths such as `ContainerStore.kt:122` resolve beneath the container profile, so they do not move.
The IDE plugin keeps its durable state under `~/.kast` (`runtime/hosted/.../HostedChangeResources.kt:32`), which is independent of `R`.

**Why one literal rule is not enough.** Draft #1377 routes the families above through one `InstallationLayout`.
It still has one root, with `managementRoot = root.parent`, and 44 call sites that resolve beneath that management root.
The upward and inverse derivations in the table also escape a rule that matches `resolve("config` literally.
So the switch is still spread across many sites, not one.

**Target type.** Stage 4 reshapes the owner to hold both roots, admitted by one function at an effect boundary:

```kotlin
/** The only Kotlin derivation of installation paths. */
class InstallationLayout private constructor(val root: Path, val version: Path) {
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

In the old layout, the same function returns `root = R` and `version = R/installation`.
Stage 8 changes only its body.
The architecture rule from #1377 extends to `Path.parent` chains on admitted installation paths.
An acceptance check sets a distinctive value in `R/config/environment`. Every entry must then observe it: query, MCP, service, daemon, broker and Host.
That check catches a site that still derives configuration from `V`.

The Gradle start scripts `kast-service`, `kast-daemon`, `kast-mcp` and `kast-codex` start the JVM without the Go frontend (`cli/build.gradle.kts:122-160`).
So the code location stays the one input that every entry has.

### 1.3 Release delivery

**Today.**

- `install.sh` is generated (`packaging/internal/bootstrap/bootstrap.sh.in`). Users fetch it from `main` (`docs/public/reference/installation.mdx:29`). It is 72,423 bytes.
- It pins a Python archive per target (`install.sh:35-41`), the `defusedxml` wheel (`:62-63`), and an embedded engine (`:66-73`). Then it runs the engine (`:76-78`).
- The engine resolves the selection over HTTPS (`managed_release.py:39-41`, `:1076-1215`). It checks the build record against the catalog or mapping digest (`:1795-1867`). Then it checks each asset against the record (`managed_product.py:353-377`).
- The Control executable already has a qualified digest: `qualification.controlSha256` (`managed_release.py:530-538`). Staging enforces it (`managed_product.py:459-464`).
- The engine rejects unknown target fields (`managed_release.py:608-617`) and unknown record schemas (`:738-739`). One invalid record rejects the whole catalog (`:1124-1150`).
- Publication uploads the record's assets, the acceptance assets, and `build-record.json` (`.github/scripts/release/publish_developer.py:295-312`). No asset is a standalone Go binary.
- Stable selection reads GitHub's `releases/latest` (`managed_release.py:40`, `:1094-1112`), which returns the newest release that is not a draft or a pre-release. Today that is `v0.52.0`, a build in the old layout.

**New assets, without a record change.** Each release target gains one top-level asset with a fixed name: `kast-control-<target>`.
Its bytes are the Control executable of that target's payload. Its digest is the existing `qualification.controlSha256`.
The record schema stays unchanged, so installed Python engines keep decoding the catalog.
The product gate and `publish_developer.py` reject a release whose asset digest differs from `controlSha256`.

**Positive layout marker.** A Control payload in the new layout carries `share/kast/layout-v2` with the content `2\n`.
Go installs only a payload that carries it, and rejects every other payload with `RELEASE_LAYOUT_UNSUPPORTED`.
The same payload drops `share/kast/managed-install-v1`, so an installed Python engine rejects it (section 1.4).

**Bootstrap and version skew.**

- A `bootstrap-<12 hex>` release holds `kast-bootstrap-<target>` for both targets. The suffix is the first 12 hexadecimal characters of the source tree digest.
  It is published as a pre-release, so `releases/latest` never selects it.
  A CI assertion checks that `releases/latest` names a `v<semver>` tag after every publication.
- The generator computes the pinned digests from a reproducible `go build -trimpath -buildvcs=false` with the `go.mod` toolchain. CI rebuilds them and compares.
- The bootstrap is minimal. It resolves the selection, verifies the record and `kast-control-<target>`, and runs that executable with the verified record.
- `kast upgrade` in an installed version does the same. So the target release always performs its own installation (M7).
- The digest chain: `install.sh` pins, then the bootstrap, then the catalog or mapping digest over HTTPS, then the record's asset digests and `controlSha256`.
- A failed bootstrap download or digest check is a shell error from `install.sh` (`fail`, `install.sh:6`, `:55-59`), not a Go failure code.

**Pin bumps.** A pull request that changes the pin merges only after its bootstrap release exists.
The pinned bootstrap stays valid across product releases because it runs each release's own binary.

### 1.4 Existing installations

Go classifies the home paths into one closed observation before any effect:

```go
type LayoutObservation uint8

const (
	LayoutAbsent     LayoutObservation = iota + 1 // nothing Kast-named in S
	LayoutResidual                                // no R; only Kast residue in S (section 3.5)
	LayoutIncomplete                              // R holds only Kast-named entries and no valid current
	LayoutCurrent                                 // R holds only Kast-named entries, and current names a V with a valid manifest
	LayoutLegacy                                  // any legacy marker exists
	LayoutForeign                                 // R holds an entry with no Kast name
)
```

The legacy marker set has nine patterns: `R/installation`, `R/management.json`, `R/recovery`, `R/shutdown.json`, `R/management.lock`, `R/activation.lock`, `S/.kast-uninstall-*.json`, `S/.kast-reset-*.json`, and `S/.kast-install-*`.
Precedence is fixed: `Legacy` over `Foreign` over `Current` over `Incomplete` over `Residual` over `Absent`.
A mixed tree with any legacy marker is `Legacy`.

On `LayoutLegacy`, every Go lifecycle command rejects with `LEGACY_LAYOUT` and has no effect.
The message tells the user to run the installed `kast uninstall`, then rerun `install.sh`.
Go never executes a legacy binary.
The legacy forced reset rejects every managed installation with `MANAGED_STATE_REQUIRES_UNINSTALL` (`PublicInstallationReset.kt:71-84`). So the legacy product's ordinary `kast uninstall` is the only removal route that its records support.
The legacy `install.sh` has no removal option (`install.sh:26-27`).

On `LayoutForeign`, every command rejects with `ROOT_FOREIGN` and names the first foreign entry relative to `R`.
Kast never deletes an entry it did not name. `R` is Kast's directory, but a user file inside it is still the user's (requirement 6).

The installed Python engine rejects a new-layout payload. With the record schema unchanged, it reaches the marker check, so `kast upgrade` in a legacy installation reports `INSTALLER_CONTRACT_UNSUPPORTED` at stage `CONTRACT` (`managed_release.py:1956-1958`, `:1538-1540`).

### 1.5 Commands: which move into Go

| Command | Owner after the switch | Reason |
| --- | --- | --- |
| `install.sh` engine | Go `kast-bootstrap` | Replaces the private Python interpreter and engine |
| `kast upgrade [--channel/--version/--commit] [--repair] [--dry-run] [--json]` | Go | Writes `R/current`, `V` and the stores |
| `kast uninstall [--force]` | Go | Removes `R`, the public command, and the stores; `--force` uses Go's forced retirement (decision D6) |
| `kast versions [--json]` | Go | Shares one catalog decoder with `upgrade`; deletes `BuildCatalog.kt`, `ReleaseDocumentSource.kt`, `ManagementBuilds.kt` |
| `kast status [--json]` | Kotlin, with Go facts | Go passes the version identifier, record digest and policy as JVM system properties |
| `kast stop`, `kast config`, `kast install skill` | Kotlin | Under the inherited shared lock (section 4.2) |
| `--internal-install managed-retire [--removal]` | Kotlin, run by Go | Retirement runs the outgoing version's code |
| `--internal-install reset`, `preflight-reset` | Deleted | Only verification harnesses call them |
| Top-level help | Kotlin catalog | `ManagementRoot` keeps help nodes for the Go commands. The frontend answers their exact help vectors from `frontend.json`. A test asserts that Kotlin can never select a Go command |
| IDE plugin | Unchanged | It reads `~/.kast` and the environment that Control passes. Lifecycle never stops a user's desktop IDE |

The frontend guide forbids lowering flags in Go (`frontend/AGENTS.md`, Rules).
Stage 1 amends that rule for the lifecycle commands above only.

**Downgrade policy.** A downgrade selects an older release, and that release's binary performs the installation.
If that binary cannot decode the current `V`'s records (their `schemaVersion` is newer), it rejects with `RECORD_SCHEMA_UNSUPPORTED`. The user then runs `kast uninstall` and installs the older release fresh.

## 2. Layout and records

### 2.1 Paths

```text
S/kast.lock                               lock file; never deleted
S/kast/                                   R, mode 0700
  current -> versions/<16 hex>            the single pointer (relative symbolic link)
  versions/<16 hex>/                      V, random identifier; mode 0700, contents read-only
    bin/, lib/, share/kast/...            Control payload, including share/kast/layout-v2
    share/kast/installation.json          payload manifest
    share/kast/product-selection.json     selection and runtime binding
    share/kast/build-record.json          release bytes, unchanged
  config/                                 Kotlin-owned client configuration
  state/                                  Kotlin-owned coordinator state
  .staging-<16 hex>/                      a command's private staging tree
  current.next                            transient link during a swap
S/.kast-removing-<16 hex>/                a tree being deleted
S/kast-workspaces/                        existing store: runtimes/, containers/, locks/
S/kast-runtime-bases/                     existing store
~/.local/bin/kast -> S/kast/current/share/kast/libexec/kast-management
~/.local/bin/kast.next-<16 hex>           transient link during public command creation
```

Kast-named entries of `R` are exactly `current`, `current.next`, `versions`, `config`, `state`, and `.staging-<16 hex>`.

- **Version directories.** Each `V` has a random identifier, so a stage never collides with a leftover. The `VersionID` slot type of the first draft is gone. `product-selection.json` records which build a `V` holds.
- **Public command.** It is a symbolic link with a fixed target string, so upgrade never rewrites it. `locateInstallation` already resolves links and strips the payload path (`control.go:77-95`), which yields the physical `V`.
- **Home paths.** `~/.local`, `~/.local/share` and `~/.local/bin` must be real directories owned by the user, not symbolic links. Otherwise every command rejects with `HOME_PATH_REJECTED`. Renames, ownership checks and the lock all rely on physical parents.
- **Root user.** Go refuses to run as user 0 (`ROOT_USER_REJECTED`).
- **File system.** `R` and both stores must sit on one local file system. A network file system rejects with `FILESYSTEM_UNSUPPORTED`, because record locks are unreliable there. Different devices reject with `CROSS_DEVICE`.
- **Modes.** The top directory of each `V` stays 0700 so that renames across parents can update it. Its contents are read-only. Deletion first adds the owner's write bit to each directory it empties.

### 2.2 Record schemas

Each record has an explicit `schemaVersion` that the decoder reads first.
Decoders reject unknown fields, duplicate keys, trailing data, and unknown enum values. Encoders write every field, including defaults.

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

// V/share/kast/product-selection.json: why this V exists and which runtime it binds.
type ProductSelection struct {
	SchemaVersion         int      `json:"schemaVersion"` // 1
	Version               VersionID `json:"version"`
	Target                Target   `json:"target"`
	RecordSHA256          Digest   `json:"recordSha256"`
	ReleaseTag            string   `json:"releaseTag"`    // "developer-v<version>" or "v<semver>"
	Policy                Policy   `json:"policy"`        // closed: "TRACK_STABLE", "TRACK_MAIN", "PINNED_BUILD", "LOCAL"
	PublicVersion         *SemVer  `json:"publicVersion"` // explicit null unless a stable release
	SourceRevision        Revision `json:"sourceRevision"`
	RuntimeArchiveSHA256  Digest   `json:"runtimeArchiveSha256"`  // names S/kast-workspaces/runtimes/<sha>
	RuntimeManifestSHA256 Digest   `json:"runtimeManifestSha256"` // digest of that entry's manifest.json
	BaseSHA256            *Digest  `json:"baseSha256"`            // explicit null without a runtime base
}

// V/share/kast/build-record.json: the release's bytes, decoded into the existing product record schema.
// Its SHA-256 equals ProductSelection.RecordSHA256.
type BuildRecord struct { /* the existing ProductRecord fields, unchanged (managed_release.py:542-581) */ }

// S/kast-workspaces/runtimes/<archive sha>/runtime.json: runtime receipt (managed_product_installation.py:183-191).
type RuntimeReceipt struct {
	SchemaVersion  int    `json:"schemaVersion"` // 1
	ArchiveSHA256  Digest `json:"archiveSha256"` // equals the directory name
	ManifestSHA256 Digest `json:"manifestSha256"`
}

// <runtime entry>/host[/Kast.app/Contents]/kast-headless.json: assembly record (managed_product_installation.py:92-107).
type AssemblyRecord struct {
	SchemaVersion       int    `json:"schemaVersion"` // 1
	Home                string `json:"home"`          // the entry's absolute Host home; checked equal to its own location
	DistributionSHA256  Digest `json:"distributionSha256"`
	BootstrapSHA256     Digest `json:"bootstrapSha256"`
	PlatformBuild       string `json:"platformBuild"`
	ProductBuild        string `json:"productBuild"`  // PlatformBuild + "-IJ"
}
```

The entry's `manifest.json` comes unchanged from the runtime archive. Its digest is `RuntimeManifestSHA256`.
The `R/current` link has a closed shape instead of a JSON schema: its target is exactly `versions/<16 hex>`.
The failure document (section 5) and the outcome document are the other Go-encoded contracts.

### 2.3 Writers and readers

| Record | Single writer | Readers |
| --- | --- | --- |
| `S/kast.lock` | Go creates it once and never deletes it | Every participant locks it (section 4) |
| `R/current`, `R/current.next` | Go, exclusive mode only | Go; the Go frontend through the public link; Kotlin `InstallationLayout.admit` |
| `V/share/kast/installation.json` | Go, inside the staging tree | Go |
| `V/share/kast/product-selection.json` | Go, inside the staging tree | Go; Kotlin under decision D3, option B only |
| `V/share/kast/build-record.json` | Go copies the verified release bytes | Go |
| `V/share/kast/layout-v2` | Release assembly | Go |
| Runtime entry: tree, `manifest.json`, `runtime.json`, `kast-headless.json` | Go, through a reservation and a rename without replacement | Kotlin `ContainerRuntime.kt:100-123` and `ManagedHeadlessInstallation.kt:147`; Go |
| Base entry | Go | Go; Kotlin through the runtime |
| `R/config/**`, `R/state/**`, `S/kast-workspaces/containers/**`, `~/.kast/**` | Kotlin, unchanged | Kotlin. Go only deletes the first three with uninstall, after Kotlin's removal checks |
| `~/.local/bin/kast` | Go | The shell; the Go frontend |

`importContainerRuntime` is a second Kotlin writer of runtime entries (`ContainerRuntime.kt:139-224`), but only tests call it.
Stage 10 moves it into test fixtures, so Go stays the single production writer.

**Deleted records.**

- `management.json`, `shutdown.json`, `management.lock` and `activation.lock`.
- The replacement journal and its preimages, and the uninstall journal.
- The retained execution bundle, the recovery bundle, and the captured selection file.
- The reset fence, the capability markers other than `layout-v2`, the invocation records (decision D4), and the private interpreter.

## 3. Operations as state machines

Each step below is one pure decision followed by at most one primitive effect (section 6).
"Power loss leaves" names the state after the last completed effect. **Old** and **new** are the selected versions. **Rerun** names the one command that finishes the state.

### 3.1 Common prelude

| Step | Effect | Lock |
| --- | --- | --- |
| P1 | Admit `HOME`, the home paths, the user, the platform and the file system (section 2.1) | none |
| P2 | Open `S/kast.lock` with `O_CREAT\|O_NOFOLLOW\|O_CLOEXEC`, mode 0600. Check that it is a regular file owned by the user, with one link and mode 0600 (`LOCK_FILE_REJECTED`) | none |
| P3 | Take the lock exclusively, without waiting, with at most 20 attempts 100 milliseconds apart. On failure, reject with `LOCK_HELD` and the one conflicting holder that `F_GETLK` reports | exclusive |
| P4 | Classify the layout (section 1.4). Reject `Legacy` and `Foreign` | exclusive |
| P5 | Delete leftovers: every `R/.staging-*`, `R/current.next`, `~/.local/bin/kast.next-*`, `S/.kast-removing-*`, the store reservations `.kast-runtime-*` and `.kast-base-*`, and every `V` that `current` does not name | exclusive |

P5 is the recovery rule.
A leftover is deleted only if it matches a Kast name, the user owns it, and it is not a symbolic link.
The two transient links are the exception: each must name its exact owned target.
Each tree is first renamed to `S/.kast-removing-<random>`, then deleted, so an interrupted deletion leaves one recognizable name.
P5 always runs under the exclusive lock, so no other lifecycle command can be staging.
A command deletes its own staging tree before it returns any rejection.

### 3.2 Install, upgrade and repair: one machine

`install` and `upgrade` run the same machine.
They differ only in selection defaults and in whether `LayoutAbsent` is admitted.
Over `LayoutCurrent`, install therefore retires and verifies like an upgrade.
That matches the documented re-run of the remote installer over an existing installation (`docs/public/reference/installation.mdx:32`).
With an unchanged record and no `--repair`, the outcome is `ALREADY_CURRENT`. The public link is still ensured.

| Step | Action | Lock | Power loss leaves |
| --- | --- | --- | --- |
| A1 | Prelude P1 to P5. Admit `Absent`, `Residual` (P5 finishes it), `Incomplete` or `Current` | exclusive | **old** |
| A2 | Home path checks (V8): the public command is absent or the exact owned link; on a first install, `S/kast-workspaces/containers` is absent or empty | exclusive | **old** |
| A3 | With `Current`, verify the current `V` against its manifest, and its runtime entry against its receipt (T3, V2). Damage proceeds only with `--repair` | exclusive | **old** |
| A4 | Downgrade the lock to shared. `fcntl` converts an exclusive lock to shared in place, with no window | shared | **old** |
| A5 | Resolve the selection, then verify the record and `kast-control-<target>` (V1). Unless this binary has that digest, run the verified binary with the record. The child inherits the lock descriptor (section 4.2). Continue as the child | shared | **old** |
| A6 | Publish missing runtime and base entries: stage a reservation, verify, sync, then rename to `runtimes/<sha>` without replacement. Verify an existing entry. Under `--repair`, stage a replacement for each damaged entry without renaming it | shared | **old**, with reservations that P5 deletes |
| A7 | Stage `R/.staging-<random>`. Extract Control and require `layout-v2`. Verify the executable against `controlSha256` (V1). Train the class-data archive with `java` from the runtime entry. Write the three records, set read-only modes, and sync the tree | shared | **old**, with a staging tree that P5 deletes |
| A8 | Convert the lock to exclusive, with the P3 attempt budget. On failure, delete the own staging tree and reject with `LOCK_HELD` | exclusive | **old** |
| A9 | Run P5 again, sparing only the own staging tree and reservations | exclusive | **old** |
| A10 | With `Current`, retire the outgoing version (section 1.1). Rejection deletes the own staging tree | exclusive | **old**, with stopped processes; they restart on demand from the old `V`. **Rerun**: the same command |
| A11 | Repair only: for each damaged entry, rename it to `S/.kast-removing-*` and rename its replacement into place | exclusive | **old** with a missing or repaired entry. **Rerun**: `kast upgrade --repair`, which retires by force because the entry fails verification |
| A12 | Rename the staging tree to `R/versions/<random>` without replacement; sync `R/versions` | exclusive | **old**, with an unnamed `V` that P5 deletes |
| A13 | Create `R/current.next`, then rename it over `R/current`; sync `R` | exclusive | **new** |
| A14 | Ensure the public link: create `~/.local/bin/kast.next-<random>`, then rename it into place. Use a rename without replacement when the link is absent, and a plain rename only over the exact owned link. Sync `~/.local/bin` | exclusive | **new**. Before A14 completes on a first install, **rerun**: `install.sh` |
| A15 | Run P5, which deletes the outgoing `V`; release the lock | released | **new** |

The installed `kast` stays runnable through every step except a first install before A14, so each rerun names a command that the user can run.
A missing runtime entry after A11 breaks semantic queries, but not the Go binary or `upgrade --repair`.
A start that races A8 to A13 either takes the shared lock before A8, which makes A8 reject, or fails its own attempt and reports `TRANSITION_IN_PROGRESS`.

### 3.3 Uninstall

The commit point is the rename of `R`. Before it, the installation is intact and the stores are present, so a rerun can still retire through Kotlin.
After it, only inert residue remains, and any command finishes it without Kotlin.

| Step | Action | Lock | Power loss leaves |
| --- | --- | --- | --- |
| X1 | Prelude P1 to P5. Admit `Current`, `Incomplete` or `Residual` | exclusive | installed |
| X2 | With `Current`, verify the retirement executables. Run `managed-retire --removal`, which adds Kotlin's removal checks: rejected and foreign containers (`ManagedWorkspaceRemoval.kt:36-41`, `:64-72`), and user state in `~/.kast` (`UninstalledUserState.kt:47-58`). Map its closed reason to `REMOVAL_BLOCKED` | exclusive | installed, with stopped processes. **Rerun**: `kast uninstall` |
| X3 | Commit: rename `R` to `S/.kast-removing-<random>`; sync `S` | exclusive | **new** (uninstalled), with residue |
| X4 | Take the workspace store's lifetime lock exclusively (`ContainerQueryLease.kt:65-77`, `:186-204`). Rename both stores to `S/.kast-removing-*` | exclusive | **new**, with residue |
| X5 | Remove `~/.local/bin/kast` only if it is the exact owned link. Remove each `/tmp/kast-uds-<32 hex>` alias only if it is a link owned by the user whose target lay inside `R` (V8) | exclusive | **new**, with residue |
| X6 | Delete every `S/.kast-removing-*`; release the lock. `S/kast.lock` stays | released | **new** |

With `--force`, X2 uses forced retirement (section 3.6) and skips Kotlin's removal checks. Then `~/.kast` stays untouched, and the outcome says so.
Kotlin's user-state cleanup in `~/.kast` stays a Kotlin effect inside `managed-retire --removal`.

### 3.4 Repair after damage

`--repair` is the only path that proceeds on a damaged `V` or runtime entry.
When the retirement executables fail verification, A10 uses forced retirement. Their own code cannot be trusted to run.

### 3.5 Residue

`LayoutResidual` is: no `R`, no legacy marker, and at least one `S/.kast-removing-*`.
Under that state only, the stores and an owned dangling public link also count as residue.
Any lifecycle command in that state finishes the removal in P5, without Kotlin, and then continues.
Without a removal tree, a store that holds containers is not residue. A first install then rejects with `WORKSPACE_STORE_OCCUPIED` (V8).
After X3, the installed `kast` is gone, so the command that finishes residue is the next `install.sh`. Until then, the residue is inert.

### 3.6 Forced retirement

Go's forced retirement needs no JVM. It serves `upgrade --repair` with damaged executables and `uninstall --force`.

1. For each supervisor label that the readiness document or `R/state/broker/*/` names, stop and remove the entry:
   - Linux: `systemctl --user stop <label>.service`, then `show` must report `LoadState=not-found`.
   - macOS: `launchctl bootout gui/<uid>/<label>`, then `list` must report the job absent.
2. Find the user's processes whose executable lies inside the outgoing `V` or the runtime entries it binds. Linux reads `/proc/<pid>/exe`. macOS reads `kern.procargs2` through `golang.org/x/sys/unix`, which keeps the build free of `cgo`.
3. Send `SIGTERM`, wait up to a bounded deadline, then send `SIGKILL`. Then confirm that each incarnation is absent.
4. Remove each owned login agent under decision D1.

## 4. The lock

### 4.1 Primitive

- POSIX record locks through `fcntl(F_SETLK)` on the whole file, from `golang.org/x/sys/unix`.
  `FileChannel.tryLock` in the JVM uses the same family on both targets (`ManagementFileLock.kt:23-24`, `ContainerQueryLease.kt:204`). #1371 aligned Python to it.
- Record locks belong to the process. Closing any descriptor for the file in that process releases them.
  So each process has exactly one opener of `S/kast.lock`: the Go lock adapter, or the Kotlin `LifecycleLock` object.
- The lock file is never deleted, so a holder never locks a stale inode. P2 checks its owner, link count and mode.
- Downgrading from exclusive to shared is atomic. Upgrading from shared to exclusive is not, and a failure keeps the shared lock.
- `F_GETLK` reports one conflicting holder, not every holder. `LOCK_HELD` evidence says so.

### 4.2 Holders per entry

The Go frontend takes the lock for a JVM entry and lets the JVM inherit it.
It opens the file with `O_CLOEXEC`, takes the lock, and clears `FD_CLOEXEC` only on the descriptor it hands to `exec`.
It passes `-Dkast.lifecycle.lock=inherited` on the JVM command line. A record lock held by a process survives `exec`, because the descriptor stays open.

| Entry (`--internal-entrypoint`) | Frontend lock | Why |
| --- | --- | --- |
| `management` with `status`, `stop`, `config`, `install skill`, public queries | shared, inherited | The invocation reads or writes `R` and must not race a structural change |
| `management` with `upgrade`, `uninstall`, `versions` | Go lifecycle (section 3) | Go owns these commands |
| `management` with `--internal-install managed-retire` | none | It runs under its parent's exclusive lock and observes it |
| `query`, `jq` | shared, inherited | As above |
| `mcp`, `codex` | shared, inherited (decision D4) | A live session is an unsettled invocation (requirement 4) |
| `daemon` | none | A lifetime holder blocks every upgrade, because retirement needs the exclusive mode first |
| `service`, `installer` | none; `installer` is deleted | `kast-service` stops Hosts during retirement |

Kotlin has one owner, `LifecycleLock`:

- With `kast.lifecycle.lock=inherited`, the process already holds the shared lock. Every gate returns `AVAILABLE` without opening the file.
  An open and close in the same process releases the inherited lock. This rule is what prevents that (`CoordinatedToolClient.kt:89-95` reaches `BrokerServiceStartLock.kt:50` in the same JVM).
- Without the property, as in entries that start scripts run, `LifecycleLock` opens the file once per process and keeps the channel open.
  Each gate takes the shared lock without waiting and releases it after its decision, on that same channel.
- Retirement children try the shared lock. Failure is their proof that a transition is in progress.

The property is a claim, not a proof. A user who starts the JVM by hand with it bypasses only their own gate.

### 4.3 Start gates

The 14 call sites of `InstallationLifecycleFence.observe` call `LifecycleLock` instead:

- `InstalledBrokerServer.kt:92`, `MacOsPersistentBrokerServiceHost.kt:363` and `:399`;
- `SemanticCoordinatorPublication.kt:69` and `:151`, `InstalledCoordinator.kt:32`, `AppServerManagement.kt:139`;
- `SemanticCoordinatorRetirement.kt:26` and `:68`, `BrokerServiceStartLock.kt:50` and `:276`;
- `runtime/CoordinatorControl.kt:130`, `:191` and `:224`.

Two more gates read lifecycle files and move too:

- `InstalledProductRuntime.kt:41` (the shutdown fence);
- `cli/.../direct/InstalledToolAdmission.kt:20-24` (the fence, the reset fence and `recovery/replacement`).

Stage 5 ships a dual gate on the old layout. A transition is in progress if the fence file exists or the shared lock is unavailable.
Stage 10 deletes the file half.

## 5. Failure type and outcomes

Go has no sum types, so the failure is sealed by construction.
`Failure` has only package-private fields, and each code has exactly one constructor that takes that code's evidence. A code paired with the wrong evidence cannot be built.

```go
type Failure struct {
	code     Code
	stage    Stage    // closed: PRELUDE, SELECTION, DOWNLOAD, VERIFY, STAGE, PUBLISH, RETIRE, SWITCH, CLEANUP, REMOVE
	evidence evidence // sealed: one variant per code
}

type evidence interface{ sealed() }

type assetDigest struct { Asset AssetName; Expected, Observed Digest }
type pathEvidence struct { Root RootKind; Path RelativePath } // RootKind: R, RUNTIME_STORE, BASE_STORE, PUBLIC_COMMAND, HOME_SHARE, TMP_ALIAS
type filesystemEvidence struct { Root RootKind; Path RelativePath; Operation FsOperation; Cause FsCause; Errno int }
type transferEvidence struct { Asset AssetName; Cause TransferCause; HTTPStatus int } // HTTPStatus is 0 unless Cause is HTTP_STATUS
type holderEvidence struct { Mode LockMode; Holder int }
type childEvidence struct { ExitCode int; Reason RetirementReason } // RetirementReason mirrors the Kotlin document's closed enum
type supervisorEvidence struct { Supervisor SupervisorKind; Label ServiceLabel; State UnitState }
type noEvidence struct{}

func AssetDigestMismatch(stage Stage, asset AssetName, expected, observed Digest) Failure
func FilesystemRejected(stage Stage, root RootKind, path RelativePath, op FsOperation, cause FsCause, errno int) Failure
// ... one constructor per code
```

- `FsOperation` is closed: `OPEN`, `MKDIR`, `WRITE`, `SYNC`, `RENAME`, `RENAME_NO_REPLACE`, `SYMLINK`, `UNLINK`, `RMDIR`, `CHMOD`, `LOCK`, `STAT`.
- `FsCause` is closed: `PERMISSION`, `NO_SPACE`, `READ_ONLY`, `EXISTS`, `NOT_FOUND`, `NOT_EMPTY`, `NAME_TOO_LONG`, `IO`, `UNMAPPED_ERRNO`. `UNMAPPED_ERRNO` carries the bounded `errno` number. So an unmapped error stays exact data instead of a generic code.
- The JSON form is `{"schemaVersion":1,"code":...,"stage":...,"evidence":{...}}`. The decoder selects the evidence type by `code` and rejects a mismatch.
- No evidence carries an absolute path, payload bytes, or an environment value.

| Code | Evidence | Check | Inventory group |
| --- | --- | --- | --- |
| `SELECTOR_INVALID`, `SELECTORS_CONFLICT` | none | Exactly one valid selector | V1 |
| `PLATFORM_UNSUPPORTED` | none | `macos-aarch64` or `linux-x86_64` | V1 |
| `CATALOG_UNAVAILABLE` | transfer | The catalog or mapping transfer | V1 |
| `CATALOG_INVALID`, `COMMIT_AMBIGUOUS`, `BUILD_UNAVAILABLE` | none | Catalog and mapping shapes and selection | V1 |
| `RECORD_DIGEST_MISMATCH` | asset digest | Record bytes against the selected digest | V1 |
| `RECORD_INVALID` | path | Record fields; the (a) value checks of T6 | V1, T6 (a) |
| `RECORD_SCHEMA_UNSUPPORTED` | path | A record from a newer binary | new |
| `ASSET_UNAVAILABLE` | transfer | Asset transfer | V1 |
| `ASSET_SIZE_MISMATCH`, `ASSET_DIGEST_MISMATCH` | asset digest | Downloaded bytes against the record | V1 |
| `QUALIFICATION_INVALID` | none | Receipt outcome, revision and bundle digest | V1 |
| `ARCHIVE_ENTRY_REJECTED` | path | Archive entries are local, unique and of admitted types | V1 |
| `BUNDLE_INVENTORY_INVALID` | path | Composed inventory and identities | V1 |
| `CONTROL_DIGEST_MISMATCH` | asset digest | Control executable against `controlSha256` | V1 |
| `RELEASE_LAYOUT_UNSUPPORTED` | none | The payload carries `layout-v2` | new |
| `LOCAL_ARTIFACT_REQUIRED` | none | A local record is never fetched remotely | V1 |
| `RUNTIME_ENTRY_DAMAGED`, `BASE_ENTRY_DAMAGED` | path | Store entries match their receipts and manifests | V2, T9 (a) |
| `PAYLOAD_MANIFEST_INVALID`, `PAYLOAD_FILE_DAMAGED` | path | `V` matches its manifest | T3 |
| `RETIREMENT_EXECUTABLE_DAMAGED` | path | Retirement code verifies before it runs | T3, V2 |
| `HOME_INVALID`, `HOME_PATH_REJECTED`, `ROOT_USER_REJECTED` | path or none | Home admission | V8 |
| `FILESYSTEM_UNSUPPORTED`, `CROSS_DEVICE` | path | Local file system, one device | new |
| `ROOT_FOREIGN` | path | `R` holds only Kast names | V8 |
| `LEGACY_LAYOUT` | path | No legacy marker (section 1.4) | new |
| `PUBLIC_COMMAND_FOREIGN` | path | The public command is absent or the owned link | V8 |
| `WORKSPACE_STORE_OCCUPIED` | path | No containers before a first install | V8 |
| `LEFTOVER_FOREIGN` | path | A cleanup candidate is the user's, not a link, and has a Kast name | V8 |
| `LOCK_FILE_REJECTED` | path | Lock file owner, link count and mode | T10 (a) |
| `LOCK_HELD` | holder | The lifecycle lock; a shared holder is also an unsettled invocation | T10 (a), V10 |
| `RETIREMENT_REJECTED`, `RETIREMENT_TIMED_OUT` | child | Child exit and deadline | T8 (a) |
| `REMOVAL_BLOCKED` | child | Kotlin's removal checks before uninstall | Kotlin workspace and user-state checks |
| `SUPERVISOR_STILL_ACTIVE` | supervisor | No unit or job remains | T8 (a) |
| `PROCESS_STILL_ALIVE` | holder | A retired incarnation is gone | T8 (a) |
| `FILESYSTEM_REJECTED` | filesystem | An effect failed | T9 (a) |

Outcomes are a separate closed union: `INSTALLED`, `UPGRADED`, `REPAIRED`, `ALREADY_CURRENT`, `UNINSTALLED`, and `UNINSTALLED_WITHOUT_USER_STATE` for `--force`.
`SUPERVISOR_UNAVAILABLE` is an observation inside an outcome, not a failure.
V10 survives as `LOCK_HELD` under decision D4.
The interpreter checks of V1 (`private_interpreter.py:67-98`) have no successor, because no interpreter remains.

**Payload checks per use.** Kotlin hashes `kast-service` against the manifest before it runs it (`InstallationLifecycle.kt:234-247`, `ContainerManagement.kt:55-58`).
Go keeps those checks at the boundaries where the code runs during a lifecycle command: A3 and step 2 of retirement.
Section 14 explains why the check on every ordinary use goes.

## 6. Go package boundaries

```text
frontend/
  cmd/kast/main.go                 process wiring only
  cmd/kast-bootstrap/main.go       selection, verification, exec of the release binary
  internal/frontend/               existing routes; per-entry lock table (section 4.2)
  internal/lifecycle/
    failure/                       Failure, codes, sealed evidence, outcomes                 (pure)
    record/                        section 2.2 types and strict decoders                     (pure)
    layout/                        LayoutObservation from an observed tree                    (pure)
    selection/                     catalog and mapping decoders; selector resolution          (pure)
    plan/                          each machine as Next(state, observation) -> (effect, state) (pure)
    run/                           interpreter: performs one effect, observes, feeds plan
    effect/fs/                     one primitive per effect: open, mkdir, write, sync, rename, rename-no-replace,
                                   symlink, unlink, rmdir, chmod, lock, stat, statfs
    effect/archive/                tar.gz and zip extraction through effect/fs, with entry admission
    effect/net/                    HTTPS GET with size limit, timeout, HTTPS-only redirects
    effect/process/                child execution with deadline; process discovery; signals
    effect/supervisor/             launchctl list, print, bootout; systemctl --user show, stop
```

- Pure packages import no `os`, `net`, `os/exec`, `syscall` or `time`. A dependency check in `frontend/check gate` enforces that with `go list -deps`.
- `run` receives each adapter as an interface value built in `main.go` from explicit configuration. Nothing below `main.go` reads the environment.
- A rename without replacement uses `renameat2(RENAME_NOREPLACE)` on Linux and `renamex_np(RENAME_EXCL)` on macOS.
- `sync` is `fsync` on Linux and `fcntl(F_FULLFSYNC)` on macOS, because `fsync` on macOS does not flush the drive cache.
- The interpreter emits one bounded stage event per step to stderr: step, outcome, and elapsed milliseconds. That is durable instrumentation (Engineering Dictum 9). Tests do not synchronize on it.

## 7. Tests at the narrowest boundary

| Boundary | What it proves | Inputs |
| --- | --- | --- |
| `failure`, `record`, `selection` | Encoded shape against checked-in expected JSON for each code and outcome; explicit nulls; rejection of unknown fields, codes, versions and code-evidence mismatches | Literal values |
| `layout` | Every observation, the fixed precedence, and every mixed tree that holds a legacy marker | Tree descriptions |
| `plan` | The effect chosen for each observation. The expected outcome comes from section 3, not from the implementation | Pure tables |
| `plan`, sync order | Every rename into a published name follows a sync of its contents, and a sync of the parent follows it | The effect list a plan emits, compared with a pure rule |
| `run`, crash at every effect | For each machine and each effect index k, an `effect/fs` double performs effects 0 to k-1, then stops. A fresh interpreter run on the resulting tree must end old, new, or finished, and a second rerun must change nothing | A real `t.TempDir()` tree, stub retirement child, fixture product |
| `effect/fs` | Lock modes and the atomic downgrade. A failed upgrade keeps the shared lock. Lock file checks, rename without replacement, link swap. Owned deletion refuses links and foreign owners. Network file systems reject | A private `t.TempDir()` |
| `effect/fs` interoperability | A JVM `FileChannel.tryLock` holder excludes Go, and a Go holder excludes the JVM, in both modes | A real JVM child, as `ManagementFileLockTest` does with Python |
| Frontend inheritance | The frontend runs a real JVM entry that passes a start gate. While that JVM lives, the test's exclusive attempt still fails | The built binary and a real JVM |
| `effect/archive` | Rejection of absolute paths, `..`, duplicates, escaping links, oversize entries | Archives built in the test |
| `effect/net` | Size limit, timeout, HTTPS-only redirects, status mapping | `httptest.NewTLSServer` |
| `effect/process`, `effect/supervisor` | Exact argument vectors; `LoadState` parsing; exit mapping | A scripted executor that rejects unexpected, excess and unconsumed calls |
| `run`, lock interactions | Rerun after `LOCK_HELD` succeeds. A shared holder present at A8 makes A8 reject with the own staging tree deleted. A second command rejects at P3 | A holder child that the test controls, ordered by the lock itself, not by time |
| `run`, broker state | Across an upgrade, D1's calls happen, and no login agent names a deleted `V` | Scripted supervisor executor |

Crashes are injected at the adapter, by effect index, so the tests cover every point inside a step.
Each effect is one primitive, so no crash point falls inside an effect.
These tests prove recovery from every prefix of effects, not durability after power loss. Power-loss durability rests on the sync-order test and on review.

## 8. Installed acceptance on both targets

The native harness runs on `ubuntu-24.04` for `linux-x86_64` and `macos-15` for `macos-aarch64` (`composed-linux.yml:68-69`).
Its cases already include fresh install, upgrade to a different build, and uninstall (`packaging/internal/verification/product_acceptance.py:15-30`).
The switch adds or changes these cases on both targets:

1. **Install** through the generated `install.sh` and the pinned bootstrap, then a first semantic query.
2. **Configuration reach**: a distinctive value in `R/config/environment` is observed by every entry (section 1.2).
3. **Upgrade** while a coordinator and a Host run. The old incarnations must exit. On Linux, `LoadState=not-found` must hold; on macOS, `launchctl list` must report the job absent. A query then succeeds from the new `V`, and the readiness document names it.
4. **Reinstall** over `LayoutCurrent` through `install.sh`, which retires like an upgrade.
5. **Repair** after a damaged runtime file, including damaged retirement executables. The case uses forced retirement.
6. **Uninstall**: no `R`, no stores, no residue, no public command, no owned `/tmp` alias, and no supervisor entry. On macOS, no owned login agent remains.
7. **Interruption**: one native kill per machine, after A10 and after X3. The crash-index tests in section 7 cover every effect.
8. **Busy refusal**: an open MCP session makes `upgrade` reject with `LOCK_HELD` (decision D4).
9. **Lock inheritance**: section 7's frontend inheritance case, on the installed product.
10. **Legacy rejection**: over a legacy baseline, `install.sh` rejects with `LEGACY_LAYOUT` and changes nothing. After the legacy `kast uninstall`, it installs.
11. **Old upgrade refusal**: the legacy `kast upgrade` rejects the new release with `INSTALLER_CONTRACT_UNSUPPORTED` at stage `CONTRACT`.
12. **Old stable refusal**: Go selecting a release without `layout-v2` rejects with `RELEASE_LAYOUT_UNSUPPORTED`.

Running without a user supervisor and running as user 0 are covered at the adapter boundary only. Neither CI runner provides those states natively.
The Linux coordinator probe reads labels from the retirement document instead of hashing `root / "installation"` (`linux_coordinator_probe.py:164-165`).

## 9. Staged implementation plan

Each stage is one pull request with its own tests. Stages 1 to 7 change no installed layout.

1. **Go lifecycle core.** Add `failure`, `record`, `selection`, `layout` and `plan`, with pure, sync-order and dependency-boundary tests, and amend the frontend flag rule.
   This stage depends on approval of this specification, on decisions D4, D6 and D7, and on the machines in section 3.
2. **Go effect adapters.** Add `effect/*`, including process discovery and the JVM lock interoperability test.
3. **Kotlin layout owner.** Draft #1377: route every family through `InstallationLayout`, still on the old layout.
4. **Kotlin layout reshape.** Make `InstallationLayout` hold `R` and `V`, admitted at the effect boundary that reads `current`. Delete `ofConfigurationFile`, route the upward derivations, and extend the architecture rule to `parent` chains. Add the configuration-reach check. Behavior stays unchanged.
5. **Early behavior on the old layout.** One pull request each:
   - (a) The Go frontend derives the runtime root from the selected payload's own records, not `management.json`.
   - (b) `LifecycleLock`, the dual gate, the per-entry lock table and inheritance, and the inheritance acceptance test.
   - (c) `managed-retire` emits the retirement document, deactivates the broker (decision D1), supports `--removal`, and puts the version identity in readiness.
   - (d) `kast status` receives its installation facts from Go.
6. **Release assets.** Publish `kast-control-<target>` with the digest equality gate. Add the bootstrap workflow as pre-releases, the reproducibility check, and the `releases/latest` assertion. The record does not change.
7. **Go lifecycle end to end, unwired.** Add `run`, `kast-bootstrap`, and the install, upgrade, uninstall, versions and forced-retirement commands. Add the crash-index and lock-interaction tests against fixture products.
8. **Switch.** Flip the body of `InstallationLayout.admit`, and derive the semantic identity from `R`. Wire the Go commands. Add `layout-v2` and drop `managed-install-v1`. Run installed acceptance on both targets.
   Only previews carry this layout until a stable promotion. Until stage 9, Python `install.sh --channel preview` fails closed with `INSTALLER_CONTRACT_UNSUPPORTED`, and the default stable installation still installs the old layout.
9. **Public installer switch.** It merges after a stable promotion of a stage-8 build and after its bootstrap release exists. Regenerate `install.sh` to pin and run the bootstrap.
10. **Deletion.** It merges right after stage 9, as a stack:
    - Delete `packaging/internal/{installation,product}`, `packaging/internal/bootstrap/{bootstrap_entry.py,private_interpreter.py,install-candidate.sh}`, and the engine embedding in `generate_bootstrap.py`.
    - Delete the Kotlin lifecycle files: `ManagedUpgrade.kt`, `ManagementUninstall.kt`, `UninstallManagedRoot.kt`, `UninstallRetirementJournal.kt`, `ProductRemovalJournal.kt`, `RecordedRecoveryInstaller.kt`, `InstallationReplacementReceipt.kt`, `ManagementReceiptAdmission.kt`, `ProductManagementReceipt.kt`, `BundledManifest.kt`, `InstallationRecoveryRemoval.kt`, `ManagedRecoveryRemovalProof.kt`, `PriorInstallationPreparation.kt`, `ManagementFileLock.kt`, the force-reset files, the shutdown fence contract, the catalog files, and the file half of the dual gate.
    - Move `importContainerRuntime` into test fixtures.
    - Delete the Gradle tasks `installationRecoveryTest`, `installerRemovalTest`, `installationSystemPythonTest` and `localInstallationTest`, and their `productBuildGate` entries (`build.gradle.kts:637-652`).
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
- **Preview gap.** Between stages 8 and 9, the public preview installation fails closed. Stage 9 waits on a stable promotion.
- **Lost user state.** Users of the old layout lose client configuration, workspace containers and exact-root trust once (decision D7).
- **Broker login agent.** On macOS, a login agent that names a deleted `V` restarts every 10 seconds (`BrokerLaunchdServiceDocument.kt:40-43`). Decision D1 removes that state; #1319 can remove the agent entirely.
- **Lock starvation.** Long MCP or Codex sessions block upgrade until they end (decision D4). That matches today's refusal, and the failure names a holder.
- **Process-wide lock semantics.** A second opener of the lock file in one process releases its lock. The single owner in each language and the inheritance test reduce the risk.
- **Runtime entry growth.** Each upgrade can add a runtime entry. Decision D9 sets the collection rule.
- **Durability.** Power-loss behavior depends on sync ordering, which only the sync-order test and review support.
- **Re-download.** An interrupted command downloads again on rerun, because P5 trusts no unnamed tree.

## 12. What stays true

| Requirement (inventory, section 5) | Where this design holds it |
| --- | --- |
| 1. Digest chain before execution | Section 1.3; A5 to A7 |
| 2. Hosts and coordinator stop before a switch; starts held off | Section 1.1; A8 to A13; section 4 |
| 3. Configuration, containers and trust survive upgrade and repair | `R/config`, `R/state` and the stores sit outside every `V` |
| 4. No switch or removal while an invocation is unsettled | Section 4.2 with decision D4 |
| 5. Power loss leaves old, new, or a state that one command finishes | Section 3 tables; crash-index tests |
| 6. Never delete a path Kast did not create | P5; `ROOT_FOREIGN`; X5; section 1.4 |
| 7. One lock primitive | Section 4.1 |
| 8. Each rejection names its check | Section 5 |
| 9. Offline repair only if promised | Repair downloads again, as today (`InstalledProductRuntime.kt:205-230`) |

## 13. Decisions for the user

1. **D1, broker job and login agent on macOS.** This decision depends on whether the broker survives #1310 and #1319.
   - Option A (recommended): `managed-retire` enumerates `R/state/broker/*/service.plist` and runs `BrokerServiceDeactivation` in `DISABLE` mode for each one. Its document lists the labels. The next Codex launch republishes through `Enable`.
     If a login agent does not match its exact document, it stays, and retirement rejects with `RETIREMENT_REJECTED` and reason `LOGIN_AGENT_FOREIGN`. Go's forced retirement leaves such an agent untouched too, and the outcome names it.
   - Option B: #1319 removes the broker login bootstrap before stage 8, and only the broker job remains to retire.
2. **D2, semantic identity.**
   - Option A (recommended): derive the label, readiness identity and runtime-directory owner from `R`, and put the version identifier in the readiness document.
   - Option B: keep the per-version derivation. Then the retirement document carries the outgoing label, and nothing else changes.
3. **D3, Kotlin's runtime root.**
   - Option A (recommended): Kotlin derives the runtime root from its own `java.home`, minus the catalog's Java home path for the platform (`ContainerRuntime.kt:397`). It then admits the entry through `ContainerRuntime` (`:100-123`). Kotlin reads no version record, and `ProductBuildRecord` (`BuildCatalog.kt:110`) is deleted.
     Every JVM entry must run on `java` from the runtime entry. Stage 4 verifies that for the start scripts, and an entry on another `java` fails closed.
   - Option B: Kotlin keeps a narrow read-only decoder of `product-selection.json`. Then that file has one writer and two readers, and `ProductBuildRecord` is deleted anyway.
   - Option C: Go passes the runtime root as a property. The start scripts bypass Go, so this option fails for them.
4. **D4, unsettled invocations.**
   - Option A (recommended): use the per-entry lock table in section 4.2, and delete the invocation records.
   - Option B: keep the Kotlin invocation records, and Go reads them (`INVOCATION_UNSETTLED`).
5. **D5, bootstrap.**
   - Option A (recommended): a pre-release `bootstrap-<12 hex>` with reproducible pinned digests. It runs the release's own binary, and the pin moves in a separate pull request after the release exists.
   - Option B: publish `install.sh` per release and change the documented URL.
   - The first draft's option C, embedding the binary in `install.sh`, is withdrawn. `bash -c "$(curl ...)"` passes the script as one argument, which Linux limits to 128 KiB, and the binary is several megabytes.
6. **D6, forced reset.**
   - Option A (recommended): Go owns `uninstall --force` completely through forced retirement (section 3.6). The Kotlin force-reset files are deleted.
   - Option B: keep Kotlin's process discovery as a child. A damaged runtime then disables the forced path, so this option does not meet M3.
7. **D7, existing installations.**
   - Option A (recommended): reject with `LEGACY_LAYOUT` and direct the user to the legacy `kast uninstall`. Nothing carries over.
   - Option B: on `LayoutLegacy`, Go copies `R/installation/config/environment` and the workspace overlays into `S/.kast-carried-config-<random>/` before it rejects. The next fresh install adopts them. This is a migration, and nothing obliges it.
8. **D8, delivery of stages 8 to 10.** Recommended: stage 8 alone, stage 9 after the stable gate, and stage 10 stacked on stage 9. Bundling 8 and 9 blocks stage 8 on a stable promotion that needs stage 8 first.
9. **D9, runtime entry collection.**
   - Option A (recommended): P5 also deletes runtime and base entries that `current` does not bind. A container whose retired generation names a deleted entry gets a new generation on its next start. Stage 7 must prove that Kotlin behavior first.
   - Option B: keep entries that any container generation names. Then Go reads Kotlin's `generation.json` records.

## 14. Audit responses

Each finding was checked against `origin/main` at `a4b830ff2e` before the change.

| Finding | Response | Change |
| --- | --- | --- |
| B1 | Accepted. `PublicInstallationReset.kt:71-84` rejects managed state, and `install.sh:26-27` has no removal option | Section 1.4: no `--reset-legacy`; `LEGACY_LAYOUT` only; Go runs no legacy binary. D6 and D7 revised |
| B2 | Accepted. Staging and renames in shared mode left trees that only exclusive mode deletes | Section 3.1: the prelude is always exclusive. Section 3.2: staging stays private until A12, and P5 runs again at A9. Other changes: own tree deleted on rejection, random identifiers, transient links in P5, `LayoutIncomplete` redefined |
| B3 | Accepted. `installation.mdx:32` documents re-running the installer | Section 3.2: one machine for install and upgrade, with A3 and A10 over `LayoutCurrent` |
| B4 | Accepted. Renaming the stores first removed the runtime that the rerun's retirement needs | Section 3.3: the rename of `R` is the commit point; section 3.5 adds `LayoutResidual` |
| B5 | Accepted. `CoordinatedToolClient.kt:89-95` reaches `BrokerServiceStartLock.kt:50` in the same JVM | Section 4.2: per-entry table, descriptor kept open across `exec`, inheritance property, one Kotlin owner; section 7 inheritance test |
| B6 | Accepted. `managed_release.py:608-617`, `:738-739` and `:1124-1150` reject the changed record; `releases/latest` is `v0.52.0` | Section 1.3: record unchanged, fixed asset name, `layout-v2`, pre-release bootstrap, `releases/latest` assertion; stage 9 gated; acceptance case 11 code |
| M1 | Accepted. #1377 has one root and upward and inverse derivations | Section 1.2 table rows; stage 4 reshape; configuration-reach check |
| M2 | Accepted. `runtime.json` and `kast-headless.json` were missing; D3-A needed `ProductBuildRecord` | Section 2.2 records; section 2.3 writers, including the test-only `importContainerRuntime`; D3 revised to `java.home` |
| M3 | Accepted. Python re-verifies before it runs old code (`managed_product_installation.py:1024-1029`) | Section 1.1 step 2; section 3.6 forced retirement; `RETIREMENT_EXECUTABLE_DAMAGED` |
| M4 | Accepted | `systemctl show` with `LoadState=not-found`; retirement document with labels and Host incarnations; version identifier in readiness; behavior without a supervisor |
| M5 | Accepted. Labels are per Codex home, and ownership is an exact match | D1 enumerates `R/state/broker/*/service.plist`, defines the mismatch outcome, and records the #1319 dependency |
| M6 | Accepted | Section 4.3 adds `InstalledProductRuntime.kt:41` and `InstalledToolAdmission.kt:20-24` |
| M7 | Accepted | Section 1.3 and A5: the release's own binary performs the installation |
| M8 | Accepted in part | Kotlin removal checks run in `managed-retire --removal` (X2); `ROOT_FOREIGN` policy (section 1.4). Rebuttal on per-use payload verification follows this table |
| M9 | Accepted. Synchronizing on a stderr event races a pipe read | Section 7: crash by effect index at the adapter, every prefix, sync-order test, lock-interaction cases |
| M10 | Accepted | Stage 1 dependencies; stages 5(a) to 5(d); section 10 |
| M11 | Accepted. `install.sh` is 72,423 bytes; the argument limit is 128 KiB | Section 1.3 pin bumps; D5 option C withdrawn |
| Lock file | Accepted | Never deleted; P2 checks owner, link count and mode |
| Failure type | Accepted | Sealed evidence by constructor; closed `FsCause`; outcomes separate; one holder |
| File system | Accepted | `F_FULLFSYNC`; directory modes; `FILESYSTEM_UNSUPPORTED`, `CROSS_DEVICE` |
| Lifecycle gaps | Accepted | D9; help owner and downgrade policy in section 1.5 |
| Classification | Accepted | Fixed precedence and one nine-pattern legacy set (section 1.4) |
| Bootstrap download failure | Accepted | Section 1.3: a shell error |
| Missing requirements | Accepted | Section 2.1 (root user, linked home paths); section 1.1 (no supervisor); X5 (`/tmp` aliases). Desktop discovery records sit under the broker owner directory (`DesktopDaemonDiscovery.kt:243-247`), so D1's deactivation releases them, and otherwise they go with `R/state` |

**Rebuttal, per-use payload verification (M8).**
This design keeps manifest verification at every lifecycle boundary where code from `V` runs (A3; retirement step 2). It drops hashing on every ordinary use.
Evidence: ordinary JVM invocations already run `lib/` without any manifest check (`frontend/internal/frontend/control.go:130-146`).
The per-use checks guard only `kast-service`, and only on lifecycle paths (`InstallationLifecycle.kt:169-189`, `ContainerManagement.kt:55-58`), which Go now covers before it runs them.
`V` is read-only, and only a rename under the exclusive lock replaces it. An in-place change needs the owner to change its modes first.
A per-use check keeps Kotlin a reader of `installation.json`, which the decision in #1369 removes.
