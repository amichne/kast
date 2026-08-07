pub fn raw_request_passthrough(
    raw_request: String,
    requested_workspace_root: Option<PathBuf>,
) -> Result<String> {
    let session = raw_rpc_session(requested_workspace_root.clone())?;
    raw_request_passthrough_in_session(raw_request, requested_workspace_root, &session)
}

#[derive(Debug, Clone)]
pub struct RawRpcSession {
    admission: AdmittedIndexerRuntime,
    socket_path: PathBuf,
    response_timeouts: RpcResponseTimeoutPolicy,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct RpcResponseTimeoutPolicy {
    ordinary: Duration,
    workspace_transition: Duration,
}

impl RpcResponseTimeoutPolicy {
    /// Proof transition: `Duration -> RpcResponseTimeoutPolicy`.
    ///
    /// Retains the configured ordinary request timeout while deriving a
    /// separate finite response allowance for workspace reconciliation. The
    /// latter covers the backend's one-hour maximum progress wait plus a small
    /// transport reserve and can never shorten the ordinary allowance.
    fn derive(ordinary: Duration) -> Self {
        Self {
            ordinary,
            workspace_transition: ordinary.max(WORKSPACE_TRANSITION_RESPONSE_TIMEOUT),
        }
    }

    fn ordinary(self) -> Duration {
        self.ordinary
    }

