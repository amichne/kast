#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ServerInstanceDescriptor {
    pub workspace_root: String,
    pub backend_name: String,
    pub backend_version: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub runtime_instance_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub process_start_epoch_millis: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub owner_uid: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub socket_file_identity: Option<RuntimeSocketFileIdentity>,
    #[serde(default = "default_transport")]
    pub transport: String,
    pub socket_path: String,
    #[serde(default)]
    pub pid: u64,
    #[serde(default = "schema_version")]
    pub schema_version: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RuntimeSocketFileIdentity {
    pub device: u64,
    pub inode: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeStatusResponse {
    pub state: RuntimeState,
    pub healthy: bool,
    pub active: bool,
    pub indexing: bool,
    pub backend_name: String,
    pub backend_version: String,
    pub workspace_root: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub warnings: Vec<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub source_module_names: Vec<String>,
    #[serde(default, skip_serializing_if = "serde_json::Map::is_empty")]
    pub dependent_module_names_by_source_module_name: serde_json::Map<String, Value>,
    #[serde(default)]
    pub reference_index_ready: bool,
    #[serde(default)]
    pub reference_coverage_state: ReferenceCoverageState,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub reference_coverage_limitations: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub published_workspace_generation:
        Option<crate::published_workspace::PublishedWorkspaceGenerationManifest>,
    #[serde(default = "schema_version")]
    pub schema_version: u32,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ReferenceCoverageState {
    Complete,
    Qualified,
    Incomplete,
    #[default]
    Unavailable,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SemanticMutationCapability {
    ApplyEdits,
    ExactFileObservation,
    ExactFileImageCas,
    FileOperations,
    OptimizeImports,
    PlanAddDeclaration,
    PlanAddFile,
    PlanReplacement,
    RefreshWorkspace,
    Rename,
    MutationScratchRecovery,
    VerifyMutationPostcondition,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RuntimeState {
    Starting,
    Indexing,
    Ready,
    Degraded,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum StaleDescriptorPolicy {
    Preserve,
    Prune,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeCandidateStatus {
    pub descriptor_path: String,
    pub descriptor: ServerInstanceDescriptor,
    pub pid_alive: bool,
    pub reachable: bool,
    pub ready: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub runtime_status: Option<RuntimeStatusResponse>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub capabilities: Option<Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error_message: Option<String>,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceStatusResult {
    pub workspace_root: String,
    pub descriptor_directory: String,
    pub path_resolution: PathResolutionReport,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub selected: Option<RuntimeCandidateStatus>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub semantic_graph: Option<crate::repository_intelligence::SemanticGraphReadiness>,
    pub candidates: Vec<RuntimeCandidateStatus>,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceEnsureResult {
    pub workspace_root: String,
    pub descriptor_directory: String,
    pub path_resolution: PathResolutionReport,
    pub started: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub log_file: Option<String>,
    pub selected: RuntimeCandidateStatus,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub note: Option<String>,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DaemonStopResult {
    pub workspace_root: String,
    pub backend_name: String,
    pub stopped: bool,
    pub stopped_count: usize,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub descriptor_path: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub pid: Option<u64>,
    pub forced: bool,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub candidates: Vec<RuntimeStopAction>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub warnings: Vec<String>,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceRestartResult {
    pub workspace_root: String,
    pub backend_name: String,
    pub stop: DaemonStopResult,
    pub ensure: WorkspaceEnsureResult,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeStopAction {
    pub backend_name: String,
    pub descriptor_path: String,
    pub pid: u64,
    pub pid_alive: bool,
    pub reachable: bool,
    #[serde(skip_serializing_if = "is_false")]
    pub lifecycle_accepted: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub lifecycle_method: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub lifecycle_action: Option<String>,
    pub terminated: bool,
    pub descriptor_deleted: bool,
    pub forced: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub skipped_reason: Option<String>,
    pub schema_version: u32,
}

#[derive(Debug, Clone)]
struct RegisteredDescriptor {
    id: String,
    descriptor: ServerInstanceDescriptor,
}

struct WorkspaceInspection {
    candidates: Vec<RuntimeCandidateStatus>,
}
