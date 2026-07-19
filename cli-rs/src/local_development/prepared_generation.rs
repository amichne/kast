const PREPARED_GENERATION_LEDGER_PATH: &str = "generation.json";
const PREPARED_SOURCE_SNAPSHOT_PATH: &str = "source-snapshot.json";
const PREPARED_CLI_PATH: &str = "bin/kast";
const PREPARED_CLI_PROVENANCE_PATH: &str = "provenance/cli.json";
const PREPARED_BACKEND_PATH: &str = "backend-headless";
const PREPARED_BACKEND_PROVENANCE_PATH: &str = "provenance/backend.json";
const PREPARED_BACKEND_COMPONENT_MANIFEST_PATH: &str = "provenance/backend-components.json";
const PREPARED_SKILL_PATH: &str = "inputs/kast-skill/SKILL.md";
const PREPARED_GUIDANCE_INPUTS_PATH: &str = "inputs/guidance.json";
const PREPARED_CONFIG_PATH: &str = "inputs/config.toml";
const LOCAL_DEVELOPMENT_CONFIG: &[u8] =
    include_bytes!("../../resources/local-development/config.toml");

pub fn prepare_local_development_generation(
    request: LocalDevelopmentPrepareRequest,
) -> Result<LocalDevelopmentPrepareResult> {
    let expected = SourceSnapshot::read_strict(&request.expected_source_snapshot)?;
    let source = SourceSnapshot::capture(&request.source_root)?;
    if source != expected {
        return Err(source_snapshot_mismatch(&expected, &source));
    }
    let cli_binary = canonical_file(&request.cli_binary, "development CLI binary")?;
    require_exact_controller(&cli_binary, "LOCAL_PREPARE_CONTROLLER_MISMATCH")?;
    let backend_directory =
        canonical_directory(&request.backend_directory, "headless backend distribution")?;
    validate_backend_distribution(&backend_directory)?;
    let expected_skill_source = source
        .canonical_root
        .join("cli-rs/resources/kast-skill/SKILL.md");
    let skill_source = canonical_file(&request.skill_source, "local skill source")?;
    if skill_source != expected_skill_source {
        return Err(CliError::new(
            "LOCAL_PREPARED_SKILL_SOURCE_MISMATCH",
            format!(
                "Prepared local skill must come from {}, not {}.",
                expected_skill_source.display(),
                skill_source.display(),
            ),
        ));
    }
    let config_source = canonical_file(
        &source
            .canonical_root
            .join("cli-rs/resources/local-development/config.toml"),
        "local configuration source",
    )?;
    if fs::read(&config_source)? != LOCAL_DEVELOPMENT_CONFIG {
        return Err(CliError::new(
            "LOCAL_PREPARED_CONFIG_SOURCE_MISMATCH",
            format!(
                "Prepared local configuration must match the source-bound CLI resource at {}.",
                config_source.display(),
            ),
        ));
    }
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
                cli_provenance.implementation_version,
                backend_provenance.implementation_version,
            ),
        ));
    }

    let output = prepared_output_path(&request.output_directory)?;
    if output.exists() {
        let verified = verify_local_development_generation(&request.source_root, &output)?;
        if verified.ledger.source != source
            || verified.ledger.components.cli.sha256 != cli_provenance.sha256
            || verified.ledger.components.backend.sha256 != backend_provenance.sha256
            || verified.ledger.implementation_version != cli_provenance.implementation_version
            || verified.ledger.components.skill.sha256 != tree_sha256(&skill_source)?
        {
            return Err(CliError::new(
                "LOCAL_PREPARED_GENERATION_CONFLICT",
                format!(
                    "Prepared generation {} does not match the requested source-bound inputs.",
                    output.display(),
                ),
            ));
        }
        return Ok(LocalDevelopmentPrepareResult {
            ledger: verified.ledger,
            directory: verified.directory,
            skipped: true,
            schema_version: crate::SCHEMA_VERSION,
        });
    }

    let parent = output.parent().ok_or_else(|| {
        CliError::new(
            "LOCAL_PREPARED_GENERATION_PATH_INVALID",
            format!("Prepared generation has no parent: {}.", output.display()),
        )
    })?;
    fs::create_dir_all(parent)?;
    let parent = canonical_directory(parent, "prepared-generation parent")?;
    let name = output.file_name().ok_or_else(|| {
        CliError::new(
            "LOCAL_PREPARED_GENERATION_PATH_INVALID",
            format!("Prepared generation has no directory name: {}.", output.display()),
        )
    })?;
    let output = parent.join(name);
    let staged = parent.join(format!(
        ".{}-staging-{}",
        name.to_string_lossy(),
        std::process::id(),
    ));
    if staged.exists() {
        fs::remove_dir_all(&staged)?;
    }
    fs::create_dir_all(&staged)?;
    let result = (|| -> Result<LocalPreparedGenerationLedger> {
        source.write_atomic(&staged.join(PREPARED_SOURCE_SNAPSHOT_PATH))?;
        copy_regular_file(&cli_binary, &staged.join(PREPARED_CLI_PATH))?;
        crate::manifest::make_executable(&staged.join(PREPARED_CLI_PATH))?;
        copy_directory_tree(&backend_directory, &staged.join(PREPARED_BACKEND_PATH))?;
        write_bytes(
            &staged.join(PREPARED_BACKEND_COMPONENT_MANIFEST_PATH),
            &backend_embedded_component_manifest_bytes(&backend_directory)?,
        )?;
        copy_regular_file(&skill_source, &staged.join(PREPARED_SKILL_PATH))?;
        let guidance_inputs = LocalGuidanceInputs {
            schema_version: LOCAL_GUIDANCE_INPUTS_SCHEMA_VERSION,
            source: source.clone(),
            skill_relative_path: PathBuf::from(PREPARED_SKILL_PATH),
        };
        write_json_atomic(
            &staged.join(PREPARED_GUIDANCE_INPUTS_PATH),
            &guidance_inputs,
        )?;
        copy_regular_file(&config_source, &staged.join(PREPARED_CONFIG_PATH))?;

        let prepared_cli_provenance = LocalPreparedArtifactProvenance {
            schema_version: LOCAL_ARTIFACT_PROVENANCE_SCHEMA_VERSION,
            kind: LocalArtifactKind::Cli,
            source: source.clone(),
            sha256: tree_sha256(&staged.join(PREPARED_CLI_PATH))?,
            implementation_version: cli_provenance.implementation_version.clone(),
        };
        let prepared_backend_provenance = LocalPreparedArtifactProvenance {
            schema_version: LOCAL_ARTIFACT_PROVENANCE_SCHEMA_VERSION,
            kind: LocalArtifactKind::HeadlessBackend,
            source: source.clone(),
            sha256: tree_sha256(&staged.join(PREPARED_BACKEND_PATH))?,
            implementation_version: backend_provenance.implementation_version.clone(),
        };
        write_json_atomic(
            &staged.join(PREPARED_CLI_PROVENANCE_PATH),
            &prepared_cli_provenance,
        )?;
        write_json_atomic(
            &staged.join(PREPARED_BACKEND_PROVENANCE_PATH),
            &prepared_backend_provenance,
        )?;

        let components = LocalPreparedGenerationComponents {
            source_snapshot: prepared_component(&staged, PREPARED_SOURCE_SNAPSHOT_PATH)?,
            cli: prepared_component(&staged, PREPARED_CLI_PATH)?,
            cli_provenance: prepared_component(&staged, PREPARED_CLI_PROVENANCE_PATH)?,
            backend: prepared_component(&staged, PREPARED_BACKEND_PATH)?,
            backend_provenance: prepared_component(
                &staged,
                PREPARED_BACKEND_PROVENANCE_PATH,
            )?,
            backend_component_manifest: prepared_component(
                &staged,
                PREPARED_BACKEND_COMPONENT_MANIFEST_PATH,
            )?,
            skill: prepared_component(&staged, PREPARED_SKILL_PATH)?,
            guidance_inputs: prepared_component(&staged, PREPARED_GUIDANCE_INPUTS_PATH)?,
            config: prepared_component(&staged, PREPARED_CONFIG_PATH)?,
        };
        let generation_id = LocalGenerationId::from_verified_artifacts(
            &source,
            &components.cli.sha256,
            &cli_provenance.implementation_version,
            &components.backend.sha256,
            &backend_provenance.implementation_version,
        );
        let ledger = LocalPreparedGenerationLedger {
            schema_version: LOCAL_PREPARED_GENERATION_SCHEMA_VERSION,
            generation_id,
            source: source.clone(),
            implementation_version: cli_provenance.implementation_version,
            components,
        };
        write_json_atomic(&staged.join(PREPARED_GENERATION_LEDGER_PATH), &ledger)?;
        fs::rename(&staged, &output)?;
        Ok(ledger)
    })();
    if result.is_err() {
        let _ = fs::remove_dir_all(&staged);
    }
    let ledger = result?;
    let verified = verify_local_development_generation(&request.source_root, &output)?;
    if verified.ledger != ledger {
        return Err(CliError::new(
            "LOCAL_PREPARED_GENERATION_INVALID",
            "Prepared generation changed between publication and verification.",
        ));
    }
    Ok(LocalDevelopmentPrepareResult {
        ledger,
        directory: verified.directory,
        skipped: false,
        schema_version: crate::SCHEMA_VERSION,
    })
}

