pub fn attest_local_artifact(
    request: LocalArtifactAttestationRequest,
) -> Result<LocalArtifactProvenance> {
    let expected = SourceSnapshot::read_strict(&request.expected_source_snapshot)?;
    let source = SourceSnapshot::capture(&request.source_root)?;
    if source != expected {
        return Err(source_snapshot_mismatch(&expected, &source));
    }
    let artifact = match request.kind {
        LocalArtifactKind::Cli => canonical_file(&request.artifact, "development CLI artifact")?,
        LocalArtifactKind::HeadlessBackend => {
            let artifact =
                canonical_directory(&request.artifact, "headless backend artifact")?;
            validate_backend_distribution(&artifact)?;
            artifact
        }
    };
    if request.kind == LocalArtifactKind::Cli {
        let producer = fs::canonicalize(std::env::current_exe()?)?;
        if producer != artifact {
            return Err(CliError::new(
                "LOCAL_CLI_ATTESTER_MISMATCH",
                format!(
                    "CLI provenance must be emitted by the exact artifact: producer {}, artifact {}.",
                    producer.display(),
                    artifact.display(),
                ),
            ));
        }
        validate_cli_producer_source_identity(
            option_env!("KAST_LOCAL_SOURCE_SHA256"),
            &source.source_tree_sha256,
        )?;
    } else {
        validate_backend_embedded_source_identity(&artifact, &source)?;
    }
    let artifact_sha256 = tree_sha256(&artifact)?;
    let provenance = LocalArtifactProvenance {
        schema_version: LOCAL_ARTIFACT_PROVENANCE_SCHEMA_VERSION,
        kind: request.kind,
        source: source.clone(),
        artifact,
        sha256: artifact_sha256,
        implementation_version: crate::cli::version().to_string(),
    };
    let after_hashing = SourceSnapshot::capture(&request.source_root)?;
    if after_hashing != expected {
        return Err(source_snapshot_mismatch(&expected, &after_hashing));
    }
    write_json_atomic(&request.output_file, &provenance)?;
    Ok(provenance)
}

fn read_local_artifact_provenance(path: &Path) -> Result<LocalArtifactProvenance> {
    let provenance: LocalArtifactProvenance =
        serde_json::from_slice(&fs::read(path)?).map_err(|error| {
            CliError::new(
                "LOCAL_ARTIFACT_PROVENANCE_INVALID",
                format!("Invalid local artifact provenance at {}: {error}", path.display()),
            )
        })?;
    if provenance.schema_version != LOCAL_ARTIFACT_PROVENANCE_SCHEMA_VERSION {
        return Err(CliError::new(
            "LOCAL_ARTIFACT_PROVENANCE_UNSUPPORTED",
            format!(
                "Local artifact provenance schema {} is unsupported; expected {}.",
                provenance.schema_version, LOCAL_ARTIFACT_PROVENANCE_SCHEMA_VERSION,
            ),
        ));
    }
    Ok(provenance)
}

fn validate_local_artifact_provenance(
    provenance: &LocalArtifactProvenance,
    kind: LocalArtifactKind,
    source: &SourceSnapshot,
    artifact: &Path,
) -> Result<()> {
    if provenance.kind != kind {
        return Err(CliError::new(
            "LOCAL_ARTIFACT_KIND_MISMATCH",
            format!(
                "Artifact provenance declares {:?}, but refresh requires {:?}.",
                provenance.kind, kind,
            ),
        ));
    }
    if &provenance.source != source {
        return Err(CliError::new(
            "LOCAL_ARTIFACT_SOURCE_MISMATCH",
            format!(
                "{:?} artifact provenance does not match source snapshot {}.",
                kind,
                source.source_tree_sha256.as_str(),
            ),
        ));
    }
    if provenance.artifact != artifact {
        return Err(CliError::new(
            "LOCAL_ARTIFACT_PATH_MISMATCH",
            format!(
                "{:?} provenance names {}, but refresh selected {}.",
                kind,
                provenance.artifact.display(),
                artifact.display(),
            ),
        ));
    }
    let actual = tree_sha256(artifact)?;
    if actual != provenance.sha256 {
        return Err(CliError::new(
            "LOCAL_ARTIFACT_CHECKSUM_MISMATCH",
            format!(
                "{:?} artifact bytes changed after provenance at {}.",
                kind,
                artifact.display(),
            ),
        ));
    }
    if provenance.implementation_version.trim().is_empty() {
        return Err(CliError::new(
            "LOCAL_ARTIFACT_PROVENANCE_INVALID",
            format!("{:?} provenance has no implementation version.", kind),
        ));
    }
    if kind == LocalArtifactKind::HeadlessBackend {
        validate_backend_embedded_source_identity(artifact, source)?;
    }
    Ok(())
}

