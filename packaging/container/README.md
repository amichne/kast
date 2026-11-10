---
type: How-to Guide
title: "Run a task in a container"
description: "Use the native CLI with a mounted Git worktree and persistent Gradle and IDEA state."
resource: "repo://packaging/container"
tags: [kast, container, cli, worktree]
timestamp: "2026-11-10T05:30:00Z"
source_revision: 85d30d515a1d777c5a83781f40813e32e69fa2e6
code_sources:
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerDispatch.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerRuntime.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerWorkspace.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerPlan.kt
  - path: packaging/container/Dockerfile
  - path: packaging/oci_task.py
  - path: packaging/composed_bundle.py
  - path: packaging/dev_container.py
---

# Run a task in a container

`kast container` runs commands in the current Git worktree with a self-contained IDEA runtime.
Edits and build outputs appear directly in the local directory.
The retained container keeps its IDEA profile and indexes.
A private volume keeps Gradle dependencies, wrapper distributions, and build-cache entries.

## Prepare the runtime

Normal use needs the native Kast executable, Colima on macOS, and a prepared native image.
The host needs no Docker CLI, uv, Python, Java, or desktop IDEA for this command.
The image contains its own tools, including Python for workspace setup.

Install Colima:

```shell
brew install colima
```

Kast uses Colima's bundled `nerdctl` client and containerd in the `kast-native` profile.
Task transport uses Colima's Lima dependency and containerd's `ctr` client to preserve guest exit codes.
The first task starts that profile when necessary.
Existing Docker profiles stay separate.
See [Colima runtimes](https://colima.run/docs/runtimes/) for the bundled client contract.

The supported runner is Colima/containerd on native ARM64 or x86_64 macOS.
OCI defines the image format. Other runners need their own adapter and qualification.
This command does not claim support for every OCI runner.

## Select an image

Use a trusted native Kast image with the v3 development layout:

```shell
kast container shell --image REGISTRY/IMAGE@sha256:DIGEST
```

Replace the example with an actual immutable image reference.
Kast pulls an absent registry image, then admits its architecture, user, layout, and digest.
A local containerd image ID also works with `--image sha256:DIGEST`.
The first unqualified call selects the local `kast-dev:arm64` or `kast-dev:amd64` image.
Later unqualified calls keep the image of the retained workspace.

A default public image remains a release prerequisite.
The separate OCI base image uses another layout and does not satisfy the v3 development contract.
The [OCI image guide](../oci/README.md) describes that cloud path.

For source builds, use the checkout tool:

```shell
mise exec -- kast-container build --checkout /path/to/kast
```

The source builder still uses Docker, uv, and the repository toolchain.
It prepares an image in the separate `kast-dev` Docker profile.
Transfer that image to the `kast-native` containerd profile before native CLI use.
Source builds are separate from normal installed commands.

For an ARM64 source build, start the containerd profile and transfer the image:

```shell
colima start --profile kast-native --runtime containerd --vm-type vz \
  --mount-type virtiofs --mount "$HOME:w" --mount /private/tmp:w \
  --cpus 4 --memory 8 --disk 48 --ssh-agent=false --activate=false --template=false
docker --host="unix://$HOME/.colima/kast-dev/docker.sock" save kast-dev:arm64 \
  | colima nerdctl --profile kast-native -- load
```

For an x86_64 source build, replace `arm64` with `amd64`.
The transfer uses Docker only because the source builder owns a Docker image store.

## Run worktree commands

From a Git worktree, open a shell:

```shell
kast container
```

Run a command with literal arguments:

```shell
kast container run -- ./gradlew --build-cache test
kast container run -- git status --short
```

Use `--workspace /absolute/path/to/worktree` to select another worktree.
Kast mounts the worktree at its original absolute path.
For linked worktrees, it also mounts the Git metadata directory at its original path.
The host needs no Git subprocess for this discovery.
Git inside the container admits only the selected worktree through `safe.directory`.

The command preserves stdin, stdout, stderr, and the task's exit status.
Progress goes to stderr. Shell exit keeps the container available.
If another invocation prepares a container, retry after that preparation completes.
Each later command reexamines the image, mount scope, and exact workspace trust.
An incompatible retained container rejects before a task starts.

An interrupt signals only the current task's containerd exec ID.
The CLI reports a bounded cancellation result. That result does not prove retirement of detached child processes.

## Keep caches and runtime state

The default name selects one container and Gradle volume per canonical worktree and architecture.
IDEA imports and shell builds use `/opt/kast/state/home/.gradle` inside that container.
The host's live Gradle cache is not mounted.
Build-cache entries persist when the project or command enables Gradle's build cache.

Use a name when the same worktree needs another private runtime:

```shell
kast container run --name review -- ./gradlew test
kast container shell --name review
```

Names separate runtime state and caches. They share the same mounted source files and build outputs.
Use separate Git worktrees for independent edits or concurrent builds.
Avoid concurrent host and container builds in the same worktree.

A repeated command can reuse downloaded dependencies and existing indexes.
That mechanism alone does not establish a startup or build-time improvement.
This command does not copy a live host cache or supply a shared dependency seed.
Gradle's [cache guide](https://docs.gradle.org/current/userguide/dependency_caching.html) describes shared read-only dependency caches.

## Select network access and inspect state

Network access stays on by default. Select offline mode for a new named runtime:

```shell
kast container run --name offline --offline -- kast --version
kast container status
```

Repeat `--offline` for that runtime. A different network selection rejects.
`status` starts no VM or container. It reports a stopped or absent profile as unavailable.

Stop the VM while preserving its containers and caches:

```shell
colima stop --profile kast-native
```

The next task restarts the profile and its selected container.
Container removal discards that container's IDEA profile and indexes.
The named Gradle volume remains separate.

The VM shares HOME and `/private/tmp` so it can mount worktrees beneath those paths.
Each workspace container receives its selected source and Git metadata mounts.
For another host location, configure a Colima mount before the task.
Mounted writes change host files. This workflow supplies a controlled executable environment, not a disposable source copy.
