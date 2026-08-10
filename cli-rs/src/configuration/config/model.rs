#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct KastConfig {
    pub server: ServerConfig,
    pub indexer: IndexerConfig,
    pub codex: CodexConfig,
    pub indexing: IndexingConfig,
    pub cache: CacheConfig,
    pub watcher: WatcherConfig,
    pub gradle: GradleConfig,
    pub telemetry: TelemetryConfig,
    pub profiling: ProfilingConfig,
    pub paths: PathsConfig,
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
pub struct IndexerConfig {
    pub runtime_libs_dir: Option<PathBuf>,
    pub host_home: Option<PathBuf>,
    pub host_command: PathBuf,
    pub max_heap_megabytes: IndexerMaxHeapMegabytes,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(transparent)]
pub struct IndexerMaxHeapMegabytes(NonZeroU32);

impl IndexerMaxHeapMegabytes {
    pub(crate) fn jvm_argument(self) -> String {
        format!("-Xmx{}m", self.0)
    }
}

impl Default for IndexerMaxHeapMegabytes {
    fn default() -> Self {
        Self(NonZeroU32::new(2_048).expect("default indexer heap is positive"))
    }
}

impl Default for IndexerConfig {
    fn default() -> Self {
        Self {
            runtime_libs_dir: None,
            host_home: None,
            host_command: PathBuf::from("idea"),
            max_heap_megabytes: IndexerMaxHeapMegabytes::default(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct CodexConfig {
    pub hooks: CodexHooksConfig,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CodexHooksConfig {
    pub enabled: bool,
    pub session_start: bool,
    pub post_tool_use: bool,
}

impl Default for CodexHooksConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            session_start: true,
            post_tool_use: true,
        }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct IndexingConfig {
    pub critical_paths: Vec<String>,
    pub ignored_paths: Vec<String>,
    pub graph: GraphIndexingConfig,
    pub relationships: RelationshipIndexingConfig,
    pub identifier_index_wait_millis: u64,
    pub remote: RemoteIndexConfig,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GraphIndexingConfig {
    pub batch_size: NonZeroU32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RelationshipIndexingConfig {
    pub enabled: bool,
    pub batch_size: u32,
    pub parallelism: u32,
    pub module_priority_depth: u32,
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
#[serde(rename_all = "camelCase")]
pub struct CliConfig {
    pub binary_path: PathBuf,
    pub dynamic_output: bool,
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

include!("model/path_resolution_context.rs");
