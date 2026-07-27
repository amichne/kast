# Published docs source guide

`docs/` is the authored Zensical site. `zensical.toml` is the navigation and
rendering source of truth.

## Current structure

- `docs/index.md` routes readers into the site.
- `docs/tutorials/` teaches one end-to-end workflow.
- `docs/how-to/` covers installation, exploration, safe edits, and
  troubleshooting.
- `docs/reference/` documents the CLI and Codex plugin.
- `docs/explanation/` explains architecture and compiler evidence.

Do not create a parallel journey map. The current tree and `zensical.toml`
already define the reader paths. Generated protocol material remains under
`cli-rs/protocol/` and is not hand-edited here.

## Rules

- Document only behavior supported by current code and tests.
- Treat `zensical.toml` as authoritative; add or remove a page and its
  navigation entry together.
- Keep tutorials, how-to guides, reference, and explanation focused on their
  respective Diataxis reader need.
- Keep README and docs aligned when the public CLI, setup, runtime, or
  packaging contract changes.
- Do not publish agent-only ADRs or generated `site/` output.
- Wrap prose at 80 characters except for tables and long links.
- Follow every heading with prose before a list or nested heading.

## Verify

```console
.github/scripts/docs/test-docs-content-contract.sh
.github/scripts/docs/test-docs-navigation-contract.sh
zensical build --clean
```
