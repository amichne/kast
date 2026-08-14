fn run_apply_typed(
    plan_id: crate::agent::public_protocol::PlanId,
    output_format: OutputFormat,
) -> Result<i32> {
    match plan_id {
        crate::agent::public_protocol::PlanId::Legacy(plan_id) => {
            run_legacy_apply(plan_id, output_format)
        }
        crate::agent::public_protocol::PlanId::VerifiedAddFile(plan_id) => {
            run_verified_add_file_apply(plan_id, output_format)
        }
        crate::agent::public_protocol::PlanId::VerifiedAddDeclaration(plan_id) => {
            run_verified_add_declaration_apply(plan_id, output_format)
        }
    }
}

fn run_verified_add_declaration_plan(
    workspace_root: PathBuf,
    requested_target: PathBuf,
    content: PreparedPlanContent,
    output_format: OutputFormat,
) -> Result<i32> {
    let target = VerifiedAddDeclarationTarget::admit(&workspace_root, requested_target)?;
    let source = VerifiedAddDeclarationSource::admit(content)?;
    let workspace_root_text = workspace_root
        .to_str()
        .map(str::to_string)
        .ok_or_else(|| {
            CliError::new(
                "KAST_VERIFIED_ADD_DECLARATION_WORKSPACE_INVALID",
                "The canonical workspace root is not exact UTF-8.",
            )
        })?;
    let params = VerifiedAddDeclarationPlanRequest {
        workspace_root: &workspace_root_text,
        target_path: target.as_str(),
        proposed_declaration: source.as_str(),
    };
    let raw = verified_add_declaration_rpc(
        &workspace_root,
        VerifiedAddDeclarationRpcOperation::Plan,
        &params,
    )?;
    let response: RawVerifiedAddDeclarationPlanResponse = serde_json::from_value(raw).map_err(
        |error| {
            CliError::new(
                "KAST_VERIFIED_ADD_DECLARATION_PLAN_INVALID",
                format!(
                    "The operation-specific plan response violated its closed contract: {error}"
                ),
            )
        },
    )?;
    let response = response.admit(&target, &source)?;
    let paths = VerifiedAddDeclarationPaths::new(&response.plan_id);
    let stored = StoredVerifiedAddDeclarationPlan {
        schema_version: VERIFIED_ADD_DECLARATION_STORE_SCHEMA_VERSION,
        workspace_root: workspace_root_text,
        plan_id: response.plan_id.clone(),
        plan_version: response.plan_version,
        target_path: target,
        planned_generation: response.preview.generation,
        state: StoredVerifiedAddDeclarationState::AwaitingApproval,
    };
    ensure_private_directory(&paths.directory)?;
    write_verified_add_declaration_plan(&paths.plan, &stored, false)?;
    print_plan_protocol(
        plan_output_context(
            output_format,
            crate::agent::public_protocol::OperationId::ChangePlanAddDeclaration,
        ),
        crate::agent::public_protocol::OperationStatus::Complete,
        &response,
    )?;
    Ok(0)
}

fn run_verified_add_declaration_apply(
    plan_id: crate::agent::public_protocol::VerifiedAddDeclarationPlanId,
    output_format: OutputFormat,
) -> Result<i32> {
    let paths = VerifiedAddDeclarationPaths::new(&plan_id);
    let _operation_lock = PlanOperationLock::acquire(&paths.lock)?;
    let mut plan = read_verified_add_declaration_plan(&paths.plan, &plan_id)?;
    let workspace_root = canonical_workspace_root()?;
    if workspace_root.to_str() != Some(plan.workspace_root.as_str()) {
        return Err(CliError::new(
            "KAST_PLAN_WORKSPACE_MISMATCH",
            "The verified add-declaration plan belongs to a different exact workspace root.",
        ));
    }
    if let StoredVerifiedAddDeclarationState::Terminal { receipt } = &plan.state {
        output::print_structured(receipt, output_format)?;
        return Ok(0);
    }
    let params = VerifiedAddDeclarationApplyRequest {
        workspace_root: &plan.workspace_root,
        plan_id: plan.plan_id.as_str(),
        expected_version: plan.plan_version.value(),
        approval_evidence: verified_add_declaration_approval(&plan),
    };
    let raw = verified_add_declaration_rpc(
        &workspace_root,
        VerifiedAddDeclarationRpcOperation::Apply,
        &params,
    )?;
    if raw.get("outcome").and_then(Value::as_str) != Some("VERIFIED") {
        let mut error = CliError::new(
            "KAST_VERIFIED_ADD_DECLARATION_APPLY_INCOMPLETE",
            "The server did not return a terminal VERIFIED add-declaration receipt; client-side recovery projection is unavailable.",
        );
        error
            .details
            .insert("serverResult".to_string(), raw.to_string());
        return Err(error);
    }
    let receipt: VerifiedAddDeclarationReceipt = serde_json::from_value(raw).map_err(|error| {
        CliError::new(
            "KAST_VERIFIED_ADD_DECLARATION_RECEIPT_INVALID",
            format!("The operation-specific apply response violated its closed contract: {error}"),
        )
    })?;
    let receipt = receipt.admit(&plan)?;
    plan.state = StoredVerifiedAddDeclarationState::Terminal {
        receipt: receipt.clone(),
    };
    write_verified_add_declaration_plan(&paths.plan, &plan, true)?;
    output::print_structured(&receipt, output_format)?;
    Ok(0)
}

fn verified_add_declaration_approval(
    plan: &StoredVerifiedAddDeclarationPlan,
) -> VerifiedAddDeclarationApprovalEvidence {
    let statement = format!(
        "kast-public-cli\nworkspaceRoot={}\nplanId={}\nexpectedVersion={}\n",
        plan.workspace_root,
        plan.plan_id.as_str(),
        plan.plan_version.value(),
    );
    VerifiedAddDeclarationApprovalEvidence {
        approved_by: "kast-public-cli",
        evidence_sha256: manifest::sha256_bytes(statement.as_bytes()),
    }
}
