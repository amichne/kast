---
title: Install And Repair Commands
description: Install repository resources, shell integration, plugins, bundles, and managed state.
icon: lucide/wrench
---

# Install And Repair Commands

Install commands write managed files. Run them deliberately, then use
`kast doctor` and `kast paths` to verify the active install state.

## Repository resources

Use `kast install copilot` once per repository where Copilot should use Kast.
The command targets the repository's `.github` directory and records managed
resource checksums in the install manifest.

```console title="Install repository-local Copilot files"
cd /path/to/your/repository
kast install copilot
kast install copilot --force
```

Use `--force` after upgrading the machine binary or when `kast doctor` reports
stale repository outputs.

`kast install instructions` and `kast install skill` install lighter-weight
agent resources for hosts that load Markdown instructions or skills instead of
the full repository Copilot package.

```console title="Install lightweight agent resources"
kast install instructions --target-dir "$PWD" --force
kast install skill --target-dir "$PWD" --force
```

## Machine repair

Use `kast doctor` as the broad read-only check. Add `--repair` only when you
want Kast to rewrite install-owned state such as the manifest, managed shim,
and stale resource records.

```console title="Audit and repair"
kast doctor
kast doctor --repair
```

Use `kast paths` when you only need to inspect the manifest-backed path model.

```console title="Inspect resolved paths"
kast paths
kast --output json paths
```

## Plugin and shell integration

The Homebrew formula installs or refreshes the version-coupled
`kast-plugin` cask. Use `kast install plugin` or the cask directly when local
JetBrains profile links need repair.

```console title="Repair local IDE profile links"
brew reinstall --cask kast-plugin
kast install plugin
```

Use `kast install shell` to add the active shim directory to a shell profile
and write managed completion integration.

```console title="Install shell integration"
kast install shell --shell zsh
kast install shell --shell bash
```

## Linux bundle activation

Release builds use `kast package ubuntu-debian-bundle` to build the Linux
headless tarball. Servers and images can activate an extracted or archived
bundle with `kast install activate-bundle`.

```console title="Activate a portable bundle"
kast install activate-bundle \
  --source /artifacts/kast-ubuntu-debian-headless-x86_64-v1.2.3.tar.gz
```

Use `--verify-only` when an image build should prove the bundle and current
install without changing files.
