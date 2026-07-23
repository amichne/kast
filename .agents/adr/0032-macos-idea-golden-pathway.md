# ADR 0032: macOS IDEA golden pathway

Status: Accepted

Date: 2026-07-23

## Context

The normal macOS path starts in Codex, often in a new worktree, while a
developer already has an IDEA-based IDE open. Requiring the developer to open
the exact directory, choose an IDE window policy, wait for import, and then
retry Kast makes the normal path unreliable. Unconditionally closing every
JetBrains IDE for setup is also unnecessary when the installed plugin already
matches the release.

This record supersedes the manual-open next action in ADR 0019, the
unconditional closed-IDE plugin clauses in ADRs 0013 and 0029, and ADR 0029's
rule that exact-root lease acquisition never opens an IDE. ADR 0031 remains the
sole setup authority.

## Decision

The macOS golden pathway is:

```text
Codex or worktree
  -> exact-root runtime admission
  -> reuse or background-open one supported IDE
  -> plugin bootstrap
  -> Gradle link or refresh
  -> IDEA smart mode and Kotlin admission
  -> Kast reference index
  -> READY
```

The supported host matrix is exact:

| Product | Supported platform | Build toolchain | Plugin bytecode |
| --- | --- | --- | --- |
| IntelliJ IDEA | 2026.2, baseline 262 | Java 25 | JVM 21 |
| Android Studio | 2026.1.2, baseline 261 | Java 25 | JVM 21 |

The common plugin is compiled against baseline 261, verified against both
products, and uses no API that fails verification in either product. A product
or baseline outside this matrix returns `IDEA_VERSION_UNSUPPORTED`. JVM 21
bytecode is required because Android Studio 261 runs on JBR 21 even though
Kast's build uses the Java 25 toolchain required by the 2026.2 build.

### Workspace states

Kast treats canonical worktree roots as isolated runtime identities while
sharing only the IDE application process.

| Starting state | Behavior |
| --- | --- |
| Exact root already open | Reuse its descriptor without opening, focusing, moving, or marking the project. |
| Existing root closed | Ask the sole compatible running IDE plugin to force a new project frame, or background-launch the sole selected installed app. |
| New worktree | Permit missing workspace metadata, open the exact root, let the plugin create metadata, then validate it. |
| Plugin missing or different | Do not open the project. Return `IDEA_PLUGIN_UPDATE_REQUIRED`. |

Runtime descriptors, leases, metadata, sockets, and indexes remain isolated by
canonical root. No index is shared between worktrees.

### Host selection and opening

The host order is an exact-root descriptor, an explicit application override,
the sole compatible running IDE process, then the sole supported installed
bundle. Multiple eligible processes or bundles return `IDEA_HOST_AMBIGUOUS`.

Warm opening uses the authenticated local `runtime/open-project` operation.
Its canonical root and one-shot UUID must match a private, short-lived request
under `KAST_HOME/state/runtime/idea-open-requests`. The selected process
atomically consumes that request. It uses
`OpenProjectTask.forceOpenInNewFrame`, so the current project is not closed and
the developer's Ask/Current Window preference cannot redirect the operation.

Cold opening resolves one exact app and uses:

```console
open -j -g -a "/path/to/Selected IDE.app" "/canonical/project/root"
```

Kast does not use `open -n`. Before a cold open, it verifies that the selected
product profile contains plugin bytes matching the active release.

`-j -g` requests background launch and suppresses normal foreground
activation. Kast never calls focus APIs. For a warm open, it copies the active
IDE frame's public bounds and extended state only when the new frame is not
active. Native macOS project tabs remain controlled by the user's JetBrains
tab preference. Fullscreen and display inheritance are best effort because
Kast does not use Accessibility, AppleScript, or private window APIs.

### Provenance and visible state

Only a project actually opened by Kast receives project-lifetime provenance.
The plugin shows `Kast Agent` in the existing status widget and applies a
best-effort frame-title suffix. Exact projects that were already open are not
marked. Kast does not write project icon files or overwrite persistent user
appearance.

### Readiness and signals

The session-start hook invokes Kast exactly once and accepts `INDEXING` as soon
as the exact runtime is reachable. Semantic commands continue to require
`READY`.

The runtime state order is:

```text
metadata/bootstrap
  -> INDEXING
  -> Gradle completion
  -> IDEA smart mode and Kotlin semantic admission
  -> Kast reference-index completion
  -> READY
```

A terminal failure in import, semantic admission, or reference indexing
becomes `DEGRADED` with one actionable cause. Progress and success are silent.
The plugin deduplicates terminal Kast notifications for the project lifetime.
It does not suppress Git, shallow-clone, IDE, or third-party notifications.

### Plugin setup

Setup compares installed plugin bytes before checking whether an IDE is
running. A matching plugin is usable without interruption. A real plugin
change returns `IDE_RESTART_REQUIRED` in noninteractive setup. The interactive
macOS installer may close the sole selected IDE after confirmation, retry the
transaction, and relaunch that same app with `open -j -g`.

New Recommended installations enable `runtime.ideaLaunch.enabled`. When an
existing default config has no launch choice, setup adds the Recommended
choice. An explicit `runtime.ideaLaunch.enabled = false` remains authoritative.

## Exclusions

This decision does not create worktrees, change Linux/headless behavior, share
indexes, globally suppress notifications, or use Accessibility, AppleScript,
private macOS APIs, or manual project-window manipulation.

## Source ownership and validation

| Contract | Owner | Validation |
| --- | --- | --- |
| Host selection, one-shot request, and launch disposition | `cli-rs/src/runtime/` | focused runtime tests |
| Idempotent plugin setup | `cli-rs/src/install/idea_plugin.rs`, `install.sh` | setup and macOS installer tests |
| Local open operation and provenance | `analysis-api/`, `analysis-server/`, `backend-idea/` | IDEA tests |
| Gradle, semantic, and reference-index readiness | `backend-idea/` | IDEA backend tests |
| Supported product matrix | `backend-idea/build.gradle.kts`, `packaging/jetbrains/` | plugin verifier and compatibility contract |
| Published workflow | `AGENTS.md`, `docs/` | docs content contract and Zensical build |
| Real workstation matrix | `scripts/smoke-macos-idea-golden-path.sh` | open, closed, and new-worktree smoke runs |

The release gate runs focused Cargo tests, `:backend-idea:test`,
`:backend-idea:buildPlugin`, `:backend-idea:verifyPlugin`, compatibility and
installer contracts, documentation contracts, and the real macOS smoke matrix.
