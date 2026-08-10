include!("workspace/launch_lock.rs");

pub fn workspace_status(mut args: RuntimeArgs) -> Result<WorkspaceStatusResult> {
    args.accept_indexing = Some(true);
    args.no_auto_start = Some(true);
    let admission = admitted_runtime(semantic_workspace_route_for_runtime(args)?)?;
    let candidate = admission.candidate().clone();
    let semantic_graph = candidate_advertises_semantic_graph(&candidate)
        .then(|| crate::repository_intelligence::semantic_graph_readiness_for_admission(&admission));
    let path_resolution = config::path_resolution_report(
        admission.config(),
        Some(admission.workspace_root()),
        config::PathResolutionMode::Cli,
    )?;
    Ok(WorkspaceStatusResult {
        workspace_root: admission.workspace_root().display().to_string(),
        descriptor_directory: admission.config().paths.descriptor_dir.display().to_string(),
        path_resolution,
        selected: Some(candidate.clone()),
        semantic_graph,
        candidates: vec![candidate],
        schema_version: SCHEMA_VERSION,
    })
}

pub fn inspect_lifecycle(workspace_root: Option<PathBuf>) -> LifecycleInspection {
    let requested_root = workspace_root.as_ref().map(|root| root.display().to_string());
    let resolved_root = match config::resolve_workspace_root(workspace_root) {
        Ok(root) => root,
        Err(error) => {
            return LifecycleInspection::Blocked {
                workspace_root: requested_root,
                blocker: LifecycleBlocker {
                    code: error.code.to_string(),
                    message: error.message,
                },
                schema_version: SCHEMA_VERSION,
            };
        }
    };
    let root = resolved_root.as_path();
    let requested_root = Some(root.display().to_string());
    let config = match KastConfig::load(root) {
        Ok(config) => config,
        Err(error) => {
            return LifecycleInspection::Blocked {
                workspace_root: requested_root,
                blocker: LifecycleBlocker {
                    code: error.code.to_string(),
                    message: error.message,
                },
            schema_version: SCHEMA_VERSION,
            };
        }
    };
    match indexer_authority::inspect_lifecycle_ownership(&config, root) {
        Ok(indexer_authority::LifecycleOwnershipObservation::Absent) => {
            LifecycleInspection::Absent {
                workspace_root: root.display().to_string(),
                schema_version: SCHEMA_VERSION,
            }
        }
        Ok(indexer_authority::LifecycleOwnershipObservation::Blocked { code, message }) => {
            LifecycleInspection::Blocked {
                workspace_root: Some(root.display().to_string()),
                blocker: LifecycleBlocker {
                    code: code.to_string(),
                    message,
                },
                schema_version: SCHEMA_VERSION,
            }
        }
        Ok(indexer_authority::LifecycleOwnershipObservation::ExactOwned {
            runtime_instance_id,
        }) => lifecycle_epoch_from_observation(root, &config, &runtime_instance_id),
        Err(error) => LifecycleInspection::Blocked {
            workspace_root: requested_root,
            blocker: LifecycleBlocker {
                code: error.code.to_string(),
                message: error.message,
            },
            schema_version: SCHEMA_VERSION,
        },
    }
}

fn lifecycle_epoch_from_observation(
    workspace_root: &Path,
    config: &KastConfig,
    runtime_instance_id: &str,
) -> LifecycleInspection {
    let inspection = match inspect_indexer_workspace_status_only(workspace_root, config) {
        Ok(inspection) => inspection,
        Err(error) => {
            return LifecycleInspection::Blocked {
                workspace_root: Some(workspace_root.display().to_string()),
                blocker: LifecycleBlocker {
                    code: error.code.to_string(),
                    message: error.message,
                },
                schema_version: SCHEMA_VERSION,
            };
        }
    };
    let mut matching = inspection.candidates.into_iter().filter(|candidate| {
        candidate.descriptor.runtime_instance_id.as_deref() == Some(runtime_instance_id)
    });
    let Some(candidate) = matching.next() else {
        return LifecycleInspection::Blocked {
            workspace_root: Some(workspace_root.display().to_string()),
            blocker: LifecycleBlocker {
                code: "RUNTIME_IDENTITY_INCOMPLETE".to_string(),
                message: "The exact-owned runtime has not published matching epoch evidence."
                    .to_string(),
            },
            schema_version: SCHEMA_VERSION,
        };
    };
    if matching.next().is_some() {
        return LifecycleInspection::Blocked {
            workspace_root: Some(workspace_root.display().to_string()),
            blocker: LifecycleBlocker {
                code: "RUNTIME_OWNERSHIP_AMBIGUOUS".to_string(),
                message: "More than one descriptor claims the exact-owned runtime epoch."
                    .to_string(),
            },
            schema_version: SCHEMA_VERSION,
        };
    }
    lifecycle_epoch(workspace_root, candidate)
}

