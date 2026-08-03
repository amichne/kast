#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentAdditionSymbolKind {
    Class,
    Interface,
    Object,
    Function,
    Property,
    Parameter,
    Unknown,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentAdditionSymbolIdentity {
    fq_name: String,
    kind: AgentAdditionSymbolKind,
    declaration_file: String,
    declaration_start_offset: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    containing_type: Option<String>,
}

impl AgentAdditionSymbolIdentity {
    fn validate(&self) -> std::result::Result<(), String> {
        if self.fq_name.trim().is_empty()
            || self.kind == AgentAdditionSymbolKind::Unknown
            || !is_normalized_absolute_exact_file_path(&self.declaration_file)
            || self.declaration_start_offset > i32::MAX as u32
            || self
                .containing_type
                .as_ref()
                .is_some_and(|value| value.trim().is_empty())
        {
            return Err("addition proof contained an invalid compiler symbol identity".to_string());
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(tag = "type", deny_unknown_fields)]
enum AgentAdditionKotlinPackage {
    #[serde(rename = "ROOT")]
    Root,
    #[serde(rename = "NAMED")]
    Named { segments: Vec<String> },
}

impl AgentAdditionKotlinPackage {
    fn validate(&self) -> std::result::Result<(), String> {
        match self {
            Self::Root => Ok(()),
            Self::Named { segments }
                if !segments.is_empty()
                    && segments.iter().all(|segment| {
                        !segment.is_empty() && !segment.chars().any(char::is_control)
                    }) =>
            {
                Ok(())
            }
            Self::Named { .. } => {
                Err("addition proof contained an invalid Kotlin package".to_string())
            }
        }
    }

    fn collision_key(&self) -> String {
        match self {
            Self::Root => "ROOT".to_string(),
            Self::Named { segments } => format!("NAMED:{}", segments.join("\u{0}")),
        }
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentAdditionSourceOwner {
    source_root: String,
    idea_module_name: String,
    gradle_build_root: String,
    gradle_project_path: String,
    source_set_name: String,
}

impl AgentAdditionSourceOwner {
    fn validate_for(&self, target_path: &str) -> std::result::Result<(), String> {
        if !is_normalized_absolute_exact_file_path(&self.source_root)
            || !is_normalized_absolute_exact_file_path(&self.gradle_build_root)
            || !strict_descendant(&self.source_root, &self.gradle_build_root)
            || !strict_descendant(target_path, &self.source_root)
            || !is_canonical_nonblank(&self.idea_module_name)
            || !is_valid_gradle_project_path(&self.gradle_project_path)
            || !is_canonical_nonblank(&self.source_set_name)
            || self.source_set_name.contains(['/', '\\', ':'])
        {
            return Err("addition proof contained invalid source ownership".to_string());
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentAdditionDeclarationKind {
    Class,
    Interface,
    Object,
    EnumClass,
    AnnotationClass,
    Function,
    Property,
    TypeAlias,
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentAdditionRelativeRange {
    start_offset: u32,
    end_offset: u32,
}

impl AgentAdditionRelativeRange {
    fn validate(&self) -> bool {
        self.start_offset < self.end_offset && self.end_offset <= i32::MAX as u32
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentAdditionDeclaration {
    package_identity: AgentAdditionKotlinPackage,
    name: String,
    kind: AgentAdditionDeclarationKind,
    relative_range: AgentAdditionRelativeRange,
    collision_signature: String,
}

impl AgentAdditionDeclaration {
    fn validate_for(
        &self,
        package_identity: &AgentAdditionKotlinPackage,
        content_length: usize,
    ) -> std::result::Result<(), String> {
        if &self.package_identity != package_identity
            || self.name.is_empty()
            || self.name.chars().any(char::is_control)
            || !self.relative_range.validate()
            || self.relative_range.end_offset as usize > content_length
            || !is_lowercase_exact_file_sha256(&self.collision_signature)
        {
            return Err("addition proof contained an invalid top-level declaration".to_string());
        }
        self.package_identity.validate()
    }

    fn collision_key(&self) -> String {
        let category = match self.kind {
            AgentAdditionDeclarationKind::Class
            | AgentAdditionDeclarationKind::Interface
            | AgentAdditionDeclarationKind::Object
            | AgentAdditionDeclarationKind::EnumClass
            | AgentAdditionDeclarationKind::AnnotationClass
            | AgentAdditionDeclarationKind::TypeAlias => "CLASSIFIER".to_string(),
            AgentAdditionDeclarationKind::Function => {
                format!("FUNCTION:{}", self.collision_signature)
            }
            AgentAdditionDeclarationKind::Property => {
                format!("PROPERTY:{}", self.collision_signature)
            }
        };
        format!(
            "{}\u{0}{}\u{0}{category}",
            self.package_identity.collision_key(),
            self.name
        )
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(tag = "type", rename_all_fields = "camelCase", deny_unknown_fields)]
enum AgentAdditionResolvedTarget {
    #[serde(rename = "SOURCE")]
    Source {
        identity: AgentAdditionSymbolIdentity,
    },
    #[serde(rename = "EXTERNAL")]
    External {
        fq_name: String,
        kind: AgentAdditionSymbolKind,
        compiler_signature: String,
    },
}

impl AgentAdditionResolvedTarget {
    fn validate(&self) -> std::result::Result<(), String> {
        match self {
            Self::Source { identity } => identity.validate(),
            Self::External {
                fq_name,
                kind,
                compiler_signature,
            } if is_canonical_nonblank(fq_name)
                && *kind != AgentAdditionSymbolKind::Unknown
                && is_canonical_nonblank(compiler_signature) =>
            {
                Ok(())
            }
            Self::External { .. } => {
                Err("addition proof contained an invalid external compiler target".to_string())
            }
        }
    }

    fn source_file_path(&self) -> Option<&str> {
        match self {
            Self::Source { identity } => Some(&identity.declaration_file),
            Self::External { .. } => None,
        }
    }
}

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

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentExactAddFileProof {
    target_path: String,
    target_state: AgentAddFileTargetState,
    owner: AgentAdditionSourceOwner,
    package_identity: AgentAdditionKotlinPackage,
    declarations: Vec<AgentAdditionDeclaration>,
    context: AgentAdditionProofContext,
    collision_evidence: AgentAdditionCollisionEvidence,
    outbound_evidence: AgentAdditionOutboundEvidence,
    rebinding_baseline: AgentAdditionRebindingBaseline,
    postimage_sha256: String,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct AgentAddFilePostconditionEvidence {
    owner: AgentAdditionSourceOwner,
    package_identity: AgentAdditionKotlinPackage,
    declarations: Vec<AgentAdditionDeclaration>,
    outbound_evidence: AgentAdditionOutboundEvidence,
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentAddFileTargetState {
    Absent,
}

impl AgentExactAddFileProof {
    fn validate(&self, content_length: usize) -> std::result::Result<(), String> {
        validate_addition_target_path(&self.target_path)?;
        self.owner.validate_for(&self.target_path)?;
        self.package_identity.validate()?;
        if self.target_state != AgentAddFileTargetState::Absent
            || self.declarations.is_empty()
            || !is_lowercase_exact_file_sha256(&self.postimage_sha256)
        {
            return Err("add-file proof did not retain an exact absent target".to_string());
        }
        validate_addition_declarations(
            &self.declarations,
            &self.package_identity,
            content_length,
        )?;
        self.collision_evidence.validate(self.declarations.len())?;
        self.outbound_evidence.validate(content_length)?;
        self.rebinding_baseline.validate()?;
        let context = self.context.validate()?;
        if context.contains_key(self.target_path.as_str()) {
            return Err("absent add-file target appeared in source context".to_string());
        }
        validate_addition_context_coverage(
            &context,
            &self.outbound_evidence,
            &self.rebinding_baseline,
        )
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentCompilerFileBottomInsertion {
    offset: u32,
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentAdditionNewlinePolicy {
    PreserveExistingAppendBlankLineFinalLf,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentExactAddDeclarationProof {
    target_path: String,
    target_preimage_sha256: String,
    owner: AgentAdditionSourceOwner,
    package_identity: AgentAdditionKotlinPackage,
    declaration: AgentAdditionDeclaration,
    insertion: AgentCompilerFileBottomInsertion,
    newline_policy: AgentAdditionNewlinePolicy,
    context: AgentAdditionProofContext,
    collision_evidence: AgentAdditionCollisionEvidence,
    outbound_evidence: AgentAdditionOutboundEvidence,
    rebinding_baseline: AgentAdditionRebindingBaseline,
    postimage_sha256: String,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct AgentAddDeclarationPostconditionEvidence {
    owner: AgentAdditionSourceOwner,
    package_identity: AgentAdditionKotlinPackage,
    declaration: AgentAdditionDeclaration,
    outbound_evidence: AgentAdditionOutboundEvidence,
}

impl AgentExactAddDeclarationProof {
    fn validate(
        &self,
        declaration_length: usize,
        normalized_preimage_length: usize,
    ) -> std::result::Result<(), String> {
        validate_addition_target_path(&self.target_path)?;
        self.owner.validate_for(&self.target_path)?;
        self.package_identity.validate()?;
        if !is_lowercase_exact_file_sha256(&self.target_preimage_sha256)
            || !is_lowercase_exact_file_sha256(&self.postimage_sha256)
            || self.insertion.offset > i32::MAX as u32
            || self.insertion.offset as usize != normalized_preimage_length
            || self.newline_policy
                != AgentAdditionNewlinePolicy::PreserveExistingAppendBlankLineFinalLf
        {
            return Err("add-declaration proof did not retain exact FILE_BOTTOM authority".to_string());
        }
        self.declaration
            .validate_for(&self.package_identity, declaration_length)?;
        self.collision_evidence.validate(1)?;
        self.outbound_evidence.validate(declaration_length)?;
        self.rebinding_baseline.validate()?;
        let context = self.context.validate()?;
        if context.get(self.target_path.as_str())
            != Some(&self.target_preimage_sha256.as_str())
        {
            return Err("add-declaration target context did not match its preimage".to_string());
        }
        validate_addition_context_coverage(
            &context,
            &self.outbound_evidence,
            &self.rebinding_baseline,
        )
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct AgentAddFilePlanResult {
    proposed_content: String,
    postimage: AgentExactByteImage,
    proof: AgentExactAddFileProof,
    schema_version: u32,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct AgentAddFileAuthority {
    postimage: AgentExactByteImage,
    proof: AgentExactAddFileProof,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct AgentAddFilePostconditionAuthority {
    proof: AgentExactAddFileProof,
    postimage: AgentExactByteImage,
}

impl AgentAddFilePlanResult {
    pub(crate) fn validate_for(
        &self,
        target_path: &str,
        proposed_content: &str,
    ) -> std::result::Result<(), String> {
        validate_strict_addition_text(proposed_content, true)?;
        if self.schema_version != SCHEMA_VERSION
            || self.proposed_content != proposed_content
            || self.proof.target_path != target_path
        {
            return Err("add-file preview disagreed with its exact request".to_string());
        }
        let postimage = self.postimage.validate()?;
        if postimage != proposed_content.as_bytes()
            || self.postimage.sha256 != self.proof.postimage_sha256
        {
            return Err("add-file preview content disagreed with its exact postimage".to_string());
        }
        self.proof.validate(proposed_content.encode_utf16().count())
    }

    pub(crate) fn into_authority(self) -> AgentAddFileAuthority {
        AgentAddFileAuthority {
            postimage: self.postimage,
            proof: self.proof,
        }
    }
}

impl AgentAddFileAuthority {
    pub(crate) fn from_projected_result(result: &Value) -> std::result::Result<Self, String> {
        let preview = result
            .pointer("/plan/preview")
            .cloned()
            .ok_or_else(|| "projected add-file plan omitted its exact preview".to_string())?;
        let preview: AgentAddFilePlanResult = serde_json::from_value(preview)
            .map_err(|error| format!("projected add-file preview was malformed: {error}"))?;
        let target = preview.proof.target_path.clone();
        let proposed = preview.proposed_content.clone();
        preview.validate_for(&target, &proposed)?;
        Ok(preview.into_authority())
    }

    pub(crate) fn validate(&self) -> std::result::Result<(), String> {
        let postimage = self.postimage.validate()?;
        let proposed = std::str::from_utf8(&postimage)
            .map_err(|_| "add-file postimage was not strict UTF-8".to_string())?;
        validate_strict_addition_text(proposed, true)?;
        if self.postimage.sha256 != self.proof.postimage_sha256 {
            return Err("add-file authority postimage disagreed with its proof".to_string());
        }
        self.proof.validate(proposed.encode_utf16().count())
    }

    pub(crate) fn target_path(&self) -> &str {
        &self.proof.target_path
    }

    pub(crate) fn proposed_content_sha256(&self) -> &str {
        &self.postimage.sha256
    }

    pub(crate) fn postimage(&self) -> &AgentExactByteImage {
        &self.postimage
    }

    pub(crate) fn postcondition_authority(&self) -> AgentAddFilePostconditionAuthority {
        AgentAddFilePostconditionAuthority {
            proof: self.proof.clone(),
            postimage: self.postimage.clone(),
        }
    }

    pub(crate) fn minimum_postcondition_generation(&self) -> u64 {
        self.proof.context.required_generation
    }

    pub(crate) fn validate_postcondition_evidence(
        &self,
        result: &AgentAddFilePostconditionEvidence,
    ) -> std::result::Result<(), String> {
        self.validate()?;
        if result.owner != self.proof.owner
            || result.package_identity != self.proof.package_identity
            || result.declarations != self.proof.declarations
            || result.outbound_evidence != self.proof.outbound_evidence
        {
            return Err(
                "add-file postcondition changed its owner, package, declarations, or outbound set"
                    .to_string(),
            );
        }
        let postimage = self.postimage.validate()?;
        let content = std::str::from_utf8(&postimage)
            .map_err(|_| "add-file postcondition image was not UTF-8".to_string())?;
        result
            .outbound_evidence
            .validate(content.encode_utf16().count())
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct AgentAddDeclarationPlanResult {
    proposed_declaration: String,
    proposed_content: String,
    image: AgentExactFileImage,
    proof: AgentExactAddDeclarationProof,
    schema_version: u32,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct AgentAddDeclarationAuthority {
    image: AgentExactFileImage,
    proof: AgentExactAddDeclarationProof,
    proposed_declaration_sha256: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct AgentAddDeclarationPostconditionAuthority {
    proof: AgentExactAddDeclarationProof,
    image: AgentExactFileImage,
}

impl AgentAddDeclarationPlanResult {
    pub(crate) fn validate_for(
        &self,
        target_path: &str,
        expected_current_sha256: &str,
        proposed_declaration: &str,
    ) -> std::result::Result<(), String> {
        validate_strict_addition_text(proposed_declaration, false)?;
        if self.schema_version != SCHEMA_VERSION
            || self.proposed_declaration != proposed_declaration
            || self.proof.target_path != target_path
            || self.proof.target_preimage_sha256 != expected_current_sha256
            || self.image.file_path != target_path
        {
            return Err("add-declaration preview disagreed with its exact request".to_string());
        }
        let (preimage, postimage) = self.image.decode()?;
        let proposed_content = std::str::from_utf8(&postimage)
            .map_err(|_| "add-declaration postimage was not strict UTF-8".to_string())?;
        if self.proposed_content != proposed_content
            || self.image.preimage.sha256 != self.proof.target_preimage_sha256
            || self.image.postimage.sha256 != self.proof.postimage_sha256
        {
            return Err("add-declaration preview image disagreed with its proof".to_string());
        }
        validate_exact_add_declaration_append(&preimage, &postimage, proposed_declaration)?;
        let normalized_length = normalized_addition_preimage(&preimage)?
            .encode_utf16()
            .count();
        self.proof.validate(
            proposed_declaration.encode_utf16().count(),
            normalized_length,
        )
    }

    pub(crate) fn into_authority(self) -> AgentAddDeclarationAuthority {
        AgentAddDeclarationAuthority {
            image: self.image,
            proof: self.proof,
            proposed_declaration_sha256: exact_file_sha256(
                self.proposed_declaration.as_bytes(),
            ),
        }
    }
}

impl AgentAddDeclarationAuthority {
    pub(crate) fn from_projected_result(result: &Value) -> std::result::Result<Self, String> {
        let preview = result
            .pointer("/plan/preview")
            .cloned()
            .ok_or_else(|| "projected add-declaration plan omitted its exact preview".to_string())?;
        let preview: AgentAddDeclarationPlanResult = serde_json::from_value(preview)
            .map_err(|error| format!("projected add-declaration preview was malformed: {error}"))?;
        let target = preview.proof.target_path.clone();
        let expected = preview.proof.target_preimage_sha256.clone();
        let proposed = preview.proposed_declaration.clone();
        preview.validate_for(&target, &expected, &proposed)?;
        Ok(preview.into_authority())
    }

    pub(crate) fn validate(&self) -> std::result::Result<(), String> {
        if !is_lowercase_exact_file_sha256(&self.proposed_declaration_sha256)
            || self.image.file_path != self.proof.target_path
            || self.image.preimage.sha256 != self.proof.target_preimage_sha256
            || self.image.postimage.sha256 != self.proof.postimage_sha256
        {
            return Err("add-declaration authority image disagreed with its proof".to_string());
        }
        let (preimage, postimage) = self.image.decode()?;
        let declaration = extract_exact_add_declaration(&preimage, &postimage)?;
        if exact_file_sha256(declaration.as_bytes()) != self.proposed_declaration_sha256 {
            return Err("add-declaration authority declaration digest changed".to_string());
        }
        validate_strict_addition_text(&declaration, false)?;
        let normalized_length = normalized_addition_preimage(&preimage)?
            .encode_utf16()
            .count();
        self.proof
            .validate(declaration.encode_utf16().count(), normalized_length)
    }

    pub(crate) fn target_path(&self) -> &str {
        &self.proof.target_path
    }

    pub(crate) fn proposed_content_sha256(&self) -> &str {
        &self.proposed_declaration_sha256
    }

    pub(crate) fn file_image(&self) -> &AgentExactFileImage {
        &self.image
    }

    pub(crate) fn expected_current_sha256(&self) -> &str {
        &self.proof.target_preimage_sha256
    }

    pub(crate) fn postcondition_authority(&self) -> AgentAddDeclarationPostconditionAuthority {
        AgentAddDeclarationPostconditionAuthority {
            proof: self.proof.clone(),
            image: self.image.clone(),
        }
    }

    pub(crate) fn minimum_postcondition_generation(&self) -> u64 {
        self.proof.context.required_generation
    }

    pub(crate) fn validate_postcondition_evidence(
        &self,
        result: &AgentAddDeclarationPostconditionEvidence,
    ) -> std::result::Result<(), String> {
        self.validate()?;
        if result.owner != self.proof.owner
            || result.package_identity != self.proof.package_identity
            || result.declaration != self.proof.declaration
            || result.outbound_evidence != self.proof.outbound_evidence
        {
            return Err(
                "add-declaration postcondition changed its owner, package, declaration, or outbound set"
                    .to_string(),
            );
        }
        let (preimage, postimage) = self.image.decode()?;
        let declaration = extract_exact_add_declaration(&preimage, &postimage)?;
        result
            .outbound_evidence
            .validate(declaration.encode_utf16().count())
    }
}

fn validate_addition_declarations(
    declarations: &[AgentAdditionDeclaration],
    package_identity: &AgentAdditionKotlinPackage,
    content_length: usize,
) -> std::result::Result<(), String> {
    let mut previous: Option<&AgentAdditionRelativeRange> = None;
    let mut collision_keys = BTreeSet::new();
    for declaration in declarations {
        declaration.validate_for(package_identity, content_length)?;
        let range = &declaration.relative_range;
        if !collision_keys.insert(declaration.collision_key())
            || previous.is_some_and(|prior| {
                prior.start_offset > range.start_offset || prior.end_offset > range.start_offset
            })
        {
            return Err("addition declarations were not unique and ordered".to_string());
        }
        previous = Some(range);
    }
    Ok(())
}

fn validate_addition_context_coverage(
    context: &BTreeMap<&str, &str>,
    outbound: &AgentAdditionOutboundEvidence,
    rebinding: &AgentAdditionRebindingBaseline,
) -> std::result::Result<(), String> {
    let mut required_paths = BTreeSet::new();
    for occurrence in &outbound.occurrences {
        if let Some(path) = occurrence.resolved_target.source_file_path() {
            required_paths.insert(path);
        }
    }
    for occurrence in &rebinding.occurrences {
        required_paths.insert(occurrence.range.file_path.as_str());
        if let Some(path) = occurrence.current_target.source_file_path() {
            required_paths.insert(path);
        }
    }
    if required_paths.iter().any(|path| !context.contains_key(path)) {
        return Err("addition context did not cover every compiler occurrence".to_string());
    }
    Ok(())
}

fn validate_addition_target_path(value: &str) -> std::result::Result<(), String> {
    if !is_normalized_absolute_exact_file_path(value)
        || !value.ends_with(".kt")
        || value.ends_with(".kts")
    {
        return Err("addition target was not one normalized absolute Kotlin file".to_string());
    }
    Ok(())
}

fn validate_strict_addition_text(
    value: &str,
    allow_final_lf: bool,
) -> std::result::Result<(), String> {
    if value.trim().is_empty()
        || value.contains('\r')
        || value.contains('\u{feff}')
        || (!allow_final_lf && value.ends_with('\n'))
    {
        return Err("addition source was not strict normalized non-blank Kotlin text".to_string());
    }
    Ok(())
}

fn normalized_addition_preimage(bytes: &[u8]) -> std::result::Result<String, String> {
    let decoded = std::str::from_utf8(bytes)
        .map_err(|_| "add-declaration preimage was not strict UTF-8".to_string())?;
    Ok(decoded
        .strip_prefix('\u{feff}')
        .unwrap_or(decoded)
        .replace("\r\n", "\n")
        .replace('\r', "\n"))
}

fn exact_add_declaration_separator(normalized_preimage: &str) -> &'static str {
    if normalized_preimage.is_empty() || normalized_preimage.ends_with("\n\n") {
        ""
    } else if normalized_preimage.ends_with('\n') {
        "\n"
    } else {
        "\n\n"
    }
}

fn validate_exact_add_declaration_append(
    preimage: &[u8],
    postimage: &[u8],
    declaration: &str,
) -> std::result::Result<(), String> {
    let normalized = normalized_addition_preimage(preimage)?;
    let mut expected = preimage.to_vec();
    expected.extend_from_slice(exact_add_declaration_separator(&normalized).as_bytes());
    expected.extend_from_slice(declaration.as_bytes());
    expected.push(b'\n');
    if expected != postimage {
        return Err("add-declaration postimage violated the exact FILE_BOTTOM LF policy".to_string());
    }
    Ok(())
}

#[cfg(test)]
mod exact_addition_rebinding_tests {
    use super::*;

    #[test]
    fn accepted_addition_authority_requires_zero_rebinding_candidates() {
        let baseline = AgentAdditionRebindingBaseline {
            cardinality: 1,
            dimensions: COMPLETE_ADDITION_REBINDING_DIMENSIONS.to_vec(),
            occurrences: vec![AgentAdditionRebindingOccurrence {
                range: AgentAdditionWorkspaceRange {
                    file_path: "/workspace/src/Use.kt".to_string(),
                    start_offset: 1,
                    end_offset: 2,
                },
                current_target: AgentAdditionRebindingCurrentTarget::Unresolved {
                    reason: AgentAdditionUnresolvedReason::NotFound,
                },
                provenance: AgentAdditionOccurrenceProvenance::Compiler,
            }],
        };

        assert!(baseline.validate().is_err());
    }
}

fn extract_exact_add_declaration(
    preimage: &[u8],
    postimage: &[u8],
) -> std::result::Result<String, String> {
    let normalized = normalized_addition_preimage(preimage)?;
    let separator = exact_add_declaration_separator(&normalized).as_bytes();
    let suffix = postimage
        .strip_prefix(preimage)
        .and_then(|suffix| suffix.strip_prefix(separator))
        .and_then(|suffix| suffix.strip_suffix(b"\n"))
        .ok_or_else(|| {
            "add-declaration authority violated the exact FILE_BOTTOM LF policy".to_string()
        })?;
    let declaration = std::str::from_utf8(suffix)
        .map_err(|_| "add-declaration declaration was not strict UTF-8".to_string())?
        .to_string();
    validate_exact_add_declaration_append(preimage, postimage, &declaration)?;
    Ok(declaration)
}

fn strict_descendant(child: &str, parent: &str) -> bool {
    let child = Path::new(child);
    let parent = Path::new(parent);
    child != parent && child.starts_with(parent)
}

fn is_canonical_nonblank(value: &str) -> bool {
    !value.trim().is_empty()
        && value == value.trim()
        && !value.chars().any(char::is_control)
}

fn is_valid_gradle_project_path(value: &str) -> bool {
    value.starts_with(':')
        && !value.contains(['/', '\\'])
        && !value.chars().any(char::is_control)
        && (value == ":"
            || (!value.ends_with(':') && value[1..].split(':').all(|segment| !segment.is_empty())))
}
