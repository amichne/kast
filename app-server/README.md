# Persistent Kast App Server

`:app-server` owns the persistent Codex broker and its client sessions. `:cli`
composes installed executables and exposes the command tree. Neither semantic
contracts nor IntelliJ runtime implementations belong in this module.

The desktop release gate is **unqualified**. See [compatibility and blockers](docs/compatibility.md)
for observed evidence, remaining client checks, and reproduction commands.

## Enable and attach

On the inspected macOS desktop build, run from the workspace to enroll:

```sh
kast app-server enable
kast app-server status
kast codex
# Alternatively:
kast codex desktop
```

Enablement records one canonical workspace under the selected `CODEX_HOME`,
installs a login LaunchAgent, starts the broker, and establishes the GUI daemon
opt-in. It rejects known conflicting overrides and unsupported desktop builds.
Desktop host-specific configuration and interactive behavior still require the
release gate below. A currently running desktop process must be restarted by its
user to pick up the login environment.

The CLI and `kast-codex app-server` attach to the same service. Per-attachment
App Server process options are rejected because the service owns that process's
configuration. `kast-codex` retains CLI argument forwarding. Client closure does
not stop the service.

```sh
kast app-server control release THREAD_ID CONNECTION_ID
kast app-server control claim THREAD_ID CONNECTION_ID
kast app-server stop
kast app-server disable
```

Status exposes bounded connection IDs and task state. A task has one controller;
claiming an occupied task conflicts, and handoff during an active turn or pending
server request fails. An observer must first successfully resume the task.

Stop publishes its suppression marker under the startup lock, removes only the
identity-proven service, and waits for retirement. Attachments cannot restart it
for that login. Explicit enable or the next login bootstrap clears suppression.
Disable additionally removes the Kast-owned login agent and restores only
Kast-owned daemon environment changes. Enrollment and invocation evidence remain
on disk; disable does not erase execution history.

## Ownership and recovery

```mermaid
flowchart LR
  Desktop[Desktop client] --> Public[Standard Codex control socket]
  CLI[CLI and stdio attachments] --> Public
  Public --> Hub[Service-owned sessions and controller routing]
  Hub --> Upstream[One private Codex App Server process]
  Hub --> Fence[Durable invocation fence]
  Fence --> Kast[Installed Kast CLI]
  Kast --> Workspace[Enrolled workspace runtime]
```

Each connection completes its own initialize/initialized exchange and retains its
own upstream request-ID space. A bounded outbound queue prevents one stalled
upstream writer from blocking other connections. Only the authoritative task
stream fans out notifications to observers; equal text deltas remain separate
events. Native server requests retain their responsible client and an explicit
response/resolution mapping. Kast never chooses an approval decision.

One workspace execution mutex serializes Kast reads and mutations while the
existing runtime remains authoritative for semantic validation. Workspace
admission follows canonical filesystem identity. Unenrolled thread creation and
unknown thread resume/fork traffic receive no Kast catalog or binding.

The service stores enrollment, thread/catalog bindings, and digest-only
invocation intent under `CODEX_HOME/broker`. It does not store conversation
history. Intent reaches durable storage before execution; a recovered started
invocation is uncertain and cannot run again automatically. Completed duplicate
calls within a live generation share one result. A restarted service rejects a
completed duplicate instead of fabricating its missing result.

Lost upstream work enters reconciliation-required state. Matching terminal turn
history or authoritative idle status can restore task control. This does not
clear an uncertain mutation's durable fence. Corrupt enrollment, thread bindings,
or invocation journals fail closed. The journal caps at 4,096 invocation records;
capacity exhaustion is an explicit rejection, not automatic eviction of replay
protection. There is no destructive journal-reset command.

## Presentation and evidence

Kast returns two native text content entries: an operation/status summary and the
complete bounded structured outcome. `dynamicToolCall`, arguments, correlation,
status, duration, and content remain native in live events and history. There are
no fabricated MCP, file-change, or commentary items. Existing native tools and
client-owned responders keep their protocol identities.

Status separates transport, protocol, catalog, semantic readiness, and desktop
qualification. Startup and invocation logs contain typed stage/outcome evidence.
A bounded session trail records admission, handshake, detach, transport failure,
reconciliation, and invocation outcome without source or argument payloads.

```sh
./gradlew :app-server:test :cli:test :cli:nativeTest verifyKastArchitecture
./gradlew installedProductTest installedCodexHostTest
./gradlew :app-server:generateCodexHostIntegrationManifest
```

The installed tests use disposable homes and remove their launchd enablement.
They test real Codex discovery and stdio attachment. They do not establish desktop
UI compatibility. The test resource `kast-schema.json` is the installed Kast
`--schema` boundary snapshot used to keep protocol tests independent of CLI
implementation imports.
