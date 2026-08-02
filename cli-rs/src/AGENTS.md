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

`execution/runtime/backend/headless_authority.rs` owns the private constructor
for `AdmittedHeadlessRuntime`. All semantic consumers must use that proof. Do
not pass backend primitives downstream or restore a second backend selector.
Legacy IDEA values are parseable only at this ingress so setup can migrate them
and runtime requests can reject them before inspection or launch.

`operations/self_mgmt.rs` owns install readiness. It must not infer semantic
readiness from a foreground IDE profile, public plugin, workspace metadata, or
other external state. Live semantic readiness comes from the admitted exact-root
headless runtime.
