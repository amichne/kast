# Kast Troubleshooting

Decision trees for every known failure mode.

---

## Daemon Won't Start

**Symptom:** `workspace ensure` or `daemon start` exits non-zero; daemon never reaches `READY`.

```
Is Java 21+ available?
├─ No  → Install Java 21. Check: java -version. Set JAVA_HOME if needed.
└─ Yes
   Is the kast binary executable?
   ├─ No  → chmod +x on the binary. Re-run resolve-kast.sh.
   └─ Yes
      Is there a stale descriptor (.kast/instances/)?
      ├─ Yes → Run: kast daemon stop --workspace-root=...
      │         If stop fails, remove the descriptor file manually, then retry.
      └─ No
         Check stderr output for port/socket conflicts.
         The daemon uses a Unix domain socket; ensure /tmp is writable.
         Try: kast workspace status --workspace-root=... for partial state.
```

---

## Daemon Not Ready / Timeout

**Symptom:** `workspace ensure` returns but `state` is `STARTING` or `INDEXING`, or the command times out.

```
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
Missing DIAGNOSTICS     → diagnostics unavailable; run ./gradlew build instead.
Missing RENAME          → rename unavailable; manual find-and-replace required.
Missing APPLY_EDITS     → edits apply unavailable; apply edits manually.
Missing CALL_HIERARCHY  → callHierarchy is not implemented; known gap.

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

**Symptom:** `resolve-kast.sh` exits 1; no binary found.

```
Run: bash .agents/skills/kast/scripts/resolve-kast.sh

If it reports "Java not found" or version < 21:
  → Install Java 21+. See references/cloud-setup.md.

If it reports Gradle build failed:
  → Run: ./gradlew :kast:writeWrapperScript
  → Check Gradle output for compilation errors.

If you want a pre-built binary:
  → Run: ./install.sh
  → This downloads a GitHub release for your platform.
```