fn lifecycle_epoch(
    workspace_root: &Path,
    candidate: RuntimeCandidateStatus,
) -> LifecycleInspection {
    if !candidate.pid_alive {
        return LifecycleInspection::Absent {
            workspace_root: workspace_root.display().to_string(),
            schema_version: SCHEMA_VERSION,
        };
    }
    let descriptor = candidate.descriptor;
    let Some(runtime_instance_id) = descriptor.runtime_instance_id else {
        return LifecycleInspection::Blocked {
            workspace_root: Some(workspace_root.display().to_string()),
            blocker: LifecycleBlocker {
                code: "RUNTIME_IDENTITY_INCOMPLETE".to_string(),
                message: "The observed runtime has no immutable epoch identity.".to_string(),
            },
            schema_version: SCHEMA_VERSION,
        };
    };
    let (Some(process_start_epoch_millis), Some(socket_file_identity)) = (
        descriptor.process_start_epoch_millis,
        descriptor.socket_file_identity,
    ) else {
        return LifecycleInspection::Blocked {
            workspace_root: Some(workspace_root.display().to_string()),
            blocker: LifecycleBlocker {
                code: "RUNTIME_IDENTITY_INCOMPLETE".to_string(),
                message: "The observed runtime lacks exact process or socket identity.".to_string(),
            },
            schema_version: SCHEMA_VERSION,
        };
    };
    let mut capabilities = Vec::new();
    let runtime_status = candidate.runtime_status.as_ref();
    let phase = match runtime_status.map(|runtime| &runtime.state) {
        Some(RuntimeState::Ready) => {
            RuntimeEpochPhase::RuntimeAvailable
        }
        Some(RuntimeState::Indexing | RuntimeState::Degraded) => RuntimeEpochPhase::ModelReady,
        Some(RuntimeState::Starting) | None => RuntimeEpochPhase::Starting,
    };
    if let Some((status, publication)) = runtime_status.and_then(|status| {
        status
            .published_workspace_generation
            .as_ref()
            .map(|publication| (status, publication))
    }) && matches!(status.state, RuntimeState::Ready)
        && publication.source_revision > 0
    {
        capabilities.push(LifecycleCapability::Source);
        if status.reference_index_ready()
            && publication.reference_revision == publication.source_revision
        {
            capabilities.push(LifecycleCapability::Reference);
        }
        if status.graph_index_ready()
            && matches!(
                publication.graph_publication,
                crate::published_workspace::PublishedGraphEvidence::Ready { revision }
                    if revision == publication.source_revision
            )
        {
            capabilities.push(LifecycleCapability::Graph);
        }
    }
    LifecycleInspection::Epoch {
        workspace_root: workspace_root.display().to_string(),
        epoch: RuntimeEpochEvidence {
            runtime_instance_id,
            process_id: descriptor.pid,
            process_start_epoch_millis,
            socket_file_identity,
            phase,
        },
        capabilities,
        schema_version: SCHEMA_VERSION,
    }
}

fn admitted_runtime(route: SemanticWorkspaceRoute) -> Result<AdmittedIndexerRuntime> {
    match route {
        SemanticWorkspaceRoute::Admitted(admission) => Ok(*admission),
        SemanticWorkspaceRoute::Rejected(rejection) => Err(rejection.into_cli_error()),
    }
}

fn candidate_advertises_semantic_graph(candidate: &RuntimeCandidateStatus) -> bool {
    candidate
        .capabilities
        .as_ref()
        .and_then(|capabilities| capabilities.get("readCapabilities"))
        .and_then(Value::as_array)
        .is_some_and(|capabilities| {
            capabilities
                .iter()
                .any(|capability| capability.as_str() == Some("SEMANTIC_GRAPH"))
        })
}
