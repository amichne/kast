---
title: Error codes
description: Every error code the Kast analysis daemon can return, with
  descriptions and common causes.
icon: lucide/alert-triangle
---

# Error codes

When a Kast command fails, the JSON response includes an `error` object
with a numeric `code` and a human-readable `message`. This page lists
every error code, what it means, and the most common cause.

## Standard JSON-RPC errors

These codes are defined by the JSON-RPC 2.0 specification.

| Code | Name | Description |
|------|------|-------------|
| `-32700` | Parse error | The request is not valid JSON. |
| `-32600` | Invalid request | The JSON is valid but doesn't match the JSON-RPC schema. |
| `-32601` | Method not found | The requested method doesn't exist or isn't supported by this backend. |
| `-32602` | Invalid params | The method exists but the parameters are wrong. |
| `-32603` | Internal error | An unexpected error occurred inside the daemon. |

## Kast-specific errors

These codes are defined by the Kast analysis daemon.

| Code | Name | Common cause |
|------|------|-------------|
| `-32000` | Server error | General server-side failure. Check the error message for details. |
| `-32001` | Not ready | The daemon is still indexing. Wait for `state: READY` or pass `--accept-indexing=true`. |
| `-32002` | File not found | The specified file path doesn't exist in the workspace. Verify it's absolute and within the workspace root. |
| `-32003` | Symbol not found | No symbol exists at the specified offset. Check the offset points at an identifier. |
| `-32004` | Conflict | File hashes don't match during apply-edits. A file changed after the plan was created. Re-plan and re-apply. |
| `-32005` | Capability not supported | The current backend doesn't support this operation. Run `capabilities` to check. |
| `-32006` | Timeout | The operation exceeded the configured timeout. Tighten traversal bounds or increase the timeout. |

## Reading error responses

Every error response follows the same structure:

```json hl_lines="3-4" title="Error response structure"
{
  "error": {
    "code": -32003,
    "message": "No symbol found at offset 42 in App.kt"
  },
  "id": 1,
  "jsonrpc": "2.0"
}
```

The `code` field is machine-readable. The `message` field is
human-readable and may include additional context about why the error
occurred.

## Next steps

- [Troubleshooting](../troubleshooting.md) — step-by-step guides for
  common problems
- [API reference](api-reference.md) — full method schemas and examples
