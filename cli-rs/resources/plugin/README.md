# Kast Copilot LSP Plugin

This package distributes only the `kast-kotlin` LSP configuration. Hooks,
agents, instructions, skills, and the previous SDK extension runtime are not
part of the shipped package.

Install into a repository:

```console
cli-rs/resources/plugin/scripts/install-local.sh --target /path/to/repo --force
```

Validate this package from the Kast repository:

```console
.github/scripts/test-kast-copilot-plugin.sh
```
