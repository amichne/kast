#[derive(Debug, Clone, Serialize)]
pub struct KastConfig {
    pub server: ServerConfig,
    #[serde(skip_serializing_if = "RuntimeConfig::is_default")]
    pub runtime: RuntimeConfig,
    #[serde(skip_serializing_if = "ProjectOpenConfig::is_default")]
    pub project_open: ProjectOpenConfig,
    #[serde(skip_serializing_if = "OnboardingConfig::is_default")]
    pub onboarding: OnboardingConfig,
    pub indexing: IndexingConfig,
    pub cache: CacheConfig,
    pub watcher: WatcherConfig,
    pub gradle: GradleConfig,
    pub telemetry: TelemetryConfig,
    pub profiling: ProfilingConfig,
    pub paths: PathsConfig,
    pub backends: BackendsConfig,
    pub cli: CliConfig,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerConfig {
    pub max_results: u32,
    pub request_timeout_millis: u64,
    pub max_concurrent_requests: u32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeConfig {
    #[serde(skip_serializing_if = "RuntimeDefaultBackend::is_auto")]
    pub default_backend: RuntimeDefaultBackend,
    #[serde(skip_serializing_if = "IdeaLaunchConfig::is_default")]
    pub idea_launch: IdeaLaunchConfig,
}

impl RuntimeConfig {
    fn is_default(&self) -> bool {
        self.default_backend.is_auto() && self.idea_launch.is_default()
    }
}

impl Default for RuntimeConfig {
    fn default() -> Self {
        Self {
            default_backend: RuntimeDefaultBackend::Auto,
            idea_launch: IdeaLaunchConfig::default(),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum RuntimeDefaultBackend {
    Auto,
    Idea,
    Headless,
}

impl RuntimeDefaultBackend {
    pub fn backend_name(self) -> Option<BackendName> {
        match self {
            Self::Auto => None,
            Self::Idea => Some(BackendName::Idea),
            Self::Headless => Some(BackendName::Headless),
        }
    }

    fn is_auto(&self) -> bool {
        matches!(self, Self::Auto)
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct IdeaLaunchConfig {
    pub enabled: bool,
    pub command: PathBuf,
    pub wait_timeout_millis: NonZeroU64,
    pub require_installed_plugin: bool,
}

impl IdeaLaunchConfig {
    fn is_default(&self) -> bool {
        !self.enabled
            && self.command.as_path() == Path::new("idea")
            && self.wait_timeout_millis.get() == 90_000
            && self.require_installed_plugin
    }
}

impl Default for IdeaLaunchConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            command: PathBuf::from("idea"),
            wait_timeout_millis: NonZeroU64::new(90_000).expect("default IDEA launch timeout"),
            require_installed_plugin: true,
        }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProjectOpenConfig {
    pub profile_auto_init: bool,
    pub profile: ProjectOpenProfile,
    pub agent_harness: AgentSetupHarness,
    pub auto_exclude_git: bool,
}

impl ProjectOpenConfig {
    fn is_default(&self) -> bool {
        !self.profile_auto_init
            && self.profile == ProjectOpenProfile::CopilotLsp
            && self.agent_harness.is_auto()
            && self.auto_exclude_git
    }
}

impl Default for ProjectOpenConfig {
    fn default() -> Self {
        Self {
            profile_auto_init: false,
            profile: ProjectOpenProfile::CopilotLsp,
            agent_harness: AgentSetupHarness::Auto,
            auto_exclude_git: true,
        }
    }
}

#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OnboardingConfig {
    pub agent_up_completed: bool,
}

impl OnboardingConfig {
    fn is_default(&self) -> bool {
        !self.agent_up_completed
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum ProjectOpenProfile {
    CopilotLsp,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct IndexingConfig {
    pub phase2_enabled: bool,
    pub phase2_batch_size: u32,
    pub phase2_parallelism: u32,
    pub phase2_priority_depth: u32,
    pub identifier_index_wait_millis: u64,
    pub reference_batch_size: u32,
    pub remote: RemoteIndexConfig,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RemoteIndexConfig {
    pub enabled: bool,
    pub source_index_url: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CacheConfig {
    pub enabled: bool,
    pub write_delay_millis: u64,
    pub source_index_save_delay_millis: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WatcherConfig {
    pub debounce_millis: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GradleConfig {
    pub tooling_api_timeout_millis: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TelemetryConfig {
    pub enabled: bool,
    pub scopes: String,
    pub detail: String,
    pub output_file: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfilingConfig {
    pub enabled: bool,
    pub modes: String,
    pub duration_seconds: u64,
    pub output_dir: String,
    pub otlp_endpoint: Option<String>,
    pub emit_manifest: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PathsConfig {
    pub install_root: PathBuf,
    pub bin_dir: PathBuf,
    pub lib_dir: PathBuf,
    pub cache_dir: PathBuf,
    pub logs_dir: PathBuf,
    pub runtime_dir: PathBuf,
    pub descriptor_dir: PathBuf,
    pub socket_dir: PathBuf,
}

#[derive(Debug, Clone, Serialize)]
pub struct BackendsConfig {
    pub headless: HeadlessBackendConfig,
    pub idea: IdeaBackendConfig,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HeadlessBackendConfig {
    pub enabled: bool,
    pub runtime_libs_dir: Option<PathBuf>,
    pub idea_home: Option<PathBuf>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct IdeaBackendConfig {
    pub enabled: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CliConfig {
    pub binary_path: PathBuf,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PathResolutionReport {
    pub root: String,
    pub config_files: Vec<PathResolutionConfigFile>,
    pub entries: Vec<PathResolutionEntry>,
    pub warnings: Vec<String>,
    pub schema_version: u32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PathResolutionConfigFile {
    pub scope: String,
    pub path: String,
    pub exists: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PathResolutionEntry {
    pub key: String,
    pub value: String,
    pub source: PathResolutionSource,
    pub owner: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub derived_from: Option<String>,
    pub exists: bool,
    pub expected_kind: String,
    pub used_by_idea: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "kebab-case")]
pub enum PathResolutionSource {
    Default,
    Env,
    Manifest,
}

impl fmt::Display for PathResolutionSource {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        let value = match self {
            Self::Default => "default",
            Self::Env => "env",
            Self::Manifest => "manifest",
        };
        formatter.write_str(value)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PathResolutionMode {
    Cli,
    Idea,
}

#[derive(Debug, Clone, Copy)]
struct PathResolutionEntryContext {
    install_root_source: PathResolutionSource,
    bin_dir_source: PathResolutionSource,
    cache_dir_source: PathResolutionSource,
    logs_dir_source: PathResolutionSource,
    logs_dir_parent: Option<&'static str>,
    runtime_dir_source: PathResolutionSource,
    runtime_dir_parent: Option<&'static str>,
    workspace_state_source: PathResolutionSource,
    workspace_state_parent: Option<&'static str>,
}

impl PathResolutionEntryContext {
    fn from_environment(workspace_root: Option<&Path>, install_manifest_exists: bool) -> Self {
        Self::from_states(
            install_manifest_exists,
            env_present("KAST_INSTALL_ROOT"),
            env_present("KAST_CACHE_HOME"),
            workspace_root.is_some() && env_present("KAST_CACHE_HOME"),
        )
    }

    fn from_states(
        install_manifest_exists: bool,
        install_root_env: bool,
        cache_home_env: bool,
        workspace_cache_environment: bool,
    ) -> Self {
        let install_root_source =
            source_for_manifest_or_env_state(install_manifest_exists, install_root_env);
        let runtime_dir_source = if install_manifest_exists {
            PathResolutionSource::Manifest
        } else {
            install_root_source
        };
        let workspace_state_source = if workspace_cache_environment {
            PathResolutionSource::Env
        } else {
            runtime_dir_source
        };
        Self {
            install_root_source,
            bin_dir_source: if install_manifest_exists {
                PathResolutionSource::Manifest
            } else {
                PathResolutionSource::Default
            },
            cache_dir_source: if workspace_cache_environment {
                PathResolutionSource::Env
            } else {
                source_for_manifest_or_env_state(install_manifest_exists, cache_home_env)
            },
            logs_dir_source: if workspace_cache_environment {
                PathResolutionSource::Env
            } else if install_manifest_exists {
                PathResolutionSource::Manifest
            } else {
                PathResolutionSource::Default
            },
            logs_dir_parent: workspace_cache_environment.then_some("paths.cacheDir"),
            runtime_dir_source,
            runtime_dir_parent: (!install_manifest_exists).then_some("paths.installRoot"),
            workspace_state_source,
            workspace_state_parent: Some(if workspace_cache_environment {
                "paths.cacheDir"
            } else {
                "paths.runtimeDir"
            }),
        }
    }
}
