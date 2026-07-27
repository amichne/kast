pub fn run(command: AgentCommand, output_format: OutputFormat) -> Result<i32> {
    let projection = AgentProjectionRequest::for_command(&command);
    let envelope = projection.project(execute(command));
    let exit_code = if envelope.ok { 0 } else { 1 };
    output::print_structured(&envelope, output_format)?;
    Ok(exit_code)
}

fn execute(command: AgentCommand) -> AgentEnvelope {
    if let Some(runtime) = agent_command_runtime(&command)
        && let Some(lease_id) = runtime.lease_id.as_ref()
        && let Err(error) = runtime::validate_workspace_lease_for_command(
            lease_id,
            runtime.workspace_root.as_deref(),
            runtime.backend_name,
        )
    {
        return error_envelope(
            "agent/lease/validate".to_string(),
            None,
            AgentError::from_cli_error(error),
        );
    }
    match command {
        AgentCommand::Lease(args) => execute_agent_lease(args),
        AgentCommand::Verify(args) => execute_agent_verify(args),
        AgentCommand::WorkspaceFiles(args) => execute_agent_workspace_files(args),
        AgentCommand::Graph(args) => execute_agent_native_graph(args),
        AgentCommand::Repository(args) => execute_agent_repository(args),
        AgentCommand::Symbol(args) => execute_agent_symbol(args),
        AgentCommand::References(args) => execute_agent_references(args),
        AgentCommand::Callers(args) => execute_agent_callers(args),
        AgentCommand::Callees(args) => execute_agent_callees(args),
        AgentCommand::Implementations(args) => execute_agent_implementations(args),
        AgentCommand::Hierarchy(args) => execute_agent_hierarchy(args),
        AgentCommand::Impact(args) => execute_agent_impact(args),
        AgentCommand::Diagnostics(args) => execute_agent_diagnostics(args),
        AgentCommand::Rename(args) => execute_agent_rename(args),
        AgentCommand::AddFile(args) => execute_agent_add_file(args),
        AgentCommand::AddDeclaration(args) => execute_agent_scoped_mutation(
            "agent/add-declaration",
            "symbol/add-declaration",
            "ADD_DECLARATION",
            "add-declaration",
            args,
        ),
        AgentCommand::AddImplementation(args) => execute_agent_scoped_mutation(
            "agent/add-implementation",
            "symbol/add-implementation",
            "ADD_IMPLEMENTATION",
            "add-implementation",
            args,
        ),
        AgentCommand::AddStatement(args) => execute_agent_add_statement(args),
        AgentCommand::ReplaceDeclaration(args) => execute_agent_replace_declaration(args),
    }
}

fn agent_command_runtime(command: &AgentCommand) -> Option<&AgentRuntimeArgs> {
    match command {
        AgentCommand::Verify(args) => Some(&args.runtime),
        AgentCommand::WorkspaceFiles(args) => Some(&args.runtime),
        AgentCommand::Graph(args) => Some(&args.runtime),
        AgentCommand::Repository(_) => None,
        AgentCommand::Symbol(args) => Some(&args.runtime),
        AgentCommand::References(args) => Some(&args.runtime),
        AgentCommand::Callers(args) | AgentCommand::Callees(args) => Some(&args.runtime),
        AgentCommand::Implementations(args) => Some(&args.runtime),
        AgentCommand::Hierarchy(args) => Some(&args.runtime),
        AgentCommand::Impact(args) => Some(&args.runtime),
        AgentCommand::Diagnostics(args) => Some(&args.runtime),
        AgentCommand::Rename(args) => Some(&args.runtime),
        AgentCommand::AddFile(args) => Some(&args.runtime),
        AgentCommand::AddDeclaration(args) | AgentCommand::AddImplementation(args) => {
            Some(&args.runtime)
        }
        AgentCommand::AddStatement(args) => Some(&args.runtime),
        AgentCommand::ReplaceDeclaration(args) => Some(&args.runtime),
        AgentCommand::Lease(_) => None,
    }
}

fn execute_agent_lease(args: AgentLeaseArgs) -> AgentEnvelope {
    let (method, result) = match args.command {
        AgentLeaseCommand::Acquire(args) => (
            "agent/lease/acquire",
            runtime::workspace_lease_acquire(args),
        ),
        AgentLeaseCommand::Status(args) => {
            ("agent/lease/status", runtime::workspace_lease_status(args))
        }
        AgentLeaseCommand::Release(args) => (
            "agent/lease/release",
            runtime::workspace_lease_release(args),
        ),
    };
    match result {
        Ok(result) => AgentEnvelope {
            ok: true,
            method: method.to_string(),
            request: None,
            response: None,
            result: Some(json!(result)),
            raw_response: None,
            error: None,
            schema_version: SCHEMA_VERSION,
        },
        Err(error) => error_envelope(method.to_string(), None, AgentError::from_cli_error(error)),
    }
}

fn execute_agent_verify(args: AgentVerifyArgs) -> AgentEnvelope {
    execute_agent_steps(
        "agent/verify",
        args.runtime,
        vec![
            AgentPublicStep::new("health", "health", json!({}), false),
            AgentPublicStep::new("runtime-status", "runtime/status", json!({}), false),
            AgentPublicStep::new("capabilities", "capabilities", json!({}), false),
        ],
    )
}

