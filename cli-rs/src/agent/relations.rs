const AGENT_RELATION_TOKEN_VERSION: &str = "krp1";
const AGENT_REFERENCE_RELATION: &str = "references";
const AGENT_REFERENCE_PAYLOAD_TAG: &str = "reference";
const AGENT_TRAVERSAL_PAYLOAD_TAG: &str = "traversal";
const AGENT_IMPACT_TOKEN_VERSION: &str = "kip1";
const AGENT_IMPACT_MAX_OFFSET: usize = 10_000;

#[derive(Debug, Deserialize)]
struct AgentRawImpactResolveResult {
    #[serde(default)]
    symbol: Option<AgentRawImpactSubject>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRawImpactSubject {
    fq_name: String,
    kind: String,
    location: AgentLocationInput,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    containing_type: Option<String>,
}

fn execute_identity_first_impact(args: AgentImpactArgs) -> AgentEnvelope {
    let (declaration_file, expected) =
        match normalize_relationship_selector("agent/impact", &args.runtime, &args.selector) {
            Ok(value) => value,
            Err(envelope) => return *envelope,
        };
    let detailed = impact_result_view(&args.view).detailed();
    let limit = if detailed {
        args.limit.get()
    } else {
        args.limit.get().min(4)
    };
    let fingerprint = impact_query_fingerprint(&expected, args.depth.get(), limit);
    let offset = match args.page_token.as_ref() {
        Some(token) => match decode_impact_page_token(token, &fingerprint) {
            Ok(offset) => offset,
            Err(error) => return error_envelope("agent/impact".to_string(), None, error),
        },
        None => 0,
    };
    let selector = drop_nulls(json!({
        "fqName": expected.fq_name,
        "declarationFile": declaration_file,
        "declarationStartOffset": expected.declaration_start_offset,
        "kind": expected.kind,
        "containingType": expected.containing_type,
    }));
    let resolve_request = json_rpc_request(
        "raw/resolve",
        json!({
            "position": {
                "filePath": declaration_file,
                "offset": expected.declaration_start_offset,
            }
        }),
    );
    let resolved = execute_request(AgentRequest {
        method: "raw/resolve".to_string(),
        request: resolve_request.clone(),
        runtime: args.runtime.clone(),
        full_response: true,
        operation: AgentOperation::ReadOnly,
    });
    if !resolved.ok {
        return error_envelope(
            "agent/impact".to_string(),
            Some(resolve_request),
            resolved.error.unwrap_or_else(|| {
                agent_error(
                    "IMPACT_SUBJECT_RESOLUTION_FAILED",
                    "Compiler position resolution failed without a typed error.",
                )
            }),
        );
    }
    let Some(resolve_result) = resolved.result else {
        return invalid_projection_envelope(
            "agent/impact".to_string(),
            "Compiler position resolution returned no result.",
        );
    };
    let parsed = match serde_json::from_value::<AgentRawImpactResolveResult>(resolve_result) {
        Ok(parsed) => parsed,
        Err(error) => {
            return invalid_projection_envelope(
                "agent/impact".to_string(),
                format!("Compiler position resolution violated its contract: {error}"),
            );
        }
    };
    let Some(mut subject) = parsed.symbol else {
        return impact_outcome_envelope(selector, None, "SUBJECT_NOT_FOUND", None);
    };
    let Some(start_offset) = subject.location.start_offset else {
        return impact_outcome_envelope(
            selector,
            Some(subject),
            "SUBJECT_IDENTITY_MISMATCH",
            None,
        );
    };
    let mut actual = AgentRelationIdentityProjection {
        fq_name: subject.fq_name.clone(),
        kind: subject.kind.to_ascii_uppercase(),
        declaration_file: subject.location.file_path.clone(),
        declaration_start_offset: start_offset,
        containing_type: subject.containing_type.clone(),
    };
    if !actual.is_valid() || !expected.matches(&mut actual) {
        return impact_outcome_envelope(
            selector,
            Some(subject),
            "SUBJECT_IDENTITY_MISMATCH",
            None,
        );
    }
    subject.location.file_path.clone_from(&actual.declaration_file);
    subject.kind.clone_from(&actual.kind);
    let Some(kind) = impact_subject_kind(&actual.kind) else {
        return impact_outcome_envelope(
            selector,
            Some(subject),
            "UNSUPPORTED_SUBJECT_KIND",
            None,
        );
    };
    let mut envelope = execute_agent_steps(
        "agent/impact",
        args.runtime,
        vec![AgentPublicStep::new(
            "impact",
            "database/metrics",
            json!({
                "metric": "impact",
                "symbol": actual.fq_name,
                "depth": args.depth.get(),
                "limit": limit,
                "offset": offset,
                "subject": {
                    "fqName": actual.fq_name,
                    "declarationFile": actual.declaration_file,
                    "declarationStartOffset": actual.declaration_start_offset,
                    "kind": kind,
                }
            }),
            false,
        )],
    );
    if let Some(code) = impact_metrics_failure_code(&envelope)
        && matches!(
            code,
            "IMPACT_INDEX_IDENTITY_UNAVAILABLE" | "IMPACT_OVERLOAD_GRANULARITY_UNAVAILABLE"
        )
    {
        return impact_outcome_envelope(selector, Some(subject), "DEGRADED", Some(code));
    }
    wrap_impact_page_token(&mut envelope, &fingerprint);
    envelope
}

fn impact_subject_kind(kind: &str) -> Option<ImpactSubjectKind> {
    match kind {
        "CLASS" => Some(ImpactSubjectKind::Class),
        "INTERFACE" => Some(ImpactSubjectKind::Interface),
        "OBJECT" => Some(ImpactSubjectKind::Object),
        "FUNCTION" => Some(ImpactSubjectKind::Function),
        "PROPERTY" => Some(ImpactSubjectKind::Property),
        _ => None,
    }
}

fn impact_query_fingerprint(
    selector: &AgentExpectedRelationshipSelector,
    depth: u8,
    limit: u8,
) -> String {
    let proof = [
        selector.workspace_root.clone(),
        "impact".to_string(),
        selector.fq_name.clone(),
        selector.declaration_file.clone(),
        selector.declaration_start_offset.to_string(),
        selector.kind.clone().unwrap_or_default(),
        selector.containing_type.clone().unwrap_or_default(),
        depth.to_string(),
        limit.to_string(),
    ]
    .join("\n");
    crate::manifest::sha256_bytes(proof.as_bytes())[..24].to_string()
}

fn decode_impact_page_token(
    token: &AgentImpactPageToken,
    expected_fingerprint: &str,
) -> std::result::Result<usize, AgentError> {
    let fields = token.as_str().split('.').collect::<Vec<_>>();
    if fields.len() != 3
        || fields[0] != AGENT_IMPACT_TOKEN_VERSION
        || fields[1].len() != 24
        || !fields[1]
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        return Err(agent_error(
            "IMPACT_PAGE_TOKEN_INVALID",
            "The impact page token has an invalid version, fingerprint, or offset.",
        ));
    }
    if fields[1] != expected_fingerprint {
        return Err(agent_error(
            "IMPACT_PAGE_TOKEN_MISMATCH",
            "The impact page token was issued for a different workspace or query.",
        ));
    }
    let offset = fields[2].parse::<usize>().map_err(|_| {
        agent_error(
            "IMPACT_PAGE_TOKEN_INVALID",
            "The impact page token offset is invalid.",
        )
    })?;
    if offset > AGENT_IMPACT_MAX_OFFSET {
        return Err(agent_error(
            "IMPACT_PAGE_TOKEN_INVALID",
            "The impact page token offset exceeds the supported ceiling.",
        ));
    }
    Ok(offset)
}

