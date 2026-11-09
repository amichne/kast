# Kast OCI base image

The private GitHub Container Registry package contains Control, the composed
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

## Start a task

After you authenticate to `ghcr.io`, start a container with the digest reference.

```sh
docker run --rm -it --init --platform linux/amd64 ghcr.io/amichne/kast-base@sha256:DIGEST
```

The default user is `kast` with UID `10001`. Use a writable project directory
under `/workspace`. After you review the project, grant trust to its exact path.

```sh
python3 /opt/kast/packaging/composed_bundle.py trust \
  --root /opt/kast/runtime --workspace /workspace/project
kast workspace wait --workspace /workspace/project
```

Use a fresh container for another project. The trust command preserves an
existing trust file. The published image starts with an empty trust configuration.
