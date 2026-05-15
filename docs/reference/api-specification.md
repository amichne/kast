---
title: API specification
description: Machine-readable OpenAPI 3.1 specification for the Kast analysis
  daemon JSON-RPC protocol.
icon: lucide/file-code
---

The OpenAPI spec documents the `kast` analysis daemon's JSON-RPC
protocol. It's generated from the Kotlin serialization models in
`analysis-api` and stays in sync via automated tests.

For human-readable documentation of every operation — schemas,
examples, behavioral notes — see the [API reference](api-reference.md).

## Transport note

Real transport is **line-delimited JSON-RPC 2.0** over Unix domain
sockets, stdio pipes, or TCP — not HTTP. The OpenAPI spec is a
logical projection for docs, client codegen, and schema validation.
Batch requests and JSON-RPC notifications aren't supported.

## Capability gating

Read and mutation operations require the daemon to advertise the
matching capability via the `capabilities` method. Each operation
in the spec includes an `x-kast-required-capability` extension
naming the required capability enum value (e.g. `RESOLVE_SYMBOL`,
`RENAME`). System methods have no capability requirement.

`edits/apply` additionally needs the `FILE_OPERATIONS` capability
when the request carries non-empty `fileOperations`. This
conditional requirement is documented with the
`x-kast-conditional-capability` extension.

## View the spec

The generated YAML is checked into `docs/openapi.yaml` in the repository root
and served alongside these docs on GitHub Pages.

[:material-file-code: View openapi.yaml](../openapi.yaml){ .md-button }

## Download as build artifact

The OpenAPI spec is published as `dist/openapi.yaml` alongside the CLI and
plugin artifacts when you run `./kast.sh build`. You can also generate it directly:

```console
./gradlew :analysis-api:generateOpenApiSpec
```

## Import into tools

Valid OpenAPI 3.1. Import into Swagger UI, Redoc, or Stoplight, or
use it for client codegen with openapi-generator. The
`jsonrpc://localhost` server URL is a logical placeholder —
configure your client for the real transport.

## Schema version

The spec version tracks the analysis API schema version
(`SCHEMA_VERSION`), currently **3**. OpenAPI `info.version` is set
to `3.0.0` to reflect this.

??? info "For contributors: regenerating the spec"

    To regenerate the checked-in YAML after changing analysis-api models:

    ```console
    ./gradlew :analysis-api:generateOpenApiSpec
    ```

    The `AnalysisOpenApiDocumentTest` will fail if the checked-in file drifts
    from the generated output, ensuring the spec stays in sync with the Kotlin
    models.
