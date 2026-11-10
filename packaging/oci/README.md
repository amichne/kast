# Kast OCI base image

The OCI recipe packages Control, the composed
Host, and its JetBrains Runtime for Linux x86-64. It starts without a Kast
checkout or an installer download. A Gradle project can still need its own
wrapper distribution and dependencies.

## Publish an image

1. Start the existing bundle workflow on the source ref that you want to qualify.

   ```sh
   gh workflow run composed-linux.yml --repo amichne/kast --ref main -f version=0.0.0
   ```

2. After the bundle workflow succeeds, use its run ID to start the image workflow.

   ```sh
   gh workflow run kast-oci-base.yml --repo amichne/kast --ref main -f qualified_run_id=RUN_ID
   ```

3. Copy the digest reference from the image workflow summary.

The image workflow checks the source revision and archive digest against the
native qualification receipt. It starts Control and Java offline. A fresh
container must pass the compiler probe before the workflow pushes the image.
The workflow then pulls the image by digest and compares its local image ID.
To qualify an image without a registry push, add `-f publish=false` to step 2.
That run also exports a `kast-oci-candidate` artifact for a cloud trial.
The artifact contains the image archive, checksum, local image ID, and tag.
The workflow reloads the archive and compares its image ID before upload.

After download, examine the checksum and load the exact archive:

```sh
sha256sum --check SHA256SUMS
docker image load --input kast-base.tar
```

Use the image ID from `image-id.txt` in the task command.
The artifact expires after 14 days and needs GitHub Actions access.

## Start a task

Start a container from the repository directory.

```sh
docker run --rm -it --init --platform linux/amd64 \
  --mount "type=bind,src=$PWD,dst=/workspace/project" \
  ghcr.io/amichne/kast-base@sha256:DIGEST kast-task /workspace/project
```

The project directory must be writable by `kast` with UID `10001`.
`kast-task` grants Gradle import access to that path in the container's
private IDEA profile. It opens a shell in the selected directory.

To run a command instead of a shell, add it after the workspace path.
If you clone a repository inside the container, run `kast-task /workspace/project` there.
Use a fresh container for each task. The base image starts without a workspace grant.

## Use another cloud workspace path

The launcher also accepts explicit runtime and workspace directories.
For example, a cloud provider can place repositories under the agent user's home:

```sh
kast-task --runtime-root /opt/kast/runtime --workspace-root /home/kast/task-repos \
  /home/kast/task-repos/project bash
```

Both directories must use canonical absolute paths.
The workspace must exist beneath the selected parent.
The launcher rejects the parent itself, outside paths, and symlinks.
The runtime and its private state must belong to the task's agent user.
The grant names one exact workspace and does not change an existing grant.
Subsequent commands use `/opt/kast/runtime/bin/kast` directly.

Arguments after the workspace go directly to the child process.
The launcher preserves the child's exit code.
It grants workspace trust and starts a process inside an existing environment.
The cloud provider or container engine supplies isolation.

## Prepare a reusable cloud environment

Use the composed archive when the provider supplies the base operating system.
This path needs no Docker daemon or desktop IDEA installation.
Use Python 3.12 or 3.13 and the Linux libraries listed in [the image recipe](Dockerfile).

Install an authenticated archive with the shared installer:

```sh
bin/setup-kast --archive "$BUNDLE" --archive-sha256 "$BUNDLE_SHA256" \
  --version "$KAST_VERSION" --root "$KAST_RUNTIME"
```

Select an absent absolute runtime path outside the repository.
The bundle workflow supplies the archive, digest, version, and qualification receipt.
For a source checkout, `packaging/composed_oci.py` admits a successful producer and downloads its exact artifact.
This download needs GitHub Actions access.

Keep workspace trust out of the reusable environment.
At task startup, select the current repository with the shared launcher:

```sh
python3 packaging/oci_task.py --runtime-root "$KAST_RUNTIME" \
  --workspace-root "$REPOSITORY_PARENT" "$REPOSITORY" "$KAST_RUNTIME/bin/kast" --version
```

Then run the [semantic handoff](../cloud/README.md#managed-cloud-handoff) from the actual agent process.
Version output alone proves neither workspace readiness nor compiler results.
Stop owned processes before the provider captures a reusable filesystem.
Keep transient endpoints and locks out of that filesystem.

Codex Cloud separates an Install script from a Start skill.
Use the installer during environment setup and the workspace handoff in each fresh task.
See the [Codex Cloud guide](https://learn.chatgpt.com/docs/environments/cloud-environments).

For Copilot on GitHub Actions, place installation commands in `.github/workflows/copilot-setup-steps.yml`.
That mechanism does not apply to Copilot cloud sandboxes.
See the [GitHub guide](https://docs.github.com/en/copilot/how-tos/copilot-on-github/customize-copilot/customize-cloud-agent/customize-the-agent-environment).

For providers that accept OCI base images, use a published digest in `FROM`.
Keep the runtime at `/opt/kast/runtime` because its installed records contain absolute paths.
Keep its user and writable private state when you add the agent's tools.
Devin integration still needs a fresh task with that provider.

## Examine cloud evidence

The image workflow starts Control and Java without network access.
It then starts a fresh container with a workspace outside `/workspace`.
Separate container commands grant trust, run the compiler probe, and query an exact declaration.
The workflow preserves these results in a `kast-oci-evidence` artifact.
This evidence covers Docker on the hosted Linux runner.
Each agent provider still needs its own fresh-task handoff.
