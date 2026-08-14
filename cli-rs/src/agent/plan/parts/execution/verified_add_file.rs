include!("verified_add_file/model.rs");
include!("verified_add_file/authority.rs");
include!("verified_add_file/result.rs");
include!("verified_add_file/wire.rs");
include!("verified_add_file/storage.rs");
include!("verified_add_file/flow.rs");

fn reject_legacy_add_file_apply(plan: &StoredPlan) -> Result<()> {
    if matches!(plan.operation, StoredOperation::AddFile { .. }) {
        return Err(CliError::new(
            "KAST_VERIFIED_ADD_FILE_WORKFLOW_REQUIRED",
            "Legacy add-file plans cannot acquire mutation authority; create a verified plan with `kast change plan add-file --file ...`.",
        ));
    }
    Ok(())
}
