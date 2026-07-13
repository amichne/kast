pub fn run(command: AgentCommand, output_format: OutputFormat) -> Result<i32> {
    let envelope = execute(command);
    let exit_code = if envelope.ok { 0 } else { 1 };
    output::print_structured(&envelope, output_format)?;
    Ok(exit_code)
}

fn execute(command: AgentCommand) -> AgentEnvelope {
    if matches!(command, AgentCommand::Lsp(_)) {
        return error_envelope(
            "agent/operator".to_string(),
            None,
            agent_error(
                "AGENT_COMMAND_UNSUPPORTED",
                "`kast agent lsp` is an operator command handled before JSON envelope dispatch.",
            ),
        );
    }
    if let AgentCommand::Workflow(_) = command {
        return removed_agent_command(
            "agent/workflow",
            "`kast agent workflow` is no longer public. Use `kast agent verify`, `kast agent symbol`, `kast agent diagnostics`, `kast agent impact`, `kast agent rename`, or `kast repair --apply`.",
            replacement_commands([
                "kast agent verify --workspace-root <repo>",
                "kast agent symbol --query <name> --workspace-root <repo>",
                "kast agent diagnostics --file-path <path> --workspace-root <repo>",
                "kast repair --apply",
            ]),
        );
    }
    if let AgentCommand::Tools(args) = command {
        let _ = args;
        return removed_agent_command(
            "agent/tools",
            "`kast agent tools` is no longer public. Use `kast`, `kast help`, and the installed Kast skill for the CLI dialect.",
            replacement_commands([
                "kast",
                "kast help agent",
                "kast agent verify --workspace-root <repo>",
            ]),
        );
    }
    match command {
        AgentCommand::Lsp(_) => {
            unreachable!("operator agent commands are handled before request prep")
        }
        AgentCommand::Tools(_) => unreachable!("agent tools is handled before request prep"),
        AgentCommand::Call(_) => removed_agent_command(
            "agent/call",
            "`kast agent call <method>` is no longer public. Use typed `kast agent` commands; generated catalogs remain internal contracts.",
            replacement_commands([
                "kast agent symbol --query <name> --workspace-root <repo>",
                "kast agent diagnostics --file-path <path> --workspace-root <repo>",
                "kast agent rename --symbol <fq-name> --new-name <name> --apply --workspace-root <repo>",
            ]),
        ),
        AgentCommand::Workflow(_) => unreachable!("workflow is handled before request prep"),
        AgentCommand::Verify(args) => execute_agent_verify(args),
        AgentCommand::Symbol(args) => execute_agent_symbol(args),
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
        AgentCommand::Operation(args) => execute_agent_operation(args),
    }
}

fn replacement_commands<const N: usize>(commands: [&str; N]) -> Vec<Value> {
    commands
        .into_iter()
        .map(|command| Value::String(command.to_string()))
        .collect()
}

