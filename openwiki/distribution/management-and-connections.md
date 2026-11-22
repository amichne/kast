---
type: System Explanation
title: Native management and agent connections
description: Explains the public native command, passive status evidence, managed workspace admission, skill export, and dotagents agent connections.
tags:
- kast
- distribution
code_sources:
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementCommand.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementExecution.kt
- path: packaging/composed_bundle.py
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementStatus.kt
- path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/ConfigurationSchemaDocument.kt
- path: distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ManagementCliTest.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/SkillExport.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementUninstall.kt
- path: docs/public/reference/integrations.mdx
sources:
  - id: openwiki-source-5e92c81a46b459b558f8901e
    resource: repo://.github/scripts/release/product_candidate.py
  - id: openwiki-source-e61ce41a823368fa4e25eb84
    resource: repo://.github/scripts/release/product_producer.py
  - id: openwiki-source-f02c30e87f3db4d071a7be5b
    resource: repo://.github/scripts/release/publish_developer.py
  - id: openwiki-source-9334c5b6f4494897095d1968
    resource: repo://cli/src/main/control-launchers/bin/kast-mcp
  - id: openwiki-source-581a5d52446c24f31ab1ed9d
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/direct/WorkspaceSeedHost.kt
  - id: openwiki-source-7904a856aedff77e554352c6
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/KastControlMain.kt
  - id: openwiki-source-e18a27e9e0fbd580f3d323e9
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerFiles.kt
  - id: openwiki-source-97c3915cf78feeb35ecec04a
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerProgress.kt
  - id: openwiki-source-a3d400e2149c937d7b2dafff
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerQueryLease.kt
  - id: openwiki-source-22e272ee16fcff0c22f636ab
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerStateAdmission.kt
  - id: openwiki-source-dc8df2b070b8c6ba7da7a8f9
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerStore.kt
  - id: openwiki-source-6ead538479055040a6fa54d0
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/InstalledProductRuntime.kt
  - id: openwiki-source-2141db5e92ccbb5de75a1544
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt
  - id: openwiki-source-b30c7b16cf2f49971ca55950
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedUpgrade.kt
  - id: openwiki-source-8631d89978b8c4768c0c151b
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedWorkspaceLifecycle.kt
  - id: openwiki-source-ecb21af09f4a49f9e40342dd
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedWorkspaceQuery.kt
  - id: openwiki-source-7cea1ff036129ab5e1cebfe3
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedWorkspaceRemoval.kt
  - id: openwiki-source-dd26d5b3df9520fb1b710d75
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedWorkspaceStatus.kt
  - id: openwiki-source-1d4cdf342ee112e0d9d87924
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementReceiptAdmission.kt
  - id: openwiki-source-a62e12ff14ead337deede61b
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementUninstall.kt
  - id: openwiki-source-dfb88490277ececa651be1f8
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PublicInstallationReset.kt
  - id: openwiki-source-e62300b4b70eef650b59f1db
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/RejectedWorkspaceRemoval.kt
  - id: openwiki-source-656a2bd4859ff8a64d431829
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/SkillExport.kt
  - id: openwiki-source-d9ace9189c53b7c82c5ed60c
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/WorkspaceReclamation.kt
  - id: openwiki-source-0675d10d4917bf4cb2dc6de8
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/WorkspaceSeedClone.kt
  - id: openwiki-source-e140285f42db726bccbc4e01
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/WorkspaceSeeding.kt
  - id: openwiki-source-e7fd130e6641f6c75e3cde3f
    resource: repo://distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/BuildSelectionTest.kt
  - id: openwiki-source-617221ff7ee3e7082b7ba9da
    resource: repo://distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/LifecycleIngressTest.kt
  - id: openwiki-source-d95d75ec0e14dda7faf63dbf
    resource: repo://distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ManagedUpgradeSelectionFileTest.kt
  - id: openwiki-source-1c6c64417570ecf53e6c68a2
    resource: repo://docs/public/reference/integrations.mdx
  - id: openwiki-source-714a256a14f92900e3bd2e7e
    resource: repo://packaging/internal/composition/composed_release.py
  - id: openwiki-source-9f2a730e7185b57c14ac08e8
    resource: repo://packaging/internal/installation/managed_installation.py
  - id: openwiki-source-496462e31bbb3fc9156abfe9
    resource: repo://packaging/internal/installation/managed_release.py
  - id: openwiki-source-ba9aa96ff29fd40c40dce826
    resource: repo://packaging/internal/verification/product_acceptance.py
  - id: openwiki-source-6904899612d0ede016a5965a
    resource: repo://packaging/internal/verification/product_lifecycle_probe.py
