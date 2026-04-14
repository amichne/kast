# Installation and Instance Management

This page summarizes the raw note [[Installation-and-Instance-Management]]. It
is the deepest source for installation and version-selection behavior.

## Source

This source is an installation note.

- Type: note
- Date: Unknown
- Author: Unknown
- Location: [[Installation-and-Instance-Management]]

## Summary

This note explains platform detection, the primary installer, instance
management, side-by-side versions, the discovery cascade, skill installation,
and shell completion. Its main contribution is clarifying how Kast remains
usable across upgrades, local builds, and multiple installed versions.

It also shows that "find the right Kast" is a deliberate product problem with
explicit scripts and ordering rules.

## Key claims

- Kast supports side-by-side instance installation and selection.
- Binary discovery follows a defined cascade.
- Skill installation and completion are part of the operational experience.

## Connections

This source anchors the install-focused concept pages.

- Reinforces [[concepts/installation-and-instance-management]]
- Adds detail to [[entities/kast-cli]]
- Supports [[analyses/operator-journeys]]

## Open questions

This source leaves some operator policy unstated.

- Which selection rule wins when multiple valid local and published instances
  exist?
- How are outdated instances expected to be cleaned up?

## Pages updated from this source

The pages below now depend on this source.

- [[concepts/installation-and-instance-management]]
- [[entities/kast-cli]]
- [[analyses/operator-journeys]]
