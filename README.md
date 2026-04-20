# Kast
[![CI](https://github.com/amichne/kast/actions/workflows/ci.yml/badge.svg)](https://github.com/amichne/kast/actions/workflows/ci.yml) [![DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/amichne/kast)

Kast is a Kotlin analysis tool for real Kotlin workspaces. The current
supported operator path is the repo-local `kast` command.

The repo is organized as a Gradle multi-module build:

- `analysis-api`: shared contract, JSON-RPC models, descriptor discovery,
  standalone options, errors, and edit validation
- `kast-cli`: operator-facing CLI control plane, wrapper packaging, portable
  distribution layout, and the native-image entrypoint
- `analysis-server`: request dispatch and daemon transport plumbing
- `backend-standalone`: standalone runtime entrypoint plus Kotlin Analysis API
  integration
- `shared-testing`: fake backend fixtures used by server and backend tests

## Install the published CLI

Kast publishes portable release zips for supported operating systems. Install
the latest release from any shell with a copyable one-line command:

```console
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/HEAD/kast.sh)"
```

Or via pipe:

```console
curl -fsSL https://raw.githubusercontent.com/amichne/kast/HEAD/kast.sh | bash
```

## Try it

`kast demo` runs an interactive comparison of grep-based text search versus
Kast's semantic analysis on your own Kotlin workspace.

```console
kast demo --workspace-root=/path/to/your/kotlin/project
kast demo --workspace-root=/path/to/your/kotlin/project --symbol=YourClassName
```

## Documentation

Full documentation: <https://amichne.github.io/kast/>
