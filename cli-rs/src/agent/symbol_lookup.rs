const INDEXED_EXACT_FALLBACK_CODES: [&str; 13] = [
    "MACOS_PLUGIN_WORKSPACE_REQUIRED",
    "NO_BACKEND_AVAILABLE",
    "IDEA_NOT_RUNNING",
    "IDEA_BACKEND_DISABLED",
    "IDEA_PLUGIN_NOT_INSTALLED",
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
        AgentCompilerResolveResponse::Resolved { symbol } if symbol.has_complete_anchor() => {
            let symbol = serde_json::to_value(symbol).unwrap_or(Value::Null);
            symbol_lookup_envelope(
                args.mode,
                compiler_request,
                AgentSymbolLookupOutcome::Resolved {
                    source: AgentSymbolLookupSource::Compiler,
                    symbol,
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

fn symbol_has_complete_anchor(symbol: &Value) -> bool {
    let direct_location = symbol.get("location");
    let declaration = symbol.get("declaration");
    let fq_name = symbol
        .get("fqName")
        .or_else(|| declaration.and_then(|value| value.get("fqName")))
        .and_then(Value::as_str);
    let kind = symbol
        .get("kind")
        .or_else(|| declaration.and_then(|value| value.get("kind")))
        .and_then(Value::as_str);
    let file_path = direct_location
        .and_then(|value| value.get("filePath"))
        .or_else(|| {
            symbol
                .get("file")
                .and_then(|value| value.get("path"))
        })
        .or_else(|| {
            declaration
                .and_then(|value| value.get("file"))
                .and_then(|value| value.get("path"))
        })
        .and_then(Value::as_str);
    let start_offset = direct_location
        .and_then(|value| value.get("startOffset"))
        .or_else(|| symbol.get("declarationOffset"))
        .or_else(|| declaration.and_then(|value| value.get("declarationOffset")))
        .and_then(Value::as_u64);

    fq_name.is_some_and(|value| !value.trim().is_empty())
        && kind.is_some_and(|value| !value.trim().is_empty())
        && file_path.is_some_and(|value| !value.trim().is_empty())
        && start_offset.is_some()
}

fn successful_symbol_query_result(
    envelope: AgentEnvelope,
    request: Value,
) -> std::result::Result<Value, Box<AgentEnvelope>> {
    if !envelope.ok {
        return Err(Box::new(error_envelope(
            "agent/symbol".to_string(),
            Some(request),
            envelope.error.unwrap_or_else(|| {
                agent_error(
                    "SYMBOL_QUERY_FAILED",
                    "Symbol query failed without a typed error.",
                )
            }),
        )));
    }
    let Some(result) = envelope.result else {
        return Err(Box::new(error_envelope(
            "agent/symbol".to_string(),
            Some(request),
            agent_error("SYMBOL_QUERY_FAILED", "Symbol query returned no result."),
        )));
    };
    match result.get("type").and_then(Value::as_str) {
        Some("SYMBOL_QUERY_SUCCESS") => Ok(result),
        Some("SYMBOL_QUERY_FAILURE") => {
            let code = result
                .get("reason")
                .and_then(Value::as_str)
                .unwrap_or("SYMBOL_QUERY_FAILED");
            let message = result
                .get("message")
                .and_then(Value::as_str)
                .unwrap_or("Symbol query failed.");
            Err(Box::new(error_envelope(
                "agent/symbol".to_string(),
                Some(request),
                agent_error(code, message),
            )))
        }
        response_type => Err(Box::new(error_envelope(
            "agent/symbol".to_string(),
            Some(request),
            agent_error(
                "INVALID_SYMBOL_QUERY_RESPONSE",
                format!("Unexpected symbol query response type: {response_type:?}"),
            ),
        ))),
    }
}

fn symbol_query_filters(args: &AgentSymbolArgs) -> Value {
    drop_nulls(json!({
        "kinds": args.kind.map(|kind| vec![kind.canonical().to_ascii_uppercase()]),
        "fileGlob": args.file_hint,
    }))
}

fn indexed_exact_query_filters(args: &AgentSymbolArgs) -> Value {
    drop_nulls(json!({
        "kinds": args.kind.map(|kind| vec![kind.canonical().to_ascii_uppercase()]),
    }))
}

fn indexed_exact_search_limit(args: &AgentSymbolArgs) -> u32 {
    if args.file_hint.is_some() {
        INDEXED_EXACT_LITERAL_FILE_SCAN_LIMIT
    } else {
        INDEXED_EXACT_CARDINALITY_LIMIT
    }
}

fn indexed_exact_candidates(
    result: &Value,
    file_hint: Option<&str>,
) -> std::result::Result<Vec<Value>, AgentError> {
    let results = result
        .get("results")
        .and_then(Value::as_array)
        .ok_or_else(|| {
            agent_error(
                "INVALID_SYMBOL_QUERY_RESPONSE",
                "Indexed exact lookup returned no results array.",
            )
        })?;
    results
        .iter()
        .map(|candidate| {
            let proof = serde_json::from_value::<IndexedExactCandidateProof>(candidate.clone())
                .map_err(|error| {
                    agent_error(
                        "INVALID_SYMBOL_QUERY_RESPONSE",
                        format!("Indexed exact candidate lacked trustworthy file identity: {error}"),
                    )
                })?;
            let declaration = candidate.get("declaration").cloned().ok_or_else(|| {
                agent_error(
                    "INVALID_SYMBOL_QUERY_RESPONSE",
                    "Indexed exact candidate returned no declaration.",
                )
            })?;
            Ok((proof, declaration))
        })
        .filter_map(|candidate| match candidate {
            Ok((proof, declaration))
                if file_hint.is_none_or(|hint| {
                    literal_file_hint_matches(hint, &proof.declaration.file.path)
                }) => Some(Ok(declaration)),
            Ok(_) => None,
            Err(error) => Some(Err(error)),
        })
        .collect()
}

fn literal_file_hint_matches(file_hint: &str, candidate_file: &str) -> bool {
    let normalized_hint = lexically_normalized_path(std::path::Path::new(file_hint));
    let normalized_candidate = lexically_normalized_path(std::path::Path::new(candidate_file));
    if normalized_hint.is_absolute() {
        normalized_candidate == normalized_hint
    } else {
        normalized_candidate.ends_with(normalized_hint)
    }
}

fn lexically_normalized_path(path: &std::path::Path) -> std::path::PathBuf {
    let mut normalized = std::path::PathBuf::new();
    for component in path.components() {
        match component {
            std::path::Component::CurDir => {}
            std::path::Component::ParentDir
                if normalized.file_name().is_some_and(|name| name != "..") =>
            {
                normalized.pop();
            }
            std::path::Component::ParentDir if normalized.has_root() => {}
            component => normalized.push(component.as_os_str()),
        }
    }
    normalized
}

fn compiler_availability_allows_indexed_exact(error: &AgentError) -> bool {
    INDEXED_EXACT_FALLBACK_CODES.contains(&error.code.as_str())
}

fn invalid_compiler_symbol_response(request: Value, message: &str) -> AgentEnvelope {
    error_envelope(
        "agent/symbol".to_string(),
        Some(request),
        agent_error("INVALID_COMPILER_RESPONSE", message),
    )
}

fn symbol_lookup_envelope(
    mode: AgentSymbolMode,
    request: Value,
    outcome: AgentSymbolLookupOutcome,
) -> AgentEnvelope {
    result_envelope(
        "agent/symbol".to_string(),
        AgentSymbolLookupResult {
            result_type: "KAST_AGENT_SYMBOL_LOOKUP",
            ok: true,
            mode,
            request,
            outcome,
            schema_version: SCHEMA_VERSION,
        },
    )
}

#[cfg(test)]
mod symbol_lookup_tests {
    use super::*;

    #[test]
    fn exact_availability_allowlist_is_closed_and_exhaustive() {
        for code in INDEXED_EXACT_FALLBACK_CODES {
            assert!(compiler_availability_allows_indexed_exact(&agent_error(code, "unavailable")));
        }
        for code in [
            "IDEA_LAUNCH_CONFIG_INVALID",
            "RPC_RESPONSE_INVALID",
            "RESOLVE_FAILURE",
            "VALIDATION_ERROR",
            "AGENT_REQUEST_INVALID",
        ] {
            assert!(!compiler_availability_allows_indexed_exact(&agent_error(code, "not availability")));
        }
    }

    #[test]
    fn indexed_exact_file_hints_are_literal_paths() {
        assert!(literal_file_hint_matches(
            "lib/Parser.kt",
            "/workspace/lib/Parser.kt"
        ));
        assert!(literal_file_hint_matches(
            "lib/../lib/Parser.kt",
            "/workspace/lib/Parser.kt"
        ));
        assert!(!literal_file_hint_matches(
            "lib/*Parser.kt",
            "/workspace/lib/AlphaParser.kt"
        ));
        assert!(!literal_file_hint_matches(
            "lib/[AB]Parser.kt",
            "/workspace/lib/AParser.kt"
        ));
    }
}
