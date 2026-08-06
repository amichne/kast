impl AgentCommandProjection {
    fn project(path: &Path, target: &Path) -> Result<Self> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }
        match fs::symlink_metadata(path) {
            Err(error) if error.kind() == io::ErrorKind::NotFound => {
                std::os::unix::fs::symlink(target, path)?;
                let identity = projection_file_identity(path)?;
                let projection = Self {
                    path: path.to_path_buf(),
                    created_identity: Some(identity),
                };
                if let Err(mut error) =
                    sync_projection_parent_after(path, "after-agent-create-before-parent-sync")
                {
                    if let Err(barrier_error) =
                        test_path_projection_barrier("before-agent-create-failure-cleanup")
                    {
                        error.details.insert(
                            "agentProjectionCleanupBarrierError".to_string(),
                            barrier_error.to_string(),
                        );
                    }
                    if let Err(rollback_error) = projection.rollback() {
                        error.details.insert(
                            "agentProjectionRollbackError".to_string(),
                            rollback_error.to_string(),
                        );
                    }
                    return Err(error);
                }
                Ok(projection)
            }
            Err(error) => Err(error.into()),
            Ok(_) if exact_projection_matches(path, target) => {
                sync_projection_parent(path)?;
                Ok(Self {
                    path: path.to_path_buf(),
                    created_identity: None,
                })
            }
            Ok(_) => Err(projection_changed_error(
                path,
                "the agent command path is not the expected Kast projection",
            )),
        }
    }

    fn rollback(&self) -> Result<()> {
        let Some(identity) = self.created_identity else {
            return Ok(());
        };
        if projection_file_identity(&self.path).ok() != Some(identity) {
            return Ok(());
        }
        let rollback_path = self
            .path
            .with_extension(format!("kast-rollback-{}", std::process::id()));
        IdentityTransactionalMove::new(
            &self.path,
            &rollback_path,
            identity,
            "agent projection selected for rollback",
        )
        .with_after_validation_barrier("after-agent-rollback-validation")
        .execute()?;
        sync_projection_parent_after(
            &rollback_path,
            "after-agent-rollback-rename-before-parent-sync",
        )?;
        remove_internal_projection_path(
            &rollback_path,
            Some(identity),
            "after-agent-rollback-cleanup-before-parent-sync",
        )
    }
}
