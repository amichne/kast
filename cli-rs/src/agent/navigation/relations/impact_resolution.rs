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
