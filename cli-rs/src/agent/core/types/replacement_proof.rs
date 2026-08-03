#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentReplacementSymbolKind {
    Class,
    Interface,
    Object,
    Function,
    Property,
    Parameter,
    Unknown,
}

impl AgentReplacementSymbolKind {
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

    fn supports_replacement(self) -> bool {
        matches!(self, Self::Function | Self::Property)
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentExactReplacementSymbolIdentity {
    fq_name: String,
    kind: AgentReplacementSymbolKind,
    declaration_file: String,
    declaration_start_offset: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    containing_type: Option<String>,
}

impl AgentExactReplacementSymbolIdentity {
    fn from_compiler(identity: &AgentCompilerSymbolIdentity) -> Option<Self> {
        let location = identity.fields.get("location")?.as_object()?;
        let target = Self {
            fq_name: identity.fq_name.clone(),
            kind: AgentReplacementSymbolKind::parse(identity.fields.get("kind")?.as_str()?)?,
            declaration_file: location.get("filePath")?.as_str()?.to_string(),
            declaration_start_offset: u32::try_from(location.get("startOffset")?.as_u64()?).ok()?,
            containing_type: match identity.fields.get("containingType") {
                None | Some(Value::Null) => None,
                Some(value) => Some(value.as_str()?.to_string()),
            },
        };
        target.is_valid().then_some(target)
    }

    fn from_relation(identity: &AgentRelationIdentityProjection) -> Option<Self> {
        let target = Self {
            fq_name: identity.fq_name.clone(),
            kind: AgentReplacementSymbolKind::parse(&identity.kind)?,
            declaration_file: identity.declaration_file.clone(),
            declaration_start_offset: u32::try_from(identity.declaration_start_offset).ok()?,
            containing_type: identity.containing_type.clone(),
        };
        target.is_valid().then_some(target)
    }

    fn is_valid(&self) -> bool {
        is_exact_replacement_name(&self.fq_name)
            && self.declaration_start_offset <= i32::MAX as u32
            && is_normalized_absolute_replacement_path(&self.declaration_file)
            && self
                .containing_type
                .as_ref()
                .is_none_or(|value| is_exact_replacement_name(value))
    }

    fn is_valid_replacement_target(&self) -> bool {
        self.is_valid() && self.kind.supports_replacement()
    }
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(transparent)]
struct AgentReplacementSemanticGeneration(u64);

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentReplacementDeclarationScope {
    start_offset: u32,
    end_offset: u32,
    start_line: u32,
    end_line: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    source_text: Option<String>,
}

impl AgentReplacementDeclarationScope {
    fn is_valid(&self) -> bool {
        self.start_offset < self.end_offset
            && self.end_offset <= i32::MAX as u32
            && self.start_line > 0
            && self.start_line <= self.end_line
            && self.end_line <= i32::MAX as u32
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentExactReplacementLocation {
    file_path: String,
    start_offset: u32,
    end_offset: u32,
    start_line: u32,
    start_column: u32,
    preview: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    usage_site_scope: Option<AgentReplacementDeclarationScope>,
}

impl AgentExactReplacementLocation {
    fn is_valid(&self) -> bool {
        is_normalized_absolute_replacement_path(&self.file_path)
            && self.start_offset < self.end_offset
            && self.end_offset <= i32::MAX as u32
            && self.start_line > 0
            && self.start_line <= i32::MAX as u32
            && self.start_column > 0
            && self.start_column <= i32::MAX as u32
            && !self.preview.is_empty()
            && self
                .usage_site_scope
                .as_ref()
                .is_none_or(AgentReplacementDeclarationScope::is_valid)
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentReplacementFileHash {
    file_path: String,
    hash: String,
}

impl AgentReplacementFileHash {
    fn is_valid_for(&self, source_file: &str) -> bool {
        self.file_path == source_file
            && is_normalized_absolute_replacement_path(&self.file_path)
            && is_lowercase_sha256(&self.hash)
    }
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentReplacementVisibility {
    Public,
    Protected,
    Internal,
    PackageProtected,
    PackagePrivate,
    Private,
    Local,
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentReplacementModality {
    Final,
    Sealed,
    Open,
    Abstract,
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentReplacementTypeVariance {
    Invariant,
    In,
    Out,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentReplacementTypeParameterSignature {
    name: String,
    upper_bounds: String,
    variance: AgentReplacementTypeVariance,
    reified: bool,
}

impl AgentReplacementTypeParameterSignature {
    fn is_valid(&self) -> bool {
        is_exact_replacement_name(&self.name) && !self.upper_bounds.trim().is_empty()
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentReplacementValueParameterSignature {
    name: String,
    #[serde(rename = "type")]
    parameter_type: String,
    vararg: bool,
    has_default_value: bool,
    noinline: bool,
    crossinline: bool,
}

impl AgentReplacementValueParameterSignature {
    fn is_valid(&self) -> bool {
        is_exact_replacement_name(&self.name) && !self.parameter_type.trim().is_empty()
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(tag = "type", rename_all_fields = "camelCase", deny_unknown_fields)]
enum AgentReplacementDeclarationSignature {
    #[serde(rename = "function")]
    Function {
        name: String,
        receiver_type: Option<String>,
        context_receiver_types: Vec<String>,
        type_parameters: Vec<AgentReplacementTypeParameterSignature>,
        value_parameters: Vec<AgentReplacementValueParameterSignature>,
        return_type: String,
        visibility: AgentReplacementVisibility,
        modality: AgentReplacementModality,
        has_stable_parameter_names: bool,
        suspend: bool,
        operator: bool,
        inline: bool,
        #[serde(rename = "override")]
        is_override: bool,
        infix: bool,
        #[serde(rename = "static")]
        is_static: bool,
        tailrec: bool,
        external: bool,
        expect: bool,
        actual: bool,
    },
    #[serde(rename = "property")]
    Property {
        name: String,
        receiver_type: Option<String>,
        context_receiver_types: Vec<String>,
        type_parameters: Vec<AgentReplacementTypeParameterSignature>,
        return_type: String,
        visibility: AgentReplacementVisibility,
        modality: AgentReplacementModality,
        getter_visibility: AgentReplacementVisibility,
        setter_visibility: Option<AgentReplacementVisibility>,
        has_getter: bool,
        has_setter: bool,
        has_backing_field: bool,
        is_val: bool,
        #[serde(rename = "const")]
        is_const: bool,
        lateinit: bool,
        delegated: bool,
        #[serde(rename = "override")]
        is_override: bool,
        #[serde(rename = "static")]
        is_static: bool,
        external: bool,
        expect: bool,
        actual: bool,
    },
}

impl AgentReplacementDeclarationSignature {
    fn is_valid_for(&self, kind: AgentReplacementSymbolKind) -> bool {
        match self {
            Self::Function {
                name,
                receiver_type,
                context_receiver_types,
                type_parameters,
                value_parameters,
                return_type,
                ..
            } => {
                kind == AgentReplacementSymbolKind::Function
                    && is_valid_replacement_signature_header(
                        name,
                        receiver_type.as_deref(),
                        context_receiver_types,
                        type_parameters,
                        return_type,
                    )
                    && value_parameters
                        .iter()
                        .all(AgentReplacementValueParameterSignature::is_valid)
            }
            Self::Property {
                name,
                receiver_type,
                context_receiver_types,
                type_parameters,
                return_type,
                ..
            } => {
                kind == AgentReplacementSymbolKind::Property
                    && is_valid_replacement_signature_header(
                        name,
                        receiver_type.as_deref(),
                        context_receiver_types,
                        type_parameters,
                        return_type,
                    )
            }
        }
    }
}

fn is_valid_replacement_signature_header(
    name: &str,
    receiver_type: Option<&str>,
    context_receiver_types: &[String],
    type_parameters: &[AgentReplacementTypeParameterSignature],
    return_type: &str,
) -> bool {
    is_exact_replacement_name(name)
        && receiver_type.is_none_or(|value| !value.trim().is_empty())
        && context_receiver_types
            .iter()
            .all(|value| !value.trim().is_empty())
        && type_parameters
            .iter()
            .all(AgentReplacementTypeParameterSignature::is_valid)
        && !return_type.trim().is_empty()
}

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

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentExactReplacementProof {
    target: AgentExactReplacementSymbolIdentity,
    required_generation: AgentReplacementSemanticGeneration,
    source_range: AgentExactReplacementLocation,
    file_hashes: Vec<AgentReplacementFileHash>,
    old_signature: AgentReplacementDeclarationSignature,
    proposed_signature: AgentReplacementDeclarationSignature,
    proposed_declaration_hash: AgentReplacementDeclarationSha256,
    proposed_declaration_length: usize,
    evidence: AgentExactReplacementEvidence,
    outbound_references: Vec<AgentExactReplacementOutboundReference>,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct AgentReplacementPostconditionEvidence {
    resulting_target: AgentExactReplacementSymbolIdentity,
    source_range: AgentExactReplacementLocation,
    signature: AgentReplacementDeclarationSignature,
    outbound_evidence: AgentExactReplacementEvidence,
    outbound_references: Vec<AgentExactReplacementOutboundReference>,
}

impl AgentExactReplacementProof {
    fn validate(&self, proposed_declaration: &str) -> std::result::Result<(), String> {
        if !self.target.is_valid_replacement_target() {
            return Err("exact replacement proof contained an invalid target identity".to_string());
        }
        if self.required_generation.0 > i64::MAX as u64 {
            return Err(
                "exact replacement proof contained an invalid semantic generation".to_string(),
            );
        }
        if !self.source_range.is_valid()
            || self.source_range.file_path != self.target.declaration_file
            || self.source_range.start_offset > self.target.declaration_start_offset
            || self.target.declaration_start_offset >= self.source_range.end_offset
        {
            return Err(
                "exact replacement source range did not contain the target declaration".to_string(),
            );
        }
        if self.file_hashes.len() != 1
            || !self.file_hashes[0].is_valid_for(&self.source_range.file_path)
        {
            return Err(
                "exact replacement proof did not contain one lowercase raw-byte source hash"
                    .to_string(),
            );
        }
        if self.old_signature != self.proposed_signature
            || !self.old_signature.is_valid_for(self.target.kind)
            || !self.proposed_signature.is_valid_for(self.target.kind)
        {
            return Err(
                "exact replacement proof did not retain one equal typed declaration signature"
                    .to_string(),
            );
        }
        let logical_length = proposed_declaration.encode_utf16().count();
        if logical_length == 0
            || logical_length > i32::MAX as usize
            || self.proposed_declaration_length != logical_length
            || !self.proposed_declaration_hash.matches(proposed_declaration)
        {
            return Err(
                "exact replacement proof disagreed with the proposed declaration hash or logical length"
                    .to_string(),
            );
        }
        if self.evidence.exact_count()? != self.outbound_references.len() {
            return Err(
                "exact replacement cardinality disagreed with its outbound occurrences".to_string(),
            );
        }
        let mut ranges = BTreeSet::new();
        for reference in &self.outbound_references {
            reference.validate_against(proposed_declaration)?;
            if !ranges.insert(reference.range_key()) {
                return Err(
                    "exact replacement proof repeated an outbound occurrence range".to_string(),
                );
            }
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentReplacementPreviewEdit {
    file_path: String,
    start_offset: u32,
    end_offset: u32,
    new_text: String,
}

impl AgentReplacementPreviewEdit {
    fn is_valid(&self) -> bool {
        is_normalized_absolute_replacement_path(&self.file_path)
            && self.start_offset < self.end_offset
            && self.end_offset <= i32::MAX as u32
            && !self.new_text.is_empty()
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct AgentReplacementPlanResult {
    edit: AgentReplacementPreviewEdit,
    proof: AgentExactReplacementProof,
    file_images: Vec<AgentExactFileImage>,
    schema_version: u32,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct AgentReplacementAuthority {
    target: AgentExactReplacementSymbolIdentity,
    proof: AgentExactReplacementProof,
    edits: Vec<AgentReplacementPreviewEdit>,
    file_images: Vec<AgentExactFileImage>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct AgentReplacementPostconditionAuthority {
    proof: AgentExactReplacementProof,
    edit: AgentReplacementPreviewEdit,
    images: Vec<AgentExactFileImage>,
}

impl AgentReplacementAuthority {
    pub(crate) fn from_projected_result(result: &Value) -> std::result::Result<Self, String> {
        let preview = result
            .pointer("/plan/preview")
            .cloned()
            .ok_or_else(|| "projected replacement plan omitted its exact preview".to_string())?;
        let preview: AgentReplacementPlanResult = serde_json::from_value(preview)
            .map_err(|error| format!("projected replacement preview was malformed: {error}"))?;
        preview.validate()?;
        Ok(preview.into_authority())
    }

    pub(crate) fn validate(&self) -> std::result::Result<(), String> {
        if self.target != self.proof.target || self.edits.len() != 1 {
            return Err("replacement authority disagreed with its exact target".to_string());
        }
        validate_exact_replacement_edit(&self.edits[0], &self.proof)?;
        validate_replacement_file_images(&self.file_images, &self.edits[0], &self.proof)?;
        Ok(())
    }

    pub(crate) fn proposed_content_sha256(&self) -> &str {
        &self.proof.proposed_declaration_hash.0
    }

    pub(crate) fn target_value(&self) -> Value {
        serde_json::to_value(&self.target).expect("typed replacement target is serializable")
    }

    pub(crate) fn file_images(&self) -> &[AgentExactFileImage] {
        &self.file_images
    }

    pub(crate) fn postcondition_authority(&self) -> AgentReplacementPostconditionAuthority {
        AgentReplacementPostconditionAuthority {
            proof: self.proof.clone(),
            edit: self.edits[0].clone(),
            images: self.file_images.clone(),
        }
    }

    pub(crate) fn minimum_postcondition_generation(&self) -> u64 {
        self.proof.required_generation.0
    }

    pub(crate) fn validate_postcondition_evidence(
        &self,
        result: &AgentReplacementPostconditionEvidence,
    ) -> std::result::Result<(), String> {
        self.validate()?;
        let edit = &self.edits[0];
        let resulting_length = u32::try_from(edit.new_text.encode_utf16().count())
            .map_err(|_| "replacement postcondition length overflowed".to_string())?;
        let expected_end = edit
            .start_offset
            .checked_add(resulting_length)
            .ok_or_else(|| "replacement postcondition range overflowed".to_string())?;
        if !result.resulting_target.is_valid_replacement_target()
            || result.resulting_target.fq_name != self.proof.target.fq_name
            || result.resulting_target.kind != self.proof.target.kind
            || result.resulting_target.declaration_file != self.proof.target.declaration_file
            || result.resulting_target.containing_type != self.proof.target.containing_type
            || result.resulting_target.declaration_start_offset < edit.start_offset
            || result.resulting_target.declaration_start_offset >= expected_end
            || !result.source_range.is_valid()
            || result.source_range.file_path != edit.file_path
            || result.source_range.start_offset != edit.start_offset
            || result.source_range.end_offset != expected_end
            || result.signature != self.proof.proposed_signature
            || result.outbound_references != self.proof.outbound_references
            || result.outbound_evidence != self.proof.evidence
            || result.outbound_evidence.exact_count()? != result.outbound_references.len()
        {
            return Err(
                "replacement postcondition changed its identity, range, signature, or exact outbound set"
                    .to_string(),
            );
        }
        Ok(())
    }
}

impl AgentReplacementPlanResult {
    pub(crate) fn validate(&self) -> std::result::Result<(), String> {
        if self.schema_version != SCHEMA_VERSION {
            return Err("replacement preview used an incompatible schema version".to_string());
        }
        validate_exact_replacement_edit(&self.edit, &self.proof)?;
        validate_replacement_file_images(&self.file_images, &self.edit, &self.proof)
    }

    fn validate_for_target(
        &self,
        expected_target: &AgentExactReplacementSymbolIdentity,
        proposed_declaration: &str,
    ) -> std::result::Result<(), String> {
        if &self.proof.target != expected_target {
            return Err(
                "replacement preview proof target disagreed with the selected compiler identity"
                    .to_string(),
            );
        }
        if self.edit.new_text != proposed_declaration {
            return Err(
                "replacement preview edit disagreed with the exact proposed declaration"
                    .to_string(),
            );
        }
        self.validate()
    }

    pub(crate) fn into_authority(self) -> AgentReplacementAuthority {
        AgentReplacementAuthority {
            target: self.proof.target.clone(),
            proof: self.proof,
            edits: vec![self.edit],
            file_images: self.file_images,
        }
    }
}

fn validate_exact_replacement_edit(
    edit: &AgentReplacementPreviewEdit,
    proof: &AgentExactReplacementProof,
) -> std::result::Result<(), String> {
    if !edit.is_valid()
        || edit.file_path != proof.source_range.file_path
        || edit.start_offset != proof.source_range.start_offset
        || edit.end_offset != proof.source_range.end_offset
    {
        return Err(
            "replacement preview edit disagreed with the exact proven source range".to_string(),
        );
    }
    proof.validate(&edit.new_text)
}

fn validate_replacement_file_images(
    images: &[AgentExactFileImage],
    edit: &AgentReplacementPreviewEdit,
    proof: &AgentExactReplacementProof,
) -> std::result::Result<(), String> {
    let exact_edits = [AgentExactFileEdit {
        file_path: &edit.file_path,
        start_offset: edit.start_offset,
        end_offset: edit.end_offset,
        new_text: &edit.new_text,
    }];
    let image_hashes = validate_exact_file_image_set(images, &exact_edits)?;
    let legacy_hashes = proof
        .file_hashes
        .iter()
        .map(|file_hash| (file_hash.file_path.clone(), file_hash.hash.clone()))
        .collect::<BTreeMap<_, _>>();
    if legacy_hashes.len() != proof.file_hashes.len() || legacy_hashes != image_hashes {
        return Err(
            "replacement proof file hashes disagreed with exact preimage authority".to_string(),
        );
    }
    Ok(())
}

fn is_exact_replacement_name(value: &str) -> bool {
    !value.is_empty() && value.trim() == value
}

fn is_normalized_absolute_replacement_path(value: &str) -> bool {
    let path = Path::new(value);
    path.is_absolute()
        && path.components().all(|component| {
            matches!(
                component,
                std::path::Component::RootDir | std::path::Component::Normal(_)
            )
        })
}

fn is_lowercase_sha256(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn replacement_sha256(bytes: &[u8]) -> String {
    use sha2::Digest as _;

    hex::encode(sha2::Sha256::digest(bytes))
}

fn utf16_range_equals(value: &str, start: u32, end: u32, expected: &str) -> bool {
    let units = value.encode_utf16().collect::<Vec<_>>();
    let expected = expected.encode_utf16().collect::<Vec<_>>();
    let Ok(start) = usize::try_from(start) else {
        return false;
    };
    let Ok(end) = usize::try_from(end) else {
        return false;
    };
    units
        .get(start..end)
        .is_some_and(|actual| actual == expected.as_slice())
}
