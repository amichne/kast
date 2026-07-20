# Codex Plugin

The `kast@kast` Codex plugin teaches compiler-backed `kast agent` commands and installs two default-discovered advisory hooks through one local launcher.

- `SessionStart` on `startup` asks Kast to open the exact `cwd` with its configured IntelliJ IDEA or Android Studio fallback. Missing, outdated, or unhealthy tooling is reported as context.
- `PostToolUse` after a successful `apply_patch`, `Edit`, or `Write` checks exact-root IDEA status. When healthy, it runs a separate diagnostics request for every `.kt` and `.kts` path from that tool input.

Hook failures never deny an edit or stop a turn. The plugin does not persist session state, baselines, changed-file ledgers, or diagnostics evidence. Repeated qualifying writes may run diagnostics again.

The IntelliJ Kast settings page controls the hooks globally. The same values can be edited in the global Kast config:

```toml
[codex.hooks]
enabled = true
sessionStart = true
postToolUse = true
```

The master switch disables both hooks; each event switch can also be disabled independently. All three default to `true`.

Mutations are plan-first and synchronous: Kast applies the edit, runs diagnostics for the resulting contents, and returns exit code 0 only for a green terminal result. Failures expose a compact structured code, message, and actionable details.
