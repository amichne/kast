---
title: Install And Repair Commands
description: Install repository resources, shell integration, plugins, bundles, and managed state.
icon: lucide/wrench
---

# Install And Repair Commands

Install commands write managed files. Run them deliberately, then use
`kast ready` and `kast inspect paths` to verify the active install state.

## Repository resources

Use `kast agent setup` once per repository where agents should discover Kast
guidance without depending on a harness-specific package. The command installs
the packaged skill under `.agents/skills/kast` and patches selected
`AGENTS.md` files with a Kast-managed fenced region.

```console title="Install harness-agnostic agent guidance"
cd /path/to/your/repository
kast agent setup
kast agent setup --agents-md "$PWD/cli-rs/AGENTS.md" --force
```

Use `--force` after upgrading the machine binary or when a managed fenced
region was intentionally reset to the active binary's guidance.

Use `kast agent up` when setup and runtime warmup should happen together. Start
with `--dry-run` to inspect the skill target, `AGENTS.md` targets, and runtime
command before writing files or starting a backend.
In a smart interactive terminal, the first eligible non-JSON run can ask
whether to apply automatic IDEA setup. Accepting lets the user save IDEA
launch and project-open auto-init as global machine defaults or for this
repository only. The flow installs or refreshes the JetBrains plugin, prepares
harness-agnostic agent guidance, then warms the repository runtime. Use
`--no-onboard` when an interactive terminal should behave like automation.
In JSON dry-runs, both `setup.installCommand` and `runtimeCommand` start with
the executable token used for the dry run, so copied binaries and absolute CLI
paths remain directly callable.

```console title="Bring a repository up for agents"
kast agent up --dry-run
kast agent up --workspace-root "$PWD"
kast agent up --workspace-root "$PWD" --backend=headless
kast agent up --workspace-root "$PWD" --no-onboard
```

In JSON dry-runs, `skillTarget`, `agentsMdTargets`, and `installCommand`
include the target paths plus the executable token used for the dry run, so
copied binaries and absolute CLI paths remain directly callable.

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
