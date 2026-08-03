#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentAdditionOccurrenceProvenance {
    Compiler,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentAdditionOutboundOccurrence {
    range: AgentAdditionRelativeRange,
    resolved_target: AgentAdditionResolvedTarget,
    provenance: AgentAdditionOccurrenceProvenance,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentAdditionOutboundEvidence {
    cardinality: u32,
    occurrences: Vec<AgentAdditionOutboundOccurrence>,
}

impl AgentAdditionOutboundEvidence {
    fn validate(&self, content_length: usize) -> std::result::Result<(), String> {
        if self.cardinality as usize != self.occurrences.len()
            || self.cardinality > i32::MAX as u32
        {
            return Err("addition outbound cardinality was not exact".to_string());
        }
        let mut previous: Option<&AgentAdditionRelativeRange> = None;
        let mut ranges = BTreeSet::new();
        for occurrence in &self.occurrences {
            occurrence.resolved_target.validate()?;
            let range = &occurrence.range;
            if occurrence.provenance != AgentAdditionOccurrenceProvenance::Compiler
                || !range.validate()
                || range.end_offset as usize > content_length
                || !ranges.insert((range.start_offset, range.end_offset))
                || previous.is_some_and(|prior| {
                    prior.start_offset > range.start_offset || prior.end_offset > range.start_offset
                })
            {
                return Err("addition outbound occurrences were not exact and ordered".to_string());
            }
            previous = Some(range);
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentAdditionCollisionDimension {
    ExactDeclarationIdentities,
    CompleteOwningSourceScope,
    CompleteDependentScope,
    NoCompilerCollision,
}

const COMPLETE_ADDITION_COLLISION_DIMENSIONS: [AgentAdditionCollisionDimension; 4] = [
    AgentAdditionCollisionDimension::ExactDeclarationIdentities,
    AgentAdditionCollisionDimension::CompleteOwningSourceScope,
    AgentAdditionCollisionDimension::CompleteDependentScope,
    AgentAdditionCollisionDimension::NoCompilerCollision,
];

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentAdditionCollisionEvidence {
    declaration_cardinality: u32,
    dimensions: Vec<AgentAdditionCollisionDimension>,
}

impl AgentAdditionCollisionEvidence {
    fn validate(&self, declaration_count: usize) -> std::result::Result<(), String> {
        if self.declaration_cardinality as usize != declaration_count
            || self.declaration_cardinality > i32::MAX as u32
            || self.dimensions != COMPLETE_ADDITION_COLLISION_DIMENSIONS
        {
            return Err("addition collision proof did not cover every closed dimension".to_string());
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentAdditionContextFileHash {
    file_path: String,
    sha256: String,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentAdditionProofContext {
    required_generation: u64,
    project_model_fingerprint: String,
    classpath_fingerprint: String,
    context_file_hashes: Vec<AgentAdditionContextFileHash>,
}

impl AgentAdditionProofContext {
    fn validate(&self) -> std::result::Result<BTreeMap<&str, &str>, String> {
        if self.required_generation > i64::MAX as u64
            || !is_lowercase_exact_file_sha256(&self.project_model_fingerprint)
            || !is_lowercase_exact_file_sha256(&self.classpath_fingerprint)
        {
            return Err("addition proof context contained invalid generation evidence".to_string());
        }
        let mut hashes = BTreeMap::new();
        let mut previous = None;
        for hash in &self.context_file_hashes {
            if !is_normalized_absolute_exact_file_path(&hash.file_path)
                || !is_lowercase_exact_file_sha256(&hash.sha256)
                || previous.is_some_and(|path: &str| path >= hash.file_path.as_str())
                || hashes.insert(hash.file_path.as_str(), hash.sha256.as_str()).is_some()
            {
                return Err("addition context hashes were not exact and ordered".to_string());
            }
            previous = Some(hash.file_path.as_str());
        }
        Ok(hashes)
    }
}
