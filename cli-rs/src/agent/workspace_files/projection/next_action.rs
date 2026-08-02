fn workspace_files_next_action(
    admitted_query: &AdmittedWorkspaceFilesQueryIdentity,
) -> WorkspaceFilesNextAction {
    let arguments = vec![
        "agent".to_string(),
        "verify".to_string(),
        "--workspace-root".to_string(),
        admitted_query.canonical_workspace_root.clone(),
    ];
    WorkspaceFilesNextAction {
        kind: "VERIFY_WORKSPACE",
        command: "kast",
        arguments,
        mutates_global_install_authority: false,
    }
}
