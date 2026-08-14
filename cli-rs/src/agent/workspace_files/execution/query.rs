fn execute_agent_workspace_files(args: AgentWorkspaceFilesArgs) -> AgentEnvelope {
    let (mut admitted_query, page_handle) = match admit_workspace_files_query(&args) {
        Ok(admitted) => admitted,
        Err(error) => {
            return error_envelope("agent/workspace-files".to_string(), None, error);
        }
    };
    let admission = match runtime::workspace_files_route(args.runtime.workspace_root.clone()) {
        Ok(runtime::SemanticWorkspaceRoute::Admitted(admission)) => admission,
        Ok(runtime::SemanticWorkspaceRoute::Rejected(rejection)) => {
            let mut error = agent_error(rejection.code, rejection.message);
            error
                .details
                .insert("semanticWorkspace".to_string(), json!(rejection.evidence));
            workspace_files_query_details(&mut error, &admitted_query, page_handle.as_ref());
            return error_envelope("agent/workspace-files".to_string(), None, error);
        }
        Err(error) => {
            let mut error = AgentError::from_cli_error(error);
            workspace_files_query_details(&mut error, &admitted_query, page_handle.as_ref());
            return error_envelope(
                "agent/workspace-files".to_string(),
                None,
                error,
            );
        }
    };
    admitted_query.canonical_workspace_root = admission.workspace_root().display().to_string();
    admitted_query.backend = Some(admission.backend_name());
    let root = match WorkspaceRoot::try_from(admission.workspace_root()) {
        Ok(root) => root,
        Err(error) => {
            let mut error = agent_error("AGENT_WORKSPACE_INVALID", error.to_string());
            workspace_files_query_details(&mut error, &admitted_query, page_handle.as_ref());
            return error_envelope(
                "agent/workspace-files".to_string(),
                None,
                error,
            );
        }
    };
    let session = runtime::raw_rpc_session_for_admission(admission.as_ref().clone());
    let source_read = match optional_workspace_source_read(&admission) {
        Ok(read) => read,
        Err(error) => return error_envelope("agent/workspace-files".to_string(), None, error),
    };
    let capability_evidence = match workspace_files_capability_evidence(&session, source_read.as_ref()) {
        Ok(evidence) => evidence,
        Err(error) => {
            return error_envelope(
                "agent/workspace-files".to_string(),
                None,
                AgentError::from_cli_error(error),
            );
        }
    };
    let mut backend = RawRpcWorkspaceBackend::new(&session, &root);
    let continuation_identity = match workspace_files_continuation_identity(&admitted_query) {
        Ok(identity) => identity,
        Err(error) => {
            return error_envelope("agent/workspace-files".to_string(), None, error);
        }
    };
    let consumed_state = match page_handle {
        Some(page_handle) => match consume_workspace_files_continuation(
            &mut backend,
            &continuation_identity,
            &page_handle.token,
        ) {
            Ok(state) => Some(state),
            Err(error) => {
                return error_envelope("agent/workspace-files".to_string(), None, error);
            }
        },
        None => None,
    };
    let mut lanes = SystemWorkspaceLaneReader::new(source_read.as_ref().map(|read| read.published()));
    let snapshot = match collect_workspace_inventory(WorkspaceInventoryInputs {
        root,
        kind_domain: workspace_files_kind_domain(args.kind_domain()),
        dirty_evidence_relevant: workspace_files_dirty_evidence_relevant(&args),
        backend: &mut backend,
        lanes: &mut lanes,
    }) {
        Ok(snapshot) => snapshot,
        Err(error) => match error {},
    };
    let resumed_continuation = match consumed_state.as_ref() {
        Some(state) => match validate_workspace_files_resumed_snapshot(
            state,
            &continuation_identity,
            &capability_evidence,
            &snapshot,
        ) {
            Ok(continuation) => Some(continuation),
            Err(error) => {
                return error_envelope("agent/workspace-files".to_string(), None, error);
            }
        },
        None => None,
    };
    if workspace_files_candidate_authorities_unavailable(&snapshot, args.kind_domain()) {
        return workspace_files_unavailable(admitted_query, None);
    }
    let coverage = snapshot.coverage();
    let index_evidence_complete = workspace_files_index_evidence_complete(&snapshot);
    let filter_coverage = workspace_files_filter_coverage(
        snapshot.files(),
        snapshot.backend_coverage(),
        &args,
        index_evidence_complete,
    );
    let exact = coverage.candidate_inventory() == WorkspaceCoverageDimension::Complete
        && filter_coverage == WorkspaceCoverageDimension::Complete;
    let matching = snapshot
        .files()
        .iter()
        .filter(|file| workspace_file_matches(file, &args))
        .collect::<Vec<_>>();
    let cardinality = if exact {
        AgentResultCardinality::Exact {
            total_count: matching.len(),
        }
    } else {
        AgentResultCardinality::KnownMinimum {
            known_minimum_count: matching.len(),
        }
    };
    let start = match resumed_continuation.as_ref() {
        Some(continuation) => match workspace_files_resume_offset(continuation, &matching) {
            Ok(offset) => offset,
            Err(error) => {
                return error_envelope("agent/workspace-files".to_string(), None, error);
            }
        },
        None => 0,
    };
    let returned_matches = matching
        .iter()
        .skip(start)
        .take(usize::from(args.limit.get()))
        .copied()
        .collect::<Vec<_>>();
    let detailed_files = returned_matches
        .iter()
        .map(|file| {
            project_workspace_file(
                admission.workspace_root(),
                file,
                index_evidence_complete,
                &args.view,
            )
        })
        .collect::<Vec<_>>();
    let returned_count = detailed_files.len();
    let has_more_known_matches = start.saturating_add(returned_count) < matching.len();
    let next_page_token = if !args.view.count
        && has_more_known_matches
        && snapshot.continuation_allowed()
    {
        let Some(last_relative_path) = returned_matches.last().map(|file| file.path().to_string()) else {
            return invalid_projection_envelope(
                "agent/workspace-files".to_string(),
                "workspace-file continuation page omitted its final path",
            );
        };
        let state = WorkspaceFilesContinuationState {
            identity: continuation_identity.clone(),
            capability_evidence: capability_evidence.clone(),
            composition_stamp_digest: snapshot.composition_digest().to_string(),
            last_relative_path,
            cumulative_returned_count: start.saturating_add(returned_count),
        };
        match issue_workspace_files_continuation(&mut backend, &continuation_identity, &state) {
            Ok(token) => Some(token),
            Err(error) => {
                return error_envelope("agent/workspace-files".to_string(), None, error);
            }
        }
    } else {
        None
    };
    let result = WorkspaceFilesResult {
        result_type: "KAST_AGENT_WORKSPACE_FILES_RESULT",
        ok: true,
        workspace_root: admission.workspace_root().display().to_string(),
        capability_evidence,
        files: if workspace_files_view_name(&args.view) == "compact" {
            WorkspaceFilesResultFiles::Compact(project_workspace_file_groups(
                admission.workspace_root(),
                &returned_matches,
                index_evidence_complete,
            ))
        } else {
            WorkspaceFilesResultFiles::Detailed(detailed_files)
        },
        cardinality,
        returned_count,
        truncated: !exact || has_more_known_matches,
        next_page_token,
        coverage: WorkspaceFilesCoverage {
            candidate_inventory: workspace_files_coverage(coverage.candidate_inventory()),
            filter_evidence: workspace_files_coverage(filter_coverage),
        },
        limitations: snapshot
            .limitations()
            .iter()
            .map(|(code, count)| WorkspaceFilesLimitation {
                code: workspace_files_limitation_code(*code),
                count: *count,
            })
            .collect(),
        backend_page_coverage: (args.view.verbose || args.view.explain)
            .then(|| workspace_files_backend_page_coverage(&snapshot)),
        classification_evidence: args.view.explain.then(|| {
            matching
                .iter()
                .skip(start)
                .take(returned_count)
                .map(|file| workspace_files_classification_evidence(file))
                .collect()
        }),
        normalized_query: args.view.explain.then(|| continuation_identity.normalized_query.clone()),
        composition_digest: (args.view.verbose || args.view.explain)
            .then(|| snapshot.composition_digest().to_string()),
        schema_version: SCHEMA_VERSION,
    };
    let result = project_workspace_files_result(
        result,
        &args.view,
        &matching,
        cardinality,
        snapshot.kind_coverage(),
        filter_coverage,
        index_evidence_complete,
    );
    let result = match source_read.as_ref() {
        Some(read) => match read.revalidate_with_current(&session) {
            Ok(proof) => proof.finish(result),
            Err(error) => {
                return error_envelope(
                    "agent/workspace-files".to_string(),
                    None,
                    AgentError::from_cli_error(error),
                );
            }
        },
        None => match session.finish_current(result) {
            Ok(result) => result,
            Err(error) => {
                return error_envelope(
                    "agent/workspace-files".to_string(),
                    None,
                    AgentError::from_cli_error(error),
                );
            }
        },
    };
    result_envelope(
        "agent/workspace-files".to_string(),
        result,
    )
}

