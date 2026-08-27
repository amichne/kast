# Installed-product packaging guide

This directory owns shell and Python checks that execute only staged or
published Kast distributions through their public boundaries.

`ide-hosted-retirement.gradle.kts` owns the Gradle registrations for hosted installer,
distribution, and default isolated-runtime retirement gates; the canonical delivery graph remains
the authority for KVP-036 task semantics and proof command.

- Keep `test-installed-product.sh` as the staged hosted-product boundary. Live
  four-operation installed evidence belongs to `acceptance/ide-hosted`.
- Published-release verification must acquire the matched control and plugin
  through the public installer, then prove metadata and missing-endpoint demand
  without runtime archive, runtime store, or isolated process authority.
- Compare the installed schema with the generated registry artifact exactly;
  do not duplicate operation-ID lists or count-only assertions.
- The topology lifecycle must prove missing evidence before the first build,
  publication and exact reuse by one PID across successive public callers, restart reuse without a
  build, mutation, fresh-selector prerequisite rejection, explicit rebuild, and new evidence.
- Keep hosted plugin payload checks separate from workspace and SQLite evidence.
- Python support stays standard-library-only and each executable check reports
  finite failures through its exit status.
- Keep `test-installer.sh` as the isolated contract for the public installer's
  install, purge-first, complete uninstall, path-safety, and idempotence flows.
- `installLocal` installs only the control product and removes any legacy runtime payload from its
  exact prefix; its launcher must carry no archive-read or isolated-indexer authority.
- `pr633-final-gate.sh` invokes only `pr633MergeCandidateAcceptance` and rejects tracked changes
  both before and after the clean-checkout GATE-060 run.
- `verification/test-public-installer.sh` exercises installer presentation and failure output
  without downloading or installing a release.

Run `./gradlew installedProductTest` after changing the installed journey and
`bash packaging/test-install-local.sh` after changing local installation. Run
`bash packaging/test-installer.sh` after changing the public installer.
