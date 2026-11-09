---
type: How-to Guide
title: "Native Kast development shell"
description: "Build and reopen a Linux development container without sharing the developer HOME."
resource: "repo://packaging/container"
tags: [kast, packaging, development, isolation]
timestamp: "2026-11-09T22:39:26Z"
source_revision: e8e46975e4ae90079d1b0a8c1a452f15a89a85b9
code_sources:
  - path: bin/kast-container
  - path: packaging/dev_container.py
  - path: packaging/container_compile.py
  - path: packaging/container_install.py
  - path: packaging/container/Dockerfile
  - path: packaging/container/compiler.Dockerfile
  - path: packaging/composition_platform.py
  - path: packaging/test_dev_container.py
  - path: packaging/test_dev_container_simulation.py
  - path: packaging/test_composition_platform.py
---

# Native Kast development shell

`kast container` opens a copied Git workspace with a self-contained IDEA runtime.
The image contains Control, Host, the bundled JBR, Git, Python, Node.js, and development tools.
The runtime needs no graphical IDEA installation on the Mac.
The public CLI installation still needs IDEA. A host-independent bootstrap remains a 1.0.0 release requirement.

Use macOS with Colima, the Docker CLI, uv, and Python 3.12 or 3.13.
The container uses Linux ARM64 on Apple Silicon or Linux x86_64 on an Intel Mac.
The public Control release supports Apple Silicon. Intel support here describes the source runner only.

## Prepare an image

Install the container prerequisites:

```sh
brew install colima docker uv
```

Examine the command without VM or network effects:

```sh
kast container --help
kast container run --help
```

For a prepared image, use its immutable registry digest or local image ID:

```sh
kast container shell --image REGISTRY/IMAGE@sha256:DIGEST
```

Replace the example with a trusted image that uses the native Kast container v2 layout.
The command downloads an absent registry image by its digest.
It rejects mutable tags, a different CPU architecture, and an incompatible image layout.
A local image ID starts with `sha256:` and needs an existing local image.
No default public image exists in this change.

To prepare the image from source, use a Kast checkout with its mise tools installed:

```sh
mise -C /path/to/kast exec -- kast container build --checkout /path/to/kast
```

The build compiles native Control and Host from the selected checkout.
It downloads the digest-pinned Native Image compiler and IDEA archives.
Debian packages use the configured Debian repositories.
The build uses local source and does not qualify a release.
The checkout command `mise exec -- kast-container build` remains available.
An absent default image triggers a source build only when the runner resides in a checkout.
An installed runner instead names the necessary build command.

## Open a task

From the target Git repository, open the default workspace:

```sh
kast container
```

The first call copies tracked files and new files that Git ignore rules permit into `/work`.
It preserves source edits and excludes the original Git metadata and ignored files.
A tracked secret remains a tracked file.
The runner rejects submodules because their contents need a separate source owner.
The guest creates a new Git baseline and grants only `/work` to its private Host profile.
The new baseline does not claim the original commit identity.

Later calls reopen the same files and dependency caches.
They preserve edits inside the container and do not copy host changes again.
Use `--repository /absolute/path` when the current directory differs from the target repository.

Select a task name for a separate copy of the same repository:

```sh
kast container run --name fix-parser -- ./gradlew test
kast container run --name review-api -- python3 -c 'print("literal arguments")'
kast container shell --name fix-parser
```

Each name selects a separate container, workspace, HOME, and IDEA profile.
A repeated name reopens that task.
Arguments after `--` reach the command unchanged. Shell syntax needs an explicit shell command.
`run` needs a command and returns its exit code.
Progress uses stderr. The child owns stdout and stderr.
The shell exits normally while the container stays available.

Agent tools and authentication belong inside the selected container.
The launcher forwards no host credentials, SSH agent, or complete host environment.
This change provides command delegation. It does not provision an agent account or transfer authentication.

## Isolation and resources

The dedicated `kast-dev` Colima VM has no host directory shares or forwarded SSH agent.
The launcher selects its Docker socket without changes to the active Docker context.
Containers have no host mounts, published ports, or Docker socket.
They use UID 10001, drop Linux capabilities, and cannot gain new privileges.
The default allocation is four CPU cores, eight GiB of memory, and 2,048 processes, with no swap allowance.
The VM has four CPU cores, twelve GiB of memory, and a 48 GiB data disk.
Files and caches remain until explicit retirement. Automatic pruning stays off.

Network access permits dependency downloads and connections to reachable host and local-network services.
Select `--offline` on the first call to remove external network access:

```sh
kast container run --name offline-review --offline -- kast --version
kast container shell --name offline-review --offline --cpus 2 --memory-gib 6
```

An existing workspace rejects a different image or network mode without changes to its files.
Resource changes update the existing container.
A different task name selects a fresh copy for another image or network mode.
These controls do not establish protection against deliberately hostile code.

## Keep results

List the retained containers:

```sh
kast container status
```

Export a task to a new archive:

```sh
kast container export --name fix-parser --output /absolute/path/new-workspace.tar.gz
```

The archive contains the current `/work` contents and excludes its Git metadata.
The command refuses an existing output file and never extracts the archive on the host.
Examine exported files before changes to host source.
`colima stop --profile kast-dev` stops the VM and keeps container files and caches.

## Evidence

Run the focused tests:

```sh
mise exec -- uv run --locked --project packaging pytest \
  packaging/test_dev_container.py packaging/test_dev_container_simulation.py -q
```

These tests exercise real Git, archive operations, and a Docker process double.
They cover literal arguments, copied files, preserved edits, image admission, and rejected policy changes.
They do not prove compiler behavior or hypervisor security.
The [1.0.0 readiness report](../../docs/reviews/1.0-readiness.md) records native evidence and release gaps.
