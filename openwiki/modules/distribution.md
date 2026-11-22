---
type: "Reference"
title: "Distribution and packaging"
openwiki_generated: true
verified:
  - by: openwiki/0.9.0
    at: 2026-11-22T20:53:36.158Z
sources:
  - id: openwiki-source-baf30c604828cfde90a8ab63
    resource: repo://.githooks/pre-push
  - id: openwiki-source-0e96c1b9a971eb3f67a64455
    resource: repo://.github/scripts/ci/fast_checks.py
  - id: openwiki-source-970d1a886c7a46d7b0335a81
    resource: repo://.github/scripts/ci/gradle_diagnostics.py
  - id: openwiki-source-d09e57e512e655a9f4013dba
    resource: repo://.github/scripts/ci/gradle_graph.py
  - id: openwiki-source-645374ea4b988c91aac66118
    resource: repo://.github/scripts/ci/release_handoff.py
  - id: openwiki-source-eaec0145ee5bcb15fda00e9c
    resource: repo://.github/scripts/ci/source_validation.py
  - id: openwiki-source-5830aecb2b5f6a16093ed507
    resource: repo://.github/scripts/ci/test_push_policy.py
  - id: openwiki-source-8c864abdf1b09fa67b48adbc
    resource: repo://.github/scripts/release/build_candidate.py
  - id: openwiki-source-5e92c81a46b459b558f8901e
    resource: repo://.github/scripts/release/product_candidate.py
  - id: openwiki-source-e61ce41a823368fa4e25eb84
    resource: repo://.github/scripts/release/product_producer.py
  - id: openwiki-source-f02c30e87f3db4d071a7be5b
    resource: repo://.github/scripts/release/publish_developer.py
  - id: openwiki-source-164e2da859b5277df81c7d94
    resource: repo://.github/workflows/ci.yml
  - id: openwiki-source-804f227d6f0416062f3436fe
    resource: repo://.github/workflows/developer-release.yml
  - id: openwiki-source-4d1d392666be6dfdd7a91a2e
    resource: repo://.github/workflows/release.yml
  - id: openwiki-source-20e85ee434d9dedec13e85f7
    resource: repo://build-logic/src/main/kotlin/support/tasks/control/GenerateAgentToolsTask.kt
  - id: openwiki-source-16803a9d8df1ded50a107e29
    resource: repo://build-logic/src/main/kotlin/support/tasks/control/GenerateComponentReleaseTask.kt
  - id: openwiki-source-a387d9d545afff426d34a10a
    resource: repo://build-logic/src/main/kotlin/support/tasks/verification/VerifyDistributionTasks.kt
  - id: openwiki-source-2a9daaac1604f238ef4c63fb
    resource: repo://build.gradle.kts
  - id: openwiki-source-9334c5b6f4494897095d1968
    resource: repo://cli/src/main/control-launchers/bin/kast-mcp
  - id: openwiki-source-7904a856aedff77e554352c6
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/KastControlMain.kt
  - id: openwiki-source-6e0cb99be2f37815eed2066a
    resource: repo://cli/src/test/kotlin/io/github/amichne/kast/cli/publicshell/NativeForceResetCompositionTest.kt
  - id: openwiki-source-e4a9595c544ec2b876590037
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/BuildCatalog.kt
  - id: openwiki-source-0e50d1b18d1925b709ede5d3
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerRuntime.kt
  - id: openwiki-source-ea2b24fdd206a91568317996
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceDaemonRuntime.kt
  - id: openwiki-source-bae14a3ed4bc7ba21f443356
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceInstallationReset.kt
  - id: openwiki-source-6f0b647814adafaaa5d5c5f2
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceLifecycleStates.kt
  - id: openwiki-source-4a775e59f7cd3b1cc2d7cc10
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/InstallationLifecycle.kt
  - id: openwiki-source-6ead538479055040a6fa54d0
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/InstalledProductRuntime.kt
  - id: openwiki-source-2141db5e92ccbb5de75a1544
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt
  - id: openwiki-source-b30c7b16cf2f49971ca55950
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedUpgrade.kt
  - id: openwiki-source-e1eccbe769b8e78a5354c5ca
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementExecution.kt
  - id: openwiki-source-7cd4eb997a7878eca6390142
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementInstallation.kt
  - id: openwiki-source-a62e12ff14ead337deede61b
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementUninstall.kt
  - id: openwiki-source-36e0824ede001594d7142429
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/NativeForceDaemonRuntime.kt
  - id: openwiki-source-dfb88490277ececa651be1f8
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PublicInstallationReset.kt
  - id: openwiki-source-e62300b4b70eef650b59f1db
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/RejectedWorkspaceRemoval.kt
  - id: openwiki-source-d258f44cd873e00e544ca7ed
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/UninstallManagedRoot.kt
  - id: openwiki-source-d6537b8c3c1d3e623334d7d1
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/UserStateInventory.kt
  - id: openwiki-source-e7fd130e6641f6c75e3cde3f
    resource: repo://distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/BuildSelectionTest.kt
  - id: openwiki-source-09db336d3e004f256dbf17f7
    resource: repo://distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/HostedEndpointPaths.kt
  - id: openwiki-source-879895757e79a8add3de70b0
    resource: repo://distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/NativeControlInvocation.kt
  - id: openwiki-source-f3718a29d9c6d868d0d9d73e
    resource: repo://distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ManagedInstallationOwnedTree.kt
  - id: openwiki-source-8677e434adb584035406f54a
    resource: repo://distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/SelectedIdeInstallation.kt
  - id: openwiki-source-9c28868aa566a6cc319d3878
    resource: repo://distribution/release/plugin-release.gradle.kts
  - id: openwiki-source-360539b338a8755db6cff27a
    resource: repo://distribution/release/run_product_gate.py
  - id: openwiki-source-eb5e76c5c52dc1f7704250ce
    resource: repo://distribution/release/test_run_product_gate.py
  - id: openwiki-source-81d5f1627e19148569f46f81
    resource: repo://gradle/libs.versions.toml
  - id: openwiki-source-d53edde1666c585216ff3c8d
    resource: repo://packaging/composed_bundle.py
  - id: openwiki-source-a27b421c9c1d82b40bd5da73
    resource: repo://packaging/internal/bootstrap/bootstrap.sh.in
  - id: openwiki-source-9685c35bc7adaf6e5678720b
    resource: repo://packaging/internal/bootstrap/generate_bootstrap.py
  - id: openwiki-source-714a256a14f92900e3bd2e7e
    resource: repo://packaging/internal/composition/composed_release.py
  - id: openwiki-source-f819c9a16e09783f5c943c2d
    resource: repo://packaging/internal/installation/immutable_store.py
  - id: openwiki-source-9f2a730e7185b57c14ac08e8
    resource: repo://packaging/internal/installation/managed_installation.py
  - id: openwiki-source-496462e31bbb3fc9156abfe9
    resource: repo://packaging/internal/installation/managed_release.py
  - id: openwiki-source-68861fd33e9641882305aee3
    resource: repo://packaging/internal/product/local_product_install.py
  - id: openwiki-source-07f32471b5605b546192361f
    resource: repo://packaging/internal/product/managed_product_installation.py
  - id: openwiki-source-543def007b531d23eaa51f66
    resource: repo://packaging/internal/runtime/runtime_archive.py
  - id: openwiki-source-e61c96fc4903a7ca5466593c
    resource: repo://packaging/internal/runtime/runtime_base.py
  - id: openwiki-source-ba9aa96ff29fd40c40dce826
    resource: repo://packaging/internal/verification/product_acceptance.py
  - id: openwiki-source-6904899612d0ede016a5965a
    resource: repo://packaging/internal/verification/product_lifecycle_probe.py
  - id: openwiki-source-27b11dfabcb3ca89e348f52d
    resource: repo://packaging/internal/verification/run-installed-product.py
  - id: openwiki-source-621afc296584eb4217553339
    resource: repo://packaging/managed_headless.py
  - id: openwiki-source-bcd37dcb7ef8ba1e7f516e0f
    resource: repo://packaging/tests/test_composition_bundle.py
  - id: openwiki-source-56b11a0b6d3644e3f2024856
    resource: repo://packaging/tests/test_product_acceptance.py
  - id: openwiki-source-12d7829bb8c1e9c898163e3a
    resource: repo://packaging/tests/test-install-checkout.py
  - id: openwiki-source-abab1b24f64b3271633d720d
    resource: repo://packaging/tests/test-managed-build-record.py
  - id: openwiki-source-ab43a673f10e05a8590d3331
    resource: repo://packaging/tests/test-managed-headless.py
  - id: openwiki-source-7fa48d17b46dccf82f4b3238
    resource: repo://runtime/hosted/build.gradle.kts
  - id: openwiki-source-7f5d36c76505fdd995af1f49
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/IdeLifecycleApplication.kt
  - id: openwiki-source-57ba3a687a2a322141093d5f
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/OwnedHostedEndpoint.kt
generated: { by: "codex", at: "2026-11-22T20:53:36.158Z" }
---


