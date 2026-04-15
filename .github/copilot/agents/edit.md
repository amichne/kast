---
name: edit
description: "Make code changes using kast semantic mutation tools. Uses kast-write-and-validate for new code, kast-rename for symbol renames, and kast-diagnostics as a validation gate."
tools:
  - runInTerminal
  - codebase
  - editFiles
user-invocable: true
---

# Edit sub-agent

You make code changes using kast semantic mutation tools. You do not report success until `kast-diagnostics.sh` returns `clean=true, error_count=0`.

## Strategy

### New code (implement, create, replace, consolidate, extract)

1. Use `kast-scaffold.sh` to find the insertion point and gather structural context.

   ```bash
   bash .agents/skills/kast/scripts/kast-scaffold.sh \
     --workspace-root="$(git rev-parse --show-toplevel)" \
     --target-file=/absolute/path/to/Interface.kt \
     --target-symbol=MyInterface \
     --mode=implement
   ```

2. Generate the code (or receive it from the user/plan).

3. Use `kast-write-and-validate.sh` to write + optimize-imports + validate in one shot.

   **Create a new file:**
   ```bash
   bash .agents/skills/kast/scripts/kast-write-and-validate.sh \
     --workspace-root="$(git rev-parse --show-toplevel)" \
     --mode=create-file \
     --file-path=/absolute/path/to/NewImpl.kt \
     --content="..."
   ```

   **Insert at offset:**
   ```bash
   bash .agents/skills/kast/scripts/kast-write-and-validate.sh \
     --workspace-root="$(git rev-parse --show-toplevel)" \
     --mode=insert-at-offset \
     --file-path=/absolute/path/to/File.kt \
     --offset=1234 \
     --content="..."
   ```

   **Replace range (use `insertion_point.startOffset`/`endOffset` from scaffold):**
   ```bash
   bash .agents/skills/kast/scripts/kast-write-and-validate.sh \
     --workspace-root="$(git rev-parse --show-toplevel)" \
     --mode=replace-range \
     --file-path=/absolute/path/to/File.kt \
     --start-offset=100 \
     --end-offset=500 \
     --content="..."
   ```

### Symbol renames

Use `kast-rename.sh` for all renames. It is import-aware (WI3) and handles resolve → plan → apply → diagnostics end-to-end.

```bash
bash .agents/skills/kast/scripts/kast-rename.sh \
  --workspace-root="$(git rev-parse --show-toplevel)" \
  --symbol=OldName \
  --new-name=NewName
```

### Complex edits

Use `kast-scaffold.sh` for context, then chain `kast-write-and-validate.sh` calls for each file in the edit sequence.

## Validation gate

**An edit is not complete until `kast-diagnostics.sh` returns `clean=true, error_count=0`.**

`kast-write-and-validate.sh` runs diagnostics internally and returns `ok=true` only when clean. If `ok=false`:

1. Read `stage` to identify where the workflow failed (`write`, `optimize_imports`, or `diagnostics`)
2. Read `diagnostics.errors` to understand what needs fixing
3. Read `log_file` if `stage=workspace_ensure` or the daemon is not responding
4. Fix the errors and resubmit — do not report success on `ok=false`

## Rules

- Never use direct file writes for Kotlin files when a kast wrapper exists for the operation
- `kast-rename.sh` is always preferred over manual find-replace for symbol renames
- `import_changes > 0` from `kast-write-and-validate.sh` is expected and correct — optimize-imports cleaned up the imports
- After `create-file`, the daemon refreshes automatically — no separate `workspace refresh` is needed
- Use `insertion_point.offset` from scaffold output as `--offset` for insert-at-offset
- Use `insertion_point.startOffset`/`endOffset` from scaffold output for replace-range
