# Kast recovery reference

## Binary resolution order

Both the Copilot extension (`extension.mjs`) and the Claude Code skill resolve
the kast binary in this order, stopping at the first hit:

1. **`resolve-kast.sh`** — checks `$PATH` via `command -v kast`, then walks up
   the directory tree looking for `kast-cli/build/scripts/kast-cli` and
   `dist/cli/kast-cli`.
2. **`$KAST_CONFIG_HOME/config.toml`** (or `$HOME/.config/kast/config.toml`
   when `KAST_CONFIG_HOME` is unset) — reads `[cli] binaryPath` written by the
   installer. Covers non-interactive shells where `$HOME/.kast/bin` was never
   exported onto `PATH`.
3. **`$HOME/.local/bin/kast`** — common manual install location.
4. **Error** — the extension logs a warning; the skill reports a setup blocker.

## Bash fallback

If you need to invoke `kast skill` directly (debugging, shell pipelines):

```bash
# Preferred — uses the same resolution order as the extension:
KAST_BIN="$(bash .agents/skills/kast/scripts/resolve-kast.sh)"
"$KAST_BIN" skill workspace-files '{}'
```

```bash
# If the resolve script is missing, read binaryPath from config.toml:
KAST_BIN="$(python3 -c "
import re, os, sys
p = os.path.join(os.environ.get('KAST_CONFIG_HOME', os.path.expanduser('~/.config/kast')), 'config.toml')
m = re.search(r'binaryPath\s*=\s*\"(.+?)\"', open(p).read())
print(m.group(1)) if m else sys.exit(1)
")"
"$KAST_BIN" skill workspace-files '{}'
```

```bash
# Last resort — standard user install location:
"${HOME}/.local/bin/kast" skill workspace-files '{}'
```

`export` does not persist across bash tool calls; keep the resolved path in the
same command.

## Semantic query recovery

- **Wrong projection.** Inspect one element (e.g. `references[0]`) before
  assuming a field name is missing. Adjust the projection — don't switch to
  text search because of JSON friction.
- **Result set too large.** Narrow with `kind`, `containingType`, `fileHint`,
  lower `depth`, or smaller limits. Don't post-filter unindexed text.
- **Truncated workspace files.** If `kast_workspace_files` marks a module with
  `filesTruncated:true`, raise the cap only when the task genuinely needs the
  wider list.
- **Stale or missing index.** If `kast_metrics` reports the reference index is
  missing or stale, treat results as advisory and rebuild before relying on
  impact, deadCode, lowUsage, cycles, or moduleDepth answers.
- **Failed mutation.** `ok:false`, a `*_FAILURE` response type, dirty
  diagnostics, or `Missing expected hash` means the edit did not commit. Run
  `kast_diagnostics` if it clarifies state; report the blocker rather than
  retrying with a hand edit.
- **Unknown field error.** A request key is wrong. Check `quickstart.md` for
  the correct shape; do not probe with `{}`.
- **Never** replace a failed semantic query with `grep`, `rg`, `sed`, or manual
  parsing for Kotlin identity. Raw search is acceptable only for non-semantic
  work: file-path discovery in non-Kotlin files, comments, string literals, and
  maintenance scripts.
