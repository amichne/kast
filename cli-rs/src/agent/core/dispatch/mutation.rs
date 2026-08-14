fn execute_agent_add_file(args: AgentAddFileArgs) -> AgentEnvelope {
    let file_path = match normalize_agent_file_target(&args.runtime, &args.file_path) {
        Ok(file_path) => file_path,
        Err(error) => return error_envelope("agent/add-file".to_string(), None, error),
    };
    let params = json!({
        "filePath": file_path,
        "contentFile": args.content_file.display().to_string(),
    });
    if !args.mutation.apply {
        let request = json_rpc_request("symbol/add-file", params);
        return execute_agent_add_file_preview(args.runtime, request, file_path, args.content_file);
    }
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

fn execute_agent_add_declaration(_args: AgentScopedMutationArgs) -> AgentEnvelope {
    error_envelope(
        "agent/add-declaration".to_string(),
        None,
        agent_error(
            "KAST_VERIFIED_ADD_DECLARATION_WORKFLOW_REQUIRED",
            "Use `kast change plan add-declaration --file ...`, then approve the durable plan with `kast change apply --plan-id ...`.",
        ),
    )
}

fn execute_agent_scoped_mutation(
    agent_method: &'static str,
    request_method: &'static str,
    mutation_kind: &'static str,
    command_name: &'static str,
    args: AgentScopedMutationArgs,
) -> AgentEnvelope {
    let inside_file = match args.inside_file {
        Some(inside_file) => match normalize_agent_file_target(&args.runtime, &inside_file) {
            Ok(inside_file) => Some(inside_file),
            Err(error) => return error_envelope(agent_method.to_string(), None, error),
        },
        None => None,
    };
    let placement = match scoped_placement_params(
        args.inside_scope,
        inside_file,
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
    let params = match (args.symbol.as_ref(), args.selector_handle.as_ref()) {
        (Some(symbol), None) => drop_nulls(json!({
            "type": "REPLACE_DECLARATION_BY_SYMBOL_REQUEST",
            "symbol": symbol,
            "contentFile": args.content_file.display().to_string(),
            "kind": args.kind.map(|kind| kind.canonical()),
            "fileHint": args.file_hint.as_ref(),
            "containingType": args.containing_type.as_ref(),
        })),
        (None, Some(handle)) => json!({
            "type": "REPLACE_DECLARATION_BY_SELECTOR_HANDLE_REQUEST",
            "selectorHandle": handle,
            "contentFile": args.content_file.display().to_string(),
        }),
        _ => {
            return error_envelope(
                "agent/replace-declaration".to_string(),
                None,
                agent_error(
                    "INVALID_SELECTOR_INPUT",
                    "Provide exactly one of --symbol or --selector-handle.",
                ),
            );
        }
    };
    if !args.mutation.apply {
        return execute_agent_replacement_preview(args, params);
    }
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
    let request = match applied_mutation_request(mutation_kind, idempotency_key, params, &runtime) {
        Ok(request) => request,
        Err(error) => return error_envelope(agent_method.to_string(), None, error),
    };
    execute_request(AgentRequest {
        method: "mutation/submit".to_string(),
        request,
        runtime,
        full_response: true,
        operation: AgentOperation::AppliedMutation,
    })
}

fn applied_mutation_request(
    mutation_kind: &'static str,
    idempotency_key: String,
    params: Value,
    _runtime: &AgentRuntimeArgs,
) -> std::result::Result<Value, AgentError> {
    Ok(json_rpc_request(
        "mutation/submit",
        json!({
            "type": mutation_kind,
            "idempotencyKey": idempotency_key,
            "request": params,
        }),
    ))
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
