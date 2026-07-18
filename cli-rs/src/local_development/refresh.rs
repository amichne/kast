pub fn refresh_local_development(
    request: LocalDevelopmentRefreshRequest,
) -> Result<LocalDevelopmentRefreshResult> {
    refresh_local_development_with_observer(request, |_| Ok(()))
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum LocalRefreshPhase {
    AfterActivation,
}

fn refresh_local_development_with_observer(
    request: LocalDevelopmentRefreshRequest,
    observe: impl FnMut(LocalRefreshPhase) -> Result<()>,
) -> Result<LocalDevelopmentRefreshResult> {
    let expected = SourceSnapshot::read_strict(&request.expected_source_snapshot)?;
    let source = SourceSnapshot::capture(&request.source_root)?;
    if source != expected {
        return Err(source_snapshot_mismatch(&expected, &source));
    }
    let workspace_root = canonical_directory(&request.workspace_root, "exact workspace root")?;
    let cli_binary = canonical_file(&request.cli_binary, "development CLI binary")?;
    if cfg!(not(test)) {
        let controller = fs::canonicalize(std::env::current_exe()?)?;
        if controller != cli_binary {
            return Err(CliError::new(
                "LOCAL_REFRESH_CONTROLLER_MISMATCH",
                format!(
                    "Local refresh must be executed by the exact staged CLI: controller {}, staged {}.",
                    controller.display(),
                    cli_binary.display(),
                ),
            ));
        }
    }
    let backend_directory =
        canonical_directory(&request.backend_directory, "headless backend distribution")?;
    validate_backend_distribution(&backend_directory)?;
    let cli_provenance = read_local_artifact_provenance(&request.cli_provenance)?;
    validate_local_artifact_provenance(
        &cli_provenance,
        LocalArtifactKind::Cli,
        &source,
        &cli_binary,
    )?;
    let backend_provenance = read_local_artifact_provenance(&request.backend_provenance)?;
    validate_local_artifact_provenance(
        &backend_provenance,
        LocalArtifactKind::HeadlessBackend,
        &source,
        &backend_directory,
    )?;
    if cli_provenance.implementation_version != backend_provenance.implementation_version {
        return Err(CliError::new(
            "LOCAL_ARTIFACT_VERSION_MISMATCH",
            format!(
                "CLI version {} and backend version {} do not form one local artifact set.",
                cli_provenance.implementation_version, backend_provenance.implementation_version,
            ),
        ));
    }
    let skill_source = canonical_file(&request.skill_source, "local skill source")?;
    let config_source = canonical_file(&request.config_source, "local configuration source")?;
    let artifacts = LocalDevelopmentArtifactSet {
        cli: cli_provenance,
        backend: backend_provenance,
    };

    activate_local_development_artifact_set(
        LocalDevelopmentArtifactActivationRequest {
            source_root: &request.source_root,
            workspace_root,
            prefix: request.prefix,
            source,
            expected,
            cli_binary,
            backend_directory,
            skill_source,
            config_source,
            artifacts,
        },
        observe,
    )
}

struct LocalDevelopmentArtifactActivationRequest<'a> {
    source_root: &'a Path,
    workspace_root: PathBuf,
    prefix: PathBuf,
    source: SourceSnapshot,
    expected: SourceSnapshot,
    cli_binary: PathBuf,
    backend_directory: PathBuf,
    skill_source: PathBuf,
    config_source: PathBuf,
    artifacts: LocalDevelopmentArtifactSet,
}

fn activate_local_development_artifact_set(
    request: LocalDevelopmentArtifactActivationRequest<'_>,
    mut observe: impl FnMut(LocalRefreshPhase) -> Result<()>,
) -> Result<LocalDevelopmentRefreshResult> {
    let LocalDevelopmentArtifactActivationRequest {
        source_root,
        workspace_root,
        prefix,
        source,
        expected,
        cli_binary,
        backend_directory,
        skill_source,
        config_source,
        artifacts,
    } = request;

    let requested_prefix = absolute_path(prefix)?;
    reject_symlink_selected_prefix(&requested_prefix)?;
    let generation_id = LocalGenerationId::from_artifact_set(&source, &artifacts);

    with_local_authority_lock(&requested_prefix, || {
        fs::create_dir_all(&requested_prefix)?;
        let prefix = canonical_directory(&requested_prefix, "local-development prefix")?;
        let generations = prefix.join("generations");
        let generation = generations.join(generation_id.as_str());
        fs::create_dir_all(&generations)?;
        let current_link = prefix.join("current");
        let current_target = read_generation_link(&current_link)?;
        let previous_target_before = read_generation_link(&prefix.join("previous"))?;
        let current_receipt = current_target
            .as_ref()
            .map(|target| -> Result<LocalDevelopmentReceipt> {
                let active_generation = prefix.join(target.as_path());
                let receipt =
                    read_local_development_receipt(&active_generation.join("authority.json"))?;
                validate_receipt_identity(
                    &receipt,
                    &prefix,
                    &active_generation,
                    &receipt.workspace_root,
                )?;
                validate_receipt_fast_components(&receipt)?;
                validate_stable_authority(
                    &prefix.join("authority.json"),
                    &receipt,
                    &active_generation,
                )?;
                Ok(receipt)
            })
            .transpose()?;
        if let Some(receipt) = &current_receipt {
            if receipt.workspace_root != workspace_root {
                return Err(CliError::new(
                    "LOCAL_WORKSPACE_AUTHORITY_MISMATCH",
                    format!(
                        "Local-development prefix {} belongs to workspace {}, not {}.",
                        prefix.display(),
                        receipt.workspace_root.display(),
                        workspace_root.display(),
                    ),
                ));
            }
        } else {
            reject_unowned_prefix_contents(&prefix, &generation)?;
        }
        validate_workspace_guidance_target(&workspace_root, &prefix)?;
        require_workspace_guidance_ignored(&workspace_root)?;
        let previous_generation = current_receipt
            .as_ref()
            .map(|receipt| receipt.generation_id.clone());

        if current_receipt
            .as_ref()
            .is_some_and(|receipt| receipt.generation_id == generation_id)
        {
            let current_receipt = current_receipt.expect("checked as present");
            if !artifact_sets_equivalent(&current_receipt.artifacts, &artifacts) {
                return Err(CliError::new(
                    "LOCAL_GENERATION_ARTIFACT_MISMATCH",
                    "The active source generation was rebuilt with different CLI or backend bytes.",
                ));
            }
            validate_physical_component(&current_receipt.components.backend)?;
            let after_validation = SourceSnapshot::capture(source_root)?;
            if after_validation != expected {
                return Err(source_snapshot_mismatch(&expected, &after_validation));
            }
            return Ok(LocalDevelopmentRefreshResult {
                receipt: current_receipt,
                skipped: true,
                schema_version: crate::SCHEMA_VERSION,
            });
        }
        reject_live_local_runtimes(&prefix)?;

        let (generation_created, generation_receipt) = if generation.is_dir() {
            let receipt = read_local_development_receipt(&generation.join("authority.json"))?;
            validate_receipt_identity(&receipt, &prefix, &generation, &workspace_root)?;
            if receipt.source != source {
                return Err(CliError::new(
                    "LOCAL_GENERATION_CONFLICT",
                    format!(
                        "Generation {} has a different source identity.",
                        generation.display()
                    ),
                ));
            }
            validate_receipt_physical_components(&receipt)?;
            if !artifact_sets_equivalent(&receipt.artifacts, &artifacts) {
                return Err(CliError::new(
                    "LOCAL_GENERATION_ARTIFACT_MISMATCH",
                    "The stored source generation was built with different CLI or backend bytes.",
                ));
            }
            (false, receipt)
        } else {
            stage_generation(GenerationStageRequest {
                prefix: &prefix,
                generation: &generation,
                generation_id: &generation_id,
                source: &source,
                workspace_root: &workspace_root,
                cli_binary: &cli_binary,
                backend_directory: &backend_directory,
                skill_source: &skill_source,
                config_source: &config_source,
                artifacts: &artifacts,
                previous_generation: previous_generation.as_ref(),
            })?;
            let receipt = read_local_development_receipt(&generation.join("authority.json"))?;
            validate_receipt_identity(&receipt, &prefix, &generation, &workspace_root)?;
            (true, receipt)
        };

        let after_staging = SourceSnapshot::capture(source_root)?;
        if after_staging != expected {
            if generation_created {
                let _ = fs::remove_dir_all(&generation);
            }
            return Err(source_snapshot_mismatch(&expected, &after_staging));
        }

        let stable_entrypoint_before = current_receipt
            .as_ref()
            .map(|_| fs::read_link(prefix.join("bin/kast")))
            .transpose()?;
        let mut transaction = LocalRefreshTransaction::new(
            &prefix,
            &workspace_root,
            current_target,
            previous_target_before,
            generation_created.then_some(generation.clone()),
            stable_entrypoint_before,
        );
        let activation = (|| -> Result<LocalDevelopmentRefreshResult> {
            transaction.guidance_link_created =
                ensure_workspace_guidance_link(&workspace_root, &prefix)?;
            transaction.stable_entrypoints_installed = true;
            install_stable_entrypoints(&prefix, &generation_receipt)?;
            if let Some(current_target) = transaction.current_before.as_ref() {
                replace_relative_symlink(&prefix.join("previous"), current_target.as_path())?;
                transaction.previous_changed = true;
            }
            replace_relative_symlink(&current_link, &generation_target(&generation_id))?;
            transaction.current_changed = true;
            observe(LocalRefreshPhase::AfterActivation)?;
            let receipt = read_local_development_receipt(&prefix.join("authority.json"))?;
            validate_receipt_identity(&receipt, &prefix, &generation, &workspace_root)?;
            validate_receipt_fast_components(&receipt)?;
            Ok(LocalDevelopmentRefreshResult {
                receipt,
                skipped: !generation_created,
                schema_version: crate::SCHEMA_VERSION,
            })
        })();
        match activation {
            Ok(result) => Ok(result),
            Err(mut error) => {
                if let Err(cleanup) = transaction.rollback() {
                    error
                        .details
                        .insert("rollbackError".to_string(), cleanup.to_string());
                }
                Err(error)
            }
        }
    })
}

