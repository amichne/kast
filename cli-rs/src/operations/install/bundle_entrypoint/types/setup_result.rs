impl SetupMode {
    fn from_force_flag(force: bool) -> Self {
        if force { Self::Force } else { Self::Reconcile }
    }

    fn is_force(self) -> bool {
        self == Self::Force
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SetupResult {
    #[serde(rename = "type")]
    pub result_type: &'static str,
    pub status: SetupStatus,
    pub release_digest: String,
    pub manifest_digest: String,
    pub kast_home: String,
    pub current: String,
    pub active_binary: String,
    pub developer_operations: DeveloperOperationsRoute,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub backup: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub restart_requirement: Option<SetupRestartRequirement>,
    pub artifacts: Vec<SetupArtifact>,
    pub verified: bool,
    pub schema_version: u32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SetupRestartRequirement {
    pub code: &'static str,
    pub message: &'static str,
}

#[derive(Debug)]
struct RetiredPublicPluginRemoval {
    restart_requirement: Option<SetupRestartRequirement>,
}

#[derive(Debug)]
struct LegacyInstallationArchive {
    entries: Vec<LegacyInstallationArchiveEntry>,
}

impl LegacyInstallationArchive {
    fn backup_path(&self) -> Option<&Path> {
        self.entries.last().map(|entry| entry.backup.as_path())
    }

    fn restore(&self) -> Result<()> {
        let mut restorable = Vec::new();
        for entry in self.entries.iter().rev() {
            match fs::symlink_metadata(&entry.backup) {
                Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
                Err(error) => return Err(error.into()),
                Ok(_) => require_identity(&entry.backup, entry.identity, "legacy archive")?,
            }
            match fs::symlink_metadata(&entry.original) {
                Err(error) if error.kind() == io::ErrorKind::NotFound => {}
                Err(error) => return Err(error.into()),
                Ok(_) => return Err(legacy_restore_conflict(entry)),
            }
            restorable.push(entry);
        }
        test_path_projection_barrier("before-legacy-restore-move")?;
        for entry in restorable {
            if let Some(parent) = entry.original.parent() {
                fs::create_dir_all(parent)?;
            }
            IdentityTransactionalMove::new(
                &entry.backup,
                &entry.original,
                entry.identity,
                "legacy archive selected for restoration",
            )
            .execute()
            .map_err(|error| {
                if fs::symlink_metadata(&entry.original).is_ok() {
                    let mut conflict = legacy_restore_conflict(entry);
                    conflict
                        .details
                        .insert("moveError".to_string(), error.to_string());
                    conflict
                } else {
                    error
                }
            })?;
            manifest::sync_parent_directory(&entry.backup)?;
            manifest::sync_parent_directory(&entry.original)?;
        }
        Ok(())
    }
}

#[derive(Debug)]
struct LegacyInstallationArchiveEntry {
    original: PathBuf,
    backup: PathBuf,
    identity: ProjectionFileIdentity,
}

fn legacy_restore_conflict(entry: &LegacyInstallationArchiveEntry) -> CliError {
    let mut error = CliError::new(
        "LEGACY_RESTORE_CONFLICT",
        format!(
            "Cannot restore archived Kast path {} because another path exists; preserved the archive at {}.",
            entry.original.display(),
            entry.backup.display(),
        ),
    );
    error
        .details
        .insert("path".to_string(), entry.original.display().to_string());
    error
        .details
        .insert("backupPath".to_string(), entry.backup.display().to_string());
    error
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SetupArtifact {
    pub role: String,
    pub path: String,
    pub sha256: String,
    pub verified: bool,
}

#[derive(Debug)]
struct ValidatedBundle {
    root: PathBuf,
    manifest: BundleManifest,
    version: BundleVersion,
    cli_relative: PathBuf,
    backend_install_relative: PathBuf,
    release_digest: String,
    manifest_digest: String,
}

#[derive(Debug)]
struct ActivationTargetPaths {
    resolved: manifest::ResolvedKastPaths,
    version_dir: PathBuf,
    current_link: PathBuf,
    previous_link: PathBuf,
    indexer_current_dir: PathBuf,
}
