include!("parts/session/receipts.rs");
include!("parts/session/terminal_output.rs");
include!("parts/session/journal_model.rs");
include!("parts/session/journal_behavior.rs");
include!("parts/session/scratch_model.rs");
include!("parts/session/scratch_journal.rs");
include!("parts/session/scratch_recovery.rs");
include!("parts/session/postcondition_and_lease.rs");

#[cfg(test)]
#[path = "parts/session/contract_tests.rs"]
mod mutation_session_contract_tests;
