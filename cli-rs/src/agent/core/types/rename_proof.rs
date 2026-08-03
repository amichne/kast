#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentExactRenameSymbolKind {
    Class,
    Interface,
    Object,
    Function,
    Property,
    Parameter,
    Unknown,
}

impl AgentExactRenameSymbolKind {
    fn parse(value: &str) -> Option<Self> {
        match value {
            "CLASS" => Some(Self::Class),
            "INTERFACE" => Some(Self::Interface),
            "OBJECT" => Some(Self::Object),
            "FUNCTION" => Some(Self::Function),
            "PROPERTY" => Some(Self::Property),
            "PARAMETER" => Some(Self::Parameter),
            "UNKNOWN" => Some(Self::Unknown),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentExactRenameSymbolIdentity {
    fq_name: String,
    kind: AgentExactRenameSymbolKind,
    declaration_file: String,
    declaration_start_offset: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    containing_type: Option<String>,
}

impl AgentExactRenameSymbolIdentity {
    fn from_compiler(identity: &AgentCompilerSymbolIdentity) -> Option<Self> {
        let kind = AgentExactRenameSymbolKind::parse(identity.fields.get("kind")?.as_str()?)?;
        let location = identity.fields.get("location")?.as_object()?;
        let declaration_start_offset =
            i32::try_from(location.get("startOffset")?.as_u64()?).ok()? as u32;
        let containing_type = match identity.fields.get("containingType") {
            None | Some(Value::Null) => None,
            Some(value) => Some(value.as_str()?.to_string()),
        };
        let target = Self {
            fq_name: identity.fq_name.clone(),
            kind,
            declaration_file: location.get("filePath")?.as_str()?.to_string(),
            declaration_start_offset,
            containing_type,
        };
        target.is_valid().then_some(target)
    }

    fn from_relation(identity: &AgentRelationIdentityProjection) -> Option<Self> {
        let target = Self {
            fq_name: identity.fq_name.clone(),
            kind: AgentExactRenameSymbolKind::parse(&identity.kind)?,
            declaration_file: identity.declaration_file.clone(),
            declaration_start_offset: u32::try_from(identity.declaration_start_offset).ok()?,
            containing_type: identity.containing_type.clone(),
        };
        target.is_valid().then_some(target)
    }

    fn is_valid(&self) -> bool {
        !self.fq_name.trim().is_empty()
            && !self.declaration_file.trim().is_empty()
            && is_normalized_absolute_rename_path(&self.declaration_file)
            && self.declaration_start_offset <= i32::MAX as u32
            && self
                .containing_type
                .as_ref()
                .is_none_or(|value| !value.trim().is_empty())
    }

    fn position(&self) -> AgentRenamePosition {
        AgentRenamePosition {
            file_path: self.declaration_file.clone(),
            offset: self.declaration_start_offset,
        }
    }
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(transparent)]
struct AgentMutationSemanticGeneration(u64);

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(
    tag = "type",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
enum AgentExactRenameCardinality {
    #[serde(rename = "EXACT")]
    Exact { total_count: usize },
}

impl AgentExactRenameCardinality {
    fn total_count(self) -> usize {
        match self {
            Self::Exact { total_count } => total_count,
        }
    }
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentExactRenameCoverageStatus {
    Complete,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(
    tag = "type",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
enum AgentExactRenameCoverage {
    #[serde(rename = "COMPLETE")]
    Complete {
        identity: AgentExactRenameCoverageStatus,
        project_scope: AgentExactRenameCoverageStatus,
        source_set_scope: AgentExactRenameCoverageStatus,
        index_freshness: AgentExactRenameCoverageStatus,
        backend: AgentExactRenameCoverageStatus,
        requested_family: AgentExactRenameCoverageStatus,
        limitations: Vec<AgentRelationshipSearchLimitation>,
    },
}

impl AgentExactRenameCoverage {
    fn is_complete(&self) -> bool {
        match self {
            Self::Complete {
                identity,
                project_scope,
                source_set_scope,
                index_freshness,
                backend,
                requested_family,
                limitations,
            } => [
                identity,
                project_scope,
                source_set_scope,
                index_freshness,
                backend,
                requested_family,
            ]
            .into_iter()
            .all(|status| *status == AgentExactRenameCoverageStatus::Complete)
                && limitations.is_empty(),
        }
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(
    tag = "type",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
enum AgentExactRenameEvidence {
    #[serde(rename = "COMPLETE")]
    Complete {
        cardinality: AgentExactRenameCardinality,
        coverage: AgentExactRenameCoverage,
    },
}

impl AgentExactRenameEvidence {
    fn validate(&self) -> std::result::Result<usize, String> {
        match self {
            Self::Complete {
                cardinality,
                coverage,
            } if coverage.is_complete() => Ok(cardinality.total_count()),
            Self::Complete { .. } => {
                Err("exact rename proof did not contain complete relationship coverage".to_string())
            }
        }
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentExactRenameDeclarationScope {
    start_offset: u32,
    end_offset: u32,
    start_line: u32,
    end_line: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    source_text: Option<String>,
}

impl AgentExactRenameDeclarationScope {
    fn is_valid(&self) -> bool {
        self.start_offset <= i32::MAX as u32
            && self.end_offset <= i32::MAX as u32
            && self.start_offset <= self.end_offset
            && self.start_line > 0
            && self.end_line <= i32::MAX as u32
            && self.start_line <= self.end_line
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentExactRenameLocation {
    file_path: String,
    start_offset: u32,
    end_offset: u32,
    start_line: u32,
    start_column: u32,
    preview: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    usage_site_scope: Option<AgentExactRenameDeclarationScope>,
}

impl AgentExactRenameLocation {
    fn is_valid(&self) -> bool {
        !self.file_path.trim().is_empty()
            && Path::new(&self.file_path).is_absolute()
            && self.start_offset <= i32::MAX as u32
            && self.end_offset <= i32::MAX as u32
            && self.start_offset <= self.end_offset
            && self.start_line > 0
            && self.start_line <= i32::MAX as u32
            && self.start_column > 0
            && self.start_column <= i32::MAX as u32
            && self
                .usage_site_scope
                .as_ref()
                .is_none_or(AgentExactRenameDeclarationScope::is_valid)
    }

    fn source_range_key(&self) -> (String, u32, u32) {
        (self.file_path.clone(), self.start_offset, self.end_offset)
    }
}

fn is_normalized_absolute_rename_path(value: &str) -> bool {
    let path = Path::new(value);
    path.is_absolute()
        && path.components().all(|component| {
            matches!(
                component,
                std::path::Component::RootDir | std::path::Component::Normal(_)
            )
        })
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(
    tag = "type",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
enum AgentExactRenameContainingSymbol {
    #[serde(rename = "KNOWN")]
    Known {
        symbol: AgentExactRenameSymbolIdentity,
    },
    #[serde(rename = "TOP_LEVEL")]
    TopLevel,
}

impl AgentExactRenameContainingSymbol {
    fn is_valid(&self) -> bool {
        match self {
            Self::Known { symbol } => symbol.is_valid(),
            Self::TopLevel => true,
        }
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentExactRenameReferenceOccurrence {
    location: AgentExactRenameLocation,
    containing_symbol: AgentExactRenameContainingSymbol,
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentExactRenameOccurrenceProvenance {
    Compiler,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentExactRenameOccurrence {
    reference: AgentExactRenameReferenceOccurrence,
    resolved_target: AgentExactRenameSymbolIdentity,
    provenance: AgentExactRenameOccurrenceProvenance,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct AgentExactRenameProof {
    target: AgentExactRenameSymbolIdentity,
    required_generation: AgentMutationSemanticGeneration,
    evidence: AgentExactRenameEvidence,
    occurrences: Vec<AgentExactRenameOccurrence>,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct AgentRenamePostconditionEvidence {
    resulting_target: AgentExactRenameSymbolIdentity,
    evidence: AgentExactRenameEvidence,
    occurrences: Vec<AgentExactRenameOccurrence>,
}

impl AgentExactRenameProof {
    fn validate(&self) -> std::result::Result<(), String> {
        if !self.target.is_valid() {
            return Err("exact rename proof contained an invalid target identity".to_string());
        }
        if self.required_generation.0 > i64::MAX as u64 {
            return Err("exact rename proof contained an invalid semantic generation".to_string());
        }
        if self.evidence.validate()? != self.occurrences.len() {
            return Err(
                "exact rename proof cardinality disagreed with its occurrence evidence".to_string(),
            );
        }
        let mut ranges = BTreeSet::new();
        for occurrence in &self.occurrences {
            if !occurrence.reference.location.is_valid()
                || !occurrence.reference.containing_symbol.is_valid()
            {
                return Err("exact rename proof contained a malformed occurrence".to_string());
            }
            if occurrence.resolved_target != self.target {
                return Err(
                    "exact rename occurrence resolved to a different target identity".to_string(),
                );
            }
            if !ranges.insert(occurrence.reference.location.source_range_key()) {
                return Err("exact rename proof repeated one occurrence source range".to_string());
            }
        }
        Ok(())
    }

    fn reference_ranges(&self) -> BTreeSet<(String, u32, u32)> {
        self.occurrences
            .iter()
            .map(|occurrence| occurrence.reference.location.source_range_key())
            .collect()
    }
}
