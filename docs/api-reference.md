---
title: HTTP API
description: Route map, request conventions, and capability gating for the
  current Kast surface.
icon: lucide/braces
---

Kast exposes one HTTP/JSON contract at `/api/v1`. The routes are stable across
runtime hosts, while the actual feature set depends on the capabilities each
backend advertises at startup.

## Request conventions

These rules apply across the API surface and are the first things to check when
you are wiring a client.

- Use absolute file paths in all request payloads.
- Use offsets greater than or equal to zero.
- Send `X-Kast-Token` only when the runtime was started with a token.
- Expect `references` and `diagnostics` to truncate at the configured
  `maxResults` limit.
- Expect every typed response to include `schemaVersion`.

## Endpoint map

Start with `health` and `capabilities`, then gate the rest of your calls
against the advertised capability set.

| Route | Method | Capability gate | Notes |
| --- | --- | --- | --- |
| `/api/v1/health` | `GET` | None | Returns backend identity and workspace metadata. |
| `/api/v1/capabilities` | `GET` | None | Returns current read and mutation capabilities plus server limits. |
| `/api/v1/symbol/resolve` | `POST` | `RESOLVE_SYMBOL` | Expects one `position` with `filePath` and `offset`. |
| `/api/v1/references` | `POST` | `FIND_REFERENCES` | Supports `includeDeclaration`; results can be truncated. |
| `/api/v1/call-hierarchy` | `POST` | `CALL_HIERARCHY` | Expects `depth > 0`; the route exists, but no production backend advertises it yet. |
| `/api/v1/diagnostics` | `POST` | `DIAGNOSTICS` | Expects one or more absolute file paths; results can be truncated. |
| `/api/v1/rename` | `POST` | `RENAME` | `dryRun` defaults to `true`; returns planned edits and file hashes. |
| `/api/v1/edits/apply` | `POST` | `APPLY_EDITS` | Applies prepared `TextEdit` values against the provided file hashes. |

## Current host matrix

The route map is broader than the currently implemented production behavior.
Use the capability response, not the runtime name alone, as the source of
truth.

| Capability | IntelliJ plugin | Standalone process | Notes |
| --- | --- | --- | --- |
| `RESOLVE_SYMBOL` | Yes | Yes | IntelliJ resolves against PSI and indices; standalone resolves against the Kotlin Analysis API. |
| `FIND_REFERENCES` | Yes | Yes | Both hosts return locations and optional declarations. |
| `CALL_HIERARCHY` | No | No | The route exists, but production support is not implemented. |
| `DIAGNOSTICS` | Yes | Yes | IntelliJ reports Kotlin semantic diagnostics for Kotlin files and falls back to PSI parse errors for other PSI; standalone reports Kotlin Analysis API diagnostics. |
| `RENAME` | Yes | Yes | The response is a text edit plan in both hosts. |
| `APPLY_EDITS` | Yes | Yes | This is the shared mutation primitive across both hosts. |

## Minimal payloads

These shapes cover the main bootstrap and mutation flows without reproducing
the full model list.

### Resolve a symbol

Use this request when you need the symbol identity at one location.

```json
{
  "position": {
    "filePath": "/absolute/path/to/Foo.kt",
    "offset": 142
  }
}
```

### Plan a rename

Use `dryRun: true` first, then apply the returned edits only after you inspect
them.

```json
{
  "position": {
    "filePath": "/absolute/path/to/Foo.kt",
    "offset": 142
  },
  "newName": "renamedSymbol",
  "dryRun": true
}
```

## Mutation flow

Kast separates mutation planning from mutation application so clients can review
the edit set before touching the workspace.

1. Call `/api/v1/rename` with `dryRun: true`.
2. Inspect the returned `edits`, `affectedFiles`, and `fileHashes`.
3. Send those values to `/api/v1/edits/apply` to write the changes.

## Next steps

Use [Choose a runtime](choose-a-runtime.md) if you need to decide which host to
start. Use [Get started](get-started.md) if you still need a running instance.
Keep [Operator guide](operator-guide.md) nearby when you need the descriptor
fields, CLI flags, or runtime defaults.
