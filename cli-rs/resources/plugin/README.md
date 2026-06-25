# Kast Copilot LSP Plugin

This package distributes the minimal reliable Kast Copilot primitive set:

- `kotlin` LSP configuration
- a small SDK extension that injects tooling guidance and exposes
  `kast_*` tools discovered through `kast agent tools`

The package source is `plugin.json` plus `primitive-manifest.json`; generated
files under `.github` are install outputs. When validating the SDK extension in
Copilot CLI, load this source directory explicitly with `--plugin-dir`.

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
  --model gpt-5-mini --effort low \
  -p 'Validation only. Reply exactly: KAST_PLUGIN_LOADED'
```
