const AGENT_RELATION_TOKEN_VERSION: &str = "krp1";
const AGENT_REFERENCE_RELATION: &str = "references";
const AGENT_REFERENCE_PAYLOAD_TAG: &str = "reference";

fn execute_agent_callers(args: AgentCallersArgs) -> AgentEnvelope {
    execute_agent_call_relationship(
        "agent/callers",
        "callers",
        "CALLER",
        "INCOMING",
        args.runtime,
        args.selector,
        args.depth.get(),
        args.limit.get(),
        args.page_token,
        args.view,
    )
}

fn execute_agent_callees(args: AgentCalleesArgs) -> AgentEnvelope {
    execute_agent_call_relationship(
        "agent/callees",
        "callees",
        "CALLEE",
        "OUTGOING",
        args.runtime,
        args.selector,
        args.depth.get(),
        args.limit.get(),
        args.page_token,
        args.view,
    )
}

#[allow(clippy::too_many_arguments)]
fn execute_agent_call_relationship(
    public_method: &str,
    relation: &'static str,
    record_relation: &'static str,
    direction: &'static str,
    runtime: AgentRuntimeArgs,
    selector: AgentExactSymbolSelectorArgs,
    depth: u8,
    limit: u8,
    page_token: Option<AgentRelationPageToken>,
    view: AgentRelationViewArgs,
) -> AgentEnvelope {
    if page_token.is_some() {
        return relationship_continuation_unavailable(public_method);
    }
    let (declaration_file, expected) = match normalize_relationship_selector(
        public_method,
        &runtime,
        &selector,
    ) {
        Ok(value) => value,
        Err(envelope) => return *envelope,
    };
    let request = json_rpc_request(
        "raw/call-hierarchy",
        json!({
            "position": {
                "filePath": declaration_file,
                "offset": selector.declaration_start_offset.get(),
            },
            "direction": direction,
            "depth": depth,
            "maxTotalCalls": limit,
            "maxChildrenPerNode": limit,
        }),
    );
    let envelope = execute_request(AgentRequest {
        method: "raw/call-hierarchy".to_string(),
        request,
        runtime,
        full_response: true,
        operation: AgentOperation::ReadOnly,
    });
    project_raw_call_relationship_envelope(
        public_method.to_string(),
        envelope,
        expected,
        relation,
        record_relation,
        direction,
        usize::from(limit),
        usize::from(depth),
        AgentResultView::from_parts(view.verbose, view.explain, &view.fields, view.count),
    )
}

fn execute_agent_implementations(args: AgentImplementationsArgs) -> AgentEnvelope {
    if args.page_token.is_some() {
        return relationship_continuation_unavailable("agent/implementations");
    }
    let (declaration_file, expected) = match normalize_relationship_selector(
        "agent/implementations",
        &args.runtime,
        &args.selector,
    ) {
        Ok(value) => value,
        Err(envelope) => return *envelope,
    };
    let request = json_rpc_request(
        "raw/implementations",
        json!({
            "position": {
                "filePath": declaration_file,
                "offset": args.selector.declaration_start_offset.get(),
            },
            "maxResults": args.limit.get(),
        }),
    );
    let envelope = execute_request(AgentRequest {
        method: "raw/implementations".to_string(),
        request,
        runtime: args.runtime,
        full_response: true,
        operation: AgentOperation::ReadOnly,
    });
    project_raw_implementations_envelope(
        "agent/implementations".to_string(),
        envelope,
        expected,
        usize::from(args.limit.get()),
        AgentResultView::from_parts(
            args.view.verbose,
            args.view.explain,
            &args.view.fields,
            args.view.count,
        ),
    )
}

