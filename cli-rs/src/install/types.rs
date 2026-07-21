#[derive(Debug, Serialize, Clone, Copy, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SetupStatus {
    Activated,
    Current,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SetupResult {
    #[serde(rename = "type")]
    pub result_type: &'static str,
    pub status: SetupStatus,
    pub release_digest: String,
    pub manifest_digest: String,
    pub kast_home: String,
    pub current: String,
    pub active_binary: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub backup: Option<String>,
    pub artifacts: Vec<SetupArtifact>,
    pub verified: bool,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SetupArtifact {
    pub role: String,
    pub path: String,
    pub sha256: String,
    pub verified: bool,
}

#[derive(Debug)]
struct ValidatedBundle {
    root: PathBuf,
    manifest: BundleManifest,
    version: BundleVersion,
    cli_relative: PathBuf,
    backend_install_relative: PathBuf,
    release_digest: String,
    manifest_digest: String,
}

#[derive(Debug)]
struct ActivationTargetPaths {
    resolved: manifest::ResolvedKastPaths,
    version_dir: PathBuf,
    current_link: PathBuf,
    previous_link: PathBuf,
    headless_current_dir: PathBuf,
}
