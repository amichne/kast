# Kast Troubleshooting

Decision trees for every known failure mode.

---

## Daemon Won't Start

**Symptom:** `workspace ensure` or `daemon start` exits non-zero; daemon never reaches `READY`.

**First step — always read the log:**
```bash
cat .kast/logs/standalone-daemon.log | tail -50
```

```
Is Java 21+ available?
├─ No  → Install Java 21. Check: java -version. Set JAVA_HOME if needed.
└─ Yes
   Is the kast binary executable?
   ├─ No  → chmod +x on the binary. Re-run resolve-kast.sh.
   └─ Yes
      Does the log contain "SocketException: Operation not permitted"?
      ├─ Yes → Unix domain socket bind is blocked. See "Unix Domain Socket Bind Failure" below.
      └─ No
         Is there a stale descriptor (.kast/instances/)?
         ├─ Yes → Run: kast daemon stop --workspace-root=...
         │         If stop fails, remove the descriptor file manually, then retry.
         └─ No
            Check stderr output for port/socket conflicts.
            The daemon uses a Unix domain socket; ensure /tmp is writable.
            Try: kast workspace status --workspace-root=... for partial state.
```

---

## Unix Domain Socket Bind Failure

**Symptom:** `workspace ensure` times out with `RUNTIME_TIMEOUT`; daemon log contains
`java.net.SocketException: Operation not permitted` during socket bind; `workspace status`
returns `selected: null` and `candidates: []`.

**Cause:** The operating environment (macOS app sandbox, container seccomp profile,
CI runner policy) is blocking Unix domain socket creation. This is a transport constraint,
not an indexing or configuration problem. The daemon JVM starts successfully but cannot
bind its communication socket, so it exits before reaching `READY`.

```
Verify UDS is blocked:
  python3 -c "import socket; s=socket.socket(socket.AF_UNIX); s.bind('/tmp/kast-test.sock'); print('UDS OK')"
  → If this raises "Operation not permitted", UDS is blocked at the OS level.

Recovery option 1 — Run outside the sandbox:
  → Restart your terminal or process host outside any app sandbox restrictions.
  → On macOS, this typically means running directly in Terminal.app or iTerm2
    rather than in a sandboxed IDE or tool process.

Recovery option 2 — Container/CI:
  → Add AF_UNIX to the seccomp allowlist.
  → See references/cloud-setup.md for CI-specific guidance.
```

**Important:** Once the transport constraint is resolved, kast reaches `READY` and the
index is typically available immediately. The underlying indexing is not affected by this
failure — only the daemon's ability to communicate.

---

## Daemon Not Ready / Timeout

**Symptom:** `workspace ensure` returns but `state` is `STARTING` or `INDEXING`, or the command times out.

A timeout is a symptom, not the root cause. When `workspace ensure` times out, always
inspect the daemon log before retrying:

```bash
cat .kast/logs/standalone-daemon.log | tail -50
```

```
workspace ensure timed out
  → Read .kast/logs/standalone-daemon.log immediately
  → Look for: SocketException, OutOfMemoryError, ClassNotFoundException, port conflicts
  → If the log shows the daemon exited, that is the real cause — fix it, then retry

state = STARTING
  → Workspace is still bootstrapping the Kotlin compiler. Wait and retry.
  → Increase timeout: --wait-timeout-ms=120000

state = INDEXING
  → Initial index build in progress. Analysis commands will return empty or
    partial results. Wait until state = READY.
  → For large workspaces, first ensure can take 30–120s.

state = DEGRADED
  → Daemon is unhealthy. workspace ensure will attempt to restart it.
  → If restart fails: daemon stop, then workspace ensure again.
  → Check disk space and memory (JVM heap may need tuning).

workspace status returns selected: null, candidates: []
  → The daemon started but exited before registering a ready descriptor.
  → This does NOT mean "no daemon configured" — it means startup failed silently.
  → Read .kast/logs/standalone-daemon.log for the exit reason.
```

---

## Stale Descriptor

**Symptom:** `workspace status` shows a daemon that isn't running; `healthy: false`.

```
Run: kast daemon stop --workspace-root=...
If stop exits non-zero (process already gone):
  Locate the descriptor in .kast/instances/ under the workspace root.
  Remove the stale JSON file.
Then: kast workspace ensure --workspace-root=...
```

---

## CONFLICT on edits apply

**Symptom:** `edits apply` exits non-zero; error code `CONFLICT` (409).

```
Cause: one or more files were modified after rename generated the plan.

Recovery:
  1. Re-run rename with the same parameters to get a fresh plan.
  2. Inspect details map in the error response to see which files changed.
  3. If files were changed by your own edits, verify intent before re-applying.
  4. Write the new RenameResult edits/fileHashes to the request file.
  5. Run edits apply again.

Do NOT manually merge old and new edits — always start from a fresh rename plan.
```

---

## CAPABILITY_NOT_SUPPORTED

**Symptom:** Error code `CAPABILITY_NOT_SUPPORTED` (501); details contain a `capability` key.

```
Run: kast capabilities --workspace-root=...
Check readCapabilities and mutationCapabilities arrays.

Missing RESOLVE_SYMBOL  → symbol resolve unavailable; use grep for text search.
Missing FIND_REFERENCES → references unavailable; use grep for text search.
Missing DIAGNOSTICS     → diagnostics unavailable; run the project's
                           configured `gradleHook` through `kotlin-gradle-loop`.
Missing RENAME          → rename unavailable; manual find-and-replace required.
Missing APPLY_EDITS     → edits apply unavailable; apply edits manually.
Missing CALL_HIERARCHY  → call hierarchy unavailable in this backend; confirm
                          the runtime version and capability gating, then retry.

If a needed capability is missing, the backend version may be too old
or the workspace may be in a partial initialization state. Restart the
daemon and check again.
```

