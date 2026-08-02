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
        backend: None,
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
