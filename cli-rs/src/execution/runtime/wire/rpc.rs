pub fn raw_request_passthrough(
    raw_request: String,
    requested_workspace_root: Option<PathBuf>,
) -> Result<String> {
    if is_local_semantic_rpc(&raw_request)? {
        let session = raw_rpc_session_from_route(semantic_workspace_route(
            requested_workspace_root.clone(),
        )?)?;
        return raw_local_request_passthrough_in_session(
            raw_request,
            requested_workspace_root,
            &session,
        );
    }
    let session = raw_rpc_session(requested_workspace_root.clone())?;
    raw_request_passthrough_in_session(raw_request, requested_workspace_root, &session)
}

#[derive(Debug, Clone)]
pub struct RawRpcSession<C: lifecycle_typestate::RequiredCapability = lifecycle_typestate::CompilerCapability> {
    admission: AdmittedIndexerRuntime<C>,
    socket_path: PathBuf,
    response_timeouts: RpcResponseTimeoutPolicy,
}

include!("rpc/timeout.rs");

#[derive(Debug, Clone)]
pub(crate) struct SemanticWorkspaceRead<C: lifecycle_typestate::PersistedCapability = lifecycle_typestate::SourceCapability> {
    session: RawRpcSession<C>,
    published: crate::published_workspace::PublishedWorkspaceDatabase,
    capability: C::Ready,
}

#[derive(Debug)]
#[must_use = "the revalidated epoch proof must authorize the result derived from this read"]
pub(crate) struct RevalidatedSemanticWorkspaceRead<
    'a,
    C: lifecycle_typestate::PersistedCapability = lifecycle_typestate::SourceCapability,
> {
    read: &'a SemanticWorkspaceRead<C>,
    capability: C::Ready,
}

#[derive(Debug)]
#[must_use = "both revalidated lane proofs must authorize the composed result"]
pub(crate) struct RevalidatedCompositeWorkspaceRead<
    'a,
    P: lifecycle_typestate::PersistedCapability,
    C: lifecycle_typestate::CurrentCapability,
> {
    read: &'a SemanticWorkspaceRead<P>,
    persisted_capability: P::Ready,
    current_capability: C::Ready,
}

impl<C: lifecycle_typestate::PersistedCapability> RevalidatedSemanticWorkspaceRead<'_, C> {
    pub(crate) fn finish<T>(self, value: T) -> T {
        debug_assert_eq!(
            C::source(&self.capability).revision().value(),
            self.read.published.manifest.source_revision,
        );
        value
    }
}

impl<
        P: lifecycle_typestate::PersistedCapability,
        C: lifecycle_typestate::CurrentCapability,
    > RevalidatedCompositeWorkspaceRead<'_, P, C>
{
    pub(crate) fn finish<T>(self, value: T) -> T {
        debug_assert_eq!(
            P::source(&self.persisted_capability).revision().value(),
            self.read.published.manifest.source_revision,
        );
        debug_assert_eq!(
            P::source(&self.persisted_capability).runtime().identity(),
            C::current(&self.current_capability).runtime().identity(),
        );
        value
    }
}

impl<C: lifecycle_typestate::PersistedCapability> SemanticWorkspaceRead<C> {
    pub(crate) fn published(
        &self,
    ) -> &crate::published_workspace::PublishedWorkspaceDatabase {
        &self.published
    }

    pub(crate) fn database(&self) -> &Path {
        self.published.database()
    }

    pub(crate) fn freshness(&self) -> lifecycle_typestate::PublishedCapabilityFreshness {
        C::source(&self.capability).freshness()
    }

    pub(crate) fn lane_revision(&self) -> u64 {
        C::source(&self.capability).lane_revision()
    }

