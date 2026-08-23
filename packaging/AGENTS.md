# Installed-product packaging guide

This directory owns shell and Python checks that execute only staged or
published Kast distributions through their public boundaries.

- Keep `test-installed-product.sh` as the single stateful installed journey.
- Compare the installed schema with the generated registry artifact exactly;
  do not duplicate operation-ID lists or count-only assertions.
- The topology lifecycle must prove missing evidence before the first build,
  publication and exact reuse, restart reuse without a build, mutation,
  fresh-selector prerequisite rejection, explicit rebuild, and new evidence.
- Keep runtime payload checks separate from workspace and SQLite evidence; a
  semantic journey must not download or extract the runtime again.
- Python support stays standard-library-only and each executable check reports
  finite failures through its exit status.
- `pr633-final-gate.sh` invokes only `pr633MergeCandidateAcceptance` and rejects tracked changes
  both before and after the clean-checkout GATE-060 run.

Run `./gradlew installedProductTest` after changing the installed journey and
`bash packaging/test-install-local.sh` after changing local installation.
