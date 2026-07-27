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
        evidence: AgentRelationshipResultEvidenceInput,
    },
    #[serde(rename = "CURSOR_STALE")]
    CursorStale {
        selector: AgentRelationSelectorProjection,
        reason: AgentRelationCursorStaleReason,
        evidence: AgentRelationshipResultEvidenceInput,
    },
    #[serde(rename = "CURSOR_INVALID")]
    CursorInvalid {
        selector: AgentRelationSelectorProjection,
        reason: AgentRelationCursorInvalidReason,
        evidence: AgentRelationshipResultEvidenceInput,
    },
    #[serde(rename = "SELECTOR_HANDLE_REJECTED")]
    SelectorHandleRejected {
        reason: AgentSelectorHandleRejectionReason,
        recovery: AgentSelectorHandleRecovery,
    },
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentTypedTraversalPageInput {
    evidence: AgentRelationshipResultEvidenceInput,
    returned_count: usize,
    visited_candidate_count: usize,
    truncated: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    next_page_token: Option<String>,
}

impl AgentTypedTraversalPageInput {
    fn is_valid(&self, record_count: usize, result_limit: usize) -> bool {
        let cardinality = self.evidence.cardinality();
        self.evidence.is_valid_complete()
            && self.returned_count == record_count
            && record_count <= result_limit
            && self.visited_candidate_count >= record_count
            && self.visited_candidate_count <= 16_384
            && self.truncated == self.next_page_token.is_some()
            && cardinality.known_minimum() >= record_count
            && (!self.truncated || cardinality.known_minimum() > record_count)
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentTypedTraversalPageProjection {
    cardinality: AgentResultCardinality,
    returned_count: usize,
    visited_candidate_count: usize,
    truncated: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    next_page_token: Option<String>,
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
    Cancelled,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentImplementationsDegradedReason {
    ImplementationsUnavailable,
    CandidateBudgetReached,
    TraversalStateBudgetReached,
    Timeout,
    Cancelled,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentHierarchyDegradedReason {
    TypeHierarchyUnavailable,
    CandidateBudgetReached,
    TraversalStateBudgetReached,
    Timeout,
    Cancelled,
}
