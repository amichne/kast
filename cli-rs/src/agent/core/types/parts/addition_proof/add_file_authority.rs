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
