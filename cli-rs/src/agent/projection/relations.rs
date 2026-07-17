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

    fn matches_identity(&self, actual: &mut AgentRelationIdentityProjection) -> bool {
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
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentRelationshipCoverageStatus {
    Complete,
    InProgress,
    Partial,
    Stale,
    Excluded,
    TimedOut,
    Cancelled,
    Unavailable,
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, PartialOrd, Ord, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentRelationshipSearchLimitation {
    IdentityUnproven,
    ProjectScopeIncomplete,
    SourceSetScopeIncomplete,
    SourceSetExcluded,
    IndexNotReady,
    IndexStale,
    BackendIncomplete,
    BackendUnavailable,
    FamilySearchInProgress,
    FamilySearchIncomplete,
    CandidateBudgetReached,
    TraversalStateBudgetReached,
    TimedOut,
    Cancelled,
    GenerationChanged,
    ContinuationExpired,
    ContinuationInvalid,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(tag = "type", rename_all_fields = "camelCase")]
enum AgentRelationshipCoverageInput {
    #[serde(rename = "COMPLETE")]
    Complete {
        identity: AgentRelationshipCoverageStatus,
        project_scope: AgentRelationshipCoverageStatus,
        source_set_scope: AgentRelationshipCoverageStatus,
        index_freshness: AgentRelationshipCoverageStatus,
        backend: AgentRelationshipCoverageStatus,
        requested_family: AgentRelationshipCoverageStatus,
        limitations: Vec<AgentRelationshipSearchLimitation>,
    },
    #[serde(rename = "RESUMABLE")]
    Resumable {
        identity: AgentRelationshipCoverageStatus,
        project_scope: AgentRelationshipCoverageStatus,
        source_set_scope: AgentRelationshipCoverageStatus,
        index_freshness: AgentRelationshipCoverageStatus,
        backend: AgentRelationshipCoverageStatus,
        requested_family: AgentRelationshipCoverageStatus,
        limitations: Vec<AgentRelationshipSearchLimitation>,
    },
    #[serde(rename = "LIMITED")]
    Limited {
        identity: AgentRelationshipCoverageStatus,
        project_scope: AgentRelationshipCoverageStatus,
        source_set_scope: AgentRelationshipCoverageStatus,
        index_freshness: AgentRelationshipCoverageStatus,
        backend: AgentRelationshipCoverageStatus,
        requested_family: AgentRelationshipCoverageStatus,
        limitations: Vec<AgentRelationshipSearchLimitation>,
    },
}

impl AgentRelationshipCoverageInput {
    fn is_valid(&self) -> bool {
        match self {
            Self::Complete {
                identity,
                project_scope,
                source_set_scope,
                index_freshness,
                backend,
                requested_family,
                limitations,
            } => {
                [
                    identity,
                    project_scope,
                    source_set_scope,
                    index_freshness,
                    backend,
                    requested_family,
                ]
                .into_iter()
                .all(|status| *status == AgentRelationshipCoverageStatus::Complete)
                    && limitations.is_empty()
            }
            Self::Resumable {
                identity,
                project_scope,
                source_set_scope,
                index_freshness,
                backend,
                requested_family,
                limitations,
            } => {
                [
                    identity,
                    project_scope,
                    source_set_scope,
                    index_freshness,
                    backend,
                ]
                .into_iter()
                .all(|status| *status == AgentRelationshipCoverageStatus::Complete)
                    && *requested_family == AgentRelationshipCoverageStatus::InProgress
                    && limitations
                        == &[AgentRelationshipSearchLimitation::FamilySearchInProgress]
            }
            Self::Limited {
                identity,
                project_scope,
                source_set_scope,
                index_freshness,
                backend,
                requested_family,
                limitations,
            } => {
                let canonical = !limitations.is_empty()
                    && limitations.windows(2).all(|pair| pair[0] < pair[1]);
                canonical
                    && *identity
                        == status_for_identity_limitations(limitations)
                    && *project_scope
                        == status_for_project_limitations(limitations)
                    && *source_set_scope
                        == status_for_source_set_limitations(limitations)
                    && *index_freshness
                        == status_for_index_limitations(limitations)
                    && *backend == status_for_backend_limitations(limitations)
                    && *requested_family == status_for_family_limitations(limitations)
            }
        }
    }

    fn is_complete(&self) -> bool {
        matches!(self, Self::Complete { .. }) && self.is_valid()
    }

    fn is_resumable(&self) -> bool {
        matches!(self, Self::Resumable { .. }) && self.is_valid()
    }

    fn is_limited(&self) -> bool {
        matches!(self, Self::Limited { .. }) && self.is_valid()
    }

    fn limitations(&self) -> &[AgentRelationshipSearchLimitation] {
        match self {
            Self::Complete { limitations, .. }
            | Self::Resumable { limitations, .. }
            | Self::Limited { limitations, .. } => limitations,
        }
    }
}

fn status_for_identity_limitations(
    limitations: &[AgentRelationshipSearchLimitation],
) -> AgentRelationshipCoverageStatus {
    if limitations.contains(&AgentRelationshipSearchLimitation::IdentityUnproven) {
        AgentRelationshipCoverageStatus::Unavailable
    } else {
        AgentRelationshipCoverageStatus::Complete
    }
}

fn status_for_project_limitations(
    limitations: &[AgentRelationshipSearchLimitation],
) -> AgentRelationshipCoverageStatus {
    if limitations.contains(&AgentRelationshipSearchLimitation::ProjectScopeIncomplete) {
        AgentRelationshipCoverageStatus::Partial
    } else {
        AgentRelationshipCoverageStatus::Complete
    }
}

fn status_for_source_set_limitations(
    limitations: &[AgentRelationshipSearchLimitation],
) -> AgentRelationshipCoverageStatus {
    if limitations.contains(&AgentRelationshipSearchLimitation::SourceSetExcluded) {
        AgentRelationshipCoverageStatus::Excluded
    } else if limitations.contains(&AgentRelationshipSearchLimitation::SourceSetScopeIncomplete) {
        AgentRelationshipCoverageStatus::Partial
    } else {
        AgentRelationshipCoverageStatus::Complete
    }
}

fn status_for_index_limitations(
    limitations: &[AgentRelationshipSearchLimitation],
) -> AgentRelationshipCoverageStatus {
    if limitations.contains(&AgentRelationshipSearchLimitation::IndexStale)
        || limitations.contains(&AgentRelationshipSearchLimitation::GenerationChanged)
    {
        AgentRelationshipCoverageStatus::Stale
    } else if limitations.contains(&AgentRelationshipSearchLimitation::IndexNotReady) {
        AgentRelationshipCoverageStatus::InProgress
    } else {
        AgentRelationshipCoverageStatus::Complete
    }
}

fn status_for_backend_limitations(
    limitations: &[AgentRelationshipSearchLimitation],
) -> AgentRelationshipCoverageStatus {
    if limitations.contains(&AgentRelationshipSearchLimitation::Cancelled) {
        AgentRelationshipCoverageStatus::Cancelled
    } else if limitations.iter().any(|limitation| {
        matches!(
            limitation,
            AgentRelationshipSearchLimitation::BackendUnavailable
                | AgentRelationshipSearchLimitation::TraversalStateBudgetReached
                | AgentRelationshipSearchLimitation::ContinuationExpired
                | AgentRelationshipSearchLimitation::ContinuationInvalid
        )
    }) {
        AgentRelationshipCoverageStatus::Unavailable
    } else if limitations.contains(&AgentRelationshipSearchLimitation::BackendIncomplete) {
        AgentRelationshipCoverageStatus::Partial
    } else {
        AgentRelationshipCoverageStatus::Complete
    }
}

fn status_for_family_limitations(
    limitations: &[AgentRelationshipSearchLimitation],
) -> AgentRelationshipCoverageStatus {
    if limitations.contains(&AgentRelationshipSearchLimitation::TimedOut) {
        AgentRelationshipCoverageStatus::TimedOut
    } else if limitations.contains(&AgentRelationshipSearchLimitation::Cancelled) {
        AgentRelationshipCoverageStatus::Cancelled
    } else if limitations.contains(&AgentRelationshipSearchLimitation::FamilySearchInProgress) {
        AgentRelationshipCoverageStatus::InProgress
    } else if limitations.iter().any(|limitation| {
        matches!(
            limitation,
            AgentRelationshipSearchLimitation::IndexNotReady
                | AgentRelationshipSearchLimitation::BackendUnavailable
                | AgentRelationshipSearchLimitation::ContinuationExpired
                | AgentRelationshipSearchLimitation::ContinuationInvalid
        )
    }) {
        AgentRelationshipCoverageStatus::Unavailable
    } else {
        AgentRelationshipCoverageStatus::Partial
    }
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize)]
#[serde(
    tag = "type",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase"
)]
enum AgentKnownMinimumCardinality {
    KnownMinimum { known_minimum_count: usize },
}