generated: { by: "codex", at: "2026-11-22T20:53:36.158Z" }
verified:
  - by: openwiki/0.9.0
    at: 2026-11-22T20:53:36.158Z
---

# Native management and agent connections

## The public command owner

`KastManagementMain` parses the public `kast` command.
Its management commands are `config`, `status`, `stop`, `install skill`, `versions`, `upgrade`, and `uninstall`.
The `query`, `result`, `source`, `diagnostic`, and `describe` command groups dispatch separately to the public semantic CLI.
Public container, resume, disconnect, reinstall, connect, and plugin commands are absent.
`KastControlMain` selects management and the private transport entries behind one Control executable.
Thin private launchers select their existing command and lifecycle owners.
Their internal command families do not add public semantic commands.

Passive status reads installed receipts and available runtime evidence.
The output separates installed and loaded identities, update policy, pending recovery, live connections, hosted services, workspaces, and in-flight requests.
Unavailable evidence stays unavailable with its reason.
Status does not start Java, prepare a workspace, or contact a release server to manufacture readiness.

## Managed workspace admission

`ManagedWorkspaceQuery` admits the installed product locally for each explicit semantic request.
The first preparation request creates a trust grant for its exact `CanonicalRoot` and private Host state.
Overlapping roots reject. Retained reads require an existing record; discovery and passive hooks do not grant trust.
The `kast-workspaces` sibling of the Control management root stores profiles outside the replaceable installation.
Each generation has private settings, indexes, logs, HOME, and default Gradle state.
Saved configuration can select an existing external Gradle home. Inherited credentials remain available; this is not an OS sandbox.

Durable record admission preserves the configured Gradle path even if that directory disappears.
Launch binding checks its current availability and physical identity, returning `INVALID_GRADLE_HOME` when those facts cannot be established.
An ordinary user-owned `.DS_Store` does not count as a workspace entry; linked or foreign entries still reject admission.
Global lifecycle rejection names the workspace entry and its finite cause.
Explicit uninstall can remove an inactive rejected record under its exclusive lease after private-state and mutation checks.
That recovery does not permit retirement of an unproven live Host or deletion outside the private profile.

A request owns its reader lease, rather than an entire MCP session.
Installed product identity is rechecked before generation publication and binding.
Queries neither acquire release assets nor fall back to a desktop installation.
`stop` selects the current workspace, an explicit workspace, or all recorded workspaces.
It holds exclusive idle leases while retiring selected Hosts, but writes no persistent stop fence.
The next explicit semantic request can start them again.
Its text result names each stopped workspace, so a broad stop shows which Hosts a later request must start.

Admission opportunistically reclaims vanished-root profiles only after exact generation retirement and private evidence checks.
An exterior `.removing-ID.json` receipt survives interrupted deletion.
Unreadable cleanup candidates produce bounded rejection evidence rather than failing an unrelated semantic request.
See [retirement and recovery](upgrades-shutdown-and-reset.md) for the deletion boundary and remaining qualification gaps.

## Workspace index seeding

