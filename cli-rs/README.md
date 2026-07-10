# cli-rs

Rust implementation of the Kast CLI control plane.

This crate owns the user-facing `kast` executable. It keeps the headless
analysis backend as a JVM process and keeps CLI-owned work in Rust:

- command parsing with `clap`
- config read/write from `config.toml`
- headless daemon launch via `java -cp <runtime-libs/classpath.txt>`
- typed semantic calls over Unix domain sockets through `kast agent`
- descriptor-based runtime lifecycle under `kast developer runtime`
- embedded skill and Copilot LSP plugin installation
- Homebrew cask-backed IDEA plugin download and optional profile linking
- install state recorded directly in `config.toml`
- direct read-only `source-index.db` metrics through `rusqlite`
- a public, repo-native semantic story and focused exploration through `ratatui`

The public binary keeps a small production surface, with development and
release commands grouped under `kast developer`:

```sh
brew tap amichne/kast
brew install kast
brew install --cask kast-plugin

kast setup
kast ready
kast status
kast demo --workspace-root "$PWD"
kast agent verify --workspace-root "$PWD"
kast agent symbol --query OrderService --references --workspace-root "$PWD"
kast developer inspect metrics fan-in
```

Use the published command manual for the full command vocabulary. Non-Brew
Linux installs use the canonical `scripts/install-ubuntu-debian.sh` flow.

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
