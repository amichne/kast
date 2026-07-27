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
        workspace_root: normalizer.canonical_root.to_string_lossy().into_owned(),
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

fn traversal_query_fingerprint(
    relation: &str,
    selector: &AgentExpectedRelationshipSelector,
    direction: &str,
    depth: Option<u8>,
    limit: u8,
) -> String {
    let proof = [
        selector.workspace_root.clone(),
        relation.to_string(),
        selector.fq_name.clone(),
        selector.declaration_file.clone(),
        selector.declaration_start_offset.to_string(),
        selector.kind.clone().unwrap_or_default(),
        selector.containing_type.clone().unwrap_or_default(),
        String::new(),
        direction.to_string(),
        depth.map(|value| value.to_string()).unwrap_or_default(),
        limit.to_string(),
    ]
    .join("\n");
    crate::manifest::sha256_bytes(proof.as_bytes())[..24].to_string()
}

fn selector_handle_traversal_query_fingerprint(
    workspace_root: &str,
    relation: &str,
    handle: &AgentSelectorHandle,
    direction: &str,
    depth: Option<u8>,
    limit: u8,
) -> String {
    let proof = [
        workspace_root.to_string(),
        relation.to_string(),
        "selector-handle".to_string(),
        handle.as_str().to_string(),
        direction.to_string(),
        depth.map(|value| value.to_string()).unwrap_or_default(),
        limit.to_string(),
    ]
    .join("\n");
    crate::manifest::sha256_bytes(proof.as_bytes())[..24].to_string()
}

fn decode_traversal_page_token(
    token: &AgentRelationPageToken,
    expected_relation: &str,
    expected_fingerprint: &str,
) -> std::result::Result<String, AgentError> {
    let fields = token.as_str().split('.').collect::<Vec<_>>();
    if fields.len() != 5
        || fields[0] != AGENT_RELATION_TOKEN_VERSION
        || fields[3] != AGENT_TRAVERSAL_PAYLOAD_TAG
        || !is_known_relation(fields.get(1).copied().unwrap_or_default())
        || fields[2].len() != 24
        || !fields[2]
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        return Err(agent_error(
            "RELATION_PAGE_TOKEN_INVALID",
            "The relationship page token has an invalid version, relation, fingerprint, or payload family.",
        ));
    }
    if fields[1] != expected_relation || fields[2] != expected_fingerprint {
        return Err(agent_error(
            "RELATION_PAGE_TOKEN_MISMATCH",
            "The relationship page token was issued for a different workspace or query.",
        ));
    }
    canonical_traversal_handle(fields[4], expected_relation)
}

fn canonical_traversal_handle(
    value: &str,
    expected_relation: &str,
) -> std::result::Result<String, AgentError> {
    let fields = value.split('_').collect::<Vec<_>>();
    if fields.len() != 3 || fields[0] != "rth1" || !is_known_traversal_relation(fields[1]) {
        return Err(agent_error(
            "RELATION_PAGE_TOKEN_INVALID",
            "The opaque traversal handle is malformed.",
        ));
    }
    if fields[1] != expected_relation {
        return Err(agent_error(
            "RELATION_PAGE_TOKEN_MISMATCH",
            "The opaque traversal handle belongs to a different relationship family.",
        ));
    }
    let parsed = uuid::Uuid::parse_str(fields[2]).map_err(|_| {
        agent_error(
            "RELATION_PAGE_TOKEN_INVALID",
            "The opaque traversal handle UUID is malformed.",
        )
    })?;
    if parsed.hyphenated().to_string() != fields[2] {
        return Err(agent_error(
            "RELATION_PAGE_TOKEN_INVALID",
            "The opaque traversal handle UUID is not canonical lowercase text.",
        ));
    }
    Ok(value.to_string())
}

fn wrap_traversal_page_token(
    mut envelope: AgentEnvelope,
    request: Value,
    relation: &str,
    fingerprint: &str,
) -> AgentEnvelope {
    if !envelope.ok {
        return envelope;
    }
    let Some(result) = envelope.result.as_mut() else {
        return invalid_traversal_response(
            envelope.method,
            request,
            "The relationship endpoint returned no result.",
        );
    };
    if result.get("type").and_then(Value::as_str) != Some("AVAILABLE") {
        return envelope;
    }
    let Some(page) = result.get_mut("page").and_then(Value::as_object_mut) else {
        return invalid_traversal_response(
            envelope.method,
            request,
            "Available relationship evidence omitted its page object.",
        );
    };
    let truncated = page.get("truncated").and_then(Value::as_bool);
    let raw_handle = page.remove("nextHandle");
    match (truncated, raw_handle) {
        (Some(false), None | Some(Value::Null)) => envelope,
        (Some(true), Some(Value::String(handle))) => {
            let handle = match canonical_traversal_handle(&handle, relation) {
                Ok(handle) => handle,
                Err(_) => {
                    return invalid_traversal_response(
                        envelope.method,
                        request,
                        "The backend returned a malformed traversal handle.",
                    );
                }
            };
            page.insert(
                "nextPageToken".to_string(),
                Value::String(format!(
                    "{AGENT_RELATION_TOKEN_VERSION}.{relation}.{fingerprint}.{AGENT_TRAVERSAL_PAYLOAD_TAG}.{handle}"
                )),
            );
            envelope
        }
        _ => invalid_traversal_response(
            envelope.method,
            request,
            "Relationship truncation disagreed with traversal-handle presence.",
        ),
    }
}

fn invalid_traversal_response(
    method: String,
    request: Value,
    message: &str,
) -> AgentEnvelope {
    error_envelope(
        method,
        Some(request),
        agent_error("INVALID_RELATION_RESPONSE", message),
    )
}

fn is_known_relation(value: &str) -> bool {
    matches!(
        value,
        "references" | "callers" | "callees" | "implementations" | "hierarchy" | "impact"
    )
}

fn is_known_traversal_relation(value: &str) -> bool {
    matches!(value, "callers" | "callees" | "implementations" | "hierarchy")
}
