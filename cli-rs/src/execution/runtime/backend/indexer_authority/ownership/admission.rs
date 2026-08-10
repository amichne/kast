pub(super) fn admit_indexer_runtime<C: RequiredCapability>(
    request: SemanticRuntimeRequest<C>,
) -> std::result::Result<AdmittedIndexerRuntime<C>, SemanticRuntimeRejection> {
    match admitted_candidate(&request) {
        Ok(candidate) => {
            construct_admitted_runtime(request, candidate, RuntimeAdmissionPath::Reused)
        }
        Err(CandidateAdmissionRejection::Missing(_))
            if matches!(
                request.availability,
                SemanticRuntimeAvailability::StartIfMissing
                    | SemanticRuntimeAvailability::StartIfMissingOrAwaitCapability
            ) =>
        {
            start_indexer_runtime(request)
        }
        Err(CandidateAdmissionRejection::PresentButNotReady { candidate, .. })
            if request.availability
                == SemanticRuntimeAvailability::StartIfMissingOrAwaitCapability =>
        {
            await_observed_runtime(request, *candidate)
        }
        Err(rejection) => Err(rejection.into_runtime_rejection()),
    }
}

enum CandidateAdmissionRejection {
    Missing(SemanticRuntimeRejection),
    PresentButNotReady {
        candidate: Box<RuntimeCandidateStatus>,
        rejection: SemanticRuntimeRejection,
    },
    Terminal(SemanticRuntimeRejection),
}

impl CandidateAdmissionRejection {
    fn into_runtime_rejection(self) -> SemanticRuntimeRejection {
        match self {
            Self::Missing(rejection)
            | Self::Terminal(rejection) => rejection,
            Self::PresentButNotReady { rejection, .. } => rejection,
        }
    }
}

fn admitted_candidate<C: RequiredCapability>(
    request: &SemanticRuntimeRequest<C>,
) -> std::result::Result<RuntimeCandidateStatus, CandidateAdmissionRejection> {
    let inspection = inspect_indexer_workspace_with_config(
        &request.workspace_root,
        &request.config,
        StaleDescriptorPolicy::Preserve,
    )
    .map_err(|error| {
        CandidateAdmissionRejection::Terminal(runtime_cli_rejection(
            &request.workspace_root,
            request.workspace_kind,
            error,
        ))
    })?;
    let reachable_candidates = inspection
        .candidates
        .into_iter()
        .filter(|candidate| candidate.reachable)
        .collect::<Vec<_>>();
    if reachable_candidates.len() > 1 {
        return Err(CandidateAdmissionRejection::Terminal(
            indexer_conflict_rejection(
                &request.workspace_root,
                request.workspace_kind,
                &reachable_candidates,
            ),
        ));
    }
    let Some(candidate) = reachable_candidates.into_iter().next() else {
        return Err(CandidateAdmissionRejection::Missing(
            unavailable_rejection(
                &request.workspace_root,
                request.workspace_kind,
                request.accept_indexing,
            ),
        ));
    };
    if candidate.runtime_status.as_ref().is_some_and(|status| {
        is_servable(status)
            && status
                .published_workspace_generation
                .as_ref()
                .is_some_and(|publication| {
                    require_capability_publication::<C>(status, publication).is_ok()
                })
    }) {
        return Ok(candidate);
    }
    Err(CandidateAdmissionRejection::PresentButNotReady {
        candidate: Box::new(candidate),
        rejection: unavailable_rejection(
            &request.workspace_root,
            request.workspace_kind,
            false,
        ),
    })
}

fn await_observed_runtime<C: RequiredCapability>(
    request: SemanticRuntimeRequest<C>,
    observed: RuntimeCandidateStatus,
) -> std::result::Result<AdmittedIndexerRuntime<C>, SemanticRuntimeRejection> {
    let expected_descriptor = observed.descriptor;
    let started_at = Instant::now();
    let candidate = poll_for_runtime_candidate(
        request.wait_timeout_ms,
        250,
        || u64::try_from(started_at.elapsed().as_millis()).unwrap_or(u64::MAX),
        || {
            admitted_candidate(&request)
                .ok()
                .filter(|candidate| candidate.descriptor == expected_descriptor)
        },
        |duration_ms| thread::sleep(Duration::from_millis(duration_ms)),
    )
    .ok_or_else(|| {
        runtime_cli_rejection(
            &request.workspace_root,
            request.workspace_kind,
            CliError::new(
                "RUNTIME_TIMEOUT",
                format!(
                    "Timed out waiting for the existing indexer for {}.",
                    request.workspace_root.display()
                ),
            ),
        )
    })?;
    construct_admitted_runtime(request, candidate, RuntimeAdmissionPath::Reused)
}

enum RuntimeAdmissionPath<C: RequiredCapability> {
    Reused,
    Starting(StartingEpoch<C>),
}