pub fn activate_local_development_generation(
    request: LocalDevelopmentActivateRequest,
) -> Result<LocalDevelopmentRefreshResult> {
    let verified = verify_local_development_generation(
        &request.source_root,
        &request.prepared_generation,
    )?;
    let directory = verified.directory;
    let cli_binary = directory.join(PREPARED_CLI_PATH);
    require_exact_controller(&cli_binary, "LOCAL_ACTIVATE_CONTROLLER_MISMATCH")?;
    let backend_directory = canonical_directory(
        &directory.join(PREPARED_BACKEND_PATH),
        "prepared headless backend",
    )?;
    let cli_provenance = read_prepared_artifact_provenance(
        &directory.join(PREPARED_CLI_PROVENANCE_PATH),
    )?;
    let backend_provenance = read_prepared_artifact_provenance(
        &directory.join(PREPARED_BACKEND_PROVENANCE_PATH),
    )?;
    let artifacts = LocalDevelopmentArtifactSet {
        cli: cli_provenance.into_local(cli_binary.clone()),
        backend: backend_provenance.into_local(backend_directory.clone()),
    };
    activate_local_development_artifact_set(
        LocalDevelopmentArtifactActivationRequest {
            source_root: &request.source_root,
            workspace_root: canonical_directory(&request.workspace_root, "exact workspace root")?,
            prefix: request.prefix,
            source: verified.ledger.source.clone(),
            expected: verified.ledger.source,
            cli_binary,
            backend_directory,
            skill_source: directory.join(PREPARED_SKILL_PATH),
            config_source: directory.join(PREPARED_CONFIG_PATH),
            artifacts,
        },
        |_| Ok(()),
    )
}

