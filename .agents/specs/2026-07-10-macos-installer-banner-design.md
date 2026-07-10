# macOS installer banner design

## Goal

Restore the original Kast ASCII banner to the root macOS installer without
changing command behavior, machine-readable output, or the concise `verify`
and help paths.

## Design

`install.sh` will own a small `print_banner` function containing the exact
six-line `KAST` artwork previously shipped by the installer, followed by the
original tagline and repository link. The function will write only to standard
error and will reuse the installer's existing `colorize` helper so the artwork
is cyan when color is enabled and plain text when `NO_COLOR` is set.

The banner will be emitted once for the `install` and `update` commands after
argument, host, and workspace validation, and before the mutation plan. The
`verify` and `--help` paths will remain banner-free.

## Failure behavior

Banner rendering is local output only. It performs no subprocess calls, reads
no external assets, and cannot alter installer exit codes or mutation order.

## Validation

The macOS installer contract will assert that both `install` and `update`
render a stable line from the restored artwork and the original tagline. It
will also assert that `verify` and `--help` do not render the banner. Existing
no-mutation, color, IDE preflight, Homebrew-path, and sudo-rejection contracts
remain unchanged.