# Distribution and packaging

## Current managed product boundary

Public installation selects one complete product for macOS ARM64 or Linux x86-64.
Preview and stable identify exact complete builds; stable promotion retains the qualified bytes rather than rebuilding them with another version.
Published schema 2 and 3 product records require both CI targets and complete component qualification evidence.
Published schema 5 records instead bind the single macOS ARM64 target that current CI qualifies.
Local schema 4 records instead retain one supported target with `LOCAL` provenance and no invented CI run or second platform.
Python published decoding and Kotlin `validProductRecord` reject local records; installed decoding and `validInstalledProductRecord` retain both forms.
`install.sh --local-artifact DIRECTORY` also admits a local `build-record.json` and recorded files under `payload/` into the canonical global installation.
`LocalReleaseSource` supplies a pinned selection to the existing product admission and activation owners, without product HTTP fallback.
The bootstrap still downloads pinned private Python and `defusedxml`; local input is not offline setup.
`InstalledProductSelection` admits recorded identity for exact repair even when runtime bytes are missing.
`installedRepairSelection` reuses that identity only for published products; a local product rejects with `LOCAL_ARTIFACT_REQUIRED` before installer or release effects.
`InstalledProductRuntime` adds local runtime admission for semantic requests, without release-server access or desktop fallback.
Local installed records require `PinnedBuild`, no public version, and the matching developer tag; recorded source and artifact identities remain exact.

