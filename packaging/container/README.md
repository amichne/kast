---
type: How-to Guide
title: "Native Kast development shell"
description: "Build and reopen a Linux development container without sharing the developer HOME."
resource: "repo://packaging/container"
tags: [kast, packaging, development, isolation]
timestamp: "2026-11-07T19:25:00Z"
source_revision: 08bc4e75693a260c1bfe0ffaad667800934cc0ed
code_sources:
  - path: bin/kast-container
  - path: packaging/dev_container.py
  - path: packaging/container_compile.py
  - path: packaging/container_install.py
  - path: packaging/container/Dockerfile
  - path: packaging/container/compiler.Dockerfile
  - path: packaging/composition_platform.py
  - path: packaging/test_dev_container.py
  - path: packaging/test_composition_platform.py
---

# Native Kast development shell

Use this checkout MVP on macOS with Colima, the Docker CLI, mise, and the repository toolchain installed.
The image uses the host CPU architecture: Linux ARM64 on Apple Silicon, or Linux x86_64 on an Intel Mac.
It does not use Rosetta or forward the graphical IntelliJ installation.
Linux assets remain local builds, not published releases.

## Open a shell

From the Kast checkout, build the image once:

```sh
mise exec -- kast-container build
```

The first build compiles native Control and Host from this checkout.
It downloads digest-pinned GraalVM and IntelliJ IDEA donors.
Debian packages follow the configured Debian repositories.
The image contains the composed Host, bundled JBR, Git, Python, Node.js, `npm`, and common development tools.
It needs no complete IntelliJ installation on the Mac.
The build uses the working checkout; it does not qualify a release.
Build records keep their existing source-revision fields, which do not authenticate uncommitted changes.

Open any target Git repository:

```sh
mise exec -- kast-container shell --repository /absolute/path/to/repository
```

An absent image triggers the first build automatically.
The first shell copies tracked files into `/work`.
It also copies new files not excluded by Git ignore rules.
It preserves uncommitted source edits but excludes the original Git metadata and ignored files.
It creates a new Git baseline inside the container.
That baseline does not claim the original commit identity.
Submodules reject rather than produce an incomplete copy.
Review the selected repository before copying it: a tracked secret remains a tracked file.

Later calls reopen the same container, including its edits and dependency caches.
They do not overwrite it with another host copy.
Exit the shell normally; the container stays available.
Changes inside `/work` do not change host files.

For the installed shell function:

```sh
cd /path/to/repository
kastbox
kastbox -- kast --version
```

The function delegates to this checkout's launcher.
A fresh interactive `zsh` session loads the installed function.
To load it in an existing session, run `autoload -Uz kastbox`.
The adapter uses `$HOME/code/kast` by default.
Set `KAST_CONTAINER_CHECKOUT` to select another checkout.
Management commands also work through the function: `kastbox build`, `kastbox status`, and `kastbox export`.

## Boundaries and options

The dedicated `kast-dev` Colima VM has no host directory shares or forwarded SSH agent.
The launcher uses its socket explicitly and leaves the active Docker context unchanged.
Containers have no host mounts, published ports, forwarded environment, or Docker socket.
They run as UID 10001, without Linux capabilities or permission to gain new privileges.
The default limits are four CPU cores, eight GiB of memory, no swap allowance, and 2,048 processes.
The VM has four CPU cores, twelve GiB of memory, and a 48 GiB data disk.
Disk allocation and caches remain until explicitly retired; no automatic pruning runs.

Network access is enabled for dependency downloads and ordinary development.
It also permits connections to reachable host and local-network services.
This setup is not a boundary for deliberately hostile code.
Select offline networking when creating a workspace if the task needs it:

```sh
kastbox --offline
kastbox --cpus 2 --memory-gib 6
kastbox -- python3 -c 'print("literal arguments; no implicit shell")'
```

Later sessions can update the CPU and memory limits without replacing files.
Existing workspaces reject a different image or network mode and keep their files.
Choose offline mode before the first session.
Rebuilding the image does not migrate an existing container.

## Keep results

List the retained containers:

```sh
mise exec -- kast-container status
```

Export a new archive without changing host source:

```sh
mise exec -- kast-container export \
  --repository /absolute/path/to/repository \
  --output /absolute/path/to/new-workspace.tar.gz
```

The export excludes the container's Git metadata and includes the current `/work` contents.
The launcher refuses an existing output file and never extracts the archive on the host.
Review exported files before applying changes.
The VM can be stopped with `colima stop --profile kast-dev`; this retains container files and caches.
