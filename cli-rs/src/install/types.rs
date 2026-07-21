#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallShellResult {
    pub shell: String,
    pub command_name: String,
    pub bin_dir: String,
    pub config_home: String,
    pub source_file: String,
    pub profile: String,
    pub profile_updated: bool,
    pub dry_run: bool,
    pub source_line: String,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallRepairAction {
    pub kind: String,
    pub target: String,
    pub status: String,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub command: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallRepairResult {
    pub applied: bool,
    pub config_path: String,
    pub apply_command: String,
    pub actions: Vec<InstallRepairAction>,
    pub backups: Vec<String>,
    pub warnings: Vec<String>,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(untagged)]
pub enum InstallResult {
    ActivateBundle(ActivateBundleResult),
    Shell(InstallShellResult),
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ActivateBundleResult {
    pub installed_at: String,
    pub version: String,
    pub platform: String,
    pub profile: String,
    pub install_root: String,
    pub current: String,
    pub manifest: String,
    pub active_binary: String,
    pub shim: String,
    pub skipped: bool,
    pub verify_only: bool,
    pub schema_version: u32,
}

#[derive(Debug)]
struct ValidatedBundle {
    root: PathBuf,
    manifest: BundleManifest,
    version: BundleVersion,
    cli_relative: PathBuf,
    backend_install_relative: PathBuf,
}

#[derive(Debug)]
struct ActivationTargetPaths {
    resolved: manifest::ResolvedKastPaths,
    version_dir: PathBuf,
    current_link: PathBuf,
    previous_link: PathBuf,
    headless_current_dir: PathBuf,
}
