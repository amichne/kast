#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
struct AdmittedWorkspaceFilesQueryIdentity {
    canonical_workspace_root: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    backend: Option<&'static str>,
    filters: AdmittedWorkspaceFileFilters,
    kind_domain: &'static str,
    view: &'static str,
    ordered_fields: Vec<&'static str>,
    limit: u8,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
struct AdmittedWorkspaceFileFilters {
    #[serde(skip_serializing_if = "Option::is_none")]
    module: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    source_set: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    kind: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    package: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    package_name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    dirty: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    drift: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    path_prefix: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    glob: Option<String>,
}

#[derive(Debug, Serialize)]
struct WorkspaceFilesPageHandleIdentity {
    token: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceFilesNextAction {
    kind: &'static str,
    command: &'static str,
    arguments: Vec<String>,
    mutates_global_install_authority: bool,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceFilesCompactResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    ok: bool,
    workspace_root: String,
    files: Vec<WorkspaceFileCompactRecord>,
    cardinality: AgentResultCardinality,
    returned_count: usize,
    truncated: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    next_page_token: Option<String>,
    coverage: WorkspaceFilesCoverage,
    limitations: Vec<WorkspaceFilesLimitation>,
    #[serde(skip_serializing_if = "Option::is_none")]
    backend_page_coverage: Option<WorkspaceFilesBackendPageCoverage>,
    #[serde(skip_serializing_if = "Option::is_none")]
    classification_evidence: Option<Vec<WorkspaceFilesClassificationEvidence>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    normalized_query: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    composition_digest: Option<String>,
    schema_version: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceFilesContinuationIdentity {
    workspace_root: String,
    backend_name: String,
    normalized_query: String,
    projection: String,
    limit: u8,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceFilesContinuationState {
    identity: WorkspaceFilesContinuationIdentity,
    composition_stamp_digest: String,
    last_relative_path: String,
    cumulative_returned_count: usize,
}

#[derive(Debug, Deserialize)]
#[serde(
    tag = "type",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase"
)]
enum WorkspaceFilesContinuationResult {
    Issued { page_token: String },
    Consumed { state: WorkspaceFilesContinuationState },
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceFileCompactRecord {
    file_path: String,
    relative_path: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    backend_modules: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    indexed_gradle_projects: Option<Vec<WorkspaceFilesGradleProject>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    source_sets: Option<WorkspaceFilesSourceSetEvidence>,
    kind: WorkspaceFilesKind,
    package: WorkspaceFilesPackageEvidence,
    source_index: WorkspaceFilesIndexState,
    drift: WorkspaceFilesDrift,
    dirty: WorkspaceFilesDirty,
    #[serde(skip_serializing_if = "Option::is_none")]
    evidence: Option<Vec<WorkspaceFilesEvidenceSource>>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceFilesGradleProject {
    build_root: String,
    project_path: String,
}

#[derive(Debug, Serialize)]
#[serde(tag = "type", rename_all = "SCREAMING_SNAKE_CASE", rename_all_fields = "camelCase")]
enum WorkspaceFilesSourceSetEvidence {
    Proven { source_sets: Vec<WorkspaceFilesGradleSourceSet> },
    Unproven { labels: Vec<String> },
    Unavailable,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceFilesGradleSourceSet {
    build_root: String,
    project_path: String,
    source_set_name: String,
}

#[derive(Debug, Serialize)]
#[serde(tag = "type", rename_all = "SCREAMING_SNAKE_CASE", rename_all_fields = "camelCase")]
enum WorkspaceFilesPackageEvidence {
    ProvenRoot,
    ProvenNamed { name: String },
    Unproven,
    Unavailable,
    InvalidReference,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum WorkspaceFilesKind {
    KotlinSource,
    KotlinScript,
}

#[derive(Debug, Serialize)]
#[serde(tag = "type", rename_all = "SCREAMING_SNAKE_CASE", rename_all_fields = "camelCase")]
enum WorkspaceFilesIndexState {
    Indexed,
    NotIndexed,
    Unknown,
    NotApplicable,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum WorkspaceFilesDrift {
    None,
    FilesystemOnly,
    IndexOnly,
    MissingOnDisk,
    Unknown,
    NotApplicable,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum WorkspaceFilesDirty {
    Clean,
    Dirty,
    Unknown,
    NotApplicable,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum WorkspaceFilesEvidenceSource {
    Manifest,
    PackageMetadata,
    GradleProjectModel,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceFilesCoverage {
    candidate_inventory: WorkspaceFilesCoverageDimension,
    filter_evidence: WorkspaceFilesCoverageDimension,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum WorkspaceFilesCoverageDimension {
    Complete,
    Partial,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceFilesLimitation {
    code: &'static str,
    count: usize,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceFilesBackendPageCoverage {
    workspace: WorkspaceFilesBackendCoverage,
    modules: Vec<WorkspaceFilesBackendModuleCoverage>,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum WorkspaceFilesBackendCoverage {
    Complete,
    Partial,
    Unavailable,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceFilesBackendModuleCoverage {
    module_name: String,
    declared_file_count: usize,
    coverage: WorkspaceFilesModuleCoverage,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum WorkspaceFilesModuleCoverage {
    Complete,
    Partial,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceFilesClassificationEvidence {
    relative_path: String,
    kind: WorkspaceFilesKind,
    sources: Vec<WorkspaceFilesEvidenceSource>,
    package: &'static str,
    source_sets: &'static str,
}

fn execute_agent_workspace_files(args: AgentWorkspaceFilesArgs) -> AgentEnvelope {
    let (mut admitted_query, page_handle) = match admit_workspace_files_query(&args) {
        Ok(admitted) => admitted,
        Err(error) => {
            return error_envelope("agent/workspace-files".to_string(), None, error);
        }
    };
    let admission = match runtime::semantic_workspace_route(
        args.runtime.workspace_root.clone(),
        args.runtime.backend_name,
    ) {
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
            return error_envelope(
                "agent/workspace-files".to_string(),
                None,
                AgentError::from_cli_error(error),
            );
        }
    };
    admitted_query.canonical_workspace_root = admission.workspace_root.display().to_string();
    admitted_query.backend = Some(admission.backend_name.canonical());
    let root = match WorkspaceRoot::try_from(admission.workspace_root.as_path()) {
        Ok(root) => root,
        Err(error) => {
            return error_envelope(
                "agent/workspace-files".to_string(),
                None,
                agent_error("AGENT_WORKSPACE_INVALID", error.to_string()),
            );
        }
    };
    let session = match runtime::raw_rpc_session(
        Some(admission.workspace_root.clone()),
        Some(admission.backend_name),
    ) {
        Ok(session) => session,
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
    let mut lanes = SystemWorkspaceLaneReader;
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
    if workspace_files_candidate_authorities_unavailable(&snapshot, args.kind_domain()) {
        return workspace_files_unavailable(admitted_query, None);
    }
    let coverage = snapshot.coverage();
    let filter_coverage = workspace_files_filter_coverage(snapshot.files(), &args);
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
    let index_evidence_complete = workspace_files_index_evidence_complete(&snapshot);
    let start = match consumed_state.as_ref() {
        Some(state) => match workspace_files_resume_offset(
            state,
            &continuation_identity,
            snapshot.composition_digest(),
            &matching,
        ) {
            Ok(offset) => offset,
            Err(error) => {
                return error_envelope("agent/workspace-files".to_string(), None, error);
            }
        },
        None => 0,
    };
    let returned = matching
        .iter()
        .skip(start)
        .take(usize::from(args.limit.get()))
        .map(|file| {
            project_workspace_file(
                admission.workspace_root.as_path(),
                file,
                index_evidence_complete,
                &args.view,
            )
        })
        .collect::<Vec<_>>();
    let returned_count = returned.len();
    let has_more_known_matches = start.saturating_add(returned_count) < matching.len();
    let next_page_token = if !args.view.count
        && has_more_known_matches
        && snapshot.continuation_allowed()
    {
        let Some(last_relative_path) = returned.last().map(|file| file.relative_path.clone()) else {
            return invalid_projection_envelope(
                "agent/workspace-files".to_string(),
                "workspace-file continuation page omitted its final path",
            );
        };
        let state = WorkspaceFilesContinuationState {
            identity: continuation_identity.clone(),
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
    let result = WorkspaceFilesCompactResult {
        result_type: "KAST_AGENT_WORKSPACE_FILES_RESULT",
        ok: true,
        workspace_root: admission.workspace_root.display().to_string(),
        files: returned,
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
    result_envelope(
        "agent/workspace-files".to_string(),
        project_workspace_files_result(
            result,
            &args.view,
            &matching,
            cardinality,
            snapshot.kind_coverage(),
            filter_coverage,
        ),
    )
}

fn workspace_files_backend_page_coverage(
    snapshot: &crate::workspace_inventory::model::WorkspaceInventorySnapshot,
) -> WorkspaceFilesBackendPageCoverage {
    WorkspaceFilesBackendPageCoverage {
        workspace: match snapshot.backend_coverage() {
            BackendWorkspaceCoverage::Complete => WorkspaceFilesBackendCoverage::Complete,
            BackendWorkspaceCoverage::Partial => WorkspaceFilesBackendCoverage::Partial,
            BackendWorkspaceCoverage::Unavailable => WorkspaceFilesBackendCoverage::Unavailable,
        },
        modules: snapshot
            .backend_modules()
            .values()
            .map(|module| WorkspaceFilesBackendModuleCoverage {
                module_name: module.name().as_str().to_string(),
                declared_file_count: module.declared_file_count(),
                coverage: match module.coverage() {
                    BackendModuleCoverage::Complete => WorkspaceFilesModuleCoverage::Complete,
                    BackendModuleCoverage::Partial => WorkspaceFilesModuleCoverage::Partial,
                },
            })
            .collect(),
    }
}

fn workspace_files_classification_evidence(
    file: &WorkspaceInventoryFile,
) -> WorkspaceFilesClassificationEvidence {
    WorkspaceFilesClassificationEvidence {
        relative_path: file.path().to_string(),
        kind: match file.kind() {
            WorkspaceFileKind::Source => WorkspaceFilesKind::KotlinSource,
            WorkspaceFileKind::Script => WorkspaceFilesKind::KotlinScript,
        },
        sources: file
            .evidence()
            .iter()
            .map(|source| match source {
                WorkspaceEvidenceSource::Manifest => WorkspaceFilesEvidenceSource::Manifest,
                WorkspaceEvidenceSource::PackageMetadata => {
                    WorkspaceFilesEvidenceSource::PackageMetadata
                }
                WorkspaceEvidenceSource::GradleProjectModel => {
                    WorkspaceFilesEvidenceSource::GradleProjectModel
                }
            })
            .collect(),
        package: match file.package() {
            WorkspacePackageEvidence::ProvenRoot => "PROVEN_ROOT",
            WorkspacePackageEvidence::ProvenNamed(_) => "PROVEN_NAMED",
            WorkspacePackageEvidence::Unproven(_) => "UNPROVEN",
            WorkspacePackageEvidence::Unavailable => "UNAVAILABLE",
            WorkspacePackageEvidence::InvalidReference(_) => "INVALID_REFERENCE",
        },
        source_sets: match file.source_sets() {
            WorkspaceSourceSetEvidence::Proven(_) => "PROVEN",
            WorkspaceSourceSetEvidence::Unproven(_) => "UNPROVEN",
            WorkspaceSourceSetEvidence::Unavailable => "UNAVAILABLE",
        },
    }
}

fn workspace_files_unavailable(
    admitted_query: AdmittedWorkspaceFilesQueryIdentity,
    page_handle: Option<WorkspaceFilesPageHandleIdentity>,
) -> AgentEnvelope {
    let mut error = agent_error(
        "WORKSPACE_FILE_DISCOVERY_UNAVAILABLE",
        "Workspace file discovery is not available until the typed inventory is initialized.",
    );
    workspace_files_query_details(&mut error, &admitted_query, page_handle.as_ref());
    error_envelope("agent/workspace-files".to_string(), None, error)
}

fn workspace_files_query_details(
    error: &mut AgentError,
    admitted_query: &AdmittedWorkspaceFilesQueryIdentity,
    page_handle: Option<&WorkspaceFilesPageHandleIdentity>,
) {
    if let Ok(value) = serde_json::to_value(admitted_query) {
        error.details.insert("admittedQuery".to_string(), value);
    }
    if let Some(page_handle) = page_handle
        && let Ok(value) = serde_json::to_value(page_handle)
    {
        error.details.insert("pageHandle".to_string(), value);
    }
    if let Ok(value) = serde_json::to_value(workspace_files_next_action(admitted_query)) {
        error.details.insert("nextAction".to_string(), value);
    }
}

fn workspace_files_kind_domain(domain: crate::cli::WorkspaceFileKindDomain) -> WorkspaceRequestedKindDomain {
    match domain {
        crate::cli::WorkspaceFileKindDomain::SourceOnly => WorkspaceRequestedKindDomain::SourceOnly,
        crate::cli::WorkspaceFileKindDomain::ScriptOnly => WorkspaceRequestedKindDomain::ScriptOnly,
        crate::cli::WorkspaceFileKindDomain::Mixed => WorkspaceRequestedKindDomain::Mixed,
    }
}

fn workspace_files_dirty_evidence_relevant(args: &AgentWorkspaceFilesArgs) -> bool {
    args.dirty.is_some()
        || args.view.count
        || args.view.verbose
        || args.view.explain
        || args.view.fields.is_empty()
        || args
            .view
            .fields
            .iter()
            .any(|field| matches!(field, AgentWorkspaceFilesField::Dirty | AgentWorkspaceFilesField::Evidence))
}

fn workspace_file_matches(file: &WorkspaceInventoryFile, args: &AgentWorkspaceFilesArgs) -> bool {
    let kind_matches = match args.kind {
        None => true,
        Some(WorkspaceFileKindFilter::Source) => file.kind() == WorkspaceFileKind::Source,
        Some(WorkspaceFileKindFilter::Script) => file.kind() == WorkspaceFileKind::Script,
    };
    let module_matches = args.module.as_ref().is_none_or(|selector| match selector {
        WorkspaceModuleSelector::Backend(expected) => file
            .backend_modules()
            .iter()
            .any(|actual| actual.as_str() == expected.as_str()),
        WorkspaceModuleSelector::Gradle {
            build_root,
            project_path,
        } => file.indexed_gradle_projects().iter().any(|actual| {
            workspace_files_build_root(actual.build_root().as_path()) == build_root.as_str()
                && actual.project_path().as_str() == project_path.as_str()
        }),
    });
    let source_set_matches = args.source_set.as_ref().is_none_or(|expected| {
        matches!(file.source_sets(), WorkspaceSourceSetEvidence::Proven(source_sets) if source_sets
            .iter()
            .any(|actual| actual.source_set_name().as_str() == expected.as_str()))
    });
    let package_matches = args.package_selector.as_ref().is_none_or(|expected| {
        match (expected, file.package()) {
            (WorkspacePackageSelector::Root, WorkspacePackageEvidence::ProvenRoot) => true,
            (
                WorkspacePackageSelector::Named(expected),
                WorkspacePackageEvidence::ProvenNamed(actual),
            ) => actual.as_str() == expected.semantic_fq_name(),
            _ => false,
        }
    });
    let dirty_matches = args.dirty.is_none_or(|expected| {
        matches!(
            (expected, file.dirty_state()),
            (WorkspaceDirtyFilter::Clean, WorkspaceFileDirtyState::Clean)
                | (WorkspaceDirtyFilter::Dirty, WorkspaceFileDirtyState::Dirty)
                | (WorkspaceDirtyFilter::Unknown, WorkspaceFileDirtyState::Unknown)
        )
    });
    let drift_matches = args.drift.is_none_or(|expected| {
        matches!(
            (expected, file.drift()),
            (WorkspaceDriftFilter::None, WorkspaceFileDrift::InSync)
                | (WorkspaceDriftFilter::FilesystemOnly, WorkspaceFileDrift::FilesystemOnly)
                | (WorkspaceDriftFilter::IndexOnly, WorkspaceFileDrift::IndexOnly)
                | (WorkspaceDriftFilter::MissingOnDisk, WorkspaceFileDrift::MissingOnDisk)
                | (WorkspaceDriftFilter::NotApplicable, WorkspaceFileDrift::NotApplicable)
                | (WorkspaceDriftFilter::Unknown, WorkspaceFileDrift::Unknown)
        )
    });
    let path_prefix_matches = args.path_prefix.as_ref().is_none_or(|prefix| {
        file.path().as_path().starts_with(Path::new(prefix.as_str()))
    });
    let glob_matches = args.glob.as_ref().is_none_or(|glob| {
        glob::Pattern::new(glob.as_str())
            .is_ok_and(|pattern| pattern.matches_path(file.path().as_path()))
    });
    kind_matches
        && module_matches
        && source_set_matches
        && package_matches
        && dirty_matches
        && drift_matches
        && path_prefix_matches
        && glob_matches
}

fn workspace_files_filter_coverage(
    candidates: &[WorkspaceInventoryFile],
    args: &AgentWorkspaceFilesArgs,
) -> WorkspaceCoverageDimension {
    let package_complete = args.package_selector.is_none()
        || candidates.iter().all(|file| {
            matches!(
                file.package(),
                WorkspacePackageEvidence::ProvenRoot | WorkspacePackageEvidence::ProvenNamed(_)
            )
        });
    let source_set_complete = args.source_set.is_none()
        || candidates
            .iter()
            .all(|file| matches!(file.source_sets(), WorkspaceSourceSetEvidence::Proven(_)));
    let dirty_complete = args.dirty.is_none_or(|filter| {
        filter == WorkspaceDirtyFilter::Unknown
            || candidates
                .iter()
                .all(|file| file.dirty_state() != WorkspaceFileDirtyState::Unknown)
    });
    let drift_complete = args.drift.is_none_or(|filter| {
        filter == WorkspaceDriftFilter::Unknown
            || candidates
                .iter()
                .all(|file| file.drift() != WorkspaceFileDrift::Unknown)
    });
    if package_complete && source_set_complete && dirty_complete && drift_complete {
        WorkspaceCoverageDimension::Complete
    } else {
        WorkspaceCoverageDimension::Partial
    }
}

fn workspace_files_candidate_authorities_unavailable(
    snapshot: &crate::workspace_inventory::model::WorkspaceInventorySnapshot,
    domain: crate::cli::WorkspaceFileKindDomain,
) -> bool {
    if snapshot.backend_coverage()
        != crate::workspace_inventory::model::BackendWorkspaceCoverage::Unavailable
    {
        return false;
    }
    let index_unavailable = snapshot
        .limitations()
        .keys()
        .any(|code| {
            matches!(
                code,
                WorkspaceInventoryLimitationCode::SourceIndexUnavailable
                    | WorkspaceInventoryLimitationCode::SourceIndexIncompatible
            )
        });
    matches!(domain, crate::cli::WorkspaceFileKindDomain::ScriptOnly) || index_unavailable
}

fn workspace_files_continuation_identity(
    query: &AdmittedWorkspaceFilesQueryIdentity,
) -> std::result::Result<WorkspaceFilesContinuationIdentity, AgentError> {
    let backend_name = query.backend.ok_or_else(|| {
        agent_error(
            "AGENT_WORKSPACE_INVALID",
            "Workspace-file continuation identity requires an admitted backend.",
        )
    })?;
    let normalized_query = serde_json::to_string(&json!({
        "filters": &query.filters,
        "kindDomain": query.kind_domain,
    }))
    .map_err(|error| agent_error("AGENT_RESULT_INVALID", error.to_string()))?;
    let projection = if query.view == "fields" {
        format!("fields:{}", query.ordered_fields.join(","))
    } else {
        query.view.to_string()
    };
    Ok(WorkspaceFilesContinuationIdentity {
        workspace_root: query.canonical_workspace_root.clone(),
        backend_name: backend_name.to_string(),
        normalized_query,
        projection,
        limit: query.limit,
    })
}

fn consume_workspace_files_continuation(
    backend: &mut dyn BackendWorkspaceRpc,
    identity: &WorkspaceFilesContinuationIdentity,
    token: &str,
) -> std::result::Result<WorkspaceFilesContinuationState, AgentError> {
    let result = backend
        .request(json_rpc_request(
            "raw/workspace-files-continuation",
            json!({
                "action": "CONSUME",
                "identity": identity,
                "pageToken": token,
            }),
        ))
        .map_err(workspace_files_continuation_failure)?;
    match serde_json::from_value::<WorkspaceFilesContinuationResult>(result) {
        Ok(WorkspaceFilesContinuationResult::Consumed { state }) => Ok(state),
        Ok(WorkspaceFilesContinuationResult::Issued { .. }) => Err(agent_error(
            "AGENT_RESULT_INVALID",
            "Workspace-file continuation consume returned an issue result.",
        )),
        Err(error) => Err(agent_error("AGENT_RESULT_INVALID", error.to_string())),
    }
}

fn issue_workspace_files_continuation(
    backend: &mut dyn BackendWorkspaceRpc,
    identity: &WorkspaceFilesContinuationIdentity,
    state: &WorkspaceFilesContinuationState,
) -> std::result::Result<String, AgentError> {
    let result = backend
        .request(json_rpc_request(
            "raw/workspace-files-continuation",
            json!({
                "action": "ISSUE",
                "identity": identity,
                "state": state,
            }),
        ))
        .map_err(workspace_files_continuation_failure)?;
    match serde_json::from_value::<WorkspaceFilesContinuationResult>(result) {
        Ok(WorkspaceFilesContinuationResult::Issued { page_token }) => Ok(page_token),
        Ok(WorkspaceFilesContinuationResult::Consumed { .. }) => Err(agent_error(
            "AGENT_RESULT_INVALID",
            "Workspace-file continuation issue returned a consume result.",
        )),
        Err(error) => Err(agent_error("AGENT_RESULT_INVALID", error.to_string())),
    }
}

fn workspace_files_continuation_failure(failure: BackendRpcFailure) -> AgentError {
    match failure {
        BackendRpcFailure::Api { code, message, .. }
            if code == "INVALID_WORKSPACE_FILES_PAGE_TOKEN" =>
        {
            let mut error = agent_error(&code, message);
            error.details.insert("status".to_string(), json!(400));
            error.details.insert("retryable".to_string(), json!(false));
            error
        }
        failure => agent_error(
            "WORKSPACE_FILES_CONTINUATION_UNAVAILABLE",
            failure.to_string(),
        ),
    }
}

fn workspace_files_resume_offset(
    state: &WorkspaceFilesContinuationState,
    identity: &WorkspaceFilesContinuationIdentity,
    composition_digest: &str,
    matching: &[&WorkspaceInventoryFile],
) -> std::result::Result<usize, AgentError> {
    if &state.identity != identity {
        return Err(invalid_workspace_files_page(
            "Workspace-file continuation identity does not match this query.",
        ));
    }
    if state.composition_stamp_digest != composition_digest {
        return Err(stale_workspace_files_page());
    }
    let Some(last_index) = matching
        .iter()
        .position(|file| file.path().to_string() == state.last_relative_path)
    else {
        return Err(stale_workspace_files_page());
    };
    let offset = last_index.saturating_add(1);
    if offset != state.cumulative_returned_count {
        return Err(invalid_workspace_files_page(
            "Workspace-file continuation cumulative count is inconsistent.",
        ));
    }
    Ok(offset)
}

fn invalid_workspace_files_page(message: &str) -> AgentError {
    let mut error = agent_error("INVALID_WORKSPACE_FILES_PAGE_TOKEN", message);
    error.details.insert("status".to_string(), json!(400));
    error.details.insert("retryable".to_string(), json!(false));
    error
}

fn stale_workspace_files_page() -> AgentError {
    let mut error = agent_error(
        "STALE_WORKSPACE_FILES_PAGE",
        "Workspace-file evidence changed; start a new unpaged query.",
    );
    error.details.insert("status".to_string(), json!(409));
    error.details.insert("retryable".to_string(), json!(true));
    error
        .details
        .insert("restartFromFirstPage".to_string(), json!(true));
    error
}

fn workspace_files_index_evidence_complete(snapshot: &crate::workspace_inventory::model::WorkspaceInventorySnapshot) -> bool {
    ![
        WorkspaceInventoryLimitationCode::SourceIndexUnavailable,
        WorkspaceInventoryLimitationCode::SourceIndexIncompatible,
        WorkspaceInventoryLimitationCode::SourceIndexProgressIncomplete,
        WorkspaceInventoryLimitationCode::SourceIndexUpdatesPending,
        WorkspaceInventoryLimitationCode::CrossSourceCompositionUnstable,
    ]
    .into_iter()
    .any(|code| snapshot.limitation_count(code) > 0)
}

fn project_workspace_file(
    root: &Path,
    file: &WorkspaceInventoryFile,
    index_evidence_complete: bool,
    view: &AgentWorkspaceFilesViewArgs,
) -> WorkspaceFileCompactRecord {
    let detailed = view.verbose || view.explain;
    let module_selected = detailed
        || view
            .fields
            .iter()
            .any(|field| matches!(field, AgentWorkspaceFilesField::Module));
    let source_set_selected = detailed
        || view
            .fields
            .iter()
            .any(|field| matches!(field, AgentWorkspaceFilesField::SourceSet));
    let evidence_selected = detailed
        || view
            .fields
            .iter()
            .any(|field| matches!(field, AgentWorkspaceFilesField::Evidence));
    WorkspaceFileCompactRecord {
        file_path: root.join(file.path().as_path()).display().to_string(),
        relative_path: file.path().to_string(),
        backend_modules: module_selected.then(|| {
            file.backend_modules()
                .iter()
                .map(|module| module.as_str().to_string())
                .collect()
        }),
        indexed_gradle_projects: module_selected.then(|| {
            file.indexed_gradle_projects()
                .iter()
                .map(|project| WorkspaceFilesGradleProject {
                    build_root: workspace_files_build_root(project.build_root().as_path()),
                    project_path: project.project_path().as_str().to_string(),
                })
                .collect()
        }),
        source_sets: source_set_selected.then(|| match file.source_sets() {
            WorkspaceSourceSetEvidence::Proven(source_sets) => WorkspaceFilesSourceSetEvidence::Proven {
                source_sets: source_sets
                    .iter()
                    .map(|source_set| WorkspaceFilesGradleSourceSet {
                        build_root: workspace_files_build_root(source_set.project().build_root().as_path()),
                        project_path: source_set.project().project_path().as_str().to_string(),
                        source_set_name: source_set.source_set_name().as_str().to_string(),
                    })
                    .collect(),
            },
            WorkspaceSourceSetEvidence::Unproven(labels) => WorkspaceFilesSourceSetEvidence::Unproven {
                labels: labels.iter().map(|label| label.as_str().to_string()).collect(),
            },
            WorkspaceSourceSetEvidence::Unavailable => WorkspaceFilesSourceSetEvidence::Unavailable,
        }),
        kind: match file.kind() {
            WorkspaceFileKind::Source => WorkspaceFilesKind::KotlinSource,
            WorkspaceFileKind::Script => WorkspaceFilesKind::KotlinScript,
        },
        package: match file.package() {
            WorkspacePackageEvidence::ProvenRoot => WorkspaceFilesPackageEvidence::ProvenRoot,
            WorkspacePackageEvidence::ProvenNamed(name) => {
                WorkspaceFilesPackageEvidence::ProvenNamed {
                    name: name.as_str().to_string(),
                }
            }
            WorkspacePackageEvidence::Unproven(_) => WorkspaceFilesPackageEvidence::Unproven,
            WorkspacePackageEvidence::Unavailable => WorkspaceFilesPackageEvidence::Unavailable,
            WorkspacePackageEvidence::InvalidReference(_) => {
                WorkspaceFilesPackageEvidence::InvalidReference
            }
        },
        source_index: match file.index_state() {
            WorkspaceFileIndexState::Indexed => WorkspaceFilesIndexState::Indexed,
            WorkspaceFileIndexState::MetadataUnavailable if index_evidence_complete => {
                WorkspaceFilesIndexState::NotIndexed
            }
            WorkspaceFileIndexState::MetadataUnavailable
            | WorkspaceFileIndexState::Incompatible(_) => WorkspaceFilesIndexState::Unknown,
            WorkspaceFileIndexState::NotApplicable => WorkspaceFilesIndexState::NotApplicable,
        },
        drift: match file.drift() {
            WorkspaceFileDrift::InSync => WorkspaceFilesDrift::None,
            WorkspaceFileDrift::FilesystemOnly => WorkspaceFilesDrift::FilesystemOnly,
            WorkspaceFileDrift::IndexOnly => WorkspaceFilesDrift::IndexOnly,
            WorkspaceFileDrift::MissingOnDisk => WorkspaceFilesDrift::MissingOnDisk,
            WorkspaceFileDrift::Unknown => WorkspaceFilesDrift::Unknown,
            WorkspaceFileDrift::NotApplicable => WorkspaceFilesDrift::NotApplicable,
        },
        dirty: match file.dirty_state() {
            WorkspaceFileDirtyState::Clean => WorkspaceFilesDirty::Clean,
            WorkspaceFileDirtyState::Dirty => WorkspaceFilesDirty::Dirty,
            WorkspaceFileDirtyState::Unknown => WorkspaceFilesDirty::Unknown,
            WorkspaceFileDirtyState::NotApplicable => WorkspaceFilesDirty::NotApplicable,
        },
        evidence: evidence_selected.then(|| {
            file.evidence()
                .iter()
                .map(|source| match source {
                    WorkspaceEvidenceSource::Manifest => WorkspaceFilesEvidenceSource::Manifest,
                    WorkspaceEvidenceSource::PackageMetadata => {
                        WorkspaceFilesEvidenceSource::PackageMetadata
                    }
                    WorkspaceEvidenceSource::GradleProjectModel => {
                        WorkspaceFilesEvidenceSource::GradleProjectModel
                    }
                })
                .collect()
        }),
    }
}

fn workspace_files_build_root(path: &Path) -> String {
    if path.as_os_str().is_empty() {
        ".".to_string()
    } else {
        path.display().to_string()
    }
}

fn workspace_files_coverage(dimension: WorkspaceCoverageDimension) -> WorkspaceFilesCoverageDimension {
    match dimension {
        WorkspaceCoverageDimension::Complete => WorkspaceFilesCoverageDimension::Complete,
        WorkspaceCoverageDimension::Partial => WorkspaceFilesCoverageDimension::Partial,
    }
}

fn workspace_files_limitation_code(code: WorkspaceInventoryLimitationCode) -> &'static str {
    match code {
        WorkspaceInventoryLimitationCode::BackendCapabilityUnavailable => "BACKEND_CAPABILITY_UNAVAILABLE",
        WorkspaceInventoryLimitationCode::BackendMetadataUnavailable => "BACKEND_METADATA_UNAVAILABLE",
        WorkspaceInventoryLimitationCode::BackendPageIncomplete => "BACKEND_PAGE_INCOMPLETE",
        WorkspaceInventoryLimitationCode::BackendWorkspaceInventoryStale => "BACKEND_WORKSPACE_INVENTORY_STALE",
        WorkspaceInventoryLimitationCode::RuntimeIndexing => "RUNTIME_INDEXING",
        WorkspaceInventoryLimitationCode::ProjectModelUnavailable => "PROJECT_MODEL_UNAVAILABLE",
        WorkspaceInventoryLimitationCode::LinkedRootUnassociated => "LINKED_ROOT_UNASSOCIATED",
        WorkspaceInventoryLimitationCode::SourceIndexUnavailable => "SOURCE_INDEX_UNAVAILABLE",
        WorkspaceInventoryLimitationCode::SourceIndexIncompatible => "SOURCE_INDEX_INCOMPATIBLE",
        WorkspaceInventoryLimitationCode::SourceIndexProgressIncomplete => "SOURCE_INDEX_PROGRESS_INCOMPLETE",
        WorkspaceInventoryLimitationCode::SourceIndexUpdatesPending => "SOURCE_INDEX_UPDATES_PENDING",
        WorkspaceInventoryLimitationCode::GitUnavailable => "GIT_UNAVAILABLE",
        WorkspaceInventoryLimitationCode::CrossSourceCompositionUnstable => "CROSS_SOURCE_COMPOSITION_UNSTABLE",
        WorkspaceInventoryLimitationCode::PathContainmentUnprovable => "PATH_CONTAINMENT_UNPROVABLE",
        WorkspaceInventoryLimitationCode::PackageMetadataInvalid => "PACKAGE_METADATA_INVALID",
        WorkspaceInventoryLimitationCode::UnknownProjectModelOwnership => "UNKNOWN_PROJECT_MODEL_OWNERSHIP",
        WorkspaceInventoryLimitationCode::ProjectModelOwnershipUnknown => "PROJECT_MODEL_OWNERSHIP_UNKNOWN",
        WorkspaceInventoryLimitationCode::OutOfRootExcluded => "OUT_OF_ROOT_EXCLUDED",
    }
}

fn workspace_files_next_action(
    admitted_query: &AdmittedWorkspaceFilesQueryIdentity,
) -> WorkspaceFilesNextAction {
    let mut arguments = vec![
        "agent".to_string(),
        "verify".to_string(),
        "--workspace-root".to_string(),
        admitted_query.canonical_workspace_root.clone(),
    ];
    if let Some(backend) = admitted_query.backend {
        arguments.extend(["--backend".to_string(), backend.to_string()]);
    }
    WorkspaceFilesNextAction {
        kind: "VERIFY_WORKSPACE",
        command: "kast",
        arguments,
        mutates_global_install_authority: false,
    }
}

fn admit_workspace_files_query(
    args: &AgentWorkspaceFilesArgs,
) -> std::result::Result<
    (
        AdmittedWorkspaceFilesQueryIdentity,
        Option<WorkspaceFilesPageHandleIdentity>,
    ),
    AgentError,
> {
    let workspace = AgentFilePathNormalizer::from_runtime(&args.runtime)?;
    let canonical_workspace_root = workspace
        .canonical_root
        .to_str()
        .ok_or_else(|| {
            agent_path_error(
                "AGENT_WORKSPACE_INVALID",
                "The canonical agent workspace root is not valid UTF-8.",
                Some(&workspace.declared_root),
                Some(&workspace.canonical_root),
                None,
            )
        })?
        .to_string();
    let package = args
        .package_selector
        .as_ref()
        .map(WorkspacePackageSelector::canonical);
    let package_name = args
        .package_selector
        .as_ref()
        .and_then(|selector| match selector {
            WorkspacePackageSelector::Root => None,
            WorkspacePackageSelector::Named(package_name) => Some(package_name.semantic_fq_name()),
        });
    let filters = AdmittedWorkspaceFileFilters {
        module: args.module.as_ref().map(WorkspaceModuleSelector::canonical),
        source_set: args
            .source_set
            .as_ref()
            .map(WorkspaceSourceSetName::as_str)
            .map(str::to_string),
        kind: args.kind.map(WorkspaceFileKindFilter::canonical),
        package,
        package_name,
        dirty: args.dirty.map(WorkspaceDirtyFilter::canonical),
        drift: args.drift.map(WorkspaceDriftFilter::canonical),
        path_prefix: args
            .path_prefix
            .as_ref()
            .map(WorkspaceRelativePathPrefix::as_str)
            .map(str::to_string),
        glob: args
            .glob
            .as_ref()
            .map(WorkspaceRelativeGlob::as_str)
            .map(str::to_string),
    };
    let ordered_fields = args
        .view
        .fields
        .iter()
        .copied()
        .map(AgentWorkspaceFilesField::canonical)
        .collect();
    let admitted_query = AdmittedWorkspaceFilesQueryIdentity {
        canonical_workspace_root,
        backend: args.runtime.backend_name.map(BackendName::canonical),
        filters,
        kind_domain: args.kind_domain().canonical(),
        view: workspace_files_view_name(&args.view),
        ordered_fields,
        limit: args.limit.get(),
    };
    let page_handle = args
        .page_token
        .as_ref()
        .map(WorkspaceFilesPublicPageToken::canonical)
        .map(|token| WorkspaceFilesPageHandleIdentity { token });
    Ok((admitted_query, page_handle))
}

fn workspace_files_view_name(view: &AgentWorkspaceFilesViewArgs) -> &'static str {
    if view.verbose {
        "verbose"
    } else if view.explain {
        "explain"
    } else if view.count {
        "count"
    } else if view.fields.is_empty() {
        "compact"
    } else {
        "fields"
    }
}
