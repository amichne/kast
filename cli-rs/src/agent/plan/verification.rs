include!("parts/verification/evidence.rs");
include!("parts/verification/protocol.rs");
include!("parts/verification/collect.rs");
include!("parts/verification/refresh.rs");
include!("parts/verification/comparison.rs");
include!("parts/verification/normalization.rs");

#[cfg(test)]
#[path = "parts/verification/rollback_tests.rs"]
mod rollback_refresh_contract_tests;