`python3 packaging/internal/product/local_product_install.py build --source CLEAN_CHECKOUT --output DIRECTORY` exports a local product without global installation.
The current builder admits a clean committed checkout on macOS ARM64, verifies component assembly, then calls `product_producer.seal_target`.
It records source, input, artifact and assembly evidence for that one target in `build-record.json`, `payload/` and `evidence/`.
Its `LOCAL_PRODUCT_BUILT` receipt leaves `releaseQualification: NOT_ESTABLISHED`.
Canonical installation remains a separate generated `install.sh --local-artifact DIRECTORY` invocation.

The first explicit semantic request creates trust for one exact canonical Gradle root and selects private Host state.
CLI and MCP share that request owner; session hooks and MCP initialization remain passive.
Scoped stop is temporary. Upgrade retirement first acquires exclusive query leases, then fences and retires exact generations.
Vanished-root reclamation requires settled private evidence and retains an exterior receipt after interrupted deletion.

Uninstall removes its own unchanged preparation fence when no removal journal was committed.
Inactive rejected workspace records need exclusive access and private-state admission before removal.
Repair stages verified runtime/base replacements in the existing journal and resets damaged owned bytes only after Host retirement.
Interrupted reset resumes from retained bytes without deleting repositories, workspace profiles, or external Gradle homes.

Installed local records remain eligible for exact recovery through `managed_product_installation`, `immutable_store`, and retained `UninstallManagedRoot` evidence validation.
These consumers use installed-record admission without weakening selection, artifact, directory identity or deletion ownership checks.

