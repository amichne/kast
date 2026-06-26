---
title: Install And Repair Commands
description: Install repository resources, shell integration, plugins, bundles, and managed state.
icon: lucide/wrench
---

# Install And Repair Commands

Install commands write managed files. Run them deliberately, then use
`kast ready` and `kast inspect paths` to verify the active install state.

## Repository resources

Use `kast agent setup copilot` once per repository where Copilot should use Kast.
The command targets the repository's `.github` directory and records managed
resource checksums in the install manifest.

```console title="Install repository-local Copilot files"
cd /path/to/your/repository
kast agent setup copilot
kast agent setup copilot --force
```

Use `--force` after upgrading the machine binary or when `kast ready` reports
stale repository outputs.

Use `kast agent up` when setup and runtime warmup should happen together. Start
with `--dry-run` to inspect the selected harness and workspace-root-derived
targets before writing files or starting a backend.
In JSON dry-runs, both `setup.installCommand` and `runtimeCommand` start with
the executable token used for the dry run, so copied binaries and absolute CLI
paths remain directly callable.

```console title="Bring a repository up for agents"
kast agent up --dry-run
kast agent up --workspace-root "$PWD" --backend=headless
```

Use `kast agent setup auto` when a repository or enterprise environment should
choose a harness-neutral package without assuming Copilot. `--harness` is the
most explicit selector. When it is omitted, Kast reads
`projectOpen.agentHarness` from config before falling back to repository
detection.
In JSON dry-runs, `installCommand` starts with the executable token used for
the dry run, so copied binaries and absolute CLI paths remain directly
callable.

```console title="Install the selected harness package"
kast agent setup auto --dry-run
kast agent setup auto --harness copilot
kast agent setup auto --harness skill --target-dir "$PWD/.agents/skills" --force
kast agent setup auto --harness skill --target-dir "$PWD/.codex/skills" --force
kast agent setup auto --harness instructions --target-dir "$PWD/.agents/instructions" --force
kast agent setup auto --harness instructions --target-dir "$PWD/.codex/instructions" --force
```

Repository auto-detection treats `.codex/skills` and `.codex/instructions` as
portable Codex roots alongside `.agents`, `.github`, and `.claude` skill or
instruction roots.

```toml title="$HOME/.config/kast/config.toml"
[projectOpen]
agentHarness = "instructions"
```

`kast agent setup instructions` and `kast agent setup skill` install lighter-weight
agent resources for hosts that load Markdown instructions or skills instead of
the full repository Copilot package.

```console title="Install lightweight agent resources"
kast agent setup instructions --target-dir "$PWD" --force
kast agent setup skill --target-dir "$PWD" --force
```

## Machine repair

Use `kast ready` as the broad read-only check. Add `--fix` only when you
want Kast to rewrite install-owned state such as the manifest, managed shim,
and stale resource records.

```console title="Audit and repair"
kast ready
kast ready --fix
```

Use `--for` when the failure mode is task-specific. `machine` treats a missing
or mismatched configured binary as a hard failure, and `kotlin` requires an
installed semantic backend in the manifest.

```console title="Targeted readiness checks"
kast ready --for machine
kast ready --for kotlin
kast ready --for release
```

Use `kast inspect paths` when you only need to inspect the manifest-backed path model.

```console title="Inspect resolved paths"
kast inspect paths
kast --output json inspect paths
```

## Plugin and shell integration

The Homebrew formula installs or refreshes the version-coupled
`kast-plugin` cask. Use `kast machine plugin` or the cask directly when local
JetBrains profile links need repair.

```console title="Repair local IDE profile links"
brew reinstall --cask kast-plugin
kast machine plugin
```

Use `kast machine shell` to add the active shim directory to a shell profile
and write managed completion integration.

```console title="Install shell integration"
kast machine shell --shell zsh
kast machine shell --shell bash
```

## Linux bundle activation

Release builds use `kast release package ubuntu-debian-bundle` to build the Linux
headless tarball. Servers and images can activate an extracted or archived
bundle with `kast release activate bundle`.

```console title="Activate a portable bundle"
kast release activate bundle \
  --source /artifacts/kast-ubuntu-debian-headless-x86_64-v1.2.3.tar.gz
```

Use `--verify-only` when an image build should prove the bundle and current
install without changing files.
