# Build Logic and Gradle Conventions

This page summarizes the raw note [[Build-Logic-and-Gradle-Conventions]]. It is
the most detailed source for Kast's custom Gradle machinery.

## Source

This source is a build-logic note.

- Type: note
- Date: Unknown
- Author: Unknown
- Location: [[Build-Logic-and-Gradle-Conventions]]

## Summary

This note explains version catalog entries, standalone app conventions,
distribution tasks, IntelliJ compatibility handling, CLI native-image
configuration, and shell scripts for instance resolution and installation. Its
main contribution is describing how Kast turns source modules into usable local
installations.

It also shows how release engineering and developer ergonomics meet in the
custom build logic.

## Key claims

- Kast's build logic is opinionated and product-aware rather than generic.
- Distribution tasks and install scripts are coordinated through Gradle
  conventions.
- IntelliJ compatibility handling is a recurring build concern.

## Connections

This source strengthens the build and install pages.

- Reinforces [[concepts/installation-and-instance-management]]
- Adds detail to [[concepts/testing-and-verification]]
- Supports [[analyses/operator-journeys]]

## Open questions

This source is specific on tasks and less explicit on long-term stability.

- Which custom Gradle conventions are most central to portability?
- Which build steps are the most fragile across toolchain upgrades?

## Pages updated from this source

The pages below now depend on this source.

- [[concepts/installation-and-instance-management]]
- [[concepts/testing-and-verification]]
- [[analyses/operator-journeys]]
