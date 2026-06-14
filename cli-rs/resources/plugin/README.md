# Kast Copilot LSP Plugin

This package distributes Kast through standard LSP configuration, hooks, agents,
skills, and concise instructions. It is the checked-in source for the packaged
CLI resources and does not require the deprecated Copilot SDK extension path
for normal use.

Install into a repository:

```console
cli-rs/resources/plugin/scripts/install-local.sh --target /path/to/repo --force
```

Validate this package from the Kast repository:

```console
.github/scripts/test-kast-copilot-plugin.sh
```