fn removed_agent_command(method: &str, message: &str, replacements: Vec<Value>) -> AgentEnvelope {
    let mut error = agent_error("AGENT_COMMAND_REMOVED", message);
    error
        .details
        .insert("replacements".to_string(), Value::Array(replacements));
    error_envelope(method.to_string(), None, error)
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

fn execute_agent_symbol(args: AgentSymbolArgs) -> AgentEnvelope {
    match args.mode {
        AgentSymbolMode::Exact => execute_agent_symbol_exact(args),
        AgentSymbolMode::Discovery => execute_agent_symbol_discovery(args),
    }
}

fn execute_agent_impact(args: AgentImpactArgs) -> AgentEnvelope {
    execute_agent_steps(
        "agent/impact",
        args.runtime,
        vec![AgentPublicStep::new(
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
    )
}

fn execute_agent_diagnostics(args: AgentDiagnosticsArgs) -> AgentEnvelope {
    let mut steps = Vec::new();
    if !args.skip_refresh {
        steps.push(AgentPublicStep::new(
            "workspace-refresh",
            "raw/workspace-refresh",
            json!({ "filePaths": args.file_paths }),
            false,
        ));
    }
    steps.push(AgentPublicStep::new(
        "diagnostics",
        "raw/diagnostics",
        json!({ "filePaths": args.file_paths }),
        false,
    ));
    execute_agent_steps("agent/diagnostics", args.runtime, steps)
}

fn execute_agent_rename(args: AgentRenameArgs) -> AgentEnvelope {
    let params = drop_nulls(json!({
        "type": "RENAME_BY_SYMBOL_REQUEST",
        "symbol": args.symbol,
        "newName": args.new_name,
        "kind": args.kind.map(|kind| kind.canonical()),
        "fileHint": args.file_hint,
        "containingType": args.containing_type,
    }));
    let request = json_rpc_request("symbol/rename", params.clone());
    if !args.mutation.apply {
        let result = json!({
            "type": "KAST_AGENT_RENAME_PLAN",
            "ok": true,
            "mutates": true,
            "applyRequired": true,
            "request": request,
            "help": [
                "Run `kast agent rename --symbol <fq-name> --new-name <name> --apply --workspace-root <repo>` to apply this rename."
            ],
            "schemaVersion": SCHEMA_VERSION,
        });
        return result_envelope("agent/rename".to_string(), result);
    }
    let idempotency_key = match applied_idempotency_key(args.mutation) {
        Ok(key) => key,
        Err(error) => return error_envelope("agent/rename".to_string(), None, error),
    };
    let request = json_rpc_request(
        "mutation/submit",
        json!({
            "type": "RENAME",
            "idempotencyKey": idempotency_key,
            "request": params,
        }),
    );
    execute_request(AgentRequest {
        method: "mutation/submit".to_string(),
        request,
        runtime: args.runtime,
        full_response: true,
    })
}

fn execute_agent_add_file(args: AgentAddFileArgs) -> AgentEnvelope {
    let params = json!({
        "filePath": args.file_path,
        "contentFile": args.content_file.display().to_string(),
    });
    execute_agent_mutation(
        "agent/add-file",
        "symbol/add-file",
        "ADD_FILE",
        "add-file",
        params,
        args.mutation,
        args.runtime,
    )
}

fn execute_agent_scoped_mutation(
    agent_method: &'static str,
    request_method: &'static str,
    mutation_kind: &'static str,
    command_name: &'static str,
    args: AgentScopedMutationArgs,
) -> AgentEnvelope {
    let placement = match scoped_placement_params(
        args.inside_scope,
        args.inside_file,
        args.at.map(|anchor| anchor.canonical().to_string()),
        args.after_symbol,
        args.before_symbol,
    ) {
        Ok(placement) => placement,
        Err(error) => return error_envelope(agent_method.to_string(), None, error),
    };
    let params = json!({
        "placement": placement,
        "contentFile": args.content_file.display().to_string(),
    });
    execute_agent_mutation(
        agent_method,
        request_method,
        mutation_kind,
        command_name,
        params,
        args.mutation,
        args.runtime,
    )
}

fn execute_agent_add_statement(args: AgentStatementMutationArgs) -> AgentEnvelope {
    let params = json!({
        "insideScope": args.inside_scope,
        "anchor": args.at.canonical(),
        "contentFile": args.content_file.display().to_string(),
    });
    execute_agent_mutation(
        "agent/add-statement",
        "symbol/add-statement",
        "ADD_STATEMENT",
        "add-statement",
        params,
        args.mutation,
        args.runtime,
    )
}

fn execute_agent_replace_declaration(args: AgentReplaceDeclarationArgs) -> AgentEnvelope {
    let params = drop_nulls(json!({
        "symbol": args.symbol,
        "contentFile": args.content_file.display().to_string(),
        "kind": args.kind.map(|kind| kind.canonical()),
        "fileHint": args.file_hint,
        "containingType": args.containing_type,
    }));
    execute_agent_mutation(
        "agent/replace-declaration",
        "symbol/replace-declaration",
        "REPLACE_DECLARATION",
        "replace-declaration",
        params,
        args.mutation,
        args.runtime,
    )
}

fn execute_agent_mutation(
    agent_method: &'static str,
    request_method: &'static str,
    mutation_kind: &'static str,
    command_name: &'static str,
    params: Value,
    mutation: AgentMutationApplyArgs,
    runtime: AgentRuntimeArgs,
) -> AgentEnvelope {
    let request = json_rpc_request(request_method, params.clone());
    if !mutation.apply {
        return mutation_plan_envelope(agent_method, command_name, request);
    }
    let idempotency_key = match applied_idempotency_key(mutation) {
        Ok(key) => key,
        Err(error) => return error_envelope(agent_method.to_string(), None, error),
    };
    let request = json_rpc_request(
        "mutation/submit",
        json!({
            "type": mutation_kind,
            "idempotencyKey": idempotency_key,
            "request": params,
        }),
    );
    execute_request(AgentRequest {
        method: "mutation/submit".to_string(),
        request,
        runtime,
        full_response: true,
    })
}

fn applied_idempotency_key(
    mutation: AgentMutationApplyArgs,
) -> std::result::Result<String, AgentError> {
    let Some(key) = mutation.idempotency_key else {
        return Err(agent_error(
            "AGENT_USAGE",
            "--idempotency-key is required whenever --apply is used",
        ));
    };
    if key.is_empty() || key.len() > 128 || key.trim() != key {
        return Err(agent_error(
            "AGENT_USAGE",
            "--idempotency-key must contain 1 to 128 characters without surrounding whitespace",
        ));
    }
    Ok(key)
}

fn execute_agent_operation(args: AgentOperationArgs) -> AgentEnvelope {
    match args.command {
        AgentOperationCommand::Status(selector) => {
            execute_agent_operation_request("mutation/status", selector)
        }
        AgentOperationCommand::Cancel(selector) => {
            execute_agent_operation_request("mutation/cancel", selector)
        }
    }
}

fn execute_agent_operation_request(
    method: &'static str,
    args: AgentOperationSelectorArgs,
) -> AgentEnvelope {
    let selector = match (args.operation_id, args.idempotency_key) {
        (Some(operation_id), None) => json!({
            "type": "BY_OPERATION_ID",
            "operationId": operation_id,
        }),
        (None, Some(idempotency_key)) => json!({
            "type": "BY_IDEMPOTENCY_KEY",
            "idempotencyKey": idempotency_key,
        }),
        _ => unreachable!("clap requires exactly one operation selector"),
    };
    execute_request(AgentRequest {
        method: method.to_string(),
        request: json_rpc_request(method, selector),
        runtime: args.runtime,
        full_response: true,
    })
}

fn mutation_plan_envelope(
    agent_method: &'static str,
    command_name: &'static str,
    request: Value,
) -> AgentEnvelope {
    let result = json!({
        "type": "KAST_AGENT_MUTATION_PLAN",
        "ok": true,
        "mutates": true,
        "applyRequired": true,
        "request": request,
        "help": [
            format!("Run `kast agent {command_name} ... --apply --workspace-root <repo>` to apply this mutation.")
        ],
        "schemaVersion": SCHEMA_VERSION,
    });
    result_envelope(agent_method.to_string(), result)
}

fn scoped_placement_params(
    inside_scope: Option<String>,
    inside_file: Option<String>,
    at: Option<String>,
    after_symbol: Option<String>,
    before_symbol: Option<String>,
) -> std::result::Result<Value, AgentError> {
    let scope = match (inside_scope, inside_file) {
        (Some(inside_scope), None) => json!({
            "type": "NAMED_SCOPE",
            "insideScope": inside_scope,
        }),
        (None, Some(inside_file)) => json!({
            "type": "FILE_SCOPE",
            "insideFile": inside_file,
        }),
        (None, None) => {
            return Err(agent_error(
                "AGENT_USAGE",
                "one of --inside-scope or --inside-file is required",
            ));
        }
        (Some(_), Some(_)) => {
            return Err(agent_error(
                "AGENT_USAGE",
                "--inside-scope and --inside-file cannot be used together",
            ));
        }
    };
    let anchor = match (at, after_symbol, before_symbol) {
        (Some(anchor), None, None) => json!({
            "type": "AT_ANCHOR",
            "anchor": anchor,
        }),
        (None, Some(symbol), None) => json!({
            "type": "AFTER_SYMBOL",
            "symbol": symbol,
        }),
        (None, None, Some(symbol)) => json!({
            "type": "BEFORE_SYMBOL",
            "symbol": symbol,
        }),
        (None, None, None) => {
            return Err(agent_error(
                "AGENT_USAGE",
                "one of --at, --after-symbol, or --before-symbol is required",
            ));
        }
        _ => {
            return Err(agent_error(
                "AGENT_USAGE",
                "use only one of --at, --after-symbol, or --before-symbol",
            ));
        }
    };
    Ok(json!({
        "scope": scope,
        "anchor": anchor,
    }))
}

struct AgentPublicStep {
    name: &'static str,
    method: &'static str,
    params: Value,
    mutates: bool,
}

impl AgentPublicStep {
    fn new(name: &'static str, method: &'static str, params: Value, mutates: bool) -> Self {
        Self {
            name,
            method,
            params,
            mutates,
        }
    }
}

fn execute_agent_steps(
    method: &'static str,
    runtime: AgentRuntimeArgs,
    steps: Vec<AgentPublicStep>,
) -> AgentEnvelope {
    let daemon_step_count = steps
        .iter()
        .filter(|step| agent_step_uses_daemon(step.method))
        .count();
    let session = if daemon_step_count > 1 {
        match runtime::raw_rpc_session(runtime.workspace_root.clone(), runtime.backend_name) {
            Ok(session) => Some(session),
            Err(error) => {
                return error_envelope(method.to_string(), None, AgentError::from_cli_error(error));
            }
        }
    } else {
        None
    };
    let mut step_results = Vec::with_capacity(steps.len());
    let mut issues = Vec::new();
    let mut semantic_analysis = None;
    for step in steps {
        let step_session = session
            .as_ref()
            .filter(|_| agent_step_uses_daemon(step.method));
        let envelope = execute_request_with_session(
            AgentRequest {
                method: step.method.to_string(),
                request: json_rpc_request(step.method, step.params),
                runtime: runtime.clone(),
                full_response: false,
            },
            step_session,
        );
        if step.method == "raw/diagnostics" {
            let evidence_is_invalid = envelope
                .error
                .as_ref()
                .is_some_and(|error| error.code == "SEMANTIC_ANALYSIS_INVALID");
            semantic_analysis = (!evidence_is_invalid)
                .then_some(envelope.result.as_ref())
                .flatten()
                .and_then(AgentSemanticAnalysisSummary::from_result);
        }
        if !envelope.ok {
            issues.push(json!({
                "code": "AGENT_STEP_FAILED",
                "step": step.name,
                "method": step.method,
            }));
        }
        step_results.push(json!({
            "name": step.name,
            "method": step.method,
            "mutates": step.mutates,
            "ok": envelope.ok,
            "result": envelope.result,
            "error": envelope.error,
        }));
        if !issues.is_empty() {
            break;
        }
    }
    let ok = issues.is_empty();
    let mut result = json!({
        "type": "KAST_AGENT_COMMAND",
        "ok": ok,
        "steps": step_results,
        "issues": issues,
        "schemaVersion": SCHEMA_VERSION,
    });
    if let (Some(summary), Some(result)) = (semantic_analysis, result.as_object_mut()) {
        result.insert("semanticAnalysis".to_string(), json!(summary));
    }
    let error = (!ok).then(|| {
        let mut error = agent_error("AGENT_COMMAND_FAILED", "Agent command failed.");
        error
            .details
            .insert("issues".to_string(), result["issues"].clone());
        error
    });
    AgentEnvelope {
        ok,
        method: method.to_string(),
        request: None,
        response: None,
        result: Some(result),
        raw_response: None,
        error,
        schema_version: SCHEMA_VERSION,
    }
}

fn agent_step_uses_daemon(method: &str) -> bool {
    !matches!(method, "database/metrics" | "symbol/query")
}