struct LocalRefreshTransaction {
    prefix: PathBuf,
    workspace_root: PathBuf,
    current_before: Option<PathBuf>,
    previous_before: Option<PathBuf>,
    created_generation: Option<PathBuf>,
    stable_entrypoint_before: Option<PathBuf>,
    guidance_link_created: bool,
    stable_entrypoints_installed: bool,
    previous_changed: bool,
    current_changed: bool,
}

impl LocalRefreshTransaction {
    fn new(
        prefix: &Path,
        workspace_root: &Path,
        current_before: Option<PathBuf>,
        previous_before: Option<PathBuf>,
        created_generation: Option<PathBuf>,
        stable_entrypoint_before: Option<PathBuf>,
    ) -> Self {
        Self {
            prefix: prefix.to_path_buf(),
            workspace_root: workspace_root.to_path_buf(),
            current_before,
            previous_before,
            created_generation,
            stable_entrypoint_before,
            guidance_link_created: false,
            stable_entrypoints_installed: false,
            previous_changed: false,
            current_changed: false,
        }
    }

    fn rollback(&self) -> Result<()> {
        let mut failures = Vec::new();
        let mut current_restored = !self.current_changed;
        if self.current_changed {
            match restore_generation_link(
                &self.prefix.join("current"),
                self.current_before.as_deref(),
            ) {
                Ok(()) => current_restored = true,
                Err(error) => failures.push(error.to_string()),
            }
        }
        if self.previous_changed
            && let Err(error) = restore_generation_link(
                &self.prefix.join("previous"),
                self.previous_before.as_deref(),
            )
        {
            failures.push(error.to_string());
        }
        if self.guidance_link_created
            && let Err(error) =
                remove_owned_workspace_guidance_link(&self.workspace_root, &self.prefix)
        {
            failures.push(error.to_string());
        }
        if self.stable_entrypoints_installed {
            if let Some(contents) = &self.stable_entrypoint_before {
                if let Err(error) =
                    replace_relative_symlink(&self.prefix.join("bin/kast"), contents)
                {
                    failures.push(error.to_string());
                }
            } else {
                for path in [
                    self.prefix.join("bin/kast"),
                    self.prefix.join("authority.json"),
                    self.prefix.join("install.json"),
                ] {
                    if let Err(error) = remove_path_if_present(&path) {
                        failures.push(error.to_string());
                    }
                }
            }
        }
        if current_restored
            && let Some(generation) = &self.created_generation
            && let Err(error) = fs::remove_dir_all(generation)
            && error.kind() != std::io::ErrorKind::NotFound
        {
            failures.push(error.to_string());
        }
        if failures.is_empty() {
            Ok(())
        } else {
            Err(CliError::new(
                "LOCAL_REFRESH_ROLLBACK_FAILED",
                failures.join("; "),
            ))
        }
    }
}

struct GenerationStageRequest<'a> {
    prefix: &'a Path,
    generation: &'a Path,
    generation_id: &'a LocalGenerationId,
    source: &'a SourceSnapshot,
    workspace_root: &'a Path,
    cli_binary: &'a Path,
    backend_directory: &'a Path,
    skill_source: &'a Path,
    config_source: &'a Path,
    artifacts: &'a LocalDevelopmentArtifactSet,
    previous_generation: Option<&'a LocalGenerationId>,
}

