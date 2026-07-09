---
title: Headless Linux And Hosted Agents
description: Install the Linux headless bundle for CI, hosted agents, and server images.
icon: lucide/server
---

# Headless Linux And Hosted Agents

Use this path for CI runners, hosted agents, server images, or air-gapped Linux
hosts that should not depend on Homebrew or an open developer IDE. The headless
bundle installs a server-local `kast` binary and packaged backend runtime.

Most macOS developers should use the [macOS install](macos.md) instead.

## Install The Bundle

The Ubuntu/Debian bundle installs the binary, install manifest, and backend
runtime together.

```bash title="Install Kast on Ubuntu or Debian"
export KAST_UBUNTU_DEBIAN_VERSION="v1.2.3"
./scripts/install-ubuntu-debian.sh install
./scripts/install-ubuntu-debian.sh verify
kast developer runtime up --backend=headless
```

The release asset is
`kast-ubuntu-debian-headless-x86_64-<version>.tar.gz` with a matching
`.sha256` sidecar. The bundle contains the Rust CLI, one backend portable
runtime, `scripts/install-ubuntu-debian.sh`, metadata, and the license notice.

??? info "Server install details"
    The installer refuses non-Ubuntu/Debian hosts, installs to
    `$HOME/.local/share/kast/versions/<version>` by default, flips
    `$HOME/.local/share/kast/current`, symlinks `$HOME/.local/bin/kast`, and
    writes `$HOME/.local/share/kast/install.json` so the CLI resolves the
    bundled headless runtime from one manifest-backed path model.

    Java 21 or newer must be available on `PATH` or through `JAVA_HOME` when
    the Linux headless runtime starts.

## Prepare Repository Guidance

Run `kast setup` once per repository on non-macOS headless or server hosts.
Setup installs only the repository agent assets:

- `.agents/skills/kast/SKILL.md`
- one managed `<kast>...</kast>` guidance region in the selected context file

```console title="Prepare the repository"
kast setup --dry-run --workspace-root "$PWD"
kast setup --workspace-root "$PWD"
kast ready --for agent --workspace-root "$PWD"
kast agent verify --workspace-root "$PWD"
```

The default context target is the first existing file from `AGENTS.md`,
`CODEX.md`, `CLAUDE.md`, or `AGENTS.local.md`. If no supported context file
exists, setup creates ignored `AGENTS.local.md`.

## Use Mirrors And Image Layers

Point the installer at an exact local tarball when the server pulls from a
private artifact store or baked image layer.

```bash title="Install from a mirrored bundle"
export KAST_UBUNTU_DEBIAN_VERSION="v1.2.3"
export KAST_UBUNTU_DEBIAN_ARTIFACT_PATH="/artifacts/kast-ubuntu-debian-headless-x86_64-v1.2.3.tar.gz"
./scripts/install-ubuntu-debian.sh install
./scripts/install-ubuntu-debian.sh verify
```

??? question "Ubuntu/Debian installer overrides"
    Most installs do not need environment overrides. Use them only for
    packaged images, private artifact stores, and CI setup scripts.

    | Variable | What it does |
    | --- | --- |
    | `KAST_UBUNTU_DEBIAN_VERSION` | Selects the release tag to install |
    | `KAST_UBUNTU_DEBIAN_ARTIFACT_PATH` | Installs from an exact local bundle tarball |
    | `KAST_UBUNTU_DEBIAN_BASE_URL` | Downloads from a mirrored release directory |
    | `KAST_UBUNTU_DEBIAN_ROOT` | Overrides the managed install root |
    | `KAST_UBUNTU_DEBIAN_BIN_DIR` | Overrides the `kast` symlink directory |
    | `KAST_UBUNTU_DEBIAN_CONFIG_HOME` | Overrides the config directory |
    | `KAST_JAVA_CMD` | Selects the Java executable used for verification |

## Hosted Agents

Short-lived Linux x64 workspaces can use the `kast-action` repository instead
of running the Ubuntu/Debian installer directly. That path installs `kast`
under `/opt/kast/current`, activates an install manifest, and seeds an optional
read-only Gradle dependency cache.

The [runtime artifact contract](../distribute/runtime-artifact-contract.md)
defines artifact names, manifest fields, cache layout, and `kast-action@v2`
compatibility requirements. Detailed action inputs and enterprise mirror
guidance live in the sibling `kast-action` repository.

Continue with the [first semantic workflow](../learn/first-semantic-workflow.md)
after the bundle, repository guidance, and backend are ready.
