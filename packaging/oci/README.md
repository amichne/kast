# Kast OCI base image

The GitHub Container Registry package contains Control, the composed
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