fn construct_admitted_runtime<C: RequiredCapability>(
    request: SemanticRuntimeRequest<C>,
    candidate: RuntimeCandidateStatus,
    admission_path: RuntimeAdmissionPath<C>,
) -> std::result::Result<AdmittedIndexerRuntime<C>, SemanticRuntimeRejection> {
    let descriptor = &candidate.descriptor;
    let runtime_status = candidate.runtime_status.as_ref().ok_or_else(|| {
        unavailable_rejection(
            &request.workspace_root,
            request.workspace_kind,
            request.accept_indexing,
        )
    })?;
    let canonical_descriptor_root =
        canonical_existing_root(&descriptor.workspace_root).map_err(|error| {
            runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
        })?;
    let canonical_status_root =
        canonical_existing_root(&runtime_status.workspace_root).map_err(|error| {
            runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
        })?;
    if canonical_descriptor_root != request.workspace_root
        || canonical_status_root != request.workspace_root
        || descriptor.backend_name != BackendName::Indexer.canonical()
        || runtime_status.backend_name != BackendName::Indexer.canonical()
        || descriptor.backend_version != runtime_status.backend_version
        || descriptor.schema_version != SCHEMA_VERSION
        || runtime_status.schema_version != SCHEMA_VERSION
        || !candidate.pid_alive
        || !runtime_status.healthy()
        || !runtime_status.active()
    {
        return Err(runtime_identity_rejection(
            &request.workspace_root,
            request.workspace_kind,
        ));
    }
    validate_descriptor_owner(descriptor).map_err(|error| {
        runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
    })?;
    let process_identity = process_identity(descriptor.pid).map_err(|error| {
        runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
    })?;
    let observed_socket_file_identity = current_socket_file_identity(&descriptor.socket_path)
        .map_err(|error| {
            runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
        })?;
    let runtime_identity = runtime_epoch_identity(descriptor).map_err(|error| {
        runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
    })?;
    let origin = match &admission_path {
        RuntimeAdmissionPath::Reused => RuntimeAdmissionOrigin::Reused,
        RuntimeAdmissionPath::Starting(_) => RuntimeAdmissionOrigin::Started,
    };
    let lifecycle = match admission_path {
        RuntimeAdmissionPath::Reused => request
            .demand
            .admit(CanonicalWorkspaceRoot::from_canonical(
                request.workspace_root.clone(),
            ))
            .observe_exact(runtime_identity)
            .revalidated()
            .available(),
        RuntimeAdmissionPath::Starting(starting) => starting
            .available(runtime_identity)
            .map_err(|blocker| lifecycle_rejection(&request, blocker))?,
    };
    let capabilities = parse_admitted_capabilities(&request, &candidate)?;
    let rejection_root = request.workspace_root.clone();
    let rejection_kind = request.workspace_kind;
    let admission = AdmittedIndexerRuntime {
        workspace_root: request.workspace_root,
        workspace_kind: request.workspace_kind,
        config: request.config,
        candidate,
        capabilities,
        lifecycle,
        origin,
        process_identity,
        observed_socket_file_identity,
    };
    admission
        .validate_current()
        .and_then(|epoch| epoch.capability_ready().map(|_| ()))
        .map_err(|error| runtime_cli_rejection(&rejection_root, rejection_kind, error))?;
    Ok(admission)
}

fn require_capability_publication<C: RequiredCapability>(
    status: &RuntimeStatusResponse,
    publication: &crate::published_workspace::PublishedWorkspaceGenerationManifest,
) -> Result<()> {
    let model_ready = status.state == RuntimeState::Ready
        && status.active()
        && !status.indexing()
        && !status.source_module_names.is_empty();
    if !model_ready || publication.source_revision == 0 {
        return Err(capability_unavailable());
    }
    match C::REQUIREMENT {
        CapabilityRequirement::Source => Ok(()),
        CapabilityRequirement::Reference
            if status.reference_index_ready()
                && publication.reference_revision == publication.source_revision =>
        {
            Ok(())
        }
        CapabilityRequirement::Graph
            if status.graph_index_ready()
                && matches!(
                    publication.graph_publication,
                    crate::published_workspace::PublishedGraphEvidence::Ready { revision }
                        if revision == publication.source_revision
                ) =>
        {
            Ok(())
        }
        CapabilityRequirement::Reference | CapabilityRequirement::Graph => {
            Err(capability_unavailable())
        }
    }
}

fn capability_unavailable() -> CliError {
    CliError::new(
        "CAPABILITY_UNAVAILABLE",
        "The current runtime epoch has not committed the demanded capability revision.",
    )
}

fn lifecycle_rejection<C: RequiredCapability>(
    request: &SemanticRuntimeRequest<C>,
    blocker: TypestateLifecycleBlocker,
) -> SemanticRuntimeRejection {
    lifecycle_blocker_rejection(&request.workspace_root, request.workspace_kind, blocker)
}

pub(super) fn lifecycle_blocker_rejection(
    workspace_root: &Path,
    workspace_kind: SemanticWorkspaceKind,
    blocker: TypestateLifecycleBlocker,
) -> SemanticRuntimeRejection {
    let code = match blocker {
        TypestateLifecycleBlocker::UnsupportedRoot => "UNSUPPORTED_WORKSPACE",
        TypestateLifecycleBlocker::OwnershipConflict => "RUNTIME_OWNERSHIP_CONFLICT",
        TypestateLifecycleBlocker::OwnershipAmbiguous => "RUNTIME_OWNERSHIP_AMBIGUOUS",
        TypestateLifecycleBlocker::IdentityChanged => "RUNTIME_IDENTITY_REPLACED",
        TypestateLifecycleBlocker::ReplacementFailed => "RUNTIME_REPLACEMENT_FAILED",
        TypestateLifecycleBlocker::CapabilityUnavailable => "CAPABILITY_UNAVAILABLE",
    };
    runtime_cli_rejection(
        workspace_root,
        workspace_kind,
        CliError::new(code, format!("Lifecycle demand terminated at {blocker:?}.")),
    )
}
