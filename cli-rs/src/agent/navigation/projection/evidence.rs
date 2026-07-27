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
