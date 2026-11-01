# Routine Gradle work: 2026-10-28

The change enables Isolated Projects and removes cross-project configuration from routine builds.
The performance conclusion is **inconclusive** because matched timing cohorts are absent.
The tests establish avoided work and preserved graph contents.

## Historical CI evidence

The examined source revision is `e7ecf325ba8bd94d817c9ff64776618158f873af`.
[CI run 40642980752](https://github.com/amichne/kast/actions/runs/40642980752), attempt 1, supplies the logs and Gradle profiles.

| Workload | Job | Wall time | Gradle configuration |
| --- | --- | --- | --- |
| Source validation, actual Gradle invocation | `122665254890` | 4m 22.31s | 3.050s |
| Product candidate, actual Gradle invocation | `122665254932` | 48m 35.20s | 7.330s |
| Source validation, dry run | `122665254890` | 2m 43.07s | 27.104s |

The complete source step took 7m 14s, including its dry run.
The candidate step took 48m 46s.
Its longest tasks included relation semantic fixtures at 21m 17.01s and hosted semantic fixtures at 10m 48.84s.
Native compilation took 8m 39.40s.
These task durations overlap and do not sum to elapsed time.

The historical cost does not support a claim that project isolation removes most CI time.
Semantic fixtures, native compilation, and their acceptance obligations remain in the full gate.

## Change and preserved behavior

Settings assign immutable group and version values before each project configures itself.
Each module publishes lazy artifacts for facts, classes, and sources.
Root consumers resolve each module separately to preserve distinct projects with identical Maven coordinates.
Generated contracts and release inputs keep their producer dependencies through consumable configurations.
The disabled topology report does not schedule fact collection.

The focused build-logic `test` task avoids the separate JSON parser suite.
The build-logic `check` task still includes both suites.
The repository verification graph keeps all 43 original modules, source roots, and consumer closures.
The product gate dry run preserves all obligations in the existing CI inspector.

## Local evidence and limits

Every local Gradle invocation uses a 65-second process limit during this work.
The full product gate, native compilation, and installed-product acceptance did not run locally.
A dry run proves task selection, without proof of task results or archive contents.

The [baseline scan](https://scans.gradle.com/s/2vayirxy32zaa) took 28s and reports 959 configurations across 45 projects.
The [candidate scan](https://scans.gradle.com/s/233uxlur25eas) used Isolated Projects and recorded a successful build.
The baseline disabled the configuration cache, while the candidate stored it.
The runs also used different warm-up states and uncommitted build edits.
Their durations do not establish a speedup.

The independent tests cover graph closure, excluded consumers, cache reuse, same-named projects, late source sets, and relocated producer outputs.
The kernel scoped gate covers 23 unit cases plus its quality guards.
The JSON-contract syntax guard reports zero violations.
The knowledge-base citation guard reports zero issues.
The knowledge impact report identifies 19 concepts for the scheduled OpenWiki refresh.

## Sources and reproduction

[Gradle Isolated Projects](https://docs.gradle.org/current/userguide/isolated_projects.html) defines the project-access limits.
[Gradle output sharing](https://docs.gradle.org/current/userguide/how_to_share_outputs_between_projects.html) defines producer-backed artifact dependencies.
[Develocity configuration](https://docs.develocity.ai/gradle/4.6/gradle-plugin/) defines opt-in scans and redaction.
The wrapper remains Gradle 9.8.1, with Kotlin 2.4.21 and GraalVM 25.0.2.

Use the scoped commands and scan opt-in in [the development guide](../development.md#gradle-configuration-and-build-scans).
For a timing decision, collect matched fresh-configuration and reused-configuration cohorts on the same runner and task selection.
