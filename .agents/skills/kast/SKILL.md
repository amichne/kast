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

Install this packaged skill into a workspace with `kast-skilled`. The command creates a
repository-local symlink, for example `.agents/skills/kast`, that points back to the
single packaged skill root from `KAST_SKILL_PATH`.

---

## 1. CLI Discovery

Never hardcode the kast binary path. Try in this order:

### Preferred: kast on PATH

```bash
kast version
```

If this succeeds, use `kast` directly for all commands.

### Fallback: resolve-kast.sh

If `kast` is not on PATH, use the helper script:

```bash
KAST=$(bash scripts/resolve-kast.sh)
```

The script tries: PATH → `KAST_CLI_PATH` env var → `KAST_SOURCE_ROOT` Gradle/dist builds → auto-build via `./gradlew :kast:writeWrapperScript`.

**Environment variables the script respects:**
- `KAST_CLI_PATH` — absolute path to a kast binary (bypasses all discovery)
- `KAST_SOURCE_ROOT` — repo root containing locally built `kast/build/scripts/kast` or `dist/kast/kast`

**Note:** This script requires the installed `kast` skill directory to be present relative
to the workspace root (`.agents/skills/kast/`). Run `kast-skilled` first if the skill link is missing.
If the path traversal still fails, fall back to installing kast directly (`./install.sh` or `./build.sh --install`).

If you need to call the packaged helper scripts by path, set
`SKILL_ROOT=/absolute/path/to/your/installed/kast-skill` and run them from
`"$SKILL_ROOT/scripts/..."`.

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
         → FAILED   (daemon exited before READY — check the log)
```

| State | Meaning | Action |
|-------|---------|--------|
| `STARTING` | Bootstrapping JVM | Wait; retry |
| `INDEXING` | Building index | Wait; queries may be empty |
| `READY` | Fully operational | Proceed with analysis |
| `DEGRADED` | Unhealthy | Run `workspace ensure` to restart |
| `FAILED` | Daemon exited before READY | Check the log |

### When `workspace ensure` times out

A timeout from `workspace ensure` is a symptom, not the root cause. The daemon may have
exited before it could register itself as ready. **Do not retry blindly.**

**Step 1 — Check the daemon log immediately:**

```bash
cat .kast/logs/standalone-daemon.log | tail -50
```

This is where the real error lives. Look for:

- `SocketException: Operation not permitted` → Unix domain socket bind blocked (see below)
- `Address already in use` → Port/socket conflict; run `daemon stop` then `workspace ensure`
- `OutOfMemoryError` → JVM heap too small; set `KAST_DAEMON_OPTS=-Xmx2g` or equivalent
- Java version errors → Requires Java 21+
- Classpath/JAR errors → Binary may be corrupt; reinstall

**Step 2 — Check workspace status:**

```bash
"$KAST" workspace status --workspace-root=/absolute/path
```

If this returns `selected: null` and `candidates: []`, the daemon failed to start and
register before exiting. This does **not** mean "no daemon is configured" — it means the
startup attempt failed silently. The log is the authoritative source.

### Unix domain socket bind failure

**Symptom:** `workspace ensure` times out; log contains `SocketException: Operation not permitted`
during socket bind; no descriptor written to `.kast/instances/`.

**Cause:** The environment (sandbox, container, macOS app sandbox) is blocking Unix domain
socket creation. This is a transport/environment constraint, not an indexing problem.

**Recovery options (in order):**

1. **Run outside the sandbox** — restart the terminal/process outside any app sandbox
   restrictions and retry.

2. **Check if UDS is blocked by policy:**
   ```bash
   python3 -c "import socket; s=socket.socket(AF_UNIX); s.bind('/tmp/kast-test.sock'); print('UDS OK')"
   ```
   If this fails with `Operation not permitted`, the environment is blocking UDS at the OS level.

3. **Container/CI:** Add `AF_UNIX` to the seccomp allowlist. See `references/cloud-setup.md`.

### Check status without ensuring

```bash
"$KAST" workspace status --workspace-root=/absolute/path
```

Returns an array of `RuntimeStatusResponse`. Check `state`, `healthy`, `active`.
An empty response (`selected: null`, `candidates: []`) indicates the daemon never
successfully started and registered — check the log for the startup error.

### Start the daemon explicitly

```bash
"$KAST" daemon start --workspace-root=/absolute/path
```

Starts a detached standalone daemon and waits for readiness. Equivalent to `workspace ensure`
but does not reuse existing candidates — always starts fresh.

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

| Capability | Required by | Status |
|------------|-------------|--------|
| `RESOLVE_SYMBOL` | `symbol resolve` | Implemented |
| `FIND_REFERENCES` | `references` | Implemented |
| `DIAGNOSTICS` | `diagnostics` | Implemented |
| `CALL_HIERARCHY` | (not implemented) | Not implemented — known gap |
| `RENAME` | `rename` | Implemented |
| `APPLY_EDITS` | `edits apply` | Implemented |

If a needed capability is absent, see `references/troubleshooting.md#capability_not_supported`.

