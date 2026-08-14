fn run_verified_add_file_plan(
    workspace_root: PathBuf,
    requested_target: PathBuf,
    content: PreparedPlanContent,
    output_format: OutputFormat,
) -> Result<i32> {
    let target = VerifiedAddFileTarget::admit(&workspace_root, requested_target)?;
    let source = VerifiedAddFileSource::admit(content)?;
    let workspace_root_text = workspace_root
        .to_str()
        .map(str::to_string)
        .ok_or_else(|| {
            CliError::new(
                "KAST_VERIFIED_ADD_FILE_WORKSPACE_INVALID",
                "The canonical workspace root is not exact UTF-8.",
            )
        })?;
    let params = VerifiedAddFilePlanRequest {
        workspace_root: &workspace_root_text,
        target_path: target.as_str(),
        proposed_content: source.as_str(),
    };
    let raw = verified_add_file_rpc(&workspace_root, VerifiedAddFileRpcOperation::Plan, &params)?;
    let response: RawVerifiedAddFilePlanResponse = serde_json::from_value(raw).map_err(|error| {
        CliError::new(
            "KAST_VERIFIED_ADD_FILE_PLAN_INVALID",
            format!("The operation-specific plan response violated its closed contract: {error}"),
        )
    })?;
    let response = response.admit(&workspace_root_text, &target, &source)?;
    let paths = VerifiedAddFilePaths::new(&response.plan_id);
    let stored = StoredVerifiedAddFilePlan {
        schema_version: VERIFIED_ADD_FILE_STORE_SCHEMA_VERSION,
        workspace_root: workspace_root_text,
        plan_id: response.plan_id.clone(),
        plan_version: response.plan_version,
        target_path: target,
        postimage_sha256: VerifiedAddFileSha256::from_source(&source),
        proposed_content: source,
        planned_generation: response.preview.generation,
        state: StoredVerifiedAddFileState::AwaitingApproval,
    };
    ensure_private_directory(&paths.directory)?;
    write_verified_add_file_plan(&paths.plan, &stored, false)?;
    print_plan_protocol(
        plan_output_context(
            output_format,
            crate::agent::public_protocol::OperationId::ChangePlanAddFile,
        ),
        crate::agent::public_protocol::OperationStatus::Complete,
        &response,
    )?;
    Ok(0)
}

fn run_verified_add_file_apply(
    plan_id: crate::agent::public_protocol::VerifiedAddFilePlanId,
    output_format: OutputFormat,
) -> Result<i32> {
    let paths = VerifiedAddFilePaths::new(&plan_id);
    let _operation_lock = PlanOperationLock::acquire(&paths.lock)?;
    let mut plan = read_verified_add_file_plan(&paths.plan, &plan_id)?;
    let workspace_root = canonical_workspace_root()?;
    if workspace_root.to_str() != Some(plan.workspace_root.as_str()) {
        return Err(CliError::new(
            "KAST_PLAN_WORKSPACE_MISMATCH",
            "The verified add-file plan belongs to a different exact workspace root.",
        ));
    }
    match &plan.state {
        StoredVerifiedAddFileState::AwaitingApproval => {}
        StoredVerifiedAddFileState::Rejected { result } => {
            output::print_structured(result.as_result(), output_format)?;
            return Ok(result.as_result().exit_code());
        }
        StoredVerifiedAddFileState::Terminal { result } if result.as_result().is_verified() => {}
        StoredVerifiedAddFileState::Terminal { result } => {
            output::print_structured(result.as_result(), output_format)?;
            return Ok(result.as_result().exit_code());
        }
        StoredVerifiedAddFileState::ApplyOutcomeUnknown { .. }
        | StoredVerifiedAddFileState::RecoveryRequired { .. }
        | StoredVerifiedAddFileState::ReconciliationRequired { .. } => {
            return Err(CliError::new(
                "KAST_VERIFIED_ADD_FILE_RECOVERY_REQUIRED",
                "This add-file attempt retained recovery authority; continue with `kast change recover --recovery-id <RECOVERY_ID>`.",
            ));
        }
    }
    let authority = StoredVerifiedAddFileApplyInFlight::prepare(&plan);
    plan.state = StoredVerifiedAddFileState::ApplyOutcomeUnknown { authority };
    write_verified_add_file_plan(&paths.plan, &plan, true)?;
    execute_verified_add_file(&paths, &mut plan, &workspace_root, output_format)
}

