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
