const AGENT_RELATION_TOKEN_VERSION: &str = "krp1";
const AGENT_REFERENCE_RELATION: &str = "references";
const AGENT_REFERENCE_PAYLOAD_TAG: &str = "reference";
const AGENT_TRAVERSAL_PAYLOAD_TAG: &str = "traversal";
const AGENT_IMPACT_TOKEN_VERSION: &str = "kip1";
const AGENT_IMPACT_MAX_OFFSET: usize = 10_000;

struct AgentPreparedReusableSelector {
    selector: Option<Value>,
    selector_handle: Option<AgentSelectorHandle>,
    expected: Option<AgentExpectedRelationshipSelector>,
    workspace_root: String,
}

impl AgentPreparedReusableSelector {
    fn traversal_fingerprint(
        &self,
        relation: &str,
        direction: &str,
        depth: Option<u8>,
        limit: u8,
    ) -> String {
        match (&self.expected, &self.selector_handle) {
            (Some(expected), None) => {
                traversal_query_fingerprint(relation, expected, direction, depth, limit)
            }
            (None, Some(handle)) => selector_handle_traversal_query_fingerprint(
                &self.workspace_root,
                relation,
                handle,
                direction,
                depth,
                limit,
            ),
            _ => unreachable!("reusable selector preparation preserves exclusive choice"),
        }
    }

    fn impact_fingerprint(&self, depth: u8, limit: u8) -> String {
        match (&self.expected, &self.selector_handle) {
            (Some(expected), None) => impact_query_fingerprint(expected, depth, limit),
            (None, Some(handle)) => selector_handle_impact_query_fingerprint(
                &self.workspace_root,
                handle,
                depth,
                limit,
            ),
            _ => unreachable!("reusable selector preparation preserves exclusive choice"),
        }
    }
}

fn prepare_reusable_selector(
    public_method: &str,
    runtime: &AgentRuntimeArgs,
    selector: AgentReusableSymbolSelectorArgs,
) -> std::result::Result<AgentPreparedReusableSelector, Box<AgentEnvelope>> {
    let selector = selector.into_selector().map_err(|message| {
        Box::new(error_envelope(
            public_method.to_string(),
            None,
            agent_error("INVALID_SELECTOR_INPUT", message),
        ))
    })?;
    match selector {
        AgentReusableSymbolSelector::Explicit(selector) => {
            let (declaration_file, expected) =
                normalize_relationship_selector(public_method, runtime, &selector)?;
            let workspace_root = expected.workspace_root.clone();
            Ok(AgentPreparedReusableSelector {
                selector: Some(drop_nulls(json!({
                    "fqName": expected.fq_name,
                    "declarationFile": declaration_file,
                    "declarationStartOffset": expected.declaration_start_offset,
                    "kind": expected.kind,
                    "containingType": expected.containing_type,
                }))),
                selector_handle: None,
                expected: Some(expected),
                workspace_root,
            })
        }
        AgentReusableSymbolSelector::Handle(handle) => {
            let normalizer = AgentFilePathNormalizer::from_runtime(runtime).map_err(|error| {
                Box::new(error_envelope(public_method.to_string(), None, error))
            })?;
            Ok(AgentPreparedReusableSelector {
                selector: None,
                selector_handle: Some(handle),
                expected: None,
                workspace_root: normalizer.canonical_root.to_string_lossy().into_owned(),
            })
        }
    }
}

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

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all_fields = "camelCase")]
enum AgentSelectorIdentityResponseInput {
    #[serde(rename = "AVAILABLE")]
    Available {
        identity: AgentRelationIdentityProjection,
    },
    #[serde(rename = "SELECTOR_HANDLE_REJECTED")]
    SelectorHandleRejected {
        reason: AgentSelectorHandleRejectionReason,
        recovery: AgentSelectorHandleRecovery,
    },
}

struct AgentVerifiedImpactSubject {
    selector: Option<Value>,
    subject: Option<AgentRawImpactSubject>,
    identity: AgentRelationIdentityProjection,
    kind: ImpactSubjectKind,
}

