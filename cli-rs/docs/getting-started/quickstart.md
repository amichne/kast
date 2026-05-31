---
title: Quickstart
description: Build the Rust CLI, point it at a Kotlin workspace, and run
  the demo views.
icon: lucide/rocket
---

# Quickstart

This page starts from the `cli-rs` checkout. The commands assume you
also have a Kotlin workspace that has been indexed by Kast or can be
indexed by the standalone backend.

## Build the binary

The crate builds a single `kast` binary. Use `--locked` when you want
the same dependency graph CI uses.

```console title="Build cli-rs"
cargo build --release --locked
target/release/kast --help
target/release/kast version
```

During development, `cargo run -- ...` is fine:

```console title="Run from source"
cargo run -- demo --help
```

## Prepare a workspace

The demo reads the workspace source index directly. If the index is
missing, run `kast up` for the Kotlin workspace first. That command
starts or warms a backend and waits for it to become servable.

```console title="Start or warm a workspace backend"
target/release/kast up --workspace-root="/absolute/path/to/kotlin/workspace"
```

By default, `cli-rs` looks for the source index through the same
workspace cache convention as the rest of Kast. You can bypass that
lookup with `--database` when you already know the SQLite path.

## Open the demo

Pass a fully-qualified symbol when you know where you want to start.
Omit `--symbol` to start from the most referenced symbols in the index,
or pass `--query` to prefilter the symbol list. The default view is the
symbol walker; `--view spatial` opens the structural tree.

=== "Interactive"

    ```console title="Open the ratatui symbol walker"
    target/release/kast demo \
      --workspace-root="/absolute/path/to/kotlin/workspace" \
      --symbol "com.example.OrderService.processOrder"
    ```

=== "Search first"

    ```console title="Start with a symbol search"
    target/release/kast demo \
      --workspace-root="/absolute/path/to/kotlin/workspace" \
      --query "OrderService"
    ```

=== "Explicit database"

    ```console title="Read a specific source-index.db"
    target/release/kast demo \
      --workspace-root="/absolute/path/to/kotlin/workspace" \
      --database="/absolute/path/to/source-index.db" \
      --symbol "com.example.OrderService"
    ```

=== "Spatial"

    ```console title="Open the spatial source-index tree"
    target/release/kast demo \
      --workspace-root="/absolute/path/to/kotlin/workspace" \
      --view spatial \
      --symbol "com.example.OrderService"
    ```

## Capture a JSON snapshot

Use `--json` for CI, tests, or scripts. The output contains the same
current symbol, source preview metadata, index confidence, and the
selected view's model used by the TUI.

```console title="Deterministic snapshot"
target/release/kast demo \
  --workspace-root="/absolute/path/to/kotlin/workspace" \
  --symbol "com.example.OrderService" \
  --json
```

```console title="Spatial deterministic snapshot"
target/release/kast demo \
  --workspace-root="/absolute/path/to/kotlin/workspace" \
  --view spatial \
  --symbol "com.example.OrderService" \
  --json
```

The command also prints JSON automatically when stdout is not a TTY.
That keeps the demo usable in non-interactive environments without
special terminal handling.

## Run the local gates

Use the same checks as CI before pushing changes that affect the CLI,
docs, or tests.

```console title="Production gates"
cargo fmt --all -- --check
cargo clippy --locked --all-targets --all-features -- -D warnings
cargo test --locked --all-targets --all-features
zensical build --clean
cargo build --release --locked
```
