# Codex Host Boundaries

- This package owns how a Codex client reaches the shared broker. Host selection must not create a second broker, tool catalog, or semantic execution path.
- `admission` refines raw executable and argument inputs before any process, socket, or stdio effect begins. Preserve admitted values as typed data; do not unwrap and re-parse them downstream.
- The CLI host owns its injected `--remote` transport. The App Server host owns its injected `--listen` transport. Reject caller-supplied transport overrides before adding either host-owned argument.
- Parse global and role argument segments independently. Unknown options, remote overrides, missing values, ambiguous roles, control characters, and size overflow are closed typed failures.
- Executable identity checks must fail closed on unavailable paths, recursive façade identity, I/O failure, or denied access.
- Stdio framing and process lifecycle remain bounded. Keep shutdown, interruption, upstream exit, and malformed-frame outcomes explicit; never write diagnostics to the App Server stdout protocol stream.
- Tests must cover successful role selection plus every failure that could transfer transport ownership, recurse into the façade, corrupt framing, or leak a process.
