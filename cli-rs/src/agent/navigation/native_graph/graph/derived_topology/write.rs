struct NewDerivedTopologyPath {
    workspace_root: PathBuf,
    relative: PathBuf,
    absolute: PathBuf,
}

pub(crate) fn write_reference_derived_topology(
    workspace_root: &Path,
    output: &Path,
    prior: Option<&Path>,
) -> Result<DerivedTopologyReceipt> {
    let output = NewDerivedTopologyPath::resolve(workspace_root, output)?;
    let semantic_read =
        crate::runtime::semantic_graph_workspace_read(Some(output.workspace_root.clone()))?;
    let previous = prior
        .map(|path| read_previous_topology(&output.workspace_root, path))
        .transpose()?;
    let snapshot = load_reference_topology_snapshot(semantic_read.published())?;
    if previous
        .as_ref()
        .is_some_and(|previous| previous.source.generation >= snapshot.generation)
    {
        return Err(CliError::new(
            "DERIVED_TOPOLOGY_PRIOR_NOT_OLDER",
            "The prior artifact generation must be older than the current source index.",
        ));
    }
    let artifact = derive_reference_topology(snapshot, previous.as_ref());
    let mut bytes = serde_json::to_vec_pretty(&artifact)?;
    bytes.push(b'\n');
    let bytes = semantic_read.revalidate()?.finish(bytes);
    output.write_new(&bytes)?;
    Ok(DerivedTopologyReceipt {
        artifact: output.relative.display().to_string(),
        generation: artifact.source.generation,
        node_count: artifact.nodes.len(),
        edge_count: artifact.edges.len(),
        community_count: artifact.communities.len(),
        sha256: crate::manifest::sha256_bytes(&bytes),
    })
}

impl NewDerivedTopologyPath {
    fn resolve(workspace_root: &Path, relative: &Path) -> Result<Self> {
        if relative.as_os_str().is_empty()
            || relative.is_absolute()
            || relative
                .components()
                .any(|component| !matches!(component, std::path::Component::Normal(_)))
        {
            return Err(CliError::new(
                "CLI_USAGE",
                "--out must be a non-empty workspace-relative path without `.` or `..`.",
            ));
        }
        let workspace_root = workspace_root.canonicalize().map_err(|error| {
            CliError::new(
                "DERIVED_TOPOLOGY_WORKSPACE_UNAVAILABLE",
                format!("Cannot resolve the workspace root: {error}"),
            )
        })?;
        let absolute = workspace_root.join(relative);
        if absolute.exists() {
            return Err(CliError::new(
                "DERIVED_TOPOLOGY_OUTPUT_EXISTS",
                format!("The output already exists: {}", relative.display()),
            ));
        }
        let parent = absolute.parent().ok_or_else(|| {
            CliError::new(
                "CLI_USAGE",
                "--out must identify a file inside the workspace.",
            )
        })?;
        let mut existing = parent;
        while !existing.exists() {
            existing = existing.parent().ok_or_else(|| {
                CliError::new(
                    "DERIVED_TOPOLOGY_OUTPUT_OUTSIDE_WORKSPACE",
                    "The output parent is outside the workspace.",
                )
            })?;
        }
        if !existing
            .canonicalize()
            .map_err(|error| {
                CliError::new(
                    "DERIVED_TOPOLOGY_OUTPUT_UNAVAILABLE",
                    format!("Cannot resolve the output parent: {error}"),
                )
            })?
            .starts_with(&workspace_root)
        {
            return Err(CliError::new(
                "DERIVED_TOPOLOGY_OUTPUT_OUTSIDE_WORKSPACE",
                "The output path resolves outside the workspace.",
            ));
        }
        Ok(Self {
            workspace_root,
            relative: relative.to_path_buf(),
            absolute,
        })
    }

