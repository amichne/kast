#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RawVerifiedAddFilePlanPreview {
    target_path: String,
    proposed_content: String,
    generation: u64,
}

#[derive(Debug, Deserialize)]
#[serde(untagged)]
enum RawVerifiedAddFilePlanResult {
    Planned(RawVerifiedAddFilePlanResponse),
    Rejected(RawVerifiedAddFilePlanRejection),
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RawVerifiedAddFilePlanResponse {
    plan_id: String,
    plan_version: u64,
    stage: VerifiedAddFilePlanStage,
    operation: VerifiedAddFileOperation,
    preview: RawVerifiedAddFilePlanPreview,
    schema_version: u32,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RawVerifiedAddFilePlanRejection {
    failure: VerifiedAddFileFailure,
    operation: VerifiedAddFileOperation,
    schema_version: u32,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
enum VerifiedAddFilePlanResult {
    Planned(VerifiedAddFilePlanResponse),
    Rejected(VerifiedAddFilePlanRejection),
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
struct VerifiedAddFilePlanResponse {
    plan_id: crate::agent::public_protocol::VerifiedAddFilePlanId,
    plan_version: VerifiedAddFilePlanVersion,
    stage: VerifiedAddFilePlanStage,
    operation: VerifiedAddFileOperation,
    preview: RawVerifiedAddFilePlanPreview,
    schema_version: u32,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
struct VerifiedAddFilePlanRejection {
    failure: VerifiedAddFileFailure,
    operation: VerifiedAddFileOperation,
    schema_version: u32,
}

impl RawVerifiedAddFilePlanResult {
    fn admit(
        self,
        workspace_root: &str,
        target: &VerifiedAddFileTarget,
        source: &VerifiedAddFileSource,
    ) -> Result<VerifiedAddFilePlanResult> {
        match self {
            Self::Planned(response) => response
                .admit(workspace_root, target, source)
                .map(VerifiedAddFilePlanResult::Planned),
            Self::Rejected(rejection) => rejection
                .admit()
                .map(VerifiedAddFilePlanResult::Rejected),
        }
    }
}

impl RawVerifiedAddFilePlanResponse {
    fn admit(
        self,
        workspace_root: &str,
        target: &VerifiedAddFileTarget,
        source: &VerifiedAddFileSource,
    ) -> Result<VerifiedAddFilePlanResponse> {
        let plan_id = crate::agent::public_protocol::VerifiedAddFilePlanId::parse(&self.plan_id)
            .ok_or_else(|| {
                CliError::new(
                    "KAST_VERIFIED_ADD_FILE_PLAN_INVALID",
                    "The server did not issue a canonical af-prefixed add-file plan identity.",
                )
            })?;
        let plan_version = VerifiedAddFilePlanVersion::initial(self.plan_version)?;
        let expected_id = verified_add_file_plan_id(
            workspace_root,
            target,
            source,
            self.preview.generation,
        )?;
        if self.schema_version != crate::SCHEMA_VERSION
            || self.stage != VerifiedAddFilePlanStage::AwaitingApproval
            || plan_id != expected_id
            || self.preview.target_path != target.as_str()
            || self.preview.proposed_content != source.as_str()
        {
            return Err(CliError::new(
                "KAST_VERIFIED_ADD_FILE_PLAN_INVALID",
                "The server-issued plan did not bind the exact target, postimage, and public schema.",
            ));
        }
        Ok(VerifiedAddFilePlanResponse {
            plan_id,
            plan_version,
            stage: self.stage,
            operation: self.operation,
            preview: self.preview,
            schema_version: self.schema_version,
        })
    }
}

impl RawVerifiedAddFilePlanRejection {
    fn admit(self) -> Result<VerifiedAddFilePlanRejection> {
        (self.schema_version == crate::SCHEMA_VERSION)
            .then_some(VerifiedAddFilePlanRejection {
                failure: self.failure,
                operation: self.operation,
                schema_version: self.schema_version,
            })
            .ok_or_else(|| {
                CliError::new(
                    "KAST_VERIFIED_ADD_FILE_PLAN_INVALID",
                    "The server-issued plan rejection did not preserve the public schema.",
                )
            })
    }
}

fn verified_add_file_plan_id(
    workspace_root: &str,
    target: &VerifiedAddFileTarget,
    source: &VerifiedAddFileSource,
    generation: u64,
) -> Result<crate::agent::public_protocol::VerifiedAddFilePlanId> {
    let identity = format!(
        "{workspace_root}\0{}\0{}\0{generation}",
        target.as_str(),
        source.as_str(),
    );
    let encoded = format!("af-{}", manifest::sha256_bytes(identity.as_bytes()));
    crate::agent::public_protocol::VerifiedAddFilePlanId::parse(&encoded).ok_or_else(|| {
        CliError::new(
            "KAST_VERIFIED_ADD_FILE_PLAN_INVALID",
            "The deterministic add-file plan identity was not canonical.",
        )
    })
}
