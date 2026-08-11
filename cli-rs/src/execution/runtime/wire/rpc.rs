pub fn raw_request_passthrough(
    raw_request: String,
    requested_workspace_root: Option<PathBuf>,
) -> Result<String> {
    let session = raw_rpc_session(requested_workspace_root.clone())?;
    raw_request_passthrough_in_session(raw_request, requested_workspace_root, &session)
}

#[derive(Debug, Clone)]
pub struct RawRpcSession<C: lifecycle_typestate::RequiredCapability = lifecycle_typestate::SourceCapability> {
    admission: AdmittedIndexerRuntime<C>,
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
    /// separate finite response allowance for complete workspace-transition
    /// dispatch. The latter covers both graph passes around the backend's
    /// one-hour maximum progress wait, then adds client-only transport and
    /// response-serialization headroom outside the server deadline.
    fn derive(ordinary: Duration) -> Self {
        let transition_dispatch = MAXIMUM_WORKSPACE_RECONCILIATION_WAIT
            .saturating_add(ordinary.saturating_mul(SEMANTIC_GRAPH_PASS_COUNT));
        Self {
            ordinary,
            workspace_transition: transition_dispatch
                .saturating_add(CLIENT_RESPONSE_COMPLETION_RESERVE),
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
            match RpcResponseDeadlineAuthority::derive(
                request.get("method").and_then(Value::as_str),
            ) {
                RpcResponseDeadlineAuthority::WorkspaceTransition => self.workspace_transition,
                RpcResponseDeadlineAuthority::Ordinary => self.ordinary,
            },
        )
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum RpcResponseDeadlineAuthority {
    WorkspaceTransition,
    Ordinary,
}

impl RpcResponseDeadlineAuthority {
    /// Boundary transition: `Option<&str> -> RpcResponseDeadlineAuthority`.
    ///
    /// Derives one closed timeout authority from the untrusted JSON-RPC method
    /// field. The output need not retain the method: it carries only the
    /// constrained fact consumed by exhaustive socket-read policy selection.
    fn derive(method: Option<&str>) -> Self {
        match method {
            Some(
                "raw/semantic-graph"
                | "raw/workspace-refresh"
                | "raw/apply-edits"
                | "raw/exact-file-image-cas"
                | "raw/recover-mutation-scratch",
            ) => Self::WorkspaceTransition,
            _ => Self::Ordinary,
        }
    }
}

const SEMANTIC_GRAPH_PASS_COUNT: u32 = 2;
const MAXIMUM_WORKSPACE_RECONCILIATION_WAIT: Duration = Duration::from_secs(60 * 60);
const CLIENT_RESPONSE_COMPLETION_RESERVE: Duration = Duration::from_secs(5);

#[derive(Debug, Clone)]
pub(crate) struct SemanticWorkspaceRead<C: lifecycle_typestate::RequiredCapability = lifecycle_typestate::SourceCapability> {
    session: RawRpcSession<C>,
    published: crate::published_workspace::PublishedWorkspaceDatabase,
    capability: C::Ready,
}

#[derive(Debug)]
#[must_use = "the revalidated epoch proof must authorize the result derived from this read"]
pub(crate) struct RevalidatedSemanticWorkspaceRead<
    'a,
    C: lifecycle_typestate::RequiredCapability = lifecycle_typestate::SourceCapability,
> {
    read: &'a SemanticWorkspaceRead<C>,
    capability: C::Ready,
}

impl<C: lifecycle_typestate::RequiredCapability> RevalidatedSemanticWorkspaceRead<'_, C> {
    pub(crate) fn finish<T>(self, value: T) -> T {
        debug_assert_eq!(
            C::source(&self.capability).revision().value(),
            self.read.published.manifest.source_revision,
        );
        value
    }
}

impl<C: lifecycle_typestate::RequiredCapability> SemanticWorkspaceRead<C> {
    pub(crate) fn published(
        &self,
    ) -> &crate::published_workspace::PublishedWorkspaceDatabase {
        &self.published
    }

    pub(crate) fn database(&self) -> &Path {
        self.published.database()
    }

    pub(crate) fn revalidate(&self) -> Result<RevalidatedSemanticWorkspaceRead<'_, C>> {
        self.published.revalidate()?;
        let epoch = self.session.admission.validate_current()?;
        let capability = epoch.capability_ready()?;
        let status = rpc::request_wait_for_close::<RuntimeStatusResponse>(
            Path::new(&self.session.socket_path),
            "runtime/status",
            Value::Object(Default::default()),
            self.session.response_timeouts.ordinary(),
        )?
        .validate_protocol()?;
        validate_runtime_status_identity(&self.session.admission.candidate().descriptor, &status)?;
        require_published_runtime_status(&status, &self.published)?;
        Ok(RevalidatedSemanticWorkspaceRead {
            read: self,
            capability,
        })
    }
}

