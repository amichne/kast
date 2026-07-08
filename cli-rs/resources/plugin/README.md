# Kast Copilot LSP Plugin

This package distributes the minimal reliable Kast Copilot primitive set:

- `kotlin` LSP configuration
- a small SDK extension that injects typed Kast command guidance

The package source is `plugin.json` plus `primitive-manifest.json`; generated
files under `.github` are install outputs. When validating the SDK extension in
Copilot CLI, load this source directory explicitly with `--plugin-dir`.

Repository-local installation is retired. The package is loaded from source by
Copilot CLI during validation:

```console
copilot -C /path/to/repo --plugin-dir cli-rs/resources/plugin
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