---

## 4. Index Validation

"The daemon is running" and "the index is serving semantic results" are different things.
Do not assume indexing is healthy just because `workspace ensure` succeeded or state is `READY`.

**Index is validated only when all four criteria pass:**

1. `workspace ensure` completes with state `READY`
2. `capabilities` returns the expected capabilities (at minimum `RESOLVE_SYMBOL` and `FIND_REFERENCES`)
3. `symbol resolve` succeeds on a known declaration in the workspace
4. `references` returns actual cross-file results for that symbol

**Quick smoke test** — run these in sequence after `workspace ensure`:

```bash
# 1. Check capabilities
"$KAST" capabilities --workspace-root=/absolute/path

# 2. Find a declaration in the workspace (pick any class or top-level function)
python "$SKILL_ROOT/scripts/find-symbol-offset.py" \
  /absolute/path/to/SomeFile.kt --symbol SomeClass

# 3. Resolve it
"$KAST" symbol resolve \
  --workspace-root=/absolute/path \
  --file-path=/absolute/path/to/SomeFile.kt \
  --offset=<offset>

# 4. Find references
"$KAST" references \
  --workspace-root=/absolute/path \
  --file-path=/absolute/path/to/SomeFile.kt \
  --offset=<offset>
```

If step 3 or 4 returns `NOT_FOUND` and state is `READY`, the workspace may still be
finishing its initial index pass. Wait 10–30 seconds and retry.

---

## 5. Resolving Named Symbols (Conversational Lookups)

