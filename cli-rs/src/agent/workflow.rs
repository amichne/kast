fn execute_workflow(command: AgentWorkflowCommand) -> AgentEnvelope {
    let method = format!("agent/workflow/{}", workflow_name(&command));
    let prepared = workflow_steps(command);
    let (workflow, common, steps) = match prepared {
        Ok(prepared) => prepared,
        Err(error) => return error_envelope(method, None, error),
    };
    match run_workflow(&workflow, &common, steps) {
        Ok(summary) => workflow_envelope(method, summary),
        Err(error) => error_envelope(method, None, AgentError::from_cli_error(error)),
    }
}

fn workflow_name(command: &AgentWorkflowCommand) -> &'static str {
    match command {
        AgentWorkflowCommand::Verify(_) => "verify",
        AgentWorkflowCommand::Symbol(_) => "symbol",
        AgentWorkflowCommand::Impact(_) => "impact",
        AgentWorkflowCommand::Diagnostics(_) => "diagnostics",
        AgentWorkflowCommand::RenamePlan(_) => "rename-plan",
        AgentWorkflowCommand::WriteValidate(_) => "write-validate",
        AgentWorkflowCommand::PackageVerify(_) => "package-verify",
    }
}

fn workflow_steps(
    command: AgentWorkflowCommand,
) -> std::result::Result<(String, AgentWorkflowCommonArgs, Vec<AgentWorkflowStep>), AgentError> {
    match command {
        AgentWorkflowCommand::Verify(args) => Ok((
            "verify".to_string(),
            args.common,
            vec![
                AgentWorkflowStep::catalog("health", "health", json!({}), false),
                AgentWorkflowStep::catalog("runtime-status", "runtime/status", json!({}), false),
                AgentWorkflowStep::catalog("capabilities", "capabilities", json!({}), false),
            ],
        )),
        AgentWorkflowCommand::Symbol(args) => symbol_workflow_steps(args),
        AgentWorkflowCommand::Impact(args) => Ok((
            "impact".to_string(),
            args.common,
            vec![AgentWorkflowStep::catalog(
                "impact",
                "database/metrics",
                json!({
                    "metric": "impact",
                    "symbol": args.symbol,
                    "depth": args.depth,
                    "limit": args.limit,
                }),
                false,
            )],
        )),
        AgentWorkflowCommand::Diagnostics(args) => diagnostics_workflow_steps(args),
        AgentWorkflowCommand::RenamePlan(args) => Ok((
            "rename-plan".to_string(),
            args.common,
            vec![AgentWorkflowStep::catalog(
                "rename-plan",
                "raw/rename",
                json!({
                    "position": {
                        "filePath": args.file_path,
                        "offset": args.offset,
                    },
                    "newName": args.new_name,
                    "dryRun": true,
                }),
                false,
            )],
        )),
        AgentWorkflowCommand::WriteValidate(args) => write_validate_workflow_steps(args),
        AgentWorkflowCommand::PackageVerify(args) => {
            let options = AgentPackageVerifyOptions::from_args(&args);
            Ok((
                "package-verify".to_string(),
                args.common,
                vec![AgentWorkflowStep::package_verify(options)],
            ))
        }
    }
}

fn symbol_workflow_steps(
    args: AgentWorkflowSymbolArgs,
) -> std::result::Result<(String, AgentWorkflowCommonArgs, Vec<AgentWorkflowStep>), AgentError> {
    let mut steps = vec![
        AgentWorkflowStep::catalog(
            "symbol-query",
            "symbol/query",
            json!({
                "query": args.symbol,
                "modes": ["exact", "lexical"],
                "filters": {},
                "limit": args.query_limit,
                "includeEvidence": true,
                "includeNextRequests": true,
            }),
            false,
        ),
        AgentWorkflowStep::catalog(
            "symbol-resolve",
            "symbol/resolve",
            drop_nulls(json!({
                "symbol": args.symbol,
                "kind": args.kind.map(|kind| kind.canonical()),
                "fileHint": args.file_hint,
                "containingType": args.containing_type,
                "includeDeclarationScope": true,
                "includeDocumentation": true,
                "surroundingLines": 3,
                "includeSurroundingMembers": true,
            })),
            false,
        ),
    ];
    if args.references {
        steps.push(AgentWorkflowStep::catalog(
            "symbol-references",
            "symbol/references",
            drop_nulls(json!({
                "symbol": args.symbol,
                "kind": args.kind.map(|kind| kind.canonical()),
                "fileHint": args.file_hint,
                "containingType": args.containing_type,
                "includeDeclaration": args.include_declaration,
            })),
            false,
        ));
    }
    if let Some(direction) = args.callers {
        steps.push(AgentWorkflowStep::catalog(
            "symbol-callers",
            "symbol/callers",
            drop_nulls(json!({
                "symbol": args.symbol,
                "kind": args.kind.map(|kind| kind.canonical()),
                "fileHint": args.file_hint,
                "containingType": args.containing_type,
                "direction": direction.canonical(),
                "depth": args.caller_depth,
            })),
            false,
        ));
    }
    Ok(("symbol".to_string(), args.common, steps))
}

