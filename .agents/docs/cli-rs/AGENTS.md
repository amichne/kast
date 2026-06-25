# Docs agent guide

This file applies to `cli-rs/docs/`. It intentionally lives under
`.agents/docs/cli-rs/` so the CLI Zensical site does not publish agent-only
guidance as site content. The `cli-rs/docs` unit follows the reader-oriented
structure used by the main Kast docs: overview, getting started, capability
pages, reference, and architecture.

## Ownership

- Keep `zensical.toml` and the source pages in sync. Add navigation entries
  when adding new pages.
- Keep public CLI examples aligned with `src/cli.rs` and `README.md`.
- State whether a command reads from `source-index.db`, talks to a daemon,
  writes files, or only prints JSON.
- Prefer narrow, evidence-backed claims over broad product language.
- Do not hand-edit generated files under `site/`.

## Authoring conventions

- Use front matter with `title`, `description`, and `icon`.
- Use content tabs (`=== "Tab"`) for interactive, JSON, and CI variants.
- Wrap prose near 80 characters except long commands and tables.
- Put at least one paragraph after every heading before a list or table.

## Verification

Before finishing docs changes, run:

```console
zensical build --clean
```

Also run the Rust gates when docs describe CLI behavior:

```console
cargo fmt --all -- --check
cargo clippy --locked --all-targets --all-features -- -D warnings
cargo test --locked --all-targets --all-features
```
