---
title: API specification
description: Machine-readable OpenAPI 3.1 specification for the Kast analysis
  daemon JSON-RPC protocol.
icon: lucide/file-code
---

This page describes the OpenAPI specification that documents the Kast analysis
daemon's JSON-RPC protocol. The spec is generated from the Kotlin serialization
models in `analysis-api` and stays in sync via automated tests.

## What the spec covers

The specification models every JSON-RPC method dispatched by the analysis
daemon as a logical `POST /rpc/{method}` operation. Request bodies map to the
`params` payload and response bodies map to the `result` payload.

| Category | Methods | Capability gated |
| --- | --- | --- |
| System | `health`, `runtime/status`, `capabilities` | No |
| Read | `symbol/resolve`, `references`, `call-hierarchy`, `type-hierarchy`, `semantic-insertion-point`, `diagnostics`, `file-outline`, `workspace-symbol`, `workspace/files` | Yes |
| Mutation | `rename`, `imports/optimize`, `edits/apply`, `workspace/refresh` | Yes |

## Transport note

The actual transport is **line-delimited JSON-RPC 2.0** over Unix domain
sockets, stdio pipes, or TCP — not HTTP. The OpenAPI spec is a logical
projection for documentation, client codegen, and schema validation. Batch
requests and JSON-RPC notifications are not supported.

## Capability gating

Read and mutation operations require the daemon to advertise the corresponding
capability via the `capabilities` method. Each operation in the spec includes
an `x-kast-required-capability` extension that names the required capability
enum value (e.g. `RESOLVE_SYMBOL`, `RENAME`). System methods have no
capability requirement.

The `edits/apply` method additionally requires the `FILE_OPERATIONS` capability
when the request includes non-empty `fileOperations`. This conditional
requirement is documented with the `x-kast-conditional-capability` extension.

## Using the spec

### View online

The generated YAML is checked into `docs/openapi.yaml` in the repository root
and served alongside these docs on GitHub Pages.

[:material-file-code: View openapi.yaml](openapi.yaml){ .md-button }

### Download as build artifact

The OpenAPI spec is published as `dist/openapi.yaml` alongside the CLI and
plugin artifacts when you run `./build.sh`. You can also generate it directly:

```bash
./gradlew :analysis-api:generateOpenApiSpec
```

### Import into tools

The spec is valid OpenAPI 3.1 and can be imported into tools like Swagger UI,
Redoc, Stoplight, or used for client code generation with openapi-generator.
Note that the `jsonrpc://localhost` server URL is a logical placeholder — you
will need to configure your client to use the actual transport.

## Schema version

The spec version tracks the analysis API schema version (`SCHEMA_VERSION`),
currently **3**. The OpenAPI `info.version` field is set to `3.0.0` to reflect
this.

## Regenerating the spec

To regenerate the checked-in YAML after changing analysis-api models:

```bash
./gradlew :analysis-api:generateOpenApiSpec
```

The `AnalysisOpenApiDocumentTest` will fail if the checked-in file drifts from
the generated output, ensuring the spec stays in sync with the Kotlin models.
