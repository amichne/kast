# Installation and instance management

Kast is only useful if a caller can find the right binary and connect to the
right running backend. This page captures how installation, version selection,
and instance discovery fit together.

## Summary

The installation story is broader than `install.sh`. Kast supports published and
local builds, side-by-side instances, shell completion, and a discovery cascade
that lets the CLI pick the right executable without asking the caller to know
the full installation layout.

## What the wiki currently believes

- Kast treats binary resolution as part of the product experience.
- Side-by-side versions are a supported workflow, not a workaround.
- Instance discovery and daemon lifecycle are coupled because reuse depends on
  finding a compatible running backend quickly.

## Evidence and sources

These pages describe installation and instance selection.

- [[sources/getting-started]] - Covers prerequisites, installation paths, and
  the first daemon lifecycle.
- [[sources/installation-and-instance-management]] - Covers installers,
  side-by-side instances, discovery, skills, and completion.
- [[sources/build-system-and-distribution]] - Explains packaging and release
  artifacts.
- [[sources/build-logic-and-gradle-conventions]] - Shows the Gradle tasks and
  scripts that produce distributable instances.

## Related pages

These pages depend on installation and discovery working correctly.

- [[entities/kast-cli]]
- [[entities/analysis-server]]
- [[concepts/client-daemon-architecture]]
- [[analyses/operator-journeys]]

## Open questions

The current corpus is precise on shell flows but not every operational nuance.

- Which upgrade paths preserve the most predictable instance selection behavior?
- Which platform-specific installation issues appear most often in practice?
