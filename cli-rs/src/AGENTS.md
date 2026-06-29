# Rust Source Instructions

This directory owns the Kast Rust CLI crate.

Large command surfaces must be split by responsibility before they grow into
catch-all files. Keep the crate root and module root files as facades: imports,
constants, and explicit `include!` part ordering only. Domain behavior belongs
in the named subdirectory next to the facade.

When adding a new part file, name it for the contract it owns, not for a generic
helper bucket. Avoid `util.rs`, `common.rs`, or broad shared modules unless the
type itself is the contract.

Do not loosen types to make a split compile. If visibility or ownership becomes
awkward, model the boundary explicitly and let the compiler force every caller
through it.
