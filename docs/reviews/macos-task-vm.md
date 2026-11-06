---
type: Engineering Review
title: "Disposable macOS ARM task VM"
description: "Defines the composed Host, offline VM lifecycle, task inputs, and native evidence limits."
resource: "repo://docs/reviews/macos-task-vm"
tags: [kast, packaging, macos, isolation, evidence]
timestamp: "2026-11-06T18:00:00Z"
source_revision: 04dc45613405b8c011eeb1ed8e6ea81a7ee6b56e
code_sources:
  - path: bin/kast-task-vm
  - path: packaging/task_vm.py
  - path: packaging/task_vm_guest.py
  - path: packaging/composed_bundle.py
  - path: packaging/composition_platform.py
  - path: packaging/managed_headless.py
  - path: packaging/test_composition_task_vm.py
  - path: packaging/test_composition_bundle.py
  - path: build.gradle.kts
---

# Disposable macOS ARM task VM

`kast-task-vm` provisions one offline macOS ARM VM for one command and a committed repository snapshot.
Each task receives a fresh composed Host, private IDEA profile, bundled JBR, and private task HOME.
The [composition recipe](composed-analysis-host.md) owns runtime selection.
The [portable bundle](linux-composed-install.md) procedure now also admits the pinned macOS ARM layout.
`install_prepared_runtime` remains the installer owner.