Native qualification is not complete. Component qualification now precedes sealed-product admission and detached installed-product acceptance.
The stages below replace the earlier circular setup route without claiming successful native Host execution.
`uninstall --force [--json]` is restricted to legacy state. Its exterior workspace lifetime lease protects admission before detachment.
Either managed store or `product-selection.json` rejects with `MANAGED_STATE_REQUIRES_UNINSTALL`; managed products use ordinary uninstall with its recovery journal.

The Control payload contains the canonical `agent-tools` skill and its references.
`kast install skill` exports that skill; Kast writes no agent harness configuration and registers no provider.
Agents receive the skill and an MCP server running `kast --internal-entrypoint=mcp` through dotagents.
Control assembly stamps the version in the skill front matter.
Query examples come from the public tool schema.

The `distribution:cli` module owns the `Clikt` management implementation. The `cli` module composes it with the existing private entries behind one JVM Control entry, `KastControlMain`. The Go frontend is the single native Control executable at `share/kast/libexec/kast-management`; it runs that JVM entry from JARs under `lib`. Management reads a separate ownership receipt and keeps the selected public executable path across upgrades. Its management commands are `config`, `status`, `stop`, `install skill`, `versions`, `upgrade`, and `uninstall`. Thin private launchers select query, MCP, service, daemon, Codex, or installer entries through `--internal-entrypoint`.

Product installation admits selected bytes before replacement and leaves semantic preparation to an explicit request. Status reads installation receipts without starting Java or preparing a workspace.

The running coordinator supplies a bounded passive projection for its captured control version, ready workspaces, and live connection count. The projection also keeps each observed host identity, plugin version, and compatibility. Installed and running control versions remain separate; missing live host evidence is unavailable. One-shot public calls publish a bounded process identity record while executing; MCP sessions keep the same canonical identity in their session directory. Native status counts only records whose exact process incarnation remains live. Unavailable observations remain distinct from verified empty results.
Distribution contracts own configuration keys, defaults, owners, operational limits, runtime identity, and bootstrap outcomes. Managed adapters own installation trees, recovery receipts, selected IDE discovery, and endpoints. The retired isolated runtime download adapter, archive store, heap observer, and network/trust-store bootstrap have been removed; IDEA owns its import environment and trust configuration.

Control and Host retain separate component artifact ownership and reviewed `idea-262.zip` and `idea-263.zip` plugin variants. Public installation selects them only through one complete product record, not independent component choices. `run-installed-product.py` delegates to the complete-product lifecycle probe with explicit qualified candidate, baseline, interpreter, and evidence inputs. Missing prerequisites reject rather than skipping or substituting a staged component pair. The checked [configuration schema](../../packaging/configuration-schema.json) must remain aligned with the Kotlin catalogue.

## Component qualification and installed acceptance

`composed_release._qualify_release` validates exact component archives and passive discovery through the shipped Control.
It does not start a compiler Host or build a test-only native executable.
The schema 2 `COMPONENT_ASSEMBLY` receipt binds source, bundle, runtime, and Control identities, not installed-product acceptance.
The producer hashes Control itself before sealing the build record; it does not take that identity on trust from the receipt.
Published schema 1 `COMPONENT_NATIVE` receipts remain admissible under their original contract without relabelling them.

The CI `product_producer.py target` command records one native target's evidence.
Its `seal` operation creates the immutable build record from the producer run without rebuilding.
After a rerun of failed jobs, sealing admits qualification from the same run at an earlier or equal attempt, and the join admits an earlier seal.
Current CI seals a Mac-only schema 5 record with `seal --macos-only`; schema 2 and 3 records bind both targets.
That record can then enter installed-product acceptance through normal managed admission.
The later `accept` operation joins one detached receipt per recorded target without rewriting the sealed record.
`product_candidate.py` distinguishes a sealed candidate from one admitted with its acceptance receipts.
Preview publication and stable promotion require those receipts and retain the original record and payload bytes.