fn execute_agent_hierarchy(args: AgentHierarchyArgs) -> AgentEnvelope {
    if args.page_token.is_some() {
        return relationship_continuation_unavailable("agent/hierarchy");
    }
    let direction = match args.direction {
        AgentHierarchyDirection::Supertypes => "SUPERTYPES",
        AgentHierarchyDirection::Subtypes => "SUBTYPES",
        AgentHierarchyDirection::Both => "BOTH",
    };
    let record_relation = match args.direction {
        AgentHierarchyDirection::Supertypes => "SUPERTYPE",
        AgentHierarchyDirection::Subtypes => "SUBTYPE",
        AgentHierarchyDirection::Both => {
            return error_envelope(
                "agent/hierarchy".to_string(),
                None,
                agent_error(
                    "RELATIONSHIP_DIRECTION_UNAVAILABLE",
                    "The bounded compiler engine cannot preserve edge direction for a combined hierarchy request.",
                ),
            );
        }
    };
    let (declaration_file, expected) = match normalize_relationship_selector(
        "agent/hierarchy",
        &args.runtime,
        &args.selector,
    ) {
        Ok(value) => value,
        Err(envelope) => return *envelope,
    };
    let request = json_rpc_request(
        "raw/type-hierarchy",
        json!({
            "position": {
                "filePath": declaration_file,
                "offset": args.selector.declaration_start_offset.get(),
            },
            "direction": direction,
            "depth": args.depth.get(),
            "maxResults": args.limit.get(),
        }),
    );
    let envelope = execute_request(AgentRequest {
        method: "raw/type-hierarchy".to_string(),
        request,
        runtime: args.runtime,
        full_response: true,
        operation: AgentOperation::ReadOnly,
    });
    project_raw_hierarchy_envelope(
        "agent/hierarchy".to_string(),
        envelope,
        expected,
        record_relation,
        usize::from(args.limit.get()),
        usize::from(args.depth.get()),
        AgentResultView::from_parts(
            args.view.verbose,
            args.view.explain,
            &args.view.fields,
            args.view.count,
        ),
    )
}

fn normalize_relationship_selector(
    public_method: &str,
    runtime: &AgentRuntimeArgs,
    selector: &AgentExactSymbolSelectorArgs,
) -> std::result::Result<(String, AgentExpectedRelationshipSelector), Box<AgentEnvelope>> {
    let normalizer = AgentFilePathNormalizer::from_runtime(runtime)
        .map_err(|error| Box::new(error_envelope(public_method.to_string(), None, error)))?;
    let declaration_file = normalizer
        .normalize(selector.declaration_file.as_str())
        .map_err(|error| Box::new(error_envelope(public_method.to_string(), None, error)))?
        .into_rpc_path();
    let expected = AgentExpectedRelationshipSelector {
        fq_name: selector.symbol.as_str().to_string(),
        declaration_file: declaration_file.clone(),
        declaration_start_offset: u64::from(selector.declaration_start_offset.get()),
        kind: selector
            .kind
            .map(|kind| kind.canonical().to_ascii_uppercase()),
        containing_type: selector
            .containing_type
            .as_ref()
            .map(|value| value.as_str().to_string()),
    };
    Ok((declaration_file, expected))
}

fn relationship_continuation_unavailable(method: &str) -> AgentEnvelope {
    error_envelope(
        method.to_string(),
        None,
        agent_error(
            "RELATION_PAGE_TOKEN_INVALID",
            "This compiler relationship engine did not issue a resumable page token.",
        ),
    )
}

fn execute_agent_references(args: AgentReferencesArgs) -> AgentEnvelope {
    let normalizer = match AgentFilePathNormalizer::from_runtime(&args.runtime) {
        Ok(normalizer) => normalizer,
        Err(error) => return error_envelope("agent/references".to_string(), None, error),
    };
    let declaration_file = match normalizer.normalize(args.selector.declaration_file.as_str()) {
        Ok(path) => path.into_rpc_path(),
        Err(error) => return error_envelope("agent/references".to_string(), None, error),
    };
    let fingerprint = reference_query_fingerprint(&normalizer, &declaration_file, &args);
    let page_token = match args.page_token.as_ref() {
        Some(token) => match decode_reference_page_token(token, &fingerprint) {
            Ok(token) => Some(token),
            Err(error) => {
                return error_envelope("agent/references".to_string(), None, error);
            }
        },
        None => None,
    };
    let selector = drop_nulls(json!({
        "fqName": args.selector.symbol.as_str(),
        "declarationFile": declaration_file,
        "declarationStartOffset": args.selector.declaration_start_offset.get(),
        "kind": args.selector.kind.map(|kind| kind.canonical().to_ascii_uppercase()),
        "containingType": args.selector.containing_type.as_ref().map(CanonicalSymbolName::as_str),
    }));
    let params = drop_nulls(json!({
        "selector": selector,
        "includeDeclaration": args.include_declaration,
        "maxResults": args.limit.get(),
        "pageToken": page_token,
    }));
    let request = json_rpc_request("symbol/references", params);
    let envelope = execute_request(AgentRequest {
        method: "symbol/references".to_string(),
        request: request.clone(),
        runtime: args.runtime,
        full_response: true,
        operation: AgentOperation::ReadOnly,
    });
    wrap_reference_page_token(envelope, request, &fingerprint)
}

