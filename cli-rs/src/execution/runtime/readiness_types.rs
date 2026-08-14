#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeReadiness {
    pub runtime: CurrentCapabilityLaneReadiness,
    pub model: CurrentCapabilityLaneReadiness,
    pub workspace_files: CurrentCapabilityLaneReadiness,
    pub compiler: CurrentCapabilityLaneReadiness,
    pub source_index: RetainedCapabilityLaneReadiness,
    pub references: RetainedCapabilityLaneReadiness,
    pub semantic_graph: RetainedCapabilityLaneReadiness,
    pub mutation: CurrentCapabilityLaneReadiness,
}

impl RuntimeReadiness {
    #[cfg(test)]
    pub(crate) fn ready() -> Self {
        let current = CurrentCapabilityLaneEvidence::current_for_test(1);
        let retained = RetainedCapabilityLaneEvidence::current_for_test(1);
        Self {
            runtime: CurrentCapabilityLaneReadiness::Available { evidence: current },
            model: CurrentCapabilityLaneReadiness::Available { evidence: current },
            workspace_files: CurrentCapabilityLaneReadiness::Available { evidence: current },
            compiler: CurrentCapabilityLaneReadiness::Available { evidence: current },
            source_index: RetainedCapabilityLaneReadiness::Available { evidence: retained },
            references: RetainedCapabilityLaneReadiness::Available { evidence: retained },
            semantic_graph: RetainedCapabilityLaneReadiness::Available { evidence: retained },
            mutation: CurrentCapabilityLaneReadiness::Available { evidence: current },
        }
    }
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(try_from = "u64", into = "u64")]
pub struct EvidenceRevision(u64);

impl EvidenceRevision {
    fn get(self) -> u64 {
        self.0
    }
}

impl TryFrom<u64> for EvidenceRevision {
    type Error = String;

    fn try_from(value: u64) -> std::result::Result<Self, Self::Error> {
        (value > 0)
            .then_some(Self(value))
            .ok_or_else(|| "capability evidence revision must be positive".to_string())
    }
}

impl From<EvidenceRevision> for u64 {
    fn from(value: EvidenceRevision) -> Self {
        value.0
    }
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CurrentCapabilityLaneFreshness {
    Current,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RetainedCapabilityLaneFreshness {
    Current,
    Previous,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum PreviousCapabilityLaneFreshness {
    Previous,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CurrentCapabilityLaneEvidence {
    revision: EvidenceRevision,
    freshness: CurrentCapabilityLaneFreshness,
}

impl CurrentCapabilityLaneEvidence {
    #[cfg(test)]
    pub(crate) fn current_for_test(revision: u64) -> Self {
        Self {
            revision: EvidenceRevision::try_from(revision).expect("test revision must be positive"),
            freshness: CurrentCapabilityLaneFreshness::Current,
        }
    }

    pub(crate) fn revision(self) -> u64 {
        self.revision.get()
    }
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RetainedCapabilityLaneEvidence {
    revision: EvidenceRevision,
    freshness: RetainedCapabilityLaneFreshness,
}

impl RetainedCapabilityLaneEvidence {
    #[cfg(test)]
    pub(crate) fn current_for_test(revision: u64) -> Self {
        Self {
            revision: EvidenceRevision::try_from(revision).expect("test revision must be positive"),
            freshness: RetainedCapabilityLaneFreshness::Current,
        }
    }

    pub(crate) fn revision(self) -> u64 {
        self.revision.get()
    }

    pub(crate) fn freshness(self) -> RetainedCapabilityLaneFreshness {
        self.freshness
    }
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PreviousCapabilityLaneEvidence {
    revision: EvidenceRevision,
    freshness: PreviousCapabilityLaneFreshness,
}

impl PreviousCapabilityLaneEvidence {
    pub(crate) fn revision(self) -> u64 {
        self.revision.get()
    }
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CapabilityLaneBlocker {
    CapabilityUnavailable,
    DependencyUnavailable,
    InitializationFailed,
    Invalidated,
    Unsupported,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "type", rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CurrentCapabilityLaneReadiness {
    Available { evidence: CurrentCapabilityLaneEvidence },
    Building { progress: Value },
    Blocked { blocker: CapabilityLaneBlocker },
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "type", rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RetainedCapabilityLaneFallback {
    None,
    Previous { evidence: PreviousCapabilityLaneEvidence },
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "type", rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RetainedCapabilityLaneReadiness {
    Available { evidence: RetainedCapabilityLaneEvidence },
    Building {
        progress: Value,
        fallback: RetainedCapabilityLaneFallback,
    },
    Blocked { blocker: CapabilityLaneBlocker },
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(tag = "type", rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RetainedWorkspaceGenerationStatus {
    #[default]
    None,
    Previous {
        publication: crate::published_workspace::PublishedWorkspaceGenerationManifest,
    },
}

impl RuntimeStatusResponse {
    pub(crate) fn validate_protocol(self) -> Result<Self> {
        let aligned = match self.state {
            RuntimeState::Starting => matches!(
                self.readiness.runtime,
                CurrentCapabilityLaneReadiness::Building { .. }
            ),
            RuntimeState::Indexing => matches!(
                self.readiness.runtime,
                CurrentCapabilityLaneReadiness::Available { .. }
            ) && matches!(
                self.readiness.model,
                CurrentCapabilityLaneReadiness::Building { .. }
            ),
            RuntimeState::Ready => matches!(
                self.readiness.runtime,
                CurrentCapabilityLaneReadiness::Available { .. }
            ) && matches!(
                self.readiness.model,
                CurrentCapabilityLaneReadiness::Available { .. }
            ),
            RuntimeState::Degraded => matches!(
                self.readiness.runtime,
                CurrentCapabilityLaneReadiness::Blocked { .. }
            ) || matches!(
                self.readiness.model,
                CurrentCapabilityLaneReadiness::Blocked { .. }
            ),
        };
        if !aligned {
            return Err(CliError::new(
                "RUNTIME_STATUS_INVALID",
                "Runtime epoch state contradicts its tagged readiness lanes.",
            ));
        }
        Ok(self)
    }

    pub fn healthy(&self) -> bool {
        !matches!(
            self.readiness.runtime,
            CurrentCapabilityLaneReadiness::Blocked { .. }
        )
    }

    pub fn active(&self) -> bool {
        matches!(
            self.readiness.runtime,
            CurrentCapabilityLaneReadiness::Available { .. }
        )
    }

    pub fn indexing(&self) -> bool {
        matches!(
            self.readiness.model,
            CurrentCapabilityLaneReadiness::Building { .. }
        )
    }

    pub fn reference_index_ready(&self) -> bool {
        matches!(
            self.readiness.references,
            RetainedCapabilityLaneReadiness::Available { .. }
        )
    }

    pub fn graph_index_ready(&self) -> bool {
        matches!(
            self.readiness.semantic_graph,
            RetainedCapabilityLaneReadiness::Available { .. }
        )
    }
}
