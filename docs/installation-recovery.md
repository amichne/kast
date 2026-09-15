# Recover a damaged installation

Use this runbook when normal installation or uninstall cannot finish. For ordinary
setup, start with [Install and connect](https://kast.michne.com/start/).

Each installation saves an offline recovery executable and an ownership receipt
under `<install-root>/recovery/<version-and-digest>/`. The receipt is written before
activation changes. Plugin replacement retains the previous plugin directory as
a baseline; recovery never deletes retained payloads or uncertain state.

To inspect and detach a damaged installation, use its saved executable directly:

```console
python3 /absolute/install-root/recovery/version-and-digest/installation-recovery.py detach --installation /absolute/install-root/versions/version-and-digest --dry-run
python3 /absolute/install-root/recovery/version-and-digest/installation-recovery.py detach --installation /absolute/install-root/versions/version-and-digest
```

For an older installation without a receipt, use `installation-recovery.py` from
a checksum-verified newer control distribution. First run `prepare` with the exact
`--installation` and `--bin-directory`. An explicit `--plugin-root` can prove an
empty plugin location; an existing unreceipted plugin remains unproven and is
preserved. Then run `detach` using the printed `recoveryExecutable` path. Do not
execute a recovery script obtained from a damaged, unverified payload.

`CleanBaselineRestored` means retirement was verified and the selected installation
was detached. `DetachedWithUnresolvedState` means verified integration was detached
but processes, plugin ownership, an IDE restart, or source-change evidence remain
unresolved; this returns a nonzero exit status. `RecoveryBlocked` means ownership,
locking, or filesystem conditions prevented completion. Preserve the receipt and
retry after resolving the reported condition. Dry-run is passive and does not
claim retirement. Recovery never signals an unproven PID or discards a mutation
journal. Retired state is quarantined only after verification; otherwise it stays
in place behind a launch fence. Restart IDEA after plugin detachment. Disk loss or
revoked filesystem permissions cannot be repaired by these commands.