pub fn verify_local_development_generation(
    source_root: &Path,
    prepared_generation: &Path,
) -> Result<LocalDevelopmentPreparedVerificationResult> {
    let directory = canonical_directory(prepared_generation, "prepared local generation")?;
    let ledger_path = directory.join(PREPARED_GENERATION_LEDGER_PATH);
    let ledger: LocalPreparedGenerationLedger = serde_json::from_slice(&fs::read(&ledger_path)?)
        .map_err(|error| {
            CliError::new(
                "LOCAL_PREPARED_GENERATION_INVALID",
                format!("Invalid prepared generation ledger {}: {error}.", ledger_path.display()),
            )
        })?;
    if ledger.schema_version != LOCAL_PREPARED_GENERATION_SCHEMA_VERSION {
        return Err(CliError::new(
            "LOCAL_PREPARED_GENERATION_UNSUPPORTED",
            format!(
                "Prepared generation schema {} is unsupported; expected {}.",
                ledger.schema_version, LOCAL_PREPARED_GENERATION_SCHEMA_VERSION,
            ),
        ));
    }
    let current_source = SourceSnapshot::capture(source_root)?;
    if current_source != ledger.source {
        return Err(source_snapshot_mismatch(&ledger.source, &current_source));
    }
    if ledger.generation_id
        != LocalGenerationId::from_verified_artifacts(
            &ledger.source,
            &ledger.components.cli.sha256,
            &ledger.implementation_version,
            &ledger.components.backend.sha256,
            &ledger.implementation_version,
        )
    {
        return Err(CliError::new(
            "LOCAL_PREPARED_GENERATION_INVALID",
            "Prepared generation identity does not match its source-bound artifact set.",
        ));
    }
    for (component, expected_path) in [
        (&ledger.components.source_snapshot, PREPARED_SOURCE_SNAPSHOT_PATH),
        (&ledger.components.cli, PREPARED_CLI_PATH),
        (&ledger.components.cli_provenance, PREPARED_CLI_PROVENANCE_PATH),
        (&ledger.components.backend, PREPARED_BACKEND_PATH),
        (
            &ledger.components.backend_provenance,
            PREPARED_BACKEND_PROVENANCE_PATH,
        ),
        (
            &ledger.components.backend_component_manifest,
            PREPARED_BACKEND_COMPONENT_MANIFEST_PATH,
        ),
        (&ledger.components.skill, PREPARED_SKILL_PATH),
        (
            &ledger.components.guidance_inputs,
            PREPARED_GUIDANCE_INPUTS_PATH,
        ),
        (&ledger.components.config, PREPARED_CONFIG_PATH),
    ] {
        validate_prepared_component(&directory, component, expected_path)?;
    }
    let stored_source = SourceSnapshot::read_strict(&directory.join(PREPARED_SOURCE_SNAPSHOT_PATH))?;
    if stored_source != ledger.source {
        return Err(CliError::new(
            "LOCAL_PREPARED_GENERATION_INVALID",
            "Prepared source snapshot does not match its ledger.",
        ));
    }
    let guidance: LocalGuidanceInputs = serde_json::from_slice(&fs::read(
        directory.join(PREPARED_GUIDANCE_INPUTS_PATH),
    )?)
    .map_err(|error| {
        CliError::new(
            "LOCAL_PREPARED_GENERATION_INVALID",
            format!("Invalid prepared guidance inputs: {error}."),
        )
    })?;
    if guidance.schema_version != LOCAL_GUIDANCE_INPUTS_SCHEMA_VERSION
        || guidance.source != ledger.source
        || guidance.skill_relative_path != Path::new(PREPARED_SKILL_PATH)
    {
        return Err(CliError::new(
            "LOCAL_PREPARED_GENERATION_INVALID",
            "Prepared guidance inputs do not match the generation ledger.",
        ));
    }
    if fs::read(directory.join(PREPARED_CONFIG_PATH))? != LOCAL_DEVELOPMENT_CONFIG {
        return Err(CliError::new(
            "LOCAL_PREPARED_GENERATION_INVALID",
            "Prepared local configuration does not match the typed headless authority contract.",
        ));
    }
    let _cli = canonical_file(&directory.join(PREPARED_CLI_PATH), "prepared CLI")?;
    if cfg!(not(test)) {
        validate_cli_producer_source_identity(
            option_env!("KAST_LOCAL_SOURCE_SHA256"),
            &ledger.source.source_tree_sha256,
        )?;
    }
    let backend = canonical_directory(
        &directory.join(PREPARED_BACKEND_PATH),
        "prepared headless backend",
    )?;
    validate_backend_distribution(&backend)?;
    let cli_provenance = read_prepared_artifact_provenance(
        &directory.join(PREPARED_CLI_PROVENANCE_PATH),
    )?;
    validate_prepared_artifact_provenance(
        &cli_provenance,
        LocalArtifactKind::Cli,
        &ledger.source,
        &ledger.components.cli.sha256,
    )?;
    let backend_provenance = read_prepared_artifact_provenance(
        &directory.join(PREPARED_BACKEND_PROVENANCE_PATH),
    )?;
    validate_prepared_artifact_provenance(
        &backend_provenance,
        LocalArtifactKind::HeadlessBackend,
        &ledger.source,
        &ledger.components.backend.sha256,
    )?;
    if cli_provenance.implementation_version != ledger.implementation_version
        || backend_provenance.implementation_version != ledger.implementation_version
    {
        return Err(CliError::new(
            "LOCAL_PREPARED_GENERATION_INVALID",
            "Prepared artifact versions do not match the generation ledger.",
        ));
    }
    let embedded_manifest = backend_embedded_component_manifest_bytes(&backend)?;
    if fs::read(directory.join(PREPARED_BACKEND_COMPONENT_MANIFEST_PATH))? != embedded_manifest {
        return Err(CliError::new(
            "LOCAL_PREPARED_BACKEND_MANIFEST_MISMATCH",
            "Prepared backend component manifest does not match the producer-emitted embedded manifest.",
        ));
    }
    validate_prepared_layout(&directory)?;
    Ok(LocalDevelopmentPreparedVerificationResult {
        ledger,
        directory,
        schema_version: crate::SCHEMA_VERSION,
    })
}