fn stage_generation(request: GenerationStageRequest<'_>) -> Result<()> {
    let GenerationStageRequest {
        prefix,
        generation,
        generation_id,
        source,
        workspace_root,
        cli_binary,
        backend_directory,
        skill_source,
        config_source,
        artifacts,
        previous_generation,
    } = request;
    let staged = prefix.join(format!(".staging-{}", generation_id.as_str()));
    if staged.exists() {
        fs::remove_dir_all(&staged)?;
    }
    fs::create_dir_all(&staged)?;
    let result = (|| -> Result<()> {
        let physical_entrypoint = staged.join("entrypoint/kast");
        write_bytes(
            &physical_entrypoint,
            local_entrypoint_script(prefix).as_bytes(),
        )?;
        crate::manifest::make_executable(&physical_entrypoint)?;

        let physical_cli = staged.join("bin/kast");
        write_bytes(&physical_cli, &fs::read(cli_binary)?)?;
        crate::manifest::make_executable(&physical_cli)?;

        let physical_backend = staged.join("lib/backends/headless/current");
        copy_directory_tree(backend_directory, &physical_backend)?;
        validate_backend_distribution(&physical_backend)?;

        let entrypoint_target = prefix.join("bin/kast");
        let physical_skill = staged.join("lib/skills/kast/SKILL.md");
        let rendered_skill = render_local_skill(skill_source, &entrypoint_target)?;
        validate_rendered_command_lockstep(&rendered_skill, &entrypoint_target)?;
        write_bytes(&physical_skill, rendered_skill.as_bytes())?;
        let effective_skill = prefix.join("current/lib/skills/kast/SKILL.md");
        let physical_guidance = staged.join("guidance/AGENTS.local.md");
        let rendered_guidance = render_local_guidance(&effective_skill, &entrypoint_target, source);
        validate_rendered_command_lockstep(&rendered_guidance, &entrypoint_target)?;
        write_bytes(&physical_guidance, rendered_guidance.as_bytes())?;
        let physical_config = staged.join("config/config.toml");
        write_bytes(&physical_config, &fs::read(config_source)?)?;

        let install_manifest =
            local_install_manifest(prefix, generation_id, source, workspace_root);
        crate::manifest::write_manifest_atomic(&staged.join("install.json"), &install_manifest)?;

        let components = LocalDevelopmentComponents {
            cli: component(
                &physical_cli,
                &generation.join("bin/kast"),
                &prefix.join("current/bin/kast"),
            )?,
            backend: component(
                &physical_backend,
                &generation.join("lib/backends/headless/current"),
                &prefix.join("current/lib/backends/headless/current"),
            )?,
            skill: component(
                &physical_skill,
                &generation.join("lib/skills/kast/SKILL.md"),
                &effective_skill,
            )?,
            guidance: component(
                &physical_guidance,
                &generation.join("guidance/AGENTS.local.md"),
                &workspace_root.join("AGENTS.local.md"),
            )?,
            config: component(
                &physical_config,
                &generation.join("config/config.toml"),
                &prefix.join("current/config/config.toml"),
            )?,
            manifest: component(
                &staged.join("install.json"),
                &generation.join("install.json"),
                &prefix.join("install.json"),
            )?,
        };
        let receipt = LocalDevelopmentReceipt {
            schema_version: LOCAL_DEVELOPMENT_RECEIPT_SCHEMA_VERSION,
            authority: LocalDevelopmentAuthority::LocalDevelopment,
            generation_id: generation_id.clone(),
            source: source.clone(),
            workspace_root: workspace_root.to_path_buf(),
            prefix: prefix.to_path_buf(),
            entrypoint: LocalDevelopmentEntrypoint {
                physical_target: generation.join("entrypoint/kast"),
                effective_target: entrypoint_target.clone(),
                sha256: Sha256Digest::try_from(crate::manifest::sha256_file(
                    &physical_entrypoint,
                )?)?,
            },
            backend: LocalDevelopmentBackendIdentity {
                kind: LocalDevelopmentBackendKind::Headless,
                implementation_version: artifacts.backend.implementation_version.clone(),
            },
            artifacts: artifacts.clone(),
            components,
            install_manifest: generation.join("install.json"),
            previous_generation: previous_generation.cloned(),
            updated_at: crate::manifest::current_timestamp(),
        };
        write_json_atomic(&staged.join("authority.json"), &receipt)?;
        fs::rename(&staged, generation)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_dir_all(&staged);
    }
    result
}

fn component(
    staged_target: &Path,
    physical_target: &Path,
    effective_target: &Path,
) -> Result<LocalDevelopmentComponent> {
    Ok(LocalDevelopmentComponent {
        physical_target: physical_target.to_path_buf(),
        effective_target: effective_target.to_path_buf(),
        sha256: tree_sha256(staged_target)?,
    })
}

fn generation_target(generation_id: &LocalGenerationId) -> PathBuf {
    Path::new("generations").join(generation_id.as_str())
}

fn read_generation_link(path: &Path) -> Result<Option<PathBuf>> {
    let Some(target) = read_relative_symlink(path)? else {
        return Ok(None);
    };
    let mut components = target.components();
    let generation_directory = components.next();
    let generation_name = components.next();
    if generation_directory != Some(Component::Normal("generations".as_ref()))
        || components.next().is_some()
    {
        return Err(CliError::new(
            "LOCAL_AUTHORITY_LINK_INVALID",
            format!(
                "Local authority link {} must target generations/<generation-id>, not {}.",
                path.display(),
                target.display(),
            ),
        ));
    }
    let generation_name = generation_name
        .and_then(|name| name.as_os_str().to_str())
        .ok_or_else(|| {
            CliError::new(
                "LOCAL_AUTHORITY_LINK_INVALID",
                format!(
                    "Local authority link {} has a non-UTF-8 generation.",
                    path.display()
                ),
            )
        })?;
    let generation_id = LocalGenerationId::try_from(generation_name.to_string())?;
    Ok(Some(generation_target(&generation_id)))
}

fn restore_generation_link(path: &Path, target: Option<&Path>) -> Result<()> {
    match target {
        Some(target) => replace_relative_symlink(path, target),
        None => remove_path_if_present(path),
    }
}

fn remove_path_if_present(path: &Path) -> Result<()> {
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.is_dir() && !metadata.file_type().is_symlink() => {
            fs::remove_dir_all(path)?;
            Ok(())
        }
        Ok(_) => {
            fs::remove_file(path)?;
            Ok(())
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error.into()),
    }
}

fn reject_unowned_prefix_contents(prefix: &Path, allowed_generation: &Path) -> Result<()> {
    let allowed_generation_name = allowed_generation.file_name().ok_or_else(|| {
        CliError::new(
            "LOCAL_PREFIX_INVALID",
            format!(
                "Local generation has no name: {}.",
                allowed_generation.display()
            ),
        )
    })?;
    let allowed_staging_name = format!(".staging-{}", allowed_generation_name.to_string_lossy());
    for entry in fs::read_dir(prefix)? {
        let entry = entry?;
        let path = entry.path();
        match entry.file_name().to_str() {
            Some("locks") => {
                let metadata = entry.file_type()?;
                if !metadata.is_dir() || metadata.is_symlink() {
                    return Err(unowned_prefix_conflict(&path));
                }
                for lock in fs::read_dir(&path)? {
                    let lock = lock?;
                    if lock.file_name() != "refresh.lock" || !lock.file_type()?.is_file() {
                        return Err(unowned_prefix_conflict(&lock.path()));
                    }
                }
            }
            Some("generations") => {
                let metadata = entry.file_type()?;
                if !metadata.is_dir() || metadata.is_symlink() {
                    return Err(unowned_prefix_conflict(&path));
                }
                for generation in fs::read_dir(&path)? {
                    let generation = generation?;
                    if generation.file_name() != allowed_generation_name {
                        return Err(unowned_prefix_conflict(&generation.path()));
                    }
                    let generation_type = generation.file_type()?;
                    if generation_type.is_symlink() {
                        return Err(CliError::new(
                            "LOCAL_AUTHORITY_RECEIPT_INVALID",
                            format!(
                                "Local generation must be an owned directory, not a symlink: {}.",
                                generation.path().display(),
                            ),
                        ));
                    }
                    if !generation_type.is_dir() {
                        return Err(unowned_prefix_conflict(&generation.path()));
                    }
                }
            }
            Some("bin") => {
                let metadata = entry.file_type()?;
                if !metadata.is_dir() || metadata.is_symlink() {
                    return Err(unowned_prefix_conflict(&path));
                }
                for launcher in fs::read_dir(&path)? {
                    let launcher = launcher?;
                    if !matches!(
                        launcher.file_name().to_str(),
                        Some("kast" | "kast.next")
                    )
                        || read_relative_symlink(&launcher.path())?
                            != Some(PathBuf::from("../current/entrypoint/kast"))
                    {
                        return Err(unowned_prefix_conflict(&launcher.path()));
                    }
                }
            }
            Some("authority.json" | "authority.next") => {
                if read_relative_symlink(&path)? != Some(PathBuf::from("current/authority.json")) {
                    return Err(unowned_prefix_conflict(&path));
                }
            }
            Some("install.json" | "install.next") => {
                if read_relative_symlink(&path)? != Some(PathBuf::from("current/install.json")) {
                    return Err(unowned_prefix_conflict(&path));
                }
            }
            Some("current.next") => {
                if read_relative_symlink(&path)?
                    != Some(Path::new("generations").join(allowed_generation_name))
                {
                    return Err(unowned_prefix_conflict(&path));
                }
            }
            Some(name) if name == allowed_staging_name => {
                let metadata = entry.file_type()?;
                if !metadata.is_dir() || metadata.is_symlink() {
                    return Err(unowned_prefix_conflict(&path));
                }
            }
            _ => return Err(unowned_prefix_conflict(&path)),
        }
    }
    Ok(())
}

