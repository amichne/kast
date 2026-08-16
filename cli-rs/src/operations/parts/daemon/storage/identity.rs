#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct IndexerStorageIdentity {
    workspace_root: PathBuf,
    storage_root: PathBuf,
    workspace_id: String,
}

impl IndexerStorageIdentity {
    pub(crate) fn resolve(requested: &Path, config: &KastConfig) -> Result<Self> {
        let workspace_root = fs::canonicalize(requested).map_err(|error| {
            CliError::new(
                "INDEXER_STORAGE_LAYOUT_INVALID",
                format!(
                    "Cannot resolve the exact indexer workspace {}: {error}",
                    requested.display(),
                ),
            )
        })?;
        let workspace_id = config::workspace_hash(&workspace_root);
        let requested_storage_root = config
            .paths
            .cache_dir
            .join("idea-sidecars")
            .join(&workspace_id)
            .to_absolute_path()?;
        let projected_storage_root = projected_real_path(&requested_storage_root)?;
        require_disjoint_storage(&workspace_root, &projected_storage_root)?;
        fs::create_dir_all(&requested_storage_root).map_err(|error| {
            CliError::new(
                "INDEXER_STORAGE_LAYOUT_INVALID",
                format!(
                    "Cannot create Kast indexer storage {}: {error}",
                    requested_storage_root.display(),
                ),
            )
        })?;
        let storage_root = fs::canonicalize(&requested_storage_root).map_err(|error| {
            CliError::new(
                "INDEXER_STORAGE_LAYOUT_INVALID",
                format!(
                    "Cannot resolve Kast indexer storage {}: {error}",
                    requested_storage_root.display(),
                ),
            )
        })?;
        require_disjoint_storage(&workspace_root, &storage_root)?;
        for child in [
            "project-identity",
            "gradle-project-cache",
            "storage.lease",
            "launch.lock",
            "launch-manifest.json",
            "bootstrap",
            "idea-config",
            "idea-system",
            "idea-log",
            "plugins",
        ] {
            reject_symbolic_link(&storage_root.join(child))?;
        }
        Ok(Self {
            workspace_root,
            storage_root,
            workspace_id,
        })
    }

    pub(crate) fn workspace_root(&self) -> &Path {
        &self.workspace_root
    }

    pub(crate) fn storage_root(&self) -> &Path {
        &self.storage_root
    }

    pub(crate) fn launch_lock_file(&self) -> PathBuf {
        self.storage_root.join("launch.lock")
    }

    fn storage_lease_file(&self) -> PathBuf {
        self.storage_root.join("storage.lease")
    }
}

fn projected_real_path(path: &Path) -> Result<PathBuf> {
    let absolute = path.to_absolute_path()?;
    let mut existing = absolute.as_path();
    let mut suffix = Vec::new();
    loop {
        match fs::symlink_metadata(existing) {
            Ok(_) => break,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                let name = existing.file_name().ok_or_else(|| {
                    CliError::new(
                        "INDEXER_STORAGE_LAYOUT_INVALID",
                        format!("Cannot resolve Kast indexer storage {}.", absolute.display()),
                    )
                })?;
                suffix.push(name.to_os_string());
                existing = existing.parent().ok_or_else(|| {
                    CliError::new(
                        "INDEXER_STORAGE_LAYOUT_INVALID",
                        format!("Cannot resolve Kast indexer storage {}.", absolute.display()),
                    )
                })?;
            }
            Err(error) => {
                return Err(CliError::new(
                    "INDEXER_STORAGE_LAYOUT_INVALID",
                    format!("Cannot inspect Kast indexer storage {}: {error}", existing.display()),
                ));
            }
        }
    }
    let mut projected = fs::canonicalize(existing).map_err(|error| {
        CliError::new(
            "INDEXER_STORAGE_LAYOUT_INVALID",
            format!("Cannot resolve Kast indexer storage {}: {error}", existing.display()),
        )
    })?;
    for name in suffix.into_iter().rev() {
        projected.push(name);
    }
    Ok(projected)
}

fn require_disjoint_storage(workspace_root: &Path, storage_root: &Path) -> Result<()> {
    if storage_root.starts_with(workspace_root) || workspace_root.starts_with(storage_root) {
        return Err(CliError::new(
            "INDEXER_STORAGE_LAYOUT_INVALID",
            format!(
                "Kast indexer storage {} must be disjoint from the exact source root {}.",
                storage_root.display(),
                workspace_root.display(),
            ),
        ));
    }
    Ok(())
}

fn reject_symbolic_link(path: &Path) -> Result<()> {
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.file_type().is_symlink() => Err(CliError::new(
            "INDEXER_STORAGE_LAYOUT_INVALID",
            format!(
                "Kast indexer storage path must not be a symbolic link: {}",
                path.display(),
            ),
        )),
        Ok(_) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(CliError::new(
            "INDEXER_STORAGE_LAYOUT_INVALID",
            format!("Cannot inspect Kast indexer storage {}: {error}", path.display()),
        )),
    }
}

#[derive(Debug)]
struct IndexerProjectLayout {
    identity: IndexerStorageIdentity,
    project_identity_directory: PathBuf,
    gradle_project_cache_directory: PathBuf,
    storage_lease_file: PathBuf,
    launch_manifest_file: PathBuf,
    bootstrap_directory: PathBuf,
    idea_config: PathBuf,
    idea_system: PathBuf,
    idea_log: PathBuf,
    plugins: PathBuf,
}

