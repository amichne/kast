---
name: kast
description: >
  Use this skill for any Kotlin/JVM semantic code intelligence task: resolve a symbol,
  find references, run diagnostics, plan a rename, apply edits, inspect daemon status,
  query capabilities, or manage the kast workspace daemon lifecycle. Triggers on:
  "resolve symbol", "find references", "kast", "rename symbol", "run diagnostics",
  "apply edits", "workspace ensure", "daemon start", "daemon stop", "capabilities",
  "symbol at offset", "semantic analysis", "kotlin analysis daemon".
  Handles CLI discovery, workspace lifecycle, output interpretation, and error recovery.
---

# Kast Skill

kast is a Kotlin analysis daemon with a CLI control plane. It provides semantic code
intelligence (symbol resolution, find references, diagnostics, rename, edit plans) for
Kotlin/JVM workspaces.

---

## 1. CLI Discovery

Never hardcode the kast binary path. Always resolve it first:

```bash
KAST=$(bash .agents/skills/kast/scripts/resolve-kast.sh)
```

The script tries in order:
1. `kast` on PATH
2. `kast/build/scripts/kast` (Gradle build output)
3. `dist/kast/kast` (make cli output)
4. Auto-build via `./gradlew :kast:writeWrapperScript` if Java 21+ and gradlew present

On success: prints the absolute path to stdout, exit 0.
On failure: prints diagnostic to stderr, exit 1. See `references/troubleshooting.md#kast-not-found`.

**Once resolved, use `$KAST` for every subsequent command:**
```bash
"$KAST" workspace ensure --workspace-root=/absolute/path
"$KAST" symbol resolve --workspace-root=/absolute/path --file-path=... --offset=...
```

---

## 2. Workspace Lifecycle

**Always run `workspace ensure` before any analysis command.**

```bash
"$KAST" workspace ensure --workspace-root=/absolute/path/to/workspace
```

This starts a new daemon if none exists, or reuses an existing ready one.
It blocks until the daemon is `READY` (default: 60s timeout).

### Daemon state machine

```
STARTING → INDEXING → READY
                    → DEGRADED (restart via workspace ensure)
```

| State | Meaning | Action |
|-------|---------|--------|
| `STARTING` | Bootstrapping JVM | Wait; retry |
| `INDEXING` | Building index | Wait; queries may be empty |
| `READY` | Fully operational | Proceed with analysis |
| `DEGRADED` | Unhealthy | Run `workspace ensure` to restart |

### Check status without ensuring

```bash
"$KAST" workspace status --workspace-root=/absolute/path
```

Returns an array of `RuntimeStatusResponse`. Check `state`, `healthy`, `active`.

### Stop the daemon

```bash
"$KAST" daemon stop --workspace-root=/absolute/path
```

Run in cleanup steps. Exit non-zero if no daemon is registered (safe to ignore).

---

## 3. Capabilities

Check what the daemon supports before running analysis commands:

```bash
"$KAST" capabilities --workspace-root=/absolute/path
```

| Capability | Required by |
|------------|-------------|
| `RESOLVE_SYMBOL` | `symbol resolve` |
| `FIND_REFERENCES` | `references` |
| `DIAGNOSTICS` | `diagnostics` |
| `CALL_HIERARCHY` | (not implemented — known gap) |
| `RENAME` | `rename` |
| `APPLY_EDITS` | `edits apply` |

If a needed capability is absent, see `references/troubleshooting.md#capability_not_supported`.

---

## 4. Analysis Commands

All commands:
- Output machine-readable JSON on stdout
- Output daemon lifecycle notes to stderr (not part of the result)
- Use `--key=value` syntax for every option
- Require absolute paths for `--workspace-root`, `--file-path`, `--file-paths`
- `offset` = zero-based UTF-16 character offset from start of file

### symbol resolve

Identify what a token is — its fully qualified name, kind, type, and declaration location.

```bash
"$KAST" symbol resolve \
  --workspace-root=/absolute/path \
  --file-path=/absolute/path/to/File.kt \
  --offset=123
```

Key output fields: `symbol.fqName`, `symbol.kind`, `symbol.location`, `symbol.type`

Use this to: confirm you are targeting the right symbol before rename; get the FQ name for documentation; navigate to the declaration.

### references

Find all call sites and usages of a symbol across the workspace.

```bash
"$KAST" references \
  --workspace-root=/absolute/path \
  --file-path=/absolute/path/to/File.kt \
  --offset=123 \
  [--include-declaration=true]
```

Key output fields: `references` (array of `Location`), `declaration` (if requested), `page.truncated`

Use this to: assess rename impact; find all callers before changing a signature; check if a symbol is used anywhere.

If `page.truncated = true`, results were capped at `limits.maxResults`. No pagination — this is the complete available set.

### diagnostics

Get compile errors and warnings for specific files.

```bash
"$KAST" diagnostics \
  --workspace-root=/absolute/path \
  --file-paths=/absolute/A.kt,/absolute/B.kt
```

Key output fields: `diagnostics[].severity`, `diagnostics[].message`, `diagnostics[].location`, `diagnostics[].code`

`severity`: `ERROR` | `WARNING` | `INFO`

Empty `diagnostics` array means the files are clean.

