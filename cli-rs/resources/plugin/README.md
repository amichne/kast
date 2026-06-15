# Kast Copilot LSP Plugin

This package distributes the minimal reliable Kast Copilot primitive set:

- `kast-kotlin` LSP configuration
- Kotlin-scoped instructions that route agents through LSP and Kast methods
- a small SDK extension that exposes catalog-backed `kast_*` tools

The package source is `primitive-manifest.json`; generated files under
`.github` are install outputs.

Install into a repository:

```console
cli-rs/resources/plugin/scripts/install-local.sh --target /path/to/repo --force
```

Validate this package from the Kast repository:

```console
.github/scripts/test-kast-copilot-plugin.sh
```
