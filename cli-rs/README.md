# cli-rs

Rust implementation of the Kast CLI control plane.

This crate owns the user-facing `kast` executable. It keeps the headless
analysis backend as a JVM process and keeps CLI-owned work in Rust:

- command parsing with `clap`
- config read/write from `config.toml`
- headless daemon launch via `java -cp <runtime-libs/classpath.txt>`
- JSON-RPC passthrough over Unix domain sockets
- descriptor-based `up`, `status`, and `stop`
- embedded skill and Copilot LSP plugin installation
- Homebrew cask-backed IDEA plugin download and optional profile linking
- install state recorded directly in `config.toml`
- direct read-only `source-index.db` metrics through `rusqlite`
- interactive symbol walking and spatial structure demos through `ratatui`

The current Rust binary supports the control-plane commands and the direct
source-index metrics surface:

```sh
cargo test
cargo build --release
target/release/kast --help
target/release/kast version
target/release/kast config init
target/release/kast status
target/release/kast metrics fan-in
target/release/kast metrics search Foo
target/release/kast demo --symbol lib.Foo
target/release/kast demo --view spatial --symbol lib.Foo
```

The monorepo release workflow publishes platform-specific CLI zips named
`kast-v<version>-<platform>.zip` and renders the generated
`amichne/homebrew-kast` tap, where `Formula/kast.rb` installs the binary
directly.

Public documentation is authored from the monorepo `docs/` tree. This crate no
longer owns a separate docs site. Generated protocol artifacts used by release
and integration consumers live under `protocol/`:

```sh
../gradlew :analysis-api:generateOpenApiSpec :analysis-api:generateDocPages :analysis-server:generateDocExamples
```

The surrounding monorepo supplies the JVM analysis backend and release assembly
inputs; this crate remains the CLI source of truth.