fn validate_cli_producer_source_identity(
    embedded_source_sha256: Option<&str>,
    expected_source_sha256: &Sha256Digest,
) -> Result<()> {
    let embedded_source_sha256 = embedded_source_sha256.ok_or_else(|| {
        CliError::new(
            "LOCAL_CLI_SOURCE_ATTESTATION_MISSING",
            "The CLI was not built as a source-bound local-development producer.",
        )
    })?;
    if embedded_source_sha256 != expected_source_sha256.as_str() {
        return Err(CliError::new(
            "LOCAL_CLI_SOURCE_MISMATCH",
            format!(
                "The CLI embeds source snapshot {embedded_source_sha256}, but attestation requires {}.",
                expected_source_sha256.as_str(),
            ),
        ));
    }
    Ok(())
}

fn validate_backend_embedded_source_identity(
    artifact: &Path,
    expected: &SourceSnapshot,
) -> Result<()> {
    let plugin_jar = backend_plugin_implementation_jar(artifact)?;
    let file = fs::File::open(&plugin_jar)?;
    let mut archive = zip::ZipArchive::new(file).map_err(|error| {
        CliError::new(
            "LOCAL_BACKEND_SOURCE_ATTESTATION_INVALID",
            format!("Invalid headless backend plugin jar {}: {error}", plugin_jar.display()),
        )
    })?;
    let bytes = read_backend_attestation_entry(
        &mut archive,
        &plugin_jar,
        LOCAL_BACKEND_SOURCE_SNAPSHOT_ENTRY,
    )?;
    let embedded = SourceSnapshot::from_slice(
        &bytes,
        &format!("{}!/{LOCAL_BACKEND_SOURCE_SNAPSHOT_ENTRY}", plugin_jar.display()),
    )
    .map_err(|error| {
        CliError::new(
            "LOCAL_BACKEND_SOURCE_ATTESTATION_INVALID",
            error.to_string(),
        )
    })?;
    if &embedded != expected {
        return Err(CliError::new(
            "LOCAL_BACKEND_SOURCE_MISMATCH",
            format!(
                "Headless backend plugin jar {} was produced from source snapshot {}, not {}.",
                plugin_jar.display(),
                embedded.source_tree_sha256.as_str(),
                expected.source_tree_sha256.as_str(),
            ),
        ));
    }
    let manifest_bytes = read_backend_attestation_entry(
        &mut archive,
        &plugin_jar,
        LOCAL_BACKEND_COMPONENT_MANIFEST_ENTRY,
    )?;
    let manifest: LocalBackendComponentManifest =
        serde_json::from_slice(&manifest_bytes).map_err(|error| {
            CliError::new(
                "LOCAL_BACKEND_COMPONENT_MANIFEST_INVALID",
                format!(
                    "Invalid producer component manifest in {}: {error}",
                    plugin_jar.display(),
                ),
            )
        })?;
    validate_backend_component_manifest(artifact, expected, &manifest)?;
    Ok(())
}

fn backend_embedded_component_manifest_bytes(artifact: &Path) -> Result<Vec<u8>> {
    let plugin_jar = backend_plugin_implementation_jar(artifact)?;
    let file = fs::File::open(&plugin_jar)?;
    let mut archive = zip::ZipArchive::new(file).map_err(|error| {
        CliError::new(
            "LOCAL_BACKEND_SOURCE_ATTESTATION_INVALID",
            format!("Invalid headless backend plugin jar {}: {error}", plugin_jar.display()),
        )
    })?;
    read_backend_attestation_entry(
        &mut archive,
        &plugin_jar,
        LOCAL_BACKEND_COMPONENT_MANIFEST_ENTRY,
    )
}

