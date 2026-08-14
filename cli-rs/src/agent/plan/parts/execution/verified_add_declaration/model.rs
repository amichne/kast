const VERIFIED_ADD_DECLARATION_STORE_SCHEMA_VERSION: u32 = 1;
const VERIFIED_ADD_DECLARATION_INITIAL_VERSION: u64 = 0;
const VERIFIED_ADD_DECLARATION_TERMINAL_VERSION: u64 = 5;

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(transparent)]
struct VerifiedAddDeclarationPlanVersion(u64);

impl VerifiedAddDeclarationPlanVersion {
    fn initial(value: u64) -> Result<Self> {
        (value == VERIFIED_ADD_DECLARATION_INITIAL_VERSION)
            .then_some(Self(value))
            .ok_or_else(|| {
                CliError::new(
                    "KAST_VERIFIED_ADD_DECLARATION_PLAN_INVALID",
                    "The server-issued add-declaration plan was not at its initial durable version.",
                )
            })
    }

    fn value(self) -> u64 {
        self.0
    }
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(transparent)]
struct VerifiedAddDeclarationSha256(String);

impl VerifiedAddDeclarationSha256 {
    fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(transparent)]
struct VerifiedAddDeclarationTarget(String);

impl VerifiedAddDeclarationTarget {
    fn admit(workspace_root: &Path, requested: PathBuf) -> Result<Self> {
        let metadata = fs::symlink_metadata(&requested).map_err(|error| {
            CliError::new(
                "KAST_VERIFIED_ADD_DECLARATION_TARGET_INVALID",
                format!("The add-declaration target could not be inspected: {error}"),
            )
        })?;
        if metadata.file_type().is_symlink() || !metadata.is_file() {
            return Err(CliError::new(
                "KAST_VERIFIED_ADD_DECLARATION_TARGET_INVALID",
                "The add-declaration target must be one regular non-symlink Kotlin file.",
            ));
        }
        let canonical = requested.canonicalize()?;
        let relative = canonical.strip_prefix(workspace_root).map_err(|_| {
            CliError::new(
                "KAST_VERIFIED_ADD_DECLARATION_TARGET_INVALID",
                "The add-declaration target is outside the exact workspace root.",
            )
        })?;
        if relative.as_os_str().is_empty()
            || canonical.extension().and_then(|value| value.to_str()) != Some("kt")
        {
            return Err(CliError::new(
                "KAST_VERIFIED_ADD_DECLARATION_TARGET_INVALID",
                "The add-declaration target must be one workspace-owned .kt file.",
            ));
        }
        canonical
            .to_str()
            .map(|value| Self(value.to_string()))
            .ok_or_else(|| {
                CliError::new(
                    "KAST_VERIFIED_ADD_DECLARATION_TARGET_INVALID",
                    "The canonical add-declaration target is not exact UTF-8.",
                )
            })
    }

    fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct VerifiedAddDeclarationSource(String);

impl VerifiedAddDeclarationSource {
    fn admit(content: PreparedPlanContent) -> Result<Self> {
        let value = String::from_utf8(content.bytes).map_err(|_| {
            CliError::new(
                "KAST_VERIFIED_ADD_DECLARATION_SOURCE_INVALID",
                "The proposed declaration must be exact UTF-8 text.",
            )
        })?;
        crate::agent::validate_strict_addition_text(&value, false).map_err(|message| {
            CliError::new("KAST_VERIFIED_ADD_DECLARATION_SOURCE_INVALID", message)
        })?;
        Ok(Self(value))
    }

    fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum VerifiedAddDeclarationPlanStage {
    AwaitingApproval,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "kebab-case")]
enum VerifiedAddDeclarationOperation {
    AddDeclaration,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RawVerifiedAddDeclarationPlanPreview {
    target_path: String,
    proposed_declaration: String,
    generation: u64,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RawVerifiedAddDeclarationPlanResponse {
    plan_id: String,
    plan_version: u64,
    stage: VerifiedAddDeclarationPlanStage,
    operation: VerifiedAddDeclarationOperation,
    preview: RawVerifiedAddDeclarationPlanPreview,
    schema_version: u32,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
struct VerifiedAddDeclarationPlanResponse {
    plan_id: crate::agent::public_protocol::VerifiedAddDeclarationPlanId,
    plan_version: VerifiedAddDeclarationPlanVersion,
    stage: VerifiedAddDeclarationPlanStage,
    operation: VerifiedAddDeclarationOperation,
    preview: RawVerifiedAddDeclarationPlanPreview,
    schema_version: u32,
}

impl RawVerifiedAddDeclarationPlanResponse {
    fn admit(
        self,
        target: &VerifiedAddDeclarationTarget,
        source: &VerifiedAddDeclarationSource,
    ) -> Result<VerifiedAddDeclarationPlanResponse> {
        let plan_id = crate::agent::public_protocol::VerifiedAddDeclarationPlanId::parse(
            &self.plan_id,
        )
        .ok_or_else(|| {
            CliError::new(
                "KAST_VERIFIED_ADD_DECLARATION_PLAN_INVALID",
                "The server did not issue a canonical lowercase SHA-256 plan identity.",
            )
        })?;
        let plan_version = VerifiedAddDeclarationPlanVersion::initial(self.plan_version)?;
        if self.schema_version != crate::SCHEMA_VERSION
            || self.preview.target_path != target.as_str()
            || self.preview.proposed_declaration != source.as_str()
        {
            return Err(CliError::new(
                "KAST_VERIFIED_ADD_DECLARATION_PLAN_INVALID",
                "The server-issued plan did not bind the exact target, declaration, and public schema.",
            ));
        }
        Ok(VerifiedAddDeclarationPlanResponse {
            plan_id,
            plan_version,
            stage: self.stage,
            operation: self.operation,
            preview: self.preview,
            schema_version: self.schema_version,
        })
    }
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct StoredVerifiedAddDeclarationPlan {
    schema_version: u32,
    workspace_root: String,
    plan_id: crate::agent::public_protocol::VerifiedAddDeclarationPlanId,
    plan_version: VerifiedAddDeclarationPlanVersion,
    target_path: VerifiedAddDeclarationTarget,
    planned_generation: u64,
    state: StoredVerifiedAddDeclarationState,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(
    tag = "state",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
enum StoredVerifiedAddDeclarationState {
    AwaitingApproval,
    Terminal { receipt: VerifiedAddDeclarationReceipt },
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum VerifiedAddDeclarationOutcome {
    Verified,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct VerifiedAddDeclarationPublication {
    generation: u64,
    workspace_state_identity: String,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct VerifiedAddDeclarationSourceRange {
    start_offset: u64,
    end_offset: u64,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum VerifiedAddDeclarationKind {
    Class,
    Interface,
    Object,
    EnumClass,
    AnnotationClass,
    Function,
    Property,
    TypeAlias,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct VerifiedAddDeclarationIdentity {
    target_path: String,
    source_range: VerifiedAddDeclarationSourceRange,
    package_name: String,
    declaration_name: String,
    declaration_kind: VerifiedAddDeclarationKind,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct VerifiedAddDeclarationReceipt {
    outcome: VerifiedAddDeclarationOutcome,
    plan_id: crate::agent::public_protocol::VerifiedAddDeclarationPlanId,
    plan_version: VerifiedAddDeclarationPlanVersion,
    operation: VerifiedAddDeclarationOperation,
    publication: VerifiedAddDeclarationPublication,
    identity: VerifiedAddDeclarationIdentity,
    postimage_sha256: VerifiedAddDeclarationSha256,
    schema_version: u32,
}

impl VerifiedAddDeclarationReceipt {
    fn admit(self, plan: &StoredVerifiedAddDeclarationPlan) -> Result<Self> {
        if self.plan_id != plan.plan_id
            || self.plan_version.value() != VERIFIED_ADD_DECLARATION_TERMINAL_VERSION
            || self.identity.target_path != plan.target_path.as_str()
            || self.publication.generation <= plan.planned_generation
            || self.publication.workspace_state_identity.trim().is_empty()
            || self.identity.source_range.end_offset <= self.identity.source_range.start_offset
            || self.identity.declaration_name.trim().is_empty()
            || !canonical_lowercase_sha256(self.postimage_sha256.as_str())
            || self.schema_version != crate::SCHEMA_VERSION
        {
            return Err(CliError::new(
                "KAST_VERIFIED_ADD_DECLARATION_RECEIPT_INVALID",
                "The server-issued VERIFIED receipt did not preserve the exact durable plan and G1 declaration proof.",
            ));
        }
        Ok(self)
    }
}
