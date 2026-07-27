#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all_fields = "camelCase")]
enum AgentReferencesResponseInput {
    #[serde(rename = "AVAILABLE")]
    Available {
        subject: AgentRelationIdentityProjection,
        references: Vec<AgentReferenceOccurrenceInput>,
        evidence: AgentRelationshipResultEvidenceInput,
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

#[derive(Debug, Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentReferencesDegradedReason {
    ReferencesUnavailable,
    IndexIdentityUnavailable,
    BoundSourceUnavailable,
    CandidateBudgetReached,
    Timeout,
    Cancelled,
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

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentSelectorHandleRejectionReason {
    Tampered,
    WrongWorkspace,
    WrongBackend,
    Stale,
    FamilyNotAllowed,
    Unavailable,
}

impl AgentSelectorHandleRejectionReason {
    fn recovery(self) -> AgentSelectorHandleRecovery {
        match self {
            Self::Tampered | Self::Stale => AgentSelectorHandleRecovery::ResolveAgain,
            Self::WrongWorkspace => AgentSelectorHandleRecovery::ResolveInCurrentWorkspace,
            Self::WrongBackend => AgentSelectorHandleRecovery::ResolveWithActiveBackend,
            Self::FamilyNotAllowed => AgentSelectorHandleRecovery::ChooseCompatibleOperation,
            Self::Unavailable => AgentSelectorHandleRecovery::UseExplicitSelector,
        }
    }
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentSelectorHandleRecovery {
    ResolveAgain,
    ResolveInCurrentWorkspace,
    ResolveWithActiveBackend,
    ChooseCompatibleOperation,
    UseExplicitSelector,
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
    coverage: Option<AgentRelationshipCoverageInput>,
    #[serde(skip_serializing_if = "Option::is_none")]
    limitations: Option<Vec<AgentRelationshipSearchLimitation>>,
    schema_version: u32,
}
