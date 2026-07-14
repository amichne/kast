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

impl AgentRelationSelectorProjection {
    fn is_valid(&self) -> bool {
        !self.fq_name.trim().is_empty()
            && !self.declaration_file.trim().is_empty()
            && self
                .kind
                .as_ref()
                .is_none_or(|value| !value.trim().is_empty())
            && self
                .containing_type
                .as_ref()
                .is_none_or(|value| !value.trim().is_empty())
    }
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
    let evidence_is_valid = match &outcome {
        AgentReferencesResponseInput::SubjectNotFound { selector }
        | AgentReferencesResponseInput::CursorStale { selector, .. }
        | AgentReferencesResponseInput::CursorInvalid { selector, .. } => selector.is_valid(),
        AgentReferencesResponseInput::SubjectIdentityMismatch { selector, actual } => {
            selector.is_valid() && actual.is_valid()
        }
        AgentReferencesResponseInput::UnsupportedSubjectKind { selector, subject }
        | AgentReferencesResponseInput::Degraded {
            selector, subject, ..
        } => selector.is_valid() && subject.is_valid(),
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
        let declaration_file_matches = self.declaration_file == actual.declaration_file
            || std::fs::canonicalize(&actual.declaration_file)
                .ok()
                .is_some_and(|path| path.to_string_lossy() == self.declaration_file);
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
        self.fq_name == selector.fq_name
            && self.declaration_file == selector.declaration_file
            && self.declaration_start_offset == selector.declaration_start_offset
            && self.kind == selector.kind
            && self.containing_type == selector.containing_type
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentRawRelationshipSymbolInput {
    fq_name: String,
    kind: String,
    location: AgentLocationInput,
    #[serde(default)]
    containing_type: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentRawCallNodeInput {
    symbol: AgentRawRelationshipSymbolInput,
    #[serde(default)]
    call_site: Option<AgentLocationInput>,
    #[serde(default)]
    children: Vec<AgentRawCallNodeInput>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentRawCallStatsInput {
    total_edges: usize,
    max_depth_reached: usize,
    #[serde(default)]
    truncated_nodes: usize,
    #[serde(default)]
    timeout_reached: bool,
    #[serde(default)]
    max_total_calls_reached: bool,
    #[serde(default)]
    max_children_per_node_reached: bool,
}

impl AgentRawCallStatsInput {
    fn is_exhaustive(&self) -> bool {
        self.truncated_nodes == 0
            && !self.timeout_reached
            && !self.max_total_calls_reached
            && !self.max_children_per_node_reached
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentRawCallHierarchyInput {
    root: AgentRawCallNodeInput,
    stats: AgentRawCallStatsInput,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentRawImplementationsInput {
    declaration: AgentRawRelationshipSymbolInput,
    implementations: Vec<AgentRawRelationshipSymbolInput>,
    exhaustive: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentRawHierarchyNodeInput {
    symbol: AgentRawRelationshipSymbolInput,
    #[serde(default)]
    children: Vec<AgentRawHierarchyNodeInput>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentRawHierarchyStatsInput {
    total_nodes: usize,
    max_depth_reached: usize,
    truncated: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentRawHierarchyInput {
    root: AgentRawHierarchyNodeInput,
    stats: AgentRawHierarchyStatsInput,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentCallRelationshipRecordProjection {
    relation: &'static str,
    related_symbol: AgentRelationIdentityProjection,
    call_site: AgentLocationInput,
    depth: usize,
    containing_symbol: AgentContainingSymbolInput,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentImplementationRecordProjection {
    relation: &'static str,
    implementation: AgentRelationIdentityProjection,
    declaration_location: AgentLocationInput,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentHierarchyRecordProjection {
    relation: &'static str,
    related_symbol: AgentRelationIdentityProjection,
    declaration_location: AgentLocationInput,
    depth: usize,
}

#[allow(clippy::too_many_arguments)]
fn project_raw_call_relationship_envelope(
    method: String,
    envelope: AgentEnvelope,
    expected: AgentExpectedRelationshipSelector,
    relation: &'static str,
    record_relation: &'static str,
    direction: &'static str,
    result_limit: usize,
    max_depth: usize,
    view: AgentResultView<AgentRelationField>,
) -> AgentEnvelope {
    if !envelope.ok {
        return compact_relationship_error(method, envelope);
    }
    let Some(result) = envelope.result else {
        return invalid_projection_envelope(method, "Call hierarchy returned no result.");
    };
    let input = match serde_json::from_value::<AgentRawCallHierarchyInput>(result) {
        Ok(input) => input,
        Err(error) => {
            return invalid_projection_envelope(
                method,
                format!("Call hierarchy violated its closed response contract: {error}"),
            );
        }
    };
    let AgentRawCallHierarchyInput { root, stats } = input;
    if !stats.is_exhaustive() {
        return invalid_projection_envelope(
            method,
            "Call hierarchy was incomplete without resumable traversal state.",
        );
    }
    let AgentRawCallNodeInput {
        symbol,
        call_site: _,
        children,
    } = root;
    let (mut subject, _) = match project_raw_relationship_symbol(symbol) {
        Ok(subject) => subject,
        Err(message) => return invalid_projection_envelope(method, message),
    };
    if !expected.matches(&mut subject) {
        return invalid_projection_envelope(
            method,
            "Call hierarchy subject did not match the selected declaration anchor.",
        );
    }
    let mut records = Vec::new();
    let mut observed_max_depth = 0_usize;
    let mut frontier = std::collections::VecDeque::new();
    for child in children {
        frontier.push_back((child, 1_usize, subject.clone()));
    }
    while let Some((node, depth, parent)) = frontier.pop_front() {
        if records.len() == result_limit || depth > max_depth {
            return invalid_projection_envelope(
                method,
                "Call hierarchy exceeded the requested result or depth bound.",
            );
        }
        observed_max_depth = observed_max_depth.max(depth);
        let AgentRawCallNodeInput {
            symbol,
            call_site,
            children,
        } = node;
        let (related_symbol, _) = match project_raw_relationship_symbol(symbol) {
            Ok(symbol) => symbol,
            Err(message) => return invalid_projection_envelope(method, message),
        };
        let Some(call_site) = call_site.filter(valid_relationship_location) else {
            return invalid_projection_envelope(
                method,
                "Call hierarchy record omitted its valid call-site location.",
            );
        };
        let containing_identity = if direction == "INCOMING" {
            related_symbol.clone()
        } else {
            parent
        };
        for child in children {
            frontier.push_back((child, depth + 1, related_symbol.clone()));
        }
        records.push(AgentCallRelationshipRecordProjection {
            relation: record_relation,
            related_symbol,
            call_site: call_site.compact_relationship(),
            depth,
            containing_symbol: AgentContainingSymbolInput::Known {
                symbol: containing_identity,
            },
        });
    }
    if stats.total_edges != records.len() || stats.max_depth_reached != observed_max_depth {
        return invalid_projection_envelope(
            method,
            "Call hierarchy statistics did not match its complete bounded evidence.",
        );
    }
    project_available_relationship(
        method,
        "KAST_AGENT_CALL_RELATIONSHIP_RESULT",
        subject,
        relation,
        records,
        view,
    )
}

fn project_raw_implementations_envelope(
    method: String,
    envelope: AgentEnvelope,
    expected: AgentExpectedRelationshipSelector,
    result_limit: usize,
    view: AgentResultView<AgentRelationField>,
) -> AgentEnvelope {
    if !envelope.ok {
        return compact_relationship_error(method, envelope);
    }
    let Some(result) = envelope.result else {
        return invalid_projection_envelope(method, "Implementations returned no result.");
    };
    let input = match serde_json::from_value::<AgentRawImplementationsInput>(result) {
        Ok(input) => input,
        Err(error) => {
            return invalid_projection_envelope(
                method,
                format!("Implementations violated its closed response contract: {error}"),
            );
        }
    };
    if !input.exhaustive || input.implementations.len() > result_limit {
        return invalid_projection_envelope(
            method,
            "Implementations were incomplete or exceeded the requested result bound.",
        );
    }
    let (mut subject, _) = match project_raw_relationship_symbol(input.declaration) {
        Ok(subject) => subject,
        Err(message) => return invalid_projection_envelope(method, message),
    };
    if !expected.matches(&mut subject) {
        return invalid_projection_envelope(
            method,
            "Implementation subject did not match the selected declaration anchor.",
        );
    }
    let mut records = Vec::with_capacity(input.implementations.len());
    for implementation in input.implementations {
        let (implementation, declaration_location) =
            match project_raw_relationship_symbol(implementation) {
                Ok(value) => value,
                Err(message) => return invalid_projection_envelope(method, message),
            };
        records.push(AgentImplementationRecordProjection {
            relation: "IMPLEMENTATION",
            implementation,
            declaration_location,
        });
    }
    project_available_relationship(
        method,
        "KAST_AGENT_IMPLEMENTATIONS_RESULT",
        subject,
        "implementations",
        records,
        view,
    )
}

fn project_raw_hierarchy_envelope(
    method: String,
    envelope: AgentEnvelope,
    expected: AgentExpectedRelationshipSelector,
    record_relation: &'static str,
    result_limit: usize,
    max_depth: usize,
    view: AgentResultView<AgentRelationField>,
) -> AgentEnvelope {
    if !envelope.ok {
        return compact_relationship_error(method, envelope);
    }
    let Some(result) = envelope.result else {
        return invalid_projection_envelope(method, "Type hierarchy returned no result.");
    };
    let input = match serde_json::from_value::<AgentRawHierarchyInput>(result) {
        Ok(input) => input,
        Err(error) => {
            return invalid_projection_envelope(
                method,
                format!("Type hierarchy violated its closed response contract: {error}"),
            );
        }
    };
    if input.stats.truncated {
        return invalid_projection_envelope(
            method,
            "Type hierarchy was incomplete without resumable traversal state.",
        );
    }
    let AgentRawHierarchyNodeInput { symbol, children } = input.root;
    let (mut subject, _) = match project_raw_relationship_symbol(symbol) {
        Ok(subject) => subject,
        Err(message) => return invalid_projection_envelope(method, message),
    };
    if !expected.matches(&mut subject) {
        return invalid_projection_envelope(
            method,
            "Type hierarchy subject did not match the selected declaration anchor.",
        );
    }
    let mut records = Vec::new();
    let mut observed_max_depth = 0_usize;
    let mut frontier = std::collections::VecDeque::new();
    for child in children {
        frontier.push_back((child, 1_usize));
    }
    while let Some((node, depth)) = frontier.pop_front() {
        if records.len() == result_limit || depth > max_depth {
            return invalid_projection_envelope(
                method,
                "Type hierarchy exceeded the requested result or depth bound.",
            );
        }
        observed_max_depth = observed_max_depth.max(depth);
        let AgentRawHierarchyNodeInput { symbol, children } = node;
        let (related_symbol, declaration_location) =
            match project_raw_relationship_symbol(symbol) {
                Ok(value) => value,
                Err(message) => return invalid_projection_envelope(method, message),
            };
        records.push(AgentHierarchyRecordProjection {
            relation: record_relation,
            related_symbol,
            declaration_location,
            depth,
        });
        for child in children {
            frontier.push_back((child, depth + 1));
        }
    }
    if input.stats.total_nodes != records.len().saturating_add(1)
        || input.stats.max_depth_reached != observed_max_depth
    {
        return invalid_projection_envelope(
            method,
            "Type hierarchy statistics did not match its complete bounded evidence.",
        );
    }
    project_available_relationship(
        method,
        "KAST_AGENT_HIERARCHY_RESULT",
        subject,
        "hierarchy",
        records,
        view,
    )
}

fn project_raw_relationship_symbol(
    symbol: AgentRawRelationshipSymbolInput,
) -> std::result::Result<(AgentRelationIdentityProjection, AgentLocationInput), &'static str> {
    if !valid_relationship_location(&symbol.location) {
        return Err("Relationship symbol omitted its valid declaration location.");
    }
    let declaration_start_offset = symbol
        .location
        .start_offset
        .ok_or("Relationship symbol omitted its declaration offset.")?;
    let identity = AgentRelationIdentityProjection {
        fq_name: symbol.fq_name,
        kind: symbol.kind,
        declaration_file: symbol.location.file_path.clone(),
        declaration_start_offset,
        containing_type: symbol.containing_type,
    };
    if !identity.is_valid() {
        return Err("Relationship symbol contained an invalid identity.");
    }
    Ok((identity, symbol.location.compact_relationship()))
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

fn project_available_relationship<Record: Serialize>(
    method: String,
    result_type: &'static str,
    subject: AgentRelationIdentityProjection,
    relation: &'static str,
    records: Vec<Record>,
    view: AgentResultView<AgentRelationField>,
) -> AgentEnvelope {
    let returned_count = records.len();
    let selected = |field| match &view {
        AgentResultView::Fields(fields) => fields.contains(&field),
        AgentResultView::Count => false,
        AgentResultView::Compact | AgentResultView::Verbose | AgentResultView::Explain => true,
    };
    let mut result = serde_json::Map::from_iter([
        ("type".to_string(), Value::String(result_type.to_string())),
        ("ok".to_string(), Value::Bool(true)),
        ("outcome".to_string(), Value::String("AVAILABLE".to_string())),
        ("schemaVersion".to_string(), Value::from(SCHEMA_VERSION)),
    ]);
    if selected(AgentRelationField::Subject) {
        result.insert(
            "subject".to_string(),
            serde_json::to_value(subject).unwrap_or(Value::Null),
        );
    }
    if selected(AgentRelationField::Relation) || matches!(view, AgentResultView::Count) {
        result.insert("relation".to_string(), Value::String(relation.to_string()));
    }
    if selected(AgentRelationField::Records) {
        result.insert(
            "records".to_string(),
            serde_json::to_value(records).unwrap_or(Value::Null),
        );
    }
    if selected(AgentRelationField::Page) || matches!(view, AgentResultView::Count) {
        result.insert(
            "page".to_string(),
            json!({
                "cardinality": {
                    "type": "EXACT",
                    "totalCount": returned_count,
                },
                "returnedCount": returned_count,
                "truncated": false,
            }),
        );
    }
    if selected(AgentRelationField::Limitations) {
        result.insert("limitations".to_string(), Value::Array(Vec::new()));
    }
    projected_agent_envelope(method, true, Value::Object(result), None)
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all_fields = "camelCase")]
enum AgentTypedTraversalResponseInput<Record, Reason> {
    #[serde(rename = "AVAILABLE")]
    Available {
        subject: AgentRelationIdentityProjection,
        records: Vec<Record>,
        page: AgentTypedTraversalPageInput,
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
        reason: Reason,
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

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentTypedTraversalPageInput {
    cardinality: AgentResultCardinality,
    returned_count: usize,
    visited_candidate_count: usize,
    truncated: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    next_page_token: Option<String>,
}

impl AgentTypedTraversalPageInput {
    fn is_valid(&self, record_count: usize, result_limit: usize) -> bool {
        self.returned_count == record_count
            && record_count <= result_limit
            && self.visited_candidate_count >= record_count
            && self.visited_candidate_count <= 16_384
            && self.truncated == self.next_page_token.is_some()
            && self.cardinality.known_minimum() >= record_count
            && (!self.truncated || self.cardinality.known_minimum() > record_count)
    }
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentTypedCallRecordInput {
    relation: String,
    related_symbol: AgentRelationIdentityProjection,
    call_site: AgentLocationInput,
    depth: usize,
    containing_symbol: AgentContainingSymbolInput,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentTypedImplementationRecordInput {
    relation: String,
    implementation: AgentRelationIdentityProjection,
    declaration_location: AgentLocationInput,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentTypedHierarchyRecordInput {
    relation: String,
    related_symbol: AgentRelationIdentityProjection,
    declaration_location: AgentLocationInput,
    depth: usize,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentCallDegradedReason {
    CallHierarchyUnavailable,
    CandidateBudgetReached,
    TraversalStateBudgetReached,
    Timeout,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentImplementationsDegradedReason {
    ImplementationsUnavailable,
    CandidateBudgetReached,
    TraversalStateBudgetReached,
    Timeout,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentHierarchyDegradedReason {
    TypeHierarchyUnavailable,
    CandidateBudgetReached,
    TraversalStateBudgetReached,
    Timeout,
}

#[allow(clippy::too_many_arguments)]
fn project_typed_call_relationship_envelope(
    method: String,
    envelope: AgentEnvelope,
    expected: AgentExpectedRelationshipSelector,
    relation: &'static str,
    record_relation: &'static str,
    result_limit: usize,
    max_depth: usize,
    view: AgentResultView<AgentRelationField>,
) -> AgentEnvelope {
    project_typed_relationship_envelope::<AgentTypedCallRecordInput, AgentCallDegradedReason>(
        method,
        envelope,
        expected,
        "KAST_AGENT_CALL_RELATIONSHIP_RESULT",
        relation,
        result_limit,
        view,
        |kind| kind == "FUNCTION",
        |record| {
            record.relation == record_relation
                && record.related_symbol.is_valid()
                && valid_relationship_location(&record.call_site)
                && (1..=max_depth).contains(&record.depth)
                && record.containing_symbol.is_valid()
        },
    )
}

fn project_typed_implementations_envelope(
    method: String,
    envelope: AgentEnvelope,
    expected: AgentExpectedRelationshipSelector,
    result_limit: usize,
    view: AgentResultView<AgentRelationField>,
) -> AgentEnvelope {
    project_typed_relationship_envelope::<
        AgentTypedImplementationRecordInput,
        AgentImplementationsDegradedReason,
    >(
        method,
        envelope,
        expected,
        "KAST_AGENT_IMPLEMENTATIONS_RESULT",
        "implementations",
        result_limit,
        view,
        |kind| matches!(kind, "CLASS" | "INTERFACE"),
        |record| {
            record.relation == "IMPLEMENTATION"
                && record.implementation.is_valid()
                && valid_relationship_location(&record.declaration_location)
                && record.implementation.declaration_file
                    == record.declaration_location.file_path
                && Some(record.implementation.declaration_start_offset)
                    == record.declaration_location.start_offset
        },
    )
}

#[allow(clippy::too_many_arguments)]
fn project_typed_hierarchy_envelope(
    method: String,
    envelope: AgentEnvelope,
    expected: AgentExpectedRelationshipSelector,
    direction: &str,
    result_limit: usize,
    max_depth: usize,
    view: AgentResultView<AgentRelationField>,
) -> AgentEnvelope {
    project_typed_relationship_envelope::<
        AgentTypedHierarchyRecordInput,
        AgentHierarchyDegradedReason,
    >(
        method,
        envelope,
        expected,
        "KAST_AGENT_HIERARCHY_RESULT",
        "hierarchy",
        result_limit,
        view,
        |kind| matches!(kind, "CLASS" | "INTERFACE" | "OBJECT"),
        |record| {
            let relation_matches = match direction {
                "SUPERTYPES" => record.relation == "SUPERTYPE",
                "SUBTYPES" => record.relation == "SUBTYPE",
                "BOTH" => matches!(record.relation.as_str(), "SUPERTYPE" | "SUBTYPE"),
                _ => false,
            };
            relation_matches
                && record.related_symbol.is_valid()
                && valid_relationship_location(&record.declaration_location)
                && record.related_symbol.declaration_file
                    == record.declaration_location.file_path
                && Some(record.related_symbol.declaration_start_offset)
                    == record.declaration_location.start_offset
                && (1..=max_depth).contains(&record.depth)
        },
    )
}

#[allow(clippy::too_many_arguments)]
fn project_typed_relationship_envelope<Record, Reason>(
    method: String,
    envelope: AgentEnvelope,
    expected: AgentExpectedRelationshipSelector,
    result_type: &'static str,
    relation: &'static str,
    result_limit: usize,
    view: AgentResultView<AgentRelationField>,
    admitted_kind: impl Fn(&str) -> bool + Copy,
    record_is_valid: impl Fn(&Record) -> bool,
) -> AgentEnvelope
where
    Record: for<'de> Deserialize<'de> + Serialize,
    Reason: for<'de> Deserialize<'de> + Serialize,
{
    if !envelope.ok {
        return compact_relationship_error(method, envelope);
    }
    let Some(result) = envelope.result else {
        return invalid_projection_envelope(method, "Relationship endpoint returned no result.");
    };
    let input = match serde_json::from_value::<AgentTypedTraversalResponseInput<Record, Reason>>(
        result,
    ) {
        Ok(input) => input,
        Err(error) => {
            return invalid_projection_envelope(
                method,
                format!("Relationship endpoint violated its closed response contract: {error}"),
            );
        }
    };
    match input {
        AgentTypedTraversalResponseInput::Available {
            mut subject,
            records,
            page,
        } => {
            if !subject.is_valid()
                || !expected.matches(&mut subject)
                || !admitted_kind(&subject.kind)
                || !page.is_valid(records.len(), result_limit)
                || records.iter().any(|record| !record_is_valid(record))
            {
                return invalid_projection_envelope(
                    method,
                    "Relationship endpoint contained invalid or unbounded available evidence.",
                );
            }
            project_typed_available_relationship(
                method,
                result_type,
                subject,
                relation,
                records,
                page,
                view,
            )
        }
        other => project_typed_expected_relationship_outcome(
            method,
            result_type,
            expected,
            other,
            admitted_kind,
        ),
    }
}

fn project_typed_available_relationship<Record: Serialize>(
    method: String,
    result_type: &'static str,
    subject: AgentRelationIdentityProjection,
    relation: &'static str,
    records: Vec<Record>,
    page: AgentTypedTraversalPageInput,
    view: AgentResultView<AgentRelationField>,
) -> AgentEnvelope {
    let selected = |field| match &view {
        AgentResultView::Fields(fields) => fields.contains(&field),
        AgentResultView::Count => false,
        AgentResultView::Compact | AgentResultView::Verbose | AgentResultView::Explain => true,
    };
    let mut result = serde_json::Map::from_iter([
        ("type".to_string(), Value::String(result_type.to_string())),
        ("ok".to_string(), Value::Bool(true)),
        ("outcome".to_string(), Value::String("AVAILABLE".to_string())),
        ("schemaVersion".to_string(), Value::from(SCHEMA_VERSION)),
    ]);
    if selected(AgentRelationField::Subject) {
        result.insert(
            "subject".to_string(),
            serde_json::to_value(subject).unwrap_or(Value::Null),
        );
    }
    if selected(AgentRelationField::Relation) || matches!(view, AgentResultView::Count) {
        result.insert("relation".to_string(), Value::String(relation.to_string()));
    }
    if selected(AgentRelationField::Records) {
        result.insert(
            "records".to_string(),
            serde_json::to_value(records).unwrap_or(Value::Null),
        );
    }
    if selected(AgentRelationField::Page) || matches!(view, AgentResultView::Count) {
        result.insert(
            "page".to_string(),
            serde_json::to_value(page).unwrap_or(Value::Null),
        );
    }
    if selected(AgentRelationField::Limitations) {
        result.insert("limitations".to_string(), Value::Array(Vec::new()));
    }
    projected_agent_envelope(method, true, Value::Object(result), None)
}

fn project_typed_expected_relationship_outcome<Record, Reason>(
    method: String,
    result_type: &'static str,
    expected: AgentExpectedRelationshipSelector,
    outcome: AgentTypedTraversalResponseInput<Record, Reason>,
    admitted_kind: impl Fn(&str) -> bool,
) -> AgentEnvelope
where
    Reason: Serialize,
{
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
        } => {
            if !selector.is_valid()
                || !expected.matches_selector(&selector)
                || !subject.is_valid()
                || !expected.matches(&mut subject)
                || !admitted_kind(&subject.kind)
            {
                return invalid_projection_envelope(
                    method,
                    "Degraded relationship subject did not match the selector and admitted kind matrix.",
                );
            }
            json!({
                "type": result_type,
                "ok": true,
                "outcome": "DEGRADED",
                "selector": selector,
                "subject": subject,
                "reason": reason,
                "schemaVersion": SCHEMA_VERSION,
            })
        }
        AgentTypedTraversalResponseInput::CursorStale { selector, reason }
            if selector.is_valid() && expected.matches_selector(&selector) =>
        {
            json!({
                "type": result_type,
                "ok": true,
                "outcome": "CURSOR_STALE",
                "selector": selector,
                "reason": reason,
                "schemaVersion": SCHEMA_VERSION,
            })
        }
        AgentTypedTraversalResponseInput::CursorInvalid { selector, reason }
            if selector.is_valid() && expected.matches_selector(&selector) =>
        {
            json!({
                "type": result_type,
                "ok": true,
                "outcome": "CURSOR_INVALID",
                "selector": selector,
                "reason": reason,
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
