# Mixed-version native acceptance

This opt-in check uses the current UID, actual HOME, normal IntelliJ profile,
and the sole installation at `~/.local/share/kast/installation`. It replaces that
installation in place. Its owned evidence directory contains logs, a genuine
Gradle workspace, reports, and inert backup archives; it contains no second
installation or IntelliJ profile. IntelliJ must be closed at entry. The existing
control must be running with admitted payload, configuration and runtime anchors.

Provide independently built C1 `0.50.2`, C2 `0.50.3`, P1 `0.49.0`, and P2
`0.49.1` release inventories in one directory: the four archives, both host
release records, both control release records, `host-installation.py`, and each
file's checksum sidecar.
The host archives target IntelliJ release line 262. Pass the canonical IDEA
`Contents` directory and a new owned evidence directory:

```shell
python3 runtime/hosted/native/mixed_version_acceptance.py \
  --idea-home "$HOME/Applications/IntelliJ IDEA.app/Contents" \
  --assets /absolute/path/to/component-assets \
  --owned-root /absolute/path/to/new-native-evidence
```

C1 and C2 must differ from the original installed control version. The baseline
uses an ordinary version replacement, preserving its workspace registry; it does
not force reset or replay registrations. The entry check admits the original
installation through its production lifecycle helper. It records exact payload and plugin bytes, public executable and management
receipt, saved configuration and workspace registry, registration destinations,
and the published login definition. Backups are archive files, never another
runnable tree. Process IDs, readiness documents, sockets and epochs are not restored
as runtime authority. Registration files remain protected throughout. Only the
owned workspace may be added to the existing workspace registry.

The check installs C1/P1 through `install.sh`, proves an unavailable-host preflight
preserves C1's files and process, and launches the normal IntelliJ application on
the owned genuine Gradle workspace. Normal project trust and Gradle import may
require IntelliJ interaction; the bounded readiness deadline fails if the actual
model is unavailable. No model, library, source root or semantic result is synthesized.
C1/P1, C2/P1 and C2/P2 must each return the one expected compiler-backed declaration
through installed Tool RPC and report an admitted host through native status.
At most three independent RUNs are permitted for the exact typed freshness rejection
and `restart_read` instruction; every rejection is retained. Other failures stop the run.

The control-only upgrade must preserve the IntelliJ PID and start time, host UUID,
socket inode, and every plugin file's checksum and inode. Independent P2 installation
must preserve C2's files, configuration and running generation. Only the IntelliJ
process launched by this case is then closed and restarted, through its normal
launcher. No control PID is signaled and no shared Gradle daemon is stopped.

Finally, the production installation lifecycle owns control retirement and cleanup
of runtime anchors. The runner acquires the existing activation, management and
host-install locks, verifies exact case postimages, and discards only the exact
reset-owned case epoch after checking its runtime installation identity and
rejecting unexpected state. It restores archived original bytes directly at the sole normal installation and plugin destinations. It enables
the original control through its private service helper using its admitted published
launch environment. Completion requires the original control reachable with a fresh
runtime generation, original static payload/plugin/configuration/publication bytes,
unchanged registrations, admitted new runtime anchors, and IntelliJ closed again.

The original legacy native loaded-version projection may remain unavailable; the report retains that qualification while requiring the
private production coordinator identity, epoch and fresh generation to match actual
ready service evidence. Candidate C1/C2 status assertions remain strict.
Ordinary IntelliJ preference writes from opening and closing the owned project are
retained; the runner never replaces the rest of the normal profile.

`report.json` retains three compiler-query witnesses and process/file identities.
Runner checksum, checkout revision/modified state, and each component release's
source revision identify the exact evidence producers. Components may come from
different source revisions; their hosted contract determines admission.
A successful report also records verified original-version restoration and its
fresh control identity. Private archives and logs have mode 0600. Any failure
remains in the report; a separate restoration failure produces `RECOVERY_REQUIRED` and retains the
inert archives for recovery. Syntax validation or scripted tests do not establish
this native behavior; a completed real run is required.

Focused helper checks use case-owned inert files and scripted command arguments:

```shell
python3 runtime/hosted/native/test-native-helpers.py -v
```

They prove inventory bounds, rejection evidence, runtime identity ordering and
the exact fresh-read recovery predicate. They do not establish native behavior.