fn diagnostics_workflow_steps(
    args: AgentWorkflowDiagnosticsArgs,
) -> std::result::Result<(String, AgentWorkflowCommonArgs, Vec<AgentWorkflowStep>), AgentError> {
    let mut steps = Vec::new();
    if !args.skip_refresh {
        steps.push(AgentWorkflowStep::catalog(
            "workspace-refresh",
            "raw/workspace-refresh",
            json!({ "filePaths": args.file_paths }),
            false,
        ));
    }
    steps.push(AgentWorkflowStep::catalog(
        "diagnostics",
        "raw/diagnostics",
        json!({ "filePaths": args.file_paths }),
        false,
    ));
    Ok(("diagnostics".to_string(), args.common, steps))
}

fn write_validate_workflow_steps(
    args: AgentWorkflowWriteValidateArgs,
) -> std::result::Result<(String, AgentWorkflowCommonArgs, Vec<AgentWorkflowStep>), AgentError> {
    if !args.common.dry_run && !args.allow_mutation {
        return Err(agent_error(
            "AGENT_WORKFLOW_MUTATION_REQUIRES_OPT_IN",
            "`write-validate` requires --allow-mutation unless --dry-run is set.",
        ));
    }
    if args.content.is_some() && args.content_file.is_some() {
        return Err(agent_error(
            "AGENT_WORKFLOW_INPUT_CONFLICT",
            "Use only one of --content or --content-file.",
        ));
    }
    let params = match args.mode {
        AgentWorkflowWriteMode::Create => drop_nulls(json!({
            "type": "CREATE_FILE_REQUEST",
            "filePath": args.file_path,
            "content": args.content,
            "contentFile": args.content_file,
        })),
        AgentWorkflowWriteMode::Insert => {
            let offset = args.offset.ok_or_else(|| {
                agent_error(
                    "AGENT_WORKFLOW_INPUT_INVALID",
                    "write-validate --mode insert requires --offset.",
                )
            })?;
            drop_nulls(json!({
                "type": "INSERT_AT_OFFSET_REQUEST",
                "filePath": args.file_path,
                "offset": offset,
                "content": args.content,
                "contentFile": args.content_file,
            }))
        }
        AgentWorkflowWriteMode::Replace => {
            let start_offset = args.start_offset.ok_or_else(|| {
                agent_error(
                    "AGENT_WORKFLOW_INPUT_INVALID",
                    "write-validate --mode replace requires --start-offset.",
                )
            })?;
            let end_offset = args.end_offset.ok_or_else(|| {
                agent_error(
                    "AGENT_WORKFLOW_INPUT_INVALID",
                    "write-validate --mode replace requires --end-offset.",
                )
            })?;
            if end_offset < start_offset {
                return Err(agent_error(
                    "AGENT_WORKFLOW_INPUT_INVALID",
                    "--end-offset must be greater than or equal to --start-offset.",
                ));
            }
            drop_nulls(json!({
                "type": "REPLACE_RANGE_REQUEST",
                "filePath": args.file_path,
                "startOffset": start_offset,
                "endOffset": end_offset,
                "content": args.content,
                "contentFile": args.content_file,
            }))
        }
    };
    Ok((
        "write-validate".to_string(),
        args.common,
        vec![AgentWorkflowStep::catalog(
            "write-and-validate",
            "symbol/write-and-validate",
            params,
            true,
        )],
    ))
}

fn run_workflow(
    workflow: &str,
    common: &AgentWorkflowCommonArgs,
    steps: Vec<AgentWorkflowStep>,
) -> Result<AgentWorkflowSummary> {
    let out_dir = workflow_out_dir(workflow, common.out_dir.as_deref())?;
    fs::create_dir_all(&out_dir)?;
    let workspace_root = common
        .runtime
        .workspace_root
        .clone()
        .unwrap_or_else(|| std::env::current_dir().unwrap_or_else(|_| PathBuf::from(".")));
    let mut step_summaries = Vec::new();
    let mut issues = Vec::new();
    for step in steps {
        let summary = run_workflow_step(&out_dir, common, &workspace_root, &step)?;
        let step_ok = summary.exit_code == 0
            && summary
                .summary
                .get("ok")
                .and_then(Value::as_bool)
                .unwrap_or(false);
        if !step_ok {
            issues.push(AgentWorkflowIssue {
                code: "AGENT_WORKFLOW_STEP_FAILED".to_string(),
                message: format!("{} failed", step.name),
                step: Some(step.name.to_string()),
            });
        }
        step_summaries.push(summary);
        if !issues.is_empty() && !common.dry_run {
            break;
        }
    }
    let summary = AgentWorkflowSummary {
        summary_type: "KAST_AGENT_WORKFLOW",
        ok: issues.is_empty(),
        workflow: workflow.to_string(),
        workspace_root: workspace_root.display().to_string(),
        out_dir: out_dir.display().to_string(),
        dry_run: common.dry_run,
        steps: step_summaries,
        issues,
        schema_version: SCHEMA_VERSION,
    };
    write_json_file(&out_dir.join("workflow.json"), &summary)?;
    Ok(summary)
}

