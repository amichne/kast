# Kast Command Reference

Full syntax, JSON schemas, and request-file formats for all public kast commands.

---

## Common Options

Every command accepts:

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `--workspace-root=` | absolute path | required | Workspace root to analyze |
| `--wait-timeout-ms=` | integer ms | 60000 | Max wait for a ready daemon |
| `--request-file=` | absolute path | — | JSON request payload on disk |

All options use `--key=value` syntax. No positional arguments.

---

## Workspace Commands

### `workspace status`

Inspect daemon descriptors, liveness, and readiness.

```
kast workspace status --workspace-root=/absolute/path
```

**Output — array of `RuntimeStatusResponse`:**

```json
[
  {
    "state": "READY",
    "healthy": true,
    "active": true,
    "indexing": false,
    "backendName": "kotlin-analysis",
    "backendVersion": "1.0.0",
    "workspaceRoot": "/absolute/path",
    "message": null,
    "schemaVersion": 1
  }
]
```

`state` values: `STARTING` | `INDEXING` | `READY` | `DEGRADED`

`active` = the selected daemon (true for at most one entry). `healthy` = process is live and responding. `indexing` = still building the index (queries may be slow or empty).

**Errors:** None specific. Exit 0 with empty array if no descriptor exists.

---

### `workspace ensure`

Reuse a ready daemon or start one; waits until ready before returning.

```
kast workspace ensure \
  --workspace-root=/absolute/path \
  [--wait-timeout-ms=60000]
```

**Output — `RuntimeStatusResponse`** (same schema as above, `state` = `READY`).

**Errors:**
- Timeout exceeded: exit non-zero, message on stderr.
- `DEGRADED` daemon: `ensure` will attempt to restart.

---

## Daemon Commands

### `daemon start`

Start a detached standalone daemon and wait for readiness.

```
kast daemon start \
  --workspace-root=/absolute/path \
  [--wait-timeout-ms=60000]
```

**Output — `RuntimeStatusResponse`** (`state` = `READY`).

**Errors:** Same timeout behavior as `workspace ensure`.

---

### `daemon stop`

Stop the registered daemon for a workspace and remove its descriptor.

```
kast daemon stop --workspace-root=/absolute/path
```

**Output — `RuntimeStatusResponse`** (the state of the daemon just before it was stopped).

**Errors:** Exit non-zero if no daemon is registered.

---

## `capabilities`

Print the capability set of the ready daemon.

```
kast capabilities \
  --workspace-root=/absolute/path \
  [--wait-timeout-ms=60000]
```

**Output — `BackendCapabilities`:**

```json
{
  "backendName": "kotlin-analysis",
  "backendVersion": "1.0.0",
  "workspaceRoot": "/absolute/path",
  "readCapabilities": ["RESOLVE_SYMBOL", "FIND_REFERENCES", "DIAGNOSTICS"],
  "mutationCapabilities": ["RENAME", "APPLY_EDITS"],
  "limits": {
    "maxResults": 500,
    "requestTimeoutMillis": 30000,
    "maxConcurrentRequests": 4
  },
  "schemaVersion": 1
}
```

`readCapabilities` values: `RESOLVE_SYMBOL` | `FIND_REFERENCES` | `CALL_HIERARCHY` | `DIAGNOSTICS`

`mutationCapabilities` values: `RENAME` | `APPLY_EDITS`

**Errors:** `CAPABILITY_NOT_SUPPORTED` if the daemon backend does not advertise the requested capability.

---

## Analysis Commands

### `symbol resolve`

Resolve the symbol at a file offset.

**Inline form:**
```
kast symbol resolve \
  --workspace-root=/absolute/path \
  --file-path=/absolute/path/to/File.kt \
  --offset=123
```

**Request-file form:**
```
kast symbol resolve \
  --workspace-root=/absolute/path \
  --request-file=/absolute/path/to/query.json
```

**Request file schema — `SymbolQuery`:**
```json
{
  "position": {
    "filePath": "/absolute/path/to/File.kt",
    "offset": 123
  }
}
```

`offset` is a zero-based UTF-16 character offset from the start of the file.

