#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentAdditionWorkspaceRange {
    file_path: String,
    start_offset: u32,
    end_offset: u32,
}

impl AgentAdditionWorkspaceRange {
    fn validate(&self) -> bool {
        is_normalized_absolute_exact_file_path(&self.file_path)
            && self.start_offset < self.end_offset
            && self.end_offset <= i32::MAX as u32
    }
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentAdditionUnresolvedReason {
    NotFound,
    Ambiguous,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(tag = "type", rename_all_fields = "camelCase", deny_unknown_fields)]
enum AgentAdditionRebindingCurrentTarget {
    #[serde(rename = "RESOLVED")]
    Resolved {
        target: AgentAdditionResolvedTarget,
    },
    #[serde(rename = "UNRESOLVED")]
    Unresolved {
        reason: AgentAdditionUnresolvedReason,
    },
}

impl AgentAdditionRebindingCurrentTarget {
    fn validate(&self) -> std::result::Result<(), String> {
        match self {
            Self::Resolved { target } => target.validate(),
            Self::Unresolved { .. } => Ok(()),
        }
    }

    fn source_file_path(&self) -> Option<&str> {
        match self {
            Self::Resolved { target } => target.source_file_path(),
            Self::Unresolved { .. } => None,
        }
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentAdditionRebindingOccurrence {
    range: AgentAdditionWorkspaceRange,
    current_target: AgentAdditionRebindingCurrentTarget,
    provenance: AgentAdditionOccurrenceProvenance,
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentAdditionRebindingDimension {
    ExactOccurrenceCardinality,
    CompleteDependentScope,
    CompleteImplicitLookupScope,
    CompleteJavaLookupScope,
    EveryCurrentBindingCaptured,
    VirtualProposedBindingsEqualBaseline,
}

const COMPLETE_ADDITION_REBINDING_DIMENSIONS: [AgentAdditionRebindingDimension; 6] = [
    AgentAdditionRebindingDimension::ExactOccurrenceCardinality,
    AgentAdditionRebindingDimension::CompleteDependentScope,
    AgentAdditionRebindingDimension::CompleteImplicitLookupScope,
    AgentAdditionRebindingDimension::CompleteJavaLookupScope,
    AgentAdditionRebindingDimension::EveryCurrentBindingCaptured,
    AgentAdditionRebindingDimension::VirtualProposedBindingsEqualBaseline,
];

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentAdditionRebindingBaseline {
    cardinality: u32,
    dimensions: Vec<AgentAdditionRebindingDimension>,
    occurrences: Vec<AgentAdditionRebindingOccurrence>,
}

impl AgentAdditionRebindingBaseline {
    fn validate(&self) -> std::result::Result<(), String> {
        if self.cardinality != 0
            || !self.occurrences.is_empty()
            || self.dimensions != COMPLETE_ADDITION_REBINDING_DIMENSIONS
        {
            return Err(
                "addition rebinding proof did not prove exact zero candidates across every closed dimension"
                    .to_string(),
            );
        }
        let mut previous: Option<&AgentAdditionWorkspaceRange> = None;
        let mut ranges = BTreeSet::new();
        for occurrence in &self.occurrences {
            occurrence.current_target.validate()?;
            let range = &occurrence.range;
            if occurrence.provenance != AgentAdditionOccurrenceProvenance::Compiler
                || !range.validate()
                || !ranges.insert((
                    range.file_path.clone(),
                    range.start_offset,
                    range.end_offset,
                ))
                || previous.is_some_and(|prior| {
                    prior.file_path > range.file_path
                        || (prior.file_path == range.file_path
                            && (prior.start_offset > range.start_offset
                                || prior.end_offset > range.start_offset))
                })
            {
                return Err("addition rebinding occurrences were not exact and ordered".to_string());
            }
            previous = Some(range);
        }
        Ok(())
    }
}