fn impact_metrics_failure_code(envelope: &AgentEnvelope) -> Option<&str> {
    envelope
        .result
        .as_ref()?
        .get("steps")?
        .as_array()?
        .first()?
        .get("result")?
        .get("code")?
        .as_str()
}

fn wrap_impact_page_token(envelope: &mut AgentEnvelope, fingerprint: &str) {
    let Some(metrics) = envelope
        .result
        .as_mut()
        .and_then(Value::as_object_mut)
        .and_then(|command| command.get_mut("steps"))
        .and_then(Value::as_array_mut)
        .and_then(|steps| steps.first_mut())
        .and_then(|step| step.get_mut("result"))
        .and_then(Value::as_object_mut)
    else {
        return;
    };
    let next_offset = metrics.remove("nextOffset").and_then(|value| value.as_u64());
    if let Some(next_offset) = next_offset {
        metrics.insert(
            "nextPageToken".to_string(),
            Value::String(format!(
                "{AGENT_IMPACT_TOKEN_VERSION}.{fingerprint}.{next_offset}"
            )),
        );
    }
}

fn impact_outcome_envelope(
    selector: Value,
    verified_subject: Option<AgentRawImpactSubject>,
    outcome: &'static str,
    reason: Option<&str>,
) -> AgentEnvelope {
    result_envelope(
        "agent/impact".to_string(),
        drop_nulls(json!({
            "type": "KAST_AGENT_IMPACT_RESULT",
            "ok": true,
            "outcome": outcome,
            "selector": selector,
            "verifiedSubject": verified_subject,
            "reason": reason,
            "schemaVersion": SCHEMA_VERSION,
        })),
    )
}

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
    let (declaration_file, expected) = match normalize_relationship_selector(
        public_method,
        &runtime,
        &selector,
    ) {
        Ok(value) => value,
        Err(envelope) => return *envelope,
    };
    let fingerprint = traversal_query_fingerprint(
        relation,
        &expected,
        direction,
        Some(depth),
        limit,
    );
    let page_handle = match page_token.as_ref() {
        Some(token) => match decode_traversal_page_token(token, relation, &fingerprint) {
            Ok(handle) => Some(handle),
            Err(error) => return error_envelope(public_method.to_string(), None, error),
        },
        None => None,
    };
    let selector = drop_nulls(json!({
        "fqName": expected.fq_name,
        "declarationFile": declaration_file,
        "declarationStartOffset": expected.declaration_start_offset,
        "kind": expected.kind,
        "containingType": expected.containing_type,
    }));
    let request = json_rpc_request(
        "symbol/callers",
        drop_nulls(json!({
            "selector": selector,
            "direction": direction.to_ascii_lowercase(),
            "depth": depth,
            "maxResults": limit,
            "pageToken": page_handle,
        })),
    );
    let envelope = wrap_traversal_page_token(
        execute_request(AgentRequest {
            method: "symbol/callers".to_string(),
            request: request.clone(),
            runtime,
            full_response: true,
            operation: AgentOperation::ReadOnly,
        }),
        request,
        relation,
        &fingerprint,
    );
    project_typed_call_relationship_envelope(
        public_method.to_string(),
        envelope,
        expected,
        relation,
        record_relation,
        usize::from(limit),
        usize::from(depth),
        AgentResultView::from_parts(view.verbose, view.explain, &view.fields, view.count),
    )
}

