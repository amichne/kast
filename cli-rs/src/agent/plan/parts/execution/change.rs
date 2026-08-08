pub(crate) fn run_change(args: KastChangePlanArgs, output_format: OutputFormat) -> Result<i32> {
    let workspace_root = canonical_workspace_root()?;
    let requested = RequestedOperation::from(args.command);
    let operation = requested.operation_id();
    let prepared = match requested.prepare(workspace_root.clone()) {
        Ok(prepared) => prepared,
        Err(envelope) => {
            let exit_code = envelope.exit_code();
            output::print_structured(envelope.as_ref(), output_format)?;
            return Ok(exit_code);
        }
    };
    let selector = prepared.selector().map(str::to_string);
    let content = prepared.requires_content().then(read_stdin).transpose()?;
    let plan_id = Uuid::new_v4();
    let paths = PlanPaths::new(plan_id);
    let preview_content = content
        .as_deref()
        .map(TemporaryPreviewContent::create)
        .transpose()?;
    let preview = agent_adapter::projected_value(prepared.command(
        workspace_root.clone(),
        preview_content.as_ref().map(TemporaryPreviewContent::path),
        false,
        None,
        None,
    )?)?;
    if preview.get("ok") != Some(&Value::Bool(true)) {
        return agent_adapter::print_backend_failure(operation, preview, output_format);
    }
    let preview_result = projected_result(&preview)?;
    let stored_operation = prepared.into_stored(preview_result)?;
    let public_plan = public_plan(preview_result);
    let content_sha256 = content.as_deref().map(manifest::sha256_bytes);
    if let Err(message) = stored_operation.validate_content_sha256(content_sha256.as_deref()) {
        return Err(CliError::new("KAST_INVALID_AGENT_RESULT", message));
    }

    ensure_private_directory(&paths.directory)?;
    if let Some(content) = content.as_deref()
        && let Err(error) = write_private_file(&paths.content, content)
    {
        return Err(error);
    }
    let stored = StoredPlan {
        schema_version: PLAN_SCHEMA_VERSION,
        plan_id,
        workspace_root: workspace_root.display().to_string(),
        operation: stored_operation,
        content_sha256,
        state: StoredPlanState::Planned,
        runtime_output: None,
    };
    if let Err(error) = write_plan(&paths.plan, &stored) {
        remove_if_exists(&paths.content);
        return Err(error);
    }

    let result = ChangeResult {
        plan_id: plan_id.hyphenated().to_string(),
        operation: stored.operation.name(),
        selector,
        plan: public_plan,
        next: format!("kast change apply --plan-id {}", plan_id.hyphenated()),
    };
    print_plan_protocol(
        plan_output_context(output_format, operation),
        crate::agent::public_protocol::OperationStatus::Complete,
        &result,
    )?;
    Ok(0)
}

struct TemporaryPreviewContent {
    path: PathBuf,
}

impl TemporaryPreviewContent {
    fn create(content: &[u8]) -> Result<Self> {
        let path = std::env::temp_dir().join(format!(
            "kast-change-preview-{}.content",
            Uuid::new_v4().hyphenated()
        ));
        write_private_file(&path, content)?;
        Ok(Self { path })
    }

    fn path(&self) -> &Path {
        &self.path
    }
}

impl Drop for TemporaryPreviewContent {
    fn drop(&mut self) {
        remove_if_exists(&self.path);
    }
}
