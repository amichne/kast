#[derive(Debug, Clone)]
struct PublishedActivationProjection {
    identity: ProjectionFileIdentity,
    target: PathBuf,
}

impl PublishedActivationProjection {
    fn capture(path: &Path, target: &Path) -> Result<Self> {
        let identity = projection_file_identity(path)?;
        require_owned_projection_unchanged(path, target, identity)?;
        let confirmed_identity = projection_file_identity(path)?;
        if identity != confirmed_identity || !exact_projection_matches(path, target) {
            return Err(activation_rollback_conflict(
                path,
                "the published activation projection changed while setup recorded it",
            ));
        }
        Ok(Self {
            identity,
            target: target.to_path_buf(),
        })
    }

    fn create_at_absent(path: &Path, target: &Path) -> Result<Self> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }
        let temporary = unique_internal_projection_path(path, "activation-create");
        std::os::unix::fs::symlink(target, &temporary)?;
        let published = Self::capture(&temporary, target)?;
        let publication = IdentityTransactionalMove::new(
            &temporary,
            path,
            published.identity,
            "prepared activation projection selected for publication",
        )
        .execute();
        if let Err(mut error) = publication {
            if projection_file_identity(&temporary).ok() == Some(published.identity)
                && let Err(cleanup_error) = remove_internal_projection_path(
                    &temporary,
                    Some(published.identity),
                    "after-activation-create-failure-cleanup-before-parent-sync",
                )
            {
                error.details.insert(
                    "activationPublicationCleanupError".to_string(),
                    cleanup_error.to_string(),
                );
            }
            return Err(error);
        }
        if let Err(mut error) = published.require_at(path) {
            if let Err(rollback_error) = published.remove_at(path) {
                error.details.insert(
                    "activationPublicationRollbackError".to_string(),
                    rollback_error.to_string(),
                );
            }
            return Err(error);
        }
        if let Err(mut error) = sync_projection_parent(path) {
            if let Err(rollback_error) = published.remove_at(path) {
                error.details.insert(
                    "activationPublicationRollbackError".to_string(),
                    rollback_error.to_string(),
                );
            }
            return Err(error);
        }
        Ok(published)
    }

    fn require_at(&self, path: &Path) -> Result<()> {
        require_owned_projection_unchanged(path, &self.target, self.identity)
            .map_err(|_| activation_rollback_conflict(path, "the activation projection changed"))
    }

    fn remove_at(&self, path: &Path) -> Result<()> {
        self.require_at(path)?;
        let quarantine = unique_internal_projection_path(path, "activation-rollback");
        IdentityTransactionalMove::new(
            path,
            &quarantine,
            self.identity,
            "published activation projection selected for rollback",
        )
        .execute()?;
        sync_projection_move_parents(path, &quarantine)?;
        remove_internal_projection_path(
            &quarantine,
            Some(self.identity),
            "after-activation-rollback-cleanup-before-parent-sync",
        )
    }

    fn replace_at(&self, path: &Path, target: &Path) -> Result<Self> {
        self.require_at(path)?;
        let temporary = unique_internal_projection_path(path, "activation-restore");
        manifest::replace_symlink_or_copy(target, &temporary)?;
        let desired = Self::capture(&temporary, target)?;
        let exchange = exchange_control_projection(
            path,
            &temporary,
            &self.target,
            self.identity,
            &desired.target,
            desired.identity,
            ControlReplacementState::DesiredPublished,
            "after-activation-exchange-before-parent-sync",
        );
        if let Err(mut error) = exchange {
            if projection_file_identity(&temporary).ok() == Some(desired.identity)
                && let Err(cleanup_error) = remove_internal_projection_path(
                    &temporary,
                    Some(desired.identity),
                    "after-activation-exchange-failure-cleanup-before-parent-sync",
                )
            {
                error.details.insert(
                    "activationPublicationCleanupError".to_string(),
                    cleanup_error.to_string(),
                );
            }
            return Err(error);
        }
        remove_internal_projection_path(
            &temporary,
            Some(self.identity),
            "after-activation-exchange-cleanup-before-parent-sync",
        )?;
        desired.require_at(path)?;
        Ok(desired)
    }
}

#[derive(Debug, Clone)]
enum ProjectionPublication {
    NotPublished,
    Ambiguous,
    Proven(PublishedActivationProjection),
}

impl ProjectionPublication {
    fn proven_at(&self, path: &Path) -> Result<Option<PublishedActivationProjection>> {
        match self {
            Self::NotPublished => Ok(None),
            Self::Ambiguous => Err(activation_rollback_conflict(
                path,
                "publication completed without stable activation projection evidence",
            )),
            Self::Proven(projection) => {
                projection.require_at(path)?;
                Ok(Some(projection.clone()))
            }
        }
    }
}

fn capture_optional_activation_projection(
    path: &Path,
) -> Result<Option<PublishedActivationProjection>> {
    match fs::symlink_metadata(path) {
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(None),
        Err(error) => Err(error.into()),
        Ok(metadata) if metadata.file_type().is_symlink() => {
            let target = fs::read_link(path)?;
            let target = if target.is_absolute() {
                target
            } else {
                path.parent().unwrap_or_else(|| Path::new(".")).join(target)
            };
            PublishedActivationProjection::capture(path, &target).map(Some)
        }
        Ok(_) => Err(activation_rollback_conflict(
            path,
            "the activation projection is not a symlink",
        )),
    }
}

#[derive(Debug, Clone, Copy)]
struct PublishedBundleCandidate {
    root_identity: ProjectionFileIdentity,
}

impl PublishedBundleCandidate {
    fn capture(path: &Path) -> Result<Self> {
        let root_identity = projection_file_identity(path)?;
        if root_identity.kind != ProjectionFileKind::Directory {
            return Err(activation_rollback_conflict(
                path,
                "the published candidate root is not a directory",
            ));
        }
        Ok(Self { root_identity })
    }
}
