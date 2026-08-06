fn current_release_matches(targets: &ActivationTargetPaths) -> bool {
    match (
        fs::canonicalize(&targets.current_link),
        fs::canonicalize(&targets.version_dir),
    ) {
        (Ok(current), Ok(version)) => current == version,
        _ => false,
    }
}

fn setup_result(
    bundle: &ValidatedBundle,
    targets: &ActivationTargetPaths,
    status: SetupStatus,
    backup: Option<&Path>,
    retired_plugin_removal: &RetiredPublicPluginRemoval,
) -> Result<SetupResult> {
    Ok(SetupResult {
        result_type: "KAST_SETUP",
        status,
        release_digest: bundle.release_digest.clone(),
        manifest_digest: bundle.manifest_digest.clone(),
        kast_home: targets.resolved.install_root.display().to_string(),
        current: targets.current_link.display().to_string(),
        active_binary: targets.resolved.active_binary.display().to_string(),
        developer_operations: DeveloperOperationsRoute::try_from_cli_path(
            &targets.resolved.active_binary,
        )?,
        backup: backup.map(|path| path.display().to_string()),
        restart_requirement: retired_plugin_removal.restart_requirement.clone(),
        artifacts: bundle
            .manifest
            .artifacts
            .iter()
            .map(|artifact| SetupArtifact {
                role: artifact.role.clone(),
                path: targets
                    .current_link
                    .join(&artifact.path)
                    .display()
                    .to_string(),
                sha256: artifact.sha256.clone(),
                verified: true,
            })
            .collect(),
        verified: true,
        schema_version: SCHEMA_VERSION,
    })
}
