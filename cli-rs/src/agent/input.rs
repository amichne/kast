fn prepare_alias(command: AgentCommand) -> AgentRequest {
    let parts = alias_parts(command);
    AgentRequest {
        request: json_rpc_request(&parts.method, parts.params),
        method: parts.method,
        runtime: parts.runtime,
        full_response: true,
    }
}

struct AliasParts {
    method: String,
    params: Value,
    runtime: AgentRuntimeArgs,
}

fn alias_parts(command: AgentCommand) -> AliasParts {
    match command {
        AgentCommand::Up(_)
        | AgentCommand::Ready(_)
        | AgentCommand::Setup(_)
        | AgentCommand::Lsp(_)
        | AgentCommand::Verify(_)
        | AgentCommand::Symbol(_)
        | AgentCommand::Impact(_)
        | AgentCommand::Diagnostics(_)
        | AgentCommand::Rename(_) => {
            unreachable!("operator agent commands are handled before alias prep")
        }
        AgentCommand::Tools(_) => unreachable!("agent tools is handled before alias prep"),
        AgentCommand::Call(_) => unreachable!("agent call is prepared separately"),
        AgentCommand::Workflow(_) => unreachable!("agent workflow is prepared separately"),
        AgentCommand::Health(runtime) => empty_alias("health", runtime),
        AgentCommand::RuntimeStatus(runtime) => empty_alias("runtime/status", runtime),
        AgentCommand::Capabilities(runtime) => empty_alias("capabilities", runtime),
        AgentCommand::Scaffold(args) => scaffold_alias(args),
        AgentCommand::Discover(args) => discover_alias(args),
        AgentCommand::Resolve(args) => symbol_resolve_alias(args),
        AgentCommand::References(args) => symbol_references_alias(args),
        AgentCommand::Callers(args) => symbol_callers_alias(args),
        AgentCommand::RawResolve(args) => raw_resolve_alias(args),
        AgentCommand::RawReferences(args) => raw_references_alias(args),
        AgentCommand::RawCallHierarchy(args) => raw_call_hierarchy_alias(args),
        AgentCommand::RawTypeHierarchy(args) => raw_type_hierarchy_alias(args),
        AgentCommand::RawSemanticInsertionPoint(args) => raw_semantic_insertion_point_alias(args),
        AgentCommand::RawDiagnostics(args) => file_paths_alias("raw/diagnostics", args),
        AgentCommand::RawRename(args) => raw_rename_alias(args),
        AgentCommand::RawOptimizeImports(args) => file_paths_alias("raw/optimize-imports", args),
        AgentCommand::RawWorkspaceRefresh(args) => {
            optional_file_paths_alias("raw/workspace-refresh", args)
        }
        AgentCommand::FileOutline(args) => file_outline_alias(args),
        AgentCommand::WorkspaceSymbol(args) => workspace_symbol_alias(args),
        AgentCommand::WorkspaceSearch(args) => workspace_search_alias(args),
        AgentCommand::WorkspaceFiles(args) => workspace_files_alias(args),
        AgentCommand::RawImplementations(args) => raw_implementations_alias(args),
        AgentCommand::RawCodeActions(args) => raw_code_actions_alias(args),
        AgentCommand::RawCompletions(args) => raw_completions_alias(args),
        AgentCommand::Metrics(args) => metrics_alias(args),
    }
}

fn validate_request(method: &str, request: &Value) -> std::result::Result<(), AgentError> {
    let catalog = validate::embedded_catalog().map_err(AgentError::from_cli_error)?;
    let report =
        validate::validate_request(request, &catalog, None).map_err(AgentError::from_cli_error)?;
    if report.ok {
        return Ok(());
    }
    let mut error = agent_error(
        "AGENT_REQUEST_INVALID",
        format!("Request for `{method}` does not match the catalog schema."),
    );
    error.details.insert(
        "validation".to_string(),
        serde_json::to_value(report).unwrap_or(Value::Null),
    );
    Err(error)
}
