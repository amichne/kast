---
name: kast
description: Use the installed Kast CLI for Kotlin declaration discovery, relationships, source reads, and compiler diagnostics in one exact workspace.
---

# Kast

Use the installed `kast` command for supported Kotlin work.
The persistent IntelliJ IDEA Host owns compiler authority and project state.
Separate CLI calls use that Host without a client JVM or a new daemon.
Use ordinary source tools for other languages.

Use flags for exact-name selection and one-hop relations.
Use `--kind class` for interfaces.

```sh
kast query --name OrderService --kind class --in services/orders --source-set main \
  --fields name,signature,location
kast query --ref "$exact_ref" --relation callers
```

Use `kast query --help` for flag names.
For one flag's accepted values, select its entry from the installed summary.

```sh
kast describe query --jq '.content.flags[] | select(.name == "--kind")'
```

For request documents, use the [targeted discovery commands](public-cli.md#discover-only-what-you-need).
Export a full descriptor only when the task needs the full contract.

## Select the exact declaration

1. Find the declaration with its simple name, known kind, directory, and source sets.
2. Examine its owner, signature, receiver, and location.
3. Select the exact declaration before a relationship query.
4. Keep each issued reference unchanged.

Apply known path and source-set exclusions before discovery and each source read.
The default source sets are `main` and `test`.
A directory at a call site does not establish the declaration's directory.
Examine source before an interface claim.
A cursor at a call selects the enclosing declaration. It does not select the callee.
Ambiguous overloads need a qualified stop.

## Keep completion and recovery evidence

Read the outer outcome and the result's qualifications.
A presentation page does not establish semantic completion.
An empty incomplete result does not prove absence.
A complete relation proves only its declared semantics and scope.
For a complete source-use answer, resolve imports, companion qualifiers, and unmatched sites too.
Location ranges contain UTF-16 offsets. They are not line numbers.

Use retained result commands to read produced output.
Keep the original output kind, issued result reference, and issued cursors.
Use resume only with an issued output continuation.
Never repeat semantic work to recover lost output.
Discard stale source evidence after a source change or freshness rejection.

Cancellation or timeout can leave submission, work, or effects uncertain.
Client exit alone does not prove Host settlement.
Never retry an uncertain operation automatically.
Preserve the original failure or receipt and its recovery instructions.
If Host or workspace admission rejects, state the reason and use qualified source reads.

## Optional MCP tools

`kast install <harness> --mcp` selects the optional MCP composition.
CLI and MCP share the persistent IDEA Host and semantic contracts.
Use `find` before exact relationship tasks.
Use `source` with `ANCHOR` before a body region.
Use `schema` before an advanced existing operation through `raw`.
Source changes need the user's request, an issued exact target, and explicit write admission.
Do not infer live readiness from registration or schema admission.

## Fresh sessions

A core update interrupts owned work and invalidates Host-issued references after a Host transition.
Kast refreshes guidance for its installed harness integrations.
Start a fresh agent session after installation or an update.
If the core result reports `HARNESS_REFRESH_REQUIRED`, repeat `kast install <harness>` before that session.
A standalone skill export keeps its bytes until explicit removal or another output directory.
