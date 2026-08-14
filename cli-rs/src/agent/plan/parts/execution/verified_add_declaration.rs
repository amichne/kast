include!("verified_add_declaration/model.rs");
include!("verified_add_declaration/wire.rs");
include!("verified_add_declaration/storage.rs");
include!("verified_add_declaration/flow.rs");

fn reject_legacy_add_declaration_apply(plan: &StoredPlan) -> Result<()> {
    if matches!(plan.operation, StoredOperation::AddDeclaration { .. }) {
        return Err(CliError::new(
            "KAST_VERIFIED_ADD_DECLARATION_WORKFLOW_REQUIRED",
            "Legacy add-declaration plans cannot acquire mutation authority; create a durable verified plan with `kast change plan add-declaration --file ...`.",
        ));
    }
    Ok(())
}
