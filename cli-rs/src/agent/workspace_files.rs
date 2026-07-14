fn execute_agent_workspace_files(args: AgentWorkspaceFilesArgs) -> AgentEnvelope {
    let kind_domain = args.kind_domain();
    let normalized_query = json!({
        "workspaceRoot": args.runtime.workspace_root,
        "backend": args.runtime.backend_name.map(BackendName::canonical),
        "module": args.module.as_ref().map(WorkspaceModuleSelector::canonical),
        "sourceSet": args.source_set.as_ref().map(WorkspaceSourceSetName::as_str),
        "kind": args.kind.map(WorkspaceFileKindFilter::canonical).unwrap_or("mixed"),
        "kindDomain": kind_domain.canonical(),
        "package": args.package_selector.as_ref().map(WorkspacePackageSelector::canonical),
        "packageName": args.package_selector.as_ref().and_then(|selector| match selector {
            WorkspacePackageSelector::Root => None,
            WorkspacePackageSelector::Named(package_name) => Some(package_name.semantic_fq_name()),
        }),
        "dirty": args.dirty.map(WorkspaceDirtyFilter::canonical),
        "drift": args.drift.map(WorkspaceDriftFilter::canonical),
        "pathPrefix": args.path_prefix.as_ref().map(WorkspaceRelativePathPrefix::as_str),
        "glob": args.glob.as_ref().map(WorkspaceRelativeGlob::as_str),
        "limit": args.limit.get(),
        "pageToken": args.page_token.as_ref().map(WorkspaceFilesPublicPageToken::canonical),
    });
    let mut error = agent_error(
        "WORKSPACE_FILE_DISCOVERY_UNAVAILABLE",
        "Workspace file discovery is not available until the typed inventory is initialized.",
    );
    error
        .details
        .insert("normalizedQuery".to_string(), normalized_query);
    error_envelope("agent/workspace-files".to_string(), None, error)
}
