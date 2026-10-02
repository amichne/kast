# Recover a damaged installation

Use this runbook when normal installation or uninstall cannot finish. For ordinary
setup, start with [Install and connect](https://kast.michne.com/start/).

Each installation saves an offline recovery executable and an ownership receipt
under `<install-root>/recovery/installation/`. The schema 3 receipt names the ordinary `<install-root>/installation` directory.
Replacement keeps an owned control payload only until activation is
verified. A successful install removes that temporary recovery copy; a failed
install preserves it and reports the unresolved condition. No selector links or
selectable historical installations are created.

To inspect and detach a damaged installation, use its saved executable directly:

```console
python3 /absolute/install-root/recovery/installation/installation-recovery.py detach --control-only --installation /absolute/install-root/installation --dry-run
python3 /absolute/install-root/recovery/installation/installation-recovery.py detach --control-only --installation /absolute/install-root/installation
```

For an older installation without a receipt, use `installation-recovery.py` from
a checksum-verified newer control distribution. First run `prepare` with the exact
`--installation` and `--bin-directory`. Then run `detach --control-only` using the printed `recoveryExecutable` path.
Older helpers that reject this option must be replaced by the checksum-verified
newer recovery helper before detachment. Do not
execute a recovery script obtained from a damaged, unverified payload.

`CleanBaselineRestored` means retirement was verified and the selected installation
was detached. `DetachedWithUnresolvedState` means verified integration was detached
but processes or source-change evidence remain
unresolved; this returns a nonzero exit status. `RecoveryBlocked` means ownership,
locking, or filesystem conditions prevented completion. Preserve the receipt and
retry after resolving the reported condition. Dry-run is passive and does not
claim retirement. Recovery never signals an unproven PID or discards a mutation
journal. Retired state is quarantined only after verification; otherwise it stays
in place behind a launch fence. Control recovery preserves all host plugin files,
including those named by historical paired receipts. Explicit legacy paired
maintenance uses `detach-legacy-pair`; control recovery never invokes it. Disk loss or
revoked filesystem permissions cannot be repaired by these commands.

The accepted [resumable recovery plan](recovery-plan.md) extends this boundary to
service restoration and user-confirmed IntelliJ restart. That extension is not
implemented by the detachment commands above.
