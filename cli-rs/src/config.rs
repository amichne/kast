use crate::cli::{BackendName, DaemonStartArgs};
use crate::error::{CliError, Result};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::env;
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
    pub auto_exclude_git: bool,
}

impl ProjectOpenConfig {
    fn is_default(&self) -> bool {
        !self.profile_auto_init
            && self.profile == ProjectOpenProfile::CopilotLsp
            && self.auto_exclude_git
    }
}

impl Default for ProjectOpenConfig {
    fn default() -> Self {
        Self {
            profile_auto_init: false,
            profile: ProjectOpenProfile::CopilotLsp,
            auto_exclude_git: true,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum ProjectOpenProfile {
    CopilotLsp,
}

impl ProjectOpenProfile {
    pub fn canonical(self) -> &'static str {
        match self {
            Self::CopilotLsp => "copilot-lsp",
        }
    }
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
    paths: Option<PartialPaths>,
    backends: Option<PartialBackends>,
    cli: Option<PartialCli>,
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
#[serde(rename_all = "camelCase")]
struct PartialPaths {
    install_root: Option<PathBuf>,
    bin_dir: Option<PathBuf>,
    lib_dir: Option<PathBuf>,
    cache_dir: Option<PathBuf>,
    logs_dir: Option<PathBuf>,
    descriptor_dir: Option<PathBuf>,
    socket_dir: Option<PathBuf>,
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
    runtime_libs_dir: Option<Option<PathBuf>>,
    idea_home: Option<Option<PathBuf>>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialIdea {
    enabled: Option<bool>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialCli {
    binary_path: Option<PathBuf>,
}

impl KastConfig {
    pub fn defaults() -> Self {
        let install_root = home_dir().join(".kast");
        let bin_dir = install_root.join("bin");
        let lib_dir = install_root.join("lib");
        let cache_dir = install_root.join("cache");
        let logs_dir = install_root.join("logs");
        let descriptor_dir = cache_dir.join("daemons");
        let socket_dir = env::temp_dir();
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
                install_root,
                bin_dir: bin_dir.clone(),
                lib_dir: lib_dir.clone(),
                cache_dir,
                logs_dir,
                descriptor_dir,
                socket_dir,
            },
            backends: BackendsConfig {
                headless: HeadlessBackendConfig {
                    enabled: true,
                    runtime_libs_dir: Some(lib_dir.join("backends/headless/current/runtime-libs")),
                    idea_home: None,
                },
                idea: IdeaBackendConfig { enabled: true },
            },
            cli: CliConfig {
                binary_path: env::current_exe().unwrap_or_else(|_| bin_dir.join("kast")),
            },
        }
    }

    pub fn load_global() -> Result<Self> {
        let mut config = Self::defaults();
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
        if let Some(paths) = partial.paths {
            if let Some(value) = paths.install_root {
                self.paths.install_root = normalize(value);
                self.paths.bin_dir = self.paths.install_root.join("bin");
                self.paths.lib_dir = self.paths.install_root.join("lib");
                self.paths.cache_dir = self.paths.install_root.join("cache");
                self.paths.logs_dir = self.paths.install_root.join("logs");
                self.paths.descriptor_dir = self.paths.cache_dir.join("daemons");
                self.cli.binary_path = self.paths.bin_dir.join("kast");
                self.backends.headless.runtime_libs_dir = Some(
                    self.paths
                        .lib_dir
                        .join("backends/headless/current/runtime-libs"),
                );
            }
            if let Some(value) = paths.bin_dir {
                self.paths.bin_dir = normalize(value);
                self.cli.binary_path = self.paths.bin_dir.join("kast");
            }
            if let Some(value) = paths.lib_dir {
                self.paths.lib_dir = normalize(value);
                self.backends.headless.runtime_libs_dir = Some(
                    self.paths
                        .lib_dir
                        .join("backends/headless/current/runtime-libs"),
                );
            }
            if let Some(value) = paths.cache_dir {
                self.paths.cache_dir = normalize(value);
                self.paths.descriptor_dir = self.paths.cache_dir.join("daemons");
            }
            if let Some(value) = paths.logs_dir {
                self.paths.logs_dir = normalize(value);
            }
            if let Some(value) = paths.descriptor_dir {
                self.paths.descriptor_dir = normalize(value);
            }
            if let Some(value) = paths.socket_dir {
                self.paths.socket_dir = normalize(value);
            }
        }
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
            if let Some(headless) = backends.headless {
                if let Some(value) = headless.enabled {
                    self.backends.headless.enabled = value;
                }
                if let Some(value) = headless.runtime_libs_dir {
                    self.backends.headless.runtime_libs_dir = value.map(normalize);
                }
                if let Some(value) = headless.idea_home {
                    self.backends.headless.idea_home = value.map(normalize);
                }
            }
            if let Some(idea) = backends.idea
                && let Some(value) = idea.enabled
            {
                self.backends.idea.enabled = value;
            }
        }
        if let Some(cli) = partial.cli
            && let Some(value) = cli.binary_path
        {
            self.cli.binary_path = normalize(value);
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
    Ok(toml::to_string_pretty(&KastConfig::defaults())?)
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
            "Cannot locate backend runtime-libs. Set backends.headless.runtimeLibsDir in `kast config init` output, or pass --runtime-libs-dir.",
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
    env::var_os("KAST_CONFIG_HOME")
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
        .map(normalize)
        .unwrap_or_else(|| normalize(home_dir().join(".config/kast")))
}

pub fn global_config_path() -> PathBuf {
    kast_config_home().join("config.toml")
}

pub fn home_dir() -> PathBuf {
    env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."))
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
    if let Some(workspace) = git_workspace(&root) {
        return Ok(workspace_data_directory_for_git(&home_dir(), &workspace));
    }
    if root.starts_with(env::temp_dir()) {
        return Ok(root.join(".gradle/kast"));
    }
    let id = local_workspace_id(&root)?;
    Ok(home_dir()
        .join(".kast/workspaces/local")
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
    let configured = if config.paths.socket_dir == env::temp_dir() {
        default_socket_path(workspace_root)
    } else {
        config.paths.socket_dir.join("kast.sock")
    };
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

fn workspace_data_directory_for_git(home: &Path, workspace: &GitWorkspace) -> PathBuf {
    let repo_root = if let Some(remote) = &workspace.remote {
        home.join(".kast/workspaces/git")
            .join(&remote.host)
            .join(&remote.owner)
            .join(&remote.repo)
    } else {
        home.join(".kast/workspaces/git/local")
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
    let registry_path = home_dir().join(".kast/workspaces/local-workspaces.json");
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
            PathBuf::from("/home/devin/.cache/kast/workspaces/kast-main/kast.sock"),
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
            &config.paths.socket_dir.join("kast.sock")
        ));
        assert_eq!(
            default_socket_path_for_config(&config, &workspace_root),
            default_socket_path(&workspace_root),
        );
    }

    #[test]
    fn default_socket_dir_keeps_legacy_temp_hash() {
        let workspace_root = PathBuf::from("/workspace/kast");
        let config = KastConfig::defaults();

        assert_eq!(
            default_socket_path_for_config(&config, &workspace_root),
            default_socket_path(&workspace_root),
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
        let home = PathBuf::from("/home/alex");
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
            workspace_data_directory_for_git(&home, &workspace),
            home.join(format!(
                ".kast/workspaces/git/github.com/amichne/kast/worktrees/kast--{}",
                git_worktree_hash(&workspace.toplevel, &workspace.git_dir)
            )),
        );
    }

    #[test]
    fn git_workspace_data_directory_isolates_sibling_worktrees() {
        let home = PathBuf::from("/home/alex");
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
            workspace_data_directory_for_git(&home, &first),
            workspace_data_directory_for_git(&home, &second),
        );
    }

    #[test]
    fn git_workspace_data_directory_supports_git_without_origin() {
        let home = PathBuf::from("/home/alex");
        let workspace = GitWorkspace {
            toplevel: PathBuf::from("/work/private"),
            common_dir: PathBuf::from("/work/private/.git"),
            git_dir: PathBuf::from("/work/private/.git/worktrees/private"),
            remote: None,
        };

        assert_eq!(
            workspace_data_directory_for_git(&home, &workspace),
            home.join(format!(
                ".kast/workspaces/git/local/{}/worktrees/private--{}",
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
autoExcludeGit = false
"#,
        )
        .unwrap();

        let mut config = KastConfig::defaults();
        config.apply(read_partial_config(&config_file).unwrap());

        assert!(config.project_open.profile_auto_init);
        assert_eq!(config.project_open.profile, ProjectOpenProfile::CopilotLsp);
        assert!(!config.project_open.auto_exclude_git);
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
