---
title: Error codes
description: Every error code the Kast analysis daemon can return, with
  descriptions and common causes.
icon: lucide/alert-triangle
---

# Error codes

When a `kast` command fails, the JSON response carries an `error`
object with a numeric `code` and a human-readable `message`. This
page lists every code, what it means, and the most common cause.

## Standard JSON-RPC errors

Defined by the JSON-RPC 2.0 specification.

| Code | Name | Description |
|------|------|-------------|
| `-32700` | Parse error | Request isn't valid JSON. |
| `-32600` | Invalid request | JSON is valid but doesn't match the JSON-RPC schema. |
| `-32601` | Method not found | The method doesn't exist or this backend doesn't support it. |
| `-32602` | Invalid params | Method exists but the parameters are wrong. |
| `-32603` | Internal error | Unexpected daemon-side error. |

## Kast-specific errors

Defined by the `kast` analysis daemon.

| Code | Name | Common cause |
|------|------|-------------|
| `-32000` | Server error | General server-side failure. Read the message for details. |
| `-32001` | Not ready | Daemon is still indexing. Wait for `state: READY` or pass `--accept-indexing=true`. |
| `-32002` | File not found | Path doesn't exist in the workspace. Make sure it's absolute and inside the workspace root. |
| `-32003` | Symbol not found | No symbol at the offset. Check the offset lands on an identifier. |
| `-32004` | Conflict | File hashes don't match during apply-edits. A file changed after the plan was created. Re-plan, re-apply. |
| `-32005` | Capability not supported | Backend doesn't support this operation. Run `capabilities` to check. |
| `-32006` | Timeout | Operation hit the configured timeout. Tighten traversal bounds or raise the timeout. |

## Reading error responses

Every error response uses the same shape:

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

`code` is machine-readable. `message` is for humans and often
carries extra context about the failure.

## Next steps

- [Troubleshooting](../troubleshooting.md) — step-by-step guides
  for common problems
- [API reference](api-reference.md) — full method schemas and
  examples