`product_lifecycle_probe.py` and `product_acceptance.py` own the installed acceptance scenarios and detached evidence contract.
The baseline needs sealed component qualification, not prior publication.
New assembly-qualified baselines also need installed acceptance. Published native-qualified baselines retain their original admission contract.
CI admits its exact record digest, source revision, producer attempt, and successful sealing job.
A later failure in the baseline run does not erase that sealing evidence.
The baseline can share the candidate's source revision, but each native target needs a different actual bundle/runtime/Control composition.
Changing only metadata or repeating exact repair cannot supply upgrade evidence.

The installed probe first upgrades the baseline to the candidate and checks a changed compiler lifetime.
It then uninstalls that product and installs the candidate again without retained managed workspace state.
Before any semantic request, it runs help, status, describe help, and query planning.
These passive calls must not create semantic workspace state.
Three simultaneous shipped public first queries to one root must all succeed and share the compiler Host identity observed by a later query.
This is a fresh-install check, not merely parallel requests to a warm Host.

The probe also checks CLI, MCP, stop/restart, simultaneous requests across two roots, and edits.
The six former component probes now use the installed Control: exact caller edges, Java references, unknown-result rejection,
Linux runtime-only shared libraries and answering-Host identity, endpoint/socket cleanup, and re-analysis after an existing-file edit.
Final uninstall must preserve sentinels in the private HOME, a foreign IDEA profile, the explicit external Gradle directory, and both repositories.
Schema 3 installed receipts require all 17 cases in order, including `FRESH_INSTALL`, `PASSIVE_DISCOVERY`, and `SAME_ROOT_PARALLEL_FIRST_QUERIES`.
It writes a detached receipt only after all required cases complete.
The receipt binds the original build record, native target, Control digest, producer, baseline record, and baseline component digests.
Every target receipt must name the same baseline record and source revision.
CI runs installed acceptance on macOS ARM64 after sealing, then joins its receipt before publication admission.
No native Host success, completed acceptance, or publication is established by this explanation.
Source checks and decoded receipts alone cannot supply that evidence.

## Immutable runtime bases and components

`runtime_base.py` separates immutable vendor files from build-specific runtime components.
Base identity includes the target, donor digest, recipe inputs, support inputs, and exact file inventory.
Overlay copies verified files rather than hard-linking them, rejects component overlap, and cannot mutate the retained base through a composition.
Product publication retains bases in `kast-runtime-bases` and complete runtimes in the workspace store.
Only the producer projects split archives into qualification inputs; promotion does not rebuild or hydrate them.

`composed_bundle.py` packs authenticated runtime inputs, component assets, the bootstrap archive, and the canonical generated `install.sh`.
The bundle contains no Python `zipapp` or separate standalone installer.
Archive assembly is not a public installation route or native qualification proof.

Workspace containers instead share an installer-free runtime ZIP for their version and platform.
The native reader checks the archive digest, typed manifest, file digests, paths, and links before sealing payload bytes and atomic publication.
The top-level directory stays owner-private and writable so publication can rename it.
It invokes neither Python nor an archive executable.
Assembly and admission contracts do not establish native qualification on each platform.

