#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct KastInstallManifest {
    #[serde(default = "tool_name")]
    pub tool: String,
    #[serde(default)]
    pub install_id: String,
    #[serde(default)]
    pub release_digest: String,
    #[serde(default)]
    pub manifest_digest: String,
    #[serde(default = "default_profile")]
    pub profile: String,
    #[serde(default)]
    pub setup_profile: SetupProfile,
    #[serde(default)]
    pub active_version: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub previous_version: Option<String>,
    #[serde(default)]
    pub created_at: String,
    #[serde(default)]
    pub updated_at: String,
    pub roots: ManifestRoots,
    pub entrypoints: ManifestEntrypoints,
    #[serde(default)]
    pub schemas: ManifestSchemas,
    #[serde(default)]
    pub version: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub backend_version: String,
    #[serde(default)]
    pub installed_at: String,
    #[serde(default)]
    pub platform: String,
    #[serde(default)]
    pub components: Vec<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub backends: Vec<BackendComponentState>,
    #[serde(default)]
    pub managed_paths: Vec<String>,
    #[serde(default)]
    pub owned_paths: Vec<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub path_projections: Vec<PathProjectionReceipt>,
    #[serde(default)]
    pub shell_rc_patches: Vec<Value>,
    #[serde(default = "schema_version")]
    pub schema_version: u32,
}

#[derive(
    Debug,
    Serialize,
    Deserialize,
    Clone,
    Copy,
    Default,
    PartialEq,
    Eq,
    clap::ValueEnum,
)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SetupProfile {
    #[default]
    Standard,
    Development,
}

impl SetupProfile {
    pub(crate) fn projects_control_command(self) -> bool {
        self == Self::Development
    }
}

#[derive(Debug, Serialize, Deserialize, Clone, Copy, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum PathProjectionCommand {
    Kast,
    Kastctl,
}

