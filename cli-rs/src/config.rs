use crate::SCHEMA_VERSION;
use crate::cli::{AgentSetupHarness, BackendName, DaemonStartArgs};
use crate::error::{CliError, Result};
use crate::manifest;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};
use std::env;
use std::fmt;
use std::fs;
use std::num::NonZeroU64;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Serialize)]
pub struct KastConfig {
    pub server: ServerConfig,
    #[serde(skip_serializing_if = "RuntimeConfig::is_default")]
    pub runtime: RuntimeConfig,
    #[serde(skip_serializing_if = "ProjectOpenConfig::is_default")]
    pub project_open: ProjectOpenConfig,
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

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialConfig {
    server: Option<PartialServer>,
    runtime: Option<PartialRuntime>,
    project_open: Option<PartialProjectOpen>,
    indexing: Option<PartialIndexing>,
    cache: Option<PartialCache>,
    watcher: Option<PartialWatcher>,
    gradle: Option<PartialGradle>,
    telemetry: Option<PartialTelemetry>,
    profiling: Option<PartialProfiling>,
    backends: Option<PartialBackends>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialServer {
    max_results: Option<u32>,
    request_timeout_millis: Option<u64>,
    max_concurrent_requests: Option<u32>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialRuntime {
    default_backend: Option<RuntimeDefaultBackend>,
    idea_launch: Option<PartialIdeaLaunch>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialIdeaLaunch {
    enabled: Option<bool>,
    command: Option<PathBuf>,
    wait_timeout_millis: Option<NonZeroU64>,
    require_installed_plugin: Option<bool>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialProjectOpen {
    profile_auto_init: Option<bool>,
    profile: Option<ProjectOpenProfile>,
    agent_harness: Option<AgentSetupHarness>,
    auto_exclude_git: Option<bool>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialIndexing {
    phase2_enabled: Option<bool>,
    phase2_batch_size: Option<u32>,
    phase2_parallelism: Option<u32>,
    phase2_priority_depth: Option<u32>,
    identifier_index_wait_millis: Option<u64>,
    reference_batch_size: Option<u32>,
    remote: Option<PartialRemoteIndex>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialRemoteIndex {
    enabled: Option<bool>,
    source_index_url: Option<Option<String>>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialCache {
    enabled: Option<bool>,
    write_delay_millis: Option<u64>,
    source_index_save_delay_millis: Option<u64>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialWatcher {
    debounce_millis: Option<u64>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialGradle {
    tooling_api_timeout_millis: Option<u64>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialTelemetry {
    enabled: Option<bool>,
    scopes: Option<String>,
    detail: Option<String>,
    output_file: Option<Option<String>>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialProfiling {
    enabled: Option<bool>,
    modes: Option<String>,
    duration_seconds: Option<u64>,
    output_dir: Option<String>,
    otlp_endpoint: Option<Option<String>>,
    emit_manifest: Option<bool>,
}

#[derive(Debug, Default, Deserialize)]
struct PartialBackends {
    headless: Option<PartialHeadless>,
    idea: Option<PartialIdea>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialHeadless {
    enabled: Option<bool>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialIdea {
    enabled: Option<bool>,
}

impl KastConfig {
    pub fn defaults() -> Self {
        Self::from_resolved_paths(&manifest::default_resolved_paths())
    }

    fn from_resolved_paths(paths: &manifest::ResolvedKastPaths) -> Self {
        Self {
            server: ServerConfig {
                max_results: 500,
                request_timeout_millis: 30_000,
                max_concurrent_requests: 4,
            },
            runtime: RuntimeConfig::default(),
            project_open: ProjectOpenConfig::default(),
            indexing: IndexingConfig {
                phase2_enabled: true,
                phase2_batch_size: 50,
                phase2_parallelism: 4,
                phase2_priority_depth: 2,
                identifier_index_wait_millis: 10_000,
                reference_batch_size: 50,
                remote: RemoteIndexConfig {
                    enabled: false,
                    source_index_url: None,
                },
            },
            cache: CacheConfig {
                enabled: true,
                write_delay_millis: 5_000,
                source_index_save_delay_millis: 5_000,
            },
            watcher: WatcherConfig {
                debounce_millis: 200,
            },
            gradle: GradleConfig {
                tooling_api_timeout_millis: 120_000,
            },
            telemetry: TelemetryConfig {
                enabled: false,
                scopes: "all".to_string(),
                detail: "basic".to_string(),
                output_file: None,
            },
            profiling: ProfilingConfig {
                enabled: false,
                modes: "cpu".to_string(),
                duration_seconds: 30,
                output_dir: "{logsDir}/profiling".to_string(),
                otlp_endpoint: None,
                emit_manifest: true,
            },
            paths: PathsConfig {
                install_root: paths.install_root.clone(),
                bin_dir: paths.bin_dir.clone(),
                lib_dir: paths.lib_dir.clone(),
                cache_dir: paths.cache_dir.clone(),
                logs_dir: paths.logs_dir.clone(),
                runtime_dir: paths.runtime_dir.clone(),
                descriptor_dir: paths.descriptor_dir.clone(),
                socket_dir: paths.socket_dir.clone(),
            },
            backends: BackendsConfig {
                headless: HeadlessBackendConfig {
                    enabled: true,
                    runtime_libs_dir: Some(paths.headless_runtime_libs_dir.clone()),
                    idea_home: paths.headless_idea_home.clone(),
                },
                idea: IdeaBackendConfig { enabled: true },
            },
            cli: CliConfig {
                binary_path: paths.shim_path.clone(),
            },
        }
    }

    pub fn load_global() -> Result<Self> {
        let resolved_paths = manifest::resolve_paths()?;
        let mut config = Self::from_resolved_paths(&resolved_paths);
        let global_config = global_config_path();
        if global_config.is_file() {
            config.apply(read_partial_config(&global_config)?);
        }
        Ok(config)
    }

    pub fn load(workspace_root: &Path) -> Result<Self> {
        let mut config = Self::load_global()?;
        let workspace_config = workspace_data_directory(workspace_root)?.join("config.toml");
        if workspace_config.is_file() {
            config.apply(read_partial_config(&workspace_config)?);
        }
        config.apply_workspace_cache_environment(workspace_root);
        Ok(config)
    }

    fn apply_workspace_cache_environment(&mut self, workspace_root: &Path) {
        let Some(cache_home) = env_path("KAST_CACHE_HOME") else {
            return;
        };
        let workspace_id = env::var("KAST_WORKSPACE_ID")
            .ok()
            .filter(|value| !value.trim().is_empty());
        self.apply_workspace_cache_home(&cache_home, workspace_root, workspace_id.as_deref());
    }

    fn apply_workspace_cache_home(
        &mut self,
        cache_home: &Path,
        workspace_root: &Path,
        workspace_id: Option<&str>,
    ) {
        let workspace_dir = workspace_cache_directory(cache_home, workspace_root, workspace_id);
        self.paths.cache_dir = cache_home.to_path_buf();
        self.paths.logs_dir = workspace_dir.join("logs");
        self.paths.descriptor_dir = workspace_dir.clone();
        self.paths.socket_dir = workspace_dir;
    }

    fn apply(&mut self, partial: PartialConfig) {
        if let Some(server) = partial.server {
            if let Some(value) = server.max_results {
                self.server.max_results = value;
            }
            if let Some(value) = server.request_timeout_millis {
                self.server.request_timeout_millis = value;
            }
            if let Some(value) = server.max_concurrent_requests {
                self.server.max_concurrent_requests = value;
            }
        }
        if let Some(runtime) = partial.runtime {
            if let Some(value) = runtime.default_backend {
                self.runtime.default_backend = value;
            }
            if let Some(idea_launch) = runtime.idea_launch {
                if let Some(value) = idea_launch.enabled {
                    self.runtime.idea_launch.enabled = value;
                }
                if let Some(value) = idea_launch.command {
                    self.runtime.idea_launch.command = value;
                }
                if let Some(value) = idea_launch.wait_timeout_millis {
                    self.runtime.idea_launch.wait_timeout_millis = value;
                }
                if let Some(value) = idea_launch.require_installed_plugin {
                    self.runtime.idea_launch.require_installed_plugin = value;
                }
            }
        }
        if let Some(project_open) = partial.project_open {
            if let Some(value) = project_open.profile_auto_init {
                self.project_open.profile_auto_init = value;
            }
            if let Some(value) = project_open.profile {
                self.project_open.profile = value;
            }
            if let Some(value) = project_open.agent_harness {
                self.project_open.agent_harness = value;
            }
            if let Some(value) = project_open.auto_exclude_git {
                self.project_open.auto_exclude_git = value;
            }
        }
        if let Some(indexing) = partial.indexing {
            if let Some(value) = indexing.phase2_enabled {
                self.indexing.phase2_enabled = value;
            }
            if let Some(value) = indexing.phase2_batch_size {
                self.indexing.phase2_batch_size = value;
            }
            if let Some(value) = indexing.phase2_parallelism {
                self.indexing.phase2_parallelism = value;
            }
            if let Some(value) = indexing.phase2_priority_depth {
                self.indexing.phase2_priority_depth = value;
            }
            if let Some(value) = indexing.identifier_index_wait_millis {
                self.indexing.identifier_index_wait_millis = value;
            }
            if let Some(value) = indexing.reference_batch_size {
                self.indexing.reference_batch_size = value;
            }
            if let Some(remote) = indexing.remote {
                if let Some(value) = remote.enabled {
                    self.indexing.remote.enabled = value;
                }
                if let Some(value) = remote.source_index_url {
                    self.indexing.remote.source_index_url = value;
                }
            }
        }
        if let Some(cache) = partial.cache {
            if let Some(value) = cache.enabled {
                self.cache.enabled = value;
            }
            if let Some(value) = cache.write_delay_millis {
                self.cache.write_delay_millis = value;
            }
            if let Some(value) = cache.source_index_save_delay_millis {
                self.cache.source_index_save_delay_millis = value;
            }
        }
        if let Some(watcher) = partial.watcher
            && let Some(value) = watcher.debounce_millis
        {
            self.watcher.debounce_millis = value;
        }
        if let Some(gradle) = partial.gradle
            && let Some(value) = gradle.tooling_api_timeout_millis
        {
            self.gradle.tooling_api_timeout_millis = value;
        }
        if let Some(telemetry) = partial.telemetry {
            if let Some(value) = telemetry.enabled {
                self.telemetry.enabled = value;
            }
            if let Some(value) = telemetry.scopes {
                self.telemetry.scopes = value;
            }
            if let Some(value) = telemetry.detail {
                self.telemetry.detail = value;
            }
            if let Some(value) = telemetry.output_file {
                self.telemetry.output_file = value;
            }
        }
        if let Some(profiling) = partial.profiling {
            if let Some(value) = profiling.enabled {
                self.profiling.enabled = value;
            }
            if let Some(value) = profiling.modes {
                self.profiling.modes = value;
            }
            if let Some(value) = profiling.duration_seconds {
                self.profiling.duration_seconds = value;
            }
            if let Some(value) = profiling.output_dir {
                self.profiling.output_dir = value;
            }
            if let Some(value) = profiling.otlp_endpoint {
                self.profiling.otlp_endpoint = value;
            }
            if let Some(value) = profiling.emit_manifest {
                self.profiling.emit_manifest = value;
            }
        }
        if let Some(backends) = partial.backends {
            if let Some(headless) = backends.headless
                && let Some(value) = headless.enabled
            {
                self.backends.headless.enabled = value;
            }
            if let Some(idea) = backends.idea
                && let Some(value) = idea.enabled
            {
                self.backends.idea.enabled = value;
            }
        }
    }
}

pub fn init_config() -> Result<PathBuf> {
    let config_file = global_config_path();
    if !config_file.exists() {
        if let Some(parent) = config_file.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::write(&config_file, default_config_template()?)?;
    }
    Ok(config_file)
}

pub fn default_config_template() -> Result<String> {
    Ok(String::new())
}

pub fn path_resolution_report(
    config: &KastConfig,
    workspace_root: Option<&Path>,
    mode: PathResolutionMode,
) -> Result<PathResolutionReport> {
    let install_manifest = manifest::default_install_manifest_path();
    let install_manifest_exists = install_manifest.is_file();
    let global_config = global_config_path();
    let workspace_config = workspace_root
        .map(workspace_data_directory)
        .transpose()?
        .map(|workspace_dir| workspace_dir.join("config.toml"));
    let global_keys = config_keys(&global_config)?;
    let workspace_keys = workspace_config
        .as_deref()
        .map(config_keys)
        .transpose()?
        .unwrap_or_default();
    let mut warnings = vec![];
    for key in global_keys
        .iter()
        .chain(workspace_keys.iter())
        .filter(|key| install_owned_config_key(key))
    {
        warnings.push(format!(
            "config key {key} is ignored; install-owned paths are resolved from install.json"
        ));
    }
    if mode == PathResolutionMode::Idea {
        for key in workspace_keys
            .iter()
            .filter(|key| idea_ignored_workspace_key(key))
        {
            warnings.push(format!(
                "workspace {key} is ignored by IDEA; global/default path resolution is used"
            ));
        }
    }
    let entry_context =
        PathResolutionEntryContext::from_environment(workspace_root, install_manifest_exists);
    let entries = path_resolution_entries(config, mode, entry_context);
    Ok(PathResolutionReport {
        root: config.paths.install_root.display().to_string(),
        config_files: config_files(install_manifest, global_config, workspace_config),
        entries,
        warnings,
        schema_version: SCHEMA_VERSION,
    })
}

fn path_resolution_entries(
    config: &KastConfig,
    mode: PathResolutionMode,
    context: PathResolutionEntryContext,
) -> Vec<PathResolutionEntry> {
    let mut entries = vec![
        path_entry(
            "paths.installRoot",
            &config.paths.install_root,
            "directory",
            None,
            context.install_root_source,
            mode,
        ),
        path_entry(
            "paths.binDir",
            &config.paths.bin_dir,
            "directory",
            None,
            context.bin_dir_source,
            mode,
        ),
        path_entry(
            "paths.libDir",
            &config.paths.lib_dir,
            "directory",
            Some("paths.installRoot"),
            context.install_root_source,
            mode,
        ),
        path_entry(
            "paths.cacheDir",
            &config.paths.cache_dir,
            "directory",
            None,
            context.cache_dir_source,
            mode,
        ),
        path_entry(
            "paths.logsDir",
            &config.paths.logs_dir,
            "directory",
            context.logs_dir_parent,
            context.logs_dir_source,
            mode,
        ),
        path_entry(
            "paths.runtimeDir",
            &config.paths.runtime_dir,
            "directory",
            context.runtime_dir_parent,
            context.runtime_dir_source,
            mode,
        ),
        path_entry(
            "paths.descriptorDir",
            &config.paths.descriptor_dir,
            "directory",
            context.workspace_state_parent,
            context.workspace_state_source,
            mode,
        ),
        path_entry(
            "paths.socketDir",
            &config.paths.socket_dir,
            "directory",
            context.workspace_state_parent,
            context.workspace_state_source,
            mode,
        ),
        path_entry(
            "cli.binaryPath",
            &config.cli.binary_path,
            "file",
            Some("paths.binDir"),
            context.bin_dir_source,
            mode,
        ),
    ];
    if let Some(runtime_libs_dir) = &config.backends.headless.runtime_libs_dir {
        entries.push(path_entry(
            "backends.headless.runtimeLibsDir",
            runtime_libs_dir,
            "directory",
            Some("paths.libDir"),
            context.install_root_source,
            mode,
        ));
        entries.push(path_entry(
            "backends.headless.runtimeLibsClasspath",
            &runtime_libs_dir.join("classpath.txt"),
            "file",
            Some("backends.headless.runtimeLibsDir"),
            context.install_root_source,
            mode,
        ));
    }
    if let Some(idea_home) = &config.backends.headless.idea_home {
        entries.push(path_entry(
            "backends.headless.ideaHome",
            idea_home,
            "directory",
            None,
            PathResolutionSource::Manifest,
            mode,
        ));
    }
    entries
}

fn env_present(env_key: &str) -> bool {
    env_value_present(env::var_os(env_key))
}

fn env_value_present(value: Option<std::ffi::OsString>) -> bool {
    value.is_some_and(|value| !value.is_empty())
}

fn source_for_manifest_or_env_state(
    install_manifest_exists: bool,
    env_present: bool,
) -> PathResolutionSource {
    if install_manifest_exists {
        PathResolutionSource::Manifest
    } else if env_present {
        PathResolutionSource::Env
    } else {
        PathResolutionSource::Default
    }
}

fn path_entry(
    key: &str,
    path: &Path,
    expected_kind: &str,
    derived_from: Option<&str>,
    source: PathResolutionSource,
    _mode: PathResolutionMode,
) -> PathResolutionEntry {
    PathResolutionEntry {
        key: key.to_string(),
        value: path.display().to_string(),
        source,
        owner: path_owner(key).to_string(),
        derived_from: derived_from.map(str::to_string),
        exists: match expected_kind {
            "file" => path.is_file(),
            "directory" => path.is_dir(),
            _ => path.exists(),
        },
        expected_kind: expected_kind.to_string(),
        used_by_idea: idea_uses_path(key),
    }
}

fn config_files(
    install_manifest: PathBuf,
    global_config: PathBuf,
    workspace_config: Option<PathBuf>,
) -> Vec<PathResolutionConfigFile> {
    let mut files = vec![
        PathResolutionConfigFile {
            scope: "install-manifest".to_string(),
            exists: install_manifest.is_file(),
            path: install_manifest.display().to_string(),
        },
        PathResolutionConfigFile {
            scope: "global".to_string(),
            exists: global_config.is_file(),
            path: global_config.display().to_string(),
        },
    ];
    if let Some(workspace_config) = workspace_config {
        files.push(PathResolutionConfigFile {
            scope: "workspace".to_string(),
            exists: workspace_config.is_file(),
            path: workspace_config.display().to_string(),
        });
    }
    files
}

fn path_owner(key: &str) -> &'static str {
    match key {
        "cli.binaryPath" => "install",
        key if key.starts_with("paths.") => "install",
        key if key.starts_with("backends.headless.") => "install",
        _ => "runtime",
    }
}

fn idea_uses_path(key: &str) -> bool {
    matches!(
        key,
        "paths.installRoot"
            | "paths.binDir"
            | "paths.cacheDir"
            | "paths.logsDir"
            | "paths.runtimeDir"
            | "paths.descriptorDir"
            | "cli.binaryPath"
    )
}

fn idea_ignored_workspace_key(key: &str) -> bool {
    let key = normalize_config_key(key);
    install_owned_config_key(&key)
}

fn install_owned_config_key(key: &str) -> bool {
    let key = normalize_config_key(key);
    key.starts_with("paths.")
        || key.starts_with("cli.")
        || key.starts_with("install.")
        || key == "backends.headless.runtimelibsdir"
        || key == "backends.headless.ideahome"
}

fn config_keys(path: &Path) -> Result<BTreeSet<String>> {
    if !path.is_file() {
        return Ok(BTreeSet::new());
    }
    let value: toml::Value = match toml::from_str(&fs::read_to_string(path)?) {
        Ok(value) => value,
        Err(_) => return Ok(BTreeSet::new()),
    };
    let mut keys = BTreeSet::new();
    collect_config_keys("", &value, &mut keys);
    Ok(keys)
}

fn collect_config_keys(prefix: &str, value: &toml::Value, keys: &mut BTreeSet<String>) {
    match value {
        toml::Value::Table(table) => {
            for (key, value) in table {
                let next_prefix = if prefix.is_empty() {
                    key.to_string()
                } else {
                    format!("{prefix}.{key}")
                };
                collect_config_keys(&next_prefix, value, keys);
            }
        }
        _ => {
            keys.insert(normalize_config_key(prefix));
        }
    }
}

fn normalize_config_key(key: &str) -> String {
    key.split('.')
        .map(|segment| {
            segment
                .chars()
                .filter(|char| *char != '-' && *char != '_')
                .collect::<String>()
                .to_ascii_lowercase()
        })
        .collect::<Vec<_>>()
        .join(".")
}

pub fn backend_runtime_libs_dir(
    config: &KastConfig,
    backend_name: BackendName,
    override_dir: Option<PathBuf>,
) -> Result<PathBuf> {
    let configured = match backend_name {
        BackendName::Headless => config.backends.headless.runtime_libs_dir.clone(),
        BackendName::Idea => {
            return Err(CliError::new(
                "DAEMON_START_ERROR",
                "The idea backend is hosted by IDEA and cannot be launched as a headless runtime.",
            ));
        }
    };
    override_dir.map(normalize).or(configured).ok_or_else(|| {
        CliError::new(
            "DAEMON_START_ERROR",
            "Cannot locate backend runtime-libs. Install or repair the manifest-backed headless runtime with `kast ready --fix`, or pass --runtime-libs-dir for this launch.",
        )
    })
}

pub fn server_launch_args(args: &DaemonStartArgs, config: &KastConfig) -> Result<Vec<String>> {
    let workspace_root = resolve_workspace_root(args.workspace_root.clone())?;
    let socket_path = args
        .socket_path
        .clone()
        .map(normalize)
        .unwrap_or_else(|| default_socket_path_for_config(config, &workspace_root));
    let mut result = vec![format!("--workspace-root={}", workspace_root.display())];
    if args.stdio {
        result.push("--stdio".to_string());
    } else {
        result.push(format!("--socket-path={}", socket_path.display()));
    }
    result.push(format!(
        "--module-name={}",
        args.module_name.as_deref().unwrap_or("sources")
    ));
    if let Some(source_roots) = &args.source_roots {
        result.push(format!("--source-roots={source_roots}"));
    }
    if let Some(classpath) = &args.classpath {
        result.push(format!("--classpath={classpath}"));
    }
    result.push(format!(
        "--request-timeout-ms={}",
        args.request_timeout_ms
            .unwrap_or(config.server.request_timeout_millis)
    ));
    result.push(format!(
        "--max-results={}",
        args.max_results.unwrap_or(config.server.max_results)
    ));
    result.push(format!(
        "--max-concurrent-requests={}",
        args.max_concurrent_requests
            .unwrap_or(config.server.max_concurrent_requests)
    ));
    if args.profile {
        result.push("--profile".to_string());
    }
    if let Some(value) = &args.profile_modes {
        result.push(format!("--profile-modes={value}"));
    }
    if let Some(value) = args.profile_duration {
        result.push(format!("--profile-duration={value}"));
    }
    if let Some(value) = &args.profile_otlp_endpoint {
        result.push(format!("--profile-otlp-endpoint={value}"));
    }
    Ok(result)
}

pub fn kast_config_home() -> PathBuf {
    manifest::default_config_root()
}

pub fn global_config_path() -> PathBuf {
    manifest::resolve_paths()
        .map(|paths| paths.config_file)
        .unwrap_or_else(|_| manifest::default_resolved_paths().config_file)
}

pub fn home_dir() -> PathBuf {
    manifest::home_dir()
}

pub fn normalize(path: PathBuf) -> PathBuf {
    if path.is_absolute() {
        path
    } else {
        env::current_dir()
            .unwrap_or_else(|_| PathBuf::from("."))
            .join(path)
    }
    .components()
    .collect()
}

pub fn resolve_workspace_root(value: Option<PathBuf>) -> Result<PathBuf> {
    if let Some(value) = value {
        return Ok(normalize(value));
    }
    let current = env::current_dir()?;
    Ok(find_workspace_marker_root(&current)
        .map(normalize)
        .unwrap_or_else(|| normalize(current)))
}

fn find_workspace_marker_root(start: &Path) -> Option<PathBuf> {
    let mut current = Some(start);
    while let Some(path) = current {
        if WORKSPACE_MARKERS
            .iter()
            .any(|marker| path.join(marker).exists())
        {
            return Some(path.to_path_buf());
        }
        current = path.parent();
    }
    None
}

const WORKSPACE_MARKERS: &[&str] = &[
    "settings.gradle.kts",
    "settings.gradle",
    "build.gradle.kts",
    "build.gradle",
    ".kast",
];
const MAX_UNIX_SOCKET_PATH_BYTES: usize = 100;

pub fn workspace_data_directory(workspace_root: &Path) -> Result<PathBuf> {
    let root = normalize(workspace_root.to_path_buf());
    let workspaces_root = manifest::resolve_paths()
        .map(|paths| paths.data_dir)
        .unwrap_or_else(|_| manifest::default_resolved_paths().data_dir)
        .join("workspaces");
    if let Some(workspace) = git_workspace(&root) {
        return Ok(workspace_data_directory_for_git(
            &workspaces_root,
            &workspace,
        ));
    }
    if root.starts_with(env::temp_dir()) {
        return Ok(root.join(".gradle/kast"));
    }
    let id = local_workspace_id(&root)?;
    Ok(workspaces_root
        .join("local")
        .join(format!("{}--{id}", sanitized_path(&root))))
}

#[allow(dead_code)]
pub fn workspace_database_path(workspace_root: &Path) -> Result<PathBuf> {
    Ok(workspace_data_directory(workspace_root)?.join("cache/source-index.db"))
}

pub fn default_socket_path(workspace_root: &Path) -> PathBuf {
    env::temp_dir().join(format!("kast-{}.sock", workspace_hash(workspace_root)))
}

fn default_socket_path_for_config(config: &KastConfig, workspace_root: &Path) -> PathBuf {
    let configured = config
        .paths
        .socket_dir
        .join(format!("kast-{}.sock", workspace_hash(workspace_root)));
    if socket_path_too_long(&configured) {
        default_socket_path(workspace_root)
    } else {
        configured
    }
}

pub fn workspace_hash(workspace_root: &Path) -> String {
    let normalized = normalize(workspace_root.to_path_buf());
    let digest = Sha256::digest(normalized.to_string_lossy().as_bytes());
    hex::encode(digest)[0..12].to_string()
}

fn socket_path_too_long(path: &Path) -> bool {
    path.to_string_lossy().len() > MAX_UNIX_SOCKET_PATH_BYTES
}

fn read_partial_config(path: &Path) -> Result<PartialConfig> {
    Ok(toml::from_str(&fs::read_to_string(path)?)?)
}

#[derive(Debug)]
struct GitWorkspace {
    toplevel: PathBuf,
    common_dir: PathBuf,
    git_dir: PathBuf,
    remote: Option<GitRemote>,
}

#[derive(Debug, Clone)]
struct GitRemote {
    host: String,
    owner: String,
    repo: String,
}

fn git_workspace(workspace_root: &Path) -> Option<GitWorkspace> {
    let toplevel = git_path(workspace_root, &["rev-parse", "--show-toplevel"])?;
    let common_dir = git_path(workspace_root, &["rev-parse", "--git-common-dir"])?;
    let git_dir = git_path(workspace_root, &["rev-parse", "--git-dir"])?;
    let remote = git_output(workspace_root, &["config", "--get", "remote.origin.url"])
        .and_then(|remote| parse_git_remote(remote.trim()));
    Some(GitWorkspace {
        toplevel,
        common_dir,
        git_dir,
        remote,
    })
}

fn workspace_data_directory_for_git(workspaces_root: &Path, workspace: &GitWorkspace) -> PathBuf {
    let repo_root = if let Some(remote) = &workspace.remote {
        workspaces_root
            .join("git")
            .join(&remote.host)
            .join(&remote.owner)
            .join(&remote.repo)
    } else {
        workspaces_root
            .join("git/local")
            .join(git_common_dir_hash(&workspace.common_dir))
    };
    repo_root.join("worktrees").join(format!(
        "{}--{}",
        workspace_slug(&workspace.toplevel),
        git_worktree_hash(&workspace.toplevel, &workspace.git_dir)
    ))
}

fn git_worktree_hash(toplevel: &Path, git_dir: &Path) -> String {
    sha256_prefix(&format!(
        "{}\n{}",
        normalize(toplevel.to_path_buf()).display(),
        normalize(git_dir.to_path_buf()).display()
    ))
}

fn git_common_dir_hash(common_dir: &Path) -> String {
    sha256_prefix(&normalize(common_dir.to_path_buf()).display().to_string())
}

fn sha256_prefix(value: &str) -> String {
    let digest = Sha256::digest(value.as_bytes());
    hex::encode(digest)[0..12].to_string()
}

fn git_path(workspace_root: &Path, args: &[&str]) -> Option<PathBuf> {
    let raw = git_output(workspace_root, args)?;
    let path = PathBuf::from(raw.trim());
    Some(normalize(if path.is_absolute() {
        path
    } else {
        workspace_root.join(path)
    }))
}

fn git_output(workspace_root: &Path, args: &[&str]) -> Option<String> {
    let output = std::process::Command::new("git")
        .args(args)
        .current_dir(workspace_root)
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let value = String::from_utf8_lossy(&output.stdout).trim().to_string();
    (!value.is_empty()).then_some(value)
}

fn parse_git_remote(remote_url: &str) -> Option<GitRemote> {
    if let Some(rest) = remote_url.strip_prefix("git@") {
        let (host, path) = rest.split_once(':')?;
        let (owner, repo) = path.split_once('/')?;
        return Some(GitRemote {
            host: host.to_string(),
            owner: owner.to_string(),
            repo: repo.trim_end_matches(".git").to_string(),
        });
    }
    if let Some(rest) = remote_url.strip_prefix("https://") {
        let mut parts = rest.splitn(4, '/');
        let host = parts.next()?;
        let owner = parts.next()?;
        let repo = parts.next()?;
        return Some(GitRemote {
            host: host.to_string(),
            owner: owner.to_string(),
            repo: repo.trim_end_matches(".git").to_string(),
        });
    }
    None
}

fn local_workspace_id(workspace_root: &Path) -> Result<String> {
    let registry_path = manifest::resolve_paths()
        .map(|paths| paths.data_dir)
        .unwrap_or_else(|_| manifest::default_resolved_paths().data_dir)
        .join("workspaces/local-workspaces.json");
    if let Some(parent) = registry_path.parent() {
        fs::create_dir_all(parent)?;
    }
    let mut registry: BTreeMap<String, String> = if registry_path.is_file() {
        serde_json::from_str(&fs::read_to_string(&registry_path)?).unwrap_or_default()
    } else {
        BTreeMap::new()
    };
    let key = workspace_root.to_string_lossy().to_string();
    if let Some(id) = registry.get(&key) {
        return Ok(id.clone());
    }
    let id = uuid::Uuid::new_v4().to_string();
    registry.insert(key, id.clone());
    fs::write(registry_path, serde_json::to_string_pretty(&registry)?)?;
    Ok(id)
}

fn sanitized_path(workspace_root: &Path) -> String {
    sanitized_segment(&workspace_root.to_string_lossy())
}

fn env_path(name: &str) -> Option<PathBuf> {
    env::var_os(name)
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
        .map(normalize)
}

fn workspace_cache_directory(
    cache_home: &Path,
    workspace_root: &Path,
    workspace_id: Option<&str>,
) -> PathBuf {
    let id = workspace_id
        .map(sanitized_segment)
        .unwrap_or_else(|| workspace_hash(workspace_root));
    cache_home.join("workspaces").join(id)
}

fn workspace_slug(workspace_root: &Path) -> String {
    workspace_root
        .file_name()
        .and_then(|name| name.to_str())
        .map(sanitized_segment)
        .unwrap_or_else(|| "workspace".to_string())
}

fn sanitized_segment(value: &str) -> String {
    let mut result = String::new();
    for ch in value.chars() {
        if ch.is_ascii_alphanumeric() || matches!(ch, '.' | '_' | '-') {
            result.push(ch);
        } else if !result.ends_with('-') {
            result.push('-');
        }
    }
    let trimmed = result.trim_matches('-');
    if trimmed.is_empty() {
        "workspace".to_string()
    } else {
        trimmed.chars().take(80).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn report_entry<'a>(entries: &'a [PathResolutionEntry], key: &str) -> &'a PathResolutionEntry {
        entries
            .iter()
            .find(|entry| entry.key == key)
            .unwrap_or_else(|| panic!("missing entry {key}: {entries:#?}"))
    }

    #[test]
    fn workspace_hash_matches_sha256_prefix_contract() {
        let path = PathBuf::from("/tmp/kast-workspace");
        let digest = Sha256::digest(path.to_string_lossy().as_bytes());
        assert_eq!(workspace_hash(&path), hex::encode(digest)[0..12]);
    }

    #[test]
    fn workspace_cache_directory_uses_explicit_workspace_id() {
        let cache_home = PathBuf::from("/home/devin/.cache/kast");
        let workspace_root = PathBuf::from("/workspace/kast");

        assert_eq!(
            workspace_cache_directory(&cache_home, &workspace_root, Some("org/repo main")),
            PathBuf::from("/home/devin/.cache/kast/workspaces/org-repo-main"),
        );
    }

    #[test]
    fn workspace_cache_directory_defaults_to_workspace_hash() {
        let cache_home = PathBuf::from("/home/devin/.cache/kast");
        let workspace_root = PathBuf::from("/workspace/kast");

        assert_eq!(
            workspace_cache_directory(&cache_home, &workspace_root, None),
            cache_home
                .join("workspaces")
                .join(workspace_hash(&workspace_root)),
        );
    }

    #[test]
    fn workspace_cache_environment_moves_runtime_state_out_of_install_root() {
        let workspace_root = PathBuf::from("/workspace/kast");
        let cache_home = PathBuf::from("/home/devin/.cache/kast");
        let mut config = KastConfig::defaults();
        config.paths.install_root = PathBuf::from("/opt/kast/current");
        config.apply_workspace_cache_home(&cache_home, &workspace_root, Some("kast-main"));

        assert_eq!(config.paths.cache_dir, cache_home);
        let workspace_dir = PathBuf::from("/home/devin/.cache/kast/workspaces/kast-main");
        assert_eq!(config.paths.logs_dir, workspace_dir.join("logs"));
        assert_eq!(config.paths.descriptor_dir, workspace_dir);
        assert!(!config.paths.descriptor_dir.starts_with("/opt/kast"));
    }

    #[test]
    fn configured_socket_dir_uses_workspace_local_socket_name() {
        let workspace_root = PathBuf::from("/workspace/kast");
        let mut config = KastConfig::defaults();
        config.paths.socket_dir = PathBuf::from("/home/devin/.cache/kast/workspaces/kast-main");

        assert_eq!(
            default_socket_path_for_config(&config, &workspace_root),
            PathBuf::from(format!(
                "/home/devin/.cache/kast/workspaces/kast-main/kast-{}.sock",
                workspace_hash(&workspace_root)
            )),
        );
    }

    #[test]
    fn long_configured_socket_dir_falls_back_to_short_temp_socket() {
        let workspace_root = PathBuf::from("/workspace/kast");
        let mut config = KastConfig::defaults();
        config.paths.socket_dir = PathBuf::from("/very")
            .join("long".repeat(25))
            .join("workspaces")
            .join("kast-main");

        assert!(socket_path_too_long(
            &config
                .paths
                .socket_dir
                .join(format!("kast-{}.sock", workspace_hash(&workspace_root)))
        ));
        assert_eq!(
            default_socket_path_for_config(&config, &workspace_root),
            default_socket_path(&workspace_root),
        );
    }

    #[test]
    fn default_socket_dir_uses_manifest_runtime_hash() {
        let workspace_root = PathBuf::from("/workspace/kast");
        let config = KastConfig::defaults();

        assert_eq!(
            default_socket_path_for_config(&config, &workspace_root),
            config
                .paths
                .socket_dir
                .join(format!("kast-{}.sock", workspace_hash(&workspace_root))),
        );
    }

    #[test]
    fn parses_github_remotes() {
        let ssh = parse_git_remote("git@github.com:amichne/kast.git").unwrap();
        assert_eq!(ssh.host, "github.com");
        assert_eq!(ssh.owner, "amichne");
        assert_eq!(ssh.repo, "kast");

        let https = parse_git_remote("https://github.com/amichne/kast.git").unwrap();
        assert_eq!(https.host, "github.com");
        assert_eq!(https.owner, "amichne");
        assert_eq!(https.repo, "kast");
    }

    #[test]
    fn git_workspace_data_directory_uses_remote_worktree_path() {
        let workspaces_root = PathBuf::from("/home/alex/.local/share/kast/state/workspaces");
        let workspace = GitWorkspace {
            toplevel: PathBuf::from("/work/kast"),
            common_dir: PathBuf::from("/work/kast/.git"),
            git_dir: PathBuf::from("/work/kast/.git"),
            remote: Some(GitRemote {
                host: "github.com".to_string(),
                owner: "amichne".to_string(),
                repo: "kast".to_string(),
            }),
        };

        assert_eq!(
            workspace_data_directory_for_git(&workspaces_root, &workspace),
            workspaces_root.join(format!(
                "git/github.com/amichne/kast/worktrees/kast--{}",
                git_worktree_hash(&workspace.toplevel, &workspace.git_dir)
            )),
        );
    }

    #[test]
    fn git_workspace_data_directory_isolates_sibling_worktrees() {
        let workspaces_root = PathBuf::from("/home/alex/.local/share/kast/state/workspaces");
        let common_dir = PathBuf::from("/work/kast/.git");
        let remote = GitRemote {
            host: "github.com".to_string(),
            owner: "amichne".to_string(),
            repo: "kast".to_string(),
        };
        let first = GitWorkspace {
            toplevel: PathBuf::from("/work/kast"),
            common_dir: common_dir.clone(),
            git_dir: common_dir.clone(),
            remote: Some(remote.clone()),
        };
        let second = GitWorkspace {
            toplevel: PathBuf::from("/work/kast-feature"),
            common_dir,
            git_dir: PathBuf::from("/work/kast/.git/worktrees/kast-feature"),
            remote: Some(remote),
        };

        assert_ne!(
            workspace_data_directory_for_git(&workspaces_root, &first),
            workspace_data_directory_for_git(&workspaces_root, &second),
        );
    }

    #[test]
    fn git_workspace_data_directory_supports_git_without_origin() {
        let workspaces_root = PathBuf::from("/home/alex/.local/share/kast/state/workspaces");
        let workspace = GitWorkspace {
            toplevel: PathBuf::from("/work/private"),
            common_dir: PathBuf::from("/work/private/.git"),
            git_dir: PathBuf::from("/work/private/.git/worktrees/private"),
            remote: None,
        };

        assert_eq!(
            workspace_data_directory_for_git(&workspaces_root, &workspace),
            workspaces_root.join(format!(
                "git/local/{}/worktrees/private--{}",
                git_common_dir_hash(&workspace.common_dir),
                git_worktree_hash(&workspace.toplevel, &workspace.git_dir)
            )),
        );
    }

    #[test]
    fn git_worktree_hash_matches_toplevel_and_git_dir_contract() {
        let toplevel = PathBuf::from("/work/kast");
        let git_dir = PathBuf::from("/work/kast/.git/worktrees/kast");

        assert_eq!(
            git_worktree_hash(&toplevel, &git_dir),
            sha256_prefix("/work/kast\n/work/kast/.git/worktrees/kast"),
        );
    }

    #[test]
    fn parses_runtime_default_backend() {
        let temp = tempfile::tempdir().unwrap();
        let config_file = temp.path().join("config.toml");
        fs::write(
            &config_file,
            r#"[runtime]
defaultBackend = "auto"
"#,
        )
        .unwrap();

        let mut config = KastConfig::defaults();
        config.apply(read_partial_config(&config_file).unwrap());

        assert_eq!(config.runtime.default_backend, RuntimeDefaultBackend::Auto);
    }

    #[test]
    fn install_owned_paths_in_toml_are_ignored() {
        let temp = tempfile::tempdir().unwrap();
        let install_root = temp.path().join("portable-kast");
        let config_file = temp.path().join("config.toml");
        fs::write(
            &config_file,
            format!(
                r#"[paths]
installRoot = "{}"
"#,
                install_root.display()
            ),
        )
        .unwrap();

        let mut config = KastConfig::defaults();
        let defaults = config.clone();
        config.apply(read_partial_config(&config_file).unwrap());

        assert_eq!(config.paths.install_root, defaults.paths.install_root);
        assert_eq!(config.paths.bin_dir, defaults.paths.bin_dir);
        assert_eq!(config.paths.lib_dir, defaults.paths.lib_dir);
        assert_eq!(config.paths.cache_dir, defaults.paths.cache_dir);
        assert_eq!(config.paths.logs_dir, defaults.paths.logs_dir);
        assert_eq!(config.paths.runtime_dir, defaults.paths.runtime_dir);
        assert_eq!(config.paths.descriptor_dir, defaults.paths.descriptor_dir);
        assert_eq!(config.paths.socket_dir, defaults.paths.socket_dir);
        assert_eq!(config.cli.binary_path, defaults.cli.binary_path);
        assert_eq!(
            config.backends.headless.runtime_libs_dir,
            defaults.backends.headless.runtime_libs_dir
        );
    }

    #[test]
    fn install_owned_path_overrides_are_ignored() {
        let temp = tempfile::tempdir().unwrap();
        let first_root = temp.path().join("first-root");
        let second_root = temp.path().join("second-root");
        let explicit_bin = temp.path().join("tools/bin");
        let explicit_lib = temp.path().join("runtime/lib");
        let explicit_cache = temp.path().join("runtime/cache");
        let explicit_logs = temp.path().join("runtime/logs");
        let explicit_runtime = temp.path().join("runtime");
        let explicit_descriptor = temp.path().join("runtime/descriptors");
        let explicit_socket = temp.path().join("runtime/socket");
        let explicit_binary = temp.path().join("custom/kast");
        let explicit_runtime_libs = temp.path().join("custom/runtime-libs");
        let first_config = temp.path().join("first.toml");
        let second_config = temp.path().join("second.toml");
        fs::write(
            &first_config,
            format!(
                r#"[paths]
installRoot = "{}"
binDir = "{}"
libDir = "{}"
cacheDir = "{}"
logsDir = "{}"
runtimeDir = "{}"
descriptorDir = "{}"
socketDir = "{}"

[backends.headless]
runtimeLibsDir = "{}"

[cli]
binaryPath = "{}"
"#,
                first_root.display(),
                explicit_bin.display(),
                explicit_lib.display(),
                explicit_cache.display(),
                explicit_logs.display(),
                explicit_runtime.display(),
                explicit_descriptor.display(),
                explicit_socket.display(),
                explicit_runtime_libs.display(),
                explicit_binary.display()
            ),
        )
        .unwrap();
        fs::write(
            &second_config,
            format!(
                r#"[paths]
installRoot = "{}"
"#,
                second_root.display()
            ),
        )
        .unwrap();

        let mut config = KastConfig::defaults();
        let defaults = config.clone();
        config.apply(read_partial_config(&first_config).unwrap());
        config.apply(read_partial_config(&second_config).unwrap());

        assert_eq!(config.paths.install_root, defaults.paths.install_root);
        assert_eq!(config.paths.bin_dir, defaults.paths.bin_dir);
        assert_eq!(config.paths.lib_dir, defaults.paths.lib_dir);
        assert_eq!(config.paths.cache_dir, defaults.paths.cache_dir);
        assert_eq!(config.paths.logs_dir, defaults.paths.logs_dir);
        assert_eq!(config.paths.runtime_dir, defaults.paths.runtime_dir);
        assert_eq!(config.paths.descriptor_dir, defaults.paths.descriptor_dir);
        assert_eq!(config.paths.socket_dir, defaults.paths.socket_dir);
        assert_eq!(config.cli.binary_path, defaults.cli.binary_path);
        assert_eq!(
            config.backends.headless.runtime_libs_dir,
            defaults.backends.headless.runtime_libs_dir
        );
    }

    #[test]
    fn default_config_template_omits_install_owned_paths() {
        let template = default_config_template().unwrap();
        let document = template.parse::<toml::Table>().unwrap();
        assert!(!document.contains_key("paths"), "{template}");
        assert!(!document.contains_key("cli"), "{template}");
        assert!(!document.contains_key("install"), "{template}");
        assert!(!template.contains("binaryPath"), "{template}");
        assert!(!template.contains("runtimeLibsDir"), "{template}");
    }

    #[test]
    fn path_resolution_entries_mark_default_derivations() {
        let temp = tempfile::tempdir().unwrap();
        let install_root = temp.path().join("portable-kast");
        let mut config = KastConfig::defaults();
        config.paths.install_root = install_root.clone();
        config.paths.bin_dir = temp.path().join("bin");
        config.paths.lib_dir = install_root.join("current/lib");
        config.paths.cache_dir = temp.path().join("cache");
        config.paths.logs_dir = temp.path().join("logs");
        config.paths.runtime_dir = install_root.join("runtime");
        config.paths.descriptor_dir = install_root.join("runtime/daemons");
        config.paths.socket_dir = install_root.join("runtime");
        config.cli.binary_path = temp.path().join("bin/kast");
        config.backends.headless.runtime_libs_dir =
            Some(install_root.join("current/lib/backends/headless/current/runtime-libs"));

        let entries = path_resolution_entries(
            &config,
            PathResolutionMode::Cli,
            PathResolutionEntryContext::from_states(false, false, false, false),
        );
        let entry = |key: &str| report_entry(&entries, key);

        assert_eq!(entry("paths.binDir").derived_from, None);
        assert_eq!(entry("paths.binDir").source, PathResolutionSource::Default);
        assert_eq!(entry("paths.cacheDir").derived_from, None);
        assert_eq!(entry("paths.logsDir").derived_from, None);
        assert_eq!(
            entry("paths.libDir").derived_from.as_deref(),
            Some("paths.installRoot")
        );
        assert_eq!(
            entry("paths.runtimeDir").derived_from.as_deref(),
            Some("paths.installRoot")
        );
        assert_eq!(
            entry("paths.descriptorDir").derived_from.as_deref(),
            Some("paths.runtimeDir")
        );
        assert_eq!(
            entry("paths.socketDir").derived_from.as_deref(),
            Some("paths.runtimeDir")
        );
        assert_eq!(
            entry("cli.binaryPath").derived_from.as_deref(),
            Some("paths.binDir")
        );
        assert_eq!(
            entry("backends.headless.runtimeLibsDir")
                .derived_from
                .as_deref(),
            Some("paths.libDir")
        );
        assert!(entry("cli.binaryPath").used_by_idea);
        assert!(!entry("backends.headless.runtimeLibsDir").used_by_idea);
    }

    #[test]
    fn path_resolution_entries_mark_manifest_owned_derivations() {
        let mut config = KastConfig::defaults();
        config.backends.headless.runtime_libs_dir = Some(PathBuf::from(
            "/opt/kast/current/lib/backends/headless/current/runtime-libs",
        ));

        let entries = path_resolution_entries(
            &config,
            PathResolutionMode::Cli,
            PathResolutionEntryContext::from_states(true, true, true, false),
        );
        let entry = |key: &str| report_entry(&entries, key);

        assert_eq!(
            entry("paths.installRoot").source,
            PathResolutionSource::Manifest
        );
        assert_eq!(entry("paths.binDir").source, PathResolutionSource::Manifest);
        assert_eq!(
            entry("paths.cacheDir").source,
            PathResolutionSource::Manifest
        );
        assert_eq!(
            entry("paths.logsDir").source,
            PathResolutionSource::Manifest
        );
        assert_eq!(
            entry("paths.runtimeDir").source,
            PathResolutionSource::Manifest
        );
        assert_eq!(entry("paths.runtimeDir").derived_from, None);
        assert_eq!(
            entry("paths.descriptorDir").derived_from.as_deref(),
            Some("paths.runtimeDir")
        );
        assert_eq!(
            entry("paths.socketDir").derived_from.as_deref(),
            Some("paths.runtimeDir")
        );
        assert_eq!(
            entry("backends.headless.runtimeLibsDir").source,
            PathResolutionSource::Manifest
        );
    }

    #[test]
    fn path_resolution_source_prefers_manifest_then_env_then_default() {
        assert_eq!(
            source_for_manifest_or_env_state(true, true),
            PathResolutionSource::Manifest
        );
        assert_eq!(
            source_for_manifest_or_env_state(false, true),
            PathResolutionSource::Env
        );
        assert_eq!(
            source_for_manifest_or_env_state(false, false),
            PathResolutionSource::Default
        );
    }

    #[test]
    fn env_value_present_matches_non_empty_path_env_contract() {
        assert!(!env_value_present(None));
        assert!(!env_value_present(Some(std::ffi::OsString::new())));
        assert!(env_value_present(Some(std::ffi::OsString::from(
            "/tmp/kast"
        ))));
    }

    #[test]
    fn parses_runtime_idea_launch() {
        let temp = tempfile::tempdir().unwrap();
        let config_file = temp.path().join("config.toml");
        fs::write(
            &config_file,
            r#"[runtime]
defaultBackend = "idea"

[runtime.ideaLaunch]
enabled = true
command = "/usr/local/bin/idea"
waitTimeoutMillis = 45678
requireInstalledPlugin = false
"#,
        )
        .unwrap();

        let mut config = KastConfig::defaults();
        config.apply(read_partial_config(&config_file).unwrap());

        assert_eq!(config.runtime.default_backend, RuntimeDefaultBackend::Idea);
        assert!(config.runtime.idea_launch.enabled);
        assert_eq!(
            config.runtime.idea_launch.command,
            PathBuf::from("/usr/local/bin/idea")
        );
        assert_eq!(config.runtime.idea_launch.wait_timeout_millis.get(), 45_678);
        assert!(!config.runtime.idea_launch.require_installed_plugin);
    }

    #[test]
    fn project_open_defaults_to_disabled_copilot_profile_with_git_excludes() {
        let config = KastConfig::defaults();

        assert!(!config.project_open.profile_auto_init);
        assert_eq!(config.project_open.profile, ProjectOpenProfile::CopilotLsp);
        assert_eq!(config.project_open.agent_harness, AgentSetupHarness::Auto);
        assert!(config.project_open.auto_exclude_git);
    }

    #[test]
    fn parses_project_open_auto_init_policy() {
        let temp = tempfile::tempdir().unwrap();
        let config_file = temp.path().join("config.toml");
        fs::write(
            &config_file,
            r#"[projectOpen]
profileAutoInit = true
profile = "copilot-lsp"
agentHarness = "instructions"
autoExcludeGit = false
"#,
        )
        .unwrap();

        let mut config = KastConfig::defaults();
        config.apply(read_partial_config(&config_file).unwrap());

        assert!(config.project_open.profile_auto_init);
        assert_eq!(config.project_open.profile, ProjectOpenProfile::CopilotLsp);
        assert_eq!(
            config.project_open.agent_harness,
            AgentSetupHarness::Instructions
        );
        assert!(!config.project_open.auto_exclude_git);
    }

    #[test]
    fn rejects_invalid_project_open_agent_harness() {
        let temp = tempfile::tempdir().unwrap();
        let config_file = temp.path().join("config.toml");
        fs::write(
            &config_file,
            r#"[projectOpen]
agentHarness = "mcp"
"#,
        )
        .unwrap();

        let error = read_partial_config(&config_file).unwrap_err();

        assert_eq!(error.code, "CONFIG_ERROR");
        assert!(error.message.contains("mcp"), "{}", error.message);
        assert!(error.message.contains("instructions"), "{}", error.message);
    }

    #[test]
    fn rejects_invalid_runtime_default_backend() {
        let temp = tempfile::tempdir().unwrap();
        let config_file = temp.path().join("config.toml");
        fs::write(
            &config_file,
            r#"[runtime]
defaultBackend = "sidecar"
"#,
        )
        .unwrap();

        let error = read_partial_config(&config_file).unwrap_err();

        assert_eq!(error.code, "CONFIG_ERROR");
        assert!(error.message.contains("sidecar"), "{}", error.message);
        assert!(error.message.contains("headless"), "{}", error.message);
    }

    #[test]
    fn rejects_invalid_project_open_profile() {
        let temp = tempfile::tempdir().unwrap();
        let config_file = temp.path().join("config.toml");
        fs::write(
            &config_file,
            r#"[projectOpen]
profile = "unknown"
"#,
        )
        .unwrap();

        let error = read_partial_config(&config_file).unwrap_err();

        assert_eq!(error.code, "CONFIG_ERROR");
        assert!(error.message.contains("unknown"), "{}", error.message);
        assert!(error.message.contains("copilot-lsp"), "{}", error.message);
    }
}
