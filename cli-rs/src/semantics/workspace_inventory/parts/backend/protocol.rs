#[derive(Debug, Clone, PartialEq, Eq, Error)]
pub(crate) enum BackendRpcFailure {
    #[error("backend API error `{code}`: {message}")]
    Api {
        code: String,
        message: String,
        reason: Option<String>,
    },
    #[error("backend transport failed: {0}")]
    Transport(String),
    #[error("backend returned invalid workspace inventory: {0}")]
    InvalidResponse(String),
    #[error("backend workspace path `{path}` cannot be proven contained: {reason}")]
    Containment { path: PathBuf, reason: String },
}

pub(crate) trait BackendWorkspaceRpc {
    fn request(&mut self, request: Value) -> Result<Value, BackendRpcFailure>;
}

pub(crate) struct RawRpcWorkspaceBackend<'a> {
    session: &'a crate::runtime::RawRpcSession,
    workspace_root: PathBuf,
}

impl<'a> RawRpcWorkspaceBackend<'a> {
    pub(crate) fn new(
        session: &'a crate::runtime::RawRpcSession,
        workspace_root: &WorkspaceRoot,
    ) -> Self {
        Self {
            session,
            workspace_root: workspace_root.as_path().to_path_buf(),
        }
    }
}

impl BackendWorkspaceRpc for RawRpcWorkspaceBackend<'_> {
    fn request(&mut self, request: Value) -> Result<Value, BackendRpcFailure> {
        let encoded = serde_json::to_string(&request)
            .map_err(|error| BackendRpcFailure::InvalidResponse(error.to_string()))?;
        let raw = crate::runtime::raw_request_passthrough_in_session(
            encoded,
            Some(self.workspace_root.clone()),
            self.session,
        )
        .map_err(|error| BackendRpcFailure::Transport(error.to_string()))?;
        decode_rpc_response(&raw)
    }
}

fn decode_rpc_response(raw: &str) -> Result<Value, BackendRpcFailure> {
    let response: Value = serde_json::from_str(raw)
        .map_err(|error| BackendRpcFailure::InvalidResponse(error.to_string()))?;
    if let Some(error) = response.get("error") {
        let data = error.get("data");
        let code = data
            .and_then(|value| value.get("code"))
            .or_else(|| error.get("code"))
            .and_then(Value::as_str)
            .unwrap_or("RPC_ERROR")
            .to_string();
        let message = data
            .and_then(|value| value.get("message"))
            .or_else(|| error.get("message"))
            .and_then(Value::as_str)
            .unwrap_or("JSON-RPC request failed")
            .to_string();
        let reason = data
            .and_then(|value| value.get("details"))
            .and_then(|value| value.get("reason"))
            .and_then(Value::as_str)
            .map(str::to_string);
        return Err(BackendRpcFailure::Api {
            code,
            message,
            reason,
        });
    }
    response
        .get("result")
        .cloned()
        .ok_or_else(|| BackendRpcFailure::InvalidResponse("missing result".to_string()))
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceInventoryResponse {
    snapshot_token: String,
    #[serde(default)]
    modules: Vec<WorkspaceModuleResponse>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceModuleResponse {
    name: String,
    source_roots: Vec<PathBuf>,
    content_roots: Vec<PathBuf>,
    dependency_module_names: Vec<String>,
    #[serde(default)]
    files: Vec<PathBuf>,
    returned_file_count: usize,
    files_truncated: bool,
    file_count: usize,
    #[serde(default)]
    next_page_token: Option<String>,
}

#[derive(Debug)]
struct MetadataModule {
    name: BackendModuleName,
    raw_source_roots: Vec<PathBuf>,
    source_roots: BTreeSet<WorkspaceContainedRoot>,
    raw_content_roots: Vec<PathBuf>,
    content_roots: BTreeSet<WorkspaceContainedRoot>,
    dependency_module_names: BTreeSet<BackendModuleName>,
    file_count: usize,
    containment_complete: bool,
}

#[derive(Debug)]
struct BackendAttempt {
    snapshot: BackendWorkspaceSnapshotToken,
    modules: Vec<MetadataModule>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum BackendFailureScope {
    Metadata,
    WholeAttempt,
}

#[derive(Debug)]
struct BackendAttemptFailure {
    failure: BackendRpcFailure,
    scope: BackendFailureScope,
    modules: BTreeMap<BackendModuleName, BackendModuleInventory>,
}