Host and lifecycle sockets use the shared HOME-and-target digest scheme under canonical `/tmp`.
Private namespace admission checks ownership and mode `0700` without repairing foreign state.
Installation and workspace path lengths no longer determine socket path lengths.
See [managed workspace admission](../distribution/management-and-connections.md#managed-workspace-admission) for trust, routing, and stop policy.

## Managed headless assembly

`managed_headless.py` admits an explicit vendor archive digest before it creates a new owned assembly root.
It stages the bundled bootstrap descriptor, seals runtime files, and extracts an admitted Host archive under `components/plugins`.
It neither invokes the installer nor creates installed command, mutable workspace, or private profile state.
If component staging rejects, `ComponentStagingRejected` keeps the original release rejection in its `component` field.
The outer reason remains `INVALID_COMPONENT`; the nested reason and stage remain available to callers and serialized diagnostics.
The missing-record regression checks this preserved rejection before plugin extraction.
The assembler rejects an existing destination.
Its manifest records input digests, Host dependency roots, plugin decisions, and each kept or removed file.
An explicit exclusion removes a whole plugin directory.
Unknown plugins, Host roots, and dependencies of kept plugins reject before removal.
Assembly records `NOT_RUN` for Host acceptance.
Only installed production behavior can establish that acceptance.

Native lifecycle commands fence admission at `<install-root>/shutdown.json`, outside the replaceable payload.
Direct tools and coordinator startup honor the fence.
Force retirement recognizes worker entries through the shared `NativeControlInvocation` contract.
It excludes management and installer entries and rejects an unknown entry or unavailable process arguments before erasure.
The selected PID and start identity remain necessary through capture and retirement.
Force retirement signals only admitted exact process incarnations.
A running selected Host can reject with `HOST_RESTART_REQUIRED`.
Public exact repair reuses the recorded complete product and existing replacement journal, not the deleted Control-only force-reinstallation owner.

## CI evidence

CI classifies the exact changed-path window on Linux before allocating product runners.
An audited edit to authored docs runs Mintlify, the Markdown parser, glossary tests, and glossary projection checks.
Known regular main/test Kotlin sources in the listed product modules run product and portable validation.
Installer examples, generated schemas, scripts, build configuration, protected paths, and unknown or mixed inputs keep full validation.
The completion job requires every selected job to succeed and every excluded job to be skipped.
It then seals a validation record with the exact source revision, source tree, comparison base, event, and producer run identities.
One product invocation tests and assembles the complete Control/Host pair at `0.0.<CI run number>`.
The installed Kast dispatcher exits without qualification. Explicit local qualification uses version `0.0.0` with a `LOCAL_VALIDATION` record.
The full source commit and tree identify the checkout; no published release label is selected.
The wrapper sets source, Control, and Host versions and passes the admitted source revision.
Clean source is checked before execution and again after Gradle succeeds.
The staged Control build identity must match that version, commit, and tree before `VALIDATED`.
A dry-run records `DRY_RUN`; failures cannot inherit an earlier success.
Before/after admission detects observed drift, not transient edits later restored.
Local qualification grants no publication or installation authority.

Full and affected source validation and the local product gate request single-use Gradle daemons.
Source validation uses sequential Isolated Projects configuration for graph inspection and task execution.
Disk caches remain enabled; client retirement does not prove synchronous daemon retirement.
On graph timeout or cancellation, optional diagnostics attempt a bounded daemon thread dump.
Invocation association and process start identity are checked before and after attachment.
Incomplete diagnostics do not replace the graph failure or prevent client cleanup.

The legacy component candidate retains exact commit, tree, producer, platform, toolchain, and payload digests. Its same-repository PR reuse requires matching trees and successful producer evidence. That candidate is not a complete product. The complete-product lane instead joins the retained native inputs of each recorded target from the producer run, at the attempt that produced each artifact, without rebuilding. Missing target evidence rejects; retention is 30 days.

Linux assembles the release once; `release_handoff.py` binds it to source revision, tree, version, and digest.
The Mac job restores that exact record and runs the existing `publicLauncherTest` suite against the assembled Control product instead of compiling Control twice.
It keeps the suite's XML and HTML reports with the Mac qualification evidence.
Component assembly qualification precedes sealing, and installed acceptance owns compiler and lifecycle evidence through the shipped Control.
Sealing joins successful source work and the qualified Mac product before installed acceptance.

Successful main push CI requests developer promotion.
The publisher first admits the validation outcome from that exact successful source, run, and attempt.
Missing, malformed, contradictory, or mismatched outcome evidence rejects promotion.
`DOCS_VALIDATED` completes without a candidate, promotion record, product release, or developer pointer update.
`PRODUCT_VALIDATED` keeps the tested-candidate publication path.
Its trusted publication job downloads retained bytes without running Gradle.
A separate promotion record binds the unchanged candidate to the successful main run and merged commit.
The publisher rechecks producer success, complete asset inventory, checksums, source identity, and remote file digests.
Existing releases must match exactly for retries.
A delayed publisher can keep its immutable release but cannot update latest after main advances.
The public `developer-latest` branch contains a pointer to the exact prerelease and original build revision.
The public bootstrap selects preview, stable, or an explicit local artifact; it no longer accepts the retired `--developer-latest` alias.
Manual publication accepts a successful current-main push or manual CI run ID with matching event and producer evidence.

The Linux source job and macOS native job configure cache transport for Gradle User Home state and task outputs.
Only main push and main workflow-dispatch runs can save shared cache entries from these jobs.
Pull requests, forks, merge groups, and other refs are read-only.
A repository secret supplies the cache encryption key and is unavailable to fork PRs.
A supplied key alone does not establish that configuration-cache state was captured.
Successful main candidate reuse skips Gradle setup.
An authorized full CI dispatch on accepted main can seed the shared cache without automatic developer publication.
Configured transport does not establish an observed task cache hit or lower build time.
Stable product release maps a public version to the exact retained preview tag and build-record digest without rebuilding.

## Retained internal installation and recovery

`generate_bootstrap.py` owns embedded engine, retained support, and fixed data membership.
Gradle stages its generated projection under `share/kast` without a copy of the root `install.sh`, and Control verification rejects `share/kast/install.sh` as a retired payload.
The competing `install-legacy.sh`, `install-local.sh`, and `install-checkout.sh` entry points are removed.
`install-candidate.sh` remains a private bridge for recorded upgrade and recovery callers, not another public installer.
Bridge admission requires the complete manifest-owned Control inventory and verified bytes.
The separate contributor `install` command uses `packaging/internal/product/local_product_install.py` and existing product activation under an isolated root.
The standalone desktop IDEA plugin installer is removed, and release admission rejects a release that still carries it.
Only product replacement journals are recoverable; any other recorded journal returns `RECOVERY_REQUIRED`.

`ControlDistributionLimits` owns the maximum verified control-product entry count and manifest size used by staged installation and runtime identity admission. Release layout verification rejects a product outside that bound before publication.

`ControlPayloadInventory` counts paths globally, including the three payload roots, before sorting or hashing. Installer and broker use that same admission; `verifyReleaseRuntimeAdmission` exercises the runtime identity owner on the actual staged control product. Limit diagnostics keep the resource and observed lower bound. The hosted-plugin ZIP has a separate archive budget.

Read [configuration](../contracts/configuration.md) for ingress and ownership rules.
`ConfigurationSchemaDocument` defines the shared document. The CLI-owned `InstalledConfigurationSchema` is the sole catalogue generator and includes operational limits from protocol, broker, installation, and CLI owners.
`assembleControlRelease` uses `controlVersion` for Control records, artifacts, and checksums.
`assembleHostRelease` uses `hostedPluginVersion` for Host records, archives, and checksums for the reviewed IDEA release lines.
Component releases
resolve latest within `control-v` or `host-v`; their versions need not match.
`ControlRelease` declares its required hosted contract. `HostRelease` declares its
provided contract and supported IntelliJ release line. Component publication
validates the record against archived bytes and the exact source revision.

`GenerateControlMetadataTask` derives `ide-host.json` from control build identities
and the required hosted contract, with no plugin archive input. The plugin embeds
its own provenance and complete provided contract. `assembleRelease` remains a
fresh-install convenience containing both artifacts and host metadata; the pair
is admitted by contract equality. Public product records additionally bind the managed runtime and native evidence.
Control installation manifests have no necessary plugin digest. Lifecycle
inspection retains historical records so old control processes can retire safely.

Release asset construction, checksums, SBOM inventory, and publication use the same archive inventory.

The [retired installed knowledge contract](../contracts/installed-knowledge.md) records the rejected `share/kast/knowledge` payload.
The [repository knowledge explanation](../knowledge/installed-projection-and-retrieval.md) covers the retained citation scanner and verified module report, not installed retrieval.

The generated catalogue also declares the four execution ceilings, host backlog
and connection capacity, and native source continuation byte/TTL limits.
`:cli:generateConfigurationCatalogue` owns the snapshot;
`verifyConfigurationIngress` checks its exact agreement with the Kotlin owners.
The [configuration contract](../contracts/configuration.md) records the defaults.
Workspace registry retention emits a bounded `kast_installation_registry` observation with its exact outcome.
Selected IDEA discovery retains one canonical home across upgrades. Typed metadata resolution identifies its macOS ARM bundle and executable and rejects ambiguity and escapes. It admits the reviewed `262` and `263` release lines. Installation inspection includes the launch observation and persists the derived receipt.

Control assembly packages `share/kast/provider-catalog.json` from the same hosted
schema projection as the CLI. The artifact contains hosted metadata and schemas,
with no executable grammar. Distribution layout requires it; runtime release
admission qualifies the actual staged file through the production provider.
The control payload includes private executables at `share/kast/libexec/kast-daemon` and `share/kast/libexec/kast-service`, inside the existing inventory and checksum boundary. The assembled-product check verifies the private launchers are present and executable; their argument and readiness rules remain with their Kotlin owners.
Legacy force-reset internals bypass old payload and receipt admission while preserving canonical scope and verified retirement. An exterior fence and persistent lock survive root replacement. Exact root-derived jobs and scoped process incarnations remain distinct from shared IDEA and Gradle processes. Erasure requires quiescent typed state; rejection preserves retained storage. Public force admission rejects managed state before detachment.

The retained lifecycle progresses through privately constructed fenced, quiescent, and erased types.
A single-use lease rejects repeated effect transitions.
Staged/installed reset states, force reinstall, and its staging compensation owner have been removed.
The exterior lock stays stable across resets.
OS and filesystem refusal remain finite failures.

Native installer dispatch explicitly binds the sole admitted HOME-derived root.
Public uninstall rejects an alternate root or nonphysical path before owned
removal. Force reset rejects symlinked ancestors and cannot resolve a parent
alias into another tree.
Public command publication retains its own receipt-bound destination and rejects
parent aliases; that destination never selects a second installation.
Full uninstall retains a receipt-bound exterior retirement journal across cleanup. Managed product removal retains an admitted recovery interpreter and payload tree through `UninstallManagedRoot`. Unknown or changed evidence remains protected after Control removal. Uninstall changes no agent configuration.
User-state inventory retains endpoint locks and physical identities through retirement and deletion. Unknown contents, live hosts, changed identities, malformed or unsettled mutation evidence, or a WAL without a completed checkpoint reject and preserve recovery state. The owning SQLite adapter inspects exact schemas and bounded typed plan, attempt, journal and receipt records read-only; it does not initialize, checkpoint or manufacture recovery. Upgrade does not invoke this full user-state cleanup.

## Managed composition and selection

`kast versions` exposes stable and a five-entry catalog of published main builds.
The catalog orders immutable identities by main ancestry and keeps original candidate and promotion provenance.
Upgrade captures one typed selection and persists its tracking or pinned policy with installed identity.
An unqualified pinned upgrade rejects before release access.

The managed coordinator captures Control, Host, profile, command, configuration, and receipt preimages in one typed journal.
It fences calls before settlement and coordinates the existing effect owners under admitted locks.
Stopped state and explicit restart use separate finite strategies; the journal still decodes its retained `HOT_RELOAD` strategy value.
Pending recovery precedes selection and consumes only the recorded transaction.
Unresolved restoration preserves the fence and returns `RECOVERY_REQUIRED`.
The public bootstrap uses this complete-product selection contract rather than the legacy component installer.
These source rules do not prove a new installed semantic session.

## Detailed distribution routes

- [Installation and managed files](../distribution/installation-and-managed-files.md) explains Control replacement, product activation, and typed state rejection.
- [Native management and connections](../distribution/management-and-connections.md) explains public command scope and agent connections.
- [Upgrade, shutdown, and reset](../distribution/upgrades-shutdown-and-reset.md) traces guarded lifecycle transitions.
- [Release candidates and promotion](../distribution/release-candidates-and-promotion.md) explains immutable source and artifact proof.

The public `kast query plan` command admits a RUN request before installation composition.
That request-only path supplies scope advice without starting an installed host.
