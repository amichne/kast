include!("parts/addition_proof/identity.rs");
include!("parts/addition_proof/evidence.rs");
include!("parts/addition_proof/rebinding.rs");
include!("parts/addition_proof/proof_models.rs");
include!("parts/addition_proof/add_file_authority.rs");
include!("parts/addition_proof/add_declaration_authority.rs");
include!("parts/addition_proof/validation.rs");

#[cfg(test)]
#[path = "parts/addition_proof/rebinding_tests.rs"]
mod exact_addition_rebinding_tests;

include!("parts/addition_proof/validation_support.rs");
