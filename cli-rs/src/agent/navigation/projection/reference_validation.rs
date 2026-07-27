fn project_expected_reference_outcome(
    method: String,
    outcome: AgentReferencesResponseInput,
    provenance: &AgentReferenceRequestProvenance,
) -> AgentEnvelope {
    let evidence_is_valid = match &outcome {
        AgentReferencesResponseInput::SubjectNotFound { selector } => {
            provenance.matches_selector(selector)
        }
        AgentReferencesResponseInput::CursorStale {
            selector, evidence, ..
        }
        | AgentReferencesResponseInput::CursorInvalid {
            selector, evidence, ..
        } => provenance.matches_selector(selector) && evidence.is_valid_limited(),
        AgentReferencesResponseInput::SubjectIdentityMismatch { selector, actual } => {
            provenance.matches_selector(selector) && actual.is_valid()
        }
        AgentReferencesResponseInput::UnsupportedSubjectKind { selector, subject } => {
            let mut subject = subject.clone();
            provenance.matches_selector_and_subject(selector, &mut subject)
        }
        AgentReferencesResponseInput::Degraded {
            selector,
            subject,
            evidence,
            ..
        } => {
            let mut subject = subject.clone();
            provenance.matches_selector_and_subject(selector, &mut subject)
                && evidence.is_valid_limited()
        }
        AgentReferencesResponseInput::SelectorHandleRejected { reason, recovery } => {
            provenance.is_handle() && reason.recovery() == *recovery
        }
        AgentReferencesResponseInput::Available { .. } => false,
    };
    if !evidence_is_valid {
        return invalid_projection_envelope(
            method,
            "References contained invalid expected-outcome evidence.",
        );
    }
    let value = match outcome {
        AgentReferencesResponseInput::SubjectNotFound { selector } => json!({
            "type": "KAST_AGENT_REFERENCES_RESULT",
            "ok": true,
            "outcome": "SUBJECT_NOT_FOUND",
            "selector": selector,
            "schemaVersion": SCHEMA_VERSION,
        }),
        AgentReferencesResponseInput::SubjectIdentityMismatch { selector, actual } => json!({
            "type": "KAST_AGENT_REFERENCES_RESULT",
            "ok": true,
            "outcome": "SUBJECT_IDENTITY_MISMATCH",
            "selector": selector,
            "actual": actual,
            "schemaVersion": SCHEMA_VERSION,
        }),
        AgentReferencesResponseInput::UnsupportedSubjectKind { selector, subject } => json!({
            "type": "KAST_AGENT_REFERENCES_RESULT",
            "ok": true,
            "outcome": "UNSUPPORTED_SUBJECT_KIND",
            "selector": selector,
            "subject": subject,
            "schemaVersion": SCHEMA_VERSION,
        }),
        AgentReferencesResponseInput::Degraded {
            selector,
            subject,
            reason,
            evidence,
        } => json!({
            "type": "KAST_AGENT_REFERENCES_RESULT",
            "ok": true,
            "outcome": "DEGRADED",
            "selector": selector,
            "subject": subject,
            "reason": reason,
            "cardinality": evidence.cardinality(),
            "coverage": evidence.coverage(),
            "limitations": evidence.coverage().limitations(),
            "schemaVersion": SCHEMA_VERSION,
        }),
        AgentReferencesResponseInput::CursorStale {
            selector,
            reason,
            evidence,
        } => json!({
            "type": "KAST_AGENT_REFERENCES_RESULT",
            "ok": true,
            "outcome": "CURSOR_STALE",
            "selector": selector,
            "reason": reason,
            "cardinality": evidence.cardinality(),
            "coverage": evidence.coverage(),
            "limitations": evidence.coverage().limitations(),
            "schemaVersion": SCHEMA_VERSION,
        }),
        AgentReferencesResponseInput::CursorInvalid {
            selector,
            reason,
            evidence,
        } => json!({
            "type": "KAST_AGENT_REFERENCES_RESULT",
            "ok": true,
            "outcome": "CURSOR_INVALID",
            "selector": selector,
            "reason": reason,
            "cardinality": evidence.cardinality(),
            "coverage": evidence.coverage(),
            "limitations": evidence.coverage().limitations(),
            "schemaVersion": SCHEMA_VERSION,
        }),
        AgentReferencesResponseInput::SelectorHandleRejected { reason, recovery } => json!({
            "type": "KAST_AGENT_REFERENCES_RESULT",
            "ok": true,
            "outcome": "SELECTOR_HANDLE_REJECTED",
            "reason": reason,
            "recovery": recovery,
            "schemaVersion": SCHEMA_VERSION,
        }),
        AgentReferencesResponseInput::Available { .. } => {
            return invalid_projection_envelope(method, "Available references were projected twice.");
        }
    };
    projected_agent_envelope(method, true, value, None)
}

#[derive(Debug, Clone)]
struct AgentExpectedRelationshipSelector {
    workspace_root: String,
    fq_name: String,
    declaration_file: String,
    declaration_start_offset: u64,
    kind: Option<String>,
    containing_type: Option<String>,
}

impl AgentExpectedRelationshipSelector {
    fn matches(&self, actual: &mut AgentRelationIdentityProjection) -> bool {
        let declaration_file_matches =
            declaration_files_match(&self.declaration_file, &actual.declaration_file);
        if declaration_file_matches {
            actual.declaration_file.clone_from(&self.declaration_file);
        }
        self.fq_name == actual.fq_name
            && declaration_file_matches
            && self.declaration_start_offset == actual.declaration_start_offset
            && self.kind.as_ref().is_none_or(|kind| kind == &actual.kind)
            && self
                .containing_type
                .as_ref()
                .is_none_or(|containing_type| {
                    actual.containing_type.as_ref() == Some(containing_type)
                })
    }

    fn matches_selector(&self, selector: &AgentRelationSelectorProjection) -> bool {
        let declaration_file_matches =
            declaration_files_match(&self.declaration_file, &selector.declaration_file);
        self.fq_name == selector.fq_name
            && declaration_file_matches
            && self.declaration_start_offset == selector.declaration_start_offset
            && self.kind == selector.kind
            && self.containing_type == selector.containing_type
    }
}

fn declaration_files_match(left: &str, right: &str) -> bool {
    left == right
        || std::fs::canonicalize(left)
            .ok()
            .zip(std::fs::canonicalize(right).ok())
            .is_some_and(|(left, right)| left == right)
}

fn valid_relationship_location(location: &AgentLocationInput) -> bool {
    !location.file_path.trim().is_empty()
        && location.start_offset.is_some()
        && location
            .end_offset
            .is_none_or(|end| location.start_offset.is_some_and(|start| start <= end))
}

fn compact_relationship_error(method: String, mut envelope: AgentEnvelope) -> AgentEnvelope {
    envelope.method = method;
    compact_error_envelope(envelope)
}
