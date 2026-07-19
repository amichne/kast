# ADR 0029: Processless development-machine authority

Status: Accepted

Date: 2026-07-18

## Context

Kast's revision-coherent local-development authority made each checkout own a
CLI, a portable headless IntelliJ runtime, agent resources, receipts, immutable
generations, runtime state, rollback, and removal. On a developer machine this
duplicates large JVMs across linked worktrees and creates several authorities
for one installed toolchain.

The desired local boundary is smaller: one active CLI revision should own the
compatible IDEA plugin and agent resources for the machine. Worktrees need
only exact-root IDEA runtime leases and plugin-produced workspace metadata.
They must not own installations, resource copies, or headless JVMs.

We considered installing a macOS LaunchAgent to keep that machine state
reconciled. Reconciliation is not continuous work: it happens after install or
development refresh, it must not replace a plugin while IDEA is open, and no
consumer requires a resident socket, event stream, or low-latency callback. A
resident process would add plist installation, crash/restart policy, logging,
permissions, upgrade coordination, and another executable lifetime without
making the closed-IDE transaction safer.

This decision supersedes ADR 0024. It supersedes ADR 0027 where readiness or
repair depends on local generations or worktree resource projections. It
supersedes ADR 0028 (exact-root leases) where leases select or own headless
runtimes. It supersedes ADR 0028 (unsigned GitHub IDEA distribution) where the
IDE or a Homebrew receipt, rather than the active machine manifest, selects
the developer-machine CLI/plugin pair. Release artifact production and Linux
headless distribution remain unchanged.

## Decision

Kast has one processless development-machine authority on macOS. The public
CLI surface is:

```console
kast machine status
kast machine activate --idea-plugin <zip>
kast machine reconcile [--idea-plugins-dir <directory>]
```

`activate` stages and atomically selects one closed bundle containing the exact
running CLI, IDEA plugin ZIP, provider-neutral Kast skill, and Codex adapter.
Its strict manifest records the digest of every selected file and resource
tree. A stable user-level `kast` symlink points at that selected CLI. Unknown
machine-root contents and non-symlink command collisions fail closed.

`reconcile` is a synchronous command. It first verifies every manifest digest,
then, only while IDEA is closed, replaces the installed Kast plugin from the
selected ZIP. It selects the machine skill globally and asks Codex's native
plugin CLI to select the bundled marketplace/plugin when Codex is installed.
It does not write worktree resource copies. The macOS installer and
`refreshDevelopmentMachine` run activation followed by reconciliation before
reporting success.

No launchd plist, resident daemon, socket, watcher, heartbeat, background
poller, or automatic open-IDE mutation is installed. `machine status` is a
read-only manifest check. A future resident service requires a new ADR that
identifies a continuous requirement that synchronous reconciliation cannot
satisfy.

macOS never starts a headless IntelliJ JVM. Explicit headless runtime requests
fail with a typed `HEADLESS_LOCAL_UNSUPPORTED` outcome. Linux release and CI
headless runtimes remain supported and keep their release packaging boundary.

Workspace preparation owns only `.kast/setup/workspace.json`. An agent lease
is always an exact-root IDEA lease: acquisition borrows one ready plugin-hosted
runtime, status revalidates the same root, process, and environment identity,
and release never starts or stops IntelliJ. The public lease command has no
backend selector.

The active machine bundle is the only developer-machine source of CLI/plugin
and agent-resource version identity. Worktrees, Codex homes, plugin caches,
Homebrew receipts, and local-generation prefixes are not competing authorities.
Codex remains a thin CLI adapter; native Codex plugin commands own its global
installation projection.

## Source ownership

| Contract | Owner | Validation |
| --- | --- | --- |
| Machine activation, strict manifest, and reconciliation | `cli-rs/src/machine.rs` and `cli-rs/src/cli/machine.rs` | `cli-rs/tests/machine_daemon_smoke.rs` |
| Development refresh graph | `build.gradle.kts` | `.github/scripts/test-local-development-refresh-contract.sh` |
| macOS installation transaction | `install.sh` | `.github/scripts/test-macos-installer-contract.sh` |
| IDEA plugin selection of the active CLI | `backend-idea/` | `:backend-idea:test` |
| Metadata-only worktree preparation | `backend-idea/.../PluginWorkspaceBootstrap.kt` | IDEA project-open profile tests |
| IDEA-only exact-root leases | `cli-rs/src/runtime/lease.rs` | `cli-rs/tests/workspace_lease_smoke.rs` |
| Bundled provider-neutral and Codex resources | `cli-rs/resources/` | packaged-content and Codex plugin contracts |

## Consequences

Developer machines hold one IDEA runtime per open worktree because IntelliJ
owns those project instances, but no additional headless JVM per worktree. A
development refresh is one explicit, recoverable transaction and leaves no
resident Kast process behind.

Plugin updates require closing IDEA. That interruption is explicit and safer
than replacing loaded plugin files. Reconciliation does not react
automatically to later external mutation; `kast machine status` diagnoses
drift and the operator reruns `kast machine reconcile`.

The former local-generation commands, receipts, prepared-generation archive,
checkout guidance projection, rollback, removal, cold-start policy, and
developer-machine headless lifecycle are deleted rather than deprecated.
