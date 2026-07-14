#[derive(Debug, Serialize)]
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

#[derive(Debug, Serialize)]
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

fn execute_agent_workspace_files(args: AgentWorkspaceFilesArgs) -> AgentEnvelope {
    let (admitted_query, page_handle) = match admit_workspace_files_query(&args) {
        Ok(admitted) => admitted,
        Err(error) => {
            return error_envelope("agent/workspace-files".to_string(), None, error);
        }
    };
    let next_action = workspace_files_next_action(&admitted_query);
    let mut error = agent_error(
        "WORKSPACE_FILE_DISCOVERY_UNAVAILABLE",
        "Workspace file discovery is not available until the typed inventory is initialized.",
    );
    error.details.insert(
        "admittedQuery".to_string(),
        serde_json::to_value(admitted_query)
            .expect("the admitted workspace-file query identity is serializable"),
    );
    if let Some(page_handle) = page_handle {
        error.details.insert(
            "pageHandle".to_string(),
            serde_json::to_value(page_handle)
                .expect("the workspace-file page handle is serializable"),
        );
    }
    error.details.insert(
        "nextAction".to_string(),
        serde_json::to_value(next_action).expect("the workspace-file next action is serializable"),
    );
    error_envelope("agent/workspace-files".to_string(), None, error)
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