fn run_workflow_step(
    out_dir: &Path,
    common: &AgentWorkflowCommonArgs,
    workspace_root: &Path,
    step: &AgentWorkflowStep,
) -> Result<AgentWorkflowStepSummary> {
    let step_dir = out_dir.join(step.name);
    fs::create_dir_all(&step_dir)?;
    let params_file = step_dir.join("input.json");
    let stdout_file = step_dir.join("stdout.json");
    let stderr_file = step_dir.join("stderr.txt");
    write_json_file(&params_file, &step.params)?;
    let (exit_code, summary) = if common.dry_run {
        let summary = match &step.action {
            AgentWorkflowStepAction::Catalog => json!({
                "ok": true,
                "dryRun": true,
                "method": step.method,
                "mutates": step.mutates,
                "nextRequest": json_rpc_request(step.method, step.params.clone()),
                "schemaVersion": SCHEMA_VERSION,
            }),
            AgentWorkflowStepAction::PackageVerify(options) => json!({
                "ok": true,
                "dryRun": true,
                "method": step.method,
                "mutates": step.mutates,
                "params": step.params,
                "nextCommandArgv": package_verify_command_argv(workspace_root, common, options),
                "schemaVersion": SCHEMA_VERSION,
            }),
        };
        (0, summary)
    } else {
        match &step.action {
            AgentWorkflowStepAction::PackageVerify(options) => {
                run_package_verify_step(workspace_root, options)?
            }
            AgentWorkflowStepAction::Catalog => {
                let envelope = execute_request(AgentRequest {
                    method: step.method.to_string(),
                    request: json_rpc_request(step.method, step.params.clone()),
                    runtime: common.runtime.clone(),
                    full_response: true,
                });
                let exit_code = if envelope.ok { 0 } else { 1 };
                (exit_code, serde_json::to_value(envelope)?)
            }
        }
    };
    write_json_file(&stdout_file, &summary)?;
    fs::write(&stderr_file, "")?;
    Ok(AgentWorkflowStepSummary {
        name: step.name.to_string(),
        method: step.method.to_string(),
        params_file: params_file.display().to_string(),
        stdout: stdout_file.display().to_string(),
        stderr: stderr_file.display().to_string(),
        exit_code,
        summary,
    })
}

fn run_package_verify_step(
    workspace_root: &Path,
    options: &AgentPackageVerifyOptions,
) -> Result<(i32, Value)> {
    let doctor = self_mgmt::doctor(false, ReadyTarget::Agent, Some(workspace_root))?;
    let required_resources = required_package_resources(&doctor, workspace_root, options)?;
    let mut summary = serde_json::to_value(&doctor)?;
    let ok = doctor.ok && required_resources.ok;
    if let Some(summary) = summary.as_object_mut() {
        append_required_resource_issues(summary, &required_resources.issues);
        summary.insert("ok".to_string(), Value::Bool(ok));
        summary.insert(
            "requiredResources".to_string(),
            serde_json::to_value(required_resources)?,
        );
    }
    let exit_code = if ok { 0 } else { 1 };
    Ok((exit_code, summary))
}

fn package_verify_command_argv(
    workspace_root: &Path,
    common: &AgentWorkflowCommonArgs,
    options: &AgentPackageVerifyOptions,
) -> Vec<String> {
    let mut argv = vec![
        current_executable_argument(),
        "--output".to_string(),
        "json".to_string(),
        "agent".to_string(),
        "workflow".to_string(),
        "package-verify".to_string(),
        "--workspace-root".to_string(),
        workspace_root.display().to_string(),
    ];
    if let Some(backend) = common.runtime.backend_name {
        argv.push("--backend".to_string());
        argv.push(backend.canonical().to_string());
    }
    if options.require_copilot {
        argv.push("--require-copilot".to_string());
    }
    if let Some(target_dir) = &options.copilot_target_dir {
        argv.push("--copilot-target-dir".to_string());
        argv.push(config::normalize(target_dir.clone()).display().to_string());
    }
    if options.require_skill {
        argv.push("--require-skill".to_string());
    }
    for target_dir in &options.skill_target_dirs {
        argv.push("--skill-target-dir".to_string());
        argv.push(config::normalize(target_dir.clone()).display().to_string());
    }
    if options.require_instructions {
        argv.push("--require-instructions".to_string());
    }
    for target_dir in &options.instructions_target_dirs {
        argv.push("--instructions-target-dir".to_string());
        argv.push(config::normalize(target_dir.clone()).display().to_string());
    }
    argv
}
