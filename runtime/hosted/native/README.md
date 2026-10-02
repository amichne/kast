# Mixed-version native acceptance

This opt-in check runs the real IntelliJ application, the production release
installer, and installed Tool RPC under the current UID. It owns a new private
HOME, IntelliJ configuration, system directory, plugin directory, project,
Gradle cache, and Kast installation. It does not use the daily IntelliJ profile.
It is separate from routine unit and packaging tests.

Provide independently built C1 `0.50.0`, C2 `0.50.1`, P1 `0.49.0`, and P2
`0.49.1` release inventories in one directory: the four archives, both host
release records, `host-installation.py`, and each file's ordinary checksum sidecar.
The host archives target IntelliJ release line 262. Pass the canonical IDEA
`Contents` directory and a new, nonexistent owned root:

```shell
python3 runtime/hosted/native/mixed_version_acceptance.py \
  --idea-home "$HOME/Applications/IntelliJ IDEA.app/Contents" \
  --assets /absolute/path/to/component-assets \
  --owned-root /absolute/path/to/new-native-proof
```

The check first installs a fresh mixed pair through `install.sh`, then proves
an unavailable-host control preflight retains the exact C1 files and running
process identity. It starts P1 through IntelliJ's native launcher and waits for
the real Gradle import. C1 and C2 must each return the one expected
compiler-backed declaration. The control-only installation must preserve the
IntelliJ PID and start time, host UUID, socket inode, and every plugin file's
checksum and inode. Independent P2 installation must preserve C2's files and
running process; after restarting only the owned IntelliJ process, C2 must
admit P2 and execute the same query.

Unpublished component selection uses the production installer's explicit
`--version`, `--host-version`, local-assets, and component-only inputs. It does
not replace the installer or rewrite package versions. Kotlin/Gradle dependency
downloads are permitted for the real model import. No model, module, library,
source root, or semantic outcome is synthesized.

`report.json` is the result. `progress.jsonl` and private command/IDE logs are
retained under the owned root. The script disables the owned control service and
IntelliJ process; unverified cleanup makes the result `RECOVERY_REQUIRED`.
Archives and checksums, process identities, plugin file identities, and
compiler-backed query witnesses are retained in a successful report. This
establishes private native composition behavior under the real UID; it does
not establish behavior in the daily IntelliJ profile.
