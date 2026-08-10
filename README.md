[![CI](https://github.com/amichne/kast/actions/workflows/ci.yml/badge.svg)](https://github.com/amichne/kast/actions/workflows/ci.yml)

# Kast

Kast gives coding agents compiler-backed Kotlin and Gradle evidence through one
exact-root indexer. It resolves exact symbols, navigates
relationships, plans semantic edits, and keeps evidence limits visible.

## Install or update

One command installs, replaces, repairs, upgrades, or downgrades Kast. Every
platform bundle contains the native CLI and its matched private indexer:

```console
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"
```

The bootstrap delegates administrative setup to the private release-local
`libexec/kastctl`, activates the release under `KAST_HOME` (default
`~/.local/share/kast`), and installs the release-matched Kast resources for
each detected Codex, Claude, or Copilot harness. KastCTL is not placed on
`PATH`. A failed invocation leaves the prior active release usable.

After installation, `kast` is the agent interface:

```console
kast
kast up
kast workspace refresh
kast symbol search --query '<query>'
kast graph summary --scope symbol
```

Pass `--harness codex`, `--harness claude`, or `--harness copilot` to select
harnesses explicitly. Repeat the option for more than one, or pass
`--harness none` to skip agent resources.

For a local bundle:

```console
./install.sh --source /path/to/kast-platform-vX.Y.Z.tar.gz
```

Use `./install.sh --force` to replace validated Kast-owned installation state
and managed user commands. Existing workspace indexes are preserved.

Repository contributors can build and activate this checkout with:

```console
./install.sh --development
```

This development-only profile projects both `kast` and `kastctl` into
`~/.local/bin`. A later standard or snapshot install removes `kastctl` only
when its install receipt proves that Kast owns the exact projection.

Start with the [first compiler-backed task](https://kast.michne.com/tutorials/first-compiler-backed-task/),
follow the [installation guide](https://kast.michne.com/how-to/install-or-update/),
or use the [CLI reference](https://kast.michne.com/reference/cli/).
