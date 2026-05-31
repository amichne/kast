---
title: Kast Rust CLI
description: Rust control plane, direct source-index metrics, and
  terminal-native demo views for Kast.
icon: lucide/terminal
hide:
  - toc
---

# A terminal control plane for Kast

`cli-rs` is the Rust implementation of the Kast CLI surface. It keeps
the compiler-backed Kotlin analysis runtime in the JVM backend, but moves
CLI-owned work into a small native binary: command parsing, config,
daemon lifecycle, JSON-RPC passthrough, direct SQLite metrics, install
helpers, and the ratatui demo views.

Pick the path that matches what you are doing:

<div class="grid cards" markdown>

-   :material-console:{ .lg .bottom } __Run the CLI__

    ---

    Build the Rust binary, start or inspect a workspace backend, and run
    direct source-index queries.

    [:octicons-arrow-right-16: Quickstart](getting-started/quickstart.md)

-   :octicons-git-branch-16:{ .lg .bottom } __Walk structure__

    ---

    Open terminal UIs that search indexed symbols, walk references,
    render structure, and show source previews.

    [:octicons-arrow-right-16: Demo views](what-can-kast-do/symbol-walk-demo.md)

-   :octicons-list-unordered-16:{ .lg .bottom } __Scan commands__

    ---

    Review the public command tree, common flags, and which commands read
    the source index directly.

    [:octicons-arrow-right-16: CLI cheat sheet](reference/cli-cheat-sheet.md)

</div>

## What this crate owns

The Rust binary is a control plane. It can start the standalone JVM
backend, send JSON-RPC requests to a workspace daemon, install packaged
resources, read `source-index.db` directly for metrics, and render the
ratatui demo. It does not replace the Kotlin compiler analysis engine.

The demo is intentionally terminal-native. When stdout is a TTY,
`kast demo` opens the ratatui interface. When stdout is not a TTY, or
when `--json` is set, it prints a deterministic JSON snapshot that CI
and tests can assert against.

## Production gates

The repository CI runs formatting, clippy with warnings denied, tests
against a seeded `source-index.db`, the Zensical docs build, and a locked
release build. The same commands can be run locally:

```console
cargo fmt --all -- --check
cargo clippy --locked --all-targets --all-features -- -D warnings
cargo test --locked --all-targets --all-features
zensical build --clean
cargo build --release --locked
```

## Next steps

<div class="grid cards" markdown>

-   :octicons-zap-24:{ .lg .middle } **Quickstart**

    ---

    Build the binary, point it at a Kotlin workspace, and open the demo.

    [:octicons-arrow-right-24: Quickstart](getting-started/quickstart.md)

-   :octicons-search-24:{ .lg .middle } **Demo views**

    ---

    Learn the symbol and spatial controls, JSON mode, and source-index
    requirements.

    [:octicons-arrow-right-24: Demo views](what-can-kast-do/symbol-walk-demo.md)

-   :octicons-database-24:{ .lg .middle } **Source index**

    ---

    See how the Rust CLI reads the cache without mutating workspace files.

    [:octicons-arrow-right-24: Source index reader](architecture/source-index-reader.md)

</div>
