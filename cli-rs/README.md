# cli-rs

Rust implementation of the Kast CLI control plane.

This crate owns the user-facing `kast` executable. It keeps the headless
analysis backend as a JVM process and keeps CLI-owned work in Rust:

- command parsing with `clap`
- config read/write from `config.toml`
- headless daemon launch via `java -cp <runtime-libs/classpath.txt>`
- JSON-RPC passthrough over Unix domain sockets
- descriptor-based `up`, `status`, and `stop`
- embedded skill and Copilot extension installation
- Homebrew cask-backed IDEA plugin download and optional profile linking
- install state recorded directly in `config.toml`
- direct read-only `source-index.db` metrics through `rusqlite`
- interactive metrics graph browsing through `ratatui`
- interactive symbol walking and spatial structure demos through `ratatui`

The current Rust binary supports the control-plane commands and the direct
source-index metrics surface:

```sh
cargo test
cargo build --release
target/release/kast --help
target/release/kast version
target/release/kast config init
target/release/kast status --workspace-root=/absolute/path/to/workspace
target/release/kast metrics fan-in --workspace-root=/absolute/path/to/workspace
target/release/kast metrics search --workspace-root=/absolute/path/to/workspace Foo
target/release/kast metrics graph --workspace-root=/absolute/path/to/workspace lib.Foo
target/release/kast demo --workspace-root=/absolute/path/to/workspace --symbol lib.Foo
target/release/kast demo --view spatial --workspace-root=/absolute/path/to/workspace --symbol lib.Foo
```

The monorepo release workflow publishes platform-specific CLI zips named
`kast-v<version>-<platform>.zip` and renders the generated
`amichne/homebrew-kast` tap, where `Formula/kast.rb` installs the binary
directly.

Documentation is authored as a Zensical site in `docs/` with
`zensical.toml` as the navigation source of truth:

```sh
python3 -m venv .venv-docs
. .venv-docs/bin/activate
python -m pip install -r requirements-docs.txt
zensical build --clean
```

The surrounding monorepo supplies the JVM analysis backend and release assembly
inputs; this crate remains the CLI source of truth.