Use this to: validate edits after applying them; triage build failures at the semantic level; check a file after refactoring.

### rename

Plan a rename operation — produces an edit plan without modifying files.

```bash
"$KAST" rename \
  --workspace-root=/absolute/path \
  --file-path=/absolute/path/to/File.kt \
  --offset=123 \
  --new-name=RenamedSymbol \
  --dry-run=true
```

Key output fields: `edits` (array of `TextEdit`), `fileHashes` (integrity tokens), `affectedFiles`

`fileHashes` are SHA-256 hashes of affected files at plan time — pass them to `edits apply` unchanged.

### edits apply

Commit a prepared edit plan to disk. Always requires `--request-file`.

```bash
# Write the rename result to disk first
cat > /tmp/apply-query.json << 'EOF'
{
  "edits": [...],
  "fileHashes": [...]
}
EOF

"$KAST" edits apply \
  --workspace-root=/absolute/path \
  --request-file=/tmp/apply-query.json
```

Key output fields: `applied` (edits that were written), `affectedFiles`

Use the `edits` and `fileHashes` from a `rename` result directly as the request body.

---

## 5. Workflows

### Pre-Edit Intelligence

Before modifying a symbol, gather context:

```
1. workspace ensure
2. symbol resolve  → confirm symbol identity and kind
3. references      → assess how many call sites exist
4. capabilities    → confirm RENAME/APPLY_EDITS available if planning rename
```

### Safe Rename

```
1. workspace ensure
2. symbol resolve (--offset=<declaration offset>)  → confirm target
3. rename --dry-run=true --new-name=NewName        → inspect affected files
4. Review rename result edits (count, file spread)
5. Write {edits, fileHashes} to /tmp/rename-apply.json
6. edits apply --request-file=/tmp/rename-apply.json
7. diagnostics on affected files                   → verify no new errors
```

If `edits apply` returns `CONFLICT`: re-run `rename` to get a fresh plan with updated hashes.

### Post-Edit Validation

After any code change (rename, manual edit, or generated edit):

```
1. workspace ensure (daemon may need to re-index)
2. diagnostics --file-paths=<all modified files>
3. If diagnostics are clean: proceed
4. If diagnostics show errors: inspect location + message, fix, repeat
```

### Diagnostic Triage

When a build fails or you need to understand errors in a file:

```
1. workspace ensure
2. diagnostics --file-paths=<file>
3. For each ERROR diagnostic:
   a. symbol resolve at diagnostic startOffset → identify the symbol
   b. references → check if the issue is a broken call site
   c. Fix the issue
4. diagnostics again → confirm clean
```

---

## 6. Error Recovery

| Error code | HTTP | Recovery |
|-----------|------|----------|
| `VALIDATION_ERROR` | 400 | Fix request parameters (file path, offset, new name) |
| `UNAUTHORIZED` | 401 | Check auth token configuration |
| `NOT_FOUND` | 404 | Offset may be on whitespace/comment. Adjust to identifier start. Wait for READY state if still indexing. |
| `CONFLICT` | 409 | Files changed since plan. Re-run `rename` for a fresh plan. |
| `CAPABILITY_NOT_SUPPORTED` | 501 | Run `capabilities` to see what is available. Fall back to grep for search. |
| `APPLY_PARTIAL_FAILURE` | 500 | Inspect `details` map. Already-applied files are committed. Fix root cause; apply remaining manually. |

For detailed decision trees: `references/troubleshooting.md`

---

## 7. Command Syntax

**Do:**
- Use `--key=value` for every option: `--workspace-root=/path`, `--offset=123`
- Use absolute paths for all file arguments
- Separate multiple commands by running them sequentially
- Use `--request-file` for `edits apply` (always) and for complex queries

**Do not:**
- Do not use `callHierarchy` — not yet implemented
- Do not use hyphenated pseudo-commands: `workspace-status`, `symbol-resolve`, `edits-apply`
- Do not use repo-local wrapper paths (`./kast/build/scripts/kast`) directly — use `resolve-kast.sh`
- Do not pass relative paths to `--workspace-root`, `--file-path`, or `--file-paths`
- Do not inspect `.kast/instances/` descriptor JSON directly
- Do not call HTTP transport endpoints directly

---

## 8. Integration

| Task | Use |
|------|-----|
| Resolve a symbol at an offset | kast `symbol resolve` |
| Find all references across workspace | kast `references` |
| Get compile errors for a file | kast `diagnostics` |
| Plan and apply a safe rename | kast `rename` + `edits apply` |
| Find text patterns in comments/strings | Grep (kast does not search text) |
| Build the project | `kotlin-gradle-loop` / `./gradlew build` |
| Run tests | `kotlin-gradle-loop` / `./gradlew test` |
| Check if code compiles | `kotlin-gradle-loop` or kast `diagnostics` (faster) |

kast = **semantic intelligence** (what is this symbol, where is it used, rename it).
kotlin-gradle-loop = **build and test iteration** (does it compile, do tests pass).

---

## 9. Reference Documents

| Document | When to read |
|----------|-------------|
| `references/command-reference.md` | Full JSON input/output schemas for any command |
| `references/troubleshooting.md` | Step-by-step recovery for specific error codes or symptoms |
| `references/cloud-setup.md` | Installing kast in CI, Docker, or headless environments |