impl SemanticWorkspaceRead<lifecycle_typestate::GraphCapability> {
    pub(crate) fn published_graph(
        &self,
    ) -> &crate::published_workspace::PublishedWorkspaceDatabase {
        debug_assert_eq!(
            self.capability.source().revision().value(),
            self.published.manifest.source_revision
        );
        &self.published
    }
}

impl<C: lifecycle_typestate::RequiredCapability> RawRpcSession<C> {
    pub(crate) fn semantic_read(&self) -> Result<SemanticWorkspaceRead<C>> {
        let epoch = self.admission.validate_current()?;
        let capability = epoch.capability_ready()?;
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
            capability,
        })
    }
}

pub(crate) fn semantic_workspace_read_ready(
    requested_workspace_root: Option<PathBuf>,
) -> Result<SemanticWorkspaceRead> {
    raw_rpc_session_ready(requested_workspace_root)?.semantic_read()
}

pub(crate) fn semantic_workspace_read(
    requested_workspace_root: Option<PathBuf>,
) -> Result<SemanticWorkspaceRead> {
    raw_rpc_session(requested_workspace_root)?.semantic_read()
}

pub(crate) fn semantic_graph_workspace_read(
    requested_workspace_root: Option<PathBuf>,
) -> Result<SemanticWorkspaceRead<lifecycle_typestate::GraphCapability>> {
    raw_rpc_session_from_route(semantic_graph_workspace_route(requested_workspace_root)?)?
        .semantic_read()
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

fn raw_rpc_session_from_route<C: lifecycle_typestate::RequiredCapability>(
    route: SemanticWorkspaceRoute<C>,
) -> Result<RawRpcSession<C>> {
    match route {
        SemanticWorkspaceRoute::Admitted(admission) => {
            Ok(raw_rpc_session_for_admission(*admission))
        }
        SemanticWorkspaceRoute::Rejected(rejection) => Err(rejection.into_cli_error()),
    }
}

pub(crate) fn raw_rpc_session_for_admission<C: lifecycle_typestate::RequiredCapability>(
    admission: AdmittedIndexerRuntime<C>,
) -> RawRpcSession<C> {
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
    let epoch = session.admission.validate_current()?;
    let _capability = epoch.capability_ready()?;
    validate_raw_rpc_workspace_root(requested_workspace_root.as_deref(), session)?;
    if is_local_semantic_rpc(&raw_request)? {
        let read = session.semantic_read()?;
        let response = try_handle_local_raw_rpc(
            &raw_request,
            session.admission.workspace_root(),
            read.published(),
        );
        let response = response?.ok_or_else(|| {
            CliError::new(
                "RPC_LOCAL_DISPATCH_INVALID",
                "A local semantic RPC method had no local handler.",
            )
        });
        return Ok(read.revalidate()?.finish(response?));
    }
    let response_timeout = session.response_timeouts.for_request(&raw_request)?;
    let traced_request = trace_correlation::trace_correlated_rpc_request(
        raw_request,
        session.admission.workspace_root(),
        session.admission.config(),
    )?;
    let response = rpc::raw_wait_for_close(
        Path::new(&session.socket_path),
        traced_request.wire_request(),
        response_timeout,
    );
    let trace_outcome = match &response {
        Ok(_) => trace_correlation::RpcTraceOutcome::Succeeded,
        Err(_) => trace_correlation::RpcTraceOutcome::Failed,
    };
    traced_request.record_completion(trace_outcome);
    response
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

#[cfg(test)]
mod semantic_demand_entrypoint_tests {
    use super::*;

    #[test]
    fn graph_and_source_reads_expose_start_capable_semantic_demand_entrypoints() {
        assert_eq!(
            semantic_demand_availability(),
            indexer_authority::SemanticRuntimeAvailability::StartIfMissing,
        );
        let _source_demand: fn(Option<PathBuf>) -> Result<SemanticWorkspaceRead> =
            semantic_workspace_read;
        let _graph_demand: fn(
            Option<PathBuf>,
        ) -> Result<SemanticWorkspaceRead<lifecycle_typestate::GraphCapability>> =
            semantic_graph_workspace_read;
    }
}
