# Project-read epoch adapter owner

This directory owns KVP-017 production observation while retaining the
`io.github.amichne.kast.workspace.intellij.read` package and its friend access to the contract.

- Refine Project and Gradle roots before comparison; compare only strong identities.
- Keep VFS classification pure, bounded to 4,096 events and 4,096 characters/8,192 UTF-8 bytes
  per path, and let only the listener apply counter effects.
- Retain one source per admitted Project/runtime, but emit epochs containing only refined state and
  a callback-free comparison identity.
- Map only named platform observation failures; propagate cancellation and unexpected defects.
- Keep epoch-source installation types in this owner even though they retain the parent package.
  KVP-019 consumes the resulting source only through one state-specific admission observation.

Run `./gradlew :workspace:contract:test :workspace:intellij-read:test --tests '*ProjectReadEpochTest'`.