#[derive(Debug, Serialize, Deserialize, Clone, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct PathProjectionReceipt {
    pub command: PathProjectionCommand,
    pub path: String,
    pub target: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct BackendComponentState {
    pub name: String,
    pub version: String,
    pub install_dir: String,
    pub runtime_libs_dir: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub idea_home: Option<String>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ManifestRoots {
    pub install: String,
    pub bin: String,
    pub config: String,
    pub data: String,
    pub cache: String,
    pub runtime: String,
    pub logs: String,
    pub locks: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ManifestEntrypoints {
    pub shim: String,
    pub active_binary: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ManifestSchemas {
    pub manifest: u32,
    pub workspace_registry: u32,
    pub symbol_index: u32,
}

impl Default for ManifestSchemas {
    fn default() -> Self {
        Self {
            manifest: 1,
            workspace_registry: 1,
            symbol_index: 3,
        }
    }
}

#[derive(Debug, Clone)]
pub struct ResolvedKastPaths {
    pub install_root: PathBuf,
    pub bin_dir: PathBuf,
    pub lib_dir: PathBuf,
    pub data_dir: PathBuf,
    pub cache_dir: PathBuf,
    pub logs_dir: PathBuf,
    pub runtime_dir: PathBuf,
    pub locks_dir: PathBuf,
    pub descriptor_dir: PathBuf,
    pub socket_dir: PathBuf,
    pub config_root: PathBuf,
    pub config_file: PathBuf,
    pub shim_path: PathBuf,
    pub active_binary: PathBuf,
    pub indexer_runtime_libs_dir: PathBuf,
    pub indexer_host_home: Option<PathBuf>,
}

pub fn resolve_paths() -> Result<ResolvedKastPaths> {
    let manifest_path = default_install_manifest_path();
    if manifest_path.is_file() {
        return paths_from_manifest(&read_manifest_at(&manifest_path)?);
    }
    Ok(default_resolved_paths())
}

pub fn default_resolved_paths() -> ResolvedKastPaths {
    let install_root = default_install_root();
    let config_root = default_config_root();
    let current = install_root.join("current");
    let bin_dir = current.join("bin");
    let lib_dir = current.join("lib");
    let state_dir = install_root.join("state");
    let cache_dir = state_dir.join("cache");
    let data_dir = state_dir.join("data");
    let runtime_dir = state_dir.join("runtime");
    let logs_dir = state_dir.join("logs");
    let locks_dir = install_root.clone();
    ResolvedKastPaths {
        install_root: install_root.clone(),
        bin_dir: bin_dir.clone(),
        lib_dir: lib_dir.clone(),
        data_dir,
        cache_dir,
        logs_dir,
        runtime_dir: runtime_dir.clone(),
        locks_dir,
        descriptor_dir: runtime_dir.join("daemons"),
        socket_dir: runtime_dir,
        config_file: config_root.join("config.toml"),
        config_root,
        shim_path: current.join(CONTROL_CLI_BUNDLE_PATH),
        active_binary: current.join(CONTROL_CLI_BUNDLE_PATH),
        indexer_runtime_libs_dir: lib_dir.join("backends/indexer/current/runtime-libs"),
        indexer_host_home: None,
    }
}

pub fn default_install_root() -> PathBuf {
    env_path("KAST_HOME").unwrap_or_else(|| home_dir().join(".local/share/kast"))
}

pub fn default_install_manifest_path() -> PathBuf {
    default_install_root()
        .join("current")
        .join(INSTALL_MANIFEST_FILE)
}

pub fn default_config_root() -> PathBuf {
    default_install_root().join("current/config")
}

pub fn read_install_manifest() -> Result<Option<KastInstallManifest>> {
    let path = default_install_manifest_path();
    if !path.is_file() {
        return Ok(None);
    }
    read_manifest_at(&path).map(Some)
}

pub fn paths_from_manifest(manifest: &KastInstallManifest) -> Result<ResolvedKastPaths> {
    if manifest.tool != TOOL_NAME {
        return Err(CliError::new(
            "INSTALL_MANIFEST_INVALID",
            format!(
                "Install manifest tool must be `{TOOL_NAME}`, got `{}`.",
                manifest.tool
            ),
        ));
    }
    let install_root = normalize(PathBuf::from(&manifest.roots.install));
    let config_root = normalize(PathBuf::from(&manifest.roots.config));
    let runtime_dir = normalize(PathBuf::from(&manifest.roots.runtime));
    let lib_dir = install_root.join("current/lib");
    let indexer = manifest
        .backends
        .iter()
        .find(|backend| backend.name == "indexer");
    Ok(ResolvedKastPaths {
        install_root: install_root.clone(),
        bin_dir: normalize(PathBuf::from(&manifest.roots.bin)),
        lib_dir: lib_dir.clone(),
        data_dir: normalize(PathBuf::from(&manifest.roots.data)),
        cache_dir: normalize(PathBuf::from(&manifest.roots.cache)),
        logs_dir: normalize(PathBuf::from(&manifest.roots.logs)),
        locks_dir: normalize(PathBuf::from(&manifest.roots.locks)),
        descriptor_dir: runtime_dir.join("daemons"),
        socket_dir: runtime_dir.clone(),
        runtime_dir,
        config_file: config_root.join("config.toml"),
        config_root,
        shim_path: normalize(PathBuf::from(&manifest.entrypoints.shim)),
        active_binary: normalize(PathBuf::from(&manifest.entrypoints.active_binary)),
        indexer_runtime_libs_dir: indexer
            .map(|backend| normalize(PathBuf::from(&backend.runtime_libs_dir)))
            .unwrap_or_else(|| lib_dir.join("backends/indexer/current/runtime-libs")),
        indexer_host_home: indexer
            .and_then(|backend| backend.idea_home.as_ref())
            .map(|path| normalize(PathBuf::from(path))),
    })
}

fn read_manifest_at(path: &Path) -> Result<KastInstallManifest> {
    serde_json::from_str(&fs::read_to_string(path)?).map_err(|error| {
        CliError::new(
            "INSTALL_MANIFEST_INVALID",
            format!(
                "Invalid Kast install manifest at {}: {error}",
                path.display()
            ),
        )
    })
}