    /// Boundary transition: `JSON-RPC request -> Duration`.
    ///
    /// Extracts the protocol method only to select the already-typed timeout
    /// policy consumed by the Unix socket read boundary.
    fn for_request(self, raw_request: &str) -> Result<Duration> {
        let request: Value = serde_json::from_str(raw_request)?;
        Ok(
            match WorkspaceTransitionRpcMethod::derive(
                request.get("method").and_then(Value::as_str),
            ) {
                Some(_) => self.workspace_transition,
                None => self.ordinary,
            },
        )
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum WorkspaceTransitionRpcMethod {
    SemanticGraph,
    WorkspaceRefresh,
    ApplyEdits,
    ExactFileImageCas,
    RecoverMutationScratch,
}

impl WorkspaceTransitionRpcMethod {
    /// Boundary transition: `Option<&str> -> Option<WorkspaceTransitionRpcMethod>`.
    ///
    /// Refines the untrusted JSON-RPC method field into the closed set whose
    /// response can include progress-bounded workspace reconciliation. An
    /// absent or unrelated method has no transition-timeout authority.
    fn derive(method: Option<&str>) -> Option<Self> {
        match method {
            Some("raw/semantic-graph") => Some(Self::SemanticGraph),
            Some("raw/workspace-refresh") => Some(Self::WorkspaceRefresh),
            Some("raw/apply-edits") => Some(Self::ApplyEdits),
            Some("raw/exact-file-image-cas") => Some(Self::ExactFileImageCas),
            Some("raw/recover-mutation-scratch") => Some(Self::RecoverMutationScratch),
            _ => None,
        }
    }
}

const WORKSPACE_TRANSITION_RESPONSE_TIMEOUT: Duration = Duration::from_secs(60 * 60 + 5);

#[derive(Debug, Clone)]
pub(crate) struct SemanticWorkspaceRead {
    session: RawRpcSession,
    published: crate::published_workspace::PublishedWorkspaceDatabase,
}

impl SemanticWorkspaceRead {
    pub(crate) fn published(
        &self,
    ) -> &crate::published_workspace::PublishedWorkspaceDatabase {
        &self.published
    }

    pub(crate) fn database(&self) -> &Path {
        self.published.database()
    }

    pub(crate) fn revalidate(&self) -> Result<()> {
        self.published.revalidate()?;
        self.session.admission.validate_current()?;
        let status = rpc::request_wait_for_close::<RuntimeStatusWireResponse>(
            Path::new(&self.session.socket_path),
            "runtime/status",
            Value::Object(Default::default()),
            self.session.response_timeouts.ordinary(),
        )?
        .into_status()?;
        validate_runtime_status_identity(&self.session.admission.candidate().descriptor, &status)?;
        require_published_runtime_status(&status, &self.published)
    }
}

impl RawRpcSession {
    pub(crate) fn semantic_read(&self) -> Result<SemanticWorkspaceRead> {
        self.admission.validate_current()?;
        let published = crate::published_workspace::resolve_published_workspace_database(
            self.admission.workspace_root(),
        )?;
        let status = self
            .admission
            .candidate()
            .runtime_status
            .as_ref()
            .ok_or_else(published_runtime_status_unavailable)?;
        require_published_runtime_status(status, &published)?;
        Ok(SemanticWorkspaceRead {
            session: self.clone(),
            published,
        })
    }
}

pub(crate) fn semantic_workspace_read_ready(
    requested_workspace_root: Option<PathBuf>,
) -> Result<SemanticWorkspaceRead> {
    raw_rpc_session_ready(requested_workspace_root)?.semantic_read()
}

pub(crate) fn semantic_workspace_read_for_admission(
    admission: &SemanticWorkspaceAdmission,
) -> Result<SemanticWorkspaceRead> {
    raw_rpc_session_for_admission(admission.clone()).semantic_read()
}

pub fn raw_rpc_session(
    requested_workspace_root: Option<PathBuf>,
) -> Result<RawRpcSession> {
    raw_rpc_session_from_route(semantic_workspace_route(requested_workspace_root)?)
}

pub fn raw_rpc_session_ready(
    requested_workspace_root: Option<PathBuf>,
) -> Result<RawRpcSession> {
    raw_rpc_session_from_route(semantic_workspace_route_ready(requested_workspace_root)?)
}

fn raw_rpc_session_from_route(route: SemanticWorkspaceRoute) -> Result<RawRpcSession> {
    match route {
        SemanticWorkspaceRoute::Admitted(admission) => {
            Ok(raw_rpc_session_for_admission(*admission))
        }
        SemanticWorkspaceRoute::Rejected(rejection) => Err(rejection.into_cli_error()),
    }
}

pub(crate) fn raw_rpc_session_for_admission(
    admission: AdmittedIndexerRuntime,
) -> RawRpcSession {
    let advertised_timeout_millis = admission
        .candidate()
        .capabilities
        .as_ref()
        .and_then(|capabilities| capabilities.pointer("/limits/requestTimeoutMillis"))
        .and_then(Value::as_u64)
        .unwrap_or(0);
    RawRpcSession {
        socket_path: PathBuf::from(&admission.candidate().descriptor.socket_path),
        response_timeouts: RpcResponseTimeoutPolicy::derive(Duration::from_millis(
            admission
                .config()
                .server
                .request_timeout_millis
                .max(advertised_timeout_millis)
                .saturating_add(5_000)
                .max(1),
        )),
        admission,
    }
}

pub fn raw_request_passthrough_in_session(
    raw_request: String,
    requested_workspace_root: Option<PathBuf>,
    session: &RawRpcSession,
) -> Result<String> {
    session.admission.validate_current()?;
    validate_raw_rpc_workspace_root(requested_workspace_root.as_deref(), session)?;
    if is_local_semantic_rpc(&raw_request)? {
        let read = session.semantic_read()?;
        let response = try_handle_local_raw_rpc(
            &raw_request,
            session.admission.workspace_root(),
            read.published(),
        );
        read.revalidate()?;
        return response?.ok_or_else(|| {
            CliError::new(
                "RPC_LOCAL_DISPATCH_INVALID",
                "A local semantic RPC method had no local handler.",
            )
        });
    }
    rpc::raw_wait_for_close(
        Path::new(&session.socket_path),
        &raw_request,
        session.response_timeouts.for_request(&raw_request)?,
    )
}

fn try_handle_local_raw_rpc(
    raw_request: &str,
    workspace_root: &Path,
    published: &crate::published_workspace::PublishedWorkspaceDatabase,
) -> Result<Option<String>> {
    if let Some(response) = crate::repository_intelligence::try_handle_raw_rpc(
        raw_request,
        workspace_root,
        published,
    )? {
        return Ok(Some(response));
    }
    if let Some(response) = crate::metrics::try_handle_raw_rpc(raw_request, workspace_root, published)? {
        return Ok(Some(response));
    }
    crate::symbol_query::try_handle_raw_rpc(raw_request, workspace_root, published)
}

fn is_local_semantic_rpc(raw_request: &str) -> Result<bool> {
    let request: Value = serde_json::from_str(raw_request)?;
    Ok(matches!(
        request.get("method").and_then(Value::as_str),
        Some("graph/coverage" | "repository/query" | "database/metrics" | "symbol/query")
    ))
}

fn validate_raw_rpc_workspace_root(
    requested_workspace_root: Option<&Path>,
    session: &RawRpcSession,
) -> Result<()> {
    let Some(requested_workspace_root) = requested_workspace_root else {
        return Ok(());
    };
    let requested_workspace_root = std::fs::canonicalize(requested_workspace_root).map_err(|error| {
        CliError::new(
            "WORKSPACE_ROOT_INVALID",
            format!(
                "Workspace root {} could not be canonicalized: {error}",
                requested_workspace_root.display()
            ),
        )
    })?;
    if requested_workspace_root != session.admission.workspace_root() {
        return Err(CliError::new(
            "SEMANTIC_WORKSPACE_MISMATCH",
            "The raw RPC workspace root does not match the admitted indexer workspace.",
        ));
    }
    Ok(())
}

fn require_published_runtime_status(
    status: &RuntimeStatusResponse,
    published: &crate::published_workspace::PublishedWorkspaceDatabase,
) -> Result<()> {
    if !is_ready(status) {
        return Err(CliError::new(
            "PUBLISHED_WORKSPACE_MOVED",
            "The indexer runtime left READY while the semantic read was in progress.",
        ));
    }
    let expected = status
        .published_workspace_generation
        .as_ref()
        .ok_or_else(published_runtime_status_unavailable)?;
    published.require_manifest(expected)
}

fn published_runtime_status_unavailable() -> CliError {
    CliError::new(
        "PUBLISHED_WORKSPACE_UNAVAILABLE",
        "The READY indexer runtime did not advertise its published workspace generation.",
    )
}

pub fn capabilities(args: RuntimeArgs) -> Result<Value> {
    let ensure = workspace_ensure(args)?;
    ensure.selected.capabilities.ok_or_else(|| {
        CliError::new(
            "CAPABILITIES_UNAVAILABLE",
            "Runtime capabilities are unavailable",
        )
    })
}
