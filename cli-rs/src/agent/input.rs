fn prepare_call(args: AgentCallArgs) -> std::result::Result<AgentRequest, Box<AgentFailure>> {
    let method = args.method.clone();
    let input = match load_call_input(&args) {
        Ok(input) => input,
        Err(error) => return Err(agent_failure(method, None, error)),
    };
    let request = match normalize_input(&method, input) {
        Ok(request) => request,
        Err(error) => return Err(agent_failure(method, None, error)),
    };
    Ok(AgentRequest {
        method,
        request,
        runtime: args.runtime,
        full_response: args.full,
    })
}

fn prepare_alias(command: AgentCommand) -> AgentRequest {
    let parts = alias_parts(command);
    AgentRequest {
        request: json_rpc_request(&parts.method, parts.params),
        method: parts.method,
        runtime: parts.runtime,
        full_response: true,
    }
}

struct AgentFailure {
    method: String,
    request: Option<Value>,
    error: AgentError,
}

fn agent_failure(method: String, request: Option<Value>, error: AgentError) -> Box<AgentFailure> {
    Box::new(AgentFailure {
        method,
        request,
        error,
    })
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
        | AgentCommand::Lsp(_) => {
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

fn load_call_input(args: &AgentCallArgs) -> std::result::Result<Option<Value>, AgentError> {
    let explicit_sources = [
        args.params.is_some(),
        args.params_file.is_some(),
        args.request_file.is_some(),
    ]
    .into_iter()
    .filter(|present| *present)
    .count();
    if explicit_sources > 1 {
        return Err(agent_error(
            "AGENT_INPUT_CONFLICT",
            "Use only one of --params, --params-file, or --request-file.",
        ));
    }
    if let Some(raw) = &args.params {
        return parse_input(raw);
    }
    if let Some(path) = &args.params_file {
        return parse_input_file(path);
    }
    if let Some(path) = &args.request_file {
        return parse_input_file(path);
    }
    let mut stdin = io::stdin();
    if stdin.is_terminal() {
        return Ok(None);
    }
    let mut raw = String::new();
    if let Err(error) = stdin.read_to_string(&mut raw) {
        return Err(AgentError::from_cli_error(CliError::from(error)));
    }
    if raw.trim().is_empty() {
        return Ok(None);
    }
    parse_input(&raw)
}

fn parse_input(raw: &str) -> std::result::Result<Option<Value>, AgentError> {
    serde_json::from_str(raw)
        .map(Some)
        .map_err(|error| AgentError::from_cli_error(CliError::from(error)))
}

fn parse_input_file(path: &std::path::Path) -> std::result::Result<Option<Value>, AgentError> {
    let raw = fs::read_to_string(path).map_err(|error| AgentError::from_cli_error(error.into()))?;
    parse_input(&raw)
}

fn normalize_input(method: &str, input: Option<Value>) -> std::result::Result<Value, AgentError> {
    let Some(input) = input else {
        return Ok(json_rpc_request(method, json!({})));
    };
    normalize_value(method, input)
}

fn normalize_value(method: &str, value: Value) -> std::result::Result<Value, AgentError> {
    if is_full_json_rpc_request(&value) {
        ensure_method_matches(method, &value)?;
        return Ok(value);
    }
    if let Some(request) = value.get("request").filter(|request| request.is_object()) {
        return normalize_value(method, request.clone());
    }
    if let Some(request) = value
        .get("result")
        .and_then(|result| result.get("nextRequest"))
        .filter(|request| request.is_object())
    {
        return normalize_value(method, request.clone());
    }
    if let Some(request) = value
        .get("nextRequest")
        .filter(|request| request.is_object())
    {
        return normalize_value(method, request.clone());
    }
    if value.get("method").and_then(Value::as_str).is_some() {
        ensure_method_matches(method, &value)?;
        let params = value.get("params").cloned().unwrap_or_else(|| json!({}));
        return Ok(json_rpc_request(method, params));
    }
    Ok(json_rpc_request(method, value))
}

fn is_full_json_rpc_request(value: &Value) -> bool {
    value.get("jsonrpc").and_then(Value::as_str) == Some("2.0")
        && value.get("method").and_then(Value::as_str).is_some()
}

fn ensure_method_matches(method: &str, request: &Value) -> std::result::Result<(), AgentError> {
    let request_method = request
        .get("method")
        .and_then(Value::as_str)
        .ok_or_else(|| agent_error("AGENT_INPUT_INVALID", "Input method must be a string."))?;
    if request_method != method {
        let mut error = agent_error(
            "AGENT_METHOD_MISMATCH",
            format!("Input method `{request_method}` does not match CLI method `{method}`."),
        );
        error
            .details
            .insert("inputMethod".to_string(), json!(request_method));
        error.details.insert("cliMethod".to_string(), json!(method));
        return Err(error);
    }
    Ok(())
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