    fn write_new(&self, bytes: &[u8]) -> Result<()> {
        let parent = self.absolute.parent().ok_or_else(|| {
            CliError::new(
                "DERIVED_TOPOLOGY_OUTPUT_UNAVAILABLE",
                "The output path has no parent.",
            )
        })?;
        std::fs::create_dir_all(parent).map_err(derived_topology_write_error)?;
        let canonical_parent = parent
            .canonicalize()
            .map_err(derived_topology_write_error)?;
        if !canonical_parent.starts_with(&self.workspace_root) {
            return Err(CliError::new(
                "DERIVED_TOPOLOGY_OUTPUT_OUTSIDE_WORKSPACE",
                "The output parent resolves outside the workspace.",
            ));
        }
        let filename = self.absolute.file_name().ok_or_else(|| {
            CliError::new(
                "DERIVED_TOPOLOGY_OUTPUT_UNAVAILABLE",
                "The output path has no file name.",
            )
        })?;
        let target = canonical_parent.join(filename);
        if target.exists() {
            return Err(CliError::new(
                "DERIVED_TOPOLOGY_OUTPUT_EXISTS",
                format!("The output already exists: {}", self.relative.display()),
            ));
        }
        let temporary = canonical_parent.join(format!(
            ".{}.{}.tmp",
            filename.to_string_lossy(),
            std::process::id()
        ));
        let result = (|| {
            use std::io::Write;

            let mut file = std::fs::OpenOptions::new()
                .create_new(true)
                .write(true)
                .open(&temporary)
                .map_err(derived_topology_write_error)?;
            file.write_all(bytes)
                .map_err(derived_topology_write_error)?;
            file.sync_all().map_err(derived_topology_write_error)?;
            std::fs::hard_link(&temporary, &target).map_err(|error| {
                if error.kind() == std::io::ErrorKind::AlreadyExists {
                    CliError::new(
                        "DERIVED_TOPOLOGY_OUTPUT_EXISTS",
                        format!("The output already exists: {}", self.relative.display()),
                    )
                } else {
                    derived_topology_write_error(error)
                }
            })
        })();
        let _ = std::fs::remove_file(&temporary);
        result
    }
}

fn read_previous_topology(
    workspace_root: &Path,
    relative: &Path,
) -> Result<DerivedTopologyArtifact> {
    if relative.as_os_str().is_empty()
        || relative.is_absolute()
        || relative
            .components()
            .any(|component| !matches!(component, std::path::Component::Normal(_)))
    {
        return Err(CliError::new(
            "CLI_USAGE",
            "--prior must be a workspace-relative artifact path without `.` or `..`.",
        ));
    }
    let path = workspace_root.join(relative);
    let canonical = path.canonicalize().map_err(|error| {
        CliError::new(
            "DERIVED_TOPOLOGY_PRIOR_UNAVAILABLE",
            format!("Cannot read prior artifact {}: {error}", relative.display()),
        )
    })?;
    if !canonical.starts_with(workspace_root) || !canonical.is_file() {
        return Err(CliError::new(
            "DERIVED_TOPOLOGY_PRIOR_OUTSIDE_WORKSPACE",
            "The prior artifact must be a file inside the workspace.",
        ));
    }
    let artifact: DerivedTopologyArtifact =
        serde_json::from_slice(&std::fs::read(canonical).map_err(derived_topology_read_error)?)?;
    if artifact.r#type != "KAST_DERIVED_TOPOLOGY"
        || artifact.schema_version != DERIVED_TOPOLOGY_SCHEMA_VERSION
        || artifact.evidence_class != DerivedEvidenceClass::StatisticalDerivation
        || artifact.source.lane != DerivedSourceLane::ReferenceDerived
        || artifact.algorithm.name != DerivedAlgorithmName::KastDeterministicPartitionV1
        || artifact.algorithm.version != DERIVED_TOPOLOGY_ALGORITHM_VERSION
        || artifact.algorithm.resolution != DERIVED_TOPOLOGY_RESOLUTION
        || artifact.algorithm.weighting != DerivedWeighting::Log1pOccurrenceCount
    {
        return Err(CliError::new(
            "DERIVED_TOPOLOGY_PRIOR_INCOMPATIBLE",
            "The prior artifact has an incompatible type, schema, source lane, or algorithm.",
        ));
    }
    Ok(artifact)
}

fn derived_topology_read_error(error: std::io::Error) -> CliError {
    CliError::new("DERIVED_TOPOLOGY_PRIOR_UNAVAILABLE", error.to_string())
}

fn derived_topology_write_error(error: std::io::Error) -> CliError {
    CliError::new("DERIVED_TOPOLOGY_WRITE_FAILED", error.to_string())
}
