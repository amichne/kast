#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentReplacementProofDimension {
    ExactTargetIdentity,
    SupportedTargetKind,
    SingleSupportedProposedDeclaration,
    CompilerSignatureEqual,
    ProposedPsiTraversalExhaustive,
    EveryReferenceCompilerResolved,
    EveryReferenceTargetMatched,
    EveryCallExact,
    NoUnsupportedReferenceKind,
    ExactOutboundCardinality,
    SourceContextHashBound,
    SemanticGenerationUnchanged,
}

const ALL_REPLACEMENT_PROOF_DIMENSIONS: [AgentReplacementProofDimension; 12] = [
    AgentReplacementProofDimension::ExactTargetIdentity,
    AgentReplacementProofDimension::SupportedTargetKind,
    AgentReplacementProofDimension::SingleSupportedProposedDeclaration,
    AgentReplacementProofDimension::CompilerSignatureEqual,
    AgentReplacementProofDimension::ProposedPsiTraversalExhaustive,
    AgentReplacementProofDimension::EveryReferenceCompilerResolved,
    AgentReplacementProofDimension::EveryReferenceTargetMatched,
    AgentReplacementProofDimension::EveryCallExact,
    AgentReplacementProofDimension::NoUnsupportedReferenceKind,
    AgentReplacementProofDimension::ExactOutboundCardinality,
    AgentReplacementProofDimension::SourceContextHashBound,
    AgentReplacementProofDimension::SemanticGenerationUnchanged,
];

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(tag = "type", rename_all_fields = "camelCase", deny_unknown_fields)]
enum AgentExactReplacementCardinality {
    #[serde(rename = "EXACT")]
    Exact { total_count: usize },
}

impl AgentExactReplacementCardinality {
    fn total_count(self) -> usize {
        match self {
            Self::Exact { total_count } => total_count,
        }
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(tag = "type", rename_all_fields = "camelCase", deny_unknown_fields)]
enum AgentExactReplacementEvidence {
    #[serde(rename = "complete")]
    Complete {
        cardinality: AgentExactReplacementCardinality,
        dimensions: Vec<AgentReplacementProofDimension>,
    },
}

impl AgentExactReplacementEvidence {
    fn exact_count(&self) -> std::result::Result<usize, String> {
        match self {
            Self::Complete {
                cardinality,
                dimensions,
            } if dimensions.as_slice() == ALL_REPLACEMENT_PROOF_DIMENSIONS => {
                let count = cardinality.total_count();
                if count <= i32::MAX as usize {
                    Ok(count)
                } else {
                    Err("exact replacement cardinality exceeded the backend range".to_string())
                }
            }
            Self::Complete { .. } => Err(
                "exact replacement evidence did not contain every closed proof dimension in canonical order"
                    .to_string(),
            ),
        }
    }
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentReplacementCompilerSymbolKind {
    Function,
    Property,
    Constructor,
    Class,
    TypeAlias,
    Parameter,
    TypeParameter,
    Package,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(transparent)]
struct AgentReplacementCompilerTargetSignature(String);

impl AgentReplacementCompilerTargetSignature {
    fn is_valid(&self) -> bool {
        !self.0.trim().is_empty()
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(tag = "type", rename_all_fields = "camelCase", deny_unknown_fields)]
enum AgentReplacementOutboundTarget {
    #[serde(rename = "source")]
    Source {
        symbol: AgentExactReplacementSymbolIdentity,
    },
    #[serde(rename = "external")]
    External {
        fq_name: String,
        kind: AgentReplacementCompilerSymbolKind,
        signature: AgentReplacementCompilerTargetSignature,
    },
}

impl AgentReplacementOutboundTarget {
    fn is_valid(&self) -> bool {
        match self {
            Self::Source { symbol } => {
                symbol.is_valid() && symbol.kind != AgentReplacementSymbolKind::Unknown
            }
            Self::External {
                fq_name, signature, ..
            } => is_exact_replacement_name(fq_name) && signature.is_valid(),
        }
    }
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentReplacementOccurrenceProvenance {
    Compiler,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentExactReplacementOutboundReference {
    relative_start_offset: u32,
    relative_end_offset: u32,
    source_text: String,
    resolved_target: AgentReplacementOutboundTarget,
    provenance: AgentReplacementOccurrenceProvenance,
}

impl AgentExactReplacementOutboundReference {
    fn validate_against(&self, proposed_declaration: &str) -> std::result::Result<(), String> {
        if self.relative_start_offset >= self.relative_end_offset
            || self.relative_end_offset > i32::MAX as u32
            || self.source_text.trim().is_empty()
            || !self.resolved_target.is_valid()
        {
            return Err(
                "exact replacement proof contained a malformed outbound reference".to_string(),
            );
        }
        if !utf16_range_equals(
            proposed_declaration,
            self.relative_start_offset,
            self.relative_end_offset,
            &self.source_text,
        ) {
            return Err(
                "exact replacement outbound reference range did not match its source text"
                    .to_string(),
            );
        }
        Ok(())
    }

    fn range_key(&self) -> (u32, u32) {
        (self.relative_start_offset, self.relative_end_offset)
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(transparent)]
struct AgentReplacementDeclarationSha256(String);

impl AgentReplacementDeclarationSha256 {
    fn matches(&self, proposed_declaration: &str) -> bool {
        is_lowercase_sha256(&self.0)
            && self.0 == replacement_sha256(proposed_declaration.as_bytes())
    }
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentReplacementDeclarationSlice {
    start_offset: u32,
    end_offset: u32,
}

impl AgentReplacementDeclarationSlice {
    fn validate_against<'a>(
        &self,
        proposed_edit: &'a str,
    ) -> std::result::Result<&'a str, String> {
        if self.start_offset >= self.end_offset || self.end_offset > i32::MAX as u32 {
            return Err("exact replacement proof contained an invalid declaration slice".to_string());
        }
        let start = utf16_byte_offset(proposed_edit, self.start_offset)
            .ok_or_else(|| "exact replacement declaration slice split a UTF-16 character".to_string())?;
        let end = utf16_byte_offset(proposed_edit, self.end_offset)
            .ok_or_else(|| "exact replacement declaration slice split a UTF-16 character".to_string())?;
        let declaration = &proposed_edit[start..end];
        if !proposed_edit[..start].trim().is_empty()
            || !proposed_edit[end..].trim().is_empty()
            || declaration.trim().is_empty()
            || declaration.trim() != declaration
        {
            return Err(
                "exact replacement declaration slice did not isolate one declaration from its whitespace envelope"
                    .to_string(),
            );
        }
        Ok(declaration)
    }

    fn contains(&self, start: u32, end: u32) -> bool {
        self.start_offset <= start && start < end && end <= self.end_offset
    }
}