fn unowned_prefix_conflict(path: &Path) -> CliError {
    CliError::new(
        "LOCAL_PREFIX_CONFLICT",
        format!(
            "Local-development prefix contains unowned state without an active receipt: {}.",
            path.display(),
        ),
    )
}

fn validate_receipt_identity(
    receipt: &LocalDevelopmentReceipt,
    prefix: &Path,
    generation: &Path,
    workspace_root: &Path,
) -> Result<()> {
    let expected_generation_id =
        LocalGenerationId::from_artifact_set(&receipt.source, &receipt.artifacts);
    if receipt.generation_id != expected_generation_id {
        return Err(CliError::new(
            "LOCAL_AUTHORITY_RECEIPT_INVALID",
            "Local-development generation identity does not match its source snapshot.",
        ));
    }
    let expected_generation = prefix
        .join("generations")
        .join(receipt.generation_id.as_str());
    if generation != expected_generation
        || receipt.prefix != prefix
        || receipt.workspace_root != workspace_root
    {
        return Err(CliError::new(
            "LOCAL_AUTHORITY_RECEIPT_INVALID",
            "Local-development receipt prefix, generation, or workspace does not match its selected authority.",
        ));
    }
    let metadata = fs::symlink_metadata(generation).map_err(|error| {
        CliError::new(
            "LOCAL_AUTHORITY_RECEIPT_INVALID",
            format!(
                "Could not inspect generation {}: {error}",
                generation.display()
            ),
        )
    })?;
    if !metadata.is_dir() || metadata.file_type().is_symlink() {
        return Err(CliError::new(
            "LOCAL_AUTHORITY_RECEIPT_INVALID",
            format!(
                "Generation is not an owned directory: {}",
                generation.display()
            ),
        ));
    }
    if fs::canonicalize(generation)? != generation {
        return Err(CliError::new(
            "LOCAL_AUTHORITY_RECEIPT_INVALID",
            format!("Generation path is not canonical: {}", generation.display()),
        ));
    }
    let expected_components = [
        (
            &receipt.components.cli,
            generation.join("bin/kast"),
            prefix.join("current/bin/kast"),
        ),
        (
            &receipt.components.backend,
            generation.join("lib/backends/headless/current"),
            prefix.join("current/lib/backends/headless/current"),
        ),
        (
            &receipt.components.skill,
            generation.join("lib/skills/kast/SKILL.md"),
            prefix.join("current/lib/skills/kast/SKILL.md"),
        ),
        (
            &receipt.components.guidance,
            generation.join("guidance/AGENTS.local.md"),
            workspace_root.join("AGENTS.local.md"),
        ),
        (
            &receipt.components.config,
            generation.join("config/config.toml"),
            prefix.join("current/config/config.toml"),
        ),
        (
            &receipt.components.manifest,
            generation.join("install.json"),
            prefix.join("install.json"),
        ),
    ];
    for (component, physical, effective) in expected_components {
        if component.physical_target != physical || component.effective_target != effective {
            return Err(CliError::new(
                "LOCAL_AUTHORITY_RECEIPT_INVALID",
                format!(
                    "Local component paths escape or disagree with generation {}.",
                    generation.display(),
                ),
            ));
        }
    }
    if receipt.entrypoint.physical_target != generation.join("entrypoint/kast")
        || receipt.entrypoint.effective_target != prefix.join("bin/kast")
        || receipt.install_manifest != generation.join("install.json")
    {
        return Err(CliError::new(
            "LOCAL_AUTHORITY_RECEIPT_INVALID",
            "Local-development entrypoint or install manifest target is not receipt-owned.",
        ));
    }
    if receipt.artifacts.cli.schema_version != LOCAL_ARTIFACT_PROVENANCE_SCHEMA_VERSION
        || receipt.artifacts.backend.schema_version != LOCAL_ARTIFACT_PROVENANCE_SCHEMA_VERSION
        || receipt.artifacts.cli.kind != LocalArtifactKind::Cli
        || receipt.artifacts.backend.kind != LocalArtifactKind::HeadlessBackend
        || receipt.artifacts.cli.source != receipt.source
        || receipt.artifacts.backend.source != receipt.source
        || receipt.artifacts.cli.sha256 != receipt.components.cli.sha256
        || receipt.artifacts.backend.sha256 != receipt.components.backend.sha256
        || receipt.artifacts.cli.implementation_version
            != receipt.artifacts.backend.implementation_version
        || receipt.backend.implementation_version
            != receipt.artifacts.backend.implementation_version
    {
        return Err(CliError::new(
            "LOCAL_AUTHORITY_RECEIPT_INVALID",
            "Local-development receipt artifact provenance does not match its source or installed components.",
        ));
    }
    Ok(())
}

fn artifact_sets_equivalent(
    left: &LocalDevelopmentArtifactSet,
    right: &LocalDevelopmentArtifactSet,
) -> bool {
    left.cli.kind == right.cli.kind
        && left.cli.source == right.cli.source
        && left.cli.sha256 == right.cli.sha256
        && left.cli.implementation_version == right.cli.implementation_version
        && left.backend.kind == right.backend.kind
        && left.backend.source == right.backend.source
        && left.backend.sha256 == right.backend.sha256
        && left.backend.implementation_version == right.backend.implementation_version
}

fn install_stable_entrypoints(prefix: &Path, receipt: &LocalDevelopmentReceipt) -> Result<()> {
    validate_physical_entrypoint(receipt)?;
    replace_relative_symlink(
        &prefix.join("bin/kast"),
        Path::new("../current/entrypoint/kast"),
    )?;
    replace_relative_symlink(
        &prefix.join("authority.json"),
        Path::new("current/authority.json"),
    )?;
    replace_relative_symlink(
        &prefix.join("install.json"),
        Path::new("current/install.json"),
    )?;
    Ok(())
}

fn require_workspace_guidance_ignored(workspace_root: &Path) -> Result<()> {
    let status = ProcessCommand::new("git")
        .arg("-C")
        .arg(workspace_root)
        .args(["check-ignore", "--quiet", "--", "AGENTS.local.md"])
        .status()
        .map_err(|error| {
            CliError::new(
                "LOCAL_GUIDANCE_IGNORE_CHECK_FAILED",
                format!(
                    "Could not verify local guidance ignore ownership for {}: {error}.",
                    workspace_root.display(),
                ),
            )
        })?;
    if status.success() {
        Ok(())
    } else {
        Err(CliError::new(
            "LOCAL_GUIDANCE_NOT_IGNORED",
            format!(
                "Workspace {} must source-own an ignore rule for /AGENTS.local.md before local guidance can be projected.",
                workspace_root.display(),
            ),
        ))
    }
}

