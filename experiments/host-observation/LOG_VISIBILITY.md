# Capture Kast and IntelliJ logs

Use `log_visibility.py` beside the existing semantic replay tools.
The script copies local log bytes and records their source, offsets, time, and SHA-256.
It also supplies a reversible setting for DEBUG output from Kast categories.
A capture receipt proves captured bytes, not semantic completeness or a loaded logger level.

## Sources and scope

The following owners define the selected paths and records.

| Source | Owner | Meaning |
| --- | --- | --- |
| IntelliJ `idea.log` | [Hosted read configuration](../../docs/hosted-read-configuration.md) | Hosted diagnostics, transport events, and project epochs |
| `kast_transport_read`, `kast_transport`, `kast_smart_mode_wait`, `kast_hosted` | [LoggingHostedEndpointObserver.kt](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/LoggingHostedEndpointObserver.kt) | Read IDs, connections, stages, waits, and finite failures |
| `<installation>/state/broker/<profile>/service.log` | [BrokerInstallationLayout.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/BrokerInstallationLayout.kt), [PersistentBrokerService.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/PersistentBrokerService.kt) | Control service output, when that service exists |
| Per-call observation joins | [hosted_timing.py](hosted_timing.py), [SEMANTIC_REPRODUCTION.md](SEMANTIC_REPRODUCTION.md) | Existing bounded collector and explicit transport joins |

The profile uses the first 16 hexadecimal characters of SHA-256 over the selected Codex home path.
The installation root is the parent of the directory that contains the physical Kast executable.
A direct MCP or RPC session does not establish that a persistent Control service exists.
`discover` reports conventional paths and missing files.
It does not prove which process uses those paths.
Use explicit `--log` paths for another installation or sink.

Kast emits `kast_semantic_read`, `kast_hosted`, and `kast_project_read_epoch` at INFO without a debug switch.
DEBUG cannot invent more semantic evidence than the installed code emits.
Use the existing replay collector for semantic joins.
This script keeps raw exceptions and other log text beside that collector's structured evidence.

## Find the selected logs

Run commands from `experiments/host-observation` with the checked-in script and its `log_visibility.py.lock`.
The PEP 723 metadata pins Pydantic and prohibits dependency builds.
The existing project lock supplies Ruff, Pyright, and pytest for development.
Use the actual installation and Codex home paths.

```sh
uv run --locked --script log_visibility.py discover \
  --idea-log-dir "$HOME/Library/Logs/JetBrains/IntelliJIdea2026.2" \
  --installation "$HOME/.local/share/kast/installation" \
  --codex-home "$HOME/.codex"
```

