const LOCAL_DEVELOPMENT_RECEIPT_SCHEMA_VERSION: u32 = 2;
const LOCAL_ARTIFACT_PROVENANCE_SCHEMA_VERSION: u32 = 1;
const LOCAL_PREPARED_GENERATION_SCHEMA_VERSION: u32 = 1;
const LOCAL_GUIDANCE_INPUTS_SCHEMA_VERSION: u32 = 1;
const LOCAL_BACKEND_SOURCE_SNAPSHOT_ENTRY: &str = "META-INF/kast/local-source-snapshot.json";
const LOCAL_BACKEND_COMPONENT_MANIFEST_ENTRY: &str = "META-INF/kast/local-backend-components.json";

#[derive(Debug, Clone)]
pub struct LocalDevelopmentRefreshRequest {
    pub source_root: PathBuf,
    pub workspace_root: PathBuf,
    pub prefix: PathBuf,
    pub expected_source_snapshot: PathBuf,
    pub cli_binary: PathBuf,
    pub cli_provenance: PathBuf,
    pub backend_directory: PathBuf,
    pub backend_provenance: PathBuf,
    pub skill_source: PathBuf,
    pub config_source: PathBuf,
}

#[derive(Debug, Clone)]
pub struct LocalDevelopmentPrepareRequest {
    pub source_root: PathBuf,
    pub expected_source_snapshot: PathBuf,
    pub cli_binary: PathBuf,
    pub cli_provenance: PathBuf,
    pub backend_directory: PathBuf,
    pub backend_provenance: PathBuf,
    pub skill_source: PathBuf,
    pub output_directory: PathBuf,
}

#[derive(Debug, Clone)]
pub struct LocalDevelopmentActivateRequest {
    pub source_root: PathBuf,
    pub workspace_root: PathBuf,
    pub prefix: PathBuf,
    pub prepared_generation: PathBuf,
}

#[derive(Debug, Clone)]
pub struct LocalArtifactAttestationRequest {
    pub source_root: PathBuf,
    pub expected_source_snapshot: PathBuf,
    pub kind: LocalArtifactKind,
    pub artifact: PathBuf,
    pub output_file: PathBuf,
}

#[derive(Debug, Clone)]
pub struct LocalDevelopmentRollbackRequest {
    pub prefix: PathBuf,
    pub to_generation: LocalGenerationId,
}