#[cfg(unix)]
fn validate_workspace_guidance_target(workspace_root: &Path, prefix: &Path) -> Result<()> {
    let target = workspace_root.join("AGENTS.local.md");
    let expected = prefix.join("current/guidance/AGENTS.local.md");
    match fs::symlink_metadata(&target) {
        Ok(metadata) if metadata.file_type().is_symlink() => {
            let actual = fs::read_link(&target)?;
            if actual == expected {
                Ok(())
            } else {
                Err(CliError::new(
                    "LOCAL_GUIDANCE_TARGET_CONFLICT",
                    format!(
                        "Workspace guidance symlink {} targets {}, not the selected local authority {}.",
                        target.display(),
                        actual.display(),
                        expected.display()
                    ),
                ))
            }
        }
        Ok(_) => Err(CliError::new(
            "LOCAL_GUIDANCE_TARGET_CONFLICT",
            format!(
                "Workspace guidance target already contains unrelated content: {}",
                target.display()
            ),
        )),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error.into()),
    }
}

#[cfg(not(unix))]
fn validate_workspace_guidance_target(_workspace_root: &Path, _prefix: &Path) -> Result<()> {
    Err(CliError::new(
        "LOCAL_DEVELOPMENT_UNSUPPORTED",
        "Workspace guidance indirection is not implemented on this platform.",
    ))
}

#[cfg(unix)]
fn ensure_workspace_guidance_link(workspace_root: &Path, prefix: &Path) -> Result<bool> {
    use std::os::unix::fs::symlink;

    let target = workspace_root.join("AGENTS.local.md");
    let expected = prefix.join("current/guidance/AGENTS.local.md");
    validate_workspace_guidance_target(workspace_root, prefix)?;
    match fs::symlink_metadata(&target) {
        Ok(_) => Ok(false),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            symlink(&expected, &target)?;
            Ok(true)
        }
        Err(error) => Err(error.into()),
    }
}

#[cfg(not(unix))]
fn ensure_workspace_guidance_link(_workspace_root: &Path, _prefix: &Path) -> Result<bool> {
    Err(CliError::new(
        "LOCAL_DEVELOPMENT_UNSUPPORTED",
        "Workspace guidance indirection is not implemented on this platform.",
    ))
}

fn remove_owned_workspace_guidance_link(workspace_root: &Path, prefix: &Path) -> Result<bool> {
    let target = workspace_root.join("AGENTS.local.md");
    let expected = prefix.join("current/guidance/AGENTS.local.md");
    match fs::symlink_metadata(&target) {
        Ok(metadata) if metadata.file_type().is_symlink() => {
            if fs::read_link(&target)? == expected {
                fs::remove_file(target)?;
                Ok(true)
            } else {
                Ok(false)
            }
        }
        Ok(_) => Ok(false),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(false),
        Err(error) => Err(error.into()),
    }
}

fn local_entrypoint_script(prefix: &Path) -> String {
    let prefix = shell_single_quote(&prefix.display().to_string());
    format!(
        "#!/bin/sh\nset -eu\nprefix='{prefix}'\ntarget=$(readlink \"$prefix/current\")\ncase \"$target\" in\n  generations/*) generation=${{target#generations/}} ;;\n  *) echo 'kast: invalid local generation authority' >&2; exit 70 ;;\nesac\ncase \"$generation\" in\n  ''|*[!0-9a-f-]*|*-*-*) echo 'kast: invalid local generation identity' >&2; exit 70 ;;\nesac\ngeneration_root=\"$prefix/$target\"\nstate=\"$prefix/state/$generation\"\nexport KAST_LOCAL_DEVELOPMENT_RECEIPT=\"$generation_root/authority.json\"\nexport KAST_INSTALL_ROOT=\"$prefix\"\nexport KAST_CONFIG_HOME=\"$generation_root/config\"\nexport KAST_DATA_HOME=\"$state/data\"\nexport KAST_CACHE_HOME=\"$state/cache\"\nexec \"$generation_root/bin/kast\" \"$@\"\n"
    )
}

fn shell_single_quote(value: &str) -> String {
    value.replace('\'', "'\"'\"'")
}

fn shell_single_quoted_path(path: &Path) -> String {
    format!("'{}'", shell_single_quote(&path.display().to_string()))
}

fn render_local_skill(source_skill: &Path, entrypoint: &Path) -> Result<String> {
    let bytes = fs::read(source_skill).map_err(|error| {
        CliError::new(
            "LOCAL_SKILL_MISSING",
            format!(
                "Could not read source-owned Kast skill {}: {error}",
                source_skill.display(),
            ),
        )
    })?;
    let source = std::str::from_utf8(&bytes).map_err(|error| {
        CliError::new(
            "LOCAL_SKILL_INVALID",
            format!("Source-owned Kast skill is not UTF-8: {error}"),
        )
    })?;
    let source = source.replace(
        "- Add `--apply` to `kast repair` only after the repair plan or readiness output asks for install-state mutation.",
        "- Do not apply ordinary install repair through local-development authority; rerun the source checkout's `./gradlew refreshDevelopmentLocal` task instead.",
    );
    let entrypoint = shell_single_quoted_path(entrypoint);
    let rendered = source
        .replace("`kast", &format!("`{entrypoint}"))
        .replace("--backend <idea|headless>", "--backend=headless")
        .replace("--backend <name>", "--backend=headless");
    let authority_guidance = if rendered.contains("Do not apply ordinary install repair") {
        String::new()
    } else {
        "\n\n## Local-development authority\n\nDo not apply ordinary install repair through local-development authority; rerun the source checkout's `./gradlew refreshDevelopmentLocal` task instead."
            .to_string()
    };
    Ok(format!(
        "{rendered}{authority_guidance}\n\n## Local-development workspace lease\n\nAcquire the exact receipt-owned headless runtime with `{} agent lease acquire --workspace-root \"$PWD\" --backend=headless`. Pass its ID to `{} agent verify --workspace-root \"$PWD\" --backend=headless --lease-id <id>` and every later semantic command, then release that lease.\n",
        entrypoint,
        entrypoint,
    ))
}

fn render_local_guidance(skill: &Path, entrypoint: &Path, source: &SourceSnapshot) -> String {
    let entrypoint = shell_single_quoted_path(entrypoint);
    format!(
        "<kast files=\"*.kt, *.kts\" type=\"instructions\" replaceTools=\"grep,search,write\">\n## Kast local-development routing\nUse `{}` before Kotlin or Gradle semantic work.\nAcquire the receipt-owned `{} agent lease acquire --workspace-root \"$PWD\" --backend=headless` lease for this exact root.\nVerify with `{} agent verify --workspace-root \"$PWD\" --backend=headless --lease-id <id>`, pass the same root, backend, and lease ID to later typed commands such as `{} agent symbol`, `{} agent diagnostics`, and `{} agent rename`, then release the lease.\nPrepared source commit: {}\nPrepared source SHA-256: {}\n</kast>\n",
        skill.display(),
        entrypoint,
        entrypoint,
        entrypoint,
        entrypoint,
        entrypoint,
        source.git_commit.as_str(),
        source.source_tree_sha256.as_str(),
    )
}

fn validate_rendered_command_lockstep(rendered: &str, entrypoint: &Path) -> Result<()> {
    let entrypoint = shell_single_quoted_path(entrypoint);
    for line in rendered.lines() {
        for (index, code) in line.split('`').enumerate() {
            if index % 2 == 0 {
                continue;
            }
            let Some(arguments) = code.strip_prefix(&entrypoint) else {
                continue;
            };
            if explicitly_denied_command_reference(line, arguments) {
                continue;
            }
            validate_rendered_command_path(code, arguments)?;
        }
    }
    Ok(())
}