fn read_backend_attestation_entry(
    archive: &mut zip::ZipArchive<fs::File>,
    plugin_jar: &Path,
    entry_name: &str,
) -> Result<Vec<u8>> {
    let mut entry = archive.by_name(entry_name).map_err(|error| {
        CliError::new(
            "LOCAL_BACKEND_SOURCE_ATTESTATION_INVALID",
            format!(
                "Headless backend plugin jar {} has no producer-emitted {} entry: {error}",
                plugin_jar.display(),
                entry_name,
            ),
        )
    })?;
    let mut bytes = Vec::new();
    entry.read_to_end(&mut bytes)?;
    Ok(bytes)
}

const LOCAL_BACKEND_COMPONENT_MANIFEST_SCHEMA_VERSION: u32 = 1;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct LocalBackendComponentManifest {
    schema_version: u32,
    source_tree_sha256: Sha256Digest,
    components: Vec<LocalBackendProducerComponent>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct LocalBackendProducerComponent {
    kind: LocalBackendProducerComponentKind,
    path: PathBuf,
    sha256: Sha256Digest,
}

#[derive(Debug, Clone, Copy, Deserialize, Eq, Ord, PartialEq, PartialOrd)]
#[serde(rename_all = "kebab-case")]
enum LocalBackendProducerComponentKind {
    AnalysisApi,
    AnalysisServer,
    BackendHeadlessLauncher,
    BackendHeadlessPluginDescriptor,
    BackendIdea,
    BackendShared,
    IndexStore,
}

fn validate_backend_component_manifest(
    artifact: &Path,
    expected: &SourceSnapshot,
    manifest: &LocalBackendComponentManifest,
) -> Result<()> {
    if manifest.schema_version != LOCAL_BACKEND_COMPONENT_MANIFEST_SCHEMA_VERSION {
        return Err(CliError::new(
            "LOCAL_BACKEND_COMPONENT_MANIFEST_UNSUPPORTED",
            format!(
                "Backend component manifest schema {} is unsupported; expected {}.",
                manifest.schema_version, LOCAL_BACKEND_COMPONENT_MANIFEST_SCHEMA_VERSION,
            ),
        ));
    }
    if manifest.source_tree_sha256 != expected.source_tree_sha256 {
        return Err(CliError::new(
            "LOCAL_BACKEND_COMPONENT_SOURCE_MISMATCH",
            "Backend component manifest does not belong to the captured source snapshot.",
        ));
    }
    let expected_kinds = [
        LocalBackendProducerComponentKind::AnalysisApi,
        LocalBackendProducerComponentKind::AnalysisServer,
        LocalBackendProducerComponentKind::BackendHeadlessLauncher,
        LocalBackendProducerComponentKind::BackendHeadlessPluginDescriptor,
        LocalBackendProducerComponentKind::BackendIdea,
        LocalBackendProducerComponentKind::BackendShared,
        LocalBackendProducerComponentKind::IndexStore,
    ]
    .into_iter()
    .collect::<std::collections::BTreeSet<_>>();
    let mut manifested = std::collections::BTreeMap::new();
    for component in &manifest.components {
        require_safe_relative_path(&component.path)?;
        if manifested.insert(component.kind, component.path.clone()).is_some() {
            return Err(CliError::new(
                "LOCAL_BACKEND_COMPONENT_MANIFEST_INVALID",
                format!("Backend component kind {:?} appears more than once.", component.kind),
            ));
        }
        let path = artifact.join(&component.path);
        let metadata = fs::symlink_metadata(&path).map_err(|error| {
            CliError::new(
                "LOCAL_BACKEND_COMPONENT_MISSING",
                format!("Manifested backend component {} is unavailable: {error}.", path.display()),
            )
        })?;
        if metadata.file_type().is_symlink() || !metadata.is_file() {
            return Err(CliError::new(
                "LOCAL_BACKEND_COMPONENT_MANIFEST_INVALID",
                format!("Manifested backend component is not an owned regular file: {}.", path.display()),
            ));
        }
        let actual = Sha256Digest::try_from(crate::manifest::sha256_file(&path)?)?;
        if actual != component.sha256 {
            return Err(CliError::new(
                "LOCAL_BACKEND_COMPONENT_CHECKSUM_MISMATCH",
                format!("Producer-owned backend component changed after build: {}.", path.display()),
            ));
        }
    }
    let manifested_kinds = manifested.keys().copied().collect::<std::collections::BTreeSet<_>>();
    if manifested_kinds != expected_kinds {
        return Err(CliError::new(
            "LOCAL_BACKEND_COMPONENT_MANIFEST_INVALID",
            format!(
                "Backend component manifest covered {:?}; expected {:?}.",
                manifested_kinds, expected_kinds,
            ),
        ));
    }
    let actual = owned_backend_components(artifact)?;
    if actual != manifested {
        return Err(CliError::new(
            "LOCAL_BACKEND_COMPONENT_MANIFEST_INVALID",
            "Staged producer-owned backend JARs do not exactly match the embedded component manifest.",
        ));
    }
    Ok(())
}

fn owned_backend_components(
    artifact: &Path,
) -> Result<std::collections::BTreeMap<LocalBackendProducerComponentKind, PathBuf>> {
    let candidates = [
        (artifact.join("runtime-libs"), PathBuf::from("runtime-libs")),
        (
            artifact.join("idea-home/plugins/kast-headless/lib"),
            PathBuf::from("idea-home/plugins/kast-headless/lib"),
        ),
    ];
    let mut components = std::collections::BTreeMap::new();
    for (directory, relative_directory) in candidates {
        for entry in fs::read_dir(directory)? {
            let entry = entry?;
            if !entry.file_type()?.is_file() {
                continue;
            }
            let Some(name) = entry.file_name().to_str().map(str::to_string) else {
                continue;
            };
            let Some(kind) = backend_component_kind(&name) else {
                continue;
            };
            let relative = relative_directory.join(name);
            if components.insert(kind, relative).is_some() {
                return Err(CliError::new(
                    "LOCAL_BACKEND_COMPONENT_MANIFEST_INVALID",
                    format!("Backend distribution contains duplicate {:?} components.", kind),
                ));
            }
        }
    }
    Ok(components)
}

fn backend_component_kind(name: &str) -> Option<LocalBackendProducerComponentKind> {
    use LocalBackendProducerComponentKind as Kind;

    match name {
        name if name.starts_with("analysis-api-") && name.ends_with(".jar") => Some(Kind::AnalysisApi),
        name if name.starts_with("analysis-server-") && name.ends_with(".jar") => Some(Kind::AnalysisServer),
        name if name.starts_with("backend-headless-") && name.ends_with("-launcher.jar") => {
            Some(Kind::BackendHeadlessLauncher)
        }
        name if name.starts_with("backend-headless-") && name.ends_with("-plugin-descriptor.jar") => {
            Some(Kind::BackendHeadlessPluginDescriptor)
        }
        name if name.starts_with("backend-idea-") && name.ends_with(".jar") => Some(Kind::BackendIdea),
        name if name.starts_with("backend-shared-") && name.ends_with(".jar") => Some(Kind::BackendShared),
        name if name.starts_with("index-store-") && name.ends_with(".jar") => Some(Kind::IndexStore),
        _ => None,
    }
}

fn backend_plugin_implementation_jar(artifact: &Path) -> Result<PathBuf> {
    let plugin_lib = artifact.join("idea-home/plugins/kast-headless/lib");
    let mut candidates = fs::read_dir(&plugin_lib)?
        .filter_map(|entry| entry.ok())
        .map(|entry| entry.path())
        .filter(|path| {
            path.file_name()
                .and_then(|name| name.to_str())
                .is_some_and(|name| {
                    name.starts_with("backend-headless-") && name.ends_with("-plugin.jar")
                })
        })
        .collect::<Vec<_>>();
    candidates.sort();
    if candidates.len() != 1 {
        return Err(CliError::new(
            "LOCAL_BACKEND_SOURCE_ATTESTATION_INVALID",
            format!(
                "Headless backend {} must contain exactly one backend-headless-*-plugin.jar, found {}.",
                artifact.display(),
                candidates.len(),
            ),
        ));
    }
    Ok(candidates.remove(0))
}