fn execute_agent_implementations(args: AgentImplementationsArgs) -> AgentEnvelope {
    let (declaration_file, expected) = match normalize_relationship_selector(
        "agent/implementations",
        &args.runtime,
        &args.selector,
    ) {
        Ok(value) => value,
        Err(envelope) => return *envelope,
    };
    let fingerprint = traversal_query_fingerprint(
        "implementations",
        &expected,
        "",
        None,
        args.limit.get(),
    );
    let page_handle = match args.page_token.as_ref() {
        Some(token) => match decode_traversal_page_token(
            token,
            "implementations",
            &fingerprint,
        ) {
            Ok(handle) => Some(handle),
            Err(error) => return error_envelope("agent/implementations".to_string(), None, error),
        },
        None => None,
    };
    let selector = drop_nulls(json!({
        "fqName": expected.fq_name,
        "declarationFile": declaration_file,
        "declarationStartOffset": expected.declaration_start_offset,
        "kind": expected.kind,
        "containingType": expected.containing_type,
    }));
    let request = json_rpc_request(
        "symbol/implementations",
        drop_nulls(json!({
            "selector": selector,
            "maxResults": args.limit.get(),
            "pageToken": page_handle,
        })),
    );
    let envelope = wrap_traversal_page_token(
        execute_request(AgentRequest {
            method: "symbol/implementations".to_string(),
            request: request.clone(),
            runtime: args.runtime,
            full_response: true,
            operation: AgentOperation::ReadOnly,
        }),
        request,
        "implementations",
        &fingerprint,
    );
    project_typed_implementations_envelope(
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
    let direction = match args.direction {
        AgentHierarchyDirection::Supertypes => "SUPERTYPES",
        AgentHierarchyDirection::Subtypes => "SUBTYPES",
        AgentHierarchyDirection::Both => "BOTH",
    };
    let (declaration_file, expected) = match normalize_relationship_selector(
        "agent/hierarchy",
        &args.runtime,
        &args.selector,
    ) {
        Ok(value) => value,
        Err(envelope) => return *envelope,
    };
    let fingerprint = traversal_query_fingerprint(
        "hierarchy",
        &expected,
        direction,
        Some(args.depth.get()),
        args.limit.get(),
    );
    let page_handle = match args.page_token.as_ref() {
        Some(token) => match decode_traversal_page_token(token, "hierarchy", &fingerprint) {
            Ok(handle) => Some(handle),
            Err(error) => return error_envelope("agent/hierarchy".to_string(), None, error),
        },
        None => None,
    };
    let selector = drop_nulls(json!({
        "fqName": expected.fq_name,
        "declarationFile": declaration_file,
        "declarationStartOffset": expected.declaration_start_offset,
        "kind": expected.kind,
        "containingType": expected.containing_type,
    }));
    let request = json_rpc_request(
        "symbol/hierarchy",
        drop_nulls(json!({
            "selector": selector,
            "direction": direction,
            "depth": args.depth.get(),
            "maxResults": args.limit.get(),
            "pageToken": page_handle,
        })),
    );
    let envelope = wrap_traversal_page_token(
        execute_request(AgentRequest {
            method: "symbol/hierarchy".to_string(),
            request: request.clone(),
            runtime: args.runtime,
            full_response: true,
            operation: AgentOperation::ReadOnly,
        }),
        request,
        "hierarchy",
        &fingerprint,
    );
    project_typed_hierarchy_envelope(
        "agent/hierarchy".to_string(),
        envelope,
        expected,
        direction,
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