fn reference_query_fingerprint(
    normalizer: &AgentFilePathNormalizer,
    declaration_file: &str,
    args: &AgentReferencesArgs,
) -> String {
    let kind = args
        .selector
        .kind
        .map(|kind| kind.canonical().to_ascii_uppercase())
        .unwrap_or_default();
    let containing_type = args
        .selector
        .containing_type
        .as_ref()
        .map(CanonicalSymbolName::as_str)
        .unwrap_or_default();
    let proof = [
        normalizer.canonical_root.to_string_lossy().into_owned(),
        AGENT_REFERENCE_RELATION.to_string(),
        args.selector.symbol.as_str().to_string(),
        declaration_file.to_string(),
        args.selector.declaration_start_offset.get().to_string(),
        kind,
        containing_type.to_string(),
        args.include_declaration.to_string(),
        String::new(),
        String::new(),
        args.limit.get().to_string(),
    ]
    .join("\n");
    crate::manifest::sha256_bytes(proof.as_bytes())[..24].to_string()
}

fn decode_reference_page_token(
    token: &AgentRelationPageToken,
    expected_fingerprint: &str,
) -> std::result::Result<String, AgentError> {
    let fields = token.as_str().split('.').collect::<Vec<_>>();
    if fields.len() != 5
        || fields[0] != AGENT_RELATION_TOKEN_VERSION
        || fields[1] != AGENT_REFERENCE_RELATION
        || fields[3] != AGENT_REFERENCE_PAYLOAD_TAG
    {
        return Err(agent_error(
            "RELATION_PAGE_TOKEN_INVALID",
            "The relationship page token has the wrong version, relation, or payload family.",
        ));
    }
    if fields[2] != expected_fingerprint {
        return Err(agent_error(
            "RELATION_PAGE_TOKEN_MISMATCH",
            "The relationship page token was issued for a different workspace or query.",
        ));
    }
    canonical_reference_page_token(fields[4])
}

fn wrap_reference_page_token(
    mut envelope: AgentEnvelope,
    request: Value,
    fingerprint: &str,
) -> AgentEnvelope {
    if !envelope.ok {
        return envelope;
    }
    let Some(result) = envelope.result.as_mut() else {
        return invalid_reference_response(request, "The references endpoint returned no result.");
    };
    if result.get("type").and_then(Value::as_str) != Some("AVAILABLE") {
        return envelope;
    }
    let Some(page) = result.get_mut("page") else {
        return envelope;
    };
    if page.is_null() {
        return envelope;
    }
    let Some(page) = page.as_object_mut() else {
        return invalid_reference_response(request, "Reference page evidence was not an object.");
    };
    let Some(raw_token) = page.get("nextPageToken").and_then(Value::as_str) else {
        return envelope;
    };
    let raw_token = match canonical_reference_page_token(raw_token) {
        Ok(token) => token,
        Err(_) => {
            return invalid_reference_response(
                request,
                "The backend returned a malformed opaque reference page token.",
            );
        }
    };
    page.insert(
        "nextPageToken".to_string(),
        Value::String(format!(
            "{AGENT_RELATION_TOKEN_VERSION}.{AGENT_REFERENCE_RELATION}.{fingerprint}.{AGENT_REFERENCE_PAYLOAD_TAG}.{raw_token}"
        )),
    );
    envelope
}

fn canonical_reference_page_token(
    value: &str,
) -> std::result::Result<String, AgentError> {
    let parsed = uuid::Uuid::parse_str(value).map_err(|_| {
        agent_error(
            "RELATION_PAGE_TOKEN_INVALID",
            "The opaque reference page token is malformed.",
        )
    })?;
    let canonical = parsed.hyphenated().to_string();
    if canonical != value {
        return Err(agent_error(
            "RELATION_PAGE_TOKEN_INVALID",
            "The opaque reference page token is not in canonical lowercase form.",
        ));
    }
    Ok(canonical)
}

fn invalid_reference_response(request: Value, message: &str) -> AgentEnvelope {
    error_envelope(
        "agent/references".to_string(),
        Some(request),
        agent_error("INVALID_RELATION_RESPONSE", message),
    )
}
