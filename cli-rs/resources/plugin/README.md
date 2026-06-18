# Kast Copilot LSP Plugin

This package distributes the minimal reliable Kast Copilot primitive set:

- `kotlin` LSP configuration
- Kotlin-scoped instructions that route agents through LSP and Kast methods
- a small SDK extension that exposes catalog-backed `kast_*` tools
- two custom agents: `kast-reader` for read-only analysis and `kast-writer` for
  scoped edits and validation

The package source is `plugin.json` plus `primitive-manifest.json`; generated
files under `.github` are install outputs. When validating the SDK extension in
Copilot CLI, load this source directory explicitly with `--plugin-dir`.
Project-installed `.github/agents` load as `kast-reader` and `kast-writer`;
source-plugin agents load under the plugin namespace, such as
`kast-copilot-lsp:kast-reader`.

Install into a repository:

```console
cli-rs/resources/plugin/scripts/install-local.sh --target /path/to/repo --force
```

Validate this package from the Kast repository:

```console
.github/scripts/test-kast-copilot-plugin.sh
```

Validate a live Copilot CLI source-plugin load with a short request:

```console
copilot -C /path/to/repo --plugin-dir cli-rs/resources/plugin \
  --agent kast-copilot-lsp:kast-reader \
  --model gpt-5-mini --effort low \
  -p 'Validation only. Reply exactly: KAST_READER_LOADED'
```