impl LocalPreparedArtifactProvenance {
    fn into_local(self, artifact: PathBuf) -> LocalArtifactProvenance {
        LocalArtifactProvenance {
            schema_version: self.schema_version,
            kind: self.kind,
            source: self.source,
            artifact,
            sha256: self.sha256,
            implementation_version: self.implementation_version,
        }
    }
}

fn read_prepared_artifact_provenance(path: &Path) -> Result<LocalPreparedArtifactProvenance> {
    serde_json::from_slice(&fs::read(path)?).map_err(|error| {
        CliError::new(
            "LOCAL_PREPARED_PROVENANCE_INVALID",
            format!("Invalid prepared artifact provenance at {}: {error}.", path.display()),
        )
    })
}

fn validate_prepared_artifact_provenance(
    provenance: &LocalPreparedArtifactProvenance,
    kind: LocalArtifactKind,
    source: &SourceSnapshot,
    sha256: &Sha256Digest,
) -> Result<()> {
    if provenance.schema_version != LOCAL_ARTIFACT_PROVENANCE_SCHEMA_VERSION
        || provenance.kind != kind
        || &provenance.source != source
        || &provenance.sha256 != sha256
        || provenance.implementation_version.trim().is_empty()
    {
        return Err(CliError::new(
            "LOCAL_PREPARED_PROVENANCE_INVALID",
            format!("Prepared {:?} provenance does not match its verified component.", kind),
        ));
    }
    Ok(())
}

