fn execute_agent_references(args: AgentReferencesArgs) -> AgentEnvelope {
    let normalizer = match AgentFilePathNormalizer::from_runtime(&args.runtime) {
        Ok(normalizer) => normalizer,
        Err(error) => return error_envelope("agent/references".to_string(), None, error),
    };
    let selector = match args.selector.into_selector() {
        Ok(selector) => selector,
        Err(message) => {
            return error_envelope(
                "agent/references".to_string(),
                None,
                agent_error("INVALID_SELECTOR_INPUT", message),
            );
        }
    };
    let (selector, selector_handle, fingerprint) = match selector {
        AgentReusableSymbolSelector::Explicit(selector) => {
            let (declaration_file, expected) =
                match normalize_relationship_selector("agent/references", &args.runtime, &selector)
                {
                    Ok(value) => value,
                    Err(envelope) => return *envelope,
                };
            let fingerprint = reference_query_fingerprint(
                &expected,
                args.include_declaration,
                args.limit.get(),
            );
            let selector = drop_nulls(json!({
                "fqName": expected.fq_name,
                "declarationFile": declaration_file,
                "declarationStartOffset": expected.declaration_start_offset,
                "kind": expected.kind,
                "containingType": expected.containing_type,
            }));
            (Some(selector), None, fingerprint)
        }
        AgentReusableSymbolSelector::Handle(handle) => {
            let fingerprint = selector_handle_reference_query_fingerprint(
                &normalizer,
                &handle,
                args.include_declaration,
                args.limit.get(),
            );
            (None, Some(handle), fingerprint)
        }
    };
    let page_token = match args.page_token.as_ref() {
        Some(token) => match decode_reference_page_token(token, &fingerprint) {
            Ok(token) => Some(token),
            Err(error) => {
                return error_envelope("agent/references".to_string(), None, error);
            }
        },
        None => None,
    };
    let params = drop_nulls(json!({
        "selector": selector,
        "selectorHandle": selector_handle,
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
    selector: &AgentExpectedRelationshipSelector,
    include_declaration: bool,
    limit: u8,
) -> String {
    let proof = [
        selector.workspace_root.clone(),
        AGENT_REFERENCE_RELATION.to_string(),
        selector.fq_name.clone(),
        selector.declaration_file.clone(),
        selector.declaration_start_offset.to_string(),
        selector.kind.clone().unwrap_or_default(),
        selector.containing_type.clone().unwrap_or_default(),
        include_declaration.to_string(),
        String::new(),
        String::new(),
        limit.to_string(),
    ]
    .join("\n");
    crate::manifest::sha256_bytes(proof.as_bytes())[..24].to_string()
}

fn selector_handle_reference_query_fingerprint(
    normalizer: &AgentFilePathNormalizer,
    handle: &AgentSelectorHandle,
    include_declaration: bool,
    limit: u8,
) -> String {
    let proof = [
        normalizer.canonical_root.to_string_lossy().into_owned(),
        AGENT_REFERENCE_RELATION.to_string(),
        "selector-handle".to_string(),
        handle.as_str().to_string(),
        include_declaration.to_string(),
        limit.to_string(),
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