impl AgentKnownMinimumCardinality {
    fn known_minimum(self) -> usize {
        match self {
            Self::KnownMinimum {
                known_minimum_count,
            } => known_minimum_count,
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(tag = "type", rename_all_fields = "camelCase")]
enum AgentRelationshipResultEvidenceInput {
    #[serde(rename = "COMPLETE")]
    Complete {
        cardinality: AgentExactCardinality,
        coverage: AgentRelationshipCoverageInput,
    },
    #[serde(rename = "RESUMABLE")]
    Resumable {
        cardinality: AgentKnownMinimumCardinality,
        coverage: AgentRelationshipCoverageInput,
    },
    #[serde(rename = "LIMITED")]
    Limited {
        cardinality: AgentKnownMinimumCardinality,
        coverage: AgentRelationshipCoverageInput,
    },
}

impl AgentRelationshipResultEvidenceInput {
    fn is_valid_available(&self) -> bool {
        match self {
            Self::Complete { coverage, .. } => coverage.is_complete(),
            Self::Resumable { coverage, .. } => coverage.is_resumable(),
            Self::Limited { .. } => false,
        }
    }

    fn is_valid_complete(&self) -> bool {
        matches!(self, Self::Complete { coverage, .. } if coverage.is_complete())
    }

    fn is_valid_resumable(&self) -> bool {
        matches!(self, Self::Resumable { coverage, .. } if coverage.is_resumable())
    }

    fn is_valid_limited(&self) -> bool {
        matches!(self, Self::Limited { coverage, .. } if coverage.is_limited())
    }

    fn cardinality(&self) -> AgentResultCardinality {
        match self {
            Self::Complete { cardinality, .. } => AgentResultCardinality::Exact {
                total_count: cardinality.total_count(),
            },
            Self::Resumable { cardinality, .. } | Self::Limited { cardinality, .. } => {
                AgentResultCardinality::KnownMinimum {
                    known_minimum_count: cardinality.known_minimum(),
                }
            }
        }
    }

    fn coverage(&self) -> &AgentRelationshipCoverageInput {
        match self {
            Self::Complete { coverage, .. }
            | Self::Resumable { coverage, .. }
            | Self::Limited { coverage, .. } => coverage,
        }
    }
}

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

fn project_references_envelope(
    envelope: AgentEnvelope,
    view: AgentResultView<AgentRelationField>,
    result_limit: usize,
) -> AgentEnvelope {
    if !envelope.ok {
        return compact_error_envelope(envelope);
    }
    let method = envelope.method.clone();
    let provenance = match reference_request_provenance(&envelope) {
        Ok(provenance) => provenance,
        Err(message) => return invalid_projection_envelope(method, message),
    };
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
            evidence,
            page,
        } => project_available_references(
            method,
            view,
            result_limit,
            subject,
            references,
            evidence,
            page,
            &provenance,
        ),
        other => project_expected_reference_outcome(method, other, &provenance),
    }
}