fn explicitly_denied_command_reference(line: &str, arguments: &str) -> bool {
    line.contains("Do not teach")
        && matches!(
            arguments.trim(),
            "agent tools" | "agent call" | "agent workflow" | "rpc"
        )
}

fn validate_rendered_command_path(rendered_command: &str, arguments: &str) -> Result<()> {
    let arguments = normalize_rendered_guidance_arguments(rendered_command, arguments)?;
    if is_bare_command_path(&arguments) {
        return Ok(());
    }

    let mut argv = Vec::with_capacity(arguments.len() + 1);
    argv.push("kast".to_string());
    argv.extend(arguments);
    match <crate::cli::Cli as clap::CommandFactory>::command().try_get_matches_from(argv) {
        Ok(_) => Ok(()),
        Err(error)
            if matches!(
                error.kind(),
                clap::error::ErrorKind::DisplayHelp | clap::error::ErrorKind::DisplayVersion
            ) =>
        {
            Ok(())
        }
        Err(error) => Err(CliError::new(
            "LOCAL_COMMAND_LOCKSTEP_INVALID",
            format!(
                "Rendered local guidance is not a valid staged CLI invocation: `{rendered_command}`: {error}",
            ),
        )),
    }
}

fn normalize_rendered_guidance_arguments(
    rendered_command: &str,
    arguments: &str,
) -> Result<Vec<String>> {
    arguments
        .split_whitespace()
        .map(|token| {
            let token = match token {
                "\"$PWD\"" => "/tmp/kast-workspace",
                "<name>" => "renamedSymbol",
                "<fq-name>" => "io.example.Symbol.member",
                "<path>" | "<snippet.kt>" => "/tmp/KastSnippet.kt",
                "<stable-key>" => "kast-guidance-key",
                "<id>" => "kast-guidance-lease-id",
                token => token,
            };
            if token.contains(['\'', '"', '$', '<', '>']) {
                return Err(CliError::new(
                    "LOCAL_COMMAND_LOCKSTEP_INVALID",
                    format!(
                        "Rendered local guidance contains an unsupported argument template in `{rendered_command}`.",
                    ),
                ));
            }
            Ok(token.to_string())
        })
        .collect()
}

fn is_bare_command_path(arguments: &[String]) -> bool {
    let mut command = <crate::cli::Cli as clap::CommandFactory>::command();
    for token in arguments {
        let Some(subcommand) = command.find_subcommand(token) else {
            return false;
        };
        command = subcommand.clone();
    }
    true
}

fn local_install_manifest(
    prefix: &Path,
    generation_id: &LocalGenerationId,
    source: &SourceSnapshot,
    workspace_root: &Path,
) -> crate::manifest::KastInstallManifest {
    let generation = prefix.join(generation_target(&generation_id));
    let state = prefix.join("state").join(generation_id.as_str());
    let now = crate::manifest::current_timestamp();
    crate::manifest::KastInstallManifest {
        tool: "kast".to_string(),
        install_id: generation_id.as_str().to_string(),
        profile: "local-development".to_string(),
        active_version: crate::cli::version().to_string(),
        previous_version: None,
        created_at: now.clone(),
        updated_at: now.clone(),
        roots: crate::manifest::ManifestRoots {
            install: prefix.display().to_string(),
            bin: prefix.join("bin").display().to_string(),
            config: generation.join("config").display().to_string(),
            data: state.join("data").display().to_string(),
            cache: state.join("cache").display().to_string(),
            runtime: state.join("runtime").display().to_string(),
            logs: state.join("logs").display().to_string(),
            locks: state.join("locks").display().to_string(),
        },
        entrypoints: crate::manifest::ManifestEntrypoints {
            shim: prefix.join("bin/kast").display().to_string(),
            active_binary: generation.join("bin/kast").display().to_string(),
        },
        schemas: crate::manifest::ManifestSchemas::default(),
        version: crate::cli::version().to_string(),
        backend_version: crate::cli::version().to_string(),
        installed_at: format!("local-development:{}", source.source_tree_sha256.as_str()),
        platform: format!("local-{}-{}", std::env::consts::OS, std::env::consts::ARCH),
        components: vec![
            "cli".to_string(),
            "headless-backend".to_string(),
            "skill".to_string(),
            "agent-guidance".to_string(),
            "config".to_string(),
        ],
        backends: vec![crate::manifest::BackendComponentState {
            name: "headless".to_string(),
            version: crate::cli::version().to_string(),
            install_dir: generation
                .join("lib/backends/headless/current")
                .display()
                .to_string(),
            runtime_libs_dir: generation
                .join("lib/backends/headless/current/runtime-libs")
                .display()
                .to_string(),
            idea_home: Some(
                generation
                    .join("lib/backends/headless/current/idea-home")
                    .display()
                    .to_string(),
            ),
        }],
        managed_paths: vec![
            "generations".to_string(),
            format!("state/{}", generation_id.as_str()),
            "bin/kast".to_string(),
        ],
        owned_paths: vec![prefix.display().to_string()],
        shell_rc_patches: vec![],
        repos: vec![crate::manifest::ManagedRepo {
            path: workspace_root.display().to_string(),
            copilot_package_version: String::new(),
            resources: vec![],
        }],
        schema_version: crate::SCHEMA_VERSION,
    }
}

fn validate_backend_distribution(backend: &Path) -> Result<()> {
    for required in [
        "runtime-libs/classpath.txt",
        "idea-home/lib/nio-fs.jar",
        "idea-home/modules/module-descriptors.dat",
        "idea-home/plugins/kast-headless",
    ] {
        let path = backend.join(required);
        if !path.exists() {
            return Err(CliError::new(
                "LOCAL_BACKEND_INCOMPLETE",
                format!(
                    "Headless development backend is missing {}.",
                    path.display()
                ),
            ));
        }
    }
    let runtime_libs = backend.join("runtime-libs");
    let classpath_file = runtime_libs.join("classpath.txt");
    let classpath = fs::read_to_string(&classpath_file).map_err(|error| {
        CliError::new(
            "LOCAL_BACKEND_INCOMPLETE",
            format!(
                "Could not read headless backend classpath {}: {error}.",
                classpath_file.display(),
            ),
        )
    })?;
    let mut entry_count = 0_usize;
    for raw_entry in classpath.lines().filter(|line| !line.is_empty()) {
        let entry = Path::new(raw_entry);
        if entry
            .components()
            .any(|component| !matches!(component, Component::Normal(_)))
        {
            return Err(CliError::new(
                "LOCAL_BACKEND_CLASSPATH_INVALID",
                format!(
                    "Headless backend classpath entry must stay inside runtime-libs: {raw_entry:?}.",
                ),
            ));
        }
        let target = runtime_libs.join(entry);
        let metadata = fs::symlink_metadata(&target).map_err(|error| {
            CliError::new(
                "LOCAL_BACKEND_CLASSPATH_INVALID",
                format!(
                    "Headless backend classpath entry {} is unavailable: {error}.",
                    target.display(),
                ),
            )
        })?;
        if !metadata.is_file() || metadata.file_type().is_symlink() {
            return Err(CliError::new(
                "LOCAL_BACKEND_CLASSPATH_INVALID",
                format!(
                    "Headless backend classpath entry is not an owned regular file: {}.",
                    target.display(),
                ),
            ));
        }
        entry_count += 1;
    }
    if entry_count == 0 {
        return Err(CliError::new(
            "LOCAL_BACKEND_CLASSPATH_INVALID",
            format!(
                "Headless backend classpath is empty: {}.",
                classpath_file.display()
            ),
        ));
    }
    Ok(())
}

