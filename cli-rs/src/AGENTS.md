# Rust Source Instructions

This directory owns the Kast Rust CLI crate.

Large command surfaces are split by responsibility. Keep the crate root and
module root files as facades: imports, constants, and explicit `include!` part
ordering. Domain behavior belongs in the named subdirectory next to the
facade.

When adding a new part file, name it for the contract it owns. Shared modules
use names tied to the typed contract they expose.

Keep visibility and ownership boundaries explicit so the compiler forces every
caller through the modeled contract.

`self_mgmt.rs` owns strict deserialization and internal consistency checks for
revisioned exact-root workspace compatibility metadata. Keep preparation and
admission separate: metadata schema/revision/capability parsing may fail closed,
but active runtime admission remains the existing exact implementation-version
equality until the dedicated negotiation cutover.