**Output — `SymbolResult`:**
```json
{
  "symbol": {
    "fqName": "io.example.MyClass.myFunction",
    "kind": "FUNCTION",
    "location": {
      "filePath": "/absolute/path/to/File.kt",
      "startOffset": 120,
      "endOffset": 130,
      "startLine": 10,
      "startColumn": 4,
      "preview": "fun myFunction()"
    },
    "type": "() -> Unit",
    "containingDeclaration": "io.example.MyClass"
  },
  "schemaVersion": 1
}
```

`kind` values: `CLASS` | `INTERFACE` | `OBJECT` | `FUNCTION` | `PROPERTY` | `CONSTRUCTOR` | `ENUM_ENTRY` | `TYPE_ALIAS` | `PACKAGE` | `PARAMETER` | `LOCAL_VARIABLE` | `UNKNOWN`

`type` and `containingDeclaration` are nullable.

**Errors:** `NOT_FOUND` if offset resolves to whitespace or a non-symbol token. `CAPABILITY_NOT_SUPPORTED` if daemon lacks `RESOLVE_SYMBOL`.

---

### `references`

Find all references to the symbol at a file offset.

**Inline form:**
```
kast references \
  --workspace-root=/absolute/path \
  --file-path=/absolute/path/to/File.kt \
  --offset=123 \
  [--include-declaration=true]
```

**Request-file form:**
```
kast references \
  --workspace-root=/absolute/path \
  --request-file=/absolute/path/to/query.json
```

**Request file schema — `ReferencesQuery`:**
```json
{
  "position": {
    "filePath": "/absolute/path/to/File.kt",
    "offset": 123
  },
  "includeDeclaration": false
}
```

**Output — `ReferencesResult`:**
```json
{
  "declaration": null,
  "references": [
    {
      "filePath": "/absolute/path/to/Other.kt",
      "startOffset": 450,
      "endOffset": 460,
      "startLine": 30,
      "startColumn": 8,
      "preview": "val x = myFunction()"
    }
  ],
  "page": {
    "truncated": false,
    "nextPageToken": null
  },
  "schemaVersion": 1
}
```

`declaration` is `Symbol | null` — populated when `includeDeclaration = true`.

`page.truncated = true` means results were capped by `limits.maxResults`. Currently no pagination — truncation is final.

**Errors:** `NOT_FOUND`, `CAPABILITY_NOT_SUPPORTED` (`FIND_REFERENCES`).

---

### `diagnostics`

Run diagnostics for one or more files.

**Inline form:**
```
kast diagnostics \
  --workspace-root=/absolute/path \
  --file-paths=/absolute/A.kt,/absolute/B.kt
```

**Request-file form:**
```
kast diagnostics \
  --workspace-root=/absolute/path \
  --request-file=/absolute/path/to/query.json
```

**Request file schema — `DiagnosticsQuery`:**
```json
{
  "filePaths": [
    "/absolute/path/to/A.kt",
    "/absolute/path/to/B.kt"
  ]
}
```

**Output — `DiagnosticsResult`:**
```json
{
  "diagnostics": [
    {
      "location": {
        "filePath": "/absolute/path/to/A.kt",
        "startOffset": 200,
        "endOffset": 215,
        "startLine": 15,
        "startColumn": 4,
        "preview": "val x: String = 42"
      },
      "severity": "ERROR",
      "message": "Type mismatch: inferred type is Int but String was expected",
      "code": "TYPE_MISMATCH"
    }
  ],
  "page": null,
  "schemaVersion": 1
}
```

`severity` values: `ERROR` | `WARNING` | `INFO`

`code` is nullable — may be absent for some diagnostic sources.

Empty `diagnostics` array means the files are clean.

**Errors:** `NOT_FOUND` if a file path does not exist in the workspace. `CAPABILITY_NOT_SUPPORTED` (`DIAGNOSTICS`).

---

### `rename`

Plan a rename operation. By default a dry run — does not write files.

**Inline form:**
```
kast rename \
  --workspace-root=/absolute/path \
  --file-path=/absolute/path/to/File.kt \
  --offset=123 \
  --new-name=RenamedSymbol \
  [--dry-run=true]
```

**Request-file form:**
```
kast rename \
  --workspace-root=/absolute/path \
  --request-file=/absolute/path/to/query.json
```

**Request file schema — `RenameQuery`:**
```json
{
  "position": {
    "filePath": "/absolute/path/to/File.kt",
    "offset": 123
  },
  "newName": "RenamedSymbol",
  "dryRun": true
}
```

