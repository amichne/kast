#[allow(clippy::too_many_arguments)]
fn project_typed_expected_relationship_outcome<Record, Reason>(
    method: String,
    result_type: &'static str,
    expected: Option<AgentExpectedRelationshipSelector>,
    outcome: AgentTypedTraversalResponseInput<Record, Reason>,
    result_limit: usize,
    include_records: bool,
    admitted_kind: impl Fn(&str) -> bool,
    record_is_valid: impl Fn(&Record) -> bool,
) -> AgentEnvelope
where
    Record: Serialize,
    Reason: Serialize,
{
    let outcome = match outcome {
        AgentTypedTraversalResponseInput::SelectorHandleRejected { reason, recovery }
            if expected.is_none() && reason.recovery() == recovery =>
        {
            return projected_agent_envelope(
                method,
                true,
                json!({
                    "type": result_type,
                    "ok": true,
                    "outcome": "SELECTOR_HANDLE_REJECTED",
                    "reason": reason,
                    "recovery": recovery,
                    "schemaVersion": SCHEMA_VERSION,
                }),
                None,
            );
        }
        AgentTypedTraversalResponseInput::SelectorHandleRejected { .. } => {
            return invalid_projection_envelope(
                method,
                "Selector handle rejection did not match a handle request and its required recovery.",
            );
        }
        AgentTypedTraversalResponseInput::Degraded {
            selector,
            mut subject,
            reason,
            evidence,
            records,
            page,
        } if expected.is_none() => {
            if !selector.is_valid()
                || !subject.is_valid()
                || !selector.matches_identity(&mut subject)
                || !admitted_kind(&subject.kind)
                || !evidence.is_valid_limited()
                || evidence.cardinality().known_minimum() < records.len()
                || records.len() > result_limit
                || records.iter().any(|record| !record_is_valid(record))
                || page.is_some()
            {
                return invalid_projection_envelope(
                    method,
                    "Handle-backed degraded relationship contained inconsistent subject or limitation evidence.",
                );
            }
            let mut value = json!({
                "type": result_type,
                "ok": true,
                "outcome": "DEGRADED",
                "selector": selector,
                "subject": subject,
                "reason": reason,
                "cardinality": evidence.cardinality(),
                "coverage": evidence.coverage(),
                "limitations": evidence.coverage().limitations(),
                "schemaVersion": SCHEMA_VERSION,
            });
            if include_records {
                value["records"] = json!(records);
            }
            return projected_agent_envelope(method, true, value, None);
        }
        AgentTypedTraversalResponseInput::CursorStale {
            selector,
            reason,
            evidence,
        } if expected.is_none() => {
            if !selector.is_valid() || !evidence.is_valid_limited() {
                return invalid_projection_envelope(
                    method,
                    "Handle-backed stale relationship contained invalid selector or limitation evidence.",
                );
            }
            return projected_agent_envelope(
                method,
                true,
                json!({
                    "type": result_type,
                    "ok": true,
                    "outcome": "CURSOR_STALE",
                    "selector": selector,
                    "reason": reason,
                    "cardinality": evidence.cardinality(),
                    "coverage": evidence.coverage(),
                    "limitations": evidence.coverage().limitations(),
                    "schemaVersion": SCHEMA_VERSION,
                }),
                None,
            );
        }
        AgentTypedTraversalResponseInput::CursorInvalid {
            selector,
            reason,
            evidence,
        } if expected.is_none() => {
            if !selector.is_valid() || !evidence.is_valid_limited() {
                return invalid_projection_envelope(
                    method,
                    "Handle-backed invalid relationship contained invalid selector or limitation evidence.",
                );
            }
            return projected_agent_envelope(
                method,
                true,
                json!({
                    "type": result_type,
                    "ok": true,
                    "outcome": "CURSOR_INVALID",
                    "selector": selector,
                    "reason": reason,
                    "cardinality": evidence.cardinality(),
                    "coverage": evidence.coverage(),
                    "limitations": evidence.coverage().limitations(),
                    "schemaVersion": SCHEMA_VERSION,
                }),
                None,
            );
        }
        other => other,
    };
    let Some(expected) = expected else {
        return invalid_projection_envelope(
            method,
            "Handle-backed relationship returned an outcome without authenticated subject evidence.",
        );
    };
    let value = match outcome {
        AgentTypedTraversalResponseInput::SubjectNotFound { selector }
            if selector.is_valid() && expected.matches_selector(&selector) =>
        {
            json!({
                "type": result_type,
                "ok": true,
                "outcome": "SUBJECT_NOT_FOUND",
                "selector": selector,
                "schemaVersion": SCHEMA_VERSION,
            })
        }
        AgentTypedTraversalResponseInput::SubjectIdentityMismatch {
            selector,
            mut actual,
        } => {
            if !selector.is_valid()
                || !expected.matches_selector(&selector)
                || !actual.is_valid()
                || expected.matches(&mut actual)
            {
                return invalid_projection_envelope(
                    method,
                    "Relationship identity mismatch did not prove a different anchored identity.",
                );
            }
            json!({
                "type": result_type,
                "ok": true,
                "outcome": "SUBJECT_IDENTITY_MISMATCH",
                "selector": selector,
                "actual": actual,
                "schemaVersion": SCHEMA_VERSION,
            })
        }
        AgentTypedTraversalResponseInput::UnsupportedSubjectKind {
            selector,
            mut subject,
        } => {
            if !selector.is_valid()
                || !expected.matches_selector(&selector)
                || !subject.is_valid()
                || !expected.matches(&mut subject)
                || admitted_kind(&subject.kind)
            {
                return invalid_projection_envelope(
                    method,
                    "Unsupported relationship subject did not match the selector and rejected kind matrix.",
                );
            }
            json!({
                "type": result_type,
                "ok": true,
                "outcome": "UNSUPPORTED_SUBJECT_KIND",
                "selector": selector,
                "subject": subject,
                "schemaVersion": SCHEMA_VERSION,
            })
        }
        AgentTypedTraversalResponseInput::Degraded {
            selector,
            mut subject,
            reason,
            evidence,
            records,
            page,
        } => {
            if !selector.is_valid()
                || !expected.matches_selector(&selector)
                || !subject.is_valid()
                || !expected.matches(&mut subject)
                || !admitted_kind(&subject.kind)
                || !evidence.is_valid_limited()
                || evidence.cardinality().known_minimum() < records.len()
                || records.len() > result_limit
                || records.iter().any(|record| !record_is_valid(record))
                || page.is_some()
            {
                return invalid_projection_envelope(
                    method,
                    "Degraded relationship subject did not match the selector and admitted kind matrix.",
                );
            }
            let mut value = json!({
                "type": result_type,
                "ok": true,
                "outcome": "DEGRADED",
                "selector": selector,
                "subject": subject,
                "reason": reason,
                "cardinality": evidence.cardinality(),
                "coverage": evidence.coverage(),
                "limitations": evidence.coverage().limitations(),
                "schemaVersion": SCHEMA_VERSION,
            });
            if include_records {
                value["records"] = json!(records);
            }
            value
        }
        AgentTypedTraversalResponseInput::CursorStale {
            selector,
            reason,
            evidence,
        } if selector.is_valid()
            && expected.matches_selector(&selector)
            && evidence.is_valid_limited() =>
        {
            json!({
                "type": result_type,
                "ok": true,
                "outcome": "CURSOR_STALE",
                "selector": selector,
                "reason": reason,
                "cardinality": evidence.cardinality(),
                "coverage": evidence.coverage(),
                "limitations": evidence.coverage().limitations(),
                "schemaVersion": SCHEMA_VERSION,
            })
        }
        AgentTypedTraversalResponseInput::CursorInvalid {
            selector,
            reason,
            evidence,
        } if selector.is_valid()
            && expected.matches_selector(&selector)
            && evidence.is_valid_limited() =>
        {
            json!({
                "type": result_type,
                "ok": true,
                "outcome": "CURSOR_INVALID",
                "selector": selector,
                "reason": reason,
                "cardinality": evidence.cardinality(),
                "coverage": evidence.coverage(),
                "limitations": evidence.coverage().limitations(),
                "schemaVersion": SCHEMA_VERSION,
            })
        }
        AgentTypedTraversalResponseInput::Available { .. } => {
            return invalid_projection_envelope(
                method,
                "Available relationship evidence was projected twice.",
            );
        }
        _ => {
            return invalid_projection_envelope(
                method,
                "Relationship expected outcome contained invalid identity evidence.",
            );
        }
    };
    projected_agent_envelope(method, true, value, None)
}
