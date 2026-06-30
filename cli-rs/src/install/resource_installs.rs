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
    let files = thin_skill_install_files(args.source_dir.as_deref(), &KAST_SKILL)?;
    let outcome = install_embedded_resource(
        ManagedResourceKind::Skill,
        &target,
        &files,
        args.force,
        &[RESOURCE_MARKER],
        ResourceReplaceMode::WholeDirectory,
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

pub fn install_instructions(args: ResourceInstallArgs) -> Result<InstallInstructionsResult> {
    let target_root = args
        .target_dir
        .map(config::normalize)
        .unwrap_or_else(default_instructions_target_dir);
    let name = args
        .name
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| "kast".to_string());
    let target = target_root.join(name);
    let files = thin_instruction_install_files(args.source_dir.as_deref(), &KAST_INSTRUCTIONS)?;
    let outcome = install_embedded_resource(
        ManagedResourceKind::Instructions,
        &target,
        &files,
        args.force,
        &[RESOURCE_MARKER],
        ResourceReplaceMode::WholeDirectory,
    )?;
    let repo_root = resource_repo_root(&target);
    let git_exclude = match &repo_root {
        Some(repo_root) => update_resource_git_exclude(
            ManagedResourceKind::Instructions,
            repo_root,
            &target,
            &outcome.output_paths,
            args.no_auto_exclude_git,
        )?,
        None => git_exclude_not_repository(),
    };
    let record_root = repo_root.as_ref().unwrap_or(&target_root);
    record_managed_resource(
        ManagedResourceKind::Instructions,
        record_root,
        &target,
        &outcome,
    )?;
    Ok(InstallInstructionsResult {
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

pub fn install_copilot(args: CopilotInstallArgs) -> Result<InstallCopilotPackageResult> {
    let target = args.target_dir.map(config::normalize).unwrap_or_else(|| {
        config::normalize(
            env::current_dir()
                .unwrap_or_else(|_| PathBuf::from("."))
                .join(".github"),
        )
    });
    let files = copilot_package_outputs()?
        .into_iter()
        .map(|output| EmbeddedResourceFile {
            relative: output.target,
            contents: output.contents.to_vec(),
            executable: output.executable,
        })
        .collect::<Vec<_>>();
    let outcome = install_embedded_resource(
        ManagedResourceKind::CopilotPackage,
        &target,
        &files,
        args.force,
        &[COPILOT_PACKAGE_MARKER],
        ResourceReplaceMode::ManagedFilesOnly,
    )?;
    let repo_root = resource_repo_root(&target);
    let git_exclude = match &repo_root {
        Some(repo_root) => update_resource_git_exclude(
            ManagedResourceKind::CopilotPackage,
            repo_root,
            &target,
            &outcome.output_paths,
            args.no_auto_exclude_git,
        )?,
        None => git_exclude_not_repository(),
    };
    if let Some(repo_root) = &repo_root {
        record_managed_resource(
            ManagedResourceKind::CopilotPackage,
            repo_root,
            &target,
            &outcome,
        )?;
    }
    Ok(InstallCopilotPackageResult {
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
        warnings: vec![],
        schema_version: SCHEMA_VERSION,
    })
}