Only new workspace creation with `KAST_WORKSPACE_DEPENDENCY_SHARING=SHARED` attempts index seeding.
`ContainerStore` stages private state, seeds `state/system`, and writes creation evidence before atomic publication.
Existing containers and upgrade, repair, or generation rebuild paths keep their recorded policy and home without seeding.
See [configuration](../contracts/configuration.md#creation-time-dependency-sharing) for the four-way Gradle-home selection and legacy default.

### Donor admission

A donor can belong to any workspace, not just a sibling repository worktree.
Cheap selection requires the current generation, matching runtime archive and platform, the same canonical Gradle home, and no `.removing-ID.json` receipt.
The admitted generation must contain a physical system directory and a supported Host exit record with `SMART` and a canonical UUID `hostLifetime`.
`reason` remains `IDLE` or `SETTLED`; neither alone proves completed exit or flushed storage.
The private state location binds the record to its generation and runtime, without extra DTO fields.
Candidates are ordered by newest exit-record filesystem modification time, then ascending container ID.

The donor's non-blocking exclusive query lease spans generation and record revalidation, stopped-Host inspection, and the whole clone.
The platform-derived IntelliJ configuration `.lock` must be absent, and the existing lifecycle transport must report `HOST_UNAVAILABLE`.
An answering endpoint, leftover lock, or unproven observation skips the donor. Selection never starts or stops a donor Host.
This combines evidence; endpoint unavailability alone is insufficient.
The historical lock-removal/flush ordering experiment covered one macOS/IntelliJ build, not every supported release or platform.

### Clone and creation evidence

`cloneWorkspaceSystem` supports macOS APFS on the same FileStore, using native `clonefile` per regular file.
It clones the whole `state/system` tree, not the sibling `state/host-exit.json`.
The physical system root must belong to the current user with mode `0700`; every descendant must share its owner.
Source admission rejects links, foreign owners, special files, and more than 250,000 entries.
There is no full-copy or hard-link fallback. Running Hosts never share writable index storage.
The seed grants no semantic authority: ordinary project scans, imports, and readiness checks still apply.

`state/workspace-seed.json` records `schemaVersion: 1` and `result`, whose `type` selects the closed outcome.
The bounded progress event uses `event: kast_workspace_seed` with the same result and no paths.

| Result type | Creation effect and retained fields |
| --- | --- |
| `NOT_REQUESTED` | `PRIVATE`; empty initial system state. |
| `SEEDED` | Clone completed; `donor` is the donor container ID. |
| `NO_ELIGIBLE_DONOR` | No donor passed selection; continue empty. |
| `DONORS_BUSY` | Donor query-lease contention occurred without a usable donor; continue empty. |
| `UNSUPPORTED_FILESYSTEM` | Unsupported platform or filesystem; continue empty after successful cleanup. |
| `CLONE_FAILED` | `stage` is `SOURCE_ADMISSION` or `CLONE`; continue empty only after successful cleanup. |
| `SELECTION_REJECTED` | `failure` retains a finite `ContainerFailure`; continue empty. |
| `CLEANUP_REJECTED` | `failure` retains the cleanup failure; reject publication. Only progress records this result. |

Receipt writing must succeed before publication. Partial clones are never published, and cleanup failure cannot become an empty success.
Cleanup re-admits the recipient's private physical system root before deleting partial clone entries, without following an aliased root.
Passive status exposes the recorded sharing policy, Gradle home, saved-configuration match, and an admitted seed receipt or unavailable evidence.
Missing legacy receipts remain unavailable; they are not synthesized as `NOT_REQUESTED`.
Phase 1 native qualification and performance remain pending. Historical seeded-start measurements do not qualify this implementation.
Immutable seed snapshots remain outside Phase 1.

## Agent connections

`kast install skill [--output <parent>]` exports the complete canonical skill directory, by default under `./.agents/skills`.
An identical directory returns `ALREADY_INSTALLED` without replacement. A different, incomplete, or linked directory rejects and stays unchanged.
Any other `install` target rejects and directs the caller to dotagents.
Kast writes no agent harness configuration, plugin cache, or provider registration.
An agent receives the exported skill and one MCP server that runs `kast --internal-entrypoint=mcp`, both declared in the repository's `agents.toml` and applied by dotagents.
MCP registration and `tools/list` grant no trust and start no Host.
A fresh agent session is necessary after setup.
The [agent setup guide](../../docs/public/reference/integrations.mdx) owns the exact `agents.toml` entries.

## Selection and passive status

`kast versions` lists the stable selection and five newest selectable published main builds.
Upgrade requests refine into one typed release selection and update policy before artifact effects.
Preview and stable select complete product records, not independently mixed Control and Host versions.
Exact repair reads a published product's recorded selection even when runtime bytes need repair; ordinary requests additionally require an admitted runtime.
A local product rejects exact repair with `LOCAL_ARTIFACT_REQUIRED` before installer or release effects.
The wrapper writes the captured selection in a physical temporary directory before invoking the installer bridge.
An aliased temporary-directory setting therefore still produces a physical record path.
An unqualified upgrade keeps its recorded tracking policy and rejects a pinned policy before release access.
Pending recovery takes precedence over selector resolution and invokes only the manifest-owned recovery bridge.
Bridge admission requires the complete Control payload inventory to match the manifest, with unique paths and verified bytes.
The wrapper preserves the child terminal result and does not select another release after recovery.

The parser admits `uninstall --force [--json]` for legacy state only.
Under an exterior workspace lifetime lease, either managed store or `product-selection.json` rejects force detachment with `MANAGED_STATE_REQUIRES_UNINSTALL`.
Managed products use ordinary uninstall with its recovery journal.
Uninstall holds the workspace store-removal lease while it admits the receipted payload and retires the installation.
It changes no agent configuration.

Qualification separates component assembly checks from installed-product acceptance.
`composed_release._qualify_release` verifies exact archives and passive shipped-Control discovery, without a test-only native executable.
Schema 2 `COMPONENT_ASSEMBLY` receipts bind source, bundle, runtime, and Control digests; the producer hashes Control itself.
Installed acceptance owns compiler queries, result rejection, Host identity, Linux libraries, stop cleanup, and re-analysis after edits.
The producer seals both component-qualified targets into one immutable build record before installed-product acceptance.
Acceptance receipts remain detached from that record. Publication admission requires both receipts and preserves the sealed bytes.
The acceptance baseline needs sealed component qualification, not prior publication.
Assembly-qualified baselines require installed acceptance; published native-qualified baselines retain their original admission contract.
It can share the candidate's source revision, but each target needs a different actual bundle/runtime/Control composition.
Exact repair cannot stand in for upgrade evidence. CI admits the baseline's exact retained record and successful sealing job before the native acceptance matrix.
This staged source contract replaces the earlier circular setup route, but no successful native Host acceptance is established here.
See [distribution qualification](../modules/distribution.md) for the stage owners and evidence limits.

Schema 3 management receipts preserve the installation root, executable digest, channel, update policy, installed build identity, and product reference.
They carry no agent configuration.
Pinned policy needs the same recorded build identity.
Passive status reads the selected IDEA and profile evidence without Host preparation or release access.
Unavailable evidence keeps its finite reason.

Fixture tests establish finite parsing, ownership, and recovery outcomes.
Native adapter qualification needs separate process evidence.
These mechanisms alone do not establish that a running IDEA loaded the selected Host or answered semantic queries.

## Focused tests

- [ManagementCliTest.kt](../../distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ManagementCliTest.kt)
- [ManagedUpgradeSelectionFileTest.kt](../../distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ManagedUpgradeSelectionFileTest.kt)

## Source owners

- [KastManagementMain.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt)
- [ManagementCommand.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementCommand.kt)
- [ManagementStatus.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementStatus.kt)
- [SkillExport.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/SkillExport.kt)
- [ManagementReceiptAdmission.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementReceiptAdmission.kt)
- [ManagementUninstall.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementUninstall.kt)
- [ConfigurationSchemaDocument.kt](../../distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/ConfigurationSchemaDocument.kt)

## Read next

- [Direct MCP](../transport/direct-mcp-and-tool-rpc.md)
- [Codex hosts](../transport/codex-hosts-and-framing.md)
- [Guarded reset](upgrades-shutdown-and-reset.md)
