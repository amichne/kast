#[path = "../support/mod.rs"]
mod support;

include!("cases/surface.rs");
include!("cases/coverage/exactness.rs");
include!("cases/coverage/qualified_positive.rs");
include!("cases/degraded_and_impact.rs");
include!("cases/resolution.rs");
include!("cases/identity.rs");
include!("cases/references.rs");
include!("cases/reference_failures.rs");
include!("cases/bounded_navigation.rs");
include!("cases/traversal_outcomes.rs");
include!("cases/depth_rejection.rs");
