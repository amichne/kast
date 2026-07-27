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