#[derive(Debug, Clone)]
enum AgentReferenceRequestProvenance {
    Explicit(AgentExpectedRelationshipSelector),
    Handle,
}

impl AgentReferenceRequestProvenance {
    fn matches_subject(&self, subject: &mut AgentRelationIdentityProjection) -> bool {
        subject.is_valid()
            && match self {
                Self::Explicit(expected) => expected.matches(subject),
                Self::Handle => true,
            }
    }

    fn matches_selector(&self, selector: &AgentRelationSelectorProjection) -> bool {
        selector.is_valid()
            && match self {
                Self::Explicit(expected) => expected.matches_selector(selector),
                Self::Handle => true,
            }
    }

    fn matches_selector_and_subject(
        &self,
        selector: &AgentRelationSelectorProjection,
        subject: &mut AgentRelationIdentityProjection,
    ) -> bool {
        self.matches_selector(selector)
            && self.matches_subject(subject)
            && selector.matches_identity(subject)
    }

    fn is_handle(&self) -> bool {
        matches!(self, Self::Handle)
    }
}

fn reference_request_provenance(
    envelope: &AgentEnvelope,
) -> std::result::Result<AgentReferenceRequestProvenance, String> {
    let params = envelope
        .request
        .as_ref()
        .and_then(|request| request.get("params"))
        .and_then(Value::as_object)
        .ok_or_else(|| "References omitted normalized request provenance.".to_string())?;
    let selector = params.get("selector").filter(|value| !value.is_null());
    let selector_handle = params
        .get("selectorHandle")
        .filter(|value| !value.is_null());
    match (selector, selector_handle) {
        (Some(selector), None) => {
            let selector = serde_json::from_value::<AgentRelationSelectorProjection>(
                selector.clone(),
            )
            .map_err(|error| format!("References explicit selector provenance was invalid: {error}"))?;
            if !selector.is_valid() {
                return Err("References explicit selector provenance was invalid.".to_string());
            }
            Ok(AgentReferenceRequestProvenance::Explicit(
                AgentExpectedRelationshipSelector {
                    workspace_root: String::new(),
                    fq_name: selector.fq_name,
                    declaration_file: selector.declaration_file,
                    declaration_start_offset: selector.declaration_start_offset,
                    kind: selector.kind,
                    containing_type: selector.containing_type,
                },
            ))
        }
        (None, Some(Value::String(handle))) if !handle.trim().is_empty() => {
            Ok(AgentReferenceRequestProvenance::Handle)
        }
        _ => Err(
            "References request provenance did not contain exactly one explicit selector or selector handle."
                .to_string(),
        ),
    }
}