Apple's [container tool](https://github.com/apple/container) runs Linux guests on Apple Silicon.
Native macOS behavior instead uses [Tart](https://tart.run/quick-start/) and Apple's Virtualization framework.
This path adds a task coordinator around that VM runtime.
It does not replace the semantic runtime or workspace lifecycle.

## Footprint definition

The base image contains macOS ARM, the Tart guest agent, Git, and Python 3.12 or 3.13.
The guest agent must accept `tart exec` commands through its VM socket.
The default Python path is `/opt/homebrew/bin/python3.13`.
Use `--guest-python` for a different absolute path.
Keep the base image free of personal accounts, credentials, project data, and IDEA installations.
The caller supplies a trusted OCI image with an immutable `@sha256:` reference.

The Host comes from the existing module recipe and authenticated setup bundle.
The base image needs no complete Xcode installation for this task coordinator.
A task that consumes Xcode or other tools needs those tools in its chosen image.
An offline Gradle task needs its dependencies in the image or committed task inputs.
The coordinator does not download guest dependencies or forward a package proxy.

| Resource | Default | Meaning |
| --- | --- | --- |
| Virtual CPU count | 2 | Per-task CPU allocation |
| Guest memory | 4,096 MiB | Configured allocation, without a measured minimum claim |
| Free host storage | 40 GiB | Admission floor before image or payload effects |
| Boot deadline | 180 seconds | Guest capability admission |
| Command deadline | 1,800 seconds | Limit for each task operation |

The resource flags accept explicit task budgets within their admitted ranges.
The storage floor covers the selected filesystem for the VM store.
It does not reserve space or prove that a particular task fits.
The composed runtime keeps the recipe's selected vendor files, rather than the complete IDEA donor.
The macOS bundle from this investigation contains 651,177,068 compressed bytes.
Its SHA-256 is `ef07455ecb7eb6791b1ee28000bd54a5439deed8b7c0db0399126127a61315c5`.
The earlier composition corpus records 1,646,303,144 vendor bytes, plus generated metadata.
The macOS disk, copied inputs, native components, and mutable caches add separate costs.
These figures do not establish a minimum runtime, disk allocation, or memory use.

## Run a task

Install Tart on the Mac before this command.
The local capability examination used Tart `2.41.1`.
Prepare the trusted guest image through its image owner's procedure.
The runner does not create or publish that base image.
Create the macOS bundle with `packaging/composed_bundle.py pack` and the composition report's pinned macOS inputs.

Use a new absolute output directory outside the source repository and VM store.
Use a short, dedicated VM store with an existing parent directory.

```sh
mise exec -- kast-task-vm \
  --image REGISTRY/NAMESPACE/IMAGE@sha256:IMAGE_DIGEST \
  --bundle /absolute/path/kast-macos-composed.tar.gz \
  --bundle-sha256 BUNDLE_DIGEST \
  --version 0.0.0 \
  --repository /absolute/path/repository \
  --revision COMMIT_SHA \
  --store /private/tmp/kast-task-images \
  --output /absolute/path/new-task-output \
  -- kast --version
```

The example needs real image and bundle digests.
`--revision` defaults to `HEAD` and resolves to one full commit identity before the snapshot.
The snapshot uses `git archive` and excludes uncommitted files and Git history.
The coordinator rejects submodules because their contents need a separate source owner.
Git LFS pointers remain pointers.
The guest creates a new Git repository and baseline commit for task changes.
That baseline commit does not claim the original source revision.

The guest installs the bundle through the shared receiver and trusts only its copied repository.
The task receives the private `kast` wrapper through `PATH` and bundled JBR through `JAVA_HOME`.
Arguments after `--` reach the task as literal arguments.
Shell syntax needs an explicit shell command.

## Isolation and retirement

The VM receives a fresh name, MAC address, and macOS machine identifier.
The coordinator disables graphics, audio, clipboard transfer, and USB accessories.
It supplies no directory share, external disk, SSH agent, or host credential environment.
An inherited `SOCK_DGRAM` socket has no packet-forwarding process, which disconnects guest networking.
Tart's guest channel transfers the payload and commands without SSH or a guest network address.
The [Tart command source](https://github.com/cirruslabs/tart/blob/2.41.1/Sources/tart/Commands/Run.swift) owns these device interfaces.

The dedicated VM store keeps cached image bytes for later tasks.
Each task clones its selected immutable image and receives its own writable VM state.
Automatic cache pruning stays off.
The runner rejects an existing store without its ownership marker or private permissions.
`_PreparedTask` carries the admitted VM store into execution.
Strict Pyright rejects a caller that skips that transition.
Parallel tasks share that image cache, with separate VM names, output directories, profiles, and task files.
These controls do not measure host-wide capacity or prove native parallel execution.

After ordinary task completion, the runner copies the workspace into `workspace.tar.gz`.
It keeps that archive without extraction on the host.
It also keeps stage logs and `receipt.json` in the output directory.
The receipt records source, snapshot, bundle, executable, and coordinator identities.
Its `nativeAcceptance` remains `NOT_ASSERTED` because a successful arbitrary command proves no semantic corpus.

The coordinator stops its VM and waits for its child process before VM deletion.
A task error still permits workspace export.
A task timeout or cancellation skips export and starts VM retirement.
Retirement failures stay separate from the original task result and cause an unsuccessful command exit.
The receipt keeps the task name and store for recovery if deletion fails.
The runner never deletes the shared image cache or another task's VM.

## Evidence and remaining qualification

Focused cases exercise platform admission, digest rejection, literal arguments, private environments, copied source, adverse exits, cancellation, and retirement.
Real child processes supply scripted Tart outcomes for the coordinator cases.
Those cases prove adapter behavior and effect order, without a macOS guest or compiler claim.
Separate bundle cases exercise both native layouts through the installer effect interface.

The real macOS bundle installed into a fresh private root and returned native version `0.0.0`.
Its semantic probe ran under a sandbox and returned four exact callers and three Java references.
It also rejected an unknown result reference.
The sandbox denied access to the developer HOME and installed graphical IDEA.
This corpus reused native components from revision `8367953a41082eabf96b8c5e9a37d7603c4139c9`.
It does not qualify native components from the coordinator's source revision.
The reused Control rejected `kast stop` with `HOST_RESTART_REQUIRED` after this corpus.
The disposable Host exited after an exact-process `SIGTERM`.
The VM coordinator instead retires the whole guest through Tart.
The local task command rejected `DISK_PRESSURE` before VM cloning or output creation.
The host had about 20 GiB free before this qualification.
Tart documents an initial image download of about 25 GB.
No VM image download, guest boot, native parallel run, or guest containment corpus ran in this change.
The hypervisor, guest agent, and trusted base image remain part of the isolation mechanism.

Native qualification needs enough host storage and a reviewed image digest with the stated guest tools.
Run the retained semantic probe inside that guest against a disposable fixture.
Also examine a host-only file denial, failed host-network access, separate simultaneous task state, and retirement after task failure.
Keep those results separate from host-native bundle execution and scripted coordinator cases.