fn execute_identity_first_impact(args: AgentImpactArgs) -> AgentEnvelope {
    let prepared =
        match prepare_reusable_selector("agent/impact", &args.runtime, args.selector) {
            Ok(prepared) => prepared,
            Err(envelope) => return *envelope,
        };
    let detailed = impact_result_view(&args.view).detailed();
    let limit = if detailed {
        args.limit.get()
    } else {
        args.limit.get().min(4)
    };
    let fingerprint = prepared.impact_fingerprint(args.depth.get(), limit);
    let offset = match args.page_token.as_ref() {
        Some(token) => match decode_impact_page_token(token, &fingerprint) {
            Ok(offset) => offset,
            Err(error) => return error_envelope("agent/impact".to_string(), None, error),
        },
        None => 0,
    };
    let verified = match (
        prepared.selector,
        prepared.selector_handle,
        prepared.expected,
    ) {
        (Some(selector), None, Some(expected)) => {
            match resolve_explicit_impact_subject(&args.runtime, selector, expected) {
                Ok(verified) => verified,
                Err(envelope) => return *envelope,
            }
        }
        (None, Some(handle), None) => {
            match resolve_handle_impact_subject(&args.runtime, handle) {
                Ok(verified) => verified,
                Err(envelope) => return *envelope,
            }
        }
        _ => unreachable!("reusable selector preparation preserves exclusive choice"),
    };
    let AgentVerifiedImpactSubject {
        selector,
        subject,
        identity,
        kind,
    } = verified;
    let mut envelope = execute_agent_steps(
        "agent/impact",
        args.runtime,
        vec![AgentPublicStep::new(
            "impact",
            "database/metrics",
            json!({
                "metric": "impact",
                "symbol": identity.fq_name,
                "depth": args.depth.get(),
                "limit": limit,
                "offset": offset,
                "subject": {
                    "fqName": identity.fq_name,
                    "declarationFile": identity.declaration_file,
                    "declarationStartOffset": identity.declaration_start_offset,
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
        return impact_outcome_envelope(selector, subject, "DEGRADED", Some(code));
    }
    wrap_impact_page_token(&mut envelope, &fingerprint);
    envelope
}

fn resolve_explicit_impact_subject(
    runtime: &AgentRuntimeArgs,
    selector: Value,
    expected: AgentExpectedRelationshipSelector,
) -> std::result::Result<AgentVerifiedImpactSubject, Box<AgentEnvelope>> {
    let declaration_file = expected.declaration_file.clone();
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
        runtime: runtime.clone(),
        full_response: true,
        operation: AgentOperation::ReadOnly,
    });
    if !resolved.ok {
        return Err(Box::new(error_envelope(
            "agent/impact".to_string(),
            Some(resolve_request),
            resolved.error.unwrap_or_else(|| {
                agent_error(
                    "IMPACT_SUBJECT_RESOLUTION_FAILED",
                    "Compiler position resolution failed without a typed error.",
                )
            }),
        )));
    }
    let Some(resolve_result) = resolved.result else {
        return Err(Box::new(invalid_projection_envelope(
            "agent/impact".to_string(),
            "Compiler position resolution returned no result.",
        )));
    };
    let parsed = match serde_json::from_value::<AgentRawImpactResolveResult>(resolve_result) {
        Ok(parsed) => parsed,
        Err(error) => {
            return Err(Box::new(invalid_projection_envelope(
                "agent/impact".to_string(),
                format!("Compiler position resolution violated its contract: {error}"),
            )));
        }
    };
    let Some(mut subject) = parsed.symbol else {
        return Err(Box::new(impact_outcome_envelope(
            Some(selector),
            None,
            "SUBJECT_NOT_FOUND",
            None,
        )));
    };
    let Some(start_offset) = subject.location.start_offset else {
        return Err(Box::new(impact_outcome_envelope(
            Some(selector),
            Some(subject),
            "SUBJECT_IDENTITY_MISMATCH",
            None,
        )));
    };
    let mut actual = AgentRelationIdentityProjection {
        fq_name: subject.fq_name.clone(),
        kind: subject.kind.to_ascii_uppercase(),
        declaration_file: subject.location.file_path.clone(),
        declaration_start_offset: start_offset,
        containing_type: subject.containing_type.clone(),
    };
    if !actual.is_valid() || !expected.matches(&mut actual) {
        return Err(Box::new(impact_outcome_envelope(
            Some(selector),
            Some(subject),
            "SUBJECT_IDENTITY_MISMATCH",
            None,
        )));
    }
    subject.location.file_path.clone_from(&actual.declaration_file);
    subject.kind.clone_from(&actual.kind);
    let Some(kind) = impact_subject_kind(&actual.kind) else {
        return Err(Box::new(impact_outcome_envelope(
            Some(selector),
            Some(subject),
            "UNSUPPORTED_SUBJECT_KIND",
            None,
        )));
    };
    Ok(AgentVerifiedImpactSubject {
        selector: Some(selector),
        subject: Some(subject),
        identity: actual,
        kind,
    })
}

fn resolve_handle_impact_subject(
    runtime: &AgentRuntimeArgs,
    handle: AgentSelectorHandle,
) -> std::result::Result<AgentVerifiedImpactSubject, Box<AgentEnvelope>> {
    let request = json_rpc_request(
        "selector/identity",
        json!({
            "selectorHandle": handle,
            "family": "IMPACT",
        }),
    );
    let response = execute_request(AgentRequest {
        method: "selector/identity".to_string(),
        request: request.clone(),
        runtime: runtime.clone(),
        full_response: true,
        operation: AgentOperation::ReadOnly,
    });
    if !response.ok {
        return Err(Box::new(error_envelope(
            "agent/impact".to_string(),
            Some(request),
            response.error.unwrap_or_else(|| {
                agent_error(
                    "IMPACT_SELECTOR_IDENTITY_FAILED",
                    "Selector identity authentication failed without a typed error.",
                )
            }),
        )));
    }
    let Some(result) = response.result else {
        return Err(Box::new(invalid_projection_envelope(
            "agent/impact".to_string(),
            "Selector identity authentication returned no result.",
        )));
    };
    let parsed = serde_json::from_value::<AgentSelectorIdentityResponseInput>(result).map_err(
        |error| {
            Box::new(invalid_projection_envelope(
                "agent/impact".to_string(),
                format!("Selector identity violated its closed response contract: {error}"),
            ))
        },
    )?;
    let mut identity = match parsed {
        AgentSelectorIdentityResponseInput::Available { identity } => identity,
        AgentSelectorIdentityResponseInput::SelectorHandleRejected { reason, recovery }
            if reason.recovery() == recovery =>
        {
            return Err(Box::new(impact_selector_handle_rejection_envelope(
                reason, recovery,
            )));
        }
        AgentSelectorIdentityResponseInput::SelectorHandleRejected { .. } => {
            return Err(Box::new(invalid_projection_envelope(
                "agent/impact".to_string(),
                "Selector handle rejection named an invalid recovery action.",
            )));
        }
    };
    if !identity.is_valid() {
        return Err(Box::new(invalid_projection_envelope(
            "agent/impact".to_string(),
            "Authenticated selector identity was incomplete.",
        )));
    }
    let normalizer = AgentFilePathNormalizer::from_runtime(runtime).map_err(|error| {
        Box::new(error_envelope(
            "agent/impact".to_string(),
            None,
            error,
        ))
    })?;
    identity.declaration_file = normalizer
        .normalize(&identity.declaration_file)
        .map_err(|error| {
            Box::new(invalid_projection_envelope(
                "agent/impact".to_string(),
                format!(
                    "Authenticated selector identity named an invalid declaration file: {}",
                    error.message
                ),
            ))
        })?
        .into_rpc_path();
    let Some(kind) = impact_subject_kind(&identity.kind) else {
        return Err(Box::new(invalid_projection_envelope(
            "agent/impact".to_string(),
            "Authenticated selector identity used a kind outside the impact family.",
        )));
    };
    Ok(AgentVerifiedImpactSubject {
        selector: None,
        subject: None,
        identity,
        kind,
    })
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

fn selector_handle_impact_query_fingerprint(
    workspace_root: &str,
    handle: &AgentSelectorHandle,
    depth: u8,
    limit: u8,
) -> String {
    let proof = [
        workspace_root.to_string(),
        "impact".to_string(),
        "selector-handle".to_string(),
        handle.as_str().to_string(),
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
    selector: Option<Value>,
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

fn impact_selector_handle_rejection_envelope(
    reason: AgentSelectorHandleRejectionReason,
    recovery: AgentSelectorHandleRecovery,
) -> AgentEnvelope {
    result_envelope(
        "agent/impact".to_string(),
        json!({
            "type": "KAST_AGENT_IMPACT_RESULT",
            "ok": true,
            "outcome": "SELECTOR_HANDLE_REJECTED",
            "reason": reason,
            "recovery": recovery,
            "schemaVersion": SCHEMA_VERSION,
        }),
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
    selector: AgentReusableSymbolSelectorArgs,
    depth: u8,
    limit: u8,
    page_token: Option<AgentRelationPageToken>,
    view: AgentRelationViewArgs,
) -> AgentEnvelope {
    let prepared = match prepare_reusable_selector(public_method, &runtime, selector) {
        Ok(value) => value,
        Err(envelope) => return *envelope,
    };
    let fingerprint = prepared.traversal_fingerprint(
        relation,
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
    let AgentPreparedReusableSelector {
        selector,
        selector_handle,
        expected,
        ..
    } = prepared;
    let request = json_rpc_request(
        "symbol/callers",
        drop_nulls(json!({
            "selector": selector,
            "selectorHandle": selector_handle,
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
    let prepared = match prepare_reusable_selector(
        "agent/implementations",
        &args.runtime,
        args.selector,
    ) {
        Ok(value) => value,
        Err(envelope) => return *envelope,
    };
    let fingerprint = prepared.traversal_fingerprint(
        "implementations",
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
    let AgentPreparedReusableSelector {
        selector,
        selector_handle,
        expected,
        ..
    } = prepared;
    let request = json_rpc_request(
        "symbol/implementations",
        drop_nulls(json!({
            "selector": selector,
            "selectorHandle": selector_handle,
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
    let prepared = match prepare_reusable_selector(
        "agent/hierarchy",
        &args.runtime,
        args.selector,
    ) {
        Ok(value) => value,
        Err(envelope) => return *envelope,
    };
    let fingerprint = prepared.traversal_fingerprint(
        "hierarchy",
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
    let AgentPreparedReusableSelector {
        selector,
        selector_handle,
        expected,
        ..
    } = prepared;
    let request = json_rpc_request(
        "symbol/hierarchy",
        drop_nulls(json!({
            "selector": selector,
            "selectorHandle": selector_handle,
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
