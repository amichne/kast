pub fn install_skill(args: ResourceInstallArgs) -> Result<InstallSkillResult> {
    let target_root = args
        .target_dir
        .map(config::normalize)
        .unwrap_or_else(default_skill_target_dir);
    let name = args
        .name
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| "kast".to_string());
    let target = target_root.join(name);
    let files = thin_skill_install_files(args.source_dir.as_deref())?;
    let outcome = install_embedded_resource(
        ManagedResourceKind::Skill,
        &target,
        &files,
        args.force,
        &[RESOURCE_MARKER],
    )?;
    let repo_root = resource_repo_root(&target);
    let git_exclude = match &repo_root {
        Some(repo_root) => update_resource_git_exclude(
            ManagedResourceKind::Skill,
            repo_root,
            &target,
            &outcome.output_paths,
            args.no_auto_exclude_git,
        )?,
        None => git_exclude_not_repository(),
    };
    let record_root = repo_root.as_ref().unwrap_or(&target_root);
    record_managed_resource(ManagedResourceKind::Skill, record_root, &target, &outcome)?;
    Ok(InstallSkillResult {
        installed_at: target.display().to_string(),
        version: cli::version().to_string(),
        source_bundle_sha256: outcome.source_bundle_sha256,
        output_paths: outcome
            .output_paths
            .iter()
            .map(|path| path.display().to_string())
            .collect(),
        skipped: outcome.skipped,
        git_exclude,
        schema_version: SCHEMA_VERSION,
    })
}