Most kast commands need `--offset`, but users will usually describe the target
in conversational terms: a class, a function, or a field-like property ("find
references to `MyRepository`", "resolve the `baseUrl` property on
`HttpClientConfig`", "rename `handleClick`"). Bridge that human reference to a
concrete file + position before you call kast.

### Step 1 — Locate the symbol in the codebase

Use Grep to find declaration sites. Declaration patterns to try (in order):

```bash
# class / object / interface / enum / sealed / data class
grep -rn "class MyClass\b" --include="*.kt" /workspace

# fun
grep -rn "fun myFunction\b" --include="*.kt" /workspace

# val / var (property or field-like member)
grep -rn "\bval myProp\b\|\bvar myProp\b" --include="*.kt" /workspace

# fallback: any occurrence
grep -rn "\bMySymbol\b" --include="*.kt" /workspace
```

Pick the declaration site (the line with `class`/`fun`/`val`/`var`/etc.) over plain usages.

### Step 2 — Compute the UTF-16 offset

`find-symbol-offset.py` converts the grep result to the exact offset kast needs:

```bash
# From a symbol name — prints declaration sites first:
python "$SKILL_ROOT/scripts/find-symbol-offset.py" \
  /absolute/path/to/File.kt \
  --symbol MyClass

# From a known line (1-based) and column (0-based, default 0):
python "$SKILL_ROOT/scripts/find-symbol-offset.py" \
  /absolute/path/to/File.kt \
  --line 42 --col 6
```

Output (one line per match):
```
<offset>\t<line>\t<col>\t<context-snippet>
```

Take the **first line** — that is the declaration offset (or the closest match when no
declaration is found). Feed `<offset>` to `--offset=`.

### Step 3 — Verify with symbol resolve

Before running references or rename, confirm you have the right symbol:

```bash
"$KAST" symbol resolve \
  --workspace-root=/absolute/path \
  --file-path=/absolute/path/to/File.kt \
  --offset=<offset-from-script>
```

Check `symbol.fqName` matches what the user described. If it does not (e.g. offset landed on
whitespace), try the next offset from the script output or adjust `--col` by a few characters
to the start of the identifier.

### Quick pattern — name to references

```
User: "find references to HealthCheckService"

1. Grep:   grep -rn "class HealthCheckService" --include="*.kt" /workspace
           → src/main/kotlin/com/example/HealthCheckService.kt:12:class HealthCheckService(...)

2. Offset: python find-symbol-offset.py .../HealthCheckService.kt --symbol HealthCheckService
           → 347  12  6  class HealthCheckService(private val ...

3. Verify: kast symbol resolve --offset=347  → fqName: com.example.HealthCheckService ✓

4. Run:    kast references --file-path=.../HealthCheckService.kt --offset=347
```

### Quick pattern — property lookup

```
User: "resolve the retryDelay property on RetryConfig"

1. Grep:   grep -rn "\bval retryDelay\b\|\bvar retryDelay\b" --include="*.kt" /workspace
           → src/main/kotlin/com/example/RetryConfig.kt:18:    val retryDelay: Duration

2. Offset: python find-symbol-offset.py .../RetryConfig.kt --symbol retryDelay
           → 512  18  8  val retryDelay: Duration

3. Verify: kast symbol resolve --offset=512
           → kind: PROPERTY, containingDeclaration: com.example.RetryConfig ✓
```

---

## 6. Analysis Commands

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

## 7. Workflows

### Pre-Edit Intelligence

Before modifying a symbol, gather context:

```
1. workspace ensure
2. find offset (Section 5 — grep + find-symbol-offset.py if starting from a name)
3. symbol resolve  → confirm symbol identity and kind
4. references      → assess how many call sites exist
5. capabilities    → confirm RENAME/APPLY_EDITS available if planning rename
```

### Safe Rename

```
1. workspace ensure
2. find offset: grep for declaration, then find-symbol-offset.py --symbol <Name>
3. symbol resolve (--offset=<offset>)              → confirm target
4. rename --dry-run=true --new-name=NewName        → inspect affected files
5. Review rename result edits (count, file spread)
6. Write {edits, fileHashes} to /tmp/rename-apply.json
7. edits apply --request-file=/tmp/rename-apply.json
8. diagnostics on affected files                   → verify no new errors
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

### First-Run Validation

When setting up kast in a new environment, validate the full stack before doing real work:

```
1. workspace ensure                              → must reach READY
2. capabilities                                 → must include RESOLVE_SYMBOL, FIND_REFERENCES
3. grep for any class declaration in the workspace
4. find-symbol-offset.py → get offset
5. symbol resolve at that offset                → must return a valid fqName
6. references at that offset                    → must return results (not empty)
```

Only once all six steps pass is the index confirmed healthy. If step 5 or 6 returns
`NOT_FOUND` with state `READY`, the index may still be warming up — wait and retry.

---

## 8. Error Recovery

| Error / Symptom | Likely Cause | Recovery |
|----------------|-------------|----------|
| `VALIDATION_ERROR` (400) | Bad request parameters | Fix file path, offset, or new name |
| `UNAUTHORIZED` (401) | Auth token missing | Check auth token configuration |
| `NOT_FOUND` (404) | Offset on whitespace/comment, or still indexing | Adjust offset to identifier start. Verify state is READY. |
| `CONFLICT` (409) | Files changed since rename plan | Re-run `rename` for a fresh plan |
| `CAPABILITY_NOT_SUPPORTED` (501) | Capability absent or daemon too old | Run `capabilities`. Fall back to grep for search. |
| `APPLY_PARTIAL_FAILURE` (500) | Permissions or disk error mid-apply | Inspect `details` map. Fix root cause; apply remaining manually. |
| `RUNTIME_TIMEOUT` from `workspace ensure` | Daemon failed to start | Check `.kast/logs/standalone-daemon.log` for root cause |
| `selected: null` from `workspace status` | Daemon crashed before registering | Check `.kast/logs/standalone-daemon.log`; daemon never reached READY |
| `SocketException: Operation not permitted` in log | UDS bind blocked by sandbox/container | Run outside sandbox; check seccomp in CI |

For detailed decision trees: `references/troubleshooting.md`

---

## 9. Command Syntax

**Do:**
- Use `--key=value` for every option: `--workspace-root=/path`, `--offset=123`
- Use absolute paths for all file arguments
- Use `--request-file` for `edits apply` (always) and for complex queries
- Check `.kast/logs/standalone-daemon.log` when `workspace ensure` fails or times out
- Run `kast-skilled` before first use to install the skill into the workspace

**Do not:**
- Do not use `callHierarchy` — not implemented
- Do not use hyphenated pseudo-commands: `workspace-status`, `symbol-resolve`, `edits-apply`
- Do not use repo-local wrapper paths (`./kast/build/scripts/kast`) directly — use `resolve-kast.sh`
- Do not pass relative paths to `--workspace-root`, `--file-path`, or `--file-paths`
- Do not inspect `.kast/instances/` descriptor JSON directly
- Do not call HTTP transport endpoints directly
- Do not treat `RUNTIME_TIMEOUT` as the root cause — read the log first

---

## 10. Integration

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
| Diagnose daemon startup failure | Read `.kast/logs/standalone-daemon.log` |

kast = **semantic intelligence** (what is this symbol, where is it used, rename it).
kotlin-gradle-loop = **build and test iteration** (does it compile, do tests pass).

---

## 11. Building and Installing kast

### From source (development)

```bash
./build.sh              # builds and prompts to install as a dev instance
./build.sh --no-install # builds only, no install
./build.sh --install --instance my-dev  # builds and installs as "my-dev"
```

Outputs: `dist/kast/` (directory) and `dist/kast.zip` (portable archive).

### From GitHub release (production)

```bash
./install.sh            # downloads and installs latest release
```

Installs to `~/.local/share/kast/` with launcher at `~/.local/bin/kast`.

---

## 12. Reference Documents

| Document | When to read |
|----------|-------------|
| `references/command-reference.md` | Full JSON input/output schemas for any command |
| `references/troubleshooting.md` | Step-by-step recovery for specific error codes or symptoms |
| `references/cloud-setup.md` | Installing kast in CI, Docker, or headless environments |