impl IndexerProjectLayout {
    fn resolve(args: &DaemonStartArgs, config: &KastConfig) -> Result<Self> {
        let requested = config::resolve_workspace_root(args.workspace_root.clone())?;
        Self::for_workspace(&requested, config)
    }

    fn for_workspace(requested: &Path, config: &KastConfig) -> Result<Self> {
        let identity = IndexerStorageIdentity::resolve(requested, config)?;
        let storage_root = identity.storage_root().to_path_buf();
        let layout = Self {
            identity,
            project_identity_directory: storage_root.join("project-identity"),
            gradle_project_cache_directory: storage_root.join("gradle-project-cache"),
            storage_lease_file: storage_root.join("storage.lease"),
            launch_manifest_file: storage_root.join("launch-manifest.json"),
            bootstrap_directory: storage_root.join("bootstrap"),
            idea_config: storage_root.join("idea-config"),
            idea_system: storage_root.join("idea-system"),
            idea_log: storage_root.join("idea-log"),
            plugins: storage_root.join("plugins"),
        };
        layout.validate()?;
        layout.create_directories()?;
        layout.write_launch_manifest()?;
        Ok(layout)
    }

    fn validate(&self) -> Result<()> {
        let writable_directories = [
            &self.project_identity_directory,
            &self.gradle_project_cache_directory,
            &self.idea_config,
            &self.idea_system,
            &self.idea_log,
            &self.plugins,
            &self.bootstrap_directory,
        ];
        for directory in writable_directories {
            if !directory.starts_with(self.identity.storage_root()) {
                return Err(CliError::new(
                    "INDEXER_STORAGE_LAYOUT_INVALID",
                    format!(
                        "Kast indexer storage {} escaped its canonical root {}.",
                        directory.display(),
                        self.identity.storage_root().display(),
                    ),
                ));
            }
        }
        Ok(())
    }

    fn create_directories(&self) -> Result<()> {
        for directory in [
            &self.project_identity_directory,
            &self.gradle_project_cache_directory,
            &self.idea_config,
            &self.idea_system,
            &self.idea_log,
            &self.plugins,
            &self.bootstrap_directory,
        ] {
            reject_symbolic_link(directory)?;
            fs::create_dir_all(directory).map_err(|error| {
                CliError::new(
                    "INDEXER_STORAGE_LAYOUT_INVALID",
                    format!(
                        "Cannot create Kast indexer storage {}: {error}",
                        directory.display(),
                    ),
                )
            })?;
            let actual = fs::canonicalize(directory).map_err(|error| {
                CliError::new(
                    "INDEXER_STORAGE_LAYOUT_INVALID",
                    format!(
                        "Cannot resolve Kast indexer storage {}: {error}",
                        directory.display(),
                    ),
                )
            })?;
            if actual != *directory || !actual.starts_with(self.identity.storage_root()) {
                return Err(CliError::new(
                    "INDEXER_STORAGE_LAYOUT_INVALID",
                    format!(
                        "Kast indexer storage {} escaped its canonical root {}.",
                        actual.display(),
                        self.identity.storage_root().display(),
                    ),
                ));
            }
        }
        reject_symbolic_link(&self.storage_lease_file)?;
        reject_symbolic_link(&self.launch_manifest_file)?;
        Ok(())
    }

    fn write_launch_manifest(&self) -> Result<()> {
        let admitted = config::admitted_workspace_data_layout(self.identity.workspace_root())?;
        let document = IndexerLaunchManifestDocument {
            schema_version: 1,
            canonical_workspace_root: self.identity.workspace_root().to_path_buf(),
            canonical_storage_root: self.identity.storage_root().to_path_buf(),
            workspace_data_directory: projected_real_path(&admitted.workspace_data_directory)?,
            repository_data_directory: admitted
                .repository_data_directory
                .map(|path| projected_real_path(&path))
                .transpose()?,
        };
        fs::write(
            &self.launch_manifest_file,
            serde_json::to_vec_pretty(&document)?,
        )
        .map_err(|error| {
            CliError::new(
                "INDEXER_STORAGE_LAYOUT_INVALID",
                format!(
                    "Cannot write Kast indexer launch manifest {}: {error}",
                    self.launch_manifest_file.display(),
                ),
            )
        })
    }

    fn starter_arguments(&self) -> [String; 1] {
        [format!(
            "--indexer-storage-root={}",
            self.identity.storage_root().display(),
        )]
    }
}

#[derive(Debug, serde::Deserialize, serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct IndexerLaunchManifestDocument {
    schema_version: u32,
    canonical_workspace_root: PathBuf,
    canonical_storage_root: PathBuf,
    workspace_data_directory: PathBuf,
    repository_data_directory: Option<PathBuf>,
}

trait AbsolutePath {
    fn to_absolute_path(&self) -> Result<PathBuf>;
}

impl AbsolutePath for Path {
    fn to_absolute_path(&self) -> Result<PathBuf> {
        if self.is_absolute() {
            Ok(self.components().collect())
        } else {
            Ok(env::current_dir()?.join(self).components().collect())
        }
    }
}