pub(crate) fn active_local_development_receipt() -> Result<Option<LocalDevelopmentReceipt>> {
    let Some(requested_path) = configured_local_development_receipt_path()? else {
        return Ok(None);
    };
    let receipt = read_local_development_receipt(&requested_path)?;
    validate_active_receipt(&requested_path, &receipt)?;
    Ok(Some(receipt))
}

fn configured_local_development_receipt_path() -> Result<Option<PathBuf>> {
    let Some(raw_path) = std::env::var_os("KAST_LOCAL_DEVELOPMENT_RECEIPT") else {
        return Ok(None);
    };
    if raw_path.is_empty() {
        return Err(CliError::new(
            "LOCAL_AUTHORITY_RECEIPT_INVALID",
            "KAST_LOCAL_DEVELOPMENT_RECEIPT must name an absolute receipt path.",
        ));
    }
    let requested_path = PathBuf::from(raw_path);
    if !requested_path.is_absolute() {
        return Err(CliError::new(
            "LOCAL_AUTHORITY_RECEIPT_INVALID",
            format!(
                "KAST_LOCAL_DEVELOPMENT_RECEIPT must be absolute: {}",
                requested_path.display()
            ),
        ));
    }
    Ok(Some(requested_path))
}

pub(crate) fn verified_active_local_development_receipt() -> Result<Option<LocalDevelopmentReceipt>>
{
    let Some(receipt) = active_local_development_receipt()? else {
        return Ok(None);
    };
    validate_receipt_components(&receipt)?;
    Ok(Some(receipt))
}

pub(crate) fn with_active_local_runtime_start_lock<T>(
    action: impl FnOnce(bool) -> Result<T>,
) -> Result<T> {
    let Some(receipt_path) = configured_local_development_receipt_path()? else {
        return action(false);
    };
    let receipt = read_local_development_receipt(&receipt_path)?;
    let prefix = receipt.prefix.clone();
    with_local_runtime_start_lock_after_validation(
        &prefix,
        || {
            verified_active_local_development_receipt()?.ok_or_else(|| {
                CliError::new(
                    "LOCAL_AUTHORITY_INACTIVE",
                    "Local-development authority disappeared while waiting for its runtime-start lock.",
                )
            })?;
            Ok(())
        },
        || action(true),
    )
}

fn with_local_runtime_start_lock_after_validation<T>(
    prefix: &Path,
    validate: impl FnOnce() -> Result<()>,
    action: impl FnOnce() -> Result<T>,
) -> Result<T> {
    with_local_authority_lock(prefix, || {
        validate()?;
        action()
    })
}

pub(crate) fn validate_active_local_backend_runtime(runtime_libs_dir: &Path) -> Result<()> {
    let Some(receipt) = verified_active_local_development_receipt()? else {
        return Ok(());
    };
    let expected = fs::canonicalize(
        receipt
            .components
            .backend
            .effective_target
            .join("runtime-libs"),
    )?;
    let selected = fs::canonicalize(runtime_libs_dir)?;
    if selected != expected {
        return Err(CliError::new(
            "LOCAL_BACKEND_RUNTIME_AUTHORITY_MISMATCH",
            format!(
                "Local runtime libraries {} are not owned by the active backend {}.",
                selected.display(),
                expected.display(),
            ),
        ));
    }
    Ok(())
}

pub(crate) fn read_local_development_receipt(path: &Path) -> Result<LocalDevelopmentReceipt> {
    let receipt: LocalDevelopmentReceipt =
        serde_json::from_slice(&fs::read(path)?).map_err(|error| {
            CliError::new(
                "LOCAL_AUTHORITY_RECEIPT_INVALID",
                format!(
                    "Invalid local-development receipt at {}: {error}",
                    path.display()
                ),
            )
        })?;
    if receipt.schema_version != LOCAL_DEVELOPMENT_RECEIPT_SCHEMA_VERSION {
        return Err(CliError::new(
            "LOCAL_AUTHORITY_RECEIPT_UNSUPPORTED",
            format!(
                "Local-development receipt schema {} is unsupported; expected {}.",
                receipt.schema_version, LOCAL_DEVELOPMENT_RECEIPT_SCHEMA_VERSION
            ),
        ));
    }
    Ok(receipt)
}

fn validate_active_receipt(path: &Path, receipt: &LocalDevelopmentReceipt) -> Result<()> {
    let prefix = canonical_directory(&receipt.prefix, "local-development receipt prefix")?;
    if prefix != receipt.prefix {
        return Err(CliError::new(
            "LOCAL_AUTHORITY_RECEIPT_INVALID",
            "Local-development receipt prefix must be canonical.",
        ));
    }
    let generation = prefix
        .join("generations")
        .join(receipt.generation_id.as_str());
    validate_receipt_identity(receipt, &prefix, &generation, &receipt.workspace_root)?;
    validate_stable_authority(path, receipt, &generation)?;
    validate_receipt_fast_components(receipt)?;
    let running_binary = fs::canonicalize(std::env::current_exe()?)?;
    let receipt_binary = fs::canonicalize(&receipt.components.cli.effective_target)?;
    if running_binary != receipt_binary {
        return Err(CliError::new(
            "LOCAL_AUTHORITY_BINARY_MISMATCH",
            format!(
                "Running CLI {} is not the receipt-owned local binary {}.",
                running_binary.display(),
                receipt_binary.display()
            ),
        ));
    }
    Ok(())
}

fn validate_stable_authority(
    path: &Path,
    receipt: &LocalDevelopmentReceipt,
    expected_generation: &Path,
) -> Result<()> {
    let prefix = &receipt.prefix;
    let active_generation = fs::canonicalize(prefix.join("current")).map_err(|error| {
        CliError::new(
            "LOCAL_AUTHORITY_INACTIVE",
            format!("Local-development current generation cannot be resolved: {error}"),
        )
    })?;
    let expected_generation = canonical_directory(expected_generation, "receipt generation")?;
    if active_generation != expected_generation {
        return Err(CliError::new(
            "LOCAL_AUTHORITY_INACTIVE",
            format!(
                "Receipt generation {} is not the active local generation {}.",
                expected_generation.display(),
                active_generation.display()
            ),
        ));
    }
    let canonical_receipt = fs::canonicalize(path)?;
    let expected_receipt = fs::canonicalize(expected_generation.join("authority.json"))?;
    if canonical_receipt != expected_receipt {
        return Err(CliError::new(
            "LOCAL_AUTHORITY_RECEIPT_INVALID",
            "The selected local-development receipt does not belong to the active generation.",
        ));
    }
    let stable_receipt = fs::canonicalize(prefix.join("authority.json"))?;
    if stable_receipt != expected_receipt {
        return Err(CliError::new(
            "LOCAL_AUTHORITY_RECEIPT_INVALID",
            "The stable local-development receipt link does not select the active generation.",
        ));
    }
    let canonical_manifest = fs::canonicalize(&receipt.install_manifest)?;
    let expected_manifest = fs::canonicalize(expected_generation.join("install.json"))?;
    if canonical_manifest != expected_manifest {
        return Err(CliError::new(
            "LOCAL_AUTHORITY_RECEIPT_INVALID",
            "The selected local-development install manifest does not belong to the active generation.",
        ));
    }
    let stable_manifest = fs::canonicalize(prefix.join("install.json"))?;
    if stable_manifest != expected_manifest {
        return Err(CliError::new(
            "LOCAL_AUTHORITY_RECEIPT_INVALID",
            "The stable local-development manifest link does not select the active generation.",
        ));
    }
    let entrypoint_target = read_relative_symlink(&receipt.entrypoint.effective_target)?;
    if entrypoint_target.as_deref() != Some(Path::new("../current/entrypoint/kast"))
        || fs::canonicalize(&receipt.entrypoint.effective_target)?
            != fs::canonicalize(&receipt.entrypoint.physical_target)?
    {
        return Err(CliError::new(
            "LOCAL_COMPONENT_TARGET_MISMATCH",
            "The stable local-development entrypoint does not resolve through the active generation.",
        ));
    }
    let entrypoint_sha = Sha256Digest::try_from(crate::manifest::sha256_file(
        &receipt.entrypoint.effective_target,
    )?)?;
    if entrypoint_sha != receipt.entrypoint.sha256 {
        return Err(CliError::new(
            "LOCAL_COMPONENT_CHECKSUM_MISMATCH",
            format!(
                "Local-development entrypoint checksum does not match at {}.",
                receipt.entrypoint.effective_target.display()
            ),
        ));
    }
    Ok(())
}

