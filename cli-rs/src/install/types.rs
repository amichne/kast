#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallSkillResult {
    pub installed_at: String,
    pub version: String,
    pub source_bundle_sha256: String,
    pub output_paths: Vec<String>,
    pub skipped: bool,
    pub git_exclude: GitExcludeResult,
    pub schema_version: u32,
}

#[derive(Debug, Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct AgentGuidanceSetupPlan {
    #[serde(rename = "type")]
    pub result_type: &'static str,
    pub skill_target: String,
    pub agents_md_targets: Vec<AgentsMdTargetPlan>,
    pub install_command: Vec<String>,
    pub force: bool,
    pub dry_run: bool,
    pub schema_version: u32,
}

#[derive(Debug, Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct AgentsMdTargetPlan {
    pub path: String,
    pub exists: bool,
    pub will_create: bool,
    pub managed_region_present: bool,
    pub will_modify: bool,
    pub reason: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentGuidanceSetupResult {
    #[serde(rename = "type")]
    pub result_type: &'static str,
    pub skill: InstallSkillResult,
    pub agents_md_targets: Vec<AgentsMdTargetResult>,
    pub install_command: Vec<String>,
    pub skipped: bool,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentsMdTargetResult {
    pub path: String,
    pub created: bool,
    pub updated: bool,
    pub skipped: bool,
    pub managed_region_sha256: String,
    pub git_exclude: GitExcludeResult,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallInstructionsResult {
    pub installed_at: String,
    pub version: String,
    pub source_bundle_sha256: String,
    pub output_paths: Vec<String>,
    pub skipped: bool,
    pub git_exclude: GitExcludeResult,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallCopilotPackageResult {
    pub installed_at: String,
    pub version: String,
    pub source_bundle_sha256: String,
    pub output_paths: Vec<String>,
    pub skipped: bool,
    pub git_exclude: GitExcludeResult,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub warnings: Vec<String>,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentSetupAutoPlan {
    pub harness: cli::AgentSetupHarness,
    pub selection_source: AgentSetupSelectionSource,
    pub reason: String,
    pub install_command: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub target_dir: Option<String>,
    pub dry_run: bool,
    pub schema_version: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "kebab-case")]
pub enum AgentSetupSelectionSource {
    Explicit,
    Config,
    TargetDirectory,
    Repository,
}

impl AgentSetupAutoPlan {
    pub fn new(
        harness: cli::AgentSetupHarness,
        selection_source: AgentSetupSelectionSource,
        reason: String,
        install_command: Vec<String>,
        target_dir: Option<PathBuf>,
    ) -> Self {
        Self {
            harness,
            selection_source,
            reason,
            install_command,
            target_dir: target_dir.map(|path| path.display().to_string()),
            dry_run: true,
            schema_version: SCHEMA_VERSION,
        }
    }
}

#[derive(Debug, Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct GitExcludeResult {
    pub attempted: bool,
    pub updated: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub exclude_file: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallIdeaPluginResult {
    pub cask_token: String,
    pub plugin_version: String,
    pub download_cache: String,
    pub downloaded_bytes: u64,
    pub brew_action: String,
    pub brew_command: Vec<String>,
    pub brew_prefix: String,
    pub formula_prefix: String,
    pub cli_path: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub jetbrains_config_root: Option<String>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub plugin_directories: Vec<String>,
    pub dry_run: bool,
    pub developer_defaults: self_mgmt::DeveloperMachineDefaultsResult,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub warnings: Vec<String>,
    pub schema_version: u32,
}

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
    AgentGuidance(AgentGuidanceSetupResult),
    Skill(InstallSkillResult),
    Instructions(InstallInstructionsResult),
    Copilot(InstallCopilotPackageResult),
    IdeaPlugin(InstallIdeaPluginResult),
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
