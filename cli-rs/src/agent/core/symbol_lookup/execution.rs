const INDEXED_EXACT_FALLBACK_CODES: [&str; 12] = [
    "MACOS_PLUGIN_WORKSPACE_REQUIRED",
    "NO_BACKEND_AVAILABLE",
    "IDEA_NOT_RUNNING",
    "IDEA_BACKEND_DISABLED",
    "IDEA_LAUNCH_FAILED",
    "DAEMON_START_ERROR",
    "DAEMON_UNREACHABLE",
    "RUNTIME_TIMEOUT",
    "RPC_RESPONSE_TIMEOUT",
    "RPC_RESPONSE_MISSING",
    "CAPABILITY_NOT_SUPPORTED",
    "CAPABILITIES_UNAVAILABLE",
];
const INDEXED_EXACT_CARDINALITY_LIMIT: u32 = 2;
const INDEXED_EXACT_LITERAL_FILE_SCAN_LIMIT: u32 = u32::MAX;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct IndexedExactCandidateProof {
    declaration: IndexedExactDeclarationProof,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct IndexedExactDeclarationProof {
    file: IndexedExactFileProof,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct IndexedExactFileProof {
    path: String,
}

fn execute_agent_symbol_exact(args: AgentSymbolArgs) -> AgentEnvelope {
    let detailed = args.view.detailed();
    let compiler_params = drop_nulls(json!({
        "symbol": args.query,
        "kind": args.kind.map(|kind| kind.canonical()),
        "fileHint": args.file_hint,
        "containingType": args.containing_type,
        "includeDeclarationScope": detailed,
        "includeDocumentation": detailed,
        "surroundingLines": detailed.then_some(3),
        "includeSurroundingMembers": detailed,
    }));
    let compiler_request = json_rpc_request("symbol/resolve", compiler_params);
    let session = match runtime::raw_rpc_session(
        args.runtime.workspace_root.clone(),
        args.runtime.backend_name,
    ) {
        Ok(session) => session,
        Err(error) => {
            return indexed_exact_or_compiler_error(
                &args,
                compiler_request,
                AgentError::from_cli_error(error),
            );
        }
    };
    let compiler_envelope = execute_request_with_session(
        AgentRequest {
            method: "symbol/resolve".to_string(),
            request: compiler_request.clone(),
            runtime: args.runtime.clone(),
            full_response: true,
            operation: AgentOperation::ReadOnly,
        },
        Some(&session),
    );
    if !compiler_envelope.ok {
        let error = compiler_envelope.error.unwrap_or_else(|| {
            agent_error(
                "INVALID_COMPILER_RESPONSE",
                "Compiler symbol lookup failed without a typed error.",
            )
        });
        return indexed_exact_or_compiler_error(&args, compiler_request, error);
    }
    let Some(result) = compiler_envelope.result else {
        return error_envelope(
            "agent/symbol".to_string(),
            Some(compiler_request),
            agent_error(
                "INVALID_COMPILER_RESPONSE",
                "Compiler symbol lookup returned no result.",
            ),
        );
    };
    let parsed = match serde_json::from_value::<AgentCompilerResolveResponse>(result.clone()) {
        Ok(parsed) => parsed,
        Err(error) => {
            return invalid_compiler_symbol_response(
                compiler_request,
                &format!("compiler response violated the exact lookup contract: {error}"),
            );
        }
    };
    match parsed {
        AgentCompilerResolveResponse::Resolved {
            symbol,
            selector_handle,
        } if symbol.has_complete_anchor() => {
            let symbol = serde_json::to_value(symbol).unwrap_or(Value::Null);
            symbol_lookup_envelope(
                args.mode,
                compiler_request,
                AgentSymbolLookupOutcome::Resolved {
                    source: AgentSymbolLookupSource::Compiler,
                    symbol,
                    selector_handle,
                    resolution: result,
                    relations: Vec::new(),
                    compiler_fallback: None,
                },
            )
        }
        AgentCompilerResolveResponse::Resolved { .. } => symbol_lookup_envelope(
            args.mode,
            compiler_request,
            AgentSymbolLookupOutcome::IdentityAnchorUnavailable {
                source: AgentSymbolLookupSource::Compiler,
                query: args.query,
                compiler_fallback: None,
            },
        ),
        AgentCompilerResolveResponse::NotFound => symbol_lookup_envelope(
            args.mode,
            compiler_request,
            AgentSymbolLookupOutcome::NotFound {
                source: AgentSymbolLookupSource::Compiler,
                query: args.query,
                compiler_fallback: None,
            },
        ),
        AgentCompilerResolveResponse::Ambiguous { candidates } if candidates.len() >= 2 => {
            symbol_lookup_envelope(
                args.mode,
                compiler_request,
                AgentSymbolLookupOutcome::Ambiguous {
                    source: AgentSymbolLookupSource::Compiler,
                    query: args.query,
                    candidates,
                    compiler_fallback: None,
                },
            )
        }
        AgentCompilerResolveResponse::Ambiguous { .. } => invalid_compiler_symbol_response(
            compiler_request,
            "RESOLVE_AMBIGUOUS must contain at least two candidates",
        ),
        AgentCompilerResolveResponse::OperationalFailure => invalid_compiler_symbol_response(
            compiler_request,
            "RESOLVE_FAILURE was marked successful",
        ),
    }
}

fn execute_agent_symbol_discovery(args: AgentSymbolArgs) -> AgentEnvelope {
    let detailed = args.view.detailed();
    let request = json_rpc_request(
        "symbol/query",
        json!({
            "query": args.query,
            "modes": ["lexical"],
            "filters": symbol_query_filters(&args),
            "limit": args.limit,
            "includeEvidence": detailed,
            "includeNextRequests": detailed,
        }),
    );
    let envelope = execute_request(AgentRequest {
        method: "symbol/query".to_string(),
        request: request.clone(),
        runtime: args.runtime,
        full_response: true,
        operation: AgentOperation::ReadOnly,
    });
    let result = match successful_symbol_query_result(envelope, request.clone()) {
        Ok(result) => result,
        Err(envelope) => return *envelope,
    };
    symbol_lookup_envelope(
        args.mode,
        request,
        AgentSymbolLookupOutcome::Discovered {
            source: AgentSymbolLookupSource::Fuzzy,
            query: args.query,
            candidates: result
                .get("results")
                .and_then(Value::as_array)
                .cloned()
                .unwrap_or_default(),
        },
    )
}

fn indexed_exact_or_compiler_error(
    args: &AgentSymbolArgs,
    compiler_request: Value,
    error: AgentError,
) -> AgentEnvelope {
    if !compiler_availability_allows_indexed_exact(&error) || args.containing_type.is_some() {
        return error_envelope("agent/symbol".to_string(), Some(compiler_request), error);
    }
    let fallback = AgentCompilerFallback {
        code: error.code,
        message: error.message,
    };
    let request = json_rpc_request(
        "symbol/query",
        json!({
            "query": args.query,
            "modes": ["exact"],
            "filters": indexed_exact_query_filters(args),
            "limit": indexed_exact_search_limit(args),
            "includeEvidence": args.view.detailed(),
            "includeNextRequests": false,
        }),
    );
    let envelope = execute_request(AgentRequest {
        method: "symbol/query".to_string(),
        request: request.clone(),
        runtime: args.runtime.clone(),
        full_response: true,
        operation: AgentOperation::ReadOnly,
    });
    let result = match successful_symbol_query_result(envelope, request.clone()) {
        Ok(result) => result,
        Err(envelope) => return *envelope,
    };
    let candidates = match indexed_exact_candidates(&result, args.file_hint.as_deref()) {
        Ok(candidates) => candidates,
        Err(error) => {
            return error_envelope("agent/symbol".to_string(), Some(request), error);
        }
    };
    let outcome = match candidates.as_slice() {
        [] => AgentSymbolLookupOutcome::NotFound {
            source: AgentSymbolLookupSource::IndexedExact,
            query: args.query.clone(),
            compiler_fallback: Some(fallback),
        },
        [symbol] if symbol_has_complete_anchor(symbol) => AgentSymbolLookupOutcome::Resolved {
            source: AgentSymbolLookupSource::IndexedExact,
            symbol: symbol.clone(),
            selector_handle: None,
            resolution: result,
            relations: Vec::new(),
            compiler_fallback: Some(fallback),
        },
        [_] => AgentSymbolLookupOutcome::IdentityAnchorUnavailable {
            source: AgentSymbolLookupSource::IndexedExact,
            query: args.query.clone(),
            compiler_fallback: Some(fallback),
        },
        _ => AgentSymbolLookupOutcome::Ambiguous {
            source: AgentSymbolLookupSource::IndexedExact,
            query: args.query.clone(),
            candidates,
            compiler_fallback: Some(fallback),
        },
    };
    symbol_lookup_envelope(args.mode, request, outcome)
}