#[allow(clippy::too_many_arguments)]
fn project_available_references(
    method: String,
    view: AgentResultView<AgentRelationField>,
    result_limit: usize,
    mut subject: AgentRelationIdentityProjection,
    references: Vec<AgentReferenceOccurrenceInput>,
    evidence: AgentRelationshipResultEvidenceInput,
    page: Option<AgentReferencePageInput>,
    provenance: &AgentReferenceRequestProvenance,
) -> AgentEnvelope {
    if !evidence.is_valid_available()
        || !provenance.matches_subject(&mut subject)
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
    let cardinality = evidence.cardinality();
    if evidence.is_valid_resumable() != truncated
        || cardinality.known_minimum() < returned_count
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
    let coverage = evidence.coverage().clone();
    let limitations = evidence.coverage().limitations().to_vec();
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
            coverage: Some(coverage),
            limitations: Some(limitations),
            schema_version: SCHEMA_VERSION,
        },
        None,
    )
}

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

#[allow(clippy::too_many_arguments)]
fn project_typed_call_relationship_envelope(
    method: String,
    envelope: AgentEnvelope,
    expected: Option<AgentExpectedRelationshipSelector>,
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
    expected: Option<AgentExpectedRelationshipSelector>,
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
    expected: Option<AgentExpectedRelationshipSelector>,
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
    expected: Option<AgentExpectedRelationshipSelector>,
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
                || !expected
                    .as_ref()
                    .is_none_or(|expected| expected.matches(&mut subject))
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
    let AgentTypedTraversalPageInput {
        evidence,
        returned_count,
        visited_candidate_count,
        truncated,
        next_page_token,
    } = page;
    let cardinality = evidence.cardinality();
    let coverage = evidence.coverage().clone();
    let limitations = evidence.coverage().limitations().to_vec();
    let page = AgentTypedTraversalPageProjection {
        cardinality,
        returned_count,
        visited_candidate_count,
        truncated,
        next_page_token,
    };
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
    result.insert(
        "coverage".to_string(),
        serde_json::to_value(coverage).unwrap_or(Value::Null),
    );
    result.insert(
        "limitations".to_string(),
        serde_json::to_value(limitations).unwrap_or(Value::Null),
    );
    projected_agent_envelope(method, true, Value::Object(result), None)
}

fn project_typed_expected_relationship_outcome<Record, Reason>(
    method: String,
    result_type: &'static str,
    expected: Option<AgentExpectedRelationshipSelector>,
    outcome: AgentTypedTraversalResponseInput<Record, Reason>,
    admitted_kind: impl Fn(&str) -> bool,
) -> AgentEnvelope
where
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
        } if expected.is_none() => {
            if !selector.is_valid()
                || !subject.is_valid()
                || !selector.matches_identity(&mut subject)
                || !admitted_kind(&subject.kind)
                || !evidence.is_valid_limited()
            {
                return invalid_projection_envelope(
                    method,
                    "Handle-backed degraded relationship contained inconsistent subject or limitation evidence.",
                );
            }
            return projected_agent_envelope(
                method,
                true,
                json!({
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
                }),
                None,
            );
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
        } => {
            if !selector.is_valid()
                || !expected.matches_selector(&selector)
                || !subject.is_valid()
                || !expected.matches(&mut subject)
                || !admitted_kind(&subject.kind)
                || !evidence.is_valid_limited()
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
                "cardinality": evidence.cardinality(),
                "coverage": evidence.coverage(),
                "limitations": evidence.coverage().limitations(),
                "schemaVersion": SCHEMA_VERSION,
            })
        }
        AgentTypedTraversalResponseInput::CursorStale {
            selector,
            reason,
            evidence,
        }
            if selector.is_valid()
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
        }
            if selector.is_valid()
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
