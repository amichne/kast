---
title: Get started
description: Start a Kast runtime, discover its descriptor, and make your
  first API requests.
icon: lucide/rocket
---

Kast lets you point the same client workflow at either an IntelliJ-backed
server or a standalone JVM process. This guide gets you from a clean checkout
to a running instance and a first HTTP request.

Use [Choose a runtime](choose-a-runtime.md) first if you are still deciding
whether to start Kast inside IntelliJ or as a standalone process.

> **Note:** The Gradle build uses Java 21.

## Prerequisites

Before you start, make sure you have the repository checked out and a Kotlin
workspace you want Kast to analyze.

- Java 21
- This repository
- One workspace to open in IntelliJ or pass to the standalone process

## Build a runtime

Choose the runtime that matches where you want analysis to run. The client
surface stays the same after startup.

=== "IntelliJ plugin"

    Use this path for local development when you want PSI-backed reads and
    rename planning from a running IDE.

    1. Build the plugin:

        ```bash
        ./gradlew :backend-intellij:buildPlugin
        ```

    2. Start the sandbox IDE:

        ```bash
        ./gradlew :backend-intellij:runIde
        ```

    3. Open the target workspace in the sandbox IDE.

    4. Wait for the plugin to start one project-scoped Kast server and write a
       descriptor file.

=== "Standalone process"

    Use this path for headless workflows and CI.

    1. Build the standalone distribution:

        ```bash
        ./gradlew :backend-standalone:fatJar :backend-standalone:writeWrapperScript
        ```

    2. Start the runtime for a workspace:

        ```bash
        ./backend-standalone/build/scripts/backend-standalone \
          --workspace-root=/absolute/path/to/workspace
        ```

    3. By default, the standalone host scans conventional source roots and
       auto-discovers Gradle multi-module workspaces. Use `--source-roots`,
       `--classpath`, or `--module-name` only when you need to override that
       discovery.

    4. Optional: add `--token=shared-secret` if you want protected routes to
       require the `X-Kast-Token` header.

## Discover the instance

Both runtimes register themselves by writing a `ServerInstanceDescriptor` JSON
file under `~/.kast/instances/` by default. Set `KAST_INSTANCE_DIR` if you want
to override the directory.

Each descriptor includes the values your client needs to connect:

```json
{
  "workspaceRoot": "/absolute/path/to/workspace",
  "backendName": "intellij",
  "backendVersion": "0.1.0",
  "host": "127.0.0.1",
  "port": 51234,
  "token": null,
  "pid": 12345,
  "schemaVersion": 1
}
```

## Make your first requests

Once you have the descriptor, read its `host` and `port` values and call the
discovery endpoints first.

1. Call the health endpoint:

    ```bash
    curl http://127.0.0.1:51234/api/v1/health
    ```

2. Inspect the advertised capabilities:

    ```bash
    curl http://127.0.0.1:51234/api/v1/capabilities
    ```

3. Send an analysis request with an absolute file path and byte offset:

    ```bash
    curl \
      -X POST http://127.0.0.1:51234/api/v1/symbol/resolve \
      -H 'Content-Type: application/json' \
      -d '{
        "position": {
          "filePath": "/absolute/path/to/Foo.kt",
          "offset": 142
        }
      }'
    ```

If the runtime descriptor includes a token, add
`-H 'X-Kast-Token: shared-secret'` to protected routes.

## Verify the result

You know the bootstrap worked when all three of these conditions are true.

- A descriptor file appears in the instance directory.
- `/api/v1/health` returns `status: "ok"`.
- `/api/v1/capabilities` matches the host you started.

## Next steps

Read [HTTP API](api-reference.md) to wire a client against the contract. Use
[Choose a runtime](choose-a-runtime.md) when you need the host-selection guide.
Keep [Operator guide](operator-guide.md) nearby for runtime defaults, CLI
flags, or descriptor lifecycle details.