`dryRun` defaults to `true`. Pass `false` only when you intend to apply edits immediately via `edits apply`.

**Output — `RenameResult`:**
```json
{
  "edits": [
    {
      "filePath": "/absolute/path/to/File.kt",
      "startOffset": 120,
      "endOffset": 130,
      "newText": "RenamedSymbol"
    }
  ],
  "fileHashes": [
    {
      "filePath": "/absolute/path/to/File.kt",
      "hash": "sha256:abcdef1234567890..."
    }
  ],
  "affectedFiles": [
    "/absolute/path/to/File.kt",
    "/absolute/path/to/Other.kt"
  ],
  "schemaVersion": 1
}
```

`fileHashes` are SHA-256 hashes of each affected file at the time the plan was generated. Pass them unchanged to `edits apply` to guard against concurrent modification.

**Errors:** `NOT_FOUND`, `VALIDATION_ERROR` (invalid new name), `CAPABILITY_NOT_SUPPORTED` (`RENAME`).

---

### `edits apply`

Apply a prepared edit plan. Always requires `--request-file`.

```
kast edits apply \
  --workspace-root=/absolute/path \
  --request-file=/absolute/path/to/query.json
```

**Request file schema — `ApplyEditsQuery`:**
```json
{
  "edits": [
    {
      "filePath": "/absolute/path/to/File.kt",
      "startOffset": 120,
      "endOffset": 130,
      "newText": "RenamedSymbol"
    }
  ],
  "fileHashes": [
    {
      "filePath": "/absolute/path/to/File.kt",
      "hash": "sha256:abcdef1234567890..."
    }
  ]
}
```

Use `rename` output's `edits` and `fileHashes` fields directly as the request body.

**Output — `ApplyEditsResult`:**
```json
{
  "applied": [
    {
      "filePath": "/absolute/path/to/File.kt",
      "startOffset": 120,
      "endOffset": 130,
      "newText": "RenamedSymbol"
    }
  ],
  "affectedFiles": [
    "/absolute/path/to/File.kt"
  ],
  "schemaVersion": 1
}
```

**Errors:**
- `CONFLICT` (409): a file was modified after the rename plan was generated. The `details` map lists affected paths. Re-run `rename` to get a fresh plan.
- `APPLY_PARTIAL_FAILURE` (500): commit phase failed for one or more files. `details` maps file paths to error messages. Files not in `details` were written successfully. Manual inspection required.

---

## Shell Integration

### `completion bash`

Emit a Bash completion script.

```
kast completion bash
```

Source the output to enable tab completion for kast commands and options in Bash.

### `completion zsh`

Emit a Zsh completion script.

```
kast completion zsh
```

Source the output to enable tab completion for kast commands and options in Zsh.

---

## CLI Management

### `install`

Install a portable kast archive as a named local instance.

```
kast install \
  --archive=/absolute/path/to/kast.zip \
  [--instance=my-dev]
```

**Options:**

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `--archive=` | absolute path | required | Path to a kast portable zip |
| `--instance=` | string | auto-generated | Instance name for the install |

Installs to `~/.local/share/kast/instances/<name>/` with a launcher at `~/.local/bin/kast-<name>`.

**Output:** Prints the installed instance name and launcher path.

**Errors:** Exit non-zero if the archive is invalid or the instance name contains invalid characters.

---

## Error Response Format

All errors return `ApiErrorResponse` on stdout with a non-zero exit code:

```json
{
  "schemaVersion": 1,
  "requestId": "req-abc123",
  "code": "NOT_FOUND",
  "message": "No symbol found at offset 123 in File.kt",
  "retryable": false,
  "details": {}
}
```

| `code` | HTTP | `retryable` | Meaning |
|--------|------|-------------|---------|
| `VALIDATION_ERROR` | 400 | false | Bad request parameters |
| `UNAUTHORIZED` | 401 | false | Invalid auth token |
| `NOT_FOUND` | 404 | false | Symbol/file not found |
| `CONFLICT` | 409 | false | File modified since plan; re-plan needed |
| `CAPABILITY_NOT_SUPPORTED` | 501 | false | Backend lacks this capability |
| `APPLY_PARTIAL_FAILURE` | 500 | false | Partial write failure; inspect details |