fn execute_agent_repository(args: AgentRepositoryArgs) -> AgentEnvelope {
    let result_limit = if args.view.detailed() || args.view.count {
        args.results
    } else {
        args.results.min(10)
    };
    let scope = drop_nulls(json!({
        "language": args.language,
        "module": args.module,
        "sourceSet": args.source_set,
        "relations": args.relations,
        "direction": args.direction,
        "maxDepth": args.max_depth,
        "projection": args.projection,
        "metric": args.metric,
        "sources": args.sources,
    }));
    let params = drop_nulls(json!({
        "question": args.question,
        "intent": args.intent,
        "canonicalKey": args.canonical_key,
        "scope": scope,
        "limits": {
            "depth": args.depth,
            "results": result_limit,
            "evidence": args.evidence,
        },
        "continuation": args
            .continuation
            .as_ref()
            .map(|continuation| continuation.as_str()),
        "evidenceContinuation": args
            .evidence_continuation
            .as_ref()
            .map(|continuation| continuation.as_str()),
    }));
    let mut envelope = execute_request(AgentRequest {
        method: "repository/query".to_string(),
        request: json_rpc_request("repository/query", params),
        runtime: AgentRuntimeArgs {
            workspace_root: args.workspace_root,
            backend_name: None,
            lease_id: None,
        },
        full_response: true,
        operation: AgentOperation::ReadOnly,
    });
    envelope.method = "agent/repository".to_string();
    envelope
}

fn execute_agent_symbol(args: AgentSymbolArgs) -> AgentEnvelope {
    match args.mode {
        AgentSymbolMode::Exact => execute_agent_symbol_exact(args),
        AgentSymbolMode::Discovery => execute_agent_symbol_discovery(args),
    }
}

fn execute_agent_impact(args: AgentImpactArgs) -> AgentEnvelope {
    execute_identity_first_impact(args)
}

fn execute_agent_diagnostics(args: AgentDiagnosticsArgs) -> AgentEnvelope {
    let normalizer = match AgentFilePathNormalizer::from_runtime(&args.runtime) {
        Ok(normalizer) => normalizer,
        Err(error) => return error_envelope("agent/diagnostics".to_string(), None, error),
    };
    let file_paths = match normalizer.normalize_all(&args.file_paths) {
        Ok(file_paths) => file_paths,
        Err(error) => return error_envelope("agent/diagnostics".to_string(), None, error),
    };
    let budget = match AgentDiagnosticsResultBudget::try_from(args.limit) {
        Ok(budget) => budget,
        Err(message) => {
            return error_envelope(
                "agent/diagnostics".to_string(),
                None,
                agent_error("AGENT_USAGE", message),
            );
        }
    };
    let limit = budget.request_limit(diagnostics_result_view(&args.view).detailed());
    let mut steps = Vec::new();
    if args.page_token.is_none() && !args.skip_refresh {
        steps.push(AgentPublicStep::new(
            "workspace-refresh",
            "raw/workspace-refresh",
            json!({ "filePaths": &file_paths }),
            false,
        ));
    }
    steps.push(AgentPublicStep::new(
        "diagnostics",
        "raw/diagnostics",
        drop_nulls(json!({
            "filePaths": &file_paths,
            "maxResults": limit,
            "pageToken": args.page_token,
        })),
        false,
    ));
    let mut envelope = execute_agent_steps("agent/diagnostics", args.runtime, steps);
    if let Some(result) = envelope.result.as_mut().and_then(Value::as_object_mut) {
        result.insert("filePaths".to_string(), json!(file_paths));
    }
    envelope
}

fn execute_agent_rename(args: AgentRenameArgs) -> AgentEnvelope {
    let selector_handle = args.selector_handle.clone();
    let params = match (args.symbol.as_ref(), selector_handle.as_ref()) {
        (Some(symbol), None) => drop_nulls(json!({
            "type": "RENAME_BY_SYMBOL_REQUEST",
            "symbol": symbol,
            "newName": args.new_name,
            "kind": args.kind.map(|kind| kind.canonical()),
            "fileHint": args.file_hint,
            "containingType": args.containing_type,
        })),
        (None, Some(handle)) => json!({
            "type": "RENAME_BY_SELECTOR_HANDLE_REQUEST",
            "selectorHandle": handle,
            "newName": args.new_name,
        }),
        _ => {
            return error_envelope(
                "agent/rename".to_string(),
                None,
                agent_error(
                    "INVALID_SELECTOR_INPUT",
                    "Provide exactly one of --symbol or --selector-handle.",
                ),
            );
        }
    };
    let request = json_rpc_request("symbol/rename", params.clone());
    if !args.mutation.apply {
        return match selector_handle {
            Some(handle) => execute_agent_rename_handle_preview(args, request, handle),
            None => execute_agent_rename_symbol_preview(args, request),
        };
    }
    let idempotency_key = match applied_idempotency_key(args.mutation) {
        Ok(key) => key,
        Err(error) => return error_envelope("agent/rename".to_string(), None, error),
    };
    let request = match applied_mutation_request("RENAME", idempotency_key, params, &args.runtime) {
        Ok(request) => request,
        Err(error) => return error_envelope("agent/rename".to_string(), None, error),
    };
    execute_request(AgentRequest {
        method: "mutation/submit".to_string(),
        request,
        runtime: args.runtime,
        full_response: true,
        operation: AgentOperation::AppliedMutation,
    })
}
