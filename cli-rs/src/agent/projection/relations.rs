#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRelationIdentityProjection {
    fq_name: String,
    kind: String,
    declaration_file: String,
    declaration_start_offset: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    containing_type: Option<String>,
}

impl AgentRelationIdentityProjection {
    fn is_valid(&self) -> bool {
        !self.fq_name.trim().is_empty()
            && !self.kind.trim().is_empty()
            && !self.declaration_file.trim().is_empty()
            && self
                .containing_type
                .as_ref()
                .is_none_or(|value| !value.trim().is_empty())
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRelationSelectorProjection {
    fq_name: String,
    declaration_file: String,
    declaration_start_offset: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    kind: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    containing_type: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all_fields = "camelCase")]
enum AgentReferencesResponseInput {
    #[serde(rename = "AVAILABLE")]
    Available {
        subject: AgentRelationIdentityProjection,
        references: Vec<AgentReferenceOccurrenceInput>,
        cardinality: AgentResultCardinality,
        #[serde(default)]
        page: Option<AgentReferencePageInput>,
    },
    #[serde(rename = "SUBJECT_NOT_FOUND")]
    SubjectNotFound {
        selector: AgentRelationSelectorProjection,
    },
    #[serde(rename = "SUBJECT_IDENTITY_MISMATCH")]
    SubjectIdentityMismatch {
        selector: AgentRelationSelectorProjection,
        actual: AgentRelationIdentityProjection,
    },
    #[serde(rename = "UNSUPPORTED_SUBJECT_KIND")]
    UnsupportedSubjectKind {
        selector: AgentRelationSelectorProjection,
        subject: AgentRelationIdentityProjection,
    },
    #[serde(rename = "DEGRADED")]
    Degraded {
        selector: AgentRelationSelectorProjection,
        subject: AgentRelationIdentityProjection,
        reason: AgentReferencesDegradedReason,
    },
    #[serde(rename = "CURSOR_STALE")]
    CursorStale {
        selector: AgentRelationSelectorProjection,
        reason: AgentRelationCursorStaleReason,
    },
    #[serde(rename = "CURSOR_INVALID")]
    CursorInvalid {
        selector: AgentRelationSelectorProjection,
        reason: AgentRelationCursorInvalidReason,
    },
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentReferencesDegradedReason {
    ReferencesUnavailable,
    IndexIdentityUnavailable,
    BoundSourceUnavailable,
    CandidateBudgetReached,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentRelationCursorStaleReason {
    GenerationChanged,
    Expired,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentRelationCursorInvalidReason {
    UnknownHandle,
    FamilyMismatch,
    QueryMismatch,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentReferenceOccurrenceInput {
    location: AgentLocationInput,
    containing_symbol: AgentContainingSymbolInput,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(tag = "type", rename_all_fields = "camelCase")]
enum AgentContainingSymbolInput {
    #[serde(rename = "KNOWN")]
    Known {
        symbol: AgentRelationIdentityProjection,
    },
    #[serde(rename = "TOP_LEVEL")]
    TopLevel,
    #[serde(rename = "UNAVAILABLE")]
    Unavailable {
        reason: AgentContainingSymbolUnavailableReason,
    },
}

impl AgentContainingSymbolInput {
    fn is_valid(&self) -> bool {
        match self {
            Self::Known { symbol } => symbol.is_valid(),
            Self::TopLevel | Self::Unavailable { .. } => true,
        }
    }
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentContainingSymbolUnavailableReason {
    NoSemanticOwner,
    UnsupportedOwnerKind,
    IdentityUnavailable,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentReferencePageInput {
    truncated: bool,
    #[serde(default)]
    next_page_token: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentReferenceRecordProjection {
    relation: &'static str,
    location: AgentLocationInput,
    containing_symbol: AgentContainingSymbolInput,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRelationPageProjection {
    cardinality: AgentResultCardinality,
    returned_count: usize,
    truncated: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    next_page_token: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentReferencesAvailableProjection {
    #[serde(rename = "type")]
    result_type: &'static str,
    ok: bool,
    outcome: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    subject: Option<AgentRelationIdentityProjection>,
    #[serde(skip_serializing_if = "Option::is_none")]
    relation: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    records: Option<Vec<AgentReferenceRecordProjection>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    page: Option<AgentRelationPageProjection>,
    #[serde(skip_serializing_if = "Option::is_none")]
    limitations: Option<Vec<String>>,
    schema_version: u32,
}

fn project_references_envelope(
    envelope: AgentEnvelope,
    view: AgentResultView<AgentRelationField>,
    result_limit: usize,
) -> AgentEnvelope {
    if !envelope.ok {
        return compact_error_envelope(envelope);
    }
    let method = envelope.method.clone();
    let Some(result) = envelope.result.clone() else {
        return invalid_projection_envelope(method, "References returned no result.");
    };
    let input = match serde_json::from_value::<AgentReferencesResponseInput>(result) {
        Ok(input) => input,
        Err(error) => {
            return invalid_projection_envelope(
                method,
                format!("References violated the closed response contract: {error}"),
            );
        }
    };
    match input {
        AgentReferencesResponseInput::Available {
            subject,
            references,
            cardinality,
            page,
        } => project_available_references(
            method,
            view,
            result_limit,
            subject,
            references,
            cardinality,
            page,
        ),
        other => project_expected_reference_outcome(method, other),
    }
}

#[allow(clippy::too_many_arguments)]
fn project_available_references(
    method: String,
    view: AgentResultView<AgentRelationField>,
    result_limit: usize,
    subject: AgentRelationIdentityProjection,
    references: Vec<AgentReferenceOccurrenceInput>,
    cardinality: AgentResultCardinality,
    page: Option<AgentReferencePageInput>,
) -> AgentEnvelope {
    if !subject.is_valid()
        || references.len() > result_limit
        || references.iter().any(|reference| {
            reference.location.file_path.trim().is_empty()
                || reference
                    .location
                    .end_offset
                    .is_some_and(|end| reference.location.start_offset.is_some_and(|start| start > end))
                || !reference.containing_symbol.is_valid()
        })
    {
        return invalid_projection_envelope(method, "References contained invalid or unbounded evidence.");
    }
    let returned_count = references.len();
    let truncated = page.as_ref().is_some_and(|page| page.truncated);
    let next_page_token = page.and_then(|page| page.next_page_token);
    if cardinality.known_minimum() < returned_count
        || (truncated && cardinality.known_minimum() < returned_count.saturating_add(1))
        || truncated != next_page_token.is_some()
    {
        return invalid_projection_envelope(method, "References contained inconsistent page evidence.");
    }
    let records = references
        .into_iter()
        .map(|reference| AgentReferenceRecordProjection {
            relation: "REFERENCE",
            location: reference.location.compact_relationship(),
            containing_symbol: reference.containing_symbol,
        })
        .collect::<Vec<_>>();
    let selected = |field| match &view {
        AgentResultView::Fields(fields) => fields.contains(&field),
        AgentResultView::Count => false,
        AgentResultView::Compact | AgentResultView::Verbose | AgentResultView::Explain => true,
    };
    let page = AgentRelationPageProjection {
        cardinality,
        returned_count,
        truncated,
        next_page_token,
    };
    projected_agent_envelope(
        method,
        true,
        AgentReferencesAvailableProjection {
            result_type: "KAST_AGENT_REFERENCES_RESULT",
            ok: true,
            outcome: "AVAILABLE",
            subject: selected(AgentRelationField::Subject).then_some(subject),
            relation: (selected(AgentRelationField::Relation)
                || matches!(view, AgentResultView::Count))
            .then_some("references"),
            records: selected(AgentRelationField::Records).then_some(records),
            page: (selected(AgentRelationField::Page) || matches!(view, AgentResultView::Count))
                .then_some(page),
            limitations: selected(AgentRelationField::Limitations).then_some(Vec::new()),
            schema_version: SCHEMA_VERSION,
        },
        None,
    )
}

fn project_expected_reference_outcome(
    method: String,
    outcome: AgentReferencesResponseInput,
) -> AgentEnvelope {
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
        AgentReferencesResponseInput::Degraded { selector, subject, reason } => json!({
            "type": "KAST_AGENT_REFERENCES_RESULT",
            "ok": true,
            "outcome": "DEGRADED",
            "selector": selector,
            "subject": subject,
            "reason": reason,
            "schemaVersion": SCHEMA_VERSION,
        }),
        AgentReferencesResponseInput::CursorStale { selector, reason } => json!({
            "type": "KAST_AGENT_REFERENCES_RESULT",
            "ok": true,
            "outcome": "CURSOR_STALE",
            "selector": selector,
            "reason": reason,
            "schemaVersion": SCHEMA_VERSION,
        }),
        AgentReferencesResponseInput::CursorInvalid { selector, reason } => json!({
            "type": "KAST_AGENT_REFERENCES_RESULT",
            "ok": true,
            "outcome": "CURSOR_INVALID",
            "selector": selector,
            "reason": reason,
            "schemaVersion": SCHEMA_VERSION,
        }),
        AgentReferencesResponseInput::Available { .. } => {
            return invalid_projection_envelope(method, "Available references were projected twice.");
        }
    };
    projected_agent_envelope(method, true, value, None)
}