fn validate_prepared_layout(root: &Path) -> Result<()> {
    let expected = [
        PREPARED_GENERATION_LEDGER_PATH,
        PREPARED_SOURCE_SNAPSHOT_PATH,
        PREPARED_CLI_PATH,
        PREPARED_CLI_PROVENANCE_PATH,
        PREPARED_BACKEND_PROVENANCE_PATH,
        PREPARED_BACKEND_COMPONENT_MANIFEST_PATH,
        PREPARED_SKILL_PATH,
        PREPARED_GUIDANCE_INPUTS_PATH,
        PREPARED_CONFIG_PATH,
    ]
    .into_iter()
    .map(PathBuf::from)
    .collect::<std::collections::BTreeSet<_>>();
    let allowed_directories = [
        PathBuf::from("bin"),
        PathBuf::from("provenance"),
        PathBuf::from("inputs"),
        PathBuf::from("inputs/kast-skill"),
    ]
    .into_iter()
    .collect::<std::collections::BTreeSet<_>>();
    let mut actual = Vec::new();
    collect_prepared_regular_files(root, root, &allowed_directories, &mut actual)?;
    let actual = actual.into_iter().collect::<std::collections::BTreeSet<_>>();
    let backend_root = Path::new(PREPARED_BACKEND_PATH);
    let unexpected = actual
        .iter()
        .filter(|path| !path.starts_with(backend_root) && !expected.contains(*path))
        .cloned()
        .collect::<Vec<_>>();
    let missing = expected.difference(&actual).cloned().collect::<Vec<_>>();
    if !unexpected.is_empty() || !missing.is_empty() {
        return Err(CliError::new(
            "LOCAL_PREPARED_GENERATION_LAYOUT_INVALID",
            format!(
                "Prepared generation has missing files {:?} and unexpected files {:?}.",
                missing, unexpected,
            ),
        ));
    }
    Ok(())
}

