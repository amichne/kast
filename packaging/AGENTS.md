# Installed-product packaging guide

This directory owns shell and Python checks that execute only staged or
published Kast distributions through their public boundaries.

- Keep `test-installed-product.sh` as the single stateful installed journey.
- Keep `topology_installed_acceptance.py` as the one semantic lifecycle used by
  both staged installed-product and published-release verification.
- Published-release verification must acquire both payloads through the public
  installer, then prove semantic startup from the installed local archive with
  the manifest network URL made unavailable.
- Compare the installed schema with the generated registry artifact exactly;
  do not duplicate operation-ID lists or count-only assertions.
- The topology lifecycle must prove missing evidence before the first build,
  publication and exact reuse by one PID across successive public callers, restart reuse without a
  build, mutation, fresh-selector prerequisite rejection, explicit rebuild, and new evidence.
- Keep runtime payload checks separate from workspace and SQLite evidence; a
  semantic journey must not download or extract the runtime again.
- Python support stays standard-library-only and each executable check reports
  finite failures through its exit status.
- Keep `test-installer.sh` as the isolated contract for the public installer's
  install, purge-first, complete uninstall, path-safety, and idempotence flows.
- `pr633-final-gate.sh` invokes only `pr633MergeCandidateAcceptance` and rejects tracked changes
  both before and after the clean-checkout GATE-060 run.

Run `./gradlew installedProductTest` after changing the installed journey and
`bash packaging/test-install-local.sh` after changing local installation. Run
`bash packaging/test-installer.sh` after changing the public installer.
