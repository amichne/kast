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
