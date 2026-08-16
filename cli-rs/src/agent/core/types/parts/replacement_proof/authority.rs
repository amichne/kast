#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentExactReplacementProof {
    target: AgentExactReplacementSymbolIdentity,
    required_generation: AgentReplacementSemanticGeneration,
    source_range: AgentExactReplacementLocation,
    file_hashes: Vec<AgentReplacementFileHash>,
    compiler_context: AgentReplacementCompilerContext,
    old_signature: AgentReplacementDeclarationSignature,
    proposed_signature: AgentReplacementDeclarationSignature,
    proposed_declaration_hash: AgentReplacementDeclarationSha256,
    proposed_declaration_length: usize,
    proposed_body_hash: AgentReplacementBodySha256,
    proposed_body_length: usize,
    declaration_slice: AgentReplacementDeclarationSlice,
    proposed_body_slice: AgentReplacementSubmittedBodySlice,
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
    fn validate_body_authority(&self, proposed_body: &str) -> std::result::Result<(), String> {
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
            || self.target.declaration_start_offset >= self.source_range.start_offset
        {
            return Err(
                "exact replacement body range did not follow the target declaration identity"
                    .to_string(),
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
        self.compiler_context
            .validate(&self.target.declaration_file)?;
        if self.old_signature != self.proposed_signature
            || !self.old_signature.is_valid_for(self.target.kind)
            || !self.proposed_signature.is_valid_for(self.target.kind)
        {
            return Err(
                "exact replacement proof did not retain one equal typed declaration signature"
                    .to_string(),
            );
        }
        let logical_length = proposed_body.encode_utf16().count();
        if logical_length == 0
            || logical_length > i32::MAX as usize
            || self.proposed_body_length != logical_length
            || !self.proposed_body_hash.matches(proposed_body)
        {
            return Err(
                "exact replacement proof disagreed with the extracted body hash or logical length"
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
            if usize::try_from(reference.relative_end_offset)
                .map_or(true, |end| end > logical_length)
            {
                return Err(
                    "exact replacement outbound reference escaped the extracted body"
                        .to_string(),
                );
            }
            reference.validate_against(proposed_body)?;
            if !ranges.insert(reference.range_key()) {
                return Err(
                    "exact replacement proof repeated an outbound occurrence range".to_string(),
                );
            }
        }
        Ok(())
    }

    fn validate_request_content(
        &self,
        proposed_declaration: &str,
        proposed_body: &str,
    ) -> std::result::Result<(), String> {
        let logical_length = proposed_declaration.encode_utf16().count();
        if logical_length == 0
            || logical_length > i32::MAX as usize
            || self.proposed_declaration_length != logical_length
            || !self.proposed_declaration_hash.matches(proposed_declaration)
        {
            return Err(
                "exact replacement proof disagreed with the submitted declaration hash or logical length"
                    .to_string(),
            );
        }
        self.declaration_slice
            .validate_against(proposed_declaration)?;
        if !self.declaration_slice.contains(
            self.proposed_body_slice.start_offset,
            self.proposed_body_slice.end_offset,
        ) || self
            .proposed_body_slice
            .extract_from(proposed_declaration)?
            != proposed_body
        {
            return Err(
                "exact replacement body edit was not the copied-PSI body slice of the submitted declaration"
                    .to_string(),
            );
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

    pub(crate) fn validate_for_proposed_declaration(
        &self,
        proposed_declaration: &str,
    ) -> std::result::Result<(), String> {
        self.validate()?;
        self.proof
            .validate_request_content(proposed_declaration, &self.edits[0].new_text)
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
        if !result.resulting_target.is_valid_replacement_target()
            || result.resulting_target.fq_name != self.proof.target.fq_name
            || result.resulting_target.kind != self.proof.target.kind
            || result.resulting_target.declaration_file != self.proof.target.declaration_file
            || result.resulting_target.containing_type != self.proof.target.containing_type
            || result.resulting_target.declaration_start_offset
                != self.proof.target.declaration_start_offset
            || !result.source_range.is_valid()
            || result.source_range.file_path != edit.file_path
            || result.source_range != self.proof.source_range
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
        self.proof
            .validate_request_content(proposed_declaration, &self.edit.new_text)?;
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