fn optional_workspace_source_read(
    workspace_admission: &runtime::WorkspaceFilesAdmission,
) -> std::result::Result<Option<runtime::SemanticWorkspaceRead>, AgentError> {
    let Some(status) = workspace_admission.candidate().runtime_status.as_ref() else {
        return Ok(None);
    };
    if <runtime::lifecycle_typestate::SourceCapability as runtime::lifecycle_typestate::RequiredCapability>::admit(status)
        .is_err()
    {
        return Ok(None);
    }
    let source_admission = workspace_admission
        .admit_captured_capability::<runtime::lifecycle_typestate::SourceCapability>()
        .map_err(AgentError::from_cli_error)?;
    runtime::semantic_workspace_read_for_admission(&source_admission)
        .map(Some)
        .map_err(AgentError::from_cli_error)
}

fn workspace_files_capability_evidence(
    session: &runtime::RawRpcSession<runtime::lifecycle_typestate::WorkspaceFilesCapability>,
    source_read: Option<&runtime::SemanticWorkspaceRead>,
) -> Result<WorkspaceFilesCapabilityEvidence> {
    let source_index = source_read.map_or(WorkspaceFilesSourceIndexEvidence::Unavailable, |read| {
        WorkspaceFilesSourceIndexEvidence::Available {
            revision: read.lane_revision(),
            freshness: match read.freshness() {
                runtime::lifecycle_typestate::PublishedCapabilityFreshness::Current => {
                    WorkspaceFilesPersistedFreshness::Current
                }
                runtime::lifecycle_typestate::PublishedCapabilityFreshness::Previous => {
                    WorkspaceFilesPersistedFreshness::Previous
                }
            },
        }
    });
    Ok(WorkspaceFilesCapabilityEvidence {
        workspace_files_revision: session.current_revision()?,
        source_index,
    })
}