fn collect_prepared_regular_files(
    root: &Path,
    directory: &Path,
    allowed_directories: &std::collections::BTreeSet<PathBuf>,
    output: &mut Vec<PathBuf>,
) -> Result<()> {
    let mut entries = fs::read_dir(directory)?.collect::<std::io::Result<Vec<_>>>()?;
    entries.sort_by_key(|entry| entry.file_name());
    for entry in entries {
        let path = entry.path();
        let metadata = fs::symlink_metadata(&path)?;
        if metadata.file_type().is_symlink() {
            return Err(CliError::new(
                "LOCAL_PREPARED_GENERATION_LAYOUT_INVALID",
                format!("Prepared generation refuses symlink {}.", path.display()),
            ));
        }
        if metadata.is_dir() {
            let relative = path.strip_prefix(root).map_err(|_| {
                CliError::new(
                    "LOCAL_PREPARED_GENERATION_LAYOUT_INVALID",
                    format!("Prepared path escapes its root: {}.", path.display()),
                )
            })?;
            if !relative.starts_with(Path::new(PREPARED_BACKEND_PATH))
                && !allowed_directories.contains(relative)
            {
                return Err(CliError::new(
                    "LOCAL_PREPARED_GENERATION_LAYOUT_INVALID",
                    format!(
                        "Prepared generation contains unexpected directory {}.",
                        relative.display(),
                    ),
                ));
            }
            collect_prepared_regular_files(root, &path, allowed_directories, output)?;
        } else if metadata.is_file() {
            output.push(
                path.strip_prefix(root)
                    .map_err(|_| {
                        CliError::new(
                            "LOCAL_PREPARED_GENERATION_LAYOUT_INVALID",
                            format!("Prepared path escapes its root: {}.", path.display()),
                        )
                    })?
                    .to_path_buf(),
            );
        } else {
            return Err(CliError::new(
                "LOCAL_PREPARED_GENERATION_LAYOUT_INVALID",
                format!("Prepared generation refuses special entry {}.", path.display()),
            ));
        }
    }
    Ok(())
}

fn prepared_output_path(requested: &Path) -> Result<PathBuf> {
    let absolute = absolute_path(requested.to_path_buf())?;
    if let Ok(metadata) = fs::symlink_metadata(&absolute)
        && metadata.file_type().is_symlink()
    {
        return Err(CliError::new(
            "LOCAL_PREPARED_GENERATION_PATH_INVALID",
            format!(
                "Prepared generation cannot be selected through a symlink: {}.",
                absolute.display(),
            ),
        ));
    }
    Ok(absolute)
}

fn prepared_component(
    staged_root: &Path,
    relative_path: &str,
) -> Result<LocalPreparedGenerationComponent> {
    Ok(LocalPreparedGenerationComponent {
        relative_path: PathBuf::from(relative_path),
        sha256: tree_sha256(&staged_root.join(relative_path))?,
    })
}

fn validate_prepared_component(
    root: &Path,
    component: &LocalPreparedGenerationComponent,
    expected_path: &str,
) -> Result<()> {
    if component.relative_path != Path::new(expected_path) {
        return Err(CliError::new(
            "LOCAL_PREPARED_GENERATION_INVALID",
            format!(
                "Prepared component path {} must be {}.",
                component.relative_path.display(),
                expected_path,
            ),
        ));
    }
    let actual = tree_sha256(&root.join(expected_path))?;
    if actual != component.sha256 {
        return Err(CliError::new(
            "LOCAL_PREPARED_COMPONENT_CHECKSUM_MISMATCH",
            format!(
                "Prepared component {} has SHA-256 {}, expected {}.",
                expected_path,
                actual.as_str(),
                component.sha256.as_str(),
            ),
        ));
    }
    Ok(())
}

fn copy_regular_file(source: &Path, target: &Path) -> Result<()> {
    let metadata = fs::symlink_metadata(source)?;
    if !metadata.is_file() || metadata.file_type().is_symlink() {
        return Err(CliError::new(
            "LOCAL_COMPONENT_ENTRY_UNSUPPORTED",
            format!("Prepared generation requires a regular file: {}.", source.display()),
        ));
    }
    if let Some(parent) = target.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::copy(source, target)?;
    fs::set_permissions(target, metadata.permissions())?;
    Ok(())
}

fn require_exact_controller(expected: &Path, code: &'static str) -> Result<()> {
    if cfg!(not(test)) {
        let controller = fs::canonicalize(std::env::current_exe()?)?;
        if controller != expected {
            return Err(CliError::new(
                code,
                format!(
                    "Local generation operation must be executed by the exact source-bound CLI: controller {}, expected {}.",
                    controller.display(),
                    expected.display(),
                ),
            ));
        }
    }
    Ok(())
}