fn run_verified_add_file_recover(
    recovery_id: crate::agent::public_protocol::VerifiedAddFileRecoveryId,
    output_format: OutputFormat,
) -> Result<i32> {
    let plan_id = recovery_id.originating_plan_id();
    let paths = VerifiedAddFilePaths::new(&plan_id);
    let _operation_lock = PlanOperationLock::acquire(&paths.lock)?;
    let mut plan = read_verified_add_file_plan(&paths.plan, &plan_id)?;
    let workspace_root = canonical_workspace_root()?;
    if workspace_root.to_str() != Some(plan.workspace_root.as_str()) {
        return Err(CliError::new(
            "KAST_PLAN_WORKSPACE_MISMATCH",
            "The verified add-file recovery belongs to a different exact workspace root.",
        ));
    }
    match &plan.state {
        StoredVerifiedAddFileState::ApplyOutcomeUnknown { authority }
            if authority.recovery_id() == &recovery_id => {}
        StoredVerifiedAddFileState::RecoveryRequired { result }
            if result.as_result().recovery_id() == Some(&recovery_id) => {}
        StoredVerifiedAddFileState::ReconciliationRequired { result }
            if result.as_result().recovery_id() == Some(&recovery_id) => {}
        StoredVerifiedAddFileState::Terminal { result }
            if result.as_result().is_rolled_back() =>
        {
            output::print_structured(result.as_result(), output_format)?;
            return Ok(result.as_result().exit_code());
        }
        _ => {
            return Err(CliError::new(
                "KAST_VERIFIED_ADD_FILE_RECOVERY_INVALID",
                "Recovery admits only a retained add-file recovery or reconciliation capability.",
            ));
        }
    }
    execute_verified_add_file(&paths, &mut plan, &workspace_root, output_format)
}

fn execute_verified_add_file(
    paths: &VerifiedAddFilePaths,
    plan: &mut StoredVerifiedAddFilePlan,
    workspace_root: &Path,
    output_format: OutputFormat,
) -> Result<i32> {
    let params = VerifiedAddFileApplyRequest {
        workspace_root: &plan.workspace_root,
        plan_id: plan.plan_id.as_str(),
        expected_version: plan.plan_version.value(),
        approval_evidence: verified_add_file_approval(plan),
    };
    let raw = verified_add_file_rpc(workspace_root, VerifiedAddFileRpcOperation::Apply, &params)?;
    let result: VerifiedAddFileApplyResult = serde_json::from_value(raw).map_err(|error| {
        CliError::new(
            "KAST_VERIFIED_ADD_FILE_RESULT_INVALID",
            format!("The operation-specific apply response violated its closed contract: {error}"),
        )
    })?;
    let result = result.admit(plan)?;
    plan.state = StoredVerifiedAddFileState::from_result(result.clone())?;
    write_verified_add_file_plan(&paths.plan, plan, true)?;
    output::print_structured(&result, output_format)?;
    Ok(result.exit_code())
}

fn verified_add_file_approval(
    plan: &StoredVerifiedAddFilePlan,
) -> VerifiedAddFileApprovalEvidence {
    VerifiedAddFileApprovalEvidence {
        approved_by: "kast-public-cli",
        evidence_sha256: VerifiedAddFileApprovalSha256::for_plan(plan)
            .as_str()
            .to_string(),
    }
}