---

## APPLY_PARTIAL_FAILURE

**Symptom:** Error code `APPLY_PARTIAL_FAILURE` (500); some files were written, others were not.

```
Inspect: details map — keys are file paths, values are error messages.

Files listed in details: NOT written. Fix the root cause (permissions, disk).
Files in applied array: already written to disk.

Recovery options:
  A. Fix the root cause, then manually apply the failed edits using the
     original RenameResult (re-run edits apply with just the failed files).
  B. If the workspace is now inconsistent, run diagnostics to assess damage,
     then re-plan the rename from scratch.

Do NOT re-run the original edits apply wholesale — already-applied edits
will produce offset conflicts.
```

---

## Empty Diagnostics

**Symptom:** `diagnostics` returns an empty array when errors are expected.

```
Is the daemon still indexing?
├─ Yes → Wait for state = READY, then retry.
└─ No
   Are the file paths absolute?
   ├─ No  → All file paths must be absolute. Check --file-paths= values.
   └─ Yes
      Is the file inside the workspace root?
      ├─ No  → Files outside workspace root are not indexed.
      └─ Yes
         Run workspace status to verify healthy: true.
         If degraded, restart and retry.
```

**Tip — use `kast-plan-utils.py check-diagnostics` in scripts** to reliably detect errors
without `jq` or shell quoting:

```bash
"$KAST" diagnostics \
  --workspace-root=/absolute/path \
  --file-paths="$FILES_CSV" > /tmp/diag.json

python3 "$SKILL_ROOT/scripts/kast-plan-utils.py" check-diagnostics /tmp/diag.json
# exits 0 if clean; exits 1 and prints details to stderr if any ERROR diagnostics found
```

---

## Scripted Workflows: Shell Quoting Failures

**Symptom:** A rename or diagnostics pipeline silently produces wrong output, fails with
`jq: compile error`, or a shell variable is reported as unbound inside `resolve-kast.sh`.

**Root cause checklist:**

```
Is resolve-kast.sh failing with "unbound variable"?
├─ Yes → Verify GRADLE_SCRIPT / DIST_SCRIPT are initialised before use.
│        The fixed version pre-assigns both to "" at the top of the script.
│        Re-install or update the skill if running an older version.
└─ No
   Is jq being used to build JSON or extract arrays with nested quoting?
   ├─ Yes → Replace with kast-plan-utils.py subcommands:
   │         extract-apply-request  — builds the apply-request file
   │         affected-files-csv     — produces the --file-paths CSV
   │         check-diagnostics      — checks diagnostic results
   └─ No
      Is the workflow run with bash -lc '...' or sh instead of plain bash?
      ├─ Yes → Use a bash heredoc or a separate .sh script instead.
      │         Single-quoted multi-command strings passed to bash -lc
      │         are fragile with nested jq expressions.
      └─ No
         Is kast-rename.sh the right fit?
         → Use kast-rename.sh for end-to-end rename automation:
           it handles all JSON steps internally, uses mktemp + trap,
           and needs no jq or inline JSON construction.
```

**Recommended pattern — diagnostics after rename:**

```bash
# Save affected paths to a variable without jq
FILES_CSV="$(python3 "$SKILL_ROOT/scripts/kast-plan-utils.py" affected-files-csv /tmp/rename-plan.json)"

# Run diagnostics; check results
"$KAST" diagnostics \
  --workspace-root="$WS" \
  --file-paths="$FILES_CSV" > /tmp/diag.json

python3 "$SKILL_ROOT/scripts/kast-plan-utils.py" check-diagnostics /tmp/diag.json
```

---

## NOT_FOUND on symbol resolve / references

**Symptom:** Error code `NOT_FOUND` (404) from `symbol resolve` or `references`.

```
Is the offset pointing to a symbol token?
├─ No  → Offset may land on whitespace, a comment, a string literal, or a
│         keyword. Adjust offset to the first character of the identifier.
└─ Yes
   Is the daemon state READY (not INDEXING)?
   ├─ No  → Wait for READY and retry.
   └─ Yes
      Is the file saved to disk?
      ├─ No  → kast reads from disk. Save the file before querying.
      └─ Yes
         Try symbol resolve on a known symbol in the same file to
         verify the workspace is indexing that file correctly.
```

---

## Offset Calculation

kast uses zero-based UTF-16 character offsets, not byte offsets or line:column positions.

```
To find the offset for a symbol at line L, column C (both 1-based):
  1. Read the file content as a string.
  2. Sum the length of lines 1..(L-1) including their newline characters.
  3. Add (C - 1).
  4. For multi-byte UTF-16 characters (emoji, CJK), count UTF-16 code units,
     not Unicode code points.

Quick check: use symbol resolve at the declaration site first, then read
startOffset from the response to confirm your calculation matches.
```

---

## kast Not Found

**Symptom:** `kast` not on PATH and `resolve-kast.sh` exits 1; no binary found.

```
Try kast on PATH first:
  command -v kast
  → If found, use it directly. No script needed.

If kast is not on PATH, check install options:
  → ./install.sh                    # downloads a GitHub release for your platform
  → ./build.sh --install            # builds from source and installs as dev instance
  → ./gradlew :kast:writeWrapperScript  # builds wrapper script for local dev

If using resolve-kast.sh and it reports "Java not found" or version < 21:
  → Install Java 21+. See references/cloud-setup.md.

If resolve-kast.sh reports Gradle build failed:
  → Run: ./gradlew :kast:writeWrapperScript
  → Check Gradle output for compilation errors.

Note: resolve-kast.sh requires .agents/skills/kast/ to exist relative to the
workspace root. If this path is absent (e.g., a workspace that doesn't include
the skill directory), install kast directly via ./install.sh instead.
```
