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