fn validate_receipt_fast_components(receipt: &LocalDevelopmentReceipt) -> Result<()> {
    validate_receipt_effective_topology(receipt)?;
    validate_physical_entrypoint(receipt)?;
    for component in [
        &receipt.components.cli,
        &receipt.components.skill,
        &receipt.components.guidance,
        &receipt.components.config,
        &receipt.components.manifest,
    ] {
        validate_physical_component(component)?;
    }
    Ok(())
}

fn validate_receipt_components(receipt: &LocalDevelopmentReceipt) -> Result<()> {
    validate_receipt_physical_components(receipt)?;
    validate_receipt_effective_topology(receipt)
}

fn validate_receipt_effective_topology(receipt: &LocalDevelopmentReceipt) -> Result<()> {
    for component in [
        &receipt.components.cli,
        &receipt.components.backend,
        &receipt.components.skill,
        &receipt.components.guidance,
        &receipt.components.config,
        &receipt.components.manifest,
    ] {
        let physical = fs::canonicalize(&component.physical_target).map_err(|error| {
            CliError::new(
                "LOCAL_COMPONENT_MISSING",
                format!(
                    "Could not resolve physical local component {}: {error}",
                    component.physical_target.display(),
                ),
            )
        })?;
        let effective = fs::canonicalize(&component.effective_target).map_err(|error| {
            CliError::new(
                "LOCAL_COMPONENT_MISSING",
                format!(
                    "Could not resolve effective local component {}: {error}",
                    component.effective_target.display(),
                ),
            )
        })?;
        if effective != physical {
            return Err(CliError::new(
                "LOCAL_COMPONENT_TARGET_MISMATCH",
                format!(
                    "Effective local component {} resolves to {}, not its receipt-owned physical target {}.",
                    component.effective_target.display(),
                    effective.display(),
                    physical.display(),
                ),
            ));
        }
    }
    Ok(())
}

fn validate_receipt_physical_components(receipt: &LocalDevelopmentReceipt) -> Result<()> {
    validate_physical_entrypoint(receipt)?;
    for component in [
        &receipt.components.cli,
        &receipt.components.backend,
        &receipt.components.skill,
        &receipt.components.guidance,
        &receipt.components.config,
        &receipt.components.manifest,
    ] {
        validate_physical_component(component)?;
    }
    Ok(())
}

fn validate_physical_entrypoint(receipt: &LocalDevelopmentReceipt) -> Result<()> {
    let actual = Sha256Digest::try_from(crate::manifest::sha256_file(
        &receipt.entrypoint.physical_target,
    )?)?;
    if actual != receipt.entrypoint.sha256 {
        return Err(CliError::new(
            "LOCAL_COMPONENT_CHECKSUM_MISMATCH",
            format!(
                "Physical local-development entrypoint checksum does not match at {}.",
                receipt.entrypoint.physical_target.display(),
            ),
        ));
    }
    Ok(())
}

fn validate_physical_component(component: &LocalDevelopmentComponent) -> Result<()> {
    let actual = tree_sha256(&component.physical_target)?;
    if actual != component.sha256 {
        return Err(CliError::new(
            "LOCAL_COMPONENT_CHECKSUM_MISMATCH",
            format!(
                "Local component checksum does not match receipt at {}.",
                component.physical_target.display()
            ),
        ));
    }
    Ok(())
}

fn absolute_path(path: PathBuf) -> Result<PathBuf> {
    if path.is_absolute() {
        Ok(path.components().collect())
    } else {
        Ok(std::env::current_dir()?.join(path).components().collect())
    }
}

fn canonical_file(path: &Path, label: &str) -> Result<PathBuf> {
    let path = fs::canonicalize(path).map_err(|error| {
        CliError::new(
            "LOCAL_COMPONENT_MISSING",
            format!("Could not resolve {label} {}: {error}", path.display()),
        )
    })?;
    if path.is_file() {
        Ok(path)
    } else {
        Err(CliError::new(
            "LOCAL_COMPONENT_MISSING",
            format!("{label} is not a file: {}", path.display()),
        ))
    }
}

fn source_snapshot_mismatch(expected: &SourceSnapshot, actual: &SourceSnapshot) -> CliError {
    let mut error = CliError::new(
        "LOCAL_SOURCE_SNAPSHOT_CHANGED",
        "The checkout changed after the local-development source snapshot was captured.",
    );
    error.details.insert(
        "expected".to_string(),
        expected.source_tree_sha256.as_str().to_string(),
    );
    error.details.insert(
        "actual".to_string(),
        actual.source_tree_sha256.as_str().to_string(),
    );
    error
}

fn with_local_authority_lock<T>(prefix: &Path, action: impl FnOnce() -> Result<T>) -> Result<T> {
    use std::fs::OpenOptions;

    let parent = prefix.parent().ok_or_else(|| {
        CliError::new(
            "LOCAL_PREFIX_INVALID",
            format!("Local prefix has no parent: {}", prefix.display()),
        )
    })?;
    fs::create_dir_all(parent)?;
    let parent = fs::canonicalize(parent)?;
    let name = prefix
        .file_name()
        .and_then(|name| name.to_str())
        .ok_or_else(|| {
            CliError::new(
                "LOCAL_PREFIX_INVALID",
                format!("Local prefix has no UTF-8 name: {}", prefix.display()),
            )
        })?;
    let lock_path = parent.join(format!(".{name}.refresh.lock"));
    let file = OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(lock_path)?;
    lock_local_file(&file)?;
    let result = action();
    unlock_local_file(&file)?;
    result
}

fn reject_symlink_selected_prefix(prefix: &Path) -> Result<()> {
    match fs::symlink_metadata(prefix) {
        Ok(metadata) if metadata.file_type().is_symlink() => Err(CliError::new(
            "LOCAL_PREFIX_UNSAFE",
            format!(
                "Refusing a local-development prefix selected through a symlink: {}.",
                prefix.display(),
            ),
        )),
        Ok(_) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error.into()),
    }
}

#[cfg(unix)]
fn lock_local_file(file: &fs::File) -> Result<()> {
    use std::os::fd::AsRawFd;

    if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX) } == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error().into())
    }
}

#[cfg(unix)]
fn unlock_local_file(file: &fs::File) -> Result<()> {
    use std::os::fd::AsRawFd;

    if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_UN) } == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error().into())
    }
}

#[cfg(not(unix))]
fn lock_local_file(_file: &fs::File) -> Result<()> {
    Ok(())
}

#[cfg(not(unix))]
fn unlock_local_file(_file: &fs::File) -> Result<()> {
    Ok(())
}