    pub(crate) fn revalidate(&self) -> Result<RevalidatedSemanticWorkspaceRead<'_, C>> {
        self.published.revalidate()?;
        let capability = self.session.revalidate_capability()?;
        self.published
            .require_manifest(C::source(&capability).publication())?;
        Ok(RevalidatedSemanticWorkspaceRead {
            read: self,
            capability,
        })
    }

    pub(crate) fn revalidate_with_current<D: lifecycle_typestate::CurrentCapability>(
        &self,
        current: &RawRpcSession<D>,
    ) -> Result<RevalidatedCompositeWorkspaceRead<'_, C, D>> {
        self.published.revalidate()?;
        let current_epoch = current.admission.validate_current()?;
        let persisted_epoch = self.session.admission.validate_current()?;
        if current_epoch.identity() != persisted_epoch.identity() {
            return Err(CliError::new(
                "RUNTIME_IDENTITY_REPLACED",
                "The composed current and persisted lanes belong to different runtime epochs.",
            ));
        }
        let status = rpc::request_wait_for_close::<RuntimeStatusResponse>(
            Path::new(&current.socket_path),
            "runtime/status",
            Value::Object(Default::default()),
            current.response_timeouts.ordinary(),
        )?
        .validate_protocol()?;
        validate_runtime_status_identity(&current.admission.candidate().descriptor, &status)?;
        validate_runtime_status_identity(&self.session.admission.candidate().descriptor, &status)?;
        let current_capability = current_epoch.revalidate_capability(&status)?;
        let persisted_capability = persisted_epoch.revalidate_capability(&status)?;
        self.published
            .require_manifest(C::source(&persisted_capability).publication())?;
        Ok(RevalidatedCompositeWorkspaceRead {
            read: self,
            persisted_capability,
            current_capability,
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

impl<C: lifecycle_typestate::PersistedCapability> RawRpcSession<C> {
    pub(crate) fn semantic_read(&self) -> Result<SemanticWorkspaceRead<C>> {
        let capability = self.capability()?;
        let published = crate::published_workspace::resolve_published_workspace_database(
            self.admission.workspace_root(),
        )?;
        published.require_manifest(C::source(&capability).publication())?;
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
    raw_rpc_session_from_route(semantic_workspace_route_ready(requested_workspace_root)?)?
        .semantic_read()
}

pub(crate) fn semantic_workspace_read(
    requested_workspace_root: Option<PathBuf>,
) -> Result<SemanticWorkspaceRead> {
    raw_rpc_session_from_route(semantic_workspace_route(requested_workspace_root)?)?.semantic_read()
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
    raw_rpc_session_from_route(compiler_workspace_route(requested_workspace_root)?)
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

impl<C: lifecycle_typestate::RequiredCapability> RawRpcSession<C> {
    pub(crate) fn capability(&self) -> Result<C::Ready> {
        self.admission.validate_current()?.capability_ready()
    }

    pub(crate) fn revalidate_capability(&self) -> Result<C::Ready> {
        let epoch = self.admission.validate_current()?;
        let status = rpc::request_wait_for_close::<RuntimeStatusResponse>(
            Path::new(&self.socket_path),
            "runtime/status",
            Value::Object(Default::default()),
            self.response_timeouts.ordinary(),
        )?
        .validate_protocol()?;
        validate_runtime_status_identity(&self.admission.candidate().descriptor, &status)?;
        epoch.revalidate_capability(&status)
    }
}

impl<C: lifecycle_typestate::CurrentCapability> RawRpcSession<C> {
    pub(crate) fn current_revision(&self) -> Result<u64> {
        let capability = self.capability()?;
        debug_assert_eq!(
            C::current(&capability).runtime().root().as_path(),
            self.admission.workspace_root(),
        );
        Ok(C::current(&capability).revision())
    }

    pub(crate) fn finish_current<T>(&self, value: T) -> Result<T> {
        let _capability = self.revalidate_capability()?;
        Ok(value)
    }
}

pub fn raw_request_passthrough_in_session<C: lifecycle_typestate::RequiredCapability>(
    raw_request: String,
    requested_workspace_root: Option<PathBuf>,
    session: &RawRpcSession<C>,
) -> Result<String> {
    if is_local_semantic_rpc(&raw_request)? {
        return Err(CliError::new(
            "RPC_PERSISTED_CAPABILITY_REQUIRED",
            "Local source-index RPC requires an explicitly persisted capability session.",
        ));
    }
    let _capability = session.capability()?;
    let response = raw_request_in_open_session(raw_request, requested_workspace_root, session)?;
    if C::REVALIDATE_AFTER_RPC {
        let _capability = session.revalidate_capability()?;
    }
    Ok(response)
}

pub(crate) fn raw_request_in_open_session<C: lifecycle_typestate::RequiredCapability>(
    raw_request: String,
    requested_workspace_root: Option<PathBuf>,
    session: &RawRpcSession<C>,
) -> Result<String> {
    validate_raw_rpc_workspace_root(requested_workspace_root.as_deref(), session)?;
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

include!("rpc/local.rs");

fn validate_raw_rpc_workspace_root<C: lifecycle_typestate::RequiredCapability>(
    requested_workspace_root: Option<&Path>,
    session: &RawRpcSession<C>,
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