The command reports `MISSING` when a selected log does not exist.
It does not convert missing output into a successful empty capture.
The [JetBrains directory guide](https://www.jetbrains.com/help/idea/directories-used-by-the-ide-to-store-settings-caches-plugins-and-logs.html) defines standard log paths and `idea.log.path` overrides.
Use the IntelliJ `Help | Show Log in Finder` action to find an overridden path.

## Capture a snapshot

Choose a new output directory whose parent already exists.
Add each selected Control log with another `--log` argument.

```sh
uv run --locked --script log_visibility.py snapshot \
  --log "$HOME/Library/Logs/JetBrains/IntelliJIdea2026.2/idea.log" \
  --out /tmp/kast-log-snapshot-001 \
  --max-bytes 8388608
```

Each output directory contains `manifest.json` and exact `segment-*.log` bytes.
The directory uses mode `0700` and each artifact uses mode `0600`.
Existing output directories reject without overwrite.
Inputs must be regular files.
Symlinks and FIFOs reject.
The script sends nothing over the network.

The byte limit applies to the whole capture in argument order.
A snapshot keeps each source's tail within the remaining allowance.
`PREFIX_OMITTED` names each source whose earlier bytes were omitted.
An empty regular file remains distinct from a missing file.
The manifest records partial lines at either end of a segment.

Add `--read-id <issued-read-id>` to index literal matches in each captured segment.
The index stores source byte offsets and keeps all raw bytes.
Each segment permits 4,096 matches before `CORRELATION_INDEX_LIMIT` qualifies the receipt.
Matches do not establish a semantic join.
An ID split between segments needs examination of consecutive raw segments.

## Watch a reproduction

Start the watch before the operation that needs diagnosis.
Wait for `STARTED` before the operation.

```sh
uv run --locked --script log_visibility.py watch \
  --log "$HOME/Library/Logs/JetBrains/IntelliJIdea2026.2/idea.log" \
  --out /tmp/kast-log-watch-001 \
  --seconds 60 \
  --interval 0.25 \
  --max-bytes 16777216 \
  --stream
```

The watch starts at each file's current end and copies later bytes.
`--stream` emits newline-delimited JSON immediately.
`STARTED` contains initial offsets.
`SEGMENT` contains metadata and a text view.
`ISSUE` identifies a gap or failure.
`MANIFEST` ends the stream with the final receipt.

Raw files preserve multiline exceptions and invalid UTF-8 bytes.
The text view replaces invalid UTF-8 and escapes control characters through JSON.
Without `--stream`, standard output contains only the final manifest.
The interval permits 0.1 through 60 seconds.
A watch permits at most 600 seconds and 64 MiB.
A byte limit or interrupt ends the capture with an explicit qualification.

The watch drains an open inode before a replacement file.
`ROTATED` remains a qualification because a writer can append to the old inode after that drain.
An offset decrease or changed 64-byte anchor produces `TRUNCATED_OR_REWRITTEN`.
Polling cannot detect every rewrite, including a rewrite that preserves the sampled anchor and size.
The script does not claim a consistent snapshot of an active writer.
Source bytes, time, offsets, and qualifications remain available for comparison.

## Enable DEBUG output

The installed IntelliJ build `IU-262.10968.63` supplied the examined logging mechanism.
Its `LoggerConfigFromSystemProperties` reads comma-separated `idea.log.debug.categories`.
`LogLevelConfigurationManager` applies these categories during application state load.
Both classes reside in `lib/intellij.platform.ide.impl.jar`.
This mechanism needs an IDE restart after a VM-option change.

Use an existing custom VM-options file selected by IntelliJ.
Do not select the bundled VM-options file.
Do not edit that file concurrently with these commands.

```sh
uv run --locked --script log_visibility.py debug-enable \
  --vm-options "$HOME/Library/Application Support/JetBrains/IntelliJIdea2026.2/idea.vmoptions" \
  --receipt-dir /tmp/kast-debug-receipt-001
```

The command adds only `io.github.amichne.kast` to `-Didea.log.debug.categories`.
It preserves other settings, existing categories, newline style, and file mode.
The receipt directory keeps the exact original bytes and hashes for both versions.
An existing Kast category or duplicate debug property rejects before a change.
`CONFIGURED` and `RESTART_REQUIRED_NOT_LOADED_PROOF` describe file state only.
Restart IntelliJ through the authorized host workflow before a claim of loaded DEBUG behavior.

For immediate changes, use the IntelliJ `Help | Diagnostic Tools | Debug Log Settings` action.
Add `#io.github.amichne.kast` and preserve the existing entries.
This [JetBrains guide](https://plugins.jetbrains.com/docs/intellij/ide-infrastructure.html) documents the live UI route.
The script does not automate that UI or change its persistent settings.
Remove only the added entry when that live experiment ends.

## Restore the script-owned change

Use the receipt from the matching `debug-enable` command.

```sh
uv run --locked --script log_visibility.py debug-restore \
  --receipt-dir /tmp/kast-debug-receipt-001
```

The command compares current bytes with the receipt before atomic replacement.
`RESTORE_CONFLICT` preserves later user edits and needs manual reconciliation.
A changed backup also rejects.
Repeated restoration reports `ALREADY_RESTORED`.
The script does not restart IntelliJ.
A restart also applies the restored VM options.

Receipts protect ordinary sequential edits, not concurrent writers between the final comparison and replacement.
Keep capture directories and VM-option backups local.
Raw logs and VM options can contain paths, source text, identifiers, or secrets.
Examine artifacts before a separate authorized upload.

## Outcomes and proof

Exit `0` means the selected capture window or configuration action finished without recorded issues.
Exit `2` identifies qualifications, missing inputs, refused changes, or CLI input errors.
`CAPTURED` never means complete semantic coverage.
An abrupt process kill or disk failure can prevent the terminal manifest.
A directory without that manifest remains an unfinished capture.

The focused cases cover byte preservation, literal CLI arguments, permissions, missing files, caps, rotation, truncation, interrupts, and restoration conflicts.
The child-process cases exercise live stream ordering and terminal receipts.
Temporary files supply controlled filesystem transitions.
These cases do not prove installed-host logger activation or a real reproduction.

```sh
uv run --locked ruff format --check log_visibility.py log-tests
uv run --locked ruff check log_visibility.py log-tests
uv run --locked pyright log_visibility.py log-tests
uv run --locked pytest log-tests -q
uv run --locked --script log_visibility.py --self-test
```

Use [the reusable evaluation checklist](EVALUATION_CHECKLIST.md) to record captured evidence and unresolved gaps for each repository.

## Installed campaign evidence

The October 24, 2026 campaign restarted IntelliJ IDEA `IU-262.10968.63` after the VM-option change.
The new process exposed the expected `idea.log.debug.categories` property.
The `HostedEndpointService` and `HostedReadDiagnostics` logger instances both reported DEBUG enabled.
A unique DEBUG marker appeared in `idea.log` with the JUL display level `FINE`.
The marker proves that the loaded logger emitted DEBUG output.
It does not add semantic evidence to the existing INFO records.

The bounded watches kept exact byte segments and reported log rotation explicitly.
Per-call captures kept semantic records, transport phases, and exact correlation outcomes beside the raw segments.
The user-requested DEBUG setting remains enabled.
The private configuration receipt preserves the original bytes for guarded restoration.

The [campaign report](DEBUG_CAMPAIGN_REPORT.md) separates loaded logger evidence, semantic results, fixture failures, and unresolved native exceptions.
