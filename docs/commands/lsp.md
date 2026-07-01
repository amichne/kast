---
title: LSP Command
description: Start Kast's Language Server Protocol adapter over stdio.
icon: lucide/plug
---

# LSP Command

`kast agent lsp --stdio` starts the Language Server Protocol adapter. It is the
command used by repository Copilot package files and other LSP-aware hosts.

## Start the adapter

The adapter reuses the same backend selection model as the rest of the CLI.
Pass `--workspace-root` when the host launches outside the repository root.

```console title="Run LSP over stdio"
kast agent lsp --stdio --workspace-root "$PWD"
kast agent lsp --stdio --workspace-root "$PWD" --backend=headless
kast agent lsp --stdio --workspace-root "$PWD" --backend=idea
```

The command writes LSP-framed messages on stdout. Do not wrap it in tools that
expect normal human text output.

## Repository integration

Most developers should not hand-write LSP configuration. Run
`kast agent setup copilot` from the repository root and let Kast write the managed
`.github/lsp.json` and extension files for the active CLI version.

```console title="Install the managed LSP package"
cd /path/to/your/repository
kast agent setup copilot --force
```

Use `kast ready` after installation when a host cannot find the binary,
repository files, or expected plugin/backend state.

## Troubleshooting

LSP startup failures are usually install or backend failures. Verify the same
workspace outside the host first.

```console title="Verify outside the LSP host"
kast ready
kast developer runtime status --workspace-root "$PWD"
kast agent call health --params '{}' --workspace-root "$PWD"
```

If those commands pass, inspect the host's LSP logs for command path,
workspace-root, backend, and stdio framing errors.
