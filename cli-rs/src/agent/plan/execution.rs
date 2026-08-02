pub(crate) fn run_change(args: KastChangeArgs) -> Result<i32> {
    let workspace_root = canonical_workspace_root()?;
    let mut operation = StoredOperation::from(args.command);
    let content = operation.requires_content().then(read_stdin).transpose()?;
    let plan_id = Uuid::new_v4();
    let paths = PlanPaths::new(plan_id);
    ensure_private_directory(&paths.directory)?;

    let preview_content_path = match content.as_deref() {
        Some(content) => {
            write_private_file(&paths.preview_content, content)?;
            Some(paths.preview_content.as_path())
        }
        None => None,
    };
    let preview = match agent_adapter::projected_value(operation.command(
        workspace_root.clone(),
        preview_content_path,
        false,
        None,
        None,
    )?) {
        Ok(preview) => preview,
        Err(error) => {
            remove_if_exists(&paths.preview_content);
            return Err(error);
        }
    };
    if preview.get("ok") != Some(&Value::Bool(true)) {
        remove_if_exists(&paths.preview_content);
        return agent_adapter::print_projected_value(preview);
    }
    let preview_result = match projected_result(&preview) {
        Ok(result) => result,
        Err(error) => {
            remove_if_exists(&paths.preview_content);
            return Err(error);
        }
    };
    if let Err(error) = operation.normalize_from_preview(preview_result) {
        remove_if_exists(&paths.preview_content);
        return Err(error);
    }
    let public_plan = public_plan(preview_result);

    if content.is_some()
        && let Err(error) = rename_private_file(&paths.preview_content, &paths.content)
    {
        remove_if_exists(&paths.preview_content);
        return Err(error);
    }
    let stored = StoredPlan {
        schema_version: PLAN_SCHEMA_VERSION,
        plan_id,
        workspace_root: workspace_root.display().to_string(),
        operation,
        content_sha256: content.as_deref().map(manifest::sha256_bytes),
    };
    if let Err(error) = write_plan(&paths.plan, &stored) {
        remove_if_exists(&paths.content);
        return Err(error);
    }

    let result = ChangeResult {
        plan_id: plan_id.hyphenated().to_string(),
        operation: stored.operation.name(),
        plan: public_plan,
        next: format!(
            "kast apply {} --lease-id <LEASE_ID>",
            plan_id.hyphenated()
        ),
    };
    output::print_structured(&result, crate::cli::OutputFormat::Toon)?;
    Ok(0)
}

pub(crate) fn run_apply(
    raw_plan_id: String,
    lease_id: AgentWorkspaceLeaseId,
) -> Result<i32> {
    let plan_id = parse_plan_id(&raw_plan_id)?;
    let paths = PlanPaths::new(plan_id);
    let plan_bytes = read_private_file(&paths.plan, "KAST_PLAN_UNAVAILABLE")?;
    let plan: StoredPlan = serde_json::from_slice(&plan_bytes).map_err(|error| {
        CliError::new(
            "KAST_PLAN_INVALID",
            format!("The stored change plan is malformed: {error}"),
        )
    })?;
    validate_plan(&plan, plan_id)?;

    let workspace_root = canonical_workspace_root()?;
    if plan.workspace_root != workspace_root.display().to_string() {
        return Err(CliError::new(
            "KAST_PLAN_WORKSPACE_MISMATCH",
            format!(
                "Plan {plan_id} belongs to {}, not {}.",
                plan.workspace_root,
                workspace_root.display()
            ),
        ));
    }

    let content_path = if plan.operation.requires_content() {
        let contents = read_private_file(&paths.content, "KAST_PLAN_CONTENT_UNAVAILABLE")?;
        let expected = plan.content_sha256.as_deref().ok_or_else(|| {
            CliError::new(
                "KAST_PLAN_INVALID",
                "The stored content-bearing plan has no content digest.",
            )
        })?;
        if manifest::sha256_bytes(&contents) != expected {
            return Err(CliError::new(
                "KAST_PLAN_CONTENT_CHANGED",
                "The stored change content no longer matches the validated plan.",
            ));
        }
        Some(paths.content.as_path())
    } else {
        if plan.content_sha256.is_some() || paths.content.exists() {
            return Err(CliError::new(
                "KAST_PLAN_INVALID",
                "The stored rename plan unexpectedly contains change content.",
            ));
        }
        None
    };

    let envelope = agent_adapter::projected_value(plan.operation.command(
        workspace_root,
        content_path,
        true,
        Some(plan_id.hyphenated().to_string()),
        Some(lease_id),
    )?)?;
    let outcome = envelope
        .get("result")
        .and_then(|result| result.get("execution"))
        .and_then(|execution| execution.get("outcome"))
        .and_then(Value::as_str);
    if outcome == Some("FAILED") {
        let result = envelope.get("result").cloned().ok_or_else(|| {
            CliError::new(
                "KAST_INVALID_AGENT_RESULT",
                "The failed change returned no typed failure result.",
            )
        })?;
        agent_adapter::print_agent_result(result)?;
        return Ok(1);
    }
    if envelope.get("ok") != Some(&Value::Bool(true)) {
        return agent_adapter::print_projected_value(envelope);
    }
    if outcome != Some("SUCCEEDED") {
        return Err(CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            "The applied change did not return a successful execution outcome.",
        ));
    }
    agent_adapter::print_projected_value(envelope)?;
    fs::remove_file(&paths.plan)?;
    if plan.operation.requires_content() {
        fs::remove_file(&paths.content)?;
    }
    sync_directory(&paths.directory)?;
    Ok(0)
}
