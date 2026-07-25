# ADR 0032: macOS IDEA golden pathway

Status: Accepted

Date: 2026-07-23

## Reason this record remains

The supported IDE matrix and window/process behavior are workstation policy,
not facts that can be inferred from one implementation module.

## Decision

The session-start path is:

```text
exact-root admission
  -> reuse or background-open one supported IDE
  -> plugin bootstrap
  -> Gradle import and smart mode
  -> reference index
  -> READY
```

Supported hosts are IntelliJ IDEA 2026.2/build 262 and Android Studio
2026.1.2/build 261. The common plugin targets baseline 261 with JVM 21 bytecode;
the build uses Java 25.

An exact root already open is reused. Otherwise Kast may ask the sole
compatible running host to open a new frame or background-launch the sole
supported installed host. Ambiguous hosts fail closed. A missing or mismatched
plugin returns `IDEA_PLUGIN_UPDATE_REQUIRED`; an unsupported host returns
`IDEA_VERSION_UNSUPPORTED`.

Warm opens use the authenticated one-shot local open request. Cold opens use
`open -j -g -a <app> <root>`. Kast does not use `open -n`, focus APIs,
Accessibility, AppleScript, or private window APIs.

Canonical worktree roots have isolated descriptors, leases, sockets, and
indexes. `INDEXING` is sufficient for session bootstrap once the exact runtime
is reachable; semantic commands still require `READY`.

Setup compares plugin bytes before requiring an IDE restart. A matching plugin
does not interrupt the host. A real plugin change returns
`IDE_RESTART_REQUIRED` unless the interactive installer receives confirmation.

## Source and proof

- `cli-rs/src/runtime/idea_launch.rs`
- `cli-rs/src/runtime/workspace_admission.rs`
- `backend-idea/`
- `packaging/jetbrains/runtime-compatibility.json`
- `.github/scripts/test-runtime-compatibility-contract.sh`
- `scripts/smoke-macos-idea-golden-path.sh`

Changes to the host matrix, exact-root opening, focus/window policy, readiness
threshold, or restart policy must update this record.
