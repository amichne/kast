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