#[derive(Debug, Clone)]
pub struct LocalDevelopmentRemoveRequest {
    pub prefix: PathBuf,
    pub workspace_root: PathBuf,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalDevelopmentRefreshResult {
    pub receipt: LocalDevelopmentReceipt,
    pub skipped: bool,
    pub schema_version: u32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalDevelopmentPrepareResult {
    pub ledger: LocalPreparedGenerationLedger,
    pub directory: PathBuf,
    pub skipped: bool,
    pub schema_version: u32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalDevelopmentPreparedVerificationResult {
    pub ledger: LocalPreparedGenerationLedger,
    pub directory: PathBuf,
    pub schema_version: u32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalDevelopmentRollbackResult {
    pub receipt: LocalDevelopmentReceipt,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub replaced_generation: Option<LocalGenerationId>,
    pub skipped: bool,
    pub schema_version: u32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalDevelopmentRemoveResult {
    pub prefix: PathBuf,
    pub workspace_root: PathBuf,
    pub removed: bool,
    pub schema_version: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LocalDevelopmentReceipt {
    pub schema_version: u32,
    pub authority: LocalDevelopmentAuthority,
    pub generation_id: LocalGenerationId,
    pub source: SourceSnapshot,
    pub workspace_root: PathBuf,
    pub prefix: PathBuf,
    pub entrypoint: LocalDevelopmentEntrypoint,
    pub backend: LocalDevelopmentBackendIdentity,
    pub artifacts: LocalDevelopmentArtifactSet,
    pub components: LocalDevelopmentComponents,
    pub install_manifest: PathBuf,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub previous_generation: Option<LocalGenerationId>,
    pub updated_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LocalArtifactProvenance {
    pub schema_version: u32,
    pub kind: LocalArtifactKind,
    pub source: SourceSnapshot,
    pub artifact: PathBuf,
    pub sha256: Sha256Digest,
    pub implementation_version: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct LocalPreparedArtifactProvenance {
    schema_version: u32,
    kind: LocalArtifactKind,
    source: SourceSnapshot,
    sha256: Sha256Digest,
    implementation_version: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LocalPreparedGenerationLedger {
    pub schema_version: u32,
    pub generation_id: LocalGenerationId,
    pub source: SourceSnapshot,
    pub implementation_version: String,
    pub components: LocalPreparedGenerationComponents,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LocalPreparedGenerationComponents {
    pub source_snapshot: LocalPreparedGenerationComponent,
    pub cli: LocalPreparedGenerationComponent,
    pub cli_provenance: LocalPreparedGenerationComponent,
    pub backend: LocalPreparedGenerationComponent,
    pub backend_provenance: LocalPreparedGenerationComponent,
    pub backend_component_manifest: LocalPreparedGenerationComponent,
    pub skill: LocalPreparedGenerationComponent,
    pub guidance_inputs: LocalPreparedGenerationComponent,
    pub config: LocalPreparedGenerationComponent,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LocalPreparedGenerationComponent {
    pub relative_path: PathBuf,
    pub sha256: Sha256Digest,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct LocalGuidanceInputs {
    schema_version: u32,
    source: SourceSnapshot,
    skill_relative_path: PathBuf,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum LocalArtifactKind {
    Cli,
    HeadlessBackend,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LocalDevelopmentArtifactSet {
    pub cli: LocalArtifactProvenance,
    pub backend: LocalArtifactProvenance,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum LocalDevelopmentAuthority {
    LocalDevelopment,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(try_from = "String", into = "String")]
pub struct LocalGenerationId(String);

impl LocalGenerationId {
    pub fn from_source(source: &SourceSnapshot) -> Self {
        Self(format!(
            "{}-{}",
            &source.git_commit.as_str()[..12],
            source.source_tree_sha256.as_str()
        ))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl TryFrom<String> for LocalGenerationId {
    type Error = CliError;

    fn try_from(value: String) -> Result<Self> {
        let mut parts = value.split('-');
        let commit = parts.next().unwrap_or_default();
        let digest = parts.next().unwrap_or_default();
        if parts.next().is_none()
            && commit.len() == 12
            && commit.bytes().all(|byte| byte.is_ascii_hexdigit())
            && digest.len() == 64
            && digest.bytes().all(|byte| byte.is_ascii_hexdigit())
        {
            Ok(Self(value.to_ascii_lowercase()))
        } else {
            Err(CliError::new(
                "LOCAL_GENERATION_ID_INVALID",
                "Local generation identity must contain a 12-character commit prefix and SHA-256 digest.",
            ))
        }
    }
}

impl From<LocalGenerationId> for String {
    fn from(value: LocalGenerationId) -> Self {
        value.0
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LocalDevelopmentEntrypoint {
    pub physical_target: PathBuf,
    pub effective_target: PathBuf,
    pub sha256: Sha256Digest,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LocalDevelopmentBackendIdentity {
    pub kind: LocalDevelopmentBackendKind,
    pub implementation_version: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum LocalDevelopmentBackendKind {
    Headless,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LocalDevelopmentComponents {
    pub cli: LocalDevelopmentComponent,
    pub backend: LocalDevelopmentComponent,
    pub skill: LocalDevelopmentComponent,
    pub guidance: LocalDevelopmentComponent,
    pub config: LocalDevelopmentComponent,
    pub manifest: LocalDevelopmentComponent,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LocalDevelopmentComponent {
    pub physical_target: PathBuf,
    pub effective_target: PathBuf,
    pub sha256: Sha256Digest,
}
