pub(crate) fn run_change(args: KastChangeArgs) -> Result<i32> {
    let workspace_root = canonical_workspace_root()?;
    let requested = RequestedOperation::from(args.command);
    let content = requested.requires_content().then(read_stdin).transpose()?;
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
    let preview = match agent_adapter::projected_value(requested.command(
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
    let operation = match requested.into_stored(preview_result) {
        Ok(operation) => operation,
        Err(error) => {
            remove_if_exists(&paths.preview_content);
            return Err(error);
        }
    };
    let public_plan = public_plan(preview_result);
    let content_sha256 = content.as_deref().map(manifest::sha256_bytes);
    if let Err(message) = operation.validate_content_sha256(content_sha256.as_deref()) {
        remove_if_exists(&paths.preview_content);
        return Err(CliError::new("KAST_INVALID_AGENT_RESULT", message));
    }

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
        content_sha256,
        state: StoredPlanState::Planned,
    };
    if let Err(error) = write_plan(&paths.plan, &stored) {
        remove_if_exists(&paths.content);
        return Err(error);
    }

    let result = ChangeResult {
        plan_id: plan_id.hyphenated().to_string(),
        operation: stored.operation.name(),
        plan: public_plan,
        next: format!("kast apply {}", plan_id.hyphenated()),
    };
    output::print_structured(&result, crate::cli::OutputFormat::Toon)?;
    Ok(0)
}
